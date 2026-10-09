package com.bragastudio.mobile.featuresettings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.bragastudio.mobile.common.components.BdsmGroupInset
import com.bragastudio.mobile.common.components.BdsmGroupedList
import com.bragastudio.mobile.common.components.BdsmLargeTitleScaffold
import com.bragastudio.mobile.common.components.BdsmSearchField
import com.bragastudio.mobile.common.components.EmptyState
import com.bragastudio.mobile.common.components.SettingsCategoryRow
import com.bragastudio.mobile.common.components.SettingsDivider
import com.bragastudio.mobile.common.components.SettingsItem
import com.bragastudio.mobile.common.components.accent
import com.bragastudio.mobile.common.components.rememberBdsmHaptics
import com.bragastudio.mobile.common.components.rememberLargeTitleState
import com.bragastudio.mobile.common.module.LocalModuleHost
import com.bragastudio.mobile.common.module.ModulePlacement
import com.bragastudio.mobile.common.ui.theme.BdsmTheme
import com.bragastudio.mobile.common.ui.theme.BdsmWidthClass
import com.bragastudio.mobile.common.ui.theme.bdsmWidthClass
import kotlinx.coroutines.launch

/**
 * Ajustes (One UI, tela de referência da Etapa 3): título grande que colapsa ao rolar, busca (a barra
 * some ao rolar; a ação da lupa volta ao topo e foca o campo) e a lista de categorias num grupo único
 * arredondado, com ícone, nome e descrição curta. Nenhum interruptor aqui; cada categoria abre a
 * própria tela (começa recolhida). A busca filtra categorias E itens (um item leva à sua categoria).
 * Em telas largas (`bdsmWidthClass`) as categorias ficam em duas colunas.
 */
@Composable
fun SettingsScreen(
    onNavigateUp: () -> Unit,
    onOpenCategory: (SettingsCategory) -> Unit,
    showBack: Boolean = false,
) {
    val host = LocalModuleHost.current
    val extraModules = remember(host) { host.registry.at(ModulePlacement.MORE) }
    var query by rememberSaveable { mutableStateOf("") }
    val searching = query.isNotBlank()
    val result = if (searching) {
        val (categories, items) = rememberSettingsIndex()
        SettingsCatalog.search(query, categories, items)
    } else {
        null
    }
    val allCategories = SettingsCategory.entries
    val wide = bdsmWidthClass() != BdsmWidthClass.Compact
    val largeTitle = rememberLargeTitleState()
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val searchFocus = remember { FocusRequester() }
    val searchDescription = stringResource(R.string.settings_search_action)
    val haptics = rememberBdsmHaptics()

    BdsmLargeTitleScaffold(
        title = stringResource(R.string.settings_title),
        onNavigateUp = onNavigateUp,
        showBack = showBack,
        state = largeTitle,
        listState = listState,
        maxContentWidth = if (wide) WideContentWidth else BdsmTheme.spacing.contentMaxWidth,
        actions = {
            IconButton(
                onClick = {
                    haptics.tick()
                    scope.launch {
                        listState.animateScrollToItem(0)
                        largeTitle.expand()
                        runCatching { searchFocus.requestFocus() }
                    }
                },
            ) { Icon(Icons.Filled.Search, contentDescription = searchDescription) }
        },
    ) {
        item(key = "search", contentType = "search") {
            BdsmSearchField(
                query = query,
                onQueryChange = { query = it },
                placeholder = stringResource(R.string.settings_search_placeholder),
                clearDescription = stringResource(R.string.settings_search_clear),
                focusRequester = searchFocus,
            )
        }

        val shown = result?.categories?.map { it.category } ?: allCategories
        if (searching && shown.isEmpty()) {
            item(key = "no-results", contentType = "empty") {
                EmptyState(
                    icon = Icons.Filled.SearchOff,
                    title = stringResource(R.string.settings_search_empty_title),
                    message = stringResource(R.string.settings_search_empty_message, query.trim()),
                    actionLabel = stringResource(R.string.settings_search_clear),
                    onAction = { query = "" },
                )
            }
        } else {
            item(key = "categories", contentType = "categories") {
                if (wide) {
                    val (left, right) = SettingsCatalog.splitColumns(shown)
                    Row(horizontalArrangement = Arrangement.spacedBy(BdsmTheme.spacing.groupGap)) {
                        Column(modifier = Modifier.weight(1f)) { CategoryGroup(left, onOpenCategory) }
                        Column(modifier = Modifier.weight(1f)) { CategoryGroup(right, onOpenCategory) }
                    }
                } else {
                    CategoryGroup(shown, onOpenCategory)
                }
            }
        }

        // Módulos extras/futuros (placement MORE) entram aqui, gerados pelo registro.
        if (!searching && extraModules.isNotEmpty()) {
            item(key = "modules", contentType = "categories") {
                BdsmGroupedList(label = stringResource(R.string.settings_modules_title)) {
                    extraModules.forEachIndexed { index, module ->
                        if (index > 0) SettingsDivider(BdsmGroupInset.Category)
                        SettingsCategoryRow(
                            icon = module.icon,
                            title = stringResource(module.titleRes),
                            description = module.subtitleRes?.let { stringResource(it) }.orEmpty(),
                            accent = module.category.accent(),
                            onClick = { module.route?.let(host::navigate) },
                        )
                    }
                }
            }
        }

        val items = result?.items.orEmpty()
        if (items.isNotEmpty()) {
            item(key = "items", contentType = "items") {
                BdsmGroupedList(label = stringResource(R.string.settings_results_items)) {
                    items.forEachIndexed { index, entry ->
                        if (index > 0) SettingsDivider()
                        val meta = settingsCategoryMeta(entry.category)
                        SettingsItem(
                            icon = meta.icon,
                            iconColor = entry.category.accent(),
                            title = entry.title,
                            subtitle = stringResource(meta.titleRes),
                        ) { onOpenCategory(entry.category) }
                    }
                }
            }
        }
    }
}

