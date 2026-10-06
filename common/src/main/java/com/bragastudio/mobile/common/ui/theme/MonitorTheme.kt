package com.bragastudio.mobile.common.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider

/**
 * Força a paleta ESCURA numa sub-árvore (ex.: Monitor sobre vídeo), independentemente do
 * tema do app. NÃO mexe na janela/status bar (diferente de [BragaStudioMobileTheme]):
 * só troca MaterialTheme.colorScheme e os tokens [BdsmColors]. Tipografia e shapes são
 * herdados do tema externo. Popups/DropdownMenu compostos dentro herdam o tema escuro.
 */
@Composable
fun BdsmDarkSurfaceTheme(
    appTheme: AppTheme = BdsmDefaultTheme,
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(LocalBdsmColors provides appTheme.monitorTokens()) {
        MaterialTheme(
            colorScheme = appTheme.monitorColorScheme(),
            typography = MaterialTheme.typography,
            shapes = MaterialTheme.shapes,
            content = content,
        )
    }
}
