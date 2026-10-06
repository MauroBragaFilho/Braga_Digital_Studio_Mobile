package com.bragastudio.mobile.core.recording

/** Regras puras para renomear gravações (testadas em JVM). */
object RecordingNaming {
    const val MAX_BASE_LENGTH = 80
    private val FORBIDDEN = Regex("""[\\/:*?"<>|\p{Cntrl}]""")
    private val SPACES = Regex("""\s+""")

    /** Remove caracteres proibidos em nomes de arquivo, apara pontos/espaços e limita o tamanho. Vazio = inválido. */
    fun sanitizeBaseName(input: String): String = input
        .replace(FORBIDDEN, " ")
        .replace(SPACES, " ")
        .trim()
        .trim('.')
        .trim()
        .take(MAX_BASE_LENGTH)
        .trim()

    /** Nome final: base limpa + extensão original (sem ponto; padrão "mp4"). */
    fun fileName(baseName: String, extension: String): String = sanitizeBaseName(baseName) + "." + extension.trimStart('.').ifBlank { "mp4" }
}
