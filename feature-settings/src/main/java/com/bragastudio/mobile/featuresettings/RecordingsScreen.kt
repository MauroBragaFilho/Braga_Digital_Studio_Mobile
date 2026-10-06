package com.bragastudio.mobile.featuresettings

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.text.format.Formatter
import android.util.Log
import android.util.LruCache
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.SdStorage
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.bragastudio.mobile.common.components.BdsmSearchField
import com.bragastudio.mobile.common.components.BdsmTextButton
import com.bragastudio.mobile.common.components.EmptyState
import com.bragastudio.mobile.common.components.IconBadge
import com.bragastudio.mobile.common.components.LoadingState
import com.bragastudio.mobile.common.components.UiText
import com.bragastudio.mobile.common.components.rememberBdsmHaptics
import com.bragastudio.mobile.common.components.showUndo
import com.bragastudio.mobile.common.ui.theme.BdsmTheme
import com.bragastudio.mobile.common.ui.theme.BdsmWidthClass
import com.bragastudio.mobile.common.ui.theme.bdsmIsShortLandscape
import com.bragastudio.mobile.common.ui.theme.bdsmWidthClass
import com.bragastudio.mobile.core.domain.HardwareMetrics
import com.bragastudio.mobile.core.domain.HardwareMonitorService
import com.bragastudio.mobile.core.recording.GalleryTab
import com.bragastudio.mobile.core.recording.RecordingGroup
import com.bragastudio.mobile.core.recording.RecordingItem
import com.bragastudio.mobile.core.recording.RecordingLibrary
import com.bragastudio.mobile.core.recording.RecordingListBuilder
import com.bragastudio.mobile.core.repository.RecordingRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "RecordingsScreen"

// ============================================================================
// ESTADO / VIEWMODEL (Room como fonte única — §5.2)
// ============================================================================

/** Exclusão aguardando confirmação do usuário (só para lote ou quando há cópia exportada). */
data class PendingDelete(val ids: List<String>, val names: List<String>, val hasExportedCopy: Boolean)

/** "Apagar com desfazer": aviso exibido enquanto os itens ficam escondidos (token identifica o pedido). */
data class UndoDelete(val token: Int, val ids: List<String>, val alsoExportedCopy: Boolean, val message: UiText)

data class GalleryUiState(
    val groups: List<RecordingGroup> = emptyList(),
    val totalVisible: Int = 0,
    val tab: GalleryTab = GalleryTab.ALL,
    val query: String = "",
    val searchOpen: Boolean = false,
    val sort: RecordingSort = RecordingSort.NEWEST,
    val selectedIds: Set<String> = emptySet(),
    val selectionMode: Boolean = false,
    val collapsedGroups: Set<String> = emptySet(),
    val isSyncing: Boolean = false,
    val pendingDelete: PendingDelete? = null,
)

