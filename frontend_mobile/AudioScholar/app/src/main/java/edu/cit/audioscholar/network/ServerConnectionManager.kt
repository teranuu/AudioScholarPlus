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

    private val CONFIGURED_URL = BuildConfig.BASE_URL.ensureTrailingSlash()

    var currentBaseUrl: String = CONFIGURED_URL
        private set

    private var isInitialized = false

    suspend fun determineBestServer(): String = coroutineScope {
        if (isInitialized) {
            Log.d("ServerCheck", "Already initialized. Keeping: $currentBaseUrl")
            return@coroutineScope currentBaseUrl
        }

        val client = OkHttpClient.Builder()
            .connectTimeout(TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .readTimeout(TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .build()

        val configuredCheck = async(Dispatchers.IO) { checkHealth(client, CONFIGURED_URL, "CONFIGURED") }

        if (configuredCheck.await()) {
            currentBaseUrl = CONFIGURED_URL
            isInitialized = true
            Log.d("ServerCheck", "Initialized. Selected configured BASE_URL: $currentBaseUrl")
            return@coroutineScope CONFIGURED_URL
        }

        Log.e("ServerCheck", "Configured BASE_URL is unreachable: $CONFIGURED_URL")
        return@coroutineScope "Error"
    }

    private fun checkHealth(client: OkHttpClient, url: String, tag: String): Boolean {
        return try {
            val request = Request.Builder()
                .url(url)
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                val code = response.code
                // Accept 2xx (Success), 3xx (Redirect), 404 (Not Found),
                // 401/403 (Unauthorized/Forbidden) - all imply server is reachable
                val isAlive = response.isSuccessful ||
                        code in 300..399 ||
                        code == 401 ||
                        code == 403
                Log.d("ServerCheck", "[$tag] $url -> Code: $code, Alive: $isAlive")
                isAlive
            }
        } catch (e: Exception) {
            Log.e("ServerCheck", "[$tag] Failed to connect to $url: ${e.message}")
            false
        }
    }

    private fun String.ensureTrailingSlash(): String = if (endsWith("/")) this else "$this/"
}