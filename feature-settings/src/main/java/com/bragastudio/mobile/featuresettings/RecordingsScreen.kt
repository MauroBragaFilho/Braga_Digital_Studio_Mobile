package com.bragastudio.mobile.featuresettings

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.LruCache
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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FilterList
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
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// CACHE EM MEMÓRIA RAM PARA THUMBNAILS E METADADOS
private val thumbnailMemoryCache = LruCache<String, Bitmap>(50)
private val durationMemoryCache = LruCache<String, String>(100)

data class RecordingMediaModel(
    val file: File,
    val name: String,
    val sizeFormatted: String,
    val dateFormatted: String,
    val dateGroup: String, // ex: "16 de Julho de 2026"
    val timestamp: Long,
    val durationFormatted: String = "00:00",
    val resolutionStr: String = "1080p",
    val codecStr: String = "H.265",
    val fpsStr: String = "30fps",
    val thumbnail: Bitmap? = null
)

enum class ViewMode { GRID, LIST }
enum class SelectedTab { TODAS, VIDEOS, FAVORITAS }

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun RecordingsScreen(
    viewModel: SettingsViewModel = hiltViewModel(),
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val hwMetrics by viewModel.hardwareMetrics.collectAsState()
    val coroutineScope = rememberCoroutineScope()

    var selectedTab by remember { mutableStateOf(SelectedTab.TODAS) }
    var viewMode by remember { mutableStateOf(ViewMode.GRID) }

    val favoritePaths = remember { mutableStateListOf<String>() }
    var recordingFiles by remember { mutableStateOf<List<RecordingMediaModel>>(emptyList()) }

    // MODO SELEÇÃO MÚLTIPLA
    var isMultiSelectMode by remember { mutableStateOf(false) }
    val selectedMultiPaths = remember { mutableStateListOf<String>() }

    // CARREGAMENTO OTIMIZADO EM DUAS ETAPAS (Instant Fast Load + Background Enrichment)
    LaunchedEffect(Unit) {
        // Etapa 1: Carregamento Fast (< 5ms) dos dados rápidos
        val fastList = loadRecordingsFast(context)
        recordingFiles = fastList

        // Etapa 2: Processamento Assíncrono Paralelo de Thumbnails e Duração
        withContext(Dispatchers.IO) {
            val enrichedList = enrichRecordingsWithThumbnailsParallel(context, fastList)
            withContext(Dispatchers.Main) {
                recordingFiles = enrichedList
            }
        }
    }

    // Filtrar Mídias com Base nas Abas
    val displayedRecordings = remember(recordingFiles, selectedTab, favoritePaths.toList()) {
        when (selectedTab) {
            SelectedTab.TODAS, SelectedTab.VIDEOS -> recordingFiles.sortedByDescending { it.timestamp }
            SelectedTab.FAVORITAS -> recordingFiles.filter { favoritePaths.contains(it.file.absolutePath) }.sortedByDescending { it.timestamp }
        }
    }

    val groupedByDate = remember(displayedRecordings) {
        displayedRecordings.groupBy { it.dateGroup }
    }

    val configuration = LocalConfiguration.current
    val isPortrait = configuration.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT

    LaunchedEffect(isPortrait) {
        viewMode = if (isPortrait) ViewMode.LIST else ViewMode.GRID
    }

    Scaffold(
        containerColor = Color(0xFF0D0D0F)
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .background(Color(0xFF0D0D0F))
        ) {
            // 1. TOP BAR COM MÉTRICAS DO DISPOSITIVO
            if (isMultiSelectMode) {
                BatchActionBar(
                    selectedCount = selectedMultiPaths.size,
                    onClearSelection = {
                        selectedMultiPaths.clear()
                        isMultiSelectMode = false
                    },
                    onSaveToGalleryBatch = {
                        val toSave = recordingFiles.filter { selectedMultiPaths.contains(it.file.absolutePath) }
                        toSave.forEach { saveVideoToGalleryMediaStore(context, it.file) }
                        selectedMultiPaths.clear()
                        isMultiSelectMode = false
                    },
                    onFavoriteBatch = {
                        selectedMultiPaths.forEach { path ->
                            if (!favoritePaths.contains(path)) favoritePaths.add(path)
                        }
                        selectedMultiPaths.clear()
                        isMultiSelectMode = false
                    },
                    onDeleteBatch = {
                        val toDelete = recordingFiles.filter { selectedMultiPaths.contains(it.file.absolutePath) }
                        toDelete.forEach { deleteVideoFileReal(it.file) }
                        coroutineScope.launch {
                            val fast = loadRecordingsFast(context)
                            recordingFiles = fast
                            recordingFiles = enrichRecordingsWithThumbnailsParallel(context, fast)
                        }
                        selectedMultiPaths.clear()
                        isMultiSelectMode = false
                    }
                )
            } else {
                RecordingsTopHeader(
                    onNavigateBack = onNavigateBack,
                    viewMode = viewMode,
                    isPortrait = isPortrait,
                    tempCelsius = hwMetrics.temperatureCelsius,
                    storageFreeGB = hwMetrics.storageFreeGB,
                    batteryPct = hwMetrics.batteryPercentage,
                    onToggleViewMode = { viewMode = if (viewMode == ViewMode.GRID) ViewMode.LIST else ViewMode.GRID }
                )
            }

            // 2. NAVEGAÇÃO POR ABAS (TODAS / VIDEOS / FAVORITAS)
            RecordingsTabsHeader(
                selectedTab = selectedTab,
                isPortrait = isPortrait,
                onSelectTab = { selectedTab = it }
            )

            // 3. CONTEÚDO PRINCIPAL (LISTA OU GRADE AGRUPADA POR DATA)
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 8.dp)
            ) {
                RecordingsContentGrouped(
                    groupedByDate = groupedByDate,
                    viewMode = viewMode,
                    isPortrait = isPortrait,
                    isMultiSelectMode = isMultiSelectMode,
                    selectedMultiPaths = selectedMultiPaths,
                    favoritePaths = favoritePaths,
                    onToggleFavorite = { path ->
                        if (favoritePaths.contains(path)) favoritePaths.remove(path) else favoritePaths.add(path)
                    },
                    onShareFile = { file -> shareVideoFileReal(context, file) },
                    onDeleteFile = { file ->
                        deleteVideoFileReal(file)
                        coroutineScope.launch {
                            val fast = loadRecordingsFast(context)
                            recordingFiles = fast
                            recordingFiles = enrichRecordingsWithThumbnailsParallel(context, fast)
                        }
                    },
                    onSaveToGallery = { file -> saveVideoToGalleryMediaStore(context, file) },
                    onItemClick = { item ->
                        if (isMultiSelectMode) {
                            if (selectedMultiPaths.contains(item.file.absolutePath)) {
                                selectedMultiPaths.remove(item.file.absolutePath)
                                if (selectedMultiPaths.isEmpty()) isMultiSelectMode = false
                            } else {
                                selectedMultiPaths.add(item.file.absolutePath)
                            }
                        } else {
                            // ✅ CLIQUE SIMPLES: REPRODUZIR/ASSISTIR AO VÍDEO
                            playVideoFileReal(context, item.file)
                        }
                    },
                    onItemLongClick = { item ->
                        // ✅ SEGURAR (LONG PRESS): ATIVAR SELEÇÃO E OPÇÕES
                        isMultiSelectMode = true
                        if (!selectedMultiPaths.contains(item.file.absolutePath)) {
                            selectedMultiPaths.add(item.file.absolutePath)
                        }
                    }
                )
            }

            // 4. BARRA DE RODAPÉ (BOTTOM BAR)
            RecordingsBottomBar(
                isPortrait = isPortrait,
                storageFreeGB = hwMetrics.storageFreeGB,
                isMultiSelectMode = isMultiSelectMode,
                onSelectClick = { isMultiSelectMode = !isMultiSelectMode }
            )
        }
    }
}