@HiltViewModel
class RecordingsGalleryViewModel @Inject constructor(
    recordingRepository: RecordingRepository,
    private val library: RecordingLibrary,
    hardwareMonitor: HardwareMonitorService,
) : ViewModel() {

    /** Estado só de UI (sobrevive à rotação porque vive no ViewModel). */
    private data class Inputs(
        val tab: GalleryTab = GalleryTab.ALL,
        val query: String = "",
        val searchOpen: Boolean = false,
        val sort: RecordingSort = RecordingSort.NEWEST,
        val selectedIds: Set<String> = emptySet(),
        val selectionMode: Boolean = false,
        val collapsedGroups: Set<String> = emptySet(),
    )

    private val inputs = MutableStateFlow(Inputs())
    private val syncing = MutableStateFlow(false)
    private val pendingDelete = MutableStateFlow<PendingDelete?>(null)

    /** Itens apagados com "Desfazer" pendente: escondidos até a exclusão valer de verdade. */
    private val pendingRemoval = PendingRemoval()
    private val undoable = mutableMapOf<Int, UndoDelete>()
    private var nextToken = 0

    private val _messages = MutableSharedFlow<UiText>(extraBufferCapacity = 4)

    /** Avisos transitórios (Snackbar): resultado de exclusão, falhas de sincronização. */
    val messages: SharedFlow<UiText> = _messages.asSharedFlow()

    private val _undoEvents = MutableSharedFlow<UndoDelete>(extraBufferCapacity = 8)

    /** Pedidos de "apagar com desfazer" para a tela mostrar o aviso. */
    val undoEvents: SharedFlow<UndoDelete> = _undoEvents.asSharedFlow()

    val hardwareMetrics: StateFlow<HardwareMetrics> = hardwareMonitor.metrics

    private val allRows = recordingRepository.getAllRecordings()

    val uiState: StateFlow<GalleryUiState> = combine(
        allRows,
        inputs,
        syncing,
        pendingDelete,
        pendingRemoval.hidden,
    ) { rows, inp, sync, pending, hidden ->
        val shown = if (hidden.isEmpty()) rows else rows.filter { it.id !in hidden }
        val built = RecordingListBuilder.build(shown, inp.tab, inp.query, RecordingSorting.newestFirstForBuilder(inp.sort))
        val groups = RecordingSorting.arrange(built, inp.sort)
        val visibleIds = groups.flatMap { g -> g.items.map { it.id } }.toSet()
        GalleryUiState(
            groups = groups,
            totalVisible = visibleIds.size,
            tab = inp.tab,
            query = inp.query,
            searchOpen = inp.searchOpen,
            sort = inp.sort,
            // Seleção só vale para itens ainda existentes (exclusão/sincronização removem linhas).
            selectedIds = inp.selectedIds.intersect(visibleIds),
            selectionMode = inp.selectionMode,
            collapsedGroups = inp.collapsedGroups,
            isSyncing = sync,
            pendingDelete = pending,
        )
    }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), GalleryUiState())

    init {
        refresh()
    }

    /** Importa vídeos do disco sem linha no Room e remove linhas de arquivos apagados (em IO). */
    fun refresh() {
        viewModelScope.launch {
            syncing.value = true
            try {
                library.sync()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Falha ao sincronizar a biblioteca", e)
                _messages.tryEmit(UiText.of(R.string.rec_msg_refresh_failed))
            } finally {
                syncing.value = false
            }
        }
    }

    fun selectTab(tab: GalleryTab) = inputs.update { it.copy(tab = tab) }
    fun setQuery(q: String) = inputs.update { it.copy(query = q) }
    fun setSearchOpen(open: Boolean) = inputs.update { it.copy(searchOpen = open, query = if (open) it.query else "") }
    fun setSort(sort: RecordingSort) = inputs.update { it.copy(sort = sort) }

    fun toggleGroup(label: String) = inputs.update {
        it.copy(collapsedGroups = if (label in it.collapsedGroups) it.collapsedGroups - label else it.collapsedGroups + label)
    }

    fun startSelection(id: String) = inputs.update { it.copy(selectionMode = true, selectedIds = it.selectedIds + id) }

    fun toggleSelection(id: String) = inputs.update {
        val next = if (id in it.selectedIds) it.selectedIds - id else it.selectedIds + id
        it.copy(selectedIds = next, selectionMode = next.isNotEmpty() || it.selectionMode)
    }

    fun setSelectionMode(on: Boolean) = inputs.update {
        it.copy(selectionMode = on, selectedIds = if (on) it.selectedIds else emptySet())
    }

    fun clearSelection() = setSelectionMode(false)

    fun toggleFavorite(item: RecordingItem) {
        viewModelScope.launch { library.setFavorite(listOf(item.id), !item.isFavorite) }
    }

    /** Favoritar em lote: se todos já são favoritos, desfavorita; senão favorita todos. */
    fun favoriteSelected() {
        val state = uiState.value
        val selected = state.groups.flatMap { it.items }.filter { it.id in state.selectedIds }
        if (selected.isEmpty()) return
        val makeFavorite = !selected.all { it.isFavorite }
        viewModelScope.launch { library.setFavorite(selected.map { it.id }, makeFavorite) }
        clearSelection()
    }

    /**
     * Um único vídeo sem cópia exportada some na hora e ganha "Desfazer" (sem diálogo: o desfazer
     * já protege). Lotes e vídeos com cópia exportada passam antes por confirmação.
     */
    fun requestDelete(items: List<RecordingItem>) {
        if (items.isEmpty()) return
        val hasExported = items.any { !it.contentUri.isNullOrBlank() }
        if (items.size == 1 && !hasExported) {
            scheduleDelete(items.map { it.id }, alsoExportedCopy = false)
        } else {
            pendingDelete.value = PendingDelete(items.map { it.id }, items.map { it.name }, hasExported)
        }
    }

    /** Pede confirmação SEMPRE (usado pela folha de detalhes); depois da confirmação vale o "Desfazer". */
    fun requestDeleteWithConfirmation(items: List<RecordingItem>) {
        if (items.isEmpty()) return
        pendingDelete.value = PendingDelete(items.map { it.id }, items.map { it.name }, items.any { !it.contentUri.isNullOrBlank() })
    }

    /** Renomeia o arquivo (mantém a extensão); o resultado volta como aviso (Snackbar). */
    fun rename(item: RecordingItem, newBaseName: String, messages: RenameMessages) {
        viewModelScope.launch {
            val text = when (library.rename(item.id, newBaseName)) {
                RecordingLibrary.RenameResult.OK -> messages.ok
                RecordingLibrary.RenameResult.INVALID -> messages.invalid
                RecordingLibrary.RenameResult.EXISTS -> messages.exists
                RecordingLibrary.RenameResult.FAILED -> messages.failed
            }
            _messages.tryEmit(UiText.Plain(text))
        }
    }

    fun dismissDelete() {
        pendingDelete.value = null
    }

    fun confirmDelete(alsoExportedCopy: Boolean) {
        val pending = pendingDelete.value ?: return
        pendingDelete.value = null
        scheduleDelete(pending.ids, alsoExportedCopy)
    }

    private fun scheduleDelete(ids: List<String>, alsoExportedCopy: Boolean) {
        pendingRemoval.hide(ids)
        clearSelection()
        val token = nextToken++
        val message = if (ids.size == 1) UiText.of(R.string.rec_msg_deleted_one) else UiText.of(R.string.rec_msg_deleted_many, ids.size)
        val event = UndoDelete(token, ids, alsoExportedCopy, message)
        undoable[token] = event
        _undoEvents.tryEmit(event)
    }

    /** Usuário tocou em Desfazer: os vídeos voltam à lista (nada foi apagado). */
    fun undoDelete(token: Int) {
        val event = undoable.remove(token) ?: return
        pendingRemoval.restore(event.ids)
    }

    /** O aviso expirou (ou a tela saiu): apaga de verdade (arquivo + miniatura + registro). */
    fun commitDelete(token: Int) {
        val event = undoable.remove(token) ?: return
        viewModelScope.launch {
            try {
                val result = library.delete(event.ids, event.alsoExportedCopy)
                if (result.blockedInProgress > 0) _messages.tryEmit(UiText.of(R.string.rec_msg_blocked_in_progress))
                if (result.failed > 0) _messages.tryEmit(UiText.of(R.string.rec_msg_delete_failed_count, result.failed))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Falha ao excluir gravações", e)
                _messages.tryEmit(UiText.of(R.string.rec_msg_delete_error))
            } finally {
                pendingRemoval.release(event.ids)
            }
        }
    }

    fun postMessage(text: String) {
        _messages.tryEmit(UiText.Plain(text))
    }
}

