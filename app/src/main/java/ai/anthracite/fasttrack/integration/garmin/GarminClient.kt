package ai.anthracite.fasttrack.integration.garmin

import ai.anthracite.fasttrack.BuildConfig
import ai.anthracite.fasttrack.data.local.DailyHealthMetric
import ai.anthracite.fasttrack.data.local.DataSource
import ai.anthracite.fasttrack.integration.ConnectionState
import ai.anthracite.fasttrack.integration.ConnectionStatus
import ai.anthracite.fasttrack.integration.ExternalActivity
import ai.anthracite.fasttrack.integration.HealthDataSource
import ai.anthracite.fasttrack.util.DayKeys
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Garmin Connect "Personal" API client (pull-only).
 *
 * The developer supplies a single Personal API key in `local.properties`
 * ([BuildConfig.GARMIN_PERSONAL_TOKEN]); everything else is automatic:
 *   1. Exchange the ApiKey for a short-lived bearer JWT (cached until ~1h before expiry).
 *   2. Pull activities, daily wellness summaries and sleep with `Authorization: Bearer <jwt>`.
 *   3. Map the responses into [ExternalActivity] / [DailyHealthMetric].
 *
 * The older Business/OAuth path (consumer + activation keys) is left unimplemented; the Personal
 * key is all this build needs.
 */
class GarminClient : HealthDataSource {

    override val source: DataSource = DataSource.GARMIN

    private val consumerKey get() = BuildConfig.GARMIN_CONSUMER_KEY
    private val activationKey get() = BuildConfig.GARMIN_ACTIVATION_KEY
    private val personalToken get() = BuildConfig.GARMIN_PERSONAL_TOKEN

    /** True when we can talk to Garmin: either the Personal pull token, or the Business OAuth pair. */
    val isConfigured: Boolean
        get() = personalToken.isNotBlank() || (consumerKey.isNotBlank() && activationKey.isNotBlank())

    private val http by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    // Cached bearer JWT + its expiry (epoch ms). Refreshed ~1h before it lapses.
    @Volatile private var bearer: String? = null
    @Volatile private var bearerExpiresAtMs: Long = 0L

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    override suspend fun status(): ConnectionStatus = when {
        personalToken.isNotBlank() -> ConnectionStatus(ConnectionState.DISCONNECTED, "Ready to pull")
        consumerKey.isBlank() -> ConnectionStatus(
            ConnectionState.NOT_CONFIGURED,
            "Consumer key missing"
        )
        activationKey.isBlank() -> ConnectionStatus(
            ConnectionState.NOT_CONFIGURED,
            "Activation key pending"
        )
        else -> ConnectionStatus(ConnectionState.DISCONNECTED, "Ready to pair")
    }

    override suspend fun connect(): Boolean {
        if (personalToken.isBlank()) return false
        // "Connecting" just means the Personal key successfully exchanges for a bearer token.
        return runCatching { token() }.getOrNull()?.isNotBlank() == true
    }

