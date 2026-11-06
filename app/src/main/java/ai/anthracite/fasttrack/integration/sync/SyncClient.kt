package ai.anthracite.fasttrack.integration.sync

import ai.anthracite.fasttrack.BuildConfig
import ai.anthracite.fasttrack.FastTrackApp
import ai.anthracite.fasttrack.integration.ConnectionState
import ai.anthracite.fasttrack.integration.ConnectionStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

interface SyncClient {
    suspend fun status(): ConnectionStatus
    suspend fun pushAll(): SyncResult
    suspend fun syncNow(): SyncResult
}

data class SyncResult(val success: Boolean, val uploaded: Int, val message: String)

class RetrofitSyncClient(private val app: FastTrackApp) : SyncClient {

    private val http = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val mutex = Mutex()

    private val endpoint: String
        get() = BuildConfig.SYNC_BASE_URL.trimEnd('/') + "/v1/sync"

    override suspend fun status(): ConnectionStatus {
        if (!app.googleIdentity.isConfigured()) {
            return ConnectionStatus(
                ConnectionState.NOT_CONFIGURED,
                "Add GOOGLE_OAUTH_CLIENT_ID in local.properties"
            )
        }
        if (!app.googleIdentity.isSignedIn() && !app.prefs.googleSyncConnected) {
            return ConnectionStatus(ConnectionState.DISCONNECTED, "Sign in with Google to sync phone and web")
        }
        val email = app.googleIdentity.email().ifBlank { app.prefs.googleSyncEmail }
        return ConnectionStatus(ConnectionState.CONNECTED, email.ifBlank { "Google" })
    }

    override suspend fun pushAll(): SyncResult = syncNow()

    override suspend fun syncNow(): SyncResult = mutex.withLock {
        withContext(Dispatchers.IO) {
            val token = app.googleIdentity.idToken()
                ?: return@withContext SyncResult(false, 0, "Sign in with Google first")
            runCatching {
                val get = Request.Builder()
                    .url(endpoint)
                    .header("Authorization", "Bearer $token")
                    .get()
                    .build()
                http.newCall(get).execute().use { resp ->
                    if (resp.code == 401) {
                        return@runCatching SyncResult(false, 0, "Google session expired — sign in again")
                    }
                    if (!resp.isSuccessful) {
                        return@runCatching SyncResult(false, 0, "Sync failed (${resp.code})")
                    }
                    val remote = resp.body?.string().orEmpty()
                    if (remote.isNotBlank()) {
                        app.syncing = true
                        try {
                            app.repository.mergeRemote(JSONObject(remote), app.prefs)
                        } finally {
                            app.syncing = false
                        }
                    }
                }
                val body = app.repository.exportSnapshot(app.prefs).toString()
                val put = Request.Builder()
                    .url(endpoint)
                    .header("Authorization", "Bearer $token")
                    .put(body.toRequestBody(JSON))
                    .build()
                http.newCall(put).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        return@runCatching SyncResult(false, 0, "Couldn't save to web (${resp.code})")
                    }
                    val saved = resp.body?.string().orEmpty()
                    if (saved.isNotBlank()) {
                        val json = JSONObject(saved)
                        if (json.has("profile") || json.has("fasting")) {
                            app.syncing = true
                            try {
                                app.repository.mergeRemote(json, app.prefs)
                            } finally {
                                app.syncing = false
                            }
                        }
                    }
                }
                SyncResult(true, 1, "Synced with ${app.googleIdentity.email().ifBlank { "Google" }}")
            }.getOrElse {
                SyncResult(false, 0, "Couldn't reach the web app — run it with npm run dev")
            }
        }
    }

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
