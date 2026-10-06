package com.bragastudio.mobile.coremedia.domain

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.StatFs
import android.util.Log
import android.view.Surface
import androidx.documentfile.provider.DocumentFile
import com.bragastudio.mobile.core.domain.VideoSettings
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Tudo que é criado por [RecordManager.prepareRecording]/[prepareHdrRecording] e imutável
 * durante o take (A10): arquivo, destino SAF, configuração, codecs, surface e a porta do
 * muxer. Capturada em variável local no stop e passada aos jobs — um novo take nunca
 * enxerga (nem apaga) o estado do anterior.
 */
internal class RecordingSession(
    val id: String,
    val file: File,
    val destUri: Uri?,
    val settings: VideoSettings,
    val plan: VideoPlan,
    val hdr: Boolean,
    val videoCodec: MediaCodec,
    val audioCodec: MediaCodec?,
    val inputSurface: Surface,
    val gate: MuxerGate,
    val rebaser: TimelineRebaser,
) {
    private val codecsReleased = AtomicBoolean(false)

    /**
     * Para e libera codecs e Surface. Idempotente e à prova de estado: cada passo tem o seu
     * try/catch, para uma falha não pular os demais (antes um único try/catch deixava o
     * codec de hardware preso se o primeiro stop() falhasse).
     */
    fun releaseCodecs() {
        if (!codecsReleased.compareAndSet(false, true)) return
        releaseCodec(videoCodec)
        audioCodec?.let { releaseCodec(it) }
        try {
            inputSurface.release()
        } catch (e: Exception) {
            Log.w("RecordManager", "Ao liberar o input surface", e)
        }
    }

    private fun releaseCodec(codec: MediaCodec) {
        try {
            codec.stop()
        } catch (e: Exception) {
            // IllegalStateException se nunca iniciou / já parou: esperado em prepare abortado.
        }
        try {
            codec.release()
        } catch (e: Exception) {
            Log.w("RecordManager", "Ao liberar codec", e)
        }
    }
}

/** Estado mutável de um take em andamento (flags e jobs), separado da sessão imutável. */
internal class RunningTake(val session: RecordingSession, val originUs: Long) {
    val fatalReported = AtomicBoolean(false)
    val ended = AtomicBoolean(false)

    @Volatile var stopping = false

    @Volatile var acceptingAudio = true

    /** Fim (em µs, eixo nanoTime) do último bloco de áudio enfileirado — PTS do EOS e piso do próximo bloco. */
    @Volatile var lastAudioEndUs = originUs

    var videoJob: Job? = null
    var audioJob: Job? = null
    var timerJob: Job? = null
    var diskJob: Job? = null
    val audioLock = Any()

    /** Concluído quando a linha IN_PROGRESS foi inserida no Room (a finalização espera por ele). */
    val dbReady = CompletableDeferred<Unit>()
}

