package com.bragastudio.mobile.featuresettings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import android.view.TextureView
import android.graphics.SurfaceTexture
import androidx.hilt.navigation.compose.hiltViewModel
import com.bragastudio.mobile.common.components.*

// ATENÇÃO (sinalizado, não corrigido automaticamente):
//  - Não encontrei nenhum call site de LutsScreen(...) em nenhum grafo de
//    navegação do projeto — a tela parece não estar conectada ainda.
//  - onImportLut nunca é chamado: o botão de import usa `launcher.launch("*/*")`
//    direto, ignorando esse callback.
//  - isLoading e errorMessage são coletados mas nunca renderizados — falhas de
//    import de LUT hoje ficam silenciosas para o usuário.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
@Suppress("UNUSED_PARAMETER")
fun LutsScreen(
    onNavigateUp: () -> Unit,
    onImportLut: () -> Unit,
    viewModel: LutsViewModel = hiltViewModel()
) {
    val allLuts by viewModel.allLuts.collectAsState()
    val activeLut by viewModel.activeLut.collectAsState()
    @Suppress("UNUSED_VARIABLE") val isLoading by viewModel.isLoading.collectAsState()
    @Suppress("UNUSED_VARIABLE") val errorMessage by viewModel.errorMessage.collectAsState()
    val sortOrder by viewModel.sortOrder.collectAsState()

    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            viewModel.importLut(uri, context)
        }
    }

    var lutToDelete by remember { mutableStateOf<com.bragastudio.mobile.core.model.Lut?>(null) }
    var showSortMenu by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = Color(0xFF0A0A0A),
        topBar = {
            TopAppBar(
                title = { Text("LUTs", color = Color.White, fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onNavigateUp) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Voltar", tint = Color.White)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                actions = {
                    IconButton(onClick = { launcher.launch("*/*") }) {
                        Icon(Icons.Filled.Add, contentDescription = "Importar", tint = Color.White)
                    }
                }
            )
        }
    ) { paddingValues ->
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // COLUNA ESQUERDA (Preview Original vs LUT Aplicado)
            Column(
                modifier = Modifier.weight(1f)
            ) {
                Card(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF161616)),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(
                        modifier = Modifier.fillMaxSize().padding(16.dp),
                        verticalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Visualização de LUT", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        Spacer(modifier = Modifier.height(16.dp))
                        Box(
                            modifier = Modifier.fillMaxWidth().weight(1f).padding(top = 8.dp).clip(RoundedCornerShape(8.dp)).background(Color.Black)
                        ) {
                            AndroidView(
                                factory = { ctx ->
                                    TextureView(ctx).apply {
                                        surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                                            override fun onSurfaceTextureAvailable(surfaceTexture: SurfaceTexture, width: Int, height: Int) {
                                                viewModel.attachSurface(android.view.Surface(surfaceTexture))
                                            }
                                            override fun onSurfaceTextureSizeChanged(surfaceTexture: SurfaceTexture, width: Int, height: Int) {}
                                            override fun onSurfaceTextureDestroyed(surfaceTexture: SurfaceTexture): Boolean {
                                                viewModel.detachSurface()
                                                return true
                                            }
                                            override fun onSurfaceTextureUpdated(surfaceTexture: SurfaceTexture) {}
                                        }
                                    }
                                },
                                modifier = Modifier.fillMaxSize().wrapContentSize(Alignment.Center)
                            )
                        }
                        
                        Spacer(modifier = Modifier.height(8.dp))
                        
                        Text(
                            text = activeLut?.displayName ?: "Nenhum LUT selecionado",
                            color = Color.White,
                            fontWeight = FontWeight.Medium,
                            fontSize = 14.sp,
                            modifier = Modifier.align(Alignment.CenterHorizontally)
                        )
                    }
                }
            }

            // COLUNA DIREITA (Biblioteca de LUTs)
            Column(
                modifier = Modifier.weight(1f)
            ) {
                Card(
                    modifier = Modifier.fillMaxSize(),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF161616)),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Biblioteca de LUTs", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                            
                            Box {
                                IconButton(onClick = { showSortMenu = true }) {
                                    Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = "Ordenar", tint = Color.White)
                                }
                                DropdownMenu(
                                    expanded = showSortMenu,
                                    onDismissRequest = { showSortMenu = false },
                                    modifier = Modifier.background(Color(0xFF222222))
                                ) {
                                    DropdownMenuItem(
                                        text = { Text("Nome (A-Z)", color = if (sortOrder == LutsViewModel.SortOrder.ALPHABETICAL_ASC) Color(0xFFE50914) else Color.White) },
                                        onClick = { 
                                            viewModel.setSortOrder(LutsViewModel.SortOrder.ALPHABETICAL_ASC)
                                            showSortMenu = false 
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Nome (Z-A)", color = if (sortOrder == LutsViewModel.SortOrder.ALPHABETICAL_DESC) Color(0xFFE50914) else Color.White) },
                                        onClick = { 
                                            viewModel.setSortOrder(LutsViewModel.SortOrder.ALPHABETICAL_DESC)
                                            showSortMenu = false 
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Mais Recentes", color = if (sortOrder == LutsViewModel.SortOrder.IMPORT_DATE_DESC) Color(0xFFE50914) else Color.White) },
                                        onClick = { 
                                            viewModel.setSortOrder(LutsViewModel.SortOrder.IMPORT_DATE_DESC)
                                            showSortMenu = false 
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Mais Antigos", color = if (sortOrder == LutsViewModel.SortOrder.IMPORT_DATE_ASC) Color(0xFFE50914) else Color.White) },
                                        onClick = { 
                                            viewModel.setSortOrder(LutsViewModel.SortOrder.IMPORT_DATE_ASC)
                                            showSortMenu = false 
                                        }
                                    )
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                        
                        if (allLuts.isEmpty()) {
                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text("Nenhum LUT salvo", color = Color.Gray, fontSize = 14.sp)
                            }
                        } else {
                            LazyColumn(
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.fillMaxSize()
                            ) {
                                items(allLuts, key = { it.id }) { lut ->
                                    LutLibraryItem(
                                        lutName = lut.displayName,
                                        lutSize = "${lut.sizeBytes / 1024} KB",
                                        isActive = lut.isActive,
                                        isSelected = lut.id == activeLut?.id,
                                        onClick = { viewModel.applyLut(lut.id) },
                                        onLongClick = { lutToDelete = lut }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (lutToDelete != null) {
        AlertDialog(
            onDismissRequest = { lutToDelete = null },
            title = { Text("Deletar LUT") },
            text = { Text("Tem certeza que deseja deletar '${lutToDelete?.displayName}'?") },
            confirmButton = {
                TextButton(onClick = {
                    lutToDelete?.id?.let { viewModel.deleteLut(it) }
                    lutToDelete = null
                }) {
                    Text("Deletar", color = Color.Red)
                }
            },
            dismissButton = {
                TextButton(onClick = { lutToDelete = null }) {
                    Text("Cancelar", color = Color.White)
                }
            },
            containerColor = Color(0xFF2C2C2C),
            titleContentColor = Color.White,
            textContentColor = Color.LightGray
        )
    }
}

@Composable
fun LutLibraryItem(lutName: String, lutSize: String, isActive: Boolean, isSelected: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(64.dp)
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { onClick() },
                    onLongPress = { onLongClick() }
                )
            }
            .clip(RoundedCornerShape(8.dp)),
        colors = CardDefaults.cardColors(containerColor = if (isSelected) Color(0xFF2979FF).copy(alpha = 0.2f) else Color(0xFF1E1E1E)),
        border = if (isSelected) BorderStroke(1.dp, Color(0xFF2979FF)) else null
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Placeholder para miniatura da LUT
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color(0xFF333333)) // Placeholder
            ) {
                Text("LUT", color = Color.Gray, fontSize = 10.sp, modifier = Modifier.align(Alignment.Center))
            }
            
            Spacer(modifier = Modifier.width(16.dp))
            
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = lutName,
                    color = if (isSelected) Color.White else Color(0xFFB0B0B0),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1
                )
                if (lutSize.isNotEmpty()) {
                    Text(
                        text = lutSize,
                        color = Color(0xFF757575),
                        fontSize = 12.sp,
                        maxLines = 1
                    )
                }
            }
            
            if (isActive) {
                Text(
                    text = "Ativa",
                    color = Color(0xFF4CAF50),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
            }

        }
    }
}
