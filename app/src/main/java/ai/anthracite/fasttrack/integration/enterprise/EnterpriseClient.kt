package ai.anthracite.fasttrack.integration.enterprise

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

/**
 * Talks to the FastTrack **Enterprise** control plane using the same QR + token
 * protocol the web console and API implement:
 *
 *  - a **join** QR  → [handleScan] calls `POST /v1/enroll`, stores a device token,
 *    then pushes the current snapshot to `/v1/ingest`.
 *  - a **login** QR → [handleScan] calls `POST /v1/auth/qr/approve` with the device
 *    token to sign an enrolled browser into this phone's organization.
 *
 * The QR carries only JSON, so this is entirely platform-neutral — a future native
 * iOS app hits the identical endpoints with its own scanner.
 */
class EnterpriseClient(private val app: FastTrackApp) {

    private val http = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private val prefs get() = app.prefs
    private val pushMutex = Mutex()

    sealed interface Outcome {
        data class Joined(val org: String, val member: String) : Outcome
        data class SignedIn(val org: String) : Outcome
        data class Pushed(val org: String) : Outcome
        data class Error(val message: String) : Outcome
    }

    val isEnrolled: Boolean get() = prefs.enterpriseDeviceToken.isNotBlank()

    fun status(): ConnectionStatus =
        if (isEnrolled) {
            ConnectionStatus(ConnectionState.CONNECTED, "${prefs.enterpriseOrg} · ${prefs.enterpriseMemberId}")
        } else {
            ConnectionStatus(ConnectionState.DISCONNECTED, "Scan a join code to share with your org")
        }

    /** Route a scanned QR payload to the right flow. */
    suspend fun handleScan(raw: String): Outcome = withContext(Dispatchers.IO) {
        val json = runCatching { JSONObject(raw) }.getOrNull()
            ?: return@withContext Outcome.Error("That doesn't look like a FastTrack code")
        val base = json.optString("url").ifBlank { prefs.enterpriseApiBase }.trimEnd('/')
        when (json.optString("kind")) {
            "join" -> {
                if (base.isBlank()) Outcome.Error("The code is missing the server address")
                else join(base, json.optString("token"))
            }
            "login" -> approve(json.optString("session"), json.optString("nonce"))
            else -> Outcome.Error("Unrecognized FastTrack code")
        }
    }

    private suspend fun join(base: String, token: String): Outcome {
        if (token.isBlank()) return Outcome.Error("Invalid join code")
        val profile = app.repository.getProfile()
        val external = prefs.enterpriseMemberId.ifBlank { prefs.installId }
        val body = JSONObject()
            .put("token", token)
            .put("member_external_id", external)
            .put("display_name", profile.displayName)
        val resp = post("$base/v1/enroll", body.toString(), auth = null)
            ?: return Outcome.Error("Couldn't reach the server — check the address and your network")
        if (!resp.ok) return Outcome.Error(enrollError(resp.code))
        val r = runCatching { JSONObject(resp.body) }.getOrNull()
            ?: return Outcome.Error("Unexpected response from server")
        prefs.enterpriseApiBase = base
        prefs.enterpriseDeviceToken = r.optString("device_token")
        prefs.enterpriseOrg = r.optString("org")
        prefs.enterpriseMemberId = r.optString("member_external_id", external)
        // Fill the dashboard immediately with what the phone already has.
        push()
        return Outcome.Joined(prefs.enterpriseOrg, prefs.enterpriseMemberId)
    }

    private suspend fun approve(session: String, nonce: String): Outcome {
        if (!isEnrolled) return Outcome.Error("Join an organization first, then scan to sign a browser in")
        if (session.isBlank() || nonce.isBlank()) return Outcome.Error("Invalid sign-in code")
        val body = JSONObject().put("session_id", session).put("nonce", nonce)
        val resp = post("${prefs.enterpriseApiBase}/v1/auth/qr/approve", body.toString(), auth = prefs.enterpriseDeviceToken)
            ?: return Outcome.Error("Couldn't reach the server")
        if (!resp.ok) {
            return Outcome.Error(if (resp.code == 410) "That sign-in code expired" else "Sign-in failed (${resp.code})")
        }
        val org = runCatching { JSONObject(resp.body).optString("org") }.getOrNull().orEmpty()
        return Outcome.SignedIn(org.ifBlank { prefs.enterpriseOrg })
    }

    /** Push the latest snapshot if enrolled; no-op otherwise. Safe to call often. */
    suspend fun pushIfEnrolled(): Outcome? =
        if (!isEnrolled) null else withContext(Dispatchers.IO) { push() }

    private suspend fun push(): Outcome = pushMutex.withLock {
        val snapshot = app.repository.exportSnapshot(prefs)
        val body = JSONObject()
            .put("member_external_id", prefs.enterpriseMemberId)
            .put("snapshot", snapshot)
        val resp = post("${prefs.enterpriseApiBase}/v1/ingest", body.toString(), auth = prefs.enterpriseDeviceToken)
            ?: return Outcome.Error("Couldn't reach the server")
        if (resp.code == 401) return Outcome.Error("Your device session expired — scan a new join code")
        if (!resp.ok) return Outcome.Error("Push failed (${resp.code})")
        prefs.enterpriseLastPushMs = System.currentTimeMillis()
        return Outcome.Pushed(prefs.enterpriseOrg)
    }

    private fun enrollError(code: Int): String = when (code) {
        401 -> "That join code is invalid or expired — ask for a new one"
        else -> "Join failed ($code)"
    }

    private data class Resp(val code: Int, val body: String) {
        val ok: Boolean get() = code in 200..299
    }

    private fun post(url: String, json: String, auth: String?): Resp? = runCatching {
        val req = Request.Builder()
            .url(url)
            .apply { if (!auth.isNullOrBlank()) header("Authorization", "Bearer $auth") }
            .post(json.toRequestBody(JSON))
            .build()
        http.newCall(req).execute().use { Resp(it.code, it.body?.string().orEmpty()) }
    }.getOrNull()

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
