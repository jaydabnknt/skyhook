package com.jaydabnknt.skyhook.cloud

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.jaydabnknt.skyhook.browser.Gecko
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSessionSettings
import org.mozilla.geckoview.GeckoView

/**
 * Runs the Dropbox / Yandex sign-in page inside the browser engine and catches the
 * redirect, so no extra SDKs or URL-scheme registrations are needed.
 */
class OAuthActivity : AppCompatActivity() {

    private lateinit var session: GeckoSession
    private var handled = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        Gecko.init(this)
        val provider = intent.getStringExtra(EXTRA_PROVIDER)
        val startUrl = try {
            when (provider) {
                DROPBOX -> Dropbox.authUrl()
                YANDEX -> Yandex.authUrl()
                else -> null
            }
        } catch (e: Exception) {
            Toast.makeText(this, e.message, Toast.LENGTH_LONG).show(); null
        }
        if (startUrl == null) { finish(); return }

        val view = GeckoView(this)
        setContentView(view)
        ViewCompat.setOnApplyWindowInsetsListener(view) { v, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            v.setPadding(b.left, b.top, b.right, b.bottom); insets
        }

        session = GeckoSession(GeckoSessionSettings.Builder().usePrivateMode(true).build())
        session.navigationDelegate = object : GeckoSession.NavigationDelegate {
            override fun onLoadRequest(session: GeckoSession, request: GeckoSession.NavigationDelegate.LoadRequest): GeckoResult<AllowOrDeny> {
                if (isRedirect(provider!!, request.uri)) {
                    complete(provider, request.uri)
                    return GeckoResult.fromValue(AllowOrDeny.DENY)
                }
                return GeckoResult.fromValue(AllowOrDeny.ALLOW)
            }

            override fun onLocationChange(
                session: GeckoSession, url: String?,
                perms: MutableList<GeckoSession.PermissionDelegate.ContentPermission>?, hasUserGesture: Boolean?,
            ) {
                if (url != null && isRedirect(provider!!, url)) complete(provider, url)
            }
        }
        session.open(Gecko.runtime)
        view.setSession(session)
        session.loadUri(startUrl)
    }

    private fun isRedirect(provider: String, uri: String) = when (provider) {
        DROPBOX -> uri.startsWith(Dropbox.REDIRECT)
        YANDEX -> Yandex.isRedirect(uri)
        else -> false
    }

    private fun complete(provider: String, uri: String) {
        if (handled) return
        handled = true
        lifecycleScope.launch {
            val err = withContext(Dispatchers.IO) {
                runCatching {
                    if (provider == DROPBOX) Dropbox.completeAuth(uri) else Yandex.completeAuth(uri)
                }.exceptionOrNull()
            }
            Toast.makeText(
                this@OAuthActivity,
                err?.let { "Sign-in failed: ${it.message}" } ?: "Connected",
                Toast.LENGTH_LONG
            ).show()
            setResult(if (err == null) RESULT_OK else RESULT_CANCELED)
            finish()
        }
    }

    override fun onDestroy() {
        if (::session.isInitialized) session.close()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_PROVIDER = "provider"
        const val DROPBOX = "dropbox"
        const val YANDEX = "yandex"
    }
}