// ============================================================================
// CACHE DE MINIATURAS (bytes, não contagem) — as miniaturas vêm do Room (thumbnailPath)
// ============================================================================

// Dimensionado por bytes (1/8 do heap máximo).
private val thumbnailMemoryCache = object : LruCache<String, Bitmap>(
    (Runtime.getRuntime().maxMemory() / 8L).coerceIn(1L, Int.MAX_VALUE.toLong()).toInt(),
) {
    override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
}

private const val THUMBNAIL_MAX_EDGE_PX = 512

/** Decodifica com `inSampleSize` (potência de 2) para não carregar imagens grandes inteiras na memória. */
private fun decodeThumbnailDownsampled(path: String): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    while (bounds.outWidth / (sample * 2) >= THUMBNAIL_MAX_EDGE_PX && bounds.outHeight / (sample * 2) >= THUMBNAIL_MAX_EDGE_PX) {
        sample *= 2
    }
    return BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
}

/** Decodifica a miniatura em IO; devolve null se o caminho for vazio ou o arquivo ilegível. */
@Composable
private fun rememberThumbnail(path: String): Bitmap? {
    if (path.isBlank()) return null
    val bitmap by produceState<Bitmap?>(initialValue = thumbnailMemoryCache.get(path), path) {
        if (value == null) {
            value = withContext(Dispatchers.IO) {
                thumbnailMemoryCache.get(path) ?: try {
                    decodeThumbnailDownsampled(path)?.also { thumbnailMemoryCache.put(path, it) }
                } catch (e: Exception) {
                    Log.w(TAG, "Falha ao decodificar miniatura", e)
                    null
                }
            }
        }
    }
    return bitmap
}

// ============================================================================
// TELA
// ============================================================================

