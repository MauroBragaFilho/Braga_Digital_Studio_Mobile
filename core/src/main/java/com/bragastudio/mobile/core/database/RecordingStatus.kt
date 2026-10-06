package com.bragastudio.mobile.core.database

/**
 * Valores textuais gravados em [RecordingEntity.status]. A coluna continua TEXT (o
 * RecordManager grava String); estas constantes evitam "strings mágicas" espalhadas.
 */
object RecordingStatus {
    const val COMPLETED = "COMPLETED"
    const val IN_PROGRESS = "IN_PROGRESS"
    const val CORRUPTED = "CORRUPTED"
    const val DELETED = "DELETED"

    /** Status que a galeria exibe (IN_PROGRESS e DELETED ficam de fora). */
    val VISIBLE = setOf(COMPLETED, CORRUPTED)
}
