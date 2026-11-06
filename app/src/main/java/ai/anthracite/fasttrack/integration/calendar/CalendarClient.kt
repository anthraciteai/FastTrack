package ai.anthracite.fasttrack.integration.calendar

import ai.anthracite.fasttrack.data.local.EventType
import ai.anthracite.fasttrack.data.local.ScheduleEvent
import ai.anthracite.fasttrack.integration.ConnectionState
import ai.anthracite.fasttrack.integration.ConnectionStatus
import ai.anthracite.fasttrack.util.Prefs
import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.TimeZone

/** Two-way calendar sync contract. */
interface CalendarClient {
    suspend fun status(): ConnectionStatus
    suspend fun connect(): Boolean

    /** Pull calendar events in a window so workouts show up on the schedule. */
    suspend fun pullEvents(sinceEpochMs: Long, untilEpochMs: Long): List<ScheduleEvent>

    /** Push a locally-created event up to the device calendar; returns the provider event id. */
    suspend fun pushEvent(event: ScheduleEvent): String?

    /** Update an event we previously pushed (matched by [ScheduleEvent.calendarEventId]). */
    suspend fun updateEvent(event: ScheduleEvent): Boolean
}

/**
 * Two-way sync through Android's Calendar Provider.
 *
 * Google Calendar (and Exchange, etc.) already sync into this on-device store. FastTrack reads
 * upcoming instances and writes new events here; the system's calendar sync adapter pushes
 * those up to Google. No OAuth client or Play Services sign-in sheet.
 */
class GoogleCalendarClient(private val context: Context) : CalendarClient {

    private val prefs = Prefs(context)
    private val resolver get() = context.contentResolver

    fun hasPermissions(): Boolean = PERMISSIONS.all { permission ->
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }

    suspend fun onPermissionsGranted(granted: Boolean): Boolean = withContext(Dispatchers.IO) {
        prefs.googleCalendarConnected = granted
        prefs.googleAccountEmail = if (granted) primaryAccountName().orEmpty() else ""
        granted
    }

    fun signOut() {
        prefs.googleAccountEmail = ""
        prefs.googleCalendarConnected = false
    }

    override suspend fun status(): ConnectionStatus = withContext(Dispatchers.IO) {
        if (!hasPermissions()) {
            if (prefs.googleCalendarConnected) prefs.googleCalendarConnected = false
            return@withContext ConnectionStatus(
                ConnectionState.DISCONNECTED,
                "Tap Connect to allow calendar access"
            )
        }
        if (!prefs.googleCalendarConnected) {
            return@withContext ConnectionStatus(
                ConnectionState.DISCONNECTED,
                "Tap Connect to allow calendar access"
            )
        }
        val who = primaryAccountName()
            ?: prefs.googleAccountEmail.ifBlank { "On-device calendar" }
        ConnectionStatus(ConnectionState.CONNECTED, who)
    }

    override suspend fun connect(): Boolean = hasPermissions() && prefs.googleCalendarConnected

    override suspend fun pullEvents(sinceEpochMs: Long, untilEpochMs: Long): List<ScheduleEvent> {
        if (!hasPermissions() || !prefs.googleCalendarConnected) return emptyList()
        return withContext(Dispatchers.IO) {
            runCatching {
                val builder = CalendarContract.Instances.CONTENT_URI.buildUpon()
                ContentUris.appendId(builder, sinceEpochMs)
                ContentUris.appendId(builder, untilEpochMs)
                val uri = builder.build()
                val order = "${CalendarContract.Instances.BEGIN} ASC"
                val visible = "${CalendarContract.Instances.VISIBLE} = 1"
                val cursor = runCatching {
                    resolver.query(uri, INSTANCE_PROJECTION, visible, null, order)
                }.getOrNull() ?: resolver.query(uri, INSTANCE_PROJECTION, null, null, order)
                cursor?.use { cur ->
                    buildList {
                        var count = 0
                        while (cur.moveToNext() && count < MAX_PULL) {
                            val eventId = cur.getLong(0)
                            val begin = cur.getLong(1)
                            val end = cur.getLong(2)
                            val title = cur.getString(3)?.trim().orEmpty().ifBlank { "Calendar event" }
                            val notes = cur.getString(4)?.trim()?.ifBlank { null }
                            add(
                                ScheduleEvent(
                                    title = title,
                                    startEpochMs = begin,
                                    endEpochMs = if (end > begin) end else begin + 3_600_000L,
                                    type = EventType.OTHER.name,
                                    notes = notes,
                                    calendarEventId = "$eventId:$begin",
                                    synced = true
                                )
                            )
                            count++
                        }
                    }
                } ?: emptyList()
            }.getOrDefault(emptyList())
        }
    }