/** Lista limpa: tocar abre o vídeo; as ações (compartilhar, favoritar, apagar) aparecem ao selecionar. */
@Composable
fun RecordingsScreen(viewModel: RecordingsGalleryViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val hwMetrics by viewModel.hardwareMetrics.collectAsStateWithLifecycle()
    val coroutineScope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val undoLabel = stringResource(R.string.undo)

    LaunchedEffect(Unit) {
        viewModel.messages.collect { snackbarHostState.showSnackbar(it.asString(context)) }
    }
    // Apagar com DESFAZER: cada pedido ganha seu aviso; Desfazer devolve os vídeos, expirar, ser
    // substituído por outro aviso ou sair da tela efetiva a exclusão.
    LaunchedEffect(Unit) {
        val scope = this
        viewModel.undoEvents.collect { event ->
            scope.launch {
                var undone = false
                try {
                    undone = snackbarHostState.showUndo(event.message.asString(context), undoLabel)
                } finally {
                    if (undone) viewModel.undoDelete(event.token) else viewModel.commitDelete(event.token)
                }
            }
        }
    }

    // Tocar abre a folha de detalhes (prévia, duração, reprodução, nome, data e ações); só o id é guardado.
    var detailId by rememberSaveable { mutableStateOf<String?>(null) }
    var renamingId by rememberSaveable { mutableStateOf<String?>(null) }
    val renameMessages = RenameMessages(
        ok = stringResource(R.string.rec3_rename_ok),
        invalid = stringResource(R.string.rec3_rename_invalid),
        exists = stringResource(R.string.rec3_rename_exists),
        failed = stringResource(R.string.rec3_rename_failed),
    )

    BackHandler(enabled = state.selectionMode) { viewModel.clearSelection() }

    val allItems = remember(state.groups) { state.groups.flatMap { it.items } }
    val selectedItems = remember(allItems, state.selectedIds) { allItems.filter { it.id in state.selectedIds } }

    val bg = MaterialTheme.colorScheme.background
    Scaffold(
        containerColor = bg,
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .statusBarsPadding()
                .background(bg),
        ) {
            if (state.selectionMode) {
                BatchActionBar(
                    selectedCount = state.selectedIds.size,
                    onClearSelection = viewModel::clearSelection,
                    onShareSingle = selectedItems.singleOrNull()?.let { item ->
                        { shareRecording(context, item, viewModel::postMessage) }
                    },
                    onSaveToGalleryBatch = {
                        val files = selectedItems.map { File(it.filePath) }.filter { it.exists() }
                        coroutineScope.launch { GalleryExporter.exportAndNotify(context, files) }
                        viewModel.clearSelection()
                    },
                    onFavoriteBatch = viewModel::favoriteSelected,
                    onDeleteBatch = { viewModel.requestDelete(selectedItems) },
                )
            } else {
                val topHeader: @Composable (Modifier) -> Unit = { mod ->
                    RecordingsTopHeader(
                        modifier = mod,
                        showTitle = false,
                        showBack = false,
                        onNavigateBack = {},
                        totalVisible = state.totalVisible,
                        metrics = hwMetrics,
                        searchOpen = state.searchOpen,
                        sort = state.sort,
                        onToggleSearch = { viewModel.setSearchOpen(!state.searchOpen) },
                        onSort = viewModel::setSort,
                        onSelect = { viewModel.setSelectionMode(true) },
                    )
                }
                if (bdsmIsShortLandscape()) {
                    // Paisagem de celular: contagem/busca/ordenar e abas Todas|Favoritas na mesma linha.
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        topHeader(Modifier.weight(1f))
                        RecordingsTabsHeader(selectedTab = state.tab, onSelectTab = viewModel::selectTab, modifier = Modifier.padding(end = BdsmTheme.spacing.screenMargin))
                    }
                } else {
                    topHeader(Modifier)
                }
            }

            if (state.searchOpen && !state.selectionMode) {
                BdsmSearchField(
                    query = state.query,
                    onQueryChange = viewModel::setQuery,
                    placeholder = stringResource(R.string.rec_search_hint),
                    clearDescription = stringResource(R.string.settings_search_clear),
                    modifier = Modifier.padding(horizontal = BdsmTheme.spacing.screenMargin, vertical = BdsmTheme.spacing.xs),
                )
            }

            if (!bdsmIsShortLandscape() || state.selectionMode) {
                RecordingsTabsHeader(selectedTab = state.tab, onSelectTab = viewModel::selectTab)
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = BdsmTheme.spacing.screenMargin, vertical = BdsmTheme.spacing.sm),
            ) {
                if (state.isSyncing && state.groups.isEmpty()) {
                    LoadingState(
                        modifier = Modifier.align(Alignment.Center),
                        message = stringResource(R.string.rec_loading),
                    )
                } else {
                    RecordingsContent(
                        groups = state.groups,
                        collapsed = state.collapsedGroups,
                        viewMode = ViewMode.GRID,
                        selectionMode = state.selectionMode,
                        selectedIds = state.selectedIds,
                        favoritesTab = state.tab == GalleryTab.FAVORITES,
                        searching = state.query.isNotBlank(),
                        onToggleGroup = viewModel::toggleGroup,
                        onItemClick = { item ->
                            if (state.selectionMode) {
                                viewModel.toggleSelection(item.id)
                            } else {
                                detailId = item.id
                            }
                        },
                        onItemLongClick = { item ->
                            if (state.selectionMode) viewModel.toggleSelection(item.id) else viewModel.startSelection(item.id)
                        },
                    )
                }
            }
        }
    }

    val detail = allItems.firstOrNull { it.id == detailId }
    if (detail != null) {
        RecordingDetailSheet(
            item = detail,
            onPlay = { playRecording(context, detail, viewModel::postMessage) },
            onShare = { shareRecording(context, detail, viewModel::postMessage) },
            onRename = { renamingId = detail.id },
            onDelete = {
                detailId = null
                viewModel.requestDeleteWithConfirmation(listOf(detail))
            },
            onDismiss = { detailId = null },
        )
    }
    allItems.firstOrNull { it.id == renamingId }?.let { item ->
        RenameRecordingDialog(
            current = item.name.substringBeforeLast('.'),
            onConfirm = {
                viewModel.rename(item, it, renameMessages)
                renamingId = null
                detailId = null
            },
            onDismiss = { renamingId = null },
        )
    }

    state.pendingDelete?.let { pending ->
        DeleteConfirmationDialog(
            pending = pending,
            onConfirm = viewModel::confirmDelete,
            onDismiss = viewModel::dismissDelete,
        )
    }
}

// ============================================================================
// CONFIRMAÇÃO DE EXCLUSÃO (M41)
// ============================================================================