/** Largura máxima do conteúdo de Ajustes em telas largas (duas colunas de categorias). */
private val WideContentWidth = 960.dp

/** Um grupo (contêiner único de 26 dp) com as categorias e divisores recuados a partir do texto. */
@Composable
private fun CategoryGroup(categories: List<SettingsCategory>, onOpen: (SettingsCategory) -> Unit) {
    BdsmGroupedList {
        categories.forEachIndexed { index, category ->
            if (index > 0) SettingsDivider(BdsmGroupInset.Category)
            val meta = settingsCategoryMeta(category)
            SettingsCategoryRow(
                icon = meta.icon,
                title = stringResource(meta.titleRes),
                description = stringResource(meta.descriptionRes),
                accent = category.accent(),
                onClick = { onOpen(category) },
            )
        }
    }
}

/** Índice pesquisável (categorias + itens) com os textos localizados. Só é montado ao buscar. */
@Composable
private fun rememberSettingsIndex(): Pair<List<SearchableCategory>, List<SearchableItem>> {
    val kwQuality = stringResource(R.string.settings_kw_quality)
    val kwStorage = stringResource(R.string.settings_kw_storage)
    val kwSource = stringResource(R.string.settings_kw_source)
    val kwStream = stringResource(R.string.settings_kw_stream)
    val kwMonitor = stringResource(R.string.settings_kw_monitor)
    val kwAppearance = stringResource(R.string.settings_kw_appearance)
    val kwSystem = stringResource(R.string.settings_kw_system)
    val kwAbout = stringResource(R.string.settings_kw_about)
    val categoryKeywords = mapOf(
        SettingsCategory.CAMERA to kwSource,
        SettingsCategory.AUDIO to kwSource,
        SettingsCategory.MONITOR to kwMonitor,
        SettingsCategory.NDI to kwStream,
        SettingsCategory.RECORDING to "$kwQuality $kwStorage",
        SettingsCategory.APP to "$kwAppearance $kwSystem",
        SettingsCategory.ABOUT to kwAbout,
    )
    val categories = SettingsCategory.entries.map {
        val meta = settingsCategoryMeta(it)
        SearchableCategory(it, stringResource(meta.titleRes), stringResource(meta.descriptionRes), categoryKeywords.getValue(it))
    }
    fun item(category: SettingsCategory, key: String, title: String, keywords: String) = SearchableItem(category, key, title, keywords)
    val items = listOf(
        item(SettingsCategory.CAMERA, "source", stringResource(R.string.settings_video_source), kwSource),
        item(SettingsCategory.CAMERA, "resolution", stringResource(R.string.settings_resolution), kwQuality),
        item(SettingsCategory.CAMERA, "fps", stringResource(R.string.settings_fps), kwQuality),
        item(SettingsCategory.AUDIO, "mic", stringResource(R.string.settings_mic_input), kwSource),
        item(SettingsCategory.MONITOR, "zebra", stringResource(R.string.settings_zebra), kwMonitor),
        item(SettingsCategory.MONITOR, "peaking-color", stringResource(R.string.settings_peaking_color), kwMonitor),
        item(SettingsCategory.MONITOR, "peaking-sensitivity", stringResource(R.string.settings_peaking_sensitivity), kwMonitor),
        item(SettingsCategory.NDI, "ndi", stringResource(R.string.settings_ndi), kwStream),
        item(SettingsCategory.NDI, "ndi-advanced", stringResource(R.string.ndi3_advanced), kwStream),
        item(SettingsCategory.RECORDING, "quality", stringResource(R.string.settings_section_quality), kwQuality),
        item(SettingsCategory.RECORDING, "codec", stringResource(R.string.settings_codec), kwQuality),
        item(SettingsCategory.RECORDING, "bitrate", stringResource(R.string.settings_bitrate), kwQuality),
        item(SettingsCategory.RECORDING, "hdr", stringResource(R.string.settings_hdr), kwQuality),
        item(SettingsCategory.RECORDING, "destination", stringResource(R.string.settings_storage_destination), kwStorage),
        item(SettingsCategory.RECORDING, "restore", stringResource(R.string.settings_storage_restore), kwStorage),
        item(SettingsCategory.APP, "display-name", stringResource(R.string.settings_display_name), kwSystem),
        item(SettingsCategory.APP, "theme", stringResource(R.string.settings_theme), kwAppearance),
        item(SettingsCategory.APP, "dynamic-color", stringResource(R.string.settings_dynamic_color), kwAppearance),
        item(SettingsCategory.APP, "link", stringResource(R.string.link_section_title), kwStream),
        item(SettingsCategory.APP, "diagnostics", stringResource(R.string.settings_diagnostics), kwSystem),
        item(SettingsCategory.APP, "permissions", stringResource(R.string.settings_permissions), kwSystem),
        item(SettingsCategory.ABOUT, "version", stringResource(R.string.settings_version), kwAbout),
        item(SettingsCategory.ABOUT, "licenses", stringResource(R.string.settings_licenses), kwAbout),
    )
    return categories to items
}
