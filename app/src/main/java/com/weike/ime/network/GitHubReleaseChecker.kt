package com.weike.ime.network

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Reads the public latest stable release without collecting any user data. */
class GitHubReleaseChecker(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .callTimeout(12, TimeUnit.SECONDS)
        .build()
) {
    data class Release(
        val tag: String,
        val name: String,
        val publishedAt: String,
        val notes: String
    )

    suspend fun latestRelease(): Result<Release> = runCatching {
        val request = Request.Builder()
            .url(LATEST_RELEASE_URL)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "Vertick-IME-Android")
            .get()
            .build()
        client.newCall(request).await { response ->
            check(response.isSuccessful) { "GitHub 返回 HTTP ${response.code}" }
            val json = JSONObject(response.body?.string().orEmpty())
            val tag = json.optString("tag_name").trim()
            check(tag.isNotBlank()) { "GitHub Release 未包含版本号" }
            Release(
                tag = tag,
                name = json.optString("name").trim(),
                publishedAt = json.optString("published_at").trim(),
                notes = json.optString("body").trim()
            )
        }
    }

    private suspend fun <T> Call.await(transform: (okhttp3.Response) -> T): T =
        suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { cancel() }
            enqueue(object : Callback {
                override fun onFailure(call: Call, error: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(error)
                }

                override fun onResponse(call: Call, response: okhttp3.Response) {
                    try {
                        if (continuation.isActive) continuation.resume(response.use(transform))
                    } catch (error: Throwable) {
                        if (continuation.isActive) continuation.resumeWithException(error)
                    }
                }
            })
        }

    companion object {
        const val DOWNLOAD_SITE_URL = "https://vertick.cn"
        private const val LATEST_RELEASE_URL = "https://api.github.com/repos/BurgerK1ng16/Vertick-IME/releases/latest"

        /** Compares common Android version names such as v1.4.2 and 1.4.10. */
        fun isNewer(remote: String, local: String): Boolean {
            fun parts(value: String): List<Int> = Regex("\\d+").findAll(value)
                .map { it.value.toIntOrNull() ?: 0 }
                .toList()
            val remoteParts = parts(remote)
            val localParts = parts(local)
            val size = maxOf(remoteParts.size, localParts.size)
            for (index in 0 until size) {
                val difference = (remoteParts.getOrElse(index) { 0 }) - (localParts.getOrElse(index) { 0 })
                if (difference != 0) return difference > 0
            }
            return false
        }
    }
}