@Composable
private fun DeleteConfirmationDialog(
    pending: PendingDelete,
    onConfirm: (alsoExportedCopy: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    var alsoExported by rememberSaveable { mutableStateOf(false) }
    val haptics = rememberBdsmHaptics()
    val count = pending.ids.size
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = BdsmTheme.shapes.container,
        title = {
            Text(
                if (count == 1) stringResource(R.string.rec_delete_title_one) else stringResource(R.string.rec_delete_title_many, count),
                style = MaterialTheme.typography.titleLarge,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(BdsmTheme.spacing.sm)) {
                Text(
                    if (count == 1) {
                        stringResource(R.string.rec_delete_body_one, pending.names.first())
                    } else {
                        stringResource(R.string.rec_delete_body_many, count)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (pending.hasExportedCopy) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = BdsmTheme.spacing.touchTarget)
                            .clickable(role = Role.Checkbox) {
                                alsoExported = !alsoExported
                                haptics.toggle(alsoExported)
                            },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = alsoExported, onCheckedChange = null)
                        Spacer(modifier = Modifier.width(BdsmTheme.spacing.sm))
                        Text(
                            stringResource(R.string.rec_delete_also_exported),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
        },
        confirmButton = {
            BdsmTextButton(onClick = { onConfirm(alsoExported && pending.hasExportedCopy) }) {
                Text(stringResource(R.string.rec_delete_confirm), color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { BdsmTextButton(onClick = onDismiss) { Text(stringResource(com.bragastudio.mobile.common.R.string.bdsm_cancel)) } },
    )
}

// ============================================================================
// CABEÇALHO: título, "N vídeos · X livres", busca, ordenar e selecionar
// ============================================================================

enum class ViewMode { GRID, LIST }

@Composable
fun RecordingsTopHeader(
    modifier: Modifier = Modifier,
    showTitle: Boolean,
    showBack: Boolean,
    onNavigateBack: () -> Unit,
    totalVisible: Int,
    metrics: HardwareMetrics,
    searchOpen: Boolean,
    sort: RecordingSort,
    onToggleSearch: () -> Unit,
    onSort: (RecordingSort) -> Unit,
    onSelect: () -> Unit,
) {
    val context = LocalContext.current
    var sortMenu by remember { mutableStateOf(false) }
    val free = Formatter.formatShortFileSize(context, gbToBytes(metrics.storageFreeGB))
    val lowSpace = metrics.storageTotalGB > 0f && metrics.storageFreeGB <= 10f
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = BdsmTheme.spacing.sm, vertical = BdsmTheme.spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showBack) {
            IconButton(onClick = onNavigateBack) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.rec_back),
                    tint = MaterialTheme.colorScheme.onBackground,
                )
            }
        } else {
            Spacer(modifier = Modifier.width(BdsmTheme.spacing.sm))
        }
        Column(modifier = Modifier.weight(1f)) {
            if (showTitle) {
                Text(
                    text = stringResource(R.string.module_recordings),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = pluralStringResource(R.plurals.rec_count, totalVisible, totalVisible) + " · " + stringResource(R.string.rec_free, free),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (lowSpace) {
                    Spacer(modifier = Modifier.width(BdsmTheme.spacing.xs))
                    Icon(Icons.Filled.Warning, contentDescription = null, tint = BdsmTheme.colors.warning, modifier = Modifier.size(16.dp))
                    Text(
                        text = stringResource(R.string.rec_low_space),
                        style = MaterialTheme.typography.bodyMedium,
                        color = BdsmTheme.colors.warning,
                        maxLines = 1,
                    )
                }
            }
        }
        IconButton(onClick = onToggleSearch) {
            Icon(
                if (searchOpen) Icons.Filled.Close else Icons.Filled.Search,
                stringResource(if (searchOpen) R.string.rec_search_close else R.string.rec_search_open),
                tint = MaterialTheme.colorScheme.onBackground,
            )
        }
        Box {
            IconButton(onClick = { sortMenu = true }) {
                Icon(
                    Icons.AutoMirrored.Filled.Sort,
                    stringResource(R.string.rec_sort),
                    tint = MaterialTheme.colorScheme.onBackground,
                )
            }
            DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                RecordingSort.entries.forEach { option ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                stringResource(sortLabelRes(option)),
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = if (option == sort) FontWeight.Bold else FontWeight.Normal,
                            )
                        },
                        leadingIcon = {
                            if (option == sort) Icon(Icons.Filled.Check, contentDescription = null)
                        },
                        onClick = {
                            sortMenu = false
                            onSort(option)
                        },
                    )
                }
            }
        }
        BdsmTextButton(onClick = onSelect) {
            Text(stringResource(R.string.rec_select), maxLines = 1)
        }
    }
}

private fun sortLabelRes(sort: RecordingSort): Int = when (sort) {
    RecordingSort.NEWEST -> R.string.rec_sort_newest
    RecordingSort.OLDEST -> R.string.rec_sort_oldest
    RecordingSort.LARGEST -> R.string.rec_sort_largest
}

private fun gbToBytes(gb: Float): Long = (gb.toDouble() * 1024.0 * 1024.0 * 1024.0).toLong()

// ============================================================================
// NAVEGAÇÃO POR ABAS
// ============================================================================

