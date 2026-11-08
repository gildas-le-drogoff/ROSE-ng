package dev.narayan.rose.domain.repository

import dev.narayan.rose.domain.model.FileItem
import dev.narayan.rose.domain.model.FileType
import dev.narayan.rose.domain.model.SearchRequest

interface MediaStoreRepository {

    suspend fun countByCategory(excludedFolders: Set<String>): Map<FileType, Int>

    suspend fun listCategory(type: FileType, bucketId: String?, excludedFolders: Set<String>): List<FileItem>

    suspend fun recentFiles(limit: Int, excludedFolders: Set<String>): List<FileItem>

    fun generation(): Long

    suspend fun searchByName(request: SearchRequest): List<FileItem>
}
