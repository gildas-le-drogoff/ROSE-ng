package dev.narayan.rose.domain.repository

import dev.narayan.rose.domain.model.DirectoryListing
import dev.narayan.rose.domain.model.FileItem
import dev.narayan.rose.domain.model.FileType
import dev.narayan.rose.domain.model.SearchRequest
import java.io.File

interface FileRepository {

    suspend fun listDirectory(path: String, showHiddenFiles: Boolean, useRoot: Boolean): DirectoryListing

    suspend fun listLocalDirectory(path: String, showHiddenFiles: Boolean): List<FileItem>?

    fun countChildren(file: File, showHiddenFiles: Boolean): Int?

    fun folderSize(directory: File, useRoot: Boolean): Long

    fun isRestrictedPath(path: String): Boolean

    fun isRemotePath(path: String?): Boolean

    suspend fun searchRestricted(request: SearchRequest): List<FileItem>

    suspend fun scanByName(query: String, root: File, filterType: FileType?, maxResults: Int): List<FileItem>
}
