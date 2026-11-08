package dev.narayan.rose.update

import android.os.Build

object ApkSelector {

    data class Asset(
        val name: String,
        val downloadUrl: String
    )

    fun selectBestApk(
        assets: List<Asset>,
        supportedAbis: Array<String> = Build.SUPPORTED_ABIS
    ): String? {
        val apkAssets = assets.filter { it.name.endsWith(".apk", ignoreCase = true) }
        if (apkAssets.isEmpty()) return null
        if (apkAssets.size == 1) return apkAssets.first().downloadUrl

        for (abi in supportedAbis) {
            val matched = apkAssets.firstOrNull { matchesAbi(it.name, abi) }
            if (matched != null) {
                return matched.downloadUrl
            }
        }

        val universal = apkAssets.firstOrNull { isUniversal(it.name) }
        if (universal != null) {
            return universal.downloadUrl
        }

        return apkAssets.first().downloadUrl
    }

    fun matchesAbi(assetName: String, abi: String): Boolean {
        val lower = assetName.lowercase()
        return when (abi.lowercase()) {
            "arm64-v8a" -> lower.contains("arm64-v8a") || lower.contains("arm64") || lower.contains("aarch64")
            "armeabi-v7a" -> (lower.contains("armeabi-v7a") || lower.contains("armeabi") || lower.contains("armv7") || lower.contains("arm-v7")) &&
                    !lower.contains("arm64") && !lower.contains("aarch64")
            "armeabi" -> (lower.contains("armeabi") || lower.contains("armv6")) &&
                    !lower.contains("arm64") && !lower.contains("aarch64") && !lower.contains("v7a")
            "x86_64" -> lower.contains("x86_64") || lower.contains("x86-64") || lower.contains("x64")
            "x86" -> lower.contains("x86") && !lower.contains("x86_64") && !lower.contains("x86-64") && !lower.contains("x64")
            else -> lower.contains(abi.lowercase())
        }
    }

    fun isUniversal(assetName: String): Boolean {
        val lower = assetName.lowercase()
        return lower.contains("universal") || lower.contains("all-devices") || lower.contains("fat")
    }
}
