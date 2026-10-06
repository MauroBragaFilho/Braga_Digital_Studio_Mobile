package com.bragastudio.mobile.featuresettings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.bragastudio.mobile.common.components.SegmentedChoice
import com.bragastudio.mobile.common.ui.theme.BdsmTheme
import com.bragastudio.mobile.common.ui.theme.bdsmIsShortLandscape

/** Abas da tela Mídia (a ordem é a ordem na tela). */
enum class MediaTab { RECORDINGS, LUTS }

/**
 * Mídia (UX v3): Gravações e LUTs numa só aba da barra inferior, separadas por um seletor no topo.
 * A aba escolhida sobrevive à rotação ([rememberSaveable]) e cada conteúdo mantém a própria lógica.
 */
@Composable
fun MediaScreen(initialTab: MediaTab = MediaTab.RECORDINGS) {
    var tab by rememberSaveable { mutableStateOf(initialTab) }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            // Topo + laterais (cutout em paisagem); a parte de baixo é tratada pelo conteúdo.
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Top)),
    ) {
        val title: @Composable () -> Unit = {
            Text(
                text = stringResource(R.string.module_media),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.semantics { heading() },
            )
        }
        val selector: @Composable (Modifier) -> Unit = { mod ->
            SegmentedChoice(
                options = MediaTab.entries,
                selected = tab,
                label = { stringResource(if (it == MediaTab.RECORDINGS) R.string.module_recordings else R.string.module_luts) },
                onSelect = { tab = it },
                modifier = mod,
            )
        }
        if (bdsmIsShortLandscape()) {
            // Paisagem de celular: título e seletor na mesma linha (sobra altura para a grade).
            Row(
                modifier = Modifier.padding(horizontal = BdsmTheme.spacing.screenMargin, vertical = BdsmTheme.spacing.xs),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(BdsmTheme.spacing.xl),
            ) {
                title()
                selector(Modifier.widthIn(max = 420.dp).weight(1f, fill = false))
            }
        } else {
            Box(Modifier.padding(horizontal = BdsmTheme.spacing.screenMargin, vertical = BdsmTheme.spacing.md)) { title() }
            selector(Modifier.padding(horizontal = BdsmTheme.spacing.screenMargin))
        }
        Box(modifier = Modifier.weight(1f).padding(top = BdsmTheme.spacing.sm)) {
            when (tab) {
                MediaTab.RECORDINGS -> RecordingsScreen()
                MediaTab.LUTS -> LutsContent()
            }
        }
    }
}