// ============================================================================
// TOP HEADER COM MÉTRICAS DO DISPOSITIVO
// ============================================================================

@Composable
fun RecordingsTopHeader(
    onNavigateBack: () -> Unit,
    viewMode: ViewMode,
    isPortrait: Boolean,
    tempCelsius: Float,
    storageFreeGB: Float,
    batteryPct: Int,
    onToggleViewMode: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(
                onClick = onNavigateBack,
                modifier = Modifier.size(36.dp)
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Voltar",
                    tint = Color.White
                )
            }
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = "Gravações",
                color = Color.White,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold
            )
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Métricas de Hardware (Temp, Storage, Battery)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("🌡️", fontSize = 12.sp)
                    Spacer(modifier = Modifier.width(2.dp))
                    Column {
                        Text(text = "${String.format("%.1f", tempCelsius)}°C", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        Text(text = "Temp", color = Color.Gray, fontSize = 8.sp)
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(imageVector = Icons.Filled.SdStorage, contentDescription = null, tint = Color.White, modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(2.dp))
                    Column {
                        Text(text = "${String.format("%.1f", storageFreeGB)} GB", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        Text(text = "Storage", color = Color.Gray, fontSize = 8.sp)
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(imageVector = Icons.Filled.BatteryFull, contentDescription = null, tint = Color.White, modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(2.dp))
                    Column {
                        Text(text = "$batteryPct%", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        Text(text = "Battery", color = Color.Gray, fontSize = 8.sp)
                    }
                }
            }

            // Ícones de Busca, Filtro e Exibição
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                IconButton(onClick = { }, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Filled.Search, "Buscar", tint = Color.White, modifier = Modifier.size(18.dp))
                }
                IconButton(onClick = { }, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Filled.FilterList, "Filtrar", tint = Color.White, modifier = Modifier.size(18.dp))
                }

                if (!isPortrait) {
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0xFF1E1E24))
                            .padding(2.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(if (viewMode == ViewMode.GRID) Color(0xFFE50914) else Color.Transparent)
                                .clickable { if (viewMode != ViewMode.GRID) onToggleViewMode() }
                                .padding(6.dp)
                        ) {
                            Icon(Icons.Filled.GridView, null, tint = Color.White, modifier = Modifier.size(16.dp))
                        }
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(if (viewMode == ViewMode.LIST) Color(0xFFE50914) else Color.Transparent)
                                .clickable { if (viewMode != ViewMode.LIST) onToggleViewMode() }
                                .padding(6.dp)
                        ) {
                            Icon(Icons.AutoMirrored.Filled.List, null, tint = Color.White, modifier = Modifier.size(16.dp))
                        }
                    }
                } else {
                    IconButton(onClick = { }, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Filled.MoreVert, "Mais", tint = Color.White, modifier = Modifier.size(18.dp))
                    }
                }
            }
        }
    }
}

