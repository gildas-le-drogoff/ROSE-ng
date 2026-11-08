package dev.narayan.rose.update

data class SemanticVersion(
    val numbers: List<Int>,
    val preRelease: List<String> = emptyList(),
    val raw: String = ""
) : Comparable<SemanticVersion> {

    val major: Int get() = numbers.getOrElse(0) { 0 }
    val minor: Int get() = numbers.getOrElse(1) { 0 }
    val patch: Int get() = numbers.getOrElse(2) { 0 }
    val isPreRelease: Boolean get() = preRelease.isNotEmpty()

    override fun compareTo(other: SemanticVersion): Int {
        val maxLen = maxOf(this.numbers.size, other.numbers.size)
        for (i in 0 until maxLen) {
            val num1 = this.numbers.getOrElse(i) { 0 }
            val num2 = other.numbers.getOrElse(i) { 0 }
            if (num1 != num2) {
                return num1.compareTo(num2)
            }
        }

        if (this.isPreRelease && !other.isPreRelease) return -1
        if (!this.isPreRelease && other.isPreRelease) return 1
        if (!this.isPreRelease && !other.isPreRelease) return 0

        val maxPre = maxOf(this.preRelease.size, other.preRelease.size)
        for (i in 0 until maxPre) {
            val id1 = this.preRelease.getOrNull(i)
            val id2 = other.preRelease.getOrNull(i)

            if (id1 == null) return -1
            if (id2 == null) return 1

            val num1 = id1.toIntOrNull()
            val num2 = id2.toIntOrNull()

            when {
                num1 != null && num2 != null -> {
                    if (num1 != num2) return num1.compareTo(num2)
                }
                num1 != null && num2 == null -> return -1
                num1 == null && num2 != null -> return 1
                else -> {
                    val cmp = id1.compareTo(id2)
                    if (cmp != 0) return cmp
                }
            }
        }

        return 0
    }

    companion object {
        fun parseOrNull(versionStr: String?): SemanticVersion? {
            if (versionStr.isNullOrBlank()) return null
            var s = versionStr.trim()
            if (s.startsWith("v", ignoreCase = true)) {
                s = s.substring(1).trim()
            }
            if (s.isEmpty()) return null

            val plusIdx = s.indexOf('+')
            val withoutBuild = if (plusIdx >= 0) s.substring(0, plusIdx) else s

            val hyphenIdx = withoutBuild.indexOf('-')
            val coreStr = if (hyphenIdx >= 0) withoutBuild.substring(0, hyphenIdx) else withoutBuild
            val preReleaseStr = if (hyphenIdx >= 0) withoutBuild.substring(hyphenIdx + 1) else null

            val numbers = coreStr.split('.')
                .map { it.trim().toIntOrNull() ?: return null }

            if (numbers.isEmpty()) return null

            val preReleaseList = if (!preReleaseStr.isNullOrBlank()) {
                preReleaseStr.split('.').map { it.trim() }.filter { it.isNotEmpty() }
            } else {
                emptyList()
            }

            return SemanticVersion(
                numbers = numbers,
                preRelease = preReleaseList,
                raw = versionStr
            )
        }

        fun isNewerVersion(current: String?, latest: String?): Boolean {
            if (latest.isNullOrBlank()) return false
            if (current.isNullOrBlank()) return true

            val currentSem = parseOrNull(current)
            val latestSem = parseOrNull(latest)

            if (currentSem != null && latestSem != null) {
                return latestSem > currentSem
            }

            return fallbackIsNewer(current, latest)
        }

        private fun fallbackIsNewer(current: String, latest: String): Boolean {
            val cClean = current.trim().removePrefix("v").removePrefix("V")
            val lClean = latest.trim().removePrefix("v").removePrefix("V")

            val currentParts = cClean.split('.').mapNotNull { it.takeWhile { ch -> ch.isDigit() }.toIntOrNull() }
            val latestParts = lClean.split('.').mapNotNull { it.takeWhile { ch -> ch.isDigit() }.toIntOrNull() }

            val maxLen = maxOf(currentParts.size, latestParts.size)
            for (i in 0 until maxLen) {
                val curr = currentParts.getOrElse(i) { 0 }
                val late = latestParts.getOrElse(i) { 0 }
                if (late > curr) return true
                if (late < curr) return false
            }
            return false
        }
    }
}
