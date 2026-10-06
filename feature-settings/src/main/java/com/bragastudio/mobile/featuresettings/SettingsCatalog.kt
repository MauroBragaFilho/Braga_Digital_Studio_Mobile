package com.bragastudio.mobile.featuresettings

/** As sete categorias da primeira tela de Configurações (UX v3, estilo One UI). */
enum class SettingsCategory(val id: String) {
    CAMERA("camera"),
    AUDIO("audio"),
    MONITOR("monitor"),
    NDI("ndi"),
    RECORDING("recording"),
    APP("app"),
    ABOUT("about"),
    ;

    companion object {
        fun fromId(id: String?): SettingsCategory? = entries.firstOrNull { it.id == id }
    }
}

/** Categoria com os textos já localizados (a busca é pura: recebe strings, não recursos). */
data class SearchableCategory(
    val category: SettingsCategory,
    val title: String,
    val description: String,
    val keywords: String = "",
)

/** Item pesquisável de uma categoria (um ajuste que existe dentro da tela da categoria). */
data class SearchableItem(
    val category: SettingsCategory,
    val key: String,
    val title: String,
    val keywords: String = "",
)

/** O que a tela mostra para uma busca: categorias que casam (ou contêm itens que casam) e os itens. */
data class SettingsSearchResult(
    val categories: List<SearchableCategory>,
    val items: List<SearchableItem>,
)

object SettingsCatalog {
    /**
     * Divide a lista em duas colunas (telas largas): a primeira recebe a metade maior quando o total é
     * ímpar, preservando a ordem. Lista vazia ou de um item deixa a segunda coluna vazia.
     */
    fun <T> splitColumns(items: List<T>): Pair<List<T>, List<T>> {
        val firstSize = (items.size + 1) / 2
        return items.take(firstSize) to items.drop(firstSize)
    }

    /**
     * Filtra categorias e itens. Busca vazia mostra todas as categorias e nenhum item (primeira tela
     * limpa). Uma categoria aparece se ela própria casa (título, descrição, palavras-chave) ou se
     * algum item dela casa; os itens que casam vêm numa lista à parte, na ordem do catálogo.
     */
    fun search(query: String, categories: List<SearchableCategory>, items: List<SearchableItem>): SettingsSearchResult {
        if (SettingsSearch.normalize(query).isEmpty()) return SettingsSearchResult(categories, emptyList())
        val matchedItems = items.filter { SettingsSearch.matches(query, it.title, it.keywords) }
        val withItems = matchedItems.map { it.category }.toSet()
        val matchedCategories = categories.filter {
            it.category in withItems || SettingsSearch.matches(query, it.title, it.description, it.keywords)
        }
        return SettingsSearchResult(matchedCategories, matchedItems)
    }
}
