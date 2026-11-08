package dev.narayan.rose.update

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import dev.narayan.rose.BuildConfig
import dev.narayan.rose.R
import dev.narayan.rose.domain.repository.SettingsRepository
import dev.narayan.rose.roseNGLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

class UpdateController(
    private val app: Application,
    private val scope: CoroutineScope,
    private val settings: SettingsRepository
) {
    val updatePreferences = UpdatePreferences(app)

    private val _autoCheckUpdates = mutableStateOf(settings.autoCheckUpdates)
    val autoCheckUpdates: Boolean by _autoCheckUpdates

    var skippedVersion by mutableStateOf(settings.skippedUpdateVersion)
        private set

    var updateCheckResult by mutableStateOf<UpdateCheckResult>(UpdateCheckResult.Idle)
        private set

    var updateDownloadState by mutableStateOf<UpdateDownloadState>(UpdateDownloadState.Idle)
        private set

    private var updateDownloadTag: String? = null
    private var updateDownloadJob: Job? = null

    private val updateApkFile get() = File(app.cacheDir, "rose-update.apk")

    fun start() {
        scope.launch {
            updatePreferences.autoCheckEnabledFlow.collect { enabled ->
                _autoCheckUpdates.value = enabled
                settings.autoCheckUpdates = enabled
            }
        }
        scope.launch {
            updatePreferences.skippedVersionFlow.collect { skipped ->
                skippedVersion = skipped
                settings.skippedUpdateVersion = skipped
            }
        }
        performAutoUpdateCheckIfNeeded()
    }

    fun resetUpdateCheck() {
        updateCheckResult = UpdateCheckResult.Idle
    }

    fun dismissUpdateSheet() {
        cancelUpdateDownload()
        resetUpdateCheck()
        runCatching { updateApkFile.delete() }
    }

    fun setAutoCheckUpdates(enabled: Boolean) {
        _autoCheckUpdates.value = enabled
        settings.autoCheckUpdates = enabled
        scope.launch(Dispatchers.IO) { updatePreferences.setAutoCheckEnabled(enabled) }
    }

    fun skipVersion(versionTag: String) {
        skippedVersion = versionTag
        settings.skippedUpdateVersion = versionTag
        scope.launch(Dispatchers.IO) { updatePreferences.setSkippedVersion(versionTag) }
        dismissUpdateSheet()
    }

    fun performAutoUpdateCheckIfNeeded() {
        checkForUpdates(isAutoCheck = true)
    }

    fun checkForUpdates(isAutoCheck: Boolean = false) {
        if (isAutoCheck) {
            if (!autoCheckUpdates) return
            if (autoUpdateCheckedInProcess) return
            autoUpdateCheckedInProcess = true
        } else {
            if (updateCheckResult is UpdateCheckResult.Checking) return
            updateCheckResult = UpdateCheckResult.Checking
        }

        scope.launch(Dispatchers.IO) {
            if (isAutoCheck) {
                val lastCheck = updatePreferences.getLastCheckTime()
                if (System.currentTimeMillis() - lastCheck < AUTO_UPDATE_INTERVAL_MS && lastCheck > 0L) {
                    roseNGLog.d(TAG) { "Auto update check skipped (throttled)" }
                    return@launch
                }
            }

            var connection: HttpURLConnection? = null
            try {
                val conn = (URL(RELEASES_URL).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 10000
                    readTimeout = 10000
                }
                connection = conn

                val responseCode = roseNGLog.timed(TAG, "GitHub latest release request", { "HTTP $it" }) {
                    conn.responseCode
                }
                if (responseCode == 200) {
                    val json = JSONObject(conn.inputStream.bufferedReader().readText())
                    val tagName = json.getString("tag_name")
                    val body = json.optString("body", "")
                    val htmlUrl = json.getString("html_url")

                    val assetsList = mutableListOf<ApkSelector.Asset>()
                    json.optJSONArray("assets")?.let { assets ->
                        for (i in 0 until assets.length()) {
                            val asset = assets.optJSONObject(i) ?: continue
                            val name = asset.optString("name")
                            val downloadUrl = asset.optString("browser_download_url")
                            if (name.isNotBlank() && downloadUrl.isNotBlank()) {
                                assetsList.add(ApkSelector.Asset(name, downloadUrl))
                            }
                        }
                    }
                    val apkUrl = ApkSelector.selectBestApk(assetsList)

                    updatePreferences.setLastCheckTime(System.currentTimeMillis())

                    val isUpdateAvailable = SemanticVersion.isNewerVersion(BuildConfig.VERSION_NAME, tagName)
                    roseNGLog.i(TAG) { "Latest release $tagName (current ${BuildConfig.VERSION_NAME}), update=$isUpdateAvailable" }

                    if (isUpdateAvailable) {
                        if (isAutoCheck) {
                            val skipped = updatePreferences.getSkippedVersion() ?: skippedVersion
                            if (!skipped.isNullOrBlank() && !SemanticVersion.isNewerVersion(skipped, tagName)) {
                                return@launch
                            }
                        }
                        withContext(Dispatchers.Main) {
                            if (updateDownloadTag != tagName) {
                                updateDownloadTag = null
                                updateDownloadState = UpdateDownloadState.Idle
                            }
                            updateCheckResult = UpdateCheckResult.UpdateAvailable(UpdateInfo(tagName, body, htmlUrl, apkUrl))
                        }
                    } else if (!isAutoCheck) {
                        withContext(Dispatchers.Main) { updateCheckResult = UpdateCheckResult.UpToDate }
                    }
                } else if (!isAutoCheck) {
                    withContext(Dispatchers.Main) {
                        updateCheckResult = UpdateCheckResult.Error(app.getString(R.string.update_check_failed_http, responseCode))
                    }
                }
            } catch (e: Exception) {
                roseNGLog.w(TAG, e) { "Update check failed" }
                if (!isAutoCheck) {
                    withContext(Dispatchers.Main) {
                        updateCheckResult = UpdateCheckResult.Error(e.message ?: app.getString(R.string.status_unknown_error))
                    }
                }
            } finally {
                connection?.disconnect()
            }
        }
    }

    fun downloadAndInstallUpdate(info: UpdateInfo) {
        val apkUrl = info.apkUrl ?: return
        if (updateDownloadState is UpdateDownloadState.Downloading) return

        updateDownloadTag = info.tagName
        updateDownloadState = UpdateDownloadState.Downloading(0L, 0L)
        updateDownloadJob = scope.launch(Dispatchers.IO) {
            val apkFile = updateApkFile
            var connection: HttpURLConnection? = null
            try {
                val conn = (URL(apkUrl).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 15000
                    readTimeout = 15000
                    instanceFollowRedirects = true
                    connect()
                }
                connection = conn

                if (conn.responseCode !in 200..299) {
                    val code = conn.responseCode
                    withContext(Dispatchers.Main) {
                        updateDownloadState = UpdateDownloadState.Error(app.getString(R.string.update_download_failed_http, code))
                    }
                    return@launch
                }

                val totalBytes = conn.contentLengthLong
                roseNGLog.timed(TAG, "Download ${info.tagName} APK", { "${apkFile.length()} bytes" }) {
                    var downloadedBytes = 0L
                    var lastReportedPercent = -1
                    var lastReportedBytes = 0L
                    conn.inputStream.use { input ->
                        FileOutputStream(apkFile).use { output ->
                            val buffer = ByteArray(64 * 1024)
                            while (isActive) {
                                val read = input.read(buffer)
                                if (read == -1) break
                                output.write(buffer, 0, read)
                                downloadedBytes += read
                                val percent = if (totalBytes > 0) ((downloadedBytes * 100) / totalBytes).toInt() else -1
                                val shouldReport = if (totalBytes > 0) {
                                    percent != lastReportedPercent
                                } else {
                                    downloadedBytes - lastReportedBytes >= 256 * 1024
                                }
                                if (shouldReport) {
                                    lastReportedPercent = percent
                                    lastReportedBytes = downloadedBytes
                                    val bytesSoFar = downloadedBytes
                                    withContext(Dispatchers.Main) {
                                        updateDownloadState = UpdateDownloadState.Downloading(bytesSoFar, totalBytes)
                                    }
                                }
                            }
                        }
                    }
                }

                if (!isActive) {
                    apkFile.delete()
                    return@launch
                }

                withContext(Dispatchers.Main) {
                    updateDownloadState = UpdateDownloadState.ReadyToInstall(apkFile)
                    installApk(apkFile)
                }
            } catch (e: CancellationException) {
                apkFile.delete()
                throw e
            } catch (e: Exception) {
                apkFile.delete()
                withContext(Dispatchers.Main) {
                    updateDownloadState = UpdateDownloadState.Error(e.message ?: app.getString(R.string.update_download_failed))
                }
            } finally {
                connection?.disconnect()
            }
        }
    }

    fun installDownloadedUpdate() {
        val state = updateDownloadState
        if (state is UpdateDownloadState.ReadyToInstall) installApk(state.file)
    }

    fun cancelUpdateDownload() {
        updateDownloadJob?.cancel()
        updateDownloadJob = null
        if (updateDownloadState is UpdateDownloadState.Downloading) {
            updateDownloadState = UpdateDownloadState.Idle
            updateDownloadTag = null
        }
        runCatching { updateApkFile.delete() }
    }

    fun clear() {
        updateDownloadJob?.cancel()
    }

    private fun installApk(file: File) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !app.packageManager.canRequestPackageInstalls()) {
            val settingsIntent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                data = Uri.parse("package:${app.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            try {
                app.startActivity(settingsIntent)
            } catch (e: Exception) {
                roseNGLog.e(TAG, e) { "Cannot open unknown-sources settings" }
                updateDownloadState = UpdateDownloadState.Error(app.getString(R.string.update_error_open_install_settings))
            }
            return
        }

        val uri = FileProvider.getUriForFile(app, "${app.packageName}.provider", file)
        val installIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            app.startActivity(installIntent)
        } catch (e: Exception) {
            roseNGLog.e(TAG, e) { "No installer activity found" }
            updateDownloadState = UpdateDownloadState.Error(app.getString(R.string.update_error_no_installer))
        }
    }

    private companion object {
        const val TAG = "Update"
        const val RELEASES_URL = "https://api.github.com/repos/gildas-le-drogoff/ROSE-ng/releases/latest"
        const val AUTO_UPDATE_INTERVAL_MS = 24 * 60 * 60 * 1000L

        var autoUpdateCheckedInProcess = false
    }
}
