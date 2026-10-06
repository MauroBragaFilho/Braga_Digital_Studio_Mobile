package com.bragastudio.mobile.featuresettings

import android.graphics.Bitmap
import android.net.Uri
import android.util.LruCache
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ColorLens
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bragastudio.mobile.common.components.BdsmBigButton
import com.bragastudio.mobile.common.components.BdsmBottomSheet
import com.bragastudio.mobile.common.components.BdsmSecondaryButton
import com.bragastudio.mobile.common.components.BdsmTextButton
import com.bragastudio.mobile.common.components.BigButtonTone
import com.bragastudio.mobile.common.components.ConfirmDialog
import com.bragastudio.mobile.common.components.EmptyState
import com.bragastudio.mobile.common.components.LoadingState
import com.bragastudio.mobile.common.components.bdsmClickable
import com.bragastudio.mobile.common.components.bdsmTextFieldColors
import com.bragastudio.mobile.common.components.rememberBdsmHaptics
import com.bragastudio.mobile.common.components.showUndo
import com.bragastudio.mobile.common.ui.theme.BdsmTheme
import com.bragastudio.mobile.core.model.Lut
import com.bragastudio.mobile.coremedia.domain.LutParser
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

private const val CARD_PREVIEW_W = 192
private const val CARD_PREVIEW_H = 120
private const val SHEET_PREVIEW_W = 320
private const val SHEET_PREVIEW_H = 180

/**
 * "Minhas LUTs" (UX v3): grade visual de cartões com a pré-visualização do efeito e o nome. Tocar
 * abre uma folha com a prévia grande, a intensidade (0-100%) e as ações Aplicar / Editar / Excluir.
 * Conteúdo da aba LUTs da tela Mídia (sem barra superior própria).
 */