    override suspend fun pushEvent(event: ScheduleEvent): String? {
        if (!hasPermissions() || !prefs.googleCalendarConnected) return null
        return withContext(Dispatchers.IO) {
            runCatching {
                val calendar = writableCalendar() ?: return@runCatching null
                val values = ContentValues().apply {
                    put(CalendarContract.Events.CALENDAR_ID, calendar.first)
                    put(CalendarContract.Events.DTSTART, event.startEpochMs)
                    put(CalendarContract.Events.DTEND, event.endEpochMs)
                    put(CalendarContract.Events.TITLE, event.title)
                    put(CalendarContract.Events.DESCRIPTION, event.notes ?: "FastTrack")
                    put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
                }
                val uri = resolver.insert(CalendarContract.Events.CONTENT_URI, values) ?: return@runCatching null
                uri.lastPathSegment
            }.getOrNull()
        }
    }

    override suspend fun updateEvent(event: ScheduleEvent): Boolean {
        val providerId = event.calendarEventId?.toLongOrNull() ?: return false
        if (!hasPermissions() || !prefs.googleCalendarConnected) return false
        return withContext(Dispatchers.IO) {
            runCatching {
                val values = ContentValues().apply {
                    put(CalendarContract.Events.DTSTART, event.startEpochMs)
                    put(CalendarContract.Events.DTEND, event.endEpochMs)
                    put(CalendarContract.Events.TITLE, event.title)
                    put(CalendarContract.Events.DESCRIPTION, event.notes ?: "FastTrack")
                }
                val uri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, providerId)
                resolver.update(uri, values, null, null) > 0
            }.getOrDefault(false)
        }
    }

    private fun primaryAccountName(): String? = writableCalendar()?.second

    /**
     * Prefer a writable Google calendar (primary, then any synced), then any other writable calendar
     * on the device. Returns calendar _ID and ACCOUNT_NAME.
     */
    private fun writableCalendar(): Pair<Long, String>? {
        if (!hasPermissions()) return null
        val cursor = resolver.query(
            CalendarContract.Calendars.CONTENT_URI,
            CALENDAR_PROJECTION,
            null,
            null,
            null
        ) ?: return null
        val rows = cursor.use { cur ->
            buildList<CalendarRow> {
                while (cur.moveToNext()) {
                    add(
                        CalendarRow(
                            id = cur.getLong(0),
                            account = cur.getString(1).orEmpty(),
                            type = cur.getString(2).orEmpty(),
                            access = if (cur.isNull(3)) 0 else cur.getInt(3),
                            primary = !cur.isNull(4) && cur.getInt(4) == 1,
                            sync = !cur.isNull(5) && cur.getInt(5) == 1
                        )
                    )
                }
            }
        }

        val writable = rows.filter { it.access >= CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR }
        val pick = writable.firstOrNull { it.type == GOOGLE_ACCOUNT && it.primary }
            ?: writable.firstOrNull { it.type == GOOGLE_ACCOUNT && it.sync }
            ?: writable.firstOrNull { it.type == GOOGLE_ACCOUNT }
            ?: writable.firstOrNull { it.primary }
            ?: writable.firstOrNull()
        return pick?.let { it.id to it.account.ifBlank { it.type.ifBlank { "Calendar" } } }
    }

    private data class CalendarRow(
        val id: Long,
        val account: String,
        val type: String,
        val access: Int,
        val primary: Boolean,
        val sync: Boolean
    )

    companion object {
        val PERMISSIONS = arrayOf(
            Manifest.permission.READ_CALENDAR,
            Manifest.permission.WRITE_CALENDAR
        )
        private const val GOOGLE_ACCOUNT = "com.google"
        private const val MAX_PULL = 200
        private val CALENDAR_PROJECTION = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.ACCOUNT_NAME,
            CalendarContract.Calendars.ACCOUNT_TYPE,
            CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL,
            CalendarContract.Calendars.IS_PRIMARY,
            CalendarContract.Calendars.SYNC_EVENTS
        )
        private val INSTANCE_PROJECTION = arrayOf(
            CalendarContract.Instances.EVENT_ID,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.DESCRIPTION
        )
    }
}
