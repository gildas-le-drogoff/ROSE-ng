package dev.narayan.rose.data.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import dev.narayan.rose.domain.repository.SettingsRepository
import dev.narayan.rose.domain.model.FileType
import dev.narayan.rose.domain.model.ThemeMode
import dev.narayan.rose.domain.model.StartPage

class SettingsManager(context: Context) : SettingsRepository {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override var themeMode: ThemeMode
        get() = try {
            ThemeMode.valueOf(prefs.getString(KEY_THEME_MODE, ThemeMode.SYSTEM.name) ?: ThemeMode.SYSTEM.name)
        } catch (e: IllegalArgumentException) {
            ThemeMode.SYSTEM
        }
        set(value) = prefs.edit { putString(KEY_THEME_MODE, value.name) }

    override var amoledMode: Boolean
        get() = prefs.getBoolean(KEY_AMOLED, false)
        set(value) = prefs.edit { putBoolean(KEY_AMOLED, value) }

    override var dynamicColor: Boolean
        get() = prefs.getBoolean(KEY_DYNAMIC_COLOR, true)
        set(value) = prefs.edit { putBoolean(KEY_DYNAMIC_COLOR, value) }

    override var isGridView: Boolean
        get() = prefs.getBoolean(KEY_GRID_VIEW, false)
        set(value) = prefs.edit { putBoolean(KEY_GRID_VIEW, value) }

    override var isCategoryGridView: Boolean
        get() = prefs.getBoolean(KEY_CATEGORY_GRID_VIEW, true)
        set(value) = prefs.edit { putBoolean(KEY_CATEGORY_GRID_VIEW, value) }

    override var foldersFirst: Boolean
        get() = prefs.getBoolean(KEY_FOLDERS_FIRST, true)
        set(value) = prefs.edit { putBoolean(KEY_FOLDERS_FIRST, value) }

    override var showHiddenFiles: Boolean
        get() = prefs.getBoolean(KEY_SHOW_HIDDEN, false)
        set(value) = prefs.edit { putBoolean(KEY_SHOW_HIDDEN, value) }

    override var showDetails: Boolean
        get() = prefs.getBoolean(KEY_SHOW_DETAILS, true)
        set(value) = prefs.edit { putBoolean(KEY_SHOW_DETAILS, value) }

    override var confirmBeforeDelete: Boolean
        get() = prefs.getBoolean(KEY_CONFIRM_DELETE, true)
        set(value) = prefs.edit { putBoolean(KEY_CONFIRM_DELETE, value) }

    override var showFileExtensions: Boolean
        get() = prefs.getBoolean(KEY_SHOW_EXTENSIONS, true)
        set(value) = prefs.edit { putBoolean(KEY_SHOW_EXTENSIONS, value) }

    override var showListDividers: Boolean
        get() = prefs.getBoolean(KEY_SHOW_LIST_DIVIDERS, true)
        set(value) = prefs.edit { putBoolean(KEY_SHOW_LIST_DIVIDERS, value) }

    override var showFolderSize: Boolean
        get() = prefs.getBoolean(KEY_SHOW_FOLDER_SIZE, false)
        set(value) = prefs.edit { putBoolean(KEY_SHOW_FOLDER_SIZE, value) }

    override var gridItemSize: String
        get() = prefs.getString(KEY_GRID_ITEM_SIZE, "MEDIUM") ?: "MEDIUM"
        set(value) = prefs.edit { putString(KEY_GRID_ITEM_SIZE, value) }

    override var sortBy: String
        get() = prefs.getString(KEY_SORT_BY, "NAME") ?: "NAME"
        set(value) = prefs.edit { putString(KEY_SORT_BY, value) }

    override var sortOrder: String
        get() = prefs.getString(KEY_SORT_ORDER, "ASCENDING") ?: "ASCENDING"
        set(value) = prefs.edit { putString(KEY_SORT_ORDER, value) }

    override var startPage: StartPage
        get() = try {
            StartPage.valueOf(prefs.getString(KEY_START_PAGE, StartPage.HOME.name) ?: StartPage.HOME.name)
        } catch (e: IllegalArgumentException) {
            StartPage.HOME
        }
        set(value) = prefs.edit { putString(KEY_START_PAGE, value.name) }

