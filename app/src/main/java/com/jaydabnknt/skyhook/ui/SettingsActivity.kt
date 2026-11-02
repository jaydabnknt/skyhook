package com.jaydabnknt.skyhook.ui

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.material.button.MaterialButton
import com.jaydabnknt.skyhook.Prefs
import com.jaydabnknt.skyhook.browser.Gecko
import com.jaydabnknt.skyhook.cloud.Destination
import com.jaydabnknt.skyhook.cloud.Dropbox
import com.jaydabnknt.skyhook.cloud.GoogleDrive
import com.jaydabnknt.skyhook.cloud.OAuthActivity
import com.jaydabnknt.skyhook.cloud.Yandex

class SettingsActivity : AppCompatActivity() {

    private lateinit var content: LinearLayout
    private lateinit var destGroup: RadioGroup
    private val statusViews = mutableMapOf<Destination, Pair<TextView, Button>>()

    private val googleConsent = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { res ->
        try {
            val result = Identity.getAuthorizationClient(this).getAuthorizationResultFromIntent(res.data)
            GoogleDrive.onAuthorized(result)
            toast("Google Drive connected")
        } catch (e: Exception) {
            toast("Google sign-in failed: ${e.message}")
        }
        refresh()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(32))
        }
        val scroll = ScrollView(this).apply { addView(content) }
        scroll.applySystemBarPadding()
        setContentView(scroll)
        build()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    private fun header(t: String) = content.addView(TextView(this).apply {
        text = t; textSize = 13f; isAllCaps = true; alpha = 0.6f
        setPadding(0, dp(22), 0, dp(6))
    })

    private fun note(t: String) = content.addView(TextView(this).apply {
        text = t; textSize = 12f; alpha = 0.6f; setTextIsSelectable(true)
        setPadding(0, dp(2), 0, dp(6))
    })

    private fun field(hint: String, value: String, onChange: (String) -> Unit) = EditText(this).apply {
        this.hint = hint
        setText(value)
        setSingleLine()
        addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) = onChange(s.toString())
        })
    }

    private fun build() {
        content.addView(TextView(this).apply { text = "Settings"; textSize = 24f })

        header("Save downloads to")
        destGroup = RadioGroup(this)
        Destination.entries.forEach { d ->
            destGroup.addView(RadioButton(this).apply { id = View.generateViewId(); tag = d; text = d.label })
        }
        destGroup.setOnCheckedChangeListener { g, id ->
            val d = g.findViewById<RadioButton>(id)?.tag as? Destination ?: return@setOnCheckedChangeListener
            if (d.isConnected()) Prefs.destination = d
            else { toast("Connect ${d.label} below first"); refresh() }
        }
        content.addView(destGroup)
        note("Cloud destinations stream every byte straight to the provider — nothing is stored on the phone, not even temporarily.")
        content.addView(field("Folder name", Prefs.folderName) { Prefs.folderName = it })
        note("Files go into this folder on the phone (Download/…) or in the cloud drive.")

        // ---- Google Drive
        header("Google Drive")
        cloudRow(Destination.GOOGLE_DRIVE, connect = { connectGoogle() }, disconnect = { GoogleDrive.disconnect() })
        note(
            "One-time setup in Google Cloud Console (console.cloud.google.com): create a project → enable \"Google Drive API\" → " +
                "OAuth consent screen (External, add your Gmail as a test user) → Credentials → Create OAuth client ID → Android, " +
                "package name $packageName and this SHA-1 (long-press to copy):"
        )
        content.addView(TextView(this).apply {
            text = signingSha1()
            textSize = 13f
            typeface = android.graphics.Typeface.MONOSPACE
            setTextIsSelectable(true)
        })

        // ---- Dropbox
        header("Dropbox")
        content.addView(field("Dropbox app key", Prefs.dropboxAppKey) { Prefs.dropboxAppKey = it })
        cloudRow(Destination.DROPBOX, connect = {
            startActivity(Intent(this, OAuthActivity::class.java).putExtra(OAuthActivity.EXTRA_PROVIDER, OAuthActivity.DROPBOX))
        }, disconnect = { Dropbox.disconnect() })
        note(
            "dropbox.com/developers/apps → Create app → Scoped access → App folder → Permissions tab: tick files.content.write → " +
                "Settings tab: add redirect URI ${Dropbox.REDIRECT} and copy the App key here."
        )

        // ---- Yandex
        header("Yandex Disk")
        content.addView(field("Yandex client ID", Prefs.yandexClientId) { Prefs.yandexClientId = it })
        cloudRow(Destination.YANDEX, connect = {
            startActivity(Intent(this, OAuthActivity::class.java).putExtra(OAuthActivity.EXTRA_PROVIDER, OAuthActivity.YANDEX))
        }, disconnect = { Yandex.disconnect() })
        note(
            "oauth.yandex.com → Create app → Web services, Redirect URI ${Yandex.REDIRECT} → " +
                "data access: Yandex.Disk REST API (write anywhere on Disk + read). Copy the ClientID here."
        )

        // ---- Search
        header("Search engine")
        val sg = RadioGroup(this)
        Prefs.SEARCH_ENGINES.keys.forEach { k ->
            sg.addView(RadioButton(this).apply {
                id = View.generateViewId(); text = k; isChecked = Prefs.searchEngine == k
                setOnClickListener { Prefs.searchEngine = k }
            })
        }
        content.addView(sg)

        // ---- About
        header("Ad blocking")
        content.addView(TextView(this).apply {
            text = Gecko.uboError?.let { "uBlock Origin failed to load: $it" }
                ?: Gecko.ubo?.let { "uBlock Origin ${it.metaData.version} — tap the red shield for the per-site panel, long-press it for the dashboard (filter lists, element zapper rules, whitelist)." }
                ?: "uBlock Origin is loading…"
            alpha = 0.8f
        })
    }

    private fun cloudRow(d: Destination, connect: () -> Unit, disconnect: () -> Unit) {
        val status = TextView(this)
        val btn = MaterialButton(this)
        btn.setOnClickListener {
            if (d.isConnected()) {
                disconnect()
                if (Prefs.destination == d) Prefs.destination = Destination.LOCAL
                refresh()
            } else connect()
        }
        content.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            addView(status, LinearLayout.LayoutParams(0, -2, 1f))
            addView(btn)
        })
        statusViews[d] = status to btn
    }

    private fun signingSha1(): String = runCatching {
        val info = packageManager.getPackageInfo(packageName, android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES)
        val cert = info.signingInfo!!.apkContentsSigners.first().toByteArray()
        java.security.MessageDigest.getInstance("SHA-1").digest(cert).joinToString(":") { "%02X".format(it) }
    }.getOrDefault("unknown")

    private fun connectGoogle() {
        Identity.getAuthorizationClient(this).authorize(GoogleDrive.authRequest())
            .addOnSuccessListener { result ->
                if (result.hasResolution()) {
                    googleConsent.launch(IntentSenderRequest.Builder(result.pendingIntent!!.intentSender).build())
                } else {
                    GoogleDrive.onAuthorized(result)
                    toast("Google Drive connected")
                    refresh()
                }
            }
            .addOnFailureListener { e ->
                toast("Google sign-in failed: ${e.message}\n\nCheck the OAuth client (package name + SHA-1) in Google Cloud Console.")
            }
    }

    private fun refresh() {
        if (!::destGroup.isInitialized) return
        for (i in 0 until destGroup.childCount) {
            val rb = destGroup.getChildAt(i) as RadioButton
            val d = rb.tag as Destination
            rb.text = if (d.isConnected()) d.label else "${d.label} (not connected)"
            rb.isChecked = d == Prefs.destination
        }
        statusViews.forEach { (d, pair) ->
            val (status, btn) = pair
            val who = when (d) {
                Destination.DROPBOX -> Dropbox.accountLabel()
                Destination.YANDEX -> Yandex.accountLabel()
                else -> null
            }
            status.text = if (d.isConnected()) "Connected" + (who?.let { " · $it" } ?: "") else "Not connected"
            btn.text = if (d.isConnected()) "Disconnect" else "Connect"
        }
    }
}
