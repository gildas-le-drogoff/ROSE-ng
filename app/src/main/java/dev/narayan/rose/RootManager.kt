package dev.narayan.rose

import android.os.Environment
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import dev.narayan.rose.domain.model.FileItem

private val MULTI_SLASH = Regex("/+")

object RootManager {

    private const val TAG = "RootManager"

    init {
        try {
            Shell.setDefaultBuilder(
                Shell.Builder.create()
                    .setFlags(Shell.FLAG_MOUNT_MASTER or Shell.FLAG_REDIRECT_STDERR)
                    .setTimeout(15)
            )
        } catch (e: Throwable) {
            roseNGLog.w(TAG, e) { "Failed to initialize Shell builder" }
        }
    }

    fun isRootAvailable(): Boolean {
        return try {
            Shell.isAppGrantedRoot() == true || (Shell.getShell().isRoot)
        } catch (e: Throwable) {
            false
        }
    }

    fun shellEscape(arg: String): String {
        return "'" + arg.replace("'", "'\\''") + "'"
    }

    fun normalize(path: String): String {
        if (path.isEmpty()) return "/"
        var normalized = if (path.contains("//")) path.replace(MULTI_SLASH, "/") else path
        if (normalized.length > 1 && normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length - 1)
        }
        return normalized
    }

    fun isRootPath(path: String): Boolean {
        if (SafManager.isSafUri(path)) return false
        val norm = ShizukuManager.normalize(path)
        val primary = Environment.getExternalStorageDirectory().absolutePath
        if (norm == primary || norm.startsWith("$primary/")) return false
        if (norm == "/storage" || norm.startsWith("/storage/")) return false
        if (norm == "/sdcard" || norm.startsWith("/sdcard/")) return false
        if (norm == "/mnt" || norm.startsWith("/mnt/")) return false
        return norm.startsWith("/")
    }

    fun listFilesSync(path: String, showHiddenFiles: Boolean = true): List<FileItem> {
        val results = mutableListOf<FileItem>()
        val cleanPath = normalize(path)

        try {
            val escapedPath = shellEscape(cleanPath)

            val childCounts = mutableMapOf<String, Int>()
            try {
                val countCmd = "find $escapedPath -mindepth 2 -maxdepth 2 2>/dev/null"
                val countResult = Shell.cmd(countCmd).exec()
                if (countResult.out.isNotEmpty()) {
                    countResult.out.forEach { line ->
                        val trimmed = line.trim()
                        if (trimmed.isNotEmpty()) {
                            val name = File(trimmed).name
                            if (showHiddenFiles || !name.startsWith(".")) {
                                val parent = File(trimmed).parent
                                if (parent != null) {
                                    childCounts[parent] = (childCounts[parent] ?: 0) + 1
                                }
                            }
                        }
                    }
                }
            } catch (e: Throwable) {
                roseNGLog.w(TAG, e) { "Error counting subfolder items for $cleanPath" }
            }

            val listCmd = """
                cd $escapedPath 2>/dev/null || exit 1
                for f in * .[!.]* ..?*; do
                    [ -e "${'$'}f" ] || [ -L "${'$'}f" ] || continue
                    d=0
                    [ -d "${'$'}f" ] && d=1
                    stat -c "%F|%s|%Y|${'$'}d|%n" "${'$'}f" 2>/dev/null
                done
            """.trimIndent()

            val statResult = Shell.cmd(listCmd).exec()
            roseNGLog.d(TAG) { "listFilesSync($cleanPath) statResult code: ${statResult.code}, lines: ${statResult.out.size}" }

            if (statResult.out.isNotEmpty()) {
                statResult.out.forEach { line ->
                    val trimmed = line.trim()
                    if (trimmed.isEmpty()) return@forEach
                    val parts = trimmed.split('|', limit = 5)
                    if (parts.size < 5) return@forEach

                    val typeStr = parts[0]
                    val sizeStr = parts[1]
                    val timeStr = parts[2]
                    val isDirFlag = parts[3] == "1"
                    val name = parts[4]

                    if (name == "." || name == ".." || name.isEmpty()) return@forEach
                    if (!showHiddenFiles && name.startsWith(".")) return@forEach

                    val fullPath = if (cleanPath == "/") "/$name" else "$cleanPath/$name"
                    val file = File(fullPath)
                    val isDir = isDirFlag || typeStr.contains("directory", ignoreCase = true)
                    val size = if (isDir) 0L else (sizeStr.toLongOrNull() ?: 0L)
                    val seconds = timeStr.toLongOrNull() ?: 0L
                    val timestamp = seconds * 1000
                    val itemCount = if (isDir) childCounts[fullPath] else null

                    results.add(
                        FileItem(
                            file = file,
                            isDirectory = isDir,
                            name = name,
                            size = size,
                            lastModified = timestamp,
                            extension = if (isDir) "" else name.substringAfterLast('.', "").lowercase(),
                            itemCount = itemCount
                        )
                    )
                }
            }

            if (results.isEmpty()) {
                val lsCmd = "ls -1A $escapedPath 2>/dev/null"
                val lsResult = Shell.cmd(lsCmd).exec()
                roseNGLog.d(TAG) { "listFilesSync($cleanPath) fallback lsResult lines: ${lsResult.out.size}" }
                if (lsResult.out.isNotEmpty()) {
                    lsResult.out.forEach { nameLine ->
                        val name = nameLine.trim()
                        if (name.isNotEmpty() && name != "." && name != "..") {
                            if (showHiddenFiles || !name.startsWith(".")) {
                                val fullPath = if (cleanPath == "/") "/$name" else "$cleanPath/$name"
                                val isDir = isDirectory(fullPath)
                                val size = if (isDir) 0L else getFileSize(fullPath)
                                results.add(
                                    FileItem(
                                        file = File(fullPath),
                                        isDirectory = isDir,
                                        name = name,
                                        size = size,
                                        lastModified = System.currentTimeMillis(),
                                        extension = if (isDir) "" else name.substringAfterLast('.', "").lowercase(),
                                        itemCount = if (isDir) childCounts[fullPath] else null
                                    )
                                )
                            }
                        }
                    }
                }
            }
        } catch (e: Throwable) {
            roseNGLog.e(TAG, e) { "Error listing root files for $cleanPath" }
        }

        return results.distinctBy { it.file.absolutePath }
    }

    suspend fun listFiles(path: String, showHiddenFiles: Boolean = true): List<FileItem> = withContext(Dispatchers.IO) {
        listFilesSync(path, showHiddenFiles)
    }
    fun exists(path: String): Boolean {
        val clean = normalize(path)
        val cmd = "[ -e ${shellEscape(clean)} ] || [ -L ${shellEscape(clean)} ]"
        return Shell.cmd(cmd).exec().isSuccess
    }

    fun isDirectory(path: String): Boolean {
        val clean = normalize(path)
        val cmd = "[ -d ${shellEscape(clean)} ]"
        return Shell.cmd(cmd).exec().isSuccess
    }

    fun getFileSize(path: String): Long {
        val clean = normalize(path)
        val cmd = "stat -c '%s' ${shellEscape(clean)} 2>/dev/null"
        val res = Shell.cmd(cmd).exec()
        return if (res.isSuccess) {
            res.out.firstOrNull()?.trim()?.toLongOrNull() ?: 0L
        } else 0L
    }

    fun getFolderSize(path: String): Long {
        val clean = normalize(path)
        val cmd = "du -sk ${shellEscape(clean)} 2>/dev/null"
        val res = Shell.cmd(cmd).exec()
        return if (res.isSuccess) {
            val kb = res.out.firstOrNull()?.trim()?.split("\\s+".toRegex())?.firstOrNull()?.toLongOrNull() ?: 0L
            kb * 1024L
        } else 0L
    }

    fun createFolder(path: String): Boolean {
        val clean = normalize(path)
        val cmd = "mkdir -p ${shellEscape(clean)}"
        val res = Shell.cmd(cmd).exec()
        return res.isSuccess
    }

    fun createFolder(parentPath: String, name: String): Boolean {
        val parent = normalize(parentPath)
        val target = if (parent == "/") "/$name" else "$parent/$name"
        val cmd = "mkdir -p ${shellEscape(target)}"
        val res = Shell.cmd(cmd).exec()
        return res.isSuccess
    }

    fun createFile(path: String): Boolean {
        val clean = normalize(path)
        val cmd = "touch ${shellEscape(clean)}"
        val res = Shell.cmd(cmd).exec()
        return res.isSuccess
    }

    fun delete(path: String): Boolean {
        val clean = normalize(path)
        if (clean == "/" || clean.isEmpty()) return false
        val cmd = "rm -rf ${shellEscape(clean)}"
        val res = Shell.cmd(cmd).exec()
        return res.isSuccess
    }

    fun rename(path: String, newName: String): Boolean {
        val clean = normalize(path)
        if (clean == "/" || clean.isEmpty()) return false
        val parent = File(clean).parent ?: "/"
        val target = if (parent == "/") "/$newName" else "$parent/$newName"
        val cmd = "mv ${shellEscape(clean)} ${shellEscape(target)}"
        val res = Shell.cmd(cmd).exec()
        return res.isSuccess
    }

    fun copy(sourcePath: String, targetPath: String): Boolean {
        val cleanSrc = normalize(sourcePath)
        val cleanDst = normalize(targetPath)
        val parent = File(cleanDst).parent
        if (parent != null) {
            Shell.cmd("mkdir -p ${shellEscape(normalize(parent))}").exec()
        }
        val cmd = "cp -af ${shellEscape(cleanSrc)} ${shellEscape(cleanDst)}"
        val res = Shell.cmd(cmd).exec()
        return res.isSuccess
    }

    fun move(sourcePath: String, targetPath: String): Boolean {
        val cleanSrc = normalize(sourcePath)
        val cleanDst = normalize(targetPath)
        val parent = File(cleanDst).parent
        if (parent != null) {
            Shell.cmd("mkdir -p ${shellEscape(normalize(parent))}").exec()
        }
        val cmd = "mv -f ${shellEscape(cleanSrc)} ${shellEscape(cleanDst)}"
        val res = Shell.cmd(cmd).exec()
        return res.isSuccess
    }

    fun remountRw(path: String): Boolean {
        val clean = normalize(path)
        val cmd = "mount -o remount,rw ${shellEscape(clean)} 2>/dev/null || mount -o remount,rw / 2>/dev/null"
        return Shell.cmd(cmd).exec().isSuccess
    }

    fun restorecon(path: String): Boolean {
        val clean = normalize(path)
        val cmd = "restorecon -R ${shellEscape(clean)} 2>/dev/null"
        return Shell.cmd(cmd).exec().isSuccess
    }

    fun runCommandSync(command: String): Int {
        return try {
            Shell.cmd(command).exec().code
        } catch (e: Throwable) {
            -1
        }
    }

    fun openInputStream(path: String): InputStream {
        val clean = normalize(path)
        val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "cat ${shellEscape(clean)}"))
        return process.inputStream
    }

    fun openOutputStream(path: String): OutputStream {
        val clean = normalize(path)
        val parent = File(clean).parent
        if (parent != null) {
            Shell.cmd("mkdir -p ${shellEscape(normalize(parent))}").exec()
        }
        val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "cat > ${shellEscape(clean)}"))
        return process.outputStream
    }
}
