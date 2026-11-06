package ai.anthracite.fasttrack.session

import ai.anthracite.fasttrack.MainActivity
import ai.anthracite.fasttrack.R
import ai.anthracite.fasttrack.notifications.NotificationHelper
import ai.anthracite.fasttrack.util.TimeFmt
import ai.anthracite.fasttrack.util.UnitSystem
import ai.anthracite.fasttrack.util.Units
import android.Manifest
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Foreground service that accumulates GPS distance for the live workout and keeps an ongoing
 * notification updated with distance + elapsed time, so tracking continues with the screen off or
 * the app backgrounded. Distance is published through [WorkoutTracker]; the service holds no state
 * the UI needs directly.
 */
class WorkoutTrackingService : Service() {

    private lateinit var fused: FusedLocationProviderClient
    private var lastLocation: Location? = null
    private var unit: UnitSystem = UnitSystem.IMPERIAL
    private val scope = CoroutineScope(SupervisorJob())
    private var notifyJob: Job? = null

    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            for (loc in result.locations) accumulate(loc)
        }
    }

    override fun onCreate() {
        super.onCreate()
        fused = LocationServices.getFusedLocationProviderClient(this)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stop()
                return START_NOT_STICKY
            }
            else -> start(intent)
        }
        return START_STICKY
    }

    private fun start(intent: Intent?) {
        val type = intent?.getStringExtra(EXTRA_TYPE) ?: WorkoutTracker.state.value?.type ?: "Workout"
        unit = runCatching { UnitSystem.valueOf(intent?.getStringExtra(EXTRA_UNIT) ?: "") }
            .getOrDefault(UnitSystem.IMPERIAL)
        if (!WorkoutTracker.isTracking) {
            WorkoutTracker.begin(type, System.currentTimeMillis())
        }
        startForegroundWithState()
        requestUpdates()
        // Refresh the notification as distance/time change.
        notifyJob?.cancel()
        notifyJob = scope.launch {
            WorkoutTracker.state.collectLatest { updateNotification() }
        }
    }

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    private fun requestUpdates() {
        if (!hasLocationPermission()) return
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, UPDATE_INTERVAL_MS)
            .setMinUpdateIntervalMillis(MIN_UPDATE_INTERVAL_MS)
            .setMinUpdateDistanceMeters(0f)
            .build()
        try {
            fused.requestLocationUpdates(request, callback, mainLooper)
        } catch (_: SecurityException) {
        }
    }

    /** Add the segment from the previous fix, filtering noise via [GpsDistanceFilter]. */
    private fun accumulate(loc: Location) {
        val prev = lastLocation
        if (prev == null) {
            // Don't anchor on a bad first fix, but do mark that GPS is live.
            if (loc.hasAccuracy() && loc.accuracy > GpsDistanceFilter.MAX_ACCURACY_M) return
            lastLocation = loc
            WorkoutTracker.addDistance(0.0)
            return
        }
        val meters = prev.distanceTo(loc).toDouble()
        val seconds = (loc.time - prev.time) / 1000.0
        when (GpsDistanceFilter.classify(loc.accuracy, loc.hasAccuracy(), meters, seconds)) {
            GpsDistanceFilter.Action.SKIP_INACCURATE,
            GpsDistanceFilter.Action.SKIP_JITTER -> Unit
            GpsDistanceFilter.Action.REANCHOR_JUMP -> lastLocation = loc
            GpsDistanceFilter.Action.COUNT -> {
                WorkoutTracker.addDistance(meters)
                lastLocation = loc
            }
        }
    }

    private fun startForegroundWithState() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NotificationHelper.ID_WORKOUT_ONGOING, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(NotificationHelper.ID_WORKOUT_ONGOING, notification)
        }
    }

    private fun updateNotification() {
        NotificationHelper.postIfAllowed(this, NotificationHelper.ID_WORKOUT_ONGOING, buildNotification())
    }

    private fun buildNotification(): android.app.Notification {
        val s = WorkoutTracker.state.value
        val elapsed = if (s != null) System.currentTimeMillis() - s.startMs else 0L
        val type = s?.type ?: "Workout"
        val distanceText = when {
            s == null -> ""
            !s.hasFix -> " · acquiring GPS…"
            else -> " · " + Units.distanceLabel(s.distanceMeters, unit)
        }
        val openApp = PendingIntent.getActivity(
            this, NotificationHelper.ID_WORKOUT_ONGOING,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, NotificationHelper.CHANNEL_FASTING)
            .setSmallIcon(R.drawable.ic_exercise)
            .setContentTitle("$type · ${TimeFmt.hms(elapsed)}")
            .setContentText("Tracking your workout$distanceText")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp)
            .build()
    }

    private fun stop() {
        runCatching { fused.removeLocationUpdates(callback) }
        notifyJob?.cancel()
        lastLocation = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION") stopForeground(true)
        }
        stopSelf()
    }

    override fun onDestroy() {
        runCatching { fused.removeLocationUpdates(callback) }
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val ACTION_STOP = "ai.anthracite.fasttrack.workout.STOP"
        private const val EXTRA_TYPE = "type"
        private const val EXTRA_UNIT = "unit"

        private const val UPDATE_INTERVAL_MS = 3_000L
        private const val MIN_UPDATE_INTERVAL_MS = 1_000L

        /** Start tracking [type] (distances rendered in [unit]). */
        fun start(context: Context, type: String, unit: UnitSystem) {
            val intent = Intent(context, WorkoutTrackingService::class.java)
                .putExtra(EXTRA_TYPE, type)
                .putExtra(EXTRA_UNIT, unit.name)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, WorkoutTrackingService::class.java).setAction(ACTION_STOP)
            context.startService(intent)
        }
    }
}