    override var useShizuku: Boolean
        get() = prefs.getBoolean(KEY_USE_SHIZUKU, false)
        set(value) = prefs.edit { putBoolean(KEY_USE_SHIZUKU, value) }

    override var useRoot: Boolean
        get() = prefs.getBoolean(KEY_USE_ROOT, false)
        set(value) = prefs.edit { putBoolean(KEY_USE_ROOT, value) }

    override var quickAccessRemoved: Set<String>
        get() = prefs.getStringSet(KEY_QUICK_ACCESS_REMOVED, emptySet()) ?: emptySet()
        set(value) = prefs.edit { putStringSet(KEY_QUICK_ACCESS_REMOVED, value) }

    override var quickAccessCustomPaths: List<String>
        get() {
            val raw = prefs.getString(KEY_QUICK_ACCESS_CUSTOM, "") ?: return emptyList()
            val list = raw.split(QUICK_ACCESS_DELIMITER).filter { it.isNotBlank() }
            val seen = mutableSetOf<String>()
            val result = mutableListOf<String>()
            for (p in list) {
                val normalized = try { java.io.File(p).canonicalPath } catch (_: Exception) { p.trimEnd(java.io.File.separatorChar) }
                if (seen.add(normalized)) {
                    result.add(p)
                }
            }
            return result
        }
        set(value) {
            val seen = mutableSetOf<String>()
            val result = mutableListOf<String>()
            for (p in value) {
                if (p.isBlank()) continue
                val normalized = try { java.io.File(p).canonicalPath } catch (_: Exception) { p.trimEnd(java.io.File.separatorChar) }
                if (seen.add(normalized)) {
                    result.add(p)
                }
            }
            prefs.edit { putString(KEY_QUICK_ACCESS_CUSTOM, result.joinToString(QUICK_ACCESS_DELIMITER)) }
        }

    override var favouriteFiles: List<String>
        get() {
            val raw = prefs.getString(KEY_FAVOURITE_FILES, "") ?: return emptyList()
            val list = raw.split(QUICK_ACCESS_DELIMITER).filter { it.isNotBlank() }
            val seen = mutableSetOf<String>()
            val result = mutableListOf<String>()
            for (p in list) {
                val normalized = try { java.io.File(p).canonicalPath } catch (_: Exception) { p.trimEnd(java.io.File.separatorChar) }
                if (seen.add(normalized)) {
                    result.add(p)
                }
            }
            return result
        }
        set(value) {
            val seen = mutableSetOf<String>()
            val result = mutableListOf<String>()
            for (p in value) {
                if (p.isBlank()) continue
                val normalized = try { java.io.File(p).canonicalPath } catch (_: Exception) { p.trimEnd(java.io.File.separatorChar) }
                if (seen.add(normalized)) {
                    result.add(p)
                }
            }
            prefs.edit { putString(KEY_FAVOURITE_FILES, result.joinToString(QUICK_ACCESS_DELIMITER)) }
        }

    override var cachedCategoryCounts: Map<String, Int>
        get() = prefs.getString(KEY_CACHED_CATEGORY_COUNTS, "")
            ?.split(QUICK_ACCESS_DELIMITER)
            ?.filter { it.isNotBlank() && it.contains(":") }
            ?.associate { entry ->
                val (k, v) = entry.split(":", limit = 2)
                k to (v.toIntOrNull() ?: 0)
            }
            ?: emptyMap()
        set(value) = prefs.edit {
            putString(
                KEY_CACHED_CATEGORY_COUNTS,
                value.entries.joinToString(QUICK_ACCESS_DELIMITER) { "${it.key}:${it.value}" }
            )
        }

    override var offlineFiles: Set<String>
        get() = prefs.getStringSet(KEY_OFFLINE_FILES, emptySet()) ?: emptySet()
        set(value) = prefs.edit { putStringSet(KEY_OFFLINE_FILES, value) }

    override var showRecentFiles: Boolean
        get() = prefs.getBoolean(KEY_SHOW_RECENT_FILES, true)
        set(value) = prefs.edit { putBoolean(KEY_SHOW_RECENT_FILES, value) }