// ============================================================================
// NAVEGAÇÃO POR ABAS
// ============================================================================

@Composable
fun RecordingsTabsHeader(
    selectedTab: SelectedTab,
    isPortrait: Boolean,
    onSelectTab: (SelectedTab) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        val tabs = if (isPortrait) {
            listOf(SelectedTab.TODAS to "TODAS", SelectedTab.FAVORITAS to "FAVORITAS")
        } else {
            listOf(SelectedTab.TODAS to "Todas", SelectedTab.VIDEOS to "Videos", SelectedTab.FAVORITAS to "Favoritos")
        }

        tabs.forEach { (tab, label) ->
            val isSelected = selectedTab == tab
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .clickable { onSelectTab(tab) }
                    .padding(vertical = 6.dp)
            ) {
                Text(
                    text = label,
                    color = if (isSelected) Color.White else Color.Gray,
                    fontSize = 13.sp,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                )
                Spacer(modifier = Modifier.height(4.dp))
                Box(
                    modifier = Modifier
                        .height(2.dp)
                        .width(if (isSelected) 36.dp else 0.dp)
                        .background(if (isSelected) Color(0xFFE50914) else Color.Transparent)
                )
            }
        }
    }
}

// ============================================================================
// CONTEÚDO DE GRAVAÇÕES AGRUPADO POR DATA
// ============================================================================

