package ai.anthracite.fasttrack.integration.sync

import ai.anthracite.fasttrack.BuildConfig
import android.app.Activity
import android.content.Context
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

class GoogleIdentity(private val context: Context) {

    fun options(): GoogleSignInOptions =
        GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestIdToken(BuildConfig.GOOGLE_OAUTH_CLIENT_ID)
            .requestEmail()
            .build()

    fun client(activity: Activity): GoogleSignInClient = GoogleSignIn.getClient(activity, options())

    fun isConfigured(): Boolean = BuildConfig.GOOGLE_OAUTH_CLIENT_ID.isNotBlank()

    fun lastAccount() = GoogleSignIn.getLastSignedInAccount(context)

    fun isSignedIn(): Boolean = lastAccount() != null

    fun email(): String = lastAccount()?.email.orEmpty()

    suspend fun idToken(): String? = withContext(Dispatchers.IO) {
        runCatching {
            GoogleSignIn.getClient(context, options()).silentSignIn().await()?.idToken
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: lastAccount()?.idToken
    }

    suspend fun signOut() = withContext(Dispatchers.IO) {
        runCatching { GoogleSignIn.getClient(context, options()).signOut().await() }
    }
}