    override var showQuickAccess: Boolean
        get() = prefs.getBoolean(KEY_SHOW_QUICK_ACCESS, true)
        set(value) = prefs.edit { putBoolean(KEY_SHOW_QUICK_ACCESS, value) }

    override var showExternalStorage: Boolean
        get() = prefs.getBoolean(KEY_SHOW_EXTERNAL_STORAGE, true)
        set(value) = prefs.edit { putBoolean(KEY_SHOW_EXTERNAL_STORAGE, value) }

    override var recentFilesExpanded: Boolean
        get() = prefs.getBoolean(KEY_RECENT_FILES_EXPANDED, true)
        set(value) = prefs.edit { putBoolean(KEY_RECENT_FILES_EXPANDED, value) }

    override var quickAccessExpanded: Boolean
        get() = prefs.getBoolean(KEY_QUICK_ACCESS_EXPANDED, true)
        set(value) = prefs.edit { putBoolean(KEY_QUICK_ACCESS_EXPANDED, value) }

    override var externalStorageExpanded: Boolean
        get() = prefs.getBoolean(KEY_EXTERNAL_STORAGE_EXPANDED, true)
        set(value) = prefs.edit { putBoolean(KEY_EXTERNAL_STORAGE_EXPANDED, value) }

    override var useRecycleBin: Boolean
        get() = prefs.getBoolean(KEY_USE_RECYCLE_BIN, true)
        set(value) = prefs.edit { putBoolean(KEY_USE_RECYCLE_BIN, value) }

    override var recentFilesLimit: Int
        get() = prefs.getInt(KEY_RECENT_FILES_LIMIT, 50)
        set(value) = prefs.edit { putInt(KEY_RECENT_FILES_LIMIT, value) }

    override var excludedFolders: Set<String>
        get() = prefs.getStringSet(KEY_EXCLUDED_FOLDERS, emptySet()) ?: emptySet()
        set(value) = prefs.edit { putStringSet(KEY_EXCLUDED_FOLDERS, value) }

    override var notificationPrimerShown: Boolean
        get() = prefs.getBoolean(KEY_NOTIFICATION_PRIMER_SHOWN, false)
        set(value) = prefs.edit { putBoolean(KEY_NOTIFICATION_PRIMER_SHOWN, value) }

    override var autoCheckUpdates: Boolean
        get() = prefs.getBoolean(KEY_AUTO_CHECK_UPDATES, true)
        set(value) = prefs.edit { putBoolean(KEY_AUTO_CHECK_UPDATES, value) }

    override var skippedUpdateVersion: String?
        get() = prefs.getString(KEY_SKIPPED_UPDATE_VERSION, null)
        set(value) = prefs.edit { putString(KEY_SKIPPED_UPDATE_VERSION, value) }

    override var addedStorageUris: Set<String>
        get() = prefs.getStringSet(KEY_ADDED_STORAGE_URIS, emptySet()) ?: emptySet()
        set(value) = prefs.edit { putStringSet(KEY_ADDED_STORAGE_URIS, value) }

    override var lockedStoragePaths: Set<String>
        get() = prefs.getStringSet(KEY_LOCKED_STORAGE_PATHS, emptySet()) ?: emptySet()
        set(value) = prefs.edit { putStringSet(KEY_LOCKED_STORAGE_PATHS, value) }

    override var ftpServerAnonymousLogin: Boolean
        get() = prefs.getBoolean(KEY_FTP_SERVER_ANONYMOUS_LOGIN, true)
        set(value) = prefs.edit { putBoolean(KEY_FTP_SERVER_ANONYMOUS_LOGIN, value) }

    override var ftpServerUsername: String
        get() = prefs.getString(KEY_FTP_SERVER_USERNAME, "admin") ?: "admin"
        set(value) = prefs.edit { putString(KEY_FTP_SERVER_USERNAME, value) }

    override var ftpServerPassword: String
        get() = prefs.getString(KEY_FTP_SERVER_PASSWORD, "") ?: ""
        set(value) = prefs.edit { putString(KEY_FTP_SERVER_PASSWORD, value) }

    override var ftpServerPort: Int
        get() = prefs.getInt(KEY_FTP_SERVER_PORT, 2121)
        set(value) = prefs.edit { putInt(KEY_FTP_SERVER_PORT, value) }

