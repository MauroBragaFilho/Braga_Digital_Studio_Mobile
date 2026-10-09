package com.bragastudio.mobile.corecapture.domain

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.core.app.ActivityCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.CopyOnWriteArrayList
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.log10
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/** Níveis normalizados 0..1 (RMS mapeado de -60..0 dBFS). API original, mantida. */
data class AudioLevels(
    val leftLevel: Float = 0f,
    val rightLevel: Float = 0f,
)

/** Formato REAL que o AudioRecord está entregando (pode diferir do pedido). */
data class AudioStreamInfo(
    val sampleRate: Int,
    /** Canais entregues aos consumidores (mono é espelhado para estéreo). */
    val channels: Int,
    /** Canais capturados de fato pelo AudioRecord. */
    val capturedChannels: Int,
    val routedDeviceType: Int? = null,
    val routedDeviceName: String? = null,
)

/** Consumidor de PCM 16-bit little-endian intercalado. Chamado na thread de leitura: não bloqueie. */
fun interface AudioSink {
    /**
     * [pcmData] é REUTILIZADO entre chamadas (copie se for guardar além do retorno);
     * seu tamanho é exatamente numSamplesPerChannel * numChannels * 2 bytes.
     */
    fun onAudio(pcmData: ByteArray, numSamplesPerChannel: Int, numChannels: Int, sampleRate: Int)
}

