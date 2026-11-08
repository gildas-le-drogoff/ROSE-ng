package dev.narayan.rose.domain.model

sealed interface DirectoryListing {
    data class Success(val items: List<FileItem>) : DirectoryListing

    data object AccessDenied : DirectoryListing
}

data class SearchRequest(
    val query: String,
    val scopePath: String,
    val filterType: FileType? = null,
    val bucketId: String? = null,
    val showHiddenFiles: Boolean = false,
    val maxResults: Int = 100
)
