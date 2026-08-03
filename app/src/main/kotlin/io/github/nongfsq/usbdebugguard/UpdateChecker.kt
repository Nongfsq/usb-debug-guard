package io.github.nongfsq.usbdebugguard

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

sealed interface UpdateCheckResult {
    data class Available(val version: String, val releaseUrl: String) : UpdateCheckResult
    data class UpToDate(val latestVersion: String) : UpdateCheckResult
    data object Failed : UpdateCheckResult
}

fun interface ReleaseFeedClient {
    @Throws(IOException::class)
    fun fetchLatestRelease(): String
}

class GitHubReleaseFeedClient(
    private val userAgent: String,
) : ReleaseFeedClient {
    override fun fetchLatestRelease(): String {
        val connection = URL(LATEST_RELEASE_API).openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = "GET"
            connection.connectTimeout = NETWORK_TIMEOUT_MILLIS
            connection.readTimeout = NETWORK_TIMEOUT_MILLIS
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            connection.setRequestProperty("User-Agent", userAgent)

            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                throw IOException("GitHub returned HTTP ${connection.responseCode}")
            }
            if (connection.contentLengthLong > MAX_RESPONSE_BYTES) {
                throw IOException("GitHub response is too large")
            }

            connection.inputStream.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (output.size() + count > MAX_RESPONSE_BYTES) {
                        throw IOException("GitHub response is too large")
                    }
                    output.write(buffer, 0, count)
                }
                output.toString(StandardCharsets.UTF_8.name())
            }
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        const val LATEST_RELEASE_API =
            "https://api.github.com/repos/Nongfsq/usb-debug-guard/releases/latest"
        private const val NETWORK_TIMEOUT_MILLIS = 8_000
        private const val MAX_RESPONSE_BYTES = 256 * 1024
    }
}

class GitHubReleaseUpdateChecker(
    private val feedClient: ReleaseFeedClient,
    private val currentVersion: String,
) {
    fun check(): UpdateCheckResult {
        val response = try {
            feedClient.fetchLatestRelease()
        } catch (_: Exception) {
            return UpdateCheckResult.Failed
        }
        val tag = TAG_PATTERN.find(response)?.groupValues?.get(1)
            ?.replace("\\/", "/")
            ?: return UpdateCheckResult.Failed
        val latest = ComparableVersion.parse(tag) ?: return UpdateCheckResult.Failed
        val current = ComparableVersion.parse(currentVersion) ?: return UpdateCheckResult.Failed
        return if (latest > current) {
            UpdateCheckResult.Available(
                version = latest.display,
                releaseUrl = LATEST_RELEASE_PAGE,
            )
        } else {
            UpdateCheckResult.UpToDate(latest.display)
        }
    }

    companion object {
        const val LATEST_RELEASE_PAGE =
            "https://github.com/Nongfsq/usb-debug-guard/releases/latest"
        private val TAG_PATTERN = Regex("\\\"tag_name\\\"\\s*:\\s*\\\"([^\\\"\\\\]+)\\\"")
    }
}

private data class ComparableVersion(
    val numbers: List<Int>,
    val prerelease: String?,
    val display: String,
) : Comparable<ComparableVersion> {
    override fun compareTo(other: ComparableVersion): Int {
        val length = maxOf(numbers.size, other.numbers.size)
        repeat(length) { index ->
            val compared = (numbers.getOrNull(index) ?: 0)
                .compareTo(other.numbers.getOrNull(index) ?: 0)
            if (compared != 0) return compared
        }
        return when {
            prerelease == null && other.prerelease != null -> 1
            prerelease != null && other.prerelease == null -> -1
            else -> prerelease.orEmpty().compareTo(other.prerelease.orEmpty())
        }
    }

    companion object {
        private val PATTERN = Regex("^v?(\\d+(?:\\.\\d+)*)(?:-([0-9A-Za-z.-]+))?$")

        fun parse(raw: String): ComparableVersion? {
            val normalized = raw.trim()
            val match = PATTERN.matchEntire(normalized) ?: return null
            val numbers = match.groupValues[1].split('.').map { part ->
                part.toIntOrNull() ?: return null
            }
            val prerelease = match.groupValues[2].ifBlank { null }
            return ComparableVersion(
                numbers = numbers,
                prerelease = prerelease,
                display = normalized.removePrefix("v"),
            )
        }
    }
}
