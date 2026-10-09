package com.bragastudio.mobile.corecapture.domain

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

/**
 * Sondagem BREVE de câmera/microfone (ex.: descobrir qual dispositivo está em uso): abre o recurso,
 * lê e FECHA no `finally`, mesmo com erro, cancelamento ou timeout. Câmera e microfone só devem
 * ficar abertos no Monitor; qualquer outra necessidade passa por aqui.
 *
 * [open] cria o recurso (null = indisponível, devolve null); [close] sempre roda se o recurso foi aberto.
 * Devolve null em timeout/falha (nunca lança, exceto cancelamento do chamador).
 */
suspend fun <R : Any, T> withBriefProbe(
    name: String,
    timeoutMs: Long = 2_000L,
    open: suspend () -> R?,
    close: (R) -> Unit,
    block: suspend (R) -> T,
): T? {
    var resource: R? = null
    return try {
        withTimeout(timeoutMs) {
            val r = open() ?: return@withTimeout null
            resource = r
            probeLog("CaptureLifecycle", "sondagem '$name': aberto")
            block(r)
        }
    } catch (e: TimeoutCancellationException) {
        probeLog("CaptureLifecycle", "sondagem '$name': timeout de ${timeoutMs}ms")
        null
    } catch (e: CancellationException) {
        throw e
    } catch (t: Throwable) {
        probeLog("CaptureLifecycle", "sondagem '$name' falhou: ${t.message}")
        null
    } finally {
        resource?.let { r ->
            runCatching { close(r) }
            probeLog("CaptureLifecycle", "sondagem '$name': fechado")
        }
    }
}

// Log tolerante: em testes JVM o android.util.Log não está disponível.
private fun probeLog(tag: String, msg: String) {
    runCatching { Log.d(tag, msg) }
}