    override var ftpServerHomeDirectory: String
        get() = prefs.getString(KEY_FTP_SERVER_HOME_DIRECTORY, android.os.Environment.getExternalStorageDirectory().absolutePath)
            ?: android.os.Environment.getExternalStorageDirectory().absolutePath
        set(value) = prefs.edit { putString(KEY_FTP_SERVER_HOME_DIRECTORY, value) }

    override var ftpServerWritable: Boolean
        get() = prefs.getBoolean(KEY_FTP_SERVER_WRITABLE, true)
        set(value) = prefs.edit { putBoolean(KEY_FTP_SERVER_WRITABLE, value) }

    companion object {
        private const val PREFS_NAME = "rose_prefs"
        private const val KEY_ADDED_STORAGE_URIS = "added_storage_uris"
        private const val KEY_LOCKED_STORAGE_PATHS = "locked_storage_paths"
        private const val KEY_AUTO_CHECK_UPDATES = "auto_check_updates"
        private const val KEY_SKIPPED_UPDATE_VERSION = "skipped_update_version"
        private const val KEY_THEME_MODE = "theme_mode"
        private const val KEY_AMOLED = "amoled_mode"
        private const val KEY_DYNAMIC_COLOR = "dynamic_color"
        private const val KEY_GRID_VIEW = "grid_view"
        private const val KEY_CATEGORY_GRID_VIEW = "category_grid_view"
        private const val KEY_FOLDERS_FIRST = "folders_first"
        private const val KEY_SHOW_HIDDEN = "show_hidden"
        private const val KEY_SHOW_DETAILS = "show_details"
        private const val KEY_CONFIRM_DELETE = "confirm_delete"
        private const val KEY_SHOW_EXTENSIONS = "show_extensions"
        private const val KEY_SHOW_LIST_DIVIDERS = "show_list_dividers"
        private const val KEY_SHOW_FOLDER_SIZE = "show_folder_size"
        private const val KEY_GRID_ITEM_SIZE = "grid_item_size"
        private const val KEY_SORT_BY = "sort_by"
        private const val KEY_SORT_ORDER = "sort_order"
        private const val KEY_START_PAGE = "start_page"
        private const val KEY_USE_SHIZUKU = "use_shizuku"
        private const val KEY_USE_ROOT = "use_root"
        private const val KEY_QUICK_ACCESS_REMOVED = "quick_access_removed"
        private const val KEY_QUICK_ACCESS_CUSTOM = "quick_access_custom"
        private const val KEY_FAVOURITE_FILES = "favourite_files"
        private const val KEY_CACHED_CATEGORY_COUNTS = "cached_category_counts"
        private const val KEY_OFFLINE_FILES = "offline_files"
        private const val KEY_USE_RECYCLE_BIN = "use_recycle_bin"
        private const val KEY_RECENT_FILES_LIMIT = "recent_files_limit"
        private const val KEY_EXCLUDED_FOLDERS = "excluded_folders"
        private const val KEY_NOTIFICATION_PRIMER_SHOWN = "notification_primer_shown"
        private const val KEY_SHOW_RECENT_FILES = "show_recent_files"
        private const val KEY_SHOW_QUICK_ACCESS = "show_quick_access"
        private const val KEY_SHOW_EXTERNAL_STORAGE = "show_external_storage"
        private const val KEY_RECENT_FILES_EXPANDED = "recent_files_expanded"
        private const val KEY_QUICK_ACCESS_EXPANDED = "quick_access_expanded"
        private const val KEY_EXTERNAL_STORAGE_EXPANDED = "external_storage_expanded"
        private const val KEY_FTP_SERVER_ANONYMOUS_LOGIN = "ftp_server_anonymous_login"
        private const val KEY_FTP_SERVER_USERNAME = "ftp_server_username"
        private const val KEY_FTP_SERVER_PASSWORD = "ftp_server_password"
        private const val KEY_FTP_SERVER_PORT = "ftp_server_port"
        private const val KEY_FTP_SERVER_HOME_DIRECTORY = "ftp_server_home_directory"
        private const val KEY_FTP_SERVER_WRITABLE = "ftp_server_writable"
        private const val QUICK_ACCESS_DELIMITER = "|::|"
    }
}