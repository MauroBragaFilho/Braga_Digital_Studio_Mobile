package com.bragastudio.mobile.featurehome

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.core.content.FileProvider
import coil.compose.AsyncImage
import com.bragastudio.mobile.core.database.RecordingEntity
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecordingsScreen(
    onNavigateUp: () -> Unit,
    viewModel: RecordingsViewModel = hiltViewModel()
) {
    val recordings by viewModel.filteredRecordings.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val metrics by viewModel.hardwareMetrics.collectAsState()
    
    val isFavoritesOnly by viewModel.isFavoritesOnly.collectAsState()
    val isGridView by viewModel.isGridView.collectAsState()
    val sortOrder by viewModel.sortOrder.collectAsState()
    
    val context = LocalContext.current
    
    // Multi-select state
    var selectedItems by remember { mutableStateOf(setOf<String>()) }
    val isSelectionMode = selectedItems.isNotEmpty()

    Scaffold(
        containerColor = Color(0xFF0F0F0F),
        bottomBar = {
            if (!isSelectionMode) {
                RecordingsBottomBar(
                    freeGB = metrics.storageFreeGB, 
                    totalGB = metrics.storageTotalGB,
                    currentSortOrder = sortOrder,
                    onSortOrderChange = { newOrder -> viewModel.setSortOrder(newOrder) }
                )
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Header
            if (isSelectionMode) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { selectedItems = emptySet() }, modifier = Modifier.size(36.dp)) {
                            Icon(Icons.Filled.Close, contentDescription = "Cancelar", tint = Color.White, modifier = Modifier.size(20.dp))
                        }
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("${selectedItems.size} selecionados", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        IconButton(onClick = { 
                            val selectedRecordings = recordings.filter { it.id in selectedItems }
                            selectedRecordings.forEach { recording ->
                                viewModel.toggleFavorite(recording.id)
                            }
                            selectedItems = emptySet()
                        }) {
                            Icon(Icons.Filled.Star, contentDescription = "Favoritar", tint = Color.White)
                        }
                        IconButton(onClick = {
                            selectedItems.forEach { id -> viewModel.saveToGallery(id, context) }
                            selectedItems = emptySet()
                            android.widget.Toast.makeText(context, "Salvos na Galeria", android.widget.Toast.LENGTH_SHORT).show()
                        }) {
                            Icon(Icons.Filled.Download, contentDescription = "Salvar na Galeria", tint = Color.White)
                        }
                        IconButton(onClick = { 
                            viewModel.deleteSelectedRecordings(selectedItems)
                            selectedItems = emptySet()
                        }) {
                            Icon(Icons.Filled.Delete, contentDescription = "Deletar", tint = Color.White)
                        }
                    }
                }
            } else {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = onNavigateUp, modifier = Modifier.size(36.dp)) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Voltar", tint = Color.White, modifier = Modifier.size(20.dp))
                        }
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Gravações", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                        TabItem(text = "Todas", isSelected = !isFavoritesOnly, onClick = { viewModel.setFavoritesOnly(false) })
                        TabItem(text = "Favoritos", isSelected = isFavoritesOnly, onClick = { viewModel.setFavoritesOnly(true) })
                    }

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // View Toggle (Grid / List)
                        Row(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(Color(0xFF1E1E1E))
                                .padding(2.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(if (isGridView) Color(0xFFFF1744) else Color.Transparent)
                                    .clickable { viewModel.setGridView(true) }
                                    .padding(6.dp)
                            ) {
                                Icon(Icons.Filled.GridView, contentDescription = "Grid", tint = Color.White, modifier = Modifier.size(16.dp))
                            }
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(if (!isGridView) Color(0xFFFF1744) else Color.Transparent)
                                    .clickable { viewModel.setGridView(false) }
                                    .padding(6.dp)
                            ) {
                                Icon(Icons.Filled.ViewList, contentDescription = "List", tint = Color.White, modifier = Modifier.size(16.dp))
                            }
                        }
                    }
                }
            }

            HorizontalDivider(color = Color.DarkGray, thickness = 1.dp)

            if (isLoading && recordings.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Color(0xFFFF1744))
                }
            } else if (recordings.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("SEM VÍDEOS", color = Color.Gray, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                }
            } else {
                val groupedRecordings = recordings.groupBy { formatTimestamp(it.createdAt) }
                
                if (isGridView) {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = 100.dp),
                        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        groupedRecordings.forEach { (date, items) ->
                            item(span = { GridItemSpan(maxLineSpan) }) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(bottom = 8.dp, top = 8.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(date, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                                    Text("${items.size} itens", color = Color.Gray, fontSize = 14.sp)
                                }
                            }
                            items(items, key = { it.id }) { recording ->
                                RecordingItemCard(
                                    recording = recording,
                                    isGridView = true,
                                    isSelected = selectedItems.contains(recording.id),
                                    onPlay = {
                                        if (selectedItems.isNotEmpty()) {
                                            if (selectedItems.contains(recording.id)) selectedItems -= recording.id else selectedItems += recording.id
                                        } else {
                                            val file = File(recording.filePath)
                                            if (file.exists()) {
                                                val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
                                                val intent = Intent(Intent.ACTION_VIEW).apply {
                                                    setDataAndType(uri, "video/mp4")
                                                    flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
                                                }
                                                try { context.startActivity(Intent.createChooser(intent, "Reproduzir vídeo")) } 
                                                catch (e: Exception) { android.widget.Toast.makeText(context, "Nenhum reprodutor encontrado.", android.widget.Toast.LENGTH_SHORT).show() }
                                            }
                                        }
                                    },
                                    onLongClick = {
                                        if (selectedItems.contains(recording.id)) selectedItems -= recording.id else selectedItems += recording.id
                                    },
                                    onToggleFavorite = { viewModel.toggleFavorite(recording.id) },
                                    onDelete = { viewModel.deleteRecording(recording.id) },
                                    onRename = { newName -> viewModel.renameRecording(recording.id, newName) },
                                    onSaveToGallery = { viewModel.saveToGallery(recording.id, context) }
                                )
                            }
                        }
                    }
                } else {
                    LazyColumn(
                        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        groupedRecordings.forEach { (date, items) ->
                            item {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(bottom = 8.dp, top = 8.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(date, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                                    Text("${items.size} itens", color = Color.Gray, fontSize = 14.sp)
                                }
                            }
                            items(items, key = { it.id }) { recording ->
                                RecordingItemCard(
                                    recording = recording,
                                    isGridView = false,
                                    isSelected = selectedItems.contains(recording.id),
                                    onPlay = {
                                        if (selectedItems.isNotEmpty()) {
                                            if (selectedItems.contains(recording.id)) selectedItems -= recording.id else selectedItems += recording.id
                                        } else {
                                            val file = File(recording.filePath)
                                            if (file.exists()) {
                                                val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
                                                val intent = Intent(Intent.ACTION_VIEW).apply {
                                                    setDataAndType(uri, "video/mp4")
                                                    flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
                                                }
                                                try { context.startActivity(Intent.createChooser(intent, "Reproduzir vídeo")) } 
                                                catch (e: Exception) { android.widget.Toast.makeText(context, "Nenhum reprodutor encontrado.", android.widget.Toast.LENGTH_SHORT).show() }
                                            }
                                        }
                                    },
                                    onLongClick = {
                                        if (selectedItems.contains(recording.id)) selectedItems -= recording.id else selectedItems += recording.id
                                    },
                                    onToggleFavorite = { viewModel.toggleFavorite(recording.id) },
                                    onDelete = { viewModel.deleteRecording(recording.id) },
                                    onRename = { newName -> viewModel.renameRecording(recording.id, newName) },
                                    onSaveToGallery = { viewModel.saveToGallery(recording.id, context) }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun TabItem(text: String, isSelected: Boolean, onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.clickable { onClick() }
    ) {
        Text(
            text = text,
            color = if (isSelected) Color(0xFFFF1744) else Color.Gray,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
            fontSize = 14.sp
        )
        if (isSelected) {
            Spacer(modifier = Modifier.height(4.dp))
            Box(modifier = Modifier.height(2.dp).width(24.dp).background(Color(0xFFFF1744)))
        }
    }
}

@Composable
fun RecordingsBottomBar(freeGB: Float, totalGB: Float, currentSortOrder: RecordingsViewModel.SortOrder, onSortOrderChange: (RecordingsViewModel.SortOrder) -> Unit) {
    var sortMenuExpanded by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF161616))
            .padding(horizontal = 24.dp, vertical = 16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.weight(1f)
        ) {
            Icon(Icons.Filled.SdStorage, contentDescription = null, tint = Color.White)
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Armazenamento disponível", color = Color.Gray, fontSize = 12.sp)
                    Text("${String.format(Locale.US, "%.1f", freeGB)} GB / ${totalGB.toInt()} GB", color = Color.White, fontSize = 12.sp)
                }
                Spacer(modifier = Modifier.height(8.dp))
                val progress = if (totalGB > 0) ((totalGB - freeGB) / totalGB).coerceIn(0f, 1f) else 0f
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
                    color = Color(0xFFFF1744),
                    trackColor = Color.DarkGray
                )
            }
            Spacer(modifier = Modifier.width(32.dp))
        }

        Box {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.clickable { sortMenuExpanded = true }
            ) {
                Text("Ordenar por", color = Color.Gray, fontSize = 14.sp)
                Spacer(modifier = Modifier.width(16.dp))
                val sortLabel = when (currentSortOrder) {
                    RecordingsViewModel.SortOrder.DATE_DESC -> "Data (Mais recente)"
                    RecordingsViewModel.SortOrder.DATE_ASC -> "Data (Mais antigo)"
                    RecordingsViewModel.SortOrder.SIZE_DESC -> "Tamanho (Maior)"
                    RecordingsViewModel.SortOrder.SIZE_ASC -> "Tamanho (Menor)"
                }
                Text(sortLabel, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            }
            DropdownMenu(
                expanded = sortMenuExpanded,
                onDismissRequest = { sortMenuExpanded = false }
            ) {
                DropdownMenuItem(text = { Text("Data (Mais recente)") }, onClick = { onSortOrderChange(RecordingsViewModel.SortOrder.DATE_DESC); sortMenuExpanded = false })
                DropdownMenuItem(text = { Text("Data (Mais antigo)") }, onClick = { onSortOrderChange(RecordingsViewModel.SortOrder.DATE_ASC); sortMenuExpanded = false })
                DropdownMenuItem(text = { Text("Tamanho (Maior)") }, onClick = { onSortOrderChange(RecordingsViewModel.SortOrder.SIZE_DESC); sortMenuExpanded = false })
                DropdownMenuItem(text = { Text("Tamanho (Menor)") }, onClick = { onSortOrderChange(RecordingsViewModel.SortOrder.SIZE_ASC); sortMenuExpanded = false })
            }
        }
    }
}

