package com.bragastudio.mobile.core.recording

import com.bragastudio.mobile.core.database.RecordingEntity
import com.bragastudio.mobile.core.database.RecordingStatus
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Aba da galeria. */
enum class GalleryTab { ALL, FAVORITES }

/**
 * Modelo imutável de uma gravação para a UI, derivado do [RecordingEntity] (metadados reais do
 * Room, nada fixo). Rótulos já normalizados por [RecordingFormat].
 */
data class RecordingItem(
    val id: String,
    val name: String,
    val filePath: String,
    val contentUri: String?,
    val thumbnailPath: String,
    val durationMs: Long,
    val durationLabel: String,
    val sizeBytes: Long,
    val resolutionLabel: String,
    val codecLabel: String,
    val fpsLabel: String,
    val bitrateLabel: String,
    val createdAt: Long,
    val dateLabel: String,
    val dateGroup: String,
    val isFavorite: Boolean,
    /** Take interrompido/sem moov: exibido com selo de aviso. */
    val isCorrupted: Boolean,
)

/** Gravações de um mesmo dia, na ordem pedida. */
data class RecordingGroup(val label: String, val items: List<RecordingItem>)

/** Transformações puras Room -> UI (testáveis sem Android). */
object RecordingListBuilder {

    private val BR = Locale.forLanguageTag("pt-BR")

    fun toItem(entity: RecordingEntity, zone: TimeZone = TimeZone.getDefault(), now: Long = System.currentTimeMillis()): RecordingItem {
        val date = Date(entity.createdAt)
        val dateFormat = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()).apply { timeZone = zone }
        return RecordingItem(
            id = entity.id,
            name = entity.fileName,
            filePath = entity.filePath,
            contentUri = entity.contentUri,
            thumbnailPath = entity.thumbnailPath,
            durationMs = entity.durationMs,
            durationLabel = RecordingFormat.formatDuration(entity.durationMs),
            sizeBytes = entity.sizeBytes,
            resolutionLabel = RecordingFormat.resolutionLabel(entity.resolution),
            codecLabel = RecordingFormat.codecLabel(entity.codec),
            fpsLabel = RecordingFormat.fpsLabel(entity.frameRate),
            bitrateLabel = RecordingFormat.bitrateLabel(entity.bitrate),
            createdAt = entity.createdAt,
            dateLabel = dateFormat.format(date),
            dateGroup = RecordingDateLabels.group(entity.createdAt, now, zone),
            isFavorite = entity.isFavorite,
            isCorrupted = entity.status == RecordingStatus.CORRUPTED,
        )
    }

    /**
     * Filtra, ordena e agrupa por dia.
     *  - só status COMPLETED e CORRUPTED aparecem (IN_PROGRESS e DELETED ficam de fora);
     *  - [query] filtra por nome sem distinguir maiúsculas/minúsculas (vazio = tudo);
     *  - [GalleryTab.FAVORITES] mantém só os favoritos;
     *  - grupos seguem a ordem do primeiro item de cada dia.
     */
    fun build(
        rows: List<RecordingEntity>,
        tab: GalleryTab,
        query: String,
        newestFirst: Boolean,
        zone: TimeZone = TimeZone.getDefault(),
        now: Long = System.currentTimeMillis(),
    ): List<RecordingGroup> {
        val needle = query.trim()
        val filtered = rows.asSequence()
            .filter { it.status in RecordingStatus.VISIBLE }
            .filter { tab == GalleryTab.ALL || it.isFavorite }
            .filter { needle.isEmpty() || it.fileName.contains(needle, ignoreCase = true) }
            .toList()
        val sorted = if (newestFirst) {
            filtered.sortedByDescending { it.createdAt }
        } else {
            filtered.sortedBy { it.createdAt }
        }
        val items = sorted.map { toItem(it, zone, now) }
        // LinkedHashMap preserva a ordem de aparição dos dias.
        return items.groupBy { it.dateGroup }.map { (label, list) -> RecordingGroup(label, list) }
    }
}

/**
 * Rótulo de seção por data da galeria: "Hoje", "Ontem", "2 de outubro" (mesmo ano) ou
 * "2 de outubro de 2025". Puro e testável (recebe `now` e o fuso).
 */
object RecordingDateLabels {
    const val TODAY = "Hoje"
    const val YESTERDAY = "Ontem"
    private val BR: Locale = Locale.forLanguageTag("pt-BR")

    /** Dias de calendário entre [createdAt] e [now] no fuso [zone] (0 = hoje, 1 = ontem; negativo = futuro). */
    fun daysAgo(createdAt: Long, now: Long, zone: TimeZone): Int {
        fun dayNumber(ms: Long): Long {
            val offset = zone.getOffset(ms)
            return Math.floorDiv(ms + offset, MS_PER_DAY)
        }
        return (dayNumber(now) - dayNumber(createdAt)).toInt()
    }

    fun group(createdAt: Long, now: Long, zone: TimeZone = TimeZone.getDefault()): String {
        val diff = daysAgo(createdAt, now, zone)
        if (diff == 0) return TODAY
        if (diff == 1) return YESTERDAY
        val cal = java.util.Calendar.getInstance(zone).apply { timeInMillis = createdAt }
        val nowCal = java.util.Calendar.getInstance(zone).apply { timeInMillis = now }
        val sameYear = cal.get(java.util.Calendar.YEAR) == nowCal.get(java.util.Calendar.YEAR)
        val pattern = if (sameYear) "d 'de' MMMM" else "d 'de' MMMM 'de' yyyy"
        return SimpleDateFormat(pattern, BR).apply { timeZone = zone }.format(Date(createdAt))
    }

    private const val MS_PER_DAY = 86_400_000L
}