@Composable
fun LutsContent(viewModel: LutsViewModel = hiltViewModel()) {
    val allLuts by viewModel.allLuts.collectAsStateWithLifecycle()
    val activeLutRaw by viewModel.activeLut.collectAsStateWithLifecycle()
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()
    val errorMessage by viewModel.errorMessage.collectAsStateWithLifecycle()
    val infoMessage by viewModel.infoMessage.collectAsStateWithLifecycle()
    val persistedIntensity by viewModel.lutIntensity.collectAsStateWithLifecycle()

    // Se a LUT ativa acabou de ser apagada (com "Desfazer" pendente), a tela já a trata como "nenhuma".
    val activeLut = remember(activeLutRaw, allLuts) { activeLutRaw?.takeIf { a -> allLuts.any { it.id == a.id } } }

    // Valor local durante o arrasto; persistido só ao soltar (evita escrever no DataStore a cada quadro).
    var lutIntensity by remember { mutableFloatStateOf(persistedIntensity) }
    LaunchedEffect(persistedIntensity) { lutIntensity = persistedIntensity }

    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    LaunchedEffect(errorMessage) {
        errorMessage?.let {
            snackbarHostState.showSnackbar(it.asString(context))
            viewModel.clearErrorMessage()
        }
    }
    LaunchedEffect(infoMessage) {
        infoMessage?.let {
            snackbarHostState.showSnackbar(it.asString(context))
            viewModel.clearInfoMessage()
        }
    }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
    ) { uri: Uri? ->
        uri?.let { viewModel.importLut(it) }
    }
    val launchImport = { filePickerLauncher.launch("*/*") }

    // A folha guarda só o id: se a LUT some (excluída), a folha fecha sozinha.
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    val selected by remember(allLuts) { derivedStateOf { allLuts.firstOrNull { it.id == selectedId } } }
    var confirmDelete by remember { mutableStateOf<Lut?>(null) }
    var renaming by remember { mutableStateOf<Lut?>(null) }

    // Apagar com DESFAZER (depois da confirmação): some da lista na hora; a exclusão real só vale
    // quando o aviso expira.
    val deletedMsg = stringResource(R.string.luts_deleted)
    val undoLabel = stringResource(R.string.undo)
    val haptics = rememberBdsmHaptics()
    val scope = rememberCoroutineScope()
    val deleteWithUndo: (Lut) -> Unit = { lut ->
        haptics.reject()
        selectedId = null
        viewModel.hideForDelete(lut.id)
        scope.launch {
            var undone = false
            try {
                undone = snackbarHostState.showUndo(deletedMsg.format(lut.displayName), undoLabel)
            } finally {
                // Desfazer devolve a LUT; expirar, ser substituído por outro aviso ou sair da tela efetiva a exclusão.
                if (undone) viewModel.undoDelete(lut.id) else viewModel.commitDelete(lut.id)
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        when {
            isLoading && allLuts.isEmpty() -> LoadingState(
                modifier = Modifier.align(Alignment.Center),
                message = stringResource(R.string.luts_loading),
            )

            allLuts.isEmpty() -> Column(
                modifier = Modifier.align(Alignment.Center).padding(BdsmTheme.spacing.lg),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(BdsmTheme.spacing.md),
            ) {
                EmptyState(
                    icon = Icons.Filled.ColorLens,
                    title = stringResource(R.string.luts_empty_title),
                    message = stringResource(R.string.luts_empty_message),
                )
                BdsmBigButton(
                    text = stringResource(R.string.luts3_add),
                    icon = Icons.Filled.Add,
                    onClick = { launchImport() },
                )
            }

            else -> LutGrid(
                luts = allLuts,
                activeId = activeLut?.id,
                loading = isLoading,
                onAdd = { launchImport() },
                onOpen = { selectedId = it.id },
            )
        }
        SnackbarHost(snackbarHostState, modifier = Modifier.align(Alignment.BottomCenter))
    }

    selected?.let { lut ->
        LutDetailSheet(
            lut = lut,
            isActive = lut.id == activeLut?.id,
            intensity = lutIntensity,
            onIntensityChange = { lutIntensity = it },
            onIntensityChangeFinished = { viewModel.setLutIntensity(lutIntensity) },
            onApply = {
                viewModel.applyLut(if (lut.id == activeLut?.id) null else lut.id)
                selectedId = null
            },
            onEdit = { renaming = lut },
            onDelete = { confirmDelete = lut },
            onDismiss = { selectedId = null },
        )
    }
    confirmDelete?.let { lut ->
        ConfirmDialog(
            title = stringResource(R.string.luts3_delete_title, lut.displayName),
            message = stringResource(R.string.luts3_delete_message),
            confirmText = stringResource(R.string.luts3_delete),
            onConfirm = {
                confirmDelete = null
                deleteWithUndo(lut)
            },
            onDismiss = { confirmDelete = null },
        )
    }
    renaming?.let { lut ->
        RenameLutDialog(
            current = lut.displayName,
            onConfirm = {
                viewModel.renameLut(lut.id, it)
                renaming = null
            },
            onDismiss = { renaming = null },
        )
    }
}

@Composable
private fun LutGrid(
    luts: List<Lut>,
    activeId: String?,
    loading: Boolean,
    onAdd: () -> Unit,
    onOpen: (Lut) -> Unit,
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 150.dp),
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            start = BdsmTheme.spacing.lg,
            end = BdsmTheme.spacing.lg,
            bottom = BdsmTheme.spacing.xl,
        ),
        horizontalArrangement = Arrangement.spacedBy(BdsmTheme.spacing.md),
        verticalArrangement = Arrangement.spacedBy(BdsmTheme.spacing.md),
    ) {
        item(key = "header", contentType = "header", span = { GridItemSpan(maxLineSpan) }) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(BdsmTheme.spacing.md),
            ) {
                Text(
                    text = stringResource(R.string.luts3_my_luts),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                if (loading) CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
                BdsmSecondaryButton(
                    text = stringResource(R.string.luts3_add),
                    icon = Icons.Filled.Add,
                    onClick = onAdd,
                )
            }
        }
        items(luts, key = { it.id }, contentType = { "lutCard" }) { lut ->
            LutCard(lut = lut, isActive = lut.id == activeId, onClick = { onOpen(lut) })
        }
    }
}