@Singleton
class RecordManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val recordingRepository: com.bragastudio.mobile.core.repository.RecordingRepository,
) {
    companion object {
        const val TAG = "RecordManager"

        // Únicos valores usados ao configurar o MediaFormat de áudio. feedAudio confere
        // contra estas constantes e avisa se a captura vier em outro formato.
        private const val ENCODER_SAMPLE_RATE = 48000
        private const val ENCODER_CHANNELS = 2

        private const val DRAIN_TIMEOUT_US = 10_000L
        private const val DRAIN_JOIN_TIMEOUT_MS = 5_000L
        private const val DISK_CHECK_INTERVAL_MS = 2_000L
        private const val FATAL_BACKSTOP_MS = 5_000L
        private const val AUDIO_EOS_MAX_ATTEMPTS = 20
        private const val AUDIO_EOS_DEQUEUE_TIMEOUT_US = 50_000L

        // Watchdog do drain no stop: abandona a cauda após N drenagens vazias seguidas.
        private const val MAX_IDLE_AFTER_STOP = 50
    }

    private val machine = RecStateMachine()

    /** Estado do ciclo de vida do take. A UI desabilita o botão REC quando [RecState.isTransitioning]. */
    val recState: StateFlow<RecState> = machine.state

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    /** True enquanto a pós-gravação (metadados, thumbnail, cópia SAF) roda em segundo plano. */
    private val _pendingFinalizations = MutableStateFlow(0)
    val pendingFinalizations: StateFlow<Int> = _pendingFinalizations.asStateFlow()

    // Canal de erros em texto para a UI (Snackbar). SharedFlow: evento pontual.
    private val _errorEvents = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val errorEvents: SharedFlow<String> = _errorEvents

    // Eventos estruturados (M15/L1): DiskFull, EncoderError, Stopped.
    private val _events = MutableSharedFlow<RecordingEvent>(extraBufferCapacity = 16)
    val events: SharedFlow<RecordingEvent> = _events

    /** Loga E notifica a UI. Centraliza os dois passos para não esquecer um deles. */
    private fun reportError(tag: String, userMessage: String, throwable: Throwable? = null) {
        Log.e(tag, userMessage, throwable)
        _errorEvents.tryEmit(userMessage)
    }

    private val _recordingTimeMs = MutableStateFlow(0L)
    val recordingTimeMs: StateFlow<Long> = _recordingTimeMs.asStateFlow()

    private val scope = CoroutineScope(
        Dispatchers.IO + SupervisorJob() +
            CoroutineExceptionHandler { _, t -> Log.e(TAG, "Exceção não tratada no RecordManager", t) },
    )

    /** Serializa prepare/start/stop/cancel (M17). Nunca é mantido por quem chama de dentro do drain. */
    private val opMutex = Mutex()

    @Volatile private var session: RecordingSession? = null

    @Volatile private var take: RunningTake? = null

    private val encoderProbe: EncoderProbe = MediaCodecListProbe()

    private fun syncRecordingFlag() {
        _isRecording.value = machine.current === RecState.Recording
    }

    // ---------------------------------------------------------------------------------------
    // PREPARE
    // ---------------------------------------------------------------------------------------

    suspend fun prepareRecording(directoryUri: String, videoSettings: VideoSettings, portraitFrame: Boolean = false): Surface? = prepareRecordingInternal(directoryUri, videoSettings, hdrMode = false, portraitFrame = portraitFrame)

    /**
     * Prepara a gravação em HDR real (10-bit HEVC Main10 / BT.2020 HLG) pelo caminho direto
     * câmera→encoder (Caminho A). O Surface devolvido deve ser atrelado à câmera via
     * CaptureDevice.setCameraHdrSurface(…) — NÃO passa pelo GL, portanto o arquivo não
     * carrega LUT/False Color/Zebra. Devolve null se o aparelho não tem encoder Main10.
     */
    suspend fun prepareHdrRecording(directoryUri: String, videoSettings: VideoSettings): Surface? = prepareRecordingInternal(directoryUri, videoSettings, hdrMode = true)

    private suspend fun prepareRecordingInternal(
        directoryUri: String,
        videoSettings: VideoSettings,
        hdrMode: Boolean,
        portraitFrame: Boolean = false,
    ): Surface? = withContext(Dispatchers.IO) {
        opMutex.withLock {
            if (!machine.tryBeginPrepare()) {
                // Toque duplo / take em andamento: ignora em silêncio, sem tocar em nenhum codec.
                Log.w(TAG, "prepare ignorado: estado atual = ${machine.current}")
                return@withLock null
            }
            syncRecordingFlag()

            // Recursos criados até aqui, para o catch liberar exatamente o que existe.
            var file: File? = null
            var destUri: Uri? = null
            var muxer: MediaMuxer? = null
            var videoCodec: MediaCodec? = null
            var audioCodec: MediaCodec? = null
            var inputSurface: Surface? = null

            try {
                val dir = context.getExternalFilesDir(android.os.Environment.DIRECTORY_MOVIES)
                if (dir == null || (!dir.exists() && !dir.mkdirs())) {
                    throw PrepareException("Diretório de gravação inválido ou sem permissão.")
                }
                val available = availableBytes(dir)
                if (SpaceGuard.isLow(available)) {
                    _events.tryEmit(RecordingEvent.DiskFull(available))
                    throw PrepareException(
                        "Espaço livre insuficiente (${available / (1024 * 1024)} MB). Libere ao menos " +
                            "${SpaceGuard.MIN_FREE_BYTES / (1024 * 1024)} MB para gravar.",
                    )
                }

                // Planejamento de codec/fps com o que o aparelho realmente suporta (M17).
                val plan = VideoPlanner.plan(
                    codecPreference = videoSettings.codec,
                    resolutionLabel = videoSettings.resolution,
                    fps = videoSettings.fps,
                    bitrateMbps = videoSettings.bitrateMbps,
                    hdr = hdrMode,
                    probe = encoderProbe,
                    portraitFrame = portraitFrame,
                ) ?: throw PrepareException(
                    if (hdrMode) {
                        "Este aparelho não tem encoder HEVC Main10 (HDR) para ${videoSettings.resolution}."
                    } else {
                        "Nenhum encoder deste aparelho aceita ${videoSettings.resolution}@${videoSettings.fps} (${videoSettings.codec})."
                    },
                )
                plan.notices.forEach { notice ->
                    Log.w(TAG, notice)
                    _errorEvents.tryEmit(notice)
                }

                val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
                // Sufixo _HDR_ marca takes 10-bit; milissegundos evitam colisão de nome se o
                // fallback preparar um segundo arquivo (SDR) logo em seguida.
                val fileName = if (hdrMode) "BDSM_HDR_$timestamp.mp4" else "BDSM_$timestamp.mp4"
                val localFile = File(dir, fileName)
                file = localFile

                destUri = directoryUri.toUriOrNull()?.let { treeUri ->
                    val tree = DocumentFile.fromTreeUri(context, treeUri)
                        ?: throw PrepareException("Pasta de gravação inválida.")
                    if (!tree.canWrite()) throw PrepareException("Pasta de gravação sem permissão de escrita.")
                    tree.createFile("video/mp4", fileName)?.uri
                        ?: throw PrepareException("Não foi possível criar o arquivo na pasta de gravação.")
                }

                muxer = MediaMuxer(localFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

                // ---- Vídeo ----
                val videoFormat = MediaFormat.createVideoFormat(plan.mime, plan.width, plan.height).apply {
                    setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                    setInteger(MediaFormat.KEY_BIT_RATE, plan.bitrateBps)
                    setInteger(MediaFormat.KEY_FRAME_RATE, plan.fps)
                    setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                    val profile = plan.profile ?: if (hdrMode) MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10 else null
                    if (profile != null) {
                        setInteger(MediaFormat.KEY_PROFILE, profile)
                        plan.level?.let { setInteger(MediaFormat.KEY_LEVEL, it) }
                    }
                    if (hdrMode) {
                        // HEVC Main10 + metadados BT.2020/HLG: o MediaMuxer grava colr/mdcv/clli no MP4
                        // a partir destas keys e o player abre a trilha como HDR.
                        setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT2020)
                        setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_HLG)
                        setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED)
                        setByteBuffer(MediaFormat.KEY_HDR_STATIC_INFO, buildHlgStaticInfo())
                    }
                }
                // Atribui ANTES de configure(): se configure falhar, o catch libera o codec.
                videoCodec = createEncoder(plan)
                videoCodec.configure(videoFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                inputSurface = videoCodec.createInputSurface()

                // ---- Áudio ---- (falha aqui não impede o take: segue só com vídeo)
                audioCodec = try {
                    val audioFormat = MediaFormat.createAudioFormat(
                        MediaFormat.MIMETYPE_AUDIO_AAC, ENCODER_SAMPLE_RATE, ENCODER_CHANNELS,
                    ).apply {
                        setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                        setInteger(MediaFormat.KEY_BIT_RATE, 128_000)
                        setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
                    }
                    MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).also {
                        try {
                            it.configure(audioFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                        } catch (e: Exception) {
                            it.release()
                            throw e
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Encoder de áudio indisponível — gravando só vídeo", e)
                    _errorEvents.tryEmit("Encoder de áudio indisponível: o take será gravado sem som.")
                    null
                }

                val surface: Surface = inputSurface
                    ?: throw PrepareException("O encoder não forneceu o surface de entrada.")
                val rebaser = TimelineRebaser()
                // Teto da fila pré-muxer: ~2 s do bitrate pedido, entre 8 e 32 MB.
                val queueBytes = (plan.bitrateBps / 8L * 2L).coerceIn(8L * 1024 * 1024, 32L * 1024 * 1024)
                val currentVideoCodec = videoCodec
                val gate = MuxerGate(
                    muxer = muxer,
                    audioExpected = audioCodec != null,
                    rebaser = rebaser,
                    queue = PreMuxerQueue(maxDurationUs = 2_000_000L, maxBytes = queueBytes),
                    audioTimeoutMs = TrackSyncPolicy.DEFAULT_AUDIO_TIMEOUT_MS,
                    onAudioMissing = {
                        reportError(
                            TAG,
                            "Sem áudio do microfone: o take está sendo gravado apenas com vídeo.",
                        )
                    },
                    onKeyFrameNeeded = { requestSyncFrame(currentVideoCodec) },
                )

                session = RecordingSession(
                    id = java.util.UUID.randomUUID().toString(),
                    file = localFile,
                    destUri = destUri,
                    settings = videoSettings,
                    plan = plan,
                    hdr = hdrMode,
                    videoCodec = videoCodec,
                    audioCodec = audioCodec,
                    inputSurface = surface,
                    gate = gate,
                    rebaser = rebaser,
                )
                surface
            } catch (e: Throwable) {
                if (e is CancellationException) {
                    cleanupFailedPrepare(file, destUri, muxer, videoCodec, audioCodec, inputSurface)
                    machine.abortPrepare()
                    syncRecordingFlag()
                    throw e
                }
                val message = (e as? PrepareException)?.message
                    ?: "Não foi possível preparar a gravação (codec indisponível ou configuração inválida)."
                reportError(TAG, message, e)
                cleanupFailedPrepare(file, destUri, muxer, videoCodec, audioCodec, inputSurface)
                session = null
                machine.abortPrepare()
                syncRecordingFlag()
                null
            }
        }
    }

    /** Libera exatamente o que foi criado antes da falha. Cada passo isolado. */
    private fun cleanupFailedPrepare(
        file: File?,
        destUri: Uri?,
        muxer: MediaMuxer?,
        videoCodec: MediaCodec?,
        audioCodec: MediaCodec?,
        inputSurface: Surface?,
    ) {
        for (codec in listOf(videoCodec, audioCodec)) {
            if (codec == null) continue
            try {
                codec.release()
            } catch (e: Exception) {
                Log.w(TAG, "Ao liberar codec no cleanup do prepare", e)
            }
        }
        try {
            inputSurface?.release()
        } catch (e: Exception) {
            Log.w(TAG, "Ao liberar surface no cleanup do prepare", e)
        }
        try {
            muxer?.release()
        } catch (e: Exception) {
            Log.w(TAG, "Ao liberar muxer no cleanup do prepare", e)
        }
        deleteArtifacts(file, destUri)
    }

    private fun deleteArtifacts(file: File?, destUri: Uri?) {
        try {
            if (file != null && file.exists()) file.delete()
        } catch (e: Exception) {
            Log.w(TAG, "Erro ao remover temp abortado", e)
        }
        if (destUri != null) {
            try {
                DocumentFile.fromSingleUri(context, destUri)?.delete()
            } catch (e: Exception) {
                Log.w(TAG, "Erro ao remover destino abortado", e)
            }
        }
    }

    private fun createEncoder(plan: VideoPlan): MediaCodec = try {
        MediaCodec.createByCodecName(plan.encoderName)
    } catch (e: Exception) {
        Log.w(TAG, "createByCodecName(${plan.encoderName}) falhou; usando createEncoderByType", e)
        MediaCodec.createEncoderByType(plan.mime)
    }

    /**
     * Cancela uma preparação que não foi para frente (ex.: fallback quando a HAL não aceita
     * o par HLG10 + preview SDR). Libera codecs e muxer e remove o arquivo temporário de
     * 0 bytes. Nunca mexe em um take em andamento.
     */
    suspend fun cancelPreparation() {
        withContext(Dispatchers.IO + NonCancellable) {
            opMutex.withLock {
                if (machine.current !== RecState.Preparing) return@withLock
                val s = session
                session = null
                if (s != null) {
                    s.releaseCodecs()
                    s.gate.abort()
                    deleteArtifacts(s.file, s.destUri)
                }
                machine.abortPrepare()
                syncRecordingFlag()
            }
        }
    }

    // ---------------------------------------------------------------------------------------
    // START
    // ---------------------------------------------------------------------------------------

    /** @return true se o take começou; false se nada foi iniciado (recursos já liberados). */
    suspend fun startRecording(): Boolean = withContext(Dispatchers.IO) {
        opMutex.withLock {
            val s = session
            if (s == null || machine.current !== RecState.Preparing) {
                Log.w(TAG, "startRecording ignorado: estado = ${machine.current}")
                return@withLock false
            }

            try {
                s.videoCodec.start()
                s.audioCodec?.start()
            } catch (e: Exception) {
                reportError(TAG, "Falha ao iniciar a gravação. Verifique o armazenamento e tente novamente.", e)
                s.releaseCodecs()
                s.gate.abort()
                deleteArtifacts(s.file, s.destUri)
                session = null
                machine.abortPrepare()
                syncRecordingFlag()
                return@withLock false
            }

            // Origem comum do eixo de tempo (A10): vídeo (surface/nanoTime) e áudio usam o mesmo relógio.
            val originUs = System.nanoTime() / 1_000L
            s.rebaser.anchor(originUs)
            s.gate.arm()
            val t = RunningTake(s, originUs)
            take = t
            loggedAudioFormatMismatch = false
            _recordingTimeMs.value = 0L
            machine.tryStart()
            syncRecordingFlag()

            // Registro IN_PROGRESS no Room (assíncrono; a finalização espera por dbReady).
            scope.launch {
                try {
                    recordingRepository.insertRecording(
                        com.bragastudio.mobile.core.database.RecordingEntity(
                            id = s.id,
                            fileName = s.file.name,
                            filePath = s.file.absolutePath,
                            thumbnailPath = "",
                            durationMs = 0L,
                            sizeBytes = 0L,
                            resolution = s.settings.resolution,
                            frameRate = s.plan.fps,
                            codec = if (s.plan.mime == VideoPlanner.MIME_HEVC) "H.265" else "H.264",
                            bitrate = s.settings.bitrateMbps,
                            audioCodec = "AAC",
                            audioSampleRate = ENCODER_SAMPLE_RATE,
                            createdAt = System.currentTimeMillis(),
                            isFavorite = false,
                            status = "IN_PROGRESS",
                            projectTag = null,
                        ),
                    )
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    Log.e(TAG, "Falha ao inserir registro IN_PROGRESS", e)
                } finally {
                    t.dbReady.complete(Unit)
                }
            }

            val startTime = System.currentTimeMillis()
            t.timerJob = scope.launch(Dispatchers.Main) {
                while (isActive && !t.ended.get()) {
                    delay(30)
                    _recordingTimeMs.value = System.currentTimeMillis() - startTime
                }
            }
            t.diskJob = scope.launch {
                while (isActive && !t.ended.get()) {
                    delay(DISK_CHECK_INTERVAL_MS)
                    val avail = availableBytes(s.file.parentFile)
                    if (SpaceGuard.isLow(avail)) {
                        onFatal(
                            t,
                            RecordingEvent.DiskFull(avail),
                            "Espaço em disco acabando (${avail / (1024 * 1024)} MB). A gravação foi finalizada para preservar o arquivo.",
                            null,
                            StopReason.DISK_FULL,
                        )
                        break
                    }
                }
            }
            t.videoJob = scope.launch { drainEncoder(t, isVideo = true) }
            t.audioJob = s.audioCodec?.let { scope.launch { drainEncoder(t, isVideo = false) } }
            true
        }
    }

    // ---------------------------------------------------------------------------------------
    // AUDIO
    // ---------------------------------------------------------------------------------------

    // Aviso de mismatch é logado só uma vez por gravação.
    @Volatile private var loggedAudioFormatMismatch = false

    fun feedAudio(pcmData: ByteArray, numSamples: Int, numChannels: Int, sampleRate: Int) {
        val t = take ?: return
        if (!t.acceptingAudio || machine.current !== RecState.Recording) return
        val codec = t.session.audioCodec ?: return

        // sampleRate/numChannels precisam bater com o encoder (ENCODER_*); do contrário o AAC
        // sai com pitch/velocidade errados sem erro visível.
        if (!loggedAudioFormatMismatch &&
            (sampleRate != ENCODER_SAMPLE_RATE || numChannels != ENCODER_CHANNELS)
        ) {
            loggedAudioFormatMismatch = true
            Log.w(
                TAG,
                "Áudio recebido em ${sampleRate}Hz/${numChannels}ch, mas o encoder está configurado para " +
                    "${ENCODER_SAMPLE_RATE}Hz/${ENCODER_CHANNELS}ch. O arquivo pode sair com pitch/velocidade incorretos.",
            )
        }

        synchronized(t.audioLock) {
            if (!t.acceptingAudio) return
            try {
                // PTS no eixo System.nanoTime (mesmo do vídeo): o bloco acabou de ser lido,
                // então ele COMEÇOU há "duração do bloco" atrás. Nunca recua antes do fim do
                // bloco anterior (jitter do AudioRecord não pode sobrepor amostras).
                val nowUs = System.nanoTime() / 1_000L
                val totalDurationUs = PcmMath.durationUs(pcmData.size, ENCODER_CHANNELS, ENCODER_SAMPLE_RATE)
                var pts = maxOf(nowUs - totalDurationUs, t.lastAudioEndUs)
                var offset = 0
                while (offset < pcmData.size) {
                    val index = codec.dequeueInputBuffer(10_000)
                    if (index < 0) break // encoder saturado: descarta o resto do bloco (nunca bloqueia o AudioRecord)
                    val input = codec.getInputBuffer(index)
                    if (input == null) {
                        codec.queueInputBuffer(index, 0, 0, pts, 0)
                        break
                    }
                    input.clear()
                    // Fatia pela capacity real do buffer (antes: put() sem checar → overflow).
                    val frameBytes = PcmMath.frameBytes(ENCODER_CHANNELS)
                    val maxSlice = (input.capacity() / frameBytes) * frameBytes
                    val sliceBytes = minOf(pcmData.size - offset, maxSlice)
                    if (sliceBytes <= 0) {
                        codec.queueInputBuffer(index, 0, 0, pts, 0)
                        break
                    }
                    try {
                        input.put(pcmData, offset, sliceBytes)
                        codec.queueInputBuffer(index, 0, sliceBytes, pts, 0)
                    } catch (e: Exception) {
                        // Devolve o índice ao codec (buffer vazio) para ele não vazar.
                        try {
                            codec.queueInputBuffer(index, 0, 0, pts, 0)
                        } catch (inner: Exception) {
                            Log.w(TAG, "Não foi possível devolver o buffer de áudio", inner)
                        }
                        throw e
                    }
                    pts += PcmMath.durationUs(sliceBytes, ENCODER_CHANNELS, ENCODER_SAMPLE_RATE)
                    offset += sliceBytes
                }
                t.lastAudioEndUs = pts
            } catch (e: Exception) {
                Log.e(TAG, "Erro ao alimentar áudio", e)
            }
        }
    }

    /**
     * Enfileira um frame vazio com BUFFER_FLAG_END_OF_STREAM no codec de áudio para ele drenar a
     * cauda antes do stop(). Usa o fim real do último bloco ([RunningTake.lastAudioEndUs]).
     */
    private fun signalAudioEndOfStream(t: RunningTake) {
        val codec = t.session.audioCodec ?: return
        synchronized(t.audioLock) {
            t.acceptingAudio = false
            for (attempt in 1..AUDIO_EOS_MAX_ATTEMPTS) {
                try {
                    val index = codec.dequeueInputBuffer(AUDIO_EOS_DEQUEUE_TIMEOUT_US)
                    if (index >= 0) {
                        codec.queueInputBuffer(index, 0, 0, t.lastAudioEndUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        Log.d(TAG, "EOS de áudio enfileirado na tentativa $attempt")
                        return
                    }
                } catch (e: IllegalStateException) {
                    Log.w(TAG, "Codec de áudio indisponível para EOS", e)
                    return
                } catch (e: Exception) {
                    Log.e(TAG, "Erro ao sinalizar fim do áudio", e)
                    return
                }
            }
            Log.w(TAG, "EOS de áudio não enfileirado após $AUDIO_EOS_MAX_ATTEMPTS tentativas")
        }
    }

    // ---------------------------------------------------------------------------------------
    // DRAIN
    // ---------------------------------------------------------------------------------------

    private fun drainEncoder(t: RunningTake, isVideo: Boolean) {
        val s = t.session
        val codec = (if (isVideo) s.videoCodec else s.audioCodec) ?: return
        val gate = s.gate
        val bufferInfo = MediaCodec.BufferInfo()
        var isEos = false
        var idleAfterStop = 0

        while (!isEos && !t.fatalReported.get() && !t.ended.get()) {
            val status = try {
                codec.dequeueOutputBuffer(bufferInfo, DRAIN_TIMEOUT_US)
            } catch (e: Exception) {
                onFatal(
                    t, RecordingEvent.EncoderError("O encoder de ${if (isVideo) "vídeo" else "áudio"} falhou: ${e.message}"),
                    "O encoder de ${if (isVideo) "vídeo" else "áudio"} falhou durante a gravação. O arquivo foi finalizado.", e,
                    StopReason.ENCODER_ERROR,
                )
                break
            }

            when {
                status == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (isVideo) {
                        try {
                            gate.poll() // dispara o timeout do áudio, se for o caso
                        } catch (e: Exception) {
                            onFatal(
                                t, RecordingEvent.EncoderError("Falha ao iniciar o arquivo: ${e.message}"),
                                "Falha ao iniciar o arquivo de gravação.", e, StopReason.ENCODER_ERROR,
                            )
                            break
                        }
                    }
                    if (t.stopping && ++idleAfterStop >= MAX_IDLE_AFTER_STOP) isEos = true
                }

                status == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    try {
                        gate.onFormat(isVideo, codec.outputFormat)
                    } catch (e: Exception) {
                        onFatal(
                            t, RecordingEvent.EncoderError("Falha ao iniciar o arquivo: ${e.message}"),
                            "Falha ao iniciar o arquivo de gravação.", e, StopReason.ENCODER_ERROR,
                        )
                        break
                    }
                }

                status >= 0 -> {
                    try {
                        val encoded = codec.getOutputBuffer(status)
                        if (encoded != null) {
                            if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                                bufferInfo.size = 0 // config já vai no formato da trilha
                            }
                            if (bufferInfo.size > 0) gate.onSample(isVideo, encoded, bufferInfo)
                            idleAfterStop = 0
                        }
                        if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) isEos = true
                    } catch (e: Exception) {
                        // Escrita falhou (disco cheio / I/O): propaga como erro fatal, não engole.
                        val avail = availableBytes(s.file.parentFile)
                        if (SpaceGuard.isLow(avail)) {
                            onFatal(
                                t, RecordingEvent.DiskFull(avail),
                                "Sem espaço em disco: a gravação foi finalizada.", e, StopReason.DISK_FULL,
                            )
                        } else {
                            onFatal(
                                t, RecordingEvent.EncoderError("Falha ao gravar o arquivo: ${e.message}"),
                                "Falha ao gravar o arquivo de vídeo. A gravação foi finalizada.", e, StopReason.ENCODER_ERROR,
                            )
                        }
                        break
                    } finally {
                        // Sempre devolve o buffer ao codec (antes um erro antes desta linha o vazava).
                        try {
                            codec.releaseOutputBuffer(status, false)
                        } catch (e: Exception) {
                            Log.w(TAG, "releaseOutputBuffer falhou", e)
                        }
                    }
                }
            }
        }
    }

    /**
     * Erro irrecuperável durante o take. Emite o evento (a UI avisa) e agenda a finalização:
     * o MediaGraph coleta [events] e chama o stop dele (que antes desanexa as surfaces do GL).
     * O backstop abaixo garante que o arquivo é finalizado mesmo se ninguém reagir.
     */
    private fun onFatal(
        t: RunningTake,
        event: RecordingEvent,
        userMessage: String,
        throwable: Throwable?,
        reason: StopReason,
    ) {
        if (t.ended.get() || !t.fatalReported.compareAndSet(false, true)) return
        reportError(TAG, userMessage, throwable)
        _events.tryEmit(event)
        scope.launch {
            delay(FATAL_BACKSTOP_MS)
            if (take === t && !t.ended.get()) {
                Log.w(TAG, "Backstop: ninguém finalizou o take após erro fatal; finalizando")
                stopRecording(reason)
            }
        }
    }

    // ---------------------------------------------------------------------------------------
    // STOP
    // ---------------------------------------------------------------------------------------

    /**
     * Finaliza o take. Retorna assim que o arquivo MP4 local está fechado: a pós-gravação
     * (metadados, thumbnail, cópia para a pasta SAF) corre em segundo plano com o registro em
     * COPYING — o STOP não fica preso na cópia (A6).
     */
    suspend fun stopRecording(reason: StopReason = StopReason.USER) {
        if (!machine.tryBeginStop()) return
        syncRecordingFlag()

        // Toda a finalização é I/O bloqueante: roda em IO e em NonCancellable para um
        // cancelamento do chamador (UI) não deixar o arquivo pela metade.
        withContext(Dispatchers.IO + NonCancellable) {
            opMutex.withLock {
                val t = take
                val s = t?.session
                if (t == null || s == null) {
                    machine.finishStop()
                    syncRecordingFlag()
                    return@withLock
                }
                try {
                    finalizeTake(t, reason)
                } finally {
                    t.ended.set(true)
                    take = null
                    session = null
                    _recordingTimeMs.value = 0L
                    machine.finishStop()
                    syncRecordingFlag()
                }
            }
        }
    }

    private suspend fun finalizeTake(t: RunningTake, reason: StopReason) {
        val s = t.session
        t.stopping = true
        t.timerJob?.cancel()
        t.diskJob?.cancel()

        // 1) fecha a entrada: EOS de vídeo (surface) e de áudio.
        try {
            s.videoCodec.signalEndOfInputStream()
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao sinalizar fim de stream", e)
        }
        signalAudioEndOfStream(t)

        // 2) drena a cauda com timeout (um drain preso não pode segurar o stop para sempre).
        val joined = withTimeoutOrNull(DRAIN_JOIN_TIMEOUT_MS) {
            t.videoJob?.join()
            t.audioJob?.join()
            true
        }
        if (joined == null) {
            Log.w(TAG, "Drain não terminou em ${DRAIN_JOIN_TIMEOUT_MS}ms; cancelando")
            t.videoJob?.cancel()
            t.audioJob?.cancel()
        }
        t.ended.set(true)

        // 3) fecha o arquivo e 4) libera os codecs (cada passo isolado).
        val outcome = s.gate.finish()
        s.releaseCodecs()

        when (outcome) {
            MuxOutcome.OK -> Unit

            MuxOutcome.CORRUPT -> reportError(
                TAG, "Erro ao finalizar o arquivo de gravação — o vídeo pode estar corrompido ou incompleto.",
            )

            MuxOutcome.NEVER_STARTED -> {
                reportError(TAG, "A gravação não gerou nenhum quadro de vídeo utilizável e foi descartada.")
                deleteArtifacts(s.file, s.destUri)
            }
        }

        _events.tryEmit(
            RecordingEvent.Stopped(
                reason,
                if (outcome == MuxOutcome.NEVER_STARTED) null else s.file.absolutePath,
            ),
        )
        launchPostProcess(t, outcome)
    }

    /**
     * Pós-gravação assíncrona: metadados/thumbnail → (se houver pasta SAF) status COPYING e
     * cópia → status final. Usa só a sessão capturada, então um novo take pode começar em paralelo.
     */
    private fun launchPostProcess(t: RunningTake, outcome: MuxOutcome) {
        val s = t.session
        _pendingFinalizations.update { it + 1 }
        scope.launch {
            try {
                t.dbReady.await()
                if (outcome == MuxOutcome.NEVER_STARTED) {
                    runCatching { recordingRepository.updateStatus(s.id, "CORRUPTED") }
                    return@launch
                }

                val hasDestination = s.destUri != null
                val file = s.file
                var duration = 0L
                var thumbPath = ""
                if (file.exists()) {
                    val meta = extractMetadata(file, s.id)
                    duration = meta.first
                    thumbPath = meta.second
                }

                // Metadados primeiro (a galeria já mostra duração/thumb), status COPYING durante a cópia.
                val firstStatus = when {
                    outcome == MuxOutcome.CORRUPT -> "CORRUPTED"
                    hasDestination -> "COPYING"
                    else -> "COMPLETED"
                }
                updateEntity(s.id) {
                    it.copy(
                        status = firstStatus,
                        durationMs = duration,
                        sizeBytes = file.length(),
                        thumbnailPath = thumbPath,
                    )
                }

                if (hasDestination && outcome != MuxOutcome.CORRUPT) {
                    val copied = copyToDestination(s)
                    updateEntity(s.id) {
                        it.copy(
                            status = if (copied) "COMPLETED" else "CORRUPTED",
                            contentUri = s.destUri?.toString(),
                        )
                    }
                }

                // Destino "Galeria": cópia no MediaStore em segundo plano, já com o take fechado
                // e o registro COMPLETED (o stop nunca espera por ela). Falha não invalida o take.
                if (file.exists() && GalleryExportRules.shouldAutoExport(
                        hasFolderDestination = hasDestination,
                        saveToGallery = s.settings.saveToGallery,
                        corrupt = outcome == MuxOutcome.CORRUPT,
                        sdkInt = Build.VERSION.SDK_INT,
                    )
                ) {
                    exportToGallery(s)
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.e(TAG, "Erro na pós-gravação de ${s.file.name}", e)
            } finally {
                _pendingFinalizations.update { (it - 1).coerceAtLeast(0) }
            }
        }
    }

    private suspend fun updateEntity(
        id: String,
        transform: (com.bragastudio.mobile.core.database.RecordingEntity) -> com.bragastudio.mobile.core.database.RecordingEntity,
    ) {
        val current = recordingRepository.getRecordingById(id) ?: return
        recordingRepository.updateRecording(transform(current))
    }

    /**
     * Exporta o take para a Galeria e guarda a Uri do MediaStore em `contentUri` do registro
     * (mesmo campo da cópia SAF; não há migração de banco). Falha avisa a UI, mantém o original.
     */
    private suspend fun exportToGallery(s: RecordingSession) {
        when (val result = MediaStoreExporter.export(context, s.file)) {
            is MediaStoreExportResult.Success ->
                updateEntity(s.id) { it.copy(contentUri = result.uri.toString()) }

            is MediaStoreExportResult.Failure ->
                reportError(
                    TAG,
                    "A gravação foi finalizada, mas não pôde ser salva na Galeria (${result.message}). O arquivo continua no app.",
                )
        }
    }

    private fun copyToDestination(s: RecordingSession): Boolean {
        val dest = s.destUri ?: return true
        return try {
            context.contentResolver.openOutputStream(dest, "w")?.use { output ->
                s.file.inputStream().use { input -> input.copyTo(output, 256 * 1024) }
            } ?: throw IllegalStateException("Não foi possível abrir o destino da gravação")
            true
        } catch (e: Exception) {
            reportError(TAG, "A gravação foi finalizada, mas não pôde ser copiada para a pasta escolhida.", e)
            false
        }
    }

    /** Duração (ms) e caminho do thumbnail. O retriever é liberado SEMPRE (API 26: não é AutoCloseable). */
    private fun extractMetadata(file: File, id: String): Pair<Long, String> {
        var duration = 0L
        var thumbPath = ""
        val retriever = android.media.MediaMetadataRetriever()
        try {
            retriever.setDataSource(file.absolutePath)
            duration = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val bitmap = retriever.getFrameAtTime(0, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            if (bitmap != null) {
                val thumbDir = File(context.getExternalFilesDir(null), "thumbnails")
                if (!thumbDir.exists()) thumbDir.mkdirs()
                val thumb = File(thumbDir, "thumb_$id.jpg")
                java.io.FileOutputStream(thumb).use { out ->
                    bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, out)
                    out.flush()
                }
                thumbPath = thumb.absolutePath
            }
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao extrair metadados", e)
        } finally {
            try {
                retriever.release()
            } catch (e: Exception) {
                Log.w(TAG, "Erro ao liberar retriever", e)
            }
        }
        return duration to thumbPath
    }

    // ---------------------------------------------------------------------------------------
    // UTIL
    // ---------------------------------------------------------------------------------------

    private fun requestSyncFrame(codec: MediaCodec) {
        try {
            codec.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) })
        } catch (e: Exception) {
            Log.w(TAG, "Não foi possível pedir sync frame", e)
        }
    }

    private fun availableBytes(dir: File?): Long {
        if (dir == null) return Long.MAX_VALUE
        return try {
            StatFs(dir.path).availableBytes
        } catch (e: Exception) {
            Long.MAX_VALUE // não conseguiu medir: não bloqueia a gravação
        }
    }

    /**
     * Metadado estático HDR (BT.2020 / mastering 1000 nits) para a key
     * MediaFormat.KEY_HDR_STATIC_INFO — o MediaMuxer converte nos boxes mdcv/clli do MP4.
     * (A10: verificar em aparelho se o layout de 28 bytes é o esperado pelo framework.)
     */
    private fun buildHlgStaticInfo(): java.nio.ByteBuffer {
        val info = java.nio.ByteBuffer.allocate(28)
        info.order(java.nio.ByteOrder.LITTLE_ENDIAN)

        // Primárias BT.2020 em unidades de 0.00002 (x/y × 50000); toShort() faz o wrap correto.
        info.putShort(8500.toShort())    // Gx 0.170
        info.putShort(39850.toShort())   // Gy 0.797
        info.putShort(6550.toShort())    // Bx 0.131
        info.putShort(2300.toShort())    // By 0.046
        info.putShort(35400.toShort())   // Rx 0.708
        info.putShort(14600.toShort())   // Ry 0.292
        info.putShort(15635.toShort())   // Wx 0.3127
        info.putShort(16450.toShort())   // Wy 0.3290

        // Luminância do display de mastering em unidades de 0.0001 cd/m².
        info.putInt(10_000_000)  // Max = 1000 nits
        info.putInt(100)         // Min = 0.01 nits

        info.putShort(1000)      // MaxCLL = 1000 nits
        info.putShort(400)       // MaxFALL = 400 nits
        return info
    }

    private fun String.toUriOrNull(): Uri? = takeIf { it.isNotBlank() }?.let(Uri::parse)

    /** Erro de preparação com mensagem já pronta para o usuário. */
    private class PrepareException(message: String) : Exception(message)

    /** Implementação de [EncoderProbe] sobre MediaCodecList (REGULAR_CODECS). */
    private class MediaCodecListProbe : EncoderProbe {
        override fun find(mime: String, width: Int, height: Int, fps: Int, hdr10: Boolean): EncoderInfo? {
            val infos = try {
                MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
            } catch (e: Exception) {
                Log.w(TAG, "MediaCodecList indisponível", e)
                return null
            }
            val candidates = infos
                .filter { it.isEncoder && it.supportedTypes.any { type -> type.equals(mime, ignoreCase = true) } }
                .sortedBy { if (isSoftware(it)) 1 else 0 } // hardware primeiro
            for (info in candidates) {
                try {
                    val caps = info.getCapabilitiesForType(mime)
                    val video = caps.videoCapabilities ?: continue
                    if (!caps.colorFormats.contains(MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)) continue
                    if (!video.areSizeAndRateSupported(width, height, fps.toDouble())) continue
                    val levels = caps.profileLevels.map { it.profile to it.level }
                    if (hdr10 && levels.none { it.first == VideoPlanner.HEVC_PROFILE_MAIN10 }) continue
                    return EncoderInfo(info.name, levels, video.bitrateRange.upper)
                } catch (e: IllegalArgumentException) {
                    continue // tipo não suportado por este codec
                }
            }
            return null
        }

        private fun isSoftware(info: MediaCodecInfo): Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            info.isSoftwareOnly
        } else {
            info.name.startsWith("OMX.google.") || info.name.startsWith("c2.android.")
        }
    }
}
