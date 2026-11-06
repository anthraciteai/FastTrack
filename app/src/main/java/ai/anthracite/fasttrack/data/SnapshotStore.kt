package ai.anthracite.fasttrack.data

import ai.anthracite.fasttrack.data.repository.FastTrackRepository
import ai.anthracite.fasttrack.util.Prefs
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/** Disk backup of the Room database so logs survive process death, reboot, and schema-safe upgrades. */
class SnapshotStore(context: Context) {
    private val file = File(context.filesDir, "fasttrack-snapshot.json")

    suspend fun write(repo: FastTrackRepository, prefs: Prefs) = withContext(Dispatchers.IO) {
        runCatching { file.writeText(repo.exportSnapshot(prefs).toString()) }
    }

    suspend fun restoreIfEmpty(repo: FastTrackRepository, prefs: Prefs): Boolean = withContext(Dispatchers.IO) {
        if (repo.getProfileOrNull() != null) return@withContext false
        if (!file.exists()) return@withContext false
        val json = runCatching { JSONObject(file.readText()) }.getOrNull() ?: return@withContext false
        repo.importSnapshot(json, prefs)
        true
    }
}
