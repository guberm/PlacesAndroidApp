package com.guberdev.places.data

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class UpdateInfo(
    val versionName: String,   // e.g. "1.10"
    val releaseNotes: String,
    val downloadUrl: String
)

object UpdateChecker {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private const val RELEASES_API = "https://api.github.com/repos/guberm/PlacesAndroidApp/releases/latest"

    /** Returns UpdateInfo if a newer version is available, null otherwise. */
    suspend fun check(installedVersionName: String): UpdateInfo? = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder()
                .url(RELEASES_API)
                .header("Accept", "application/vnd.github+json")
                .build()
            val body = client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext null
                resp.body?.string() ?: return@withContext null
            }
            val json = JSONObject(body)
            val tagName = json.optString("tag_name", "")          // e.g. "v1.10"
            val releaseNotes = json.optString("body", "")
            val assets = json.optJSONArray("assets")
            val downloadUrl = if (assets != null && assets.length() > 0)
                assets.getJSONObject(0).optString("browser_download_url", "")
            else ""

            if (downloadUrl.isBlank()) return@withContext null

            val remoteVersion = tagName.trimStart('v')             // "1.10"
            val installed = installedVersionName.trimStart('v')

            if (isNewer(remoteVersion, installed)) {
                Log.d("UpdateChecker", "Update available: $installed → $remoteVersion")
                UpdateInfo(remoteVersion, releaseNotes, downloadUrl)
            } else {
                Log.d("UpdateChecker", "App is up to date ($installed)")
                null
            }
        } catch (e: Exception) {
            Log.w("UpdateChecker", "Check failed: ${e.message}")
            null
        }
    }

    /** Returns true if remote is strictly newer than installed (semver numeric comparison). */
    private fun isNewer(remote: String, installed: String): Boolean {
        val r = remote.split(".").map { it.toIntOrNull() ?: 0 }
        val i = installed.split(".").map { it.toIntOrNull() ?: 0 }
        val len = maxOf(r.size, i.size)
        for (idx in 0 until len) {
            val rv = r.getOrElse(idx) { 0 }
            val iv = i.getOrElse(idx) { 0 }
            if (rv > iv) return true
            if (rv < iv) return false
        }
        return false
    }
}