@Composable
fun RecordingsContentGrouped(
    groupedByDate: Map<String, List<RecordingMediaModel>>,
    viewMode: ViewMode,
    isPortrait: Boolean,
    isMultiSelectMode: Boolean,
    selectedMultiPaths: List<String>,
    favoritePaths: List<String>,
    onToggleFavorite: (String) -> Unit,
    onShareFile: (File) -> Unit,
    onDeleteFile: (File) -> Unit,
    onSaveToGallery: (File) -> Unit,
    onItemClick: (RecordingMediaModel) -> Unit,
    onItemLongClick: (RecordingMediaModel) -> Unit
) {
    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        if (groupedByDate.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 40.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Nenhuma gravação encontrada.",
                    color = Color.Gray,
                    fontSize = 13.sp
                )
            }
        } else {
            groupedByDate.forEach { (dateGroup, items) ->
                var isExpanded by remember { mutableStateOf(true) }

                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { isExpanded = !isExpanded }
                            .padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = dateGroup,
                            color = Color.White,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold
                        )

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "${items.size} itens",
                                color = Color.Gray,
                                fontSize = 11.sp
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Icon(
                                imageVector = if (isExpanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                                contentDescription = null,
                                tint = Color.Gray,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }

                    if (isExpanded) {
                        if (viewMode == ViewMode.GRID && !isPortrait) {
                            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                items.chunked(4).forEach { rowItems ->
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                                    ) {
                                        rowItems.forEach { item ->
                                            VideoCardLandscape(
                                                item = item,
                                                isSelectedMulti = selectedMultiPaths.contains(item.file.absolutePath),
                                                isMultiSelectMode = isMultiSelectMode,
                                                isFavorite = favoritePaths.contains(item.file.absolutePath),
                                                onToggleFavorite = { onToggleFavorite(item.file.absolutePath) },
                                                onShare = { onShareFile(item.file) },
                                                onDelete = { onDeleteFile(item.file) },
                                                onSaveToGallery = { onSaveToGallery(item.file) },
                                                onClick = { onItemClick(item) },
                                                onLongClick = { onItemLongClick(item) },
                                                modifier = Modifier.weight(1f)
                                            )
                                        }
                                        repeat(4 - rowItems.size) {
                                            Spacer(modifier = Modifier.weight(1f))
                                        }
                                    }
                                }
                            }
                        } else {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                items.forEach { item ->
                                    VideoRowPortrait(
                                        item = item,
                                        isSelectedMulti = selectedMultiPaths.contains(item.file.absolutePath),
                                        isMultiSelectMode = isMultiSelectMode,
                                        isFavorite = favoritePaths.contains(item.file.absolutePath),
                                        onToggleFavorite = { onToggleFavorite(item.file.absolutePath) },
                                        onShare = { onShareFile(item.file) },
                                        onDelete = { onDeleteFile(item.file) },
                                        onSaveToGallery = { onSaveToGallery(item.file) },
                                        onClick = { onItemClick(item) },
                                        onLongClick = { onItemLongClick(item) }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// ============================================================================
// CARD DE VÍDEO PAISAGEM (GRID 4 COLUNAS)
// ============================================================================

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun VideoCardLandscape(
    item: RecordingMediaModel,
    isSelectedMulti: Boolean,
    isMultiSelectMode: Boolean,
    isFavorite: Boolean,
    onToggleFavorite: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
    onSaveToGallery: () -> Unit,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    var showMenu by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF16161A))
            .border(1.dp, if (isSelectedMulti) Color(0xFFE50914) else Color.Transparent, RoundedCornerShape(12.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(115.dp)
                .clip(RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp))
                .background(Color.Black),
            contentAlignment = Alignment.Center
        ) {
            if (item.thumbnail != null) {
                Image(
                    bitmap = item.thumbnail.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Icon(Icons.Filled.Movie, null, tint = Color.Gray, modifier = Modifier.size(36.dp))
            }

            // Ícone de Play para indicar que é reproduzível ao clicar
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.5f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Filled.PlayArrow, "Assistir Vídeo", tint = Color.White, modifier = Modifier.size(22.dp))
            }

            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(6.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color.Black.copy(alpha = 0.8f))
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            ) {
                Text(
                    text = item.durationFormatted,
                    color = Color.White,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            if (isFavorite) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .size(22.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.6f)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(text = "⭐", fontSize = 11.sp)
                }
            }

            if (isMultiSelectMode) {
                Box(modifier = Modifier.align(Alignment.TopStart).padding(4.dp)) {
                    Checkbox(
                        checked = isSelectedMulti,
                        onCheckedChange = { onClick() },
                        colors = CheckboxDefaults.colors(checkedColor = Color(0xFFE50914), uncheckedColor = Color.White)
                    )
                }
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp)
        ) {
            Column(modifier = Modifier.padding(end = 20.dp)) {
                Text(
                    text = item.name,
                    color = Color.White,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "${item.resolutionStr} • ${item.codecStr} • ${item.fpsStr}",
                    color = Color.Gray,
                    fontSize = 9.sp
                )
                Text(
                    text = "${item.sizeFormatted} • ${item.durationFormatted}",
                    color = Color.Gray,
                    fontSize = 9.sp
                )
            }

            Box(modifier = Modifier.align(Alignment.BottomEnd)) {
                IconButton(
                    onClick = { showMenu = true },
                    modifier = Modifier.size(20.dp)
                ) {
                    Icon(Icons.Filled.MoreVert, "Mais", tint = Color.Gray, modifier = Modifier.size(14.dp))
                }

                DropdownMenu(
                    expanded = showMenu,
                    onDismissRequest = { showMenu = false },
                    modifier = Modifier.background(Color(0xFF1E1E24))
                ) {
                    DropdownMenuItem(
                        text = { Text("Assistir Vídeo", color = Color.White, fontSize = 12.sp) },
                        onClick = { showMenu = false; onClick() },
                        leadingIcon = { Icon(Icons.Filled.PlayArrow, null, tint = Color.White, modifier = Modifier.size(16.dp)) }
                    )
                    DropdownMenuItem(
                        text = { Text("Compartilhar", color = Color.White, fontSize = 12.sp) },
                        onClick = { showMenu = false; onShare() },
                        leadingIcon = { Icon(Icons.Filled.Share, null, tint = Color.White, modifier = Modifier.size(16.dp)) }
                    )
                    DropdownMenuItem(
                        text = { Text(if (isFavorite) "Remover Favorito" else "Favoritar", color = Color.White, fontSize = 12.sp) },
                        onClick = { showMenu = false; onToggleFavorite() },
                        leadingIcon = { Icon(Icons.Filled.Save, null, tint = Color.White, modifier = Modifier.size(16.dp)) }
                    )
                    DropdownMenuItem(
                        text = { Text("Salvar na Galeria", color = Color.White, fontSize = 12.sp) },
                        onClick = { showMenu = false; onSaveToGallery() },
                        leadingIcon = { Icon(Icons.Filled.Save, null, tint = Color.White, modifier = Modifier.size(16.dp)) }
                    )
                    DropdownMenuItem(
                        text = { Text("Excluir", color = Color(0xFFE50914), fontSize = 12.sp) },
                        onClick = { showMenu = false; onDelete() },
                        leadingIcon = { Icon(Icons.Filled.Delete, null, tint = Color(0xFFE50914), modifier = Modifier.size(16.dp)) }
                    )
                }
            }
        }
    }
}

