package dev.narayan.rose.ui.navigation

sealed interface ExplorerView {
    val key: String

    data object Files : ExplorerView { override val key = "files" }

    data object Category : ExplorerView { override val key = "category" }

    data object Recent : ExplorerView { override val key = "recent" }

    data object Favourites : ExplorerView { override val key = "favourites" }

    val isVirtual: Boolean get() = this !is Files

    val usesCategoryGrid: Boolean get() = this is Category || this is Favourites
}
