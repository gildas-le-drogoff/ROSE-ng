package dev.narayan.rose.viewmodel

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.os.StatFs
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.narayan.rose.CustomStorageType
import dev.narayan.rose.SafManager
import dev.narayan.rose.StorageDevice
import dev.narayan.rose.domain.repository.SettingsRepository
import dev.narayan.rose.roseNGLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class StorageController(
    private val app: Application,
    private val scope: CoroutineScope,
    private val settings: SettingsRepository,
    private val isRootEnabled: () -> Boolean,
    private val currentPath: () -> String,
    private val navigateTo: (String) -> Unit,
    private val onStorageInfoRequested: () -> Unit
) {
    var storageInfo by mutableStateOf<StorageInfo?>(null)
        private set

    data class StorageInfo(
        val totalBytes: Long,
        val availableBytes: Long,
        val usedBytes: Long,
        val usedFraction: Float
    )

    fun loadStorageInfo() {
        onStorageInfoRequested()
        scope.launch(Dispatchers.IO) {
            try {
                val stats = StatFs(Environment.getExternalStorageDirectory().path)
                val total = stats.totalBytes
                val available = stats.availableBytes
                val used = (total - available).coerceAtLeast(0)
                val fraction = if (total > 0) used.toFloat() / total.toFloat() else 0f

                withContext(Dispatchers.Main) {
                    storageInfo = StorageInfo(total, available, used, fraction)
                }
            } catch (e: Exception) {
                roseNGLog.w(TAG, e) { "StatFs failed for internal storage" }
            }
        }
    }

    var storageDevices = mutableStateListOf<StorageDevice>()
        private set

    fun loadStorageDevices() {
        scope.launch(Dispatchers.IO) {
            val devices = roseNGLog.timed(TAG, "loadStorageDevices", { d -> "${d.size} devices" }) { scanDevices() }
            withContext(Dispatchers.Main) {
                applyDevices(devices)
            }
        }
    }

    private fun scanDevices(): List<StorageDevice> {
        run {
            val devices = mutableListOf<StorageDevice>()

            val internalFile = Environment.getExternalStorageDirectory()
            val internalStats = try { StatFs(internalFile.path) } catch (e: Exception) { null }
            devices.add(
                StorageDevice.Physical(
                    name = "Internal Storage",
                    path = internalFile.absolutePath,
                    totalBytes = internalStats?.totalBytes ?: 0L,
                    availableBytes = internalStats?.availableBytes ?: 0L,
                    isSdCard = false
                )
            )

            val sm = app.getSystemService(android.content.Context.STORAGE_SERVICE) as android.os.storage.StorageManager
            sm.storageVolumes.forEach { volume ->
                val mounted = volume.state == Environment.MEDIA_MOUNTED ||
                        volume.state == "mounted_ro"
                if (!volume.isPrimary && mounted) {
                    val path = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                        volume.directory?.absolutePath
                    } else {
                        try {
                            val getPath = volume.javaClass.getMethod("getPath")
                            getPath.invoke(volume) as String
                        } catch (e: Exception) {
                            null
                        }
                    }
                    if (path != null) {
                        val stats = try { StatFs(path) } catch (e: Exception) { null }
                        val desc = volume.getDescription(app) ?: "SD Card"
                        val isUsbVolume = desc.contains("usb", ignoreCase = true) ||
                                path.contains("usb", ignoreCase = true) ||
                                path.contains("otg", ignoreCase = true)
                        devices.add(
                            StorageDevice.Physical(
                                name = desc,
                                path = path,
                                totalBytes = stats?.totalBytes ?: 0L,
                                availableBytes = stats?.availableBytes ?: 0L,
                                isSdCard = volume.isRemovable,
                                isUsb = isUsbVolume
                            )
                        )
                    }
                }
            }

            if (isRootEnabled()) {
                val stats = try { StatFs("/") } catch (e: Exception) { null }
                devices.add(
                    StorageDevice.Root(
                        name = "Root",
                        path = "/",
                        totalBytes = stats?.totalBytes ?: 0L,
                        availableBytes = stats?.availableBytes ?: 0L
                    )
                )
            }

            val context = app
            val pm = context.packageManager
            val savedTreeEntries = settings.addedStorageUris
            savedTreeEntries.forEach { entry ->
                val parts = entry.split("|::|")
                val uriString = parts.getOrNull(0) ?: return@forEach
                val customName = parts.getOrNull(1)?.takeIf { it.isNotBlank() }
                val typeString = parts.getOrNull(2)
                val uri = try { Uri.parse(uriString) } catch (e: Exception) { null } ?: return@forEach

                val storageType = when {
                    typeString != null -> runCatching { CustomStorageType.valueOf(typeString) }.getOrDefault(CustomStorageType.DOCUMENT_TREE)
                    uriString.startsWith("gdrive://") -> CustomStorageType.GOOGLE_DRIVE
                    else -> {
                        val auth = uri.authority ?: ""
                        when {
                            auth.contains("docs.storage") || auth.contains("com.google.android.apps.docs") -> CustomStorageType.GOOGLE_DRIVE
                            else -> CustomStorageType.DOCUMENT_TREE
                        }
                    }
                }

                val docFile = if (uriString.startsWith("content://")) {
                    try { androidx.documentfile.provider.DocumentFile.fromTreeUri(context, uri) } catch (e: Exception) { null }
                } else null
                val docName = docFile?.name

                val authority = uri.authority ?: ""
                val providerInfo = if (uriString.startsWith("content://")) {
                    try { pm.resolveContentProvider(authority, 0) } catch (e: Exception) { null }
                } else null
                val providerLabel = providerInfo?.loadLabel(pm)?.toString()

                val isCloudStorage = storageType == CustomStorageType.GOOGLE_DRIVE || (uriString.startsWith("content://") &&
                        authority != "com.android.externalstorage.documents" &&
                        authority != "com.android.providers.media.documents" &&
                        authority != "com.android.providers.downloads.documents")

                val defaultTypeName = when (storageType) {
                    CustomStorageType.GOOGLE_DRIVE -> "Google Drive"
                    CustomStorageType.ANDROID_DATA -> "Android/data"
                    CustomStorageType.ANDROID_OBB -> "Android/obb"
                    else -> if (isCloudStorage) "Google Drive" else "External Storage"
                }

                val finalName = customName
                    ?: docName
                    ?: providerLabel
                    ?: defaultTypeName

                val isLocked = isStorageLocked(uriString)

                devices.add(
                    StorageDevice.DocumentTree(
                        name = finalName,
                        path = uriString,
                        totalBytes = 0L,
                        availableBytes = 0L,
                        uriString = uriString,
                        isCloud = isCloudStorage,
                        providerName = providerLabel ?: defaultTypeName,
                        storageType = storageType,
                        isLocked = isLocked
                    )
                )
            }

            return devices
        }
    }

    private fun applyDevices(devices: List<StorageDevice>) {
        storageDevices.clear()
        storageDevices.addAll(devices)

        if (pendingUsbNavigation) {
            val usb = devices.filterIsInstance<StorageDevice.Physical>().firstOrNull { it.isSdCard }
            if (usb != null) {
                roseNGLog.i(TAG) { "USB volume detected, navigating to ${usb.path}" }
                navigateTo(usb.path)
                pendingUsbNavigation = false
                pendingNavigationPath = usb.path
            }
        }
    }

    fun isStorageLocked(path: String): Boolean {
        val clean = path.replace("://", ":/").trimEnd('/')
        return settings.lockedStoragePaths.any { lockedPath ->
            val lockedClean = lockedPath.replace("://", ":/").trimEnd('/')
            clean == lockedClean || path == lockedPath
        }
    }

    fun setStorageLocked(path: String, locked: Boolean) {
        val current = settings.lockedStoragePaths.toMutableSet()
        val clean = path.replace("://", ":/").trimEnd('/')
        if (locked) {
            current.add(path)
        } else {
            current.remove(path)
            current.removeAll { it.replace("://", ":/").trimEnd('/') == clean }
        }
        settings.lockedStoragePaths = current
        loadStorageDevices()
    }

    fun addCustomStorage(uri: Uri) {
        addCustomStorage(uri.toString(), null, CustomStorageType.DOCUMENT_TREE)
    }

    fun addCustomStorage(
        uriString: String,
        customName: String? = null,
        storageType: CustomStorageType = CustomStorageType.DOCUMENT_TREE
    ) {
        scope.launch(Dispatchers.IO) {
            if (uriString.startsWith("content://")) {
                val uri = runCatching { Uri.parse(uriString) }.getOrNull()
                if (uri != null) {
                    val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    try {
                        app.contentResolver.takePersistableUriPermission(uri, flags)
                    } catch (e: SecurityException) {
                        try {
                            app.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        } catch (e2: Exception) {}
                    }
                }
            }
            val entry = if (!customName.isNullOrBlank()) {
                "$uriString|::|$customName|::|${storageType.name}"
            } else {
                "$uriString|::||::|${storageType.name}"
            }
            val current = settings.addedStorageUris
            if (!current.any { it.substringBefore("|::|") == uriString }) {
                settings.addedStorageUris = current + entry
            }
            loadStorageDevices()
        }
    }

    fun removeCustomStorage(uriString: String) {
        scope.launch(Dispatchers.IO) {
            try {
                val uri = Uri.parse(uriString)
                val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                app.contentResolver.releasePersistableUriPermission(uri, flags)
            } catch (e: Exception) {}
            val current = settings.addedStorageUris
            settings.addedStorageUris = current.filterNot { it.substringBefore("|::|") == uriString }.toSet()
            val lockedCurrent = settings.lockedStoragePaths
            settings.lockedStoragePaths = lockedCurrent.filterNot { it == uriString || it.replace("://", ":/").trimEnd('/') == uriString.replace("://", ":/").trimEnd('/') }.toSet()
            loadStorageDevices()
        }
    }

    var pendingUsbNavigation by mutableStateOf(false)
        private set

    var pendingNavigationPath by mutableStateOf<String?>(null)
        private set

    fun clearPendingNavigation() {
        pendingNavigationPath = null
    }

    fun handleUsbDeviceAttached() {
        pendingUsbNavigation = true
        loadStorageDevices()
    }

    fun isRemovableStorage(path: String): Boolean {
        return storageDevices.any { device ->
            (device is StorageDevice.Physical &&
                    device.isSdCard &&
                    path.startsWith(device.path)) ||
            (device is StorageDevice.DocumentTree &&
                    (path == device.path || path.startsWith(device.path)))
        }
    }

    fun isCurrentPathRemovable(): Boolean = isRemovableStorage(currentPath())

    fun isCloudStorage(path: String): Boolean {
        if (path.startsWith("onedrive://") || path.startsWith("gdrive://") ||
            path.startsWith("dropbox://") || path.startsWith("mega://") ||
            path.startsWith("webdav://") || path.startsWith("webdavs://")) {
            return true
        }
        val treeDev = storageDevices.filterIsInstance<StorageDevice.DocumentTree>().firstOrNull { dev ->
            path == dev.path || path.startsWith(dev.path)
        }
        if (treeDev != null) return treeDev.isCloud

        if (!SafManager.isSafUri(path)) return false

        val uri = try { Uri.parse(SafManager.normalizeSafPath(path)) } catch (e: Exception) { null }
        val authority = uri?.authority ?: ""
        return authority.isNotEmpty() &&
                authority != "com.android.externalstorage.documents" &&
                authority != "com.android.providers.media.documents" &&
                authority != "com.android.providers.downloads.documents"
    }

    var storageRemovedEvent by mutableStateOf<String?>(null)
        private set

    fun onStorageEjected(path: String) {
        loadStorageDevices()
        if (currentPath().startsWith(path)) {
            storageRemovedEvent = path
        }
    }

    fun clearStorageRemovedEvent() {
        storageRemovedEvent = null
    }

    private companion object {
        const val TAG = "Storage"
    }
}
