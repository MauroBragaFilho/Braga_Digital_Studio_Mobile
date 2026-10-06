package com.bragastudio.mobile.common.components

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.res.stringResource

/**
 * Texto de interface vindo de um ViewModel sem depender de `Context`: ou um recurso de string (com
 * argumentos) ou um texto já pronto (ex.: mensagem de exceção). A tela resolve com [asString], então
 * o idioma e o tamanho da fonte seguem o aparelho e os textos ficam em `strings.xml`.
 */
@Immutable
sealed interface UiText {
    /** Texto pronto (dado dinâmico, como o nome de um arquivo). */
    data class Plain(val value: String) : UiText

    /** Recurso de string; [args] entram como `%1$s`, `%2$d`... (um [UiText] como argumento é resolvido antes) */
    data class Res(@StringRes val id: Int, val args: List<Any> = emptyList()) : UiText

    /** Resolve dentro de um `@Composable`. */
    @Composable
    fun asString(): String = when (this) {
        is Plain -> value
        is Res -> stringResource(id, *args.map { if (it is UiText) it.asString() else it }.toTypedArray())
    }

    /** Resolve fora de composição (ex.: `Toast`). */
    fun asString(context: Context): String = when (this) {
        is Plain -> value
        is Res -> context.getString(id, *args.map { if (it is UiText) it.asString(context) else it }.toTypedArray())
    }

    companion object {
        fun of(@StringRes id: Int, vararg args: Any): UiText = Res(id, args.toList())
    }
}