@Composable
fun RecordingsTabsHeader(
    selectedTab: GalleryTab,
    onSelectTab: (GalleryTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = rememberBdsmHaptics()
    Row(
        modifier = modifier
            .then(if (modifier == Modifier) Modifier.fillMaxWidth().padding(horizontal = BdsmTheme.spacing.screenMargin) else Modifier)
            .selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(BdsmTheme.spacing.xl),
    ) {
        val tabs = listOf(
            GalleryTab.ALL to stringResource(R.string.rec_tab_all),
            GalleryTab.FAVORITES to stringResource(R.string.rec_tab_favorites),
        )
        tabs.forEach { (tab, label) ->
            val isSelected = selectedTab == tab
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier
                    .heightIn(min = BdsmTheme.spacing.touchTarget)
                    .selectable(selected = isSelected, role = Role.Tab) {
                        if (!isSelected) haptics.tick()
                        onSelectTab(tab)
                    }
                    .padding(top = BdsmTheme.spacing.sm),
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.titleSmall,
                    color = if (isSelected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(BdsmTheme.spacing.xs))
                Box(
                    modifier = Modifier
                        .height(3.dp)
                        .width(if (isSelected) 36.dp else 0.dp)
                        .clip(BdsmTheme.shapes.pill)
                        .background(if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent),
                )
            }
        }
    }
}

// ============================================================================
// CONTEÚDO LAZY AGRUPADO POR DATA (M40: key = id)
// ============================================================================

@Composable
fun RecordingsContent(
    groups: List<RecordingGroup>,
    collapsed: Set<String>,
    viewMode: ViewMode,
    selectionMode: Boolean,
    selectedIds: Set<String>,
    favoritesTab: Boolean,
    searching: Boolean,
    onToggleGroup: (String) -> Unit,
    onItemClick: (RecordingItem) -> Unit,
    onItemLongClick: (RecordingItem) -> Unit,
) {
    if (groups.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            EmptyState(
                icon = Icons.Filled.Movie,
                title = stringResource(
                    when {
                        searching -> R.string.rec_empty_search_title
                        favoritesTab -> R.string.rec_empty_favorites_title
                        else -> R.string.rec_empty_title
                    },
                ),
                message = stringResource(
                    when {
                        searching -> R.string.rec_empty_search_message
                        favoritesTab -> R.string.rec_empty_favorites_message
                        else -> R.string.rec_empty_message
                    },
                ),
            )
        }
        return
    }

    if (viewMode == ViewMode.GRID) {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 150.dp),
            modifier = Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.spacedBy(BdsmTheme.spacing.md),
            verticalArrangement = Arrangement.spacedBy(BdsmTheme.spacing.md),
        ) {
            groups.forEach { group ->
                val isCollapsed = group.label in collapsed
                item(key = "header:${group.label}", contentType = "header", span = { GridItemSpan(maxLineSpan) }) {
                    GroupHeader(group.label, group.items.size, !isCollapsed) { onToggleGroup(group.label) }
                }
                if (!isCollapsed) {
                    items(group.items, key = { it.id }, contentType = { "videoCard" }) { item ->
                        VideoCardGrid(
                            item = item,
                            isSelected = item.id in selectedIds,
                            selectionMode = selectionMode,
                            onClick = { onItemClick(item) },
                            onLongClick = { onItemLongClick(item) },
                        )
                    }
                }
            }
        }
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(BdsmTheme.spacing.sm),
        ) {
            groups.forEach { group ->
                val isCollapsed = group.label in collapsed
                item(key = "header:${group.label}", contentType = "header") {
                    GroupHeader(group.label, group.items.size, !isCollapsed) { onToggleGroup(group.label) }
                }
                if (!isCollapsed) {
                    items(group.items, key = { it.id }, contentType = { "videoRow" }) { item ->
                        VideoRow(
                            item = item,
                            isSelected = item.id in selectedIds,
                            selectionMode = selectionMode,
                            onClick = { onItemClick(item) },
                            onLongClick = { onItemLongClick(item) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun GroupHeader(label: String, count: Int, expanded: Boolean, onToggle: () -> Unit) {
    val haptics = rememberBdsmHaptics()
    val toggleLabel = stringResource(if (expanded) R.string.rec_group_collapse else R.string.rec_group_expand)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = BdsmTheme.spacing.touchTarget)
            .clickable(role = Role.Button, onClickLabel = toggleLabel) {
                haptics.tick()
                onToggle()
            },
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = if (count == 1) stringResource(R.string.rec_group_items_one) else stringResource(R.string.rec_group_items_many, count),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.width(BdsmTheme.spacing.xs))
            Icon(
                imageVector = if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

// ============================================================================
// PARTES COMUNS DOS CARTÕES
// ============================================================================

@Composable
internal fun Thumbnail(item: RecordingItem, modifier: Modifier, iconSize: Int) {
    val thumb = rememberThumbnail(item.thumbnailPath)
    if (thumb != null) {
        Image(
            bitmap = thumb.asImageBitmap(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = modifier,
        )
    } else {
        Box(modifier = modifier, contentAlignment = Alignment.Center) {
            Icon(
                Icons.Filled.Movie,
                null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(iconSize.dp),
            )
        }
    }
}

@Composable
private fun CorruptedTag() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            Icons.Filled.Warning,
            contentDescription = null,
            tint = BdsmTheme.colors.warning,
            modifier = Modifier.size(14.dp),
        )
        Spacer(modifier = Modifier.width(BdsmTheme.spacing.xs))
        Text(
            stringResource(R.string.rec_interrupted),
            style = MaterialTheme.typography.labelSmall,
            color = BdsmTheme.colors.warning,
        )
    }
}

/** Cartão/linha de gravação: fundo e borda do tema; borda destacada (primária) quando selecionado. */
@Composable
private fun Modifier.recordingSurface(isSelected: Boolean, shape: RoundedCornerShape): Modifier {
    val borderColor = if (isSelected) MaterialTheme.colorScheme.primary else BdsmTheme.colors.cardBorder
    return this
        .clip(shape)
        .background(BdsmTheme.colors.card)
        .border(if (isSelected) 2.dp else 1.dp, borderColor, shape)
}

// ============================================================================
// CARD DE VÍDEO (GRADE)
// ============================================================================

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun VideoCardGrid(
    item: RecordingItem,
    isSelected: Boolean,
    selectionMode: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val sizeLabel = remember(item.sizeBytes) { Formatter.formatShortFileSize(context, item.sizeBytes) }
    val haptics = rememberBdsmHaptics()
    val selectLabel = stringResource(R.string.rec_action_select)
    val watchLabel = stringResource(R.string.rec_action_watch)
    val selectedText = stringResource(R.string.rec_state_selected)
    val notSelectedText = stringResource(R.string.rec_state_not_selected)
    val cardShape = BdsmTheme.shapes.card

    Column(
        modifier = modifier
            .recordingSurface(isSelected, cardShape)
            .semantics { if (selectionMode) stateDescription = if (isSelected) selectedText else notSelectedText }
            .combinedClickable(
                role = Role.Button,
                onClickLabel = if (selectionMode) selectLabel else watchLabel,
                onClick = {
                    haptics.tick()
                    onClick()
                },
                onLongClickLabel = selectLabel,
                onLongClick = {
                    haptics.longPress()
                    onLongClick()
                },
            ),
    ) {
        // Miniatura: o fundo é do tema (visível só sem imagem); scrims pretos/brancos abaixo ficam sobre a imagem.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .background(MaterialTheme.colorScheme.surfaceContainerHighest),
            contentAlignment = Alignment.Center,
        ) {
            Thumbnail(item, Modifier.fillMaxSize(), iconSize = 36)

            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(BdsmTheme.spacing.sm)
                    .clip(BdsmTheme.shapes.chip)
                    .background(Color.Black.copy(alpha = 0.8f)) // rótulo sobre a miniatura
                    .padding(horizontal = BdsmTheme.spacing.sm, vertical = BdsmTheme.spacing.xxs),
            ) {
                Text(
                    text = item.durationLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White,
                    maxLines = 1,
                )
            }

            if (item.isFavorite) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(BdsmTheme.spacing.sm)
                        .size(24.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.6f)), // sobre a miniatura
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Filled.Star, stringResource(R.string.rec_favorite_cd), tint = BdsmTheme.colors.warning, modifier = Modifier.size(16.dp))
                }
            }

            if (selectionMode) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(BdsmTheme.spacing.xs)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.4f)), // sobre a miniatura
                ) {
                    Checkbox(
                        checked = isSelected,
                        onCheckedChange = { onClick() },
                        colors = CheckboxDefaults.colors(
                            checkedColor = MaterialTheme.colorScheme.primary,
                            checkmarkColor = MaterialTheme.colorScheme.onPrimary,
                            uncheckedColor = Color.White,
                        ),
                    )
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = BdsmTheme.spacing.md, top = BdsmTheme.spacing.xs, bottom = BdsmTheme.spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.name,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = sizeLabel,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (item.isCorrupted) CorruptedTag()
            }
        }
    }
}