@Composable
private fun LutCard(lut: Lut, isActive: Boolean, onClick: () -> Unit) {
    val shape = BdsmTheme.shapes.card
    val inUse = stringResource(R.string.luts_in_use)
    Column(
        modifier = Modifier
            .clip(shape)
            .background(BdsmTheme.colors.card)
            .border(
                width = if (isActive) 2.dp else 1.dp,
                color = if (isActive) MaterialTheme.colorScheme.primary else BdsmTheme.colors.cardBorder,
                shape = shape,
            )
            .semantics { selected = isActive }
            .bdsmClickable(onClick = onClick, role = Role.Button)
            .padding(BdsmTheme.spacing.sm),
        verticalArrangement = Arrangement.spacedBy(BdsmTheme.spacing.sm),
    ) {
        LutThumb(
            lut = lut,
            width = CARD_PREVIEW_W,
            height = CARD_PREVIEW_H,
            modifier = Modifier.fillMaxWidth().aspectRatio(CARD_PREVIEW_W / CARD_PREVIEW_H.toFloat()),
        )
        Row(
            modifier = Modifier.padding(horizontal = BdsmTheme.spacing.xs).defaultMinSize(minHeight = 40.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(BdsmTheme.spacing.xs),
        ) {
            Text(
                text = lut.displayName,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (isActive) {
                Icon(
                    imageVector = Icons.Filled.CheckCircle,
                    contentDescription = inUse,
                    tint = BdsmTheme.colors.success,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

/** Folha com a prévia grande, o nome, a intensidade e as ações (Aplicar / Editar / Excluir). */
@Composable
private fun LutDetailSheet(
    lut: Lut,
    isActive: Boolean,
    intensity: Float,
    onIntensityChange: (Float) -> Unit,
    onIntensityChangeFinished: () -> Unit,
    onApply: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    BdsmBottomSheet(onDismiss = onDismiss, title = lut.displayName) {
        LutThumb(
            lut = lut,
            width = SHEET_PREVIEW_W,
            height = SHEET_PREVIEW_H,
            modifier = Modifier.fillMaxWidth().aspectRatio(SHEET_PREVIEW_W / SHEET_PREVIEW_H.toFloat()),
        )
        Column {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    text = stringResource(R.string.luts_intensity),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "${(intensity * 100).toInt()}%",
                    style = BdsmTheme.type.metric,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            val sliderDescription = stringResource(R.string.luts_intensity_description)
            Slider(
                value = intensity,
                onValueChange = onIntensityChange,
                onValueChangeFinished = onIntensityChangeFinished,
                modifier = Modifier
                    .defaultMinSize(minHeight = BdsmTheme.spacing.touchTarget)
                    .semantics { contentDescription = sliderDescription },
            )
        }
        BdsmBigButton(
            text = stringResource(if (isActive) R.string.luts3_remove_caps else R.string.luts3_apply_caps),
            tone = if (isActive) BigButtonTone.Neutral else BigButtonTone.Primary,
            onClick = onApply,
        )
        if (!lut.isBuiltIn) {
            Row(horizontalArrangement = Arrangement.spacedBy(BdsmTheme.spacing.md)) {
                BdsmSecondaryButton(
                    text = stringResource(R.string.luts3_edit_caps),
                    icon = Icons.Filled.Edit,
                    onClick = onEdit,
                    modifier = Modifier.weight(1f),
                )
                BdsmSecondaryButton(
                    text = stringResource(R.string.luts3_delete_caps),
                    icon = Icons.Filled.Delete,
                    onClick = onDelete,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun RenameLutDialog(current: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var text by rememberSaveable { mutableStateOf(current) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = BdsmTheme.shapes.container,
        title = { Text(stringResource(R.string.luts3_rename_title), style = MaterialTheme.typography.titleLarge) },
        text = {
            OutlinedTextField(
                value = text,
                colors = bdsmTextFieldColors(),
                onValueChange = { text = it.take(MAX_LUT_NAME) },
                singleLine = true,
                label = { Text(stringResource(R.string.luts3_rename_label)) },
                shape = BdsmTheme.shapes.item,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            BdsmTextButton(onClick = { onConfirm(text) }, enabled = text.isNotBlank()) { Text(stringResource(R.string.luts3_save)) }
        },
        dismissButton = { BdsmTextButton(onClick = onDismiss) { Text(stringResource(com.bragastudio.mobile.common.R.string.bdsm_cancel)) } },
    )
}

private const val MAX_LUT_NAME = 60

// ---------------------------------------------------------------------------
// Pré-visualização do efeito (gerada na CPU a partir do .cube, fora da Main, com cache)
// ---------------------------------------------------------------------------

private val previewCache = object : LruCache<String, ImageBitmap>(64) {}
private val previewGate = Semaphore(2)

/** Chave de cache: muda quando o arquivo é reescrito ou o tamanho pedido muda. */
private fun previewKey(lut: Lut, width: Int, height: Int): String = "${lut.id}:${lut.modificationDate}:${width}x$height"

private fun loadLutData(context: android.content.Context, lut: Lut): LutParser.LutData? = if (lut.isBuiltIn) LutParser.parseFromAssets(context, lut.filePath) else LutParser.parseFromFile(File(lut.filePath))

@Composable
private fun LutThumb(lut: Lut, width: Int, height: Int, modifier: Modifier = Modifier) {
    val context = LocalContext.current.applicationContext
    val key = previewKey(lut, width, height)
    val bitmap by produceState<ImageBitmap?>(initialValue = previewCache.get(key), key) {
        if (value == null) {
            value = withContext(Dispatchers.Default) {
                previewGate.withPermit {
                    try {
                        val scene = LutPreview.sampleScene(width, height)
                        val data = loadLutData(context, lut)
                        val pixels = if (data == null) null else LutPreview.apply(data, scene)
                        pixels?.let {
                            Bitmap.createBitmap(it, width, height, Bitmap.Config.ARGB_8888)
                                .asImageBitmap()
                                .also { img -> previewCache.put(key, img) }
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        null
                    }
                }
            }
        }
    }
    Box(
        modifier = modifier
            .clip(BdsmTheme.shapes.item)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .border(1.dp, BdsmTheme.colors.cardBorder, BdsmTheme.shapes.item),
        contentAlignment = Alignment.Center,
    ) {
        val img = bitmap
        if (img != null) {
            Image(
                bitmap = img,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize(),
            )
        } else {
            Icon(
                imageVector = Icons.Filled.ColorLens,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(24.dp),
            )
        }
    }
}