// ============================================================================
// ROW DE VÍDEO RETRATO
// ============================================================================

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun VideoRowPortrait(
    item: RecordingMediaModel,
    isSelectedMulti: Boolean,
    isMultiSelectMode: Boolean,
    isFavorite: Boolean,
    onToggleFavorite: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
    onSaveToGallery: () -> Unit,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0xFF141418))
            .border(1.dp, if (isSelectedMulti) Color(0xFFE50914) else Color.Transparent, RoundedCornerShape(10.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (isMultiSelectMode) {
                    Checkbox(
                        checked = isSelectedMulti,
                        onCheckedChange = { onClick() },
                        colors = CheckboxDefaults.colors(checkedColor = Color(0xFFE50914), uncheckedColor = Color.Gray)
                    )
                }

                Box(
                    modifier = Modifier
                        .size(100.dp, 60.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color.Black),
                    contentAlignment = Alignment.Center
                ) {
                    if (item.thumbnail != null) {
                        Image(
                            bitmap = item.thumbnail.asImageBitmap(),
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        Icon(Icons.Filled.Movie, null, tint = Color.Gray, modifier = Modifier.size(24.dp))
                    }

                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(CircleShape)
                            .background(Color.Black.copy(alpha = 0.5f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Filled.PlayArrow, "Assistir", tint = Color.White, modifier = Modifier.size(16.dp))
                    }

                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(4.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(Color.Black.copy(alpha = 0.8f))
                            .padding(horizontal = 4.dp, vertical = 1.dp)
                    ) {
                        Text(text = item.durationFormatted, color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                    }
                }

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = item.name,
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "${item.resolutionStr} • ${item.codecStr} • ${item.fpsStr}",
                        color = Color.Gray,
                        fontSize = 10.sp
                    )
                    Text(
                        text = "${item.sizeFormatted} • ${item.durationFormatted}",
                        color = Color.Gray,
                        fontSize = 10.sp
                    )
                }
            }

            Box {
                IconButton(onClick = { showMenu = true }, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Filled.MoreVert, "Mais", tint = Color.Gray, modifier = Modifier.size(16.dp))
                }

                DropdownMenu(
                    expanded = showMenu,
                    onDismissRequest = { showMenu = false },
                    modifier = Modifier.background(Color(0xFF1E1E24))
                ) {
                    DropdownMenuItem(
                        text = { Text("Assistir Vídeo", color = Color.White, fontSize = 12.sp) },
                        onClick = { showMenu = false; onClick() },
                        leadingIcon = { Icon(Icons.Filled.PlayArrow, null, tint = Color.White, modifier = Modifier.size(16.dp)) }
                    )
                    DropdownMenuItem(
                        text = { Text("Compartilhar", color = Color.White, fontSize = 12.sp) },
                        onClick = { showMenu = false; onShare() },
                        leadingIcon = { Icon(Icons.Filled.Share, null, tint = Color.White, modifier = Modifier.size(16.dp)) }
                    )
                    DropdownMenuItem(
                        text = { Text(if (isFavorite) "Remover Favorito" else "Favoritar", color = Color.White, fontSize = 12.sp) },
                        onClick = { showMenu = false; onToggleFavorite() },
                        leadingIcon = { Icon(Icons.Filled.Save, null, tint = Color.White, modifier = Modifier.size(16.dp)) }
                    )
                    DropdownMenuItem(
                        text = { Text("Salvar na Galeria", color = Color.White, fontSize = 12.sp) },
                        onClick = { showMenu = false; onSaveToGallery() },
                        leadingIcon = { Icon(Icons.Filled.Save, null, tint = Color.White, modifier = Modifier.size(16.dp)) }
                    )
                    DropdownMenuItem(
                        text = { Text("Excluir", color = Color(0xFFE50914), fontSize = 12.sp) },
                        onClick = { showMenu = false; onDelete() },
                        leadingIcon = { Icon(Icons.Filled.Delete, null, tint = Color(0xFFE50914), modifier = Modifier.size(16.dp)) }
                    )
                }
            }
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
    onSaveToGalleryBatch: () -> Unit,
    onFavoriteBatch: () -> Unit,
    onDeleteBatch: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFFE50914))
            .padding(horizontal = 14.dp, vertical = 8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onClearSelection, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Filled.Close, "Cancelar", tint = Color.White)
                }
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "$selectedCount selecionados",
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                IconButton(onClick = onSaveToGalleryBatch, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Filled.Save, "Salvar na Galeria", tint = Color.White, modifier = Modifier.size(20.dp))
                }
                IconButton(onClick = onFavoriteBatch, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Filled.Save, "Favoritar Selecionados", tint = Color.White, modifier = Modifier.size(20.dp))
                }
                IconButton(onClick = onDeleteBatch, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Filled.Delete, "Deletar Selecionados", tint = Color.White, modifier = Modifier.size(20.dp))
                }
            }
        }
    }
}