@Singleton
class AudioCaptureService @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private companion object {
        const val TAG = "AudioCapture"
        const val LIFECYCLE_TAG = "CaptureLifecycle"
        const val REQUESTED_SAMPLE_RATE = 48000
        const val BLOCK_MS = 20
        const val MAX_RESTARTS = 3
        const val MAX_CONSECUTIVE_READ_ERRORS = 10
    }

    private val _audioLevels = MutableStateFlow(AudioLevels())
    val audioLevels: StateFlow<AudioLevels> = _audioLevels.asStateFlow()

    /** Pico/peak-hold/clip por canal (M32), ao lado do [audioLevels] RMS. */
    private val _audioPeaks = MutableStateFlow(AudioMeterResult())
    val audioPeaks: StateFlow<AudioMeterResult> = _audioPeaks.asStateFlow()

    private val _streamInfo = MutableStateFlow<AudioStreamInfo?>(null)

    /** Formato real em uso; null quando não está capturando. */
    val streamInfo: StateFlow<AudioStreamInfo?> = _streamInfo.asStateFlow()

    private val _isCapturing = MutableStateFlow(false)
    val isCapturing: StateFlow<Boolean> = _isCapturing.asStateFlow()

    @Volatile private var audioRecord: AudioRecord? = null
    private var captureJob: Job? = null
    private val coroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val captureMutex = Mutex()
    private val meter = AudioMeter()

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var scoActive = false
    private var communicationDeviceSet = false

    // Callback único (API original, compatível). Prefira [addAudioSink].
    var onAudioBufferAvailable: ((pcmData: ByteArray, numSamplesPerChannel: Int, numChannels: Int, sampleRate: Int) -> Unit)? = null

    private val sinks = CopyOnWriteArrayList<AudioSink>()

    fun addAudioSink(sink: AudioSink) {
        sinks.addIfAbsent(sink)
    }

    fun removeAudioSink(sink: AudioSink) {
        sinks.remove(sink)
    }

    // ------------------------------------------------------------------
    // Início / parada
    // ------------------------------------------------------------------

    // RECORD_AUDIO é verificada com checkSelfPermission logo no início do launch.
    @android.annotation.SuppressLint("MissingPermission")
    fun startCapture(deviceInfo: AudioDeviceInfo?) {
        // UNDISPATCHED: o pedido do mutex entra na fila (FIFO) na ordem da chamada, então
        // start/stop seguidos nunca se invertem (evita mic aberto depois de um stop).
        coroutineScope.launch(start = CoroutineStart.UNDISPATCHED) {
            captureMutex.withLock {
                stopCaptureInternal()

                if (ActivityCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                    Log.w(TAG, "RECORD_AUDIO não concedida — captura de áudio não iniciada")
                    return@launch
                }

                try {
                    routeBluetoothIfNeeded(deviceInfo)

                    val record = createAndStartRecord(deviceInfo)
                    if (record == null) {
                        Log.e(TAG, "Não foi possível inicializar o AudioRecord")
                        releaseRouting()
                        return@launch
                    }
                    audioRecord = record
                    _isCapturing.value = true
                    Log.i(LIFECYCLE_TAG, "AudioRecord: aberto")

                    captureJob = launch {
                        var current: AudioRecord? = record
                        var restarts = 0
                        try {
                            while (current != null && currentCoroutineContext().isActive) {
                                val rec: AudioRecord = current
                                val outcome = readLoop(rec)
                                if (outcome != ReadOutcome.DEAD_OBJECT || !currentCoroutineContext().isActive) break

                                // ERROR_DEAD_OBJECT (ex.: servidor de áudio reiniciou / rota mudou): recria o AudioRecord.
                                Log.w(TAG, "AudioRecord morto (DEAD_OBJECT) — recriando (tentativa ${restarts + 1}/$MAX_RESTARTS)")
                                runCatching { rec.stop() }
                                rec.release()
                                audioRecord = null
                                current = null
                                if (++restarts > MAX_RESTARTS) break
                                delay(300)
                                current = createAndStartRecord(deviceInfo)
                                audioRecord = current
                            }
                        } finally {
                            current?.let {
                                runCatching { it.stop() }
                                it.release()
                            }
                            _isCapturing.value = false
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Falha ao iniciar a captura de áudio", e)
                    stopCaptureInternal()
                }
            }
        }
    }

    /** Idempotente: pode ser chamado a qualquer momento, inclusive sem captura ativa. */
    fun stopCapture() {
        coroutineScope.launch(start = CoroutineStart.UNDISPATCHED) {
            captureMutex.withLock {
                Log.i(LIFECYCLE_TAG, "AudioRecord: parando e liberando")
                stopCaptureInternal()
            }
        }
    }

    private suspend fun stopCaptureInternal() {
        val job = captureJob
        val record = audioRecord

        captureJob = null
        audioRecord = null

        // Para o record primeiro para desbloquear o read().
        record?.let {
            try {
                if (it.state == AudioRecord.STATE_INITIALIZED) {
                    it.stop()
                }
                Unit
            } catch (e: Exception) {
                Log.w(TAG, "Erro ao parar o AudioRecord", e)
            }
        }

        // Espera o laço de leitura terminar (ele libera o record que ainda tiver).
        job?.cancelAndJoin()

        record?.let { runCatching { it.release() } }

        releaseRouting()
        meter.reset()
        _audioLevels.value = AudioLevels()
        _audioPeaks.value = AudioMeterResult()
        _streamInfo.value = null
        _isCapturing.value = false
    }

    // ------------------------------------------------------------------
    // Criação do AudioRecord
    // ------------------------------------------------------------------

    @android.annotation.SuppressLint("MissingPermission")
    private fun createAndStartRecord(deviceInfo: AudioDeviceInfo?): AudioRecord? {
        val external = deviceInfo != null && deviceInfo.type != AudioDeviceInfo.TYPE_BUILTIN_MIC
        // CAMCORDER (otimizado para vídeo) no mic interno; MIC "cru" com dispositivo externo.
        val sources = if (external || Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            listOf(MediaRecorder.AudioSource.MIC)
        } else {
            listOf(MediaRecorder.AudioSource.CAMCORDER, MediaRecorder.AudioSource.MIC)
        }
        val masks = listOf(AudioFormat.CHANNEL_IN_STEREO, AudioFormat.CHANNEL_IN_MONO)

        for (mask in masks) {
            val minBuffer = AudioRecord.getMinBufferSize(REQUESTED_SAMPLE_RATE, mask, AudioFormat.ENCODING_PCM_16BIT)
            if (minBuffer <= 0) continue
            for (source in sources) {
                val record = try {
                    AudioRecord.Builder()
                        .setAudioSource(source)
                        .setAudioFormat(
                            AudioFormat.Builder()
                                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                .setSampleRate(REQUESTED_SAMPLE_RATE)
                                .setChannelMask(mask)
                                .build(),
                        )
                        .setBufferSizeInBytes(minBuffer * 2)
                        .build()
                } catch (e: Exception) {
                    Log.w(TAG, "AudioRecord não criado (source=$source mask=$mask): ${e.message}")
                    null
                } ?: continue

                if (record.state != AudioRecord.STATE_INITIALIZED) {
                    Log.w(TAG, "AudioRecord não inicializado (source=$source mask=$mask)")
                    record.release()
                    continue
                }

                if (deviceInfo != null) {
                    val accepted = record.setPreferredDevice(deviceInfo)
                    Log.i(TAG, "setPreferredDevice(${deviceInfo.productName}, tipo=${deviceInfo.type}) -> $accepted")
                }

                try {
                    record.startRecording()
                } catch (e: Exception) {
                    Log.e(TAG, "startRecording falhou", e)
                    record.release()
                    continue
                }
                if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                    Log.w(TAG, "AudioRecord não entrou em RECORDING")
                    runCatching { record.stop() }
                    record.release()
                    continue
                }

                val routed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) record.routedDevice else null
                if (deviceInfo != null && routed != null && routed.id != deviceInfo.id) {
                    Log.w(
                        TAG,
                        "Roteado para '${routed.productName}' (tipo ${routed.type}) em vez do dispositivo pedido " +
                            "'${deviceInfo.productName}' (tipo ${deviceInfo.type}) — o áudio pode vir do mic errado",
                    )
                } else {
                    Log.i(TAG, "Roteado para: ${routed?.productName} (tipo ${routed?.type}); ${record.sampleRate}Hz ${record.channelCount}ch")
                }
                _streamInfo.value = AudioStreamInfo(
                    sampleRate = record.sampleRate,
                    channels = 2,
                    capturedChannels = record.channelCount,
                    routedDeviceType = routed?.type,
                    routedDeviceName = routed?.productName?.toString(),
                )
                return record
            }
        }
        return null
    }

    // ------------------------------------------------------------------
    // Roteamento Bluetooth SCO (L8)
    // ------------------------------------------------------------------

    @Suppress("DEPRECATION")
    private suspend fun routeBluetoothIfNeeded(deviceInfo: AudioDeviceInfo?) {
        if (deviceInfo?.type != AudioDeviceInfo.TYPE_BLUETOOTH_SCO) return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                Log.w(TAG, "BLUETOOTH_CONNECT não concedida — não é possível rotear o microfone Bluetooth (SCO)")
                return
            }
            try {
                val target = audioManager.availableCommunicationDevices
                    .firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO && it.address == deviceInfo.address }
                    ?: audioManager.availableCommunicationDevices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
                    ?: deviceInfo
                communicationDeviceSet = audioManager.setCommunicationDevice(target)
                Log.i(TAG, "setCommunicationDevice(${target.productName}) -> $communicationDeviceSet")
                delay(400) // a rota SCO leva um instante para subir
            } catch (e: Exception) {
                Log.w(TAG, "Falha em setCommunicationDevice", e)
            }
        } else {
            try {
                audioManager.startBluetoothSco()
                audioManager.isBluetoothScoOn = true
                scoActive = true
                val connected = awaitScoConnected(3000)
                Log.i(TAG, "startBluetoothSco -> conectado=$connected")
            } catch (e: Exception) {
                Log.w(TAG, "Falha ao iniciar Bluetooth SCO", e)
            }
        }
    }

    private suspend fun awaitScoConnected(timeoutMs: Long): Boolean {
        val connected = CompletableDeferred<Boolean>()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                val s = intent?.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_STATE, -1)
                if (s == AudioManager.SCO_AUDIO_STATE_CONNECTED) connected.complete(true)
            }
        }
        val filter = IntentFilter(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED)
        val sticky = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(receiver, filter)
        }
        return try {
            if (sticky?.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_STATE, -1) == AudioManager.SCO_AUDIO_STATE_CONNECTED) {
                true
            } else {
                withTimeoutOrNull(timeoutMs) { connected.await() } ?: false
            }
        } finally {
            runCatching { context.unregisterReceiver(receiver) }
        }
    }

    @Suppress("DEPRECATION")
    private fun releaseRouting() {
        if (communicationDeviceSet && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching { audioManager.clearCommunicationDevice() }
        }
        communicationDeviceSet = false
        if (scoActive) {
            runCatching {
                audioManager.isBluetoothScoOn = false
                audioManager.stopBluetoothSco()
            }
        }
        scoActive = false
    }

    // ------------------------------------------------------------------
    // Laço de leitura
    // ------------------------------------------------------------------

    private enum class ReadOutcome { STOPPED, DEAD_OBJECT, FAILED }

    private suspend fun readLoop(record: AudioRecord): ReadOutcome {
        val capturedChannels = record.channelCount.coerceAtLeast(1)
        val sampleRate = record.sampleRate
        val mono = capturedChannels == 1
        val outChannels = 2

        // Buffers alocados UMA vez por record e reutilizados a cada bloco.
        val shorts = ShortArray((sampleRate * capturedChannels * BLOCK_MS / 1000).coerceAtLeast(256))
        val outBytes = ByteArray(shorts.size * 2 * if (mono) 2 else 1)

        var consecutiveErrors = 0
        while (currentCoroutineContext().isActive) {
            val n = try {
                record.read(shorts, 0, shorts.size)
            } catch (e: IllegalStateException) {
                // Record liberado durante o read (stop concorrente).
                return if (currentCoroutineContext().isActive) ReadOutcome.FAILED else ReadOutcome.STOPPED
            }

            when {
                n > 0 -> {
                    consecutiveErrors = 0
                    processLevels(shorts, n, capturedChannels)

                    val frames = n / capturedChannels
                    val byteCount = frames * outChannels * 2
                    fillPcm(shorts, frames, mono, outBytes)
                    // Contrato: o consumidor recebe um array com tamanho EXATO. Em leituras
                    // completas (o normal) é o buffer reutilizado; em parciais, uma cópia.
                    val payload = if (byteCount == outBytes.size) outBytes else outBytes.copyOf(byteCount)
                    dispatch(payload, frames, outChannels, sampleRate)
                }

                n == AudioRecord.ERROR_DEAD_OBJECT -> return ReadOutcome.DEAD_OBJECT

                n < 0 -> {
                    // ERROR, ERROR_BAD_VALUE, ERROR_INVALID_OPERATION: tolera alguns, depois desiste.
                    if (++consecutiveErrors >= MAX_CONSECUTIVE_READ_ERRORS) {
                        Log.e(TAG, "AudioRecord.read falhou $consecutiveErrors vezes seguidas (último código $n)")
                        return ReadOutcome.FAILED
                    }
                    delay(20)
                }

                else -> delay(10)
            }
        }
        return ReadOutcome.STOPPED
    }

    /** Converte [frames] quadros para PCM16 LE estéreo em [out] (mono é duplicado nos dois canais). */
    private fun fillPcm(shorts: ShortArray, frames: Int, mono: Boolean, out: ByteArray) {
        var o = 0
        if (mono) {
            for (i in 0 until frames) {
                val s = shorts[i].toInt()
                val lo = (s and 0xFF).toByte()
                val hi = ((s shr 8) and 0xFF).toByte()
                out[o++] = lo
                out[o++] = hi
                out[o++] = lo
                out[o++] = hi
            }
        } else {
            val total = frames * 2
            for (i in 0 until total) {
                val s = shorts[i].toInt()
                out[o++] = (s and 0xFF).toByte()
                out[o++] = ((s shr 8) and 0xFF).toByte()
            }
        }
    }

    private fun dispatch(pcm: ByteArray, frames: Int, channels: Int, sampleRate: Int) {
        try {
            onAudioBufferAvailable?.invoke(pcm, frames, channels, sampleRate)
        } catch (e: Exception) {
            Log.e(TAG, "Erro no callback onAudioBufferAvailable", e)
        }
        for (sink in sinks) {
            try {
                sink.onAudio(pcm, frames, channels, sampleRate)
            } catch (e: Exception) {
                Log.e(TAG, "Erro em AudioSink", e)
            }
        }
    }

    private fun processLevels(buffer: ShortArray, count: Int, channels: Int) {
        val r = meter.process(buffer, count, channels, SystemClock.elapsedRealtime())
        _audioPeaks.value = r
        _audioLevels.value = AudioLevels(rmsToNormalized(r.rmsLeft), rmsToNormalized(r.rmsRight))
    }

    /** Mapeia RMS linear para 0..1 sobre a faixa -60..0 dBFS (comportamento original do VU). */
    private fun rmsToNormalized(rms: Float): Float {
        if (rms <= 0f) return 0f
        val db = (20f * log10(rms)).coerceIn(-60f, 0f)
        return (db + 60f) / 60f
    }
}