@Composable
fun RecordingItemCard(
    recording: RecordingEntity,
    isGridView: Boolean,
    isSelected: Boolean,
    onPlay: () -> Unit,
    onLongClick: () -> Unit,
    onToggleFavorite: () -> Unit,
    onDelete: () -> Unit,
    onRename: (String) -> Unit,
    onSaveToGallery: () -> Unit
) {
    val durationText = formatDuration(recording.durationMs)
    val sizeMb = recording.sizeBytes / (1024 * 1024)
    var showMenu by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }

    if (showRenameDialog) {
        var newName by remember { mutableStateOf(recording.fileName) }
        AlertDialog(
            onDismissRequest = { showRenameDialog = false },
            containerColor = Color(0xFF1E1E1E),
            title = { Text("Renomear Vídeo", color = Color.White, fontWeight = FontWeight.Bold) },
            text = {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    label = { Text("Novo nome", color = Color.Gray) },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedBorderColor = Color(0xFFFF1744),
                        unfocusedBorderColor = Color.Gray,
                    ),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (newName.isNotBlank()) {
                        onRename(newName)
                    }
                    showRenameDialog = false
                }) {
                    Text("Salvar", color = Color(0xFFFF1744))
                }
            },
            dismissButton = {
                TextButton(onClick = { showRenameDialog = false }) {
                    Text("Cancelar", color = Color.Gray)
                }
            }
        )
    }

    if (isGridView) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(if (isSelected) Color(0xFFFF1744).copy(alpha = 0.2f) else Color(0xFF161616))
                .pointerInput(onPlay, onLongClick) {
                    detectTapGestures(
                        onTap = { onPlay() },
                        onLongPress = { onLongClick() }
                    )
                }
                .padding(bottom = 12.dp)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
            ) {
                AsyncImage(
                    model = File(recording.thumbnailPath),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp))
                )
                if (isSelected) {
                    Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.4f)))
                    Icon(Icons.Filled.CheckCircle, contentDescription = "Selecionado", tint = Color(0xFFFF1744), modifier = Modifier.align(Alignment.Center).size(36.dp))
                }
                
                // Favorite Badge
                IconButton(
                    onClick = onToggleFavorite,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp)
                        .size(32.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color.Black.copy(alpha = 0.6f))
                ) {
                    Icon(
                        imageVector = if (recording.isFavorite) Icons.Filled.Star else Icons.Outlined.StarBorder,
                        contentDescription = "Favoritar",
                        tint = if (recording.isFavorite) Color(0xFFFFC107) else Color.White,
                        modifier = Modifier.size(18.dp)
                    )
                }

                // Duration Badge
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(8.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(Color.Black.copy(alpha = 0.8f))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(durationText, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Text(
                    text = recording.fileName,
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    modifier = Modifier.weight(1f)
                )
                Box {
                    Icon(
                        Icons.Filled.MoreVert,
                        contentDescription = "Opções",
                        tint = Color.Gray,
                        modifier = Modifier.size(20.dp).clickable { showMenu = true }
                    )
                    DropdownMenu(
                        expanded = showMenu,
                        onDismissRequest = { showMenu = false }
                    ) {
                        DropdownMenuItem(text = { Text("Renomear") }, onClick = { showMenu = false; showRenameDialog = true })
                        DropdownMenuItem(text = { Text("Deletar") }, onClick = { showMenu = false; onDelete() })
                        DropdownMenuItem(text = { Text("Salvar na Galeria") }, onClick = { showMenu = false; onSaveToGallery() })
                    }
                }
            }

            Spacer(modifier = Modifier.height(4.dp))
            
            Text(
                text = "${recording.resolution} • ${recording.codec} • ${recording.frameRate}fps",
                color = Color.LightGray,
                fontSize = 12.sp,
                modifier = Modifier.padding(horizontal = 12.dp)
            )
            
            Spacer(modifier = Modifier.height(2.dp))
            
            Text(
                text = "$sizeMb MB • ${formatTimecodeLong(recording.createdAt)}",
                color = Color.Gray,
                fontSize = 12.sp,
                modifier = Modifier.padding(horizontal = 12.dp)
            )
        }
    } else {
        // List View Layout
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(if (isSelected) Color(0xFFFF1744).copy(alpha = 0.2f) else Color(0xFF161616))
                .pointerInput(onPlay, onLongClick) {
                    detectTapGestures(
                        onTap = { onPlay() },
                        onLongPress = { onLongClick() }
                    )
                }
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .width(120.dp)
                    .aspectRatio(16f / 9f)
            ) {
                AsyncImage(
                    model = File(recording.thumbnailPath),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(8.dp))
                )
                if (isSelected) {
                    Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.4f)))
                    Icon(Icons.Filled.CheckCircle, contentDescription = "Selecionado", tint = Color(0xFFFF1744), modifier = Modifier.align(Alignment.Center).size(24.dp))
                }
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(4.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(Color.Black.copy(alpha = 0.8f))
                        .padding(horizontal = 4.dp, vertical = 2.dp)
                ) {
                    Text(durationText, color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = recording.fileName,
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "${recording.resolution} • ${recording.codec} • ${recording.frameRate}fps",
                    color = Color.LightGray,
                    fontSize = 12.sp
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "$sizeMb MB • ${formatTimecodeLong(recording.createdAt)}",
                    color = Color.Gray,
                    fontSize = 12.sp
                )
            }

            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                IconButton(onClick = onToggleFavorite, modifier = Modifier.size(32.dp)) {
                    Icon(
                        imageVector = if (recording.isFavorite) Icons.Filled.Star else Icons.Outlined.StarBorder,
                        contentDescription = "Favoritar",
                        tint = if (recording.isFavorite) Color(0xFFFFC107) else Color.White,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Box {
                    IconButton(onClick = { showMenu = true }, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Filled.MoreVert, contentDescription = "Opções", tint = Color.Gray, modifier = Modifier.size(20.dp))
                    }
                    DropdownMenu(
                        expanded = showMenu,
                        onDismissRequest = { showMenu = false }
                    ) {
                        DropdownMenuItem(text = { Text("Renomear") }, onClick = { showMenu = false; showRenameDialog = true })
                        DropdownMenuItem(text = { Text("Deletar") }, onClick = { showMenu = false; onDelete() })
                        DropdownMenuItem(text = { Text("Salvar na Galeria") }, onClick = { showMenu = false; onSaveToGallery() })
                    }
                }
            }
        }
    }
}

fun formatDuration(durationMs: Long): String {
    val minutes = TimeUnit.MILLISECONDS.toMinutes(durationMs)
    val seconds = TimeUnit.MILLISECONDS.toSeconds(durationMs) % 60
    return String.format(Locale.US, "%02d:%02d", minutes, seconds)
}

fun formatTimestamp(timestamp: Long): String {
    val date = Date(timestamp)
    val sdf = SimpleDateFormat("dd 'de' MMM, yyyy", Locale("pt", "BR"))
    return sdf.format(date)
}

fun formatTimecodeLong(timestamp: Long): String {
    val date = Date(timestamp)
    val sdf = SimpleDateFormat("HH:mm", Locale("pt", "BR"))
    return sdf.format(date)
}
