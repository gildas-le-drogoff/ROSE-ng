package dev.narayan.rose.domain.repository

import dev.narayan.rose.domain.model.StartPage
import dev.narayan.rose.domain.model.ThemeMode

interface SettingsRepository {
    var themeMode: ThemeMode
    var amoledMode: Boolean
    var dynamicColor: Boolean
    var isGridView: Boolean
    var isCategoryGridView: Boolean
    var foldersFirst: Boolean
    var showHiddenFiles: Boolean
    var showDetails: Boolean
    var confirmBeforeDelete: Boolean
    var showFileExtensions: Boolean
    var showListDividers: Boolean
    var showFolderSize: Boolean
    var gridItemSize: String
    var sortBy: String
    var sortOrder: String
    var startPage: StartPage
    var useShizuku: Boolean
    var useRoot: Boolean
    var quickAccessRemoved: Set<String>
    var quickAccessCustomPaths: List<String>
    var favouriteFiles: List<String>
    var cachedCategoryCounts: Map<String, Int>
    var offlineFiles: Set<String>
    var showRecentFiles: Boolean
    var showQuickAccess: Boolean
    var showExternalStorage: Boolean
    var recentFilesExpanded: Boolean
    var quickAccessExpanded: Boolean
    var externalStorageExpanded: Boolean
    var useRecycleBin: Boolean
    var recentFilesLimit: Int
    var excludedFolders: Set<String>
    var notificationPrimerShown: Boolean
    var autoCheckUpdates: Boolean
    var skippedUpdateVersion: String?
    var addedStorageUris: Set<String>
    var lockedStoragePaths: Set<String>
    var ftpServerAnonymousLogin: Boolean
    var ftpServerUsername: String
    var ftpServerPassword: String
    var ftpServerPort: Int
    var ftpServerHomeDirectory: String
    var ftpServerWritable: Boolean
}