// ============================================================================
// ROW DE VÍDEO (LISTA)
// ============================================================================

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun VideoRow(
    item: RecordingItem,
    isSelected: Boolean,
    selectionMode: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val context = LocalContext.current
    val sizeLabel = remember(item.sizeBytes) { Formatter.formatShortFileSize(context, item.sizeBytes) }
    val haptics = rememberBdsmHaptics()
    val selectLabel = stringResource(R.string.rec_action_select)
    val watchLabel = stringResource(R.string.rec_action_watch)
    val selectedText = stringResource(R.string.rec_state_selected)
    val notSelectedText = stringResource(R.string.rec_state_not_selected)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .recordingSurface(isSelected, BdsmTheme.shapes.card)
            .semantics { if (selectionMode) stateDescription = if (isSelected) selectedText else notSelectedText }
            .combinedClickable(
                role = Role.Button,
                onClickLabel = if (selectionMode) selectLabel else watchLabel,
                onClick = {
                    haptics.tick()
                    onClick()
                },
                onLongClickLabel = selectLabel,
                onLongClick = {
                    haptics.longPress()
                    onLongClick()
                },
            )
            .padding(BdsmTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(BdsmTheme.spacing.md),
    ) {
        if (selectionMode) {
            Checkbox(checked = isSelected, onCheckedChange = { onClick() })
        }

        Box(
            modifier = Modifier
                .size(width = 112.dp, height = 64.dp)
                .clip(BdsmTheme.shapes.chip)
                .background(MaterialTheme.colorScheme.surfaceContainerHighest),
            contentAlignment = Alignment.Center,
        ) {
            Thumbnail(item, Modifier.fillMaxSize(), iconSize = 24)
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.5f)), // sobre a miniatura
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.PlayArrow, stringResource(R.string.rec_action_watch), tint = Color.White, modifier = Modifier.size(20.dp))
            }
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(BdsmTheme.spacing.xs)
                    .clip(BdsmTheme.shapes.chip)
                    .background(Color.Black.copy(alpha = 0.8f)) // rótulo sobre a miniatura
                    .padding(horizontal = BdsmTheme.spacing.xs, vertical = 1.dp),
            ) {
                Text(
                    text = item.durationLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White,
                    maxLines = 1,
                )
            }
        }

        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = item.name,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (item.isFavorite) {
                    Spacer(modifier = Modifier.width(BdsmTheme.spacing.xs))
                    Icon(Icons.Filled.Star, stringResource(R.string.rec_favorite_cd), tint = BdsmTheme.colors.warning, modifier = Modifier.size(16.dp))
                }
            }
            Text(
                text = "${item.resolutionLabel} • ${item.codecLabel} • ${item.fpsLabel}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "$sizeLabel • ${item.durationLabel}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (item.isCorrupted) CorruptedTag()
        }
    }
}

