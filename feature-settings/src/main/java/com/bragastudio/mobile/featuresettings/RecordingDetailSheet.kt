package com.bragastudio.mobile.featuresettings

import android.text.format.Formatter
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.bragastudio.mobile.common.components.BdsmBigButton
import com.bragastudio.mobile.common.components.BdsmBottomSheet
import com.bragastudio.mobile.common.components.BdsmSecondaryButton
import com.bragastudio.mobile.common.components.BdsmTextButton
import com.bragastudio.mobile.common.components.DetailLine
import com.bragastudio.mobile.common.components.bdsmClickable
import com.bragastudio.mobile.common.components.bdsmTextFieldColors
import com.bragastudio.mobile.common.ui.theme.BdsmTheme
import com.bragastudio.mobile.common.ui.theme.bdsmIsShortLandscape
import com.bragastudio.mobile.core.recording.RecordingItem

/** Textos do resultado de renomear (resolvidos na tela; o ViewModel só escolhe qual emitir). */
data class RenameMessages(val ok: String, val invalid: String, val exists: String, val failed: String)

/**
 * Visualização de uma gravação (UX v3): prévia grande, duração, nome e data, REPRODUZIR em destaque
 * e as ações secundárias (compartilhar, renomear, excluir) em linha. "Informações" fica logo abaixo.
 */
@Composable
fun RecordingDetailSheet(
    item: RecordingItem,
    onPlay: () -> Unit,
    onShare: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val sizeLabel = remember(item.sizeBytes) { Formatter.formatShortFileSize(context, item.sizeBytes) }
    val preview: @Composable (Modifier) -> Unit = { mod ->
        Box(
            modifier = mod
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .clip(BdsmTheme.shapes.card)
                .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                .bdsmClickable(onClick = onPlay, role = Role.Button, onClickLabel = stringResource(R.string.rec3_play)),
            contentAlignment = Alignment.Center,
        ) {
            Thumbnail(item, Modifier.fillMaxSize(), iconSize = 48)
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.55f)), // sobre a miniatura
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.PlayArrow, contentDescription = null, tint = Color.White, modifier = Modifier.size(40.dp))
            }
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(BdsmTheme.spacing.sm)
                    .clip(BdsmTheme.shapes.chip)
                    .background(Color.Black.copy(alpha = 0.8f)) // rótulo sobre a miniatura
                    .padding(horizontal = BdsmTheme.spacing.sm, vertical = BdsmTheme.spacing.xxs),
            ) {
                Text(text = item.durationLabel, style = MaterialTheme.typography.labelMedium, color = Color.White, maxLines = 1)
            }
        }
    }
    val actions: @Composable () -> Unit = {
        BdsmBigButton(
            text = stringResource(R.string.rec3_play_caps),
            icon = Icons.Filled.PlayArrow,
            onClick = onPlay,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(BdsmTheme.spacing.sm), modifier = Modifier.fillMaxWidth()) {
            BdsmSecondaryButton(
                text = stringResource(R.string.rec3_share),
                icon = Icons.Filled.Share,
                onClick = onShare,
                modifier = Modifier.weight(1f),
            )
            BdsmSecondaryButton(
                text = stringResource(R.string.rec3_rename),
                icon = Icons.Filled.Edit,
                onClick = onRename,
                modifier = Modifier.weight(1f),
            )
        }
        BdsmSecondaryButton(
            text = stringResource(R.string.rec3_delete),
            icon = Icons.Filled.Delete,
            onClick = onDelete,
            modifier = Modifier.fillMaxWidth(),
        )
        DetailLine(label = stringResource(R.string.rec3_info_date), value = item.dateLabel)
        DetailLine(label = stringResource(R.string.rec3_info_duration), value = item.durationLabel, mono = true)
        DetailLine(label = stringResource(R.string.rec3_info_size), value = sizeLabel, mono = true)
        DetailLine(
            label = stringResource(R.string.rec3_info_format),
            value = "${item.resolutionLabel} • ${item.fpsLabel} • ${item.codecLabel}",
        )
    }
    BdsmBottomSheet(onDismiss = onDismiss, title = item.name) {
        if (bdsmIsShortLandscape()) {
            // Paisagem de celular: prévia à esquerda, ações e dados à direita (sem rolar tanto).
            Row(horizontalArrangement = Arrangement.spacedBy(BdsmTheme.spacing.lg)) {
                preview(Modifier.weight(0.45f))
                Column(modifier = Modifier.weight(0.55f), verticalArrangement = Arrangement.spacedBy(BdsmTheme.spacing.md)) { actions() }
            }
        } else {
            preview(Modifier)
            actions()
        }
    }
}

@Composable
fun RenameRecordingDialog(current: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var text by rememberSaveable { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = BdsmTheme.shapes.container,
        title = { Text(stringResource(R.string.rec3_rename_title), style = MaterialTheme.typography.titleLarge) },
        text = {
            OutlinedTextField(
                value = text,
                colors = bdsmTextFieldColors(),
                onValueChange = { text = it.take(RENAME_MAX) },
                singleLine = true,
                label = { Text(stringResource(R.string.rec3_rename_label)) },
                shape = BdsmTheme.shapes.item,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            BdsmTextButton(onClick = { onConfirm(text) }, enabled = text.isNotBlank()) { Text(stringResource(R.string.luts3_save)) }
        },
        dismissButton = {
            BdsmTextButton(onClick = onDismiss) { Text(stringResource(com.bragastudio.mobile.common.R.string.bdsm_cancel)) }
        },
    )
}

private const val RENAME_MAX = 80