// ============================================================================
// BARRA DE RODAPÉ
// ============================================================================

@Composable
fun RecordingsBottomBar(
    isPortrait: Boolean,
    storageFreeGB: Float,
    isMultiSelectMode: Boolean,
    onSelectClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF121215))
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color.White.copy(alpha = 0.08f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Filled.SdStorage, null, tint = Color.White, modifier = Modifier.size(16.dp))
                }

                Column {
                    Text(
                        text = "Armazenamento disponível",
                        color = Color.Gray,
                        fontSize = 10.sp
                    )
                    Text(
                        text = "${String.format("%.1f", storageFreeGB)} GB / 128 GB",
                        color = Color.White,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            if (isPortrait) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFFE50914))
                        .clickable { onSelectClick() }
                        .padding(horizontal = 24.dp, vertical = 10.dp)
                ) {
                    Text(
                        text = if (isMultiSelectMode) "CANCELAR" else "SELECIONAR",
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(text = "Ordenar por", color = Color.Gray, fontSize = 11.sp)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(text = "Data (Mais recente) ∨", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

// ============================================================================
// CARREGAMENTO FAST + PARALELO DE MÍDIAS E MINIATURAS (OTIMIZAÇÃO EXTREMA)
// ============================================================================

suspend fun loadRecordingsFast(context: Context): List<RecordingMediaModel> = withContext(Dispatchers.IO) {
    val dir = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: context.filesDir
    val files = dir.listFiles { f -> f.extension.lowercase() in listOf("mp4", "mov", "mkv") } ?: emptyArray()

    val dateFormat = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault())
    val monthFormat = SimpleDateFormat("d 'de' MMMM 'de' yyyy", Locale("pt", "BR"))

    return@withContext files.map { file ->
        val cacheKey = "${file.absolutePath}_${file.lastModified()}"
        val cachedThumb = thumbnailMemoryCache.get(cacheKey)
        val cachedDur = durationMemoryCache.get(cacheKey) ?: "00:00"

        val sizeKb = file.length() / 1024f
        val sizeFormatted = if (sizeKb > 1024) String.format("%.1f GB", sizeKb / (1024f * 1024f)) else String.format("%.0f MB", sizeKb / 1024f)
        val dateGroup = monthFormat.format(Date(file.lastModified()))

        RecordingMediaModel(
            file = file,
            name = file.name,
            sizeFormatted = sizeFormatted,
            dateFormatted = dateFormat.format(Date(file.lastModified())),
            dateGroup = dateGroup,
            timestamp = file.lastModified(),
            durationFormatted = cachedDur,
            thumbnail = cachedThumb
        )
    }
}

suspend fun enrichRecordingsWithThumbnailsParallel(
    context: Context,
    items: List<RecordingMediaModel>
): List<RecordingMediaModel> = withContext(Dispatchers.IO) {
    val retriever = MediaMetadataRetriever()

    val deferreds = items.map { item ->
        async {
            val cacheKey = "${item.file.absolutePath}_${item.file.lastModified()}"
            var thumb = thumbnailMemoryCache.get(cacheKey)
            var dur = durationMemoryCache.get(cacheKey)

            if (thumb == null || dur == null) {
                try {
                    retriever.setDataSource(item.file.absolutePath)
                    if (thumb == null) {
                        thumb = retriever.getFrameAtTime(1000000)
                        if (thumb != null) {
                            thumbnailMemoryCache.put(cacheKey, thumb)
                        }
                    }
                    if (dur == null) {
                        val durMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                        val sec = (durMs / 1000) % 60
                        val min = (durMs / (1000 * 60)) % 60
                        dur = String.format("%02d:%02d", min, sec)
                        durationMemoryCache.put(cacheKey, dur)
                    }
                } catch (_: Exception) {}
            }

            item.copy(
                thumbnail = thumb ?: item.thumbnail,
                durationFormatted = dur ?: item.durationFormatted
            )
        }
    }

    return@withContext deferreds.awaitAll()
}

// ✅ CLIQUE SIMPLES: ABRIR E ASSISTIR AO VÍDEO NO PLAYER NATIVO DO CELULAR
fun playVideoFileReal(context: Context, file: File) {
    try {
        val uri: Uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "video/*")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(intent)
    } catch (_: Exception) {}
}

fun saveVideoToGalleryMediaStore(context: Context, file: File) {
    try {
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, file.name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/BDSM")
            }
        }
        val uri = context.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
        if (uri != null) {
            context.contentResolver.openOutputStream(uri)?.use { out ->
                FileInputStream(file).use { input -> input.copyTo(out) }
            }
        }
    } catch (_: Exception) {}
}

fun shareVideoFileReal(context: Context, file: File) {
    try {
        val uri: Uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "video/*"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Compartilhar Gravação"))
    } catch (_: Exception) {}
}

fun deleteVideoFileReal(file: File) {
    try {
        if (file.exists()) file.delete()
    } catch (_: Exception) {}
}