// ============================================================================
// BARRA DE AÇÕES EM LOTE
// ============================================================================

@Composable
fun BatchActionBar(
    selectedCount: Int,
    onClearSelection: () -> Unit,
    onShareSingle: (() -> Unit)?,
    onSaveToGalleryBatch: () -> Unit,
    onFavoriteBatch: () -> Unit,
    onDeleteBatch: () -> Unit,
) {
    val onBar = MaterialTheme.colorScheme.onPrimary
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.primary)
            .padding(horizontal = BdsmTheme.spacing.sm, vertical = BdsmTheme.spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onClearSelection) {
            Icon(Icons.Filled.Close, stringResource(R.string.rec_sel_cancel), tint = onBar)
        }
        Text(
            text = if (selectedCount == 1) stringResource(R.string.rec_sel_count_one) else stringResource(R.string.rec_sel_count_many, selectedCount),
            style = MaterialTheme.typography.titleMedium,
            color = onBar,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .padding(start = BdsmTheme.spacing.xs),
        )
        if (onShareSingle != null) {
            IconButton(onClick = onShareSingle) {
                Icon(Icons.Filled.Share, stringResource(R.string.rec_sel_share), tint = onBar)
            }
        }
        IconButton(onClick = onSaveToGalleryBatch) {
            Icon(Icons.Filled.Save, stringResource(R.string.rec_sel_save_gallery), tint = onBar)
        }
        IconButton(onClick = onFavoriteBatch) {
            Icon(Icons.Filled.Star, stringResource(R.string.rec_sel_favorite), tint = onBar)
        }
        IconButton(onClick = onDeleteBatch) {
            Icon(Icons.Filled.Delete, stringResource(R.string.rec_sel_delete), tint = onBar)
        }
    }
}

// ============================================================================
// REPRODUZIR / COMPARTILHAR (com aviso quando não há app compatível)
// ============================================================================

/** URI do arquivo local (FileProvider) ou, se ele sumiu, da cópia exportada (SAF). */
private fun playableUri(context: Context, item: RecordingItem): Uri? {
    val file = File(item.filePath)
    if (file.exists()) {
        return FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
    }
    return item.contentUri?.takeIf { it.isNotBlank() }?.let { Uri.parse(it) }
}

fun playRecording(context: Context, item: RecordingItem, onMessage: (String) -> Unit) {
    try {
        val uri = playableUri(context, item)
        if (uri == null) {
            onMessage(context.getString(R.string.rec_msg_file_missing))
            return
        }
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "video/*")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(intent)
    } catch (e: CancellationException) {
        throw e
    } catch (e: ActivityNotFoundException) {
        onMessage(context.getString(R.string.rec_msg_no_player))
    } catch (e: Exception) {
        Log.w(TAG, "Falha ao reproduzir ${item.name}", e)
        onMessage(context.getString(R.string.rec_msg_play_failed))
    }
}

fun shareRecording(context: Context, item: RecordingItem, onMessage: (String) -> Unit) {
    try {
        val uri = playableUri(context, item)
        if (uri == null) {
            onMessage(context.getString(R.string.rec_msg_file_missing))
            return
        }
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "video/*"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, context.getString(R.string.rec_share_chooser)))
    } catch (e: CancellationException) {
        throw e
    } catch (e: ActivityNotFoundException) {
        onMessage(context.getString(R.string.rec_msg_no_share_app))
    } catch (e: Exception) {
        Log.w(TAG, "Falha ao compartilhar ${item.name}", e)
        onMessage(context.getString(R.string.rec_msg_share_failed))
    }
}
