package ai.anthracite.fasttrack.ui.common

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File

/** Writes [json] to a cache file and launches a share sheet with it as a `.json` attachment. */
object ShareJson {

    fun share(context: Context, json: String, fileName: String, title: String) {
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        val file = File(dir, fileName)
        file.writeText(json)

        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "application/json"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, fileName)
            putExtra(Intent.EXTRA_TITLE, fileName)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, title).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        })
    }
}
