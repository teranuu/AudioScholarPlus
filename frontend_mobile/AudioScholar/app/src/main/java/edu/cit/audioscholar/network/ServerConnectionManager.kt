package edu.cit.audioscholar.network

import android.util.Log
import edu.cit.audioscholar.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

object ServerConnectionManager {
    private const val TIMEOUT_MS = 3000L
    private const val API_HEALTH_PATH = "api/users/me"

    private val CONFIGURED_URL = normalizeBaseUrl(BuildConfig.BASE_URL)
    private const val DEV_URL = "https://mastodon-balanced-randomly.ngrok-free.app/"
    private const val LOCAL_URL = "http://192.168.137.1:8080/"

    var currentBaseUrl: String = CONFIGURED_URL
        private set

    var hasReachableServer: Boolean = false
        private set

    var lastConnectionError: String? = null
        private set

    private var isInitialized = false

    suspend fun determineBestServer(forceRefresh: Boolean = false): String = coroutineScope {
        if (isInitialized && !forceRefresh) {
            Log.d("ServerCheck", "Already initialized. Keeping: $currentBaseUrl")
            return@coroutineScope currentBaseUrl
        }

        val client = OkHttpClient.Builder()
            .connectTimeout(TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .readTimeout(TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .build()

        val candidates = listOf(
            "CONFIGURED" to CONFIGURED_URL,
            "LOCAL" to LOCAL_URL,
            "DEV" to DEV_URL
        ).distinctBy { it.second }

        val checks = candidates.map { (tag, url) ->
            tag to url to async(Dispatchers.IO) { checkHealth(client, url, tag) }
        }

        for ((candidate, deferred) in checks) {
            val (tag, url) = candidate
            if (deferred.await()) {
                currentBaseUrl = url
                hasReachableServer = true
                lastConnectionError = null
                isInitialized = true
                Log.d("ServerCheck", "Initialized. Selected: $currentBaseUrl (Priority: $tag)")
                return@coroutineScope url
            }
        }

        currentBaseUrl = CONFIGURED_URL
        hasReachableServer = false
        lastConnectionError = "No configured backend could be reached. Last fallback: $currentBaseUrl"
        isInitialized = true
        Log.e("ServerCheck", "All server checks failed. Falling back to configured URL: $currentBaseUrl")
        return@coroutineScope currentBaseUrl
    }

    suspend fun ensureReachableServer(): Boolean {
        if (hasReachableServer) return true
        determineBestServer(forceRefresh = true)
        return hasReachableServer
    }

    private fun checkHealth(client: OkHttpClient, url: String, tag: String): Boolean {
        return try {
            val healthUrl = url.toApiHealthUrl()
            val request = Request.Builder()
                .url(healthUrl)
                .header("ngrok-skip-browser-warning", "true")
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                val code = response.code
                val contentType = response.header("content-type").orEmpty()
                val renderRouting = response.header("x-render-routing")
                val isBackendStatus = code == 200 ||
                        code == 401 ||
                        code == 403
                val isDeadRenderRoute = renderRouting.equals("no-server", ignoreCase = true)
                val isHtmlFrontend = contentType.contains("text/html", ignoreCase = true)
                val isAlive = isBackendStatus && !isDeadRenderRoute && !isHtmlFrontend
                Log.d(
                    "ServerCheck",
                    "[$tag] $healthUrl -> Code: $code, ContentType: $contentType, RenderRouting: $renderRouting, Alive: $isAlive"
                )
                isAlive
            }
        } catch (e: Exception) {
            Log.e("ServerCheck", "[$tag] Failed to connect to $url: ${e.message}")
            false
        }
    }

    private fun normalizeBaseUrl(url: String): String {
        val trimmed = url.trim()
        return if (trimmed.endsWith("/")) trimmed else "$trimmed/"
    }

    private fun String.toApiHealthUrl(): String {
        return normalizeBaseUrl(this) + API_HEALTH_PATH
    }
}
