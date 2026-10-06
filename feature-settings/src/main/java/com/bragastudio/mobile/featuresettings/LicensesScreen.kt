package com.bragastudio.mobile.featuresettings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextDecoration
import com.bragastudio.mobile.common.components.BdsmCard
import com.bragastudio.mobile.common.components.BdsmScreen
import com.bragastudio.mobile.common.components.rememberBdsmHaptics
import com.bragastudio.mobile.common.ui.theme.BdsmTheme

/**
 * Licenças de código aberto e componentes de terceiros (item 5.9 / L12).
 * Lista rolável; cada item expande para mostrar URL e aviso de atribuição.
 */
@Composable
fun LicensesScreen(onNavigateBack: () -> Unit = {}) {
    BdsmScreen(title = stringResource(R.string.licenses_title), onNavigateUp = onNavigateBack, scrollable = false) {
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(bottom = BdsmTheme.spacing.lg),
            verticalArrangement = Arrangement.spacedBy(BdsmTheme.spacing.md),
        ) {
            item(key = "lgpl") {
                Surface(
                    shape = BdsmTheme.shapes.card,
                    color = BdsmTheme.colors.warningContainer,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        OpenSourceLicenses.LGPL_NOTE,
                        color = BdsmTheme.colors.onWarningContainer,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(BdsmTheme.spacing.lg),
                    )
                }
            }
            items(OpenSourceLicenses.all, key = { it.name }) { entry -> LicenseCard(entry) }
        }
    }
}

@Composable
private fun LicenseCard(entry: OpenSourceLicense) {
    var expanded by rememberSaveable(entry.name) { mutableStateOf(false) }
    val uriHandler = LocalUriHandler.current
    val haptics = rememberBdsmHaptics()
    BdsmCard(
        modifier = Modifier.fillMaxWidth().clickable(role = Role.Button) {
            haptics.tick()
            expanded = !expanded
        },
    ) {
        Column(Modifier.padding(BdsmTheme.spacing.lg), verticalArrangement = Arrangement.spacedBy(BdsmTheme.spacing.sm)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column(Modifier.weight(1f)) {
                    Text(entry.name, color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.titleSmall)
                    Text("${entry.version} • ${entry.license}", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                }
                Icon(
                    if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = stringResource(if (expanded) R.string.licenses_collapse else R.string.licenses_expand),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (expanded) {
                Text(
                    entry.url,
                    color = BdsmTheme.colors.primaryText,
                    style = MaterialTheme.typography.bodySmall,
                    textDecoration = TextDecoration.Underline,
                    modifier = Modifier.heightIn(min = BdsmTheme.spacing.touchTarget).clickable {
                        // Sem navegador instalado o openUri lança: ignora em silêncio.
                        try {
                            uriHandler.openUri(entry.url)
                        } catch (e: Exception) {
                            android.util.Log.w("LicensesScreen", "Não foi possível abrir ${entry.url}", e)
                        }
                    },
                )
                Text(entry.notice, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
