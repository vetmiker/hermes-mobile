package com.m57.hermescontrol.data.update

import com.m57.hermescontrol.data.remote.OkHttpProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Latest release metadata from the GitHub releases API (issue #867) — the
 * in-app self-update source. Fields map to the JSON via the app's shared
 * snake_case [OkHttpProvider.json].
 */
@Serializable
data class UpdateInfo(
    val tagName: String = "",
    @SerialName("html_url")
    val htmlUrl: String = "",
)

/** Strip a leading 'v' from a release tag ("v1.21.0" → "1.21.0"). */
fun normalizedVersion(tag: String): String = tag.trim().removePrefix("v")

/**
 * True when [latest] is strictly newer than [current]. Numeric dot-segment
 * comparison ("1.21.0" > "1.2"). Non-numeric suffixes (e.g. "1.0-dev")
 * compare as 0; an unparseable version never claims an update exists.
 */
fun isNewerVersion(
    latest: String,
    current: String,
): Boolean {
    val a = versionSegments(latest) ?: return false
    val b = versionSegments(current) ?: return false
    for (i in 0 until maxOf(a.size, b.size)) {
        val x = a.getOrElse(i) { 0 }
        val y = b.getOrElse(i) { 0 }
        if (x != y) return x > y
    }
    return false
}

private fun versionSegments(version: String): List<Int>? {
    val cleaned = normalizedVersion(version)
    if (cleaned.isBlank()) return null
    return cleaned.split('.').map { segment ->
        // Leading numeric portion of each segment: "0-dev" → 0, "21" → 21.
        segment.takeWhile { it.isDigit() }.toIntOrNull() ?: 0
    }
}

/**
 * Fetches upstream release metadata for the personal fork's rebase notice.
 * APK assets are intentionally never downloaded or installed because they
 * are signed by a different lineage.
 */
open class AppUpdateChecker(
    private val client: OkHttpClient = OkHttpProvider.base,
) {
    /**
     * Fetch the latest release metadata. Returns null when there is no
     * release yet (404) or the body can't be parsed. Throws [IOException]
     * on network failure so the caller can surface a friendly error.
     */
    open suspend fun fetchLatestRelease(): UpdateInfo? =
        withContext(Dispatchers.IO) {
            val request =
                Request
                    .Builder()
                    .url("https://api.github.com/repos/Hy4ri/hermes-mobile/releases/latest")
                    .header("Accept", "application/vnd.github+json")
                    .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                parseUpdateInfo(response.body.string().orEmpty())
            }
        }
}

/** Parse a GitHub `releases/latest` JSON body into [UpdateInfo], or null. */
fun parseUpdateInfo(json: String): UpdateInfo? =
    runCatching {
        OkHttpProvider.json.decodeFromString<UpdateInfo>(json)
    }.getOrNull()