    override suspend fun fetchActivities(
        sinceEpochMs: Long,
        untilEpochMs: Long
    ): List<ExternalActivity> {
        if (personalToken.isBlank()) return emptyList()
        val start = dateFmt(sinceEpochMs)
        val end = dateFmt(untilEpochMs)
        val body = get(
            "$ACTIVITIES_PATH?start=0&limit=100&startDate=$start&endDate=$end"
        ) ?: return emptyList()
        val arr = runCatching { JSONArray(body) }.getOrNull() ?: return emptyList()
        val out = ArrayList<ExternalActivity>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val beginMs = o.optLong("beginTimestamp", 0L)
            if (beginMs <= 0L || beginMs < sinceEpochMs || beginMs > untilEpochMs) continue
            val durationSec = o.optDouble("duration", 0.0)
            val endMs = beginMs + (durationSec * 1000.0).toLong()
            val typeKey = o.optJSONObject("activityType")?.optString("typeKey").orEmpty()
            val type = o.optString("activityName").ifBlank { typeKey }.ifBlank { "activity" }
            out += ExternalActivity(
                externalId = o.optLong("activityId").toString(),
                source = DataSource.GARMIN,
                type = type,
                startEpochMs = beginMs,
                endEpochMs = endMs,
                calories = o.optDouble("calories", 0.0).toInt(),
                distanceMeters = o.optDouble("distance", 0.0).takeIf { it > 0.0 },
                avgHeartRate = o.optDouble("averageHR", 0.0).toInt().takeIf { it > 0 }
            )
        }
        return out
    }

    override suspend fun startLiveSession(activityType: String): Boolean {
        // The Personal API is pull-only; there is no way to start a live session on the watch.
        return false
    }

    /**
     * Daily wellness snapshots (all-day stress, resting HR, body battery, sleep). Stress/HR/body
     * battery come from the daily-summaries endpoint; sleep minutes are joined in by date from the
     * sleep-summaries endpoint.
     */
    suspend fun fetchDailyMetrics(
        sinceEpochMs: Long,
        untilEpochMs: Long
    ): List<DailyHealthMetric> {
        if (personalToken.isBlank()) return emptyList()
        val start = dateFmt(sinceEpochMs)
        val end = dateFmt(untilEpochMs)
        val sleepByDate = fetchSleepMinutes(start, end)
        val body = get("$DAILY_SUMMARIES_PATH?startDate=$start&endDate=$end") ?: return emptyList()
        val arr = runCatching { JSONObject(body).optJSONArray("data") }.getOrNull()
            ?: return emptyList()
        val out = ArrayList<DailyHealthMetric>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val date = o.optString("calendarDate").takeIf { it.isNotBlank() } ?: continue
            val hr = o.optJSONObject("heartRate")
            val stress = o.optJSONObject("stress")
            val bodyBattery = o.optJSONObject("bodyBattery")
            out += DailyHealthMetric(
                dayKey = date,
                epochMs = DayKeys.startMs(date),
                stressAvg = stress?.optIntOrNull("avgLevel"),
                restingHeartRate = hr?.optIntOrNull("restingValue"),
                bodyBattery = bodyBattery?.optIntOrNull("maxValue"),
                sleepMinutes = sleepByDate[date],
                source = DataSource.GARMIN.name
            )
        }
        return out
    }

    private suspend fun fetchSleepMinutes(start: String, end: String): Map<String, Int> {
        val body = get("$SLEEP_SUMMARIES_PATH?startDate=$start&endDate=$end") ?: return emptyMap()
        val arr = runCatching { JSONArray(body) }.getOrNull() ?: return emptyMap()
        val map = HashMap<String, Int>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val date = o.optString("calendarDate").takeIf { !o.isNull("calendarDate") && it.isNotBlank() }
                ?: continue
            val secs = o.optIntOrNull("sleepTimeSeconds") ?: continue
            if (secs > 0) map[date] = secs / 60
        }
        return map
    }

    // --- HTTP plumbing -------------------------------------------------------------------------

    /** Exchange the Personal API key for a bearer JWT, caching it until ~1h before it expires. */
    private suspend fun token(): String = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        bearer?.let { if (now < bearerExpiresAtMs - ONE_HOUR_MS) return@withContext it }
        val req = Request.Builder()
            .url("$BASE_URL$TOKEN_PATH")
            .header("Accept", "*/*")
            .header("Authorization", "ApiKey $personalToken")
            .post(ByteArray(0).toRequestBody(null))
            .build()
        http.newCall(req).execute().use { resp ->
            val jwt = resp.body?.string()?.trim().orEmpty()
            check(resp.isSuccessful && jwt.isNotEmpty()) {
                "Garmin token exchange failed (${resp.code})"
            }
            bearer = jwt
            bearerExpiresAtMs = jwtExpiryMs(jwt)
            jwt
        }
    }

    private suspend fun get(path: String): String? = withContext(Dispatchers.IO) {
        val jwt = runCatching { token() }.getOrNull() ?: return@withContext null
        val req = Request.Builder()
            .url("$BASE_URL$path")
            .header("Accept", "application/json")
            .header("Authorization", "Bearer $jwt")
            .build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return@withContext null
            resp.body?.string()
        }
    }

    private fun dateFmt(epochMs: Long): String = dateFormat.format(Date(epochMs))

    /** Read the `exp` claim from a JWT payload; fall back to ~55m out if it can't be parsed. */
    private fun jwtExpiryMs(jwt: String): Long = runCatching {
        val seg = jwt.split(".")[1]
        val padded = seg + "=".repeat((4 - seg.length % 4) % 4)
        val payload = String(Base64.decode(padded, Base64.URL_SAFE or Base64.NO_WRAP))
        JSONObject(payload).getLong("exp") * 1000L
    }.getOrDefault(System.currentTimeMillis() + 55 * 60 * 1000L)

    private fun JSONObject.optIntOrNull(key: String): Int? =
        if (isNull(key) || !has(key)) null else optInt(key, -1).takeIf { it >= 0 }

    companion object {
        private const val BASE_URL = "https://personal.connectapi.garmin.com"
        private const val TOKEN_PATH =
            "/personalapitoken-service/personal-api/personal-api-key/token"
        private const val ACTIVITIES_PATH =
            "/activitylist-service/activities/search/activities"
        private const val DAILY_SUMMARIES_PATH = "/usersummary-service/v2/dailysummaries"
        private const val SLEEP_SUMMARIES_PATH =
            "/sleep-service/sleep/dailySleepSummariesByDate"
        private const val ONE_HOUR_MS = 60L * 60L * 1000L
    }
}
