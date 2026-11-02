package com.jaydabnknt.skyhook.browser

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Patterns
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.jaydabnknt.skyhook.Prefs
import com.jaydabnknt.skyhook.R
import com.jaydabnknt.skyhook.download.DownloadPrompt
import com.jaydabnknt.skyhook.media.MediaItem
import com.jaydabnknt.skyhook.media.MediaKind
import com.jaydabnknt.skyhook.media.MediaSheet
import com.jaydabnknt.skyhook.ui.DownloadsActivity
import com.jaydabnknt.skyhook.ui.SettingsActivity
import com.jaydabnknt.skyhook.ui.dp
import org.json.JSONObject
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSession.ContentDelegate
import org.mozilla.geckoview.GeckoSession.NavigationDelegate
import org.mozilla.geckoview.GeckoSession.PermissionDelegate
import org.mozilla.geckoview.GeckoSession.ProgressDelegate
import org.mozilla.geckoview.GeckoView
import org.mozilla.geckoview.WebExtension
import org.mozilla.geckoview.WebResponse

class BrowserActivity : AppCompatActivity() {

    private lateinit var root: LinearLayout
    private lateinit var geckoView: GeckoView
    private lateinit var urlBar: EditText
    private lateinit var progress: ProgressBar
    private lateinit var toolbar: View
    private lateinit var videoIcon: ImageView
    private lateinit var videoBadge: TextView
    private lateinit var uboBadge: TextView
    private lateinit var tabCount: TextView

    private val tabs = mutableListOf<Tab>()
    private var current: Tab? = null
    private var uboDefaultAction: WebExtension.Action? = null
    private var fullscreen = false

    private lateinit var filePicker: FilePicker
    private lateinit var prompts: Prompts

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        Gecko.init(this)
        setContentView(R.layout.activity_browser)

        root = findViewById(R.id.root)
        geckoView = findViewById(R.id.gecko)
        urlBar = findViewById(R.id.url)
        progress = findViewById(R.id.progress)
        toolbar = findViewById(R.id.toolbar)
        videoIcon = findViewById(R.id.video_icon)
        videoBadge = findViewById(R.id.video_badge)
        uboBadge = findViewById(R.id.ubo_badge)
        tabCount = findViewById(R.id.tab_count)

        filePicker = FilePicker(this)
        prompts = Prompts(this) { multiple, mimes, done -> filePicker.pick(multiple, mimes, done) }

        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            if (fullscreen) v.setPadding(0, 0, 0, 0) else v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        setupToolbar()

        Gecko.onReady { runOnUiThread { onExtensionsReady() } }

        // Restore previous tabs, then open whatever we were launched with.
        val saved = Prefs.openTabs
        saved.forEach { newTab(it, select = false) }
        if (tabs.isNotEmpty()) selectTab(tabs[Prefs.activeTab.coerceIn(0, tabs.lastIndex)])
        if (!handleIntent(intent) && tabs.isEmpty()) newTab(HOME)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val t = current
                when {
                    fullscreen -> t?.session?.exitFullScreen()
                    urlBar.hasFocus() -> { urlBar.clearFocus(); hideKeyboard() }
                    t != null && t.canGoBack -> t.session.goBack()
                    t != null && tabs.size > 1 -> closeTab(t)
                    else -> moveTaskToBack(true)
                }
            }
        })

        if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        showLastCrash()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onPause() {
        super.onPause()
        Prefs.openTabs = tabs.map { it.url }.filter { it.startsWith("http") }
        Prefs.activeTab = tabs.indexOf(current).coerceAtLeast(0)
    }

    private fun handleIntent(intent: Intent?): Boolean {
        val target = when (intent?.action) {
            Intent.ACTION_VIEW -> intent.dataString
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)?.let { txt ->
                Regex("https?://\\S+").find(txt)?.value ?: txt
            }
            Intent.ACTION_WEB_SEARCH -> intent.getStringExtra("query")
            else -> null
        } ?: return false
        newTab(toUrl(target))
        return true
    }

    // ---------------------------------------------------------------- toolbar

    private fun setupToolbar() {
        urlBar.setOnEditorActionListener { _, actionId, event ->
            val enter = event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN
            if (actionId == EditorInfo.IME_ACTION_GO || enter) {
                val text = urlBar.text.toString().trim()
                if (text.isNotEmpty()) {
                    (current ?: newTab(null)).session.loadUri(toUrl(text))
                    urlBar.clearFocus()
                    hideKeyboard()
                }
                true
            } else false
        }
        urlBar.setOnFocusChangeListener { _, has ->
            if (!has) urlBar.setText(displayUrl(current?.url ?: ""))
            else urlBar.setText(current?.url ?: "")
            if (has) urlBar.selectAll()
        }

        findViewById<FrameLayout>(R.id.btn_video).setOnClickListener {
            val t = current ?: return@setOnClickListener
            if (t.media.isEmpty()) {
                Toast.makeText(this, "No videos detected on this page yet — start playing one.", Toast.LENGTH_SHORT).show()
            } else MediaSheet.show(this, t.media.values.toList(), t.title)
        }
        findViewById<FrameLayout>(R.id.btn_ubo).setOnClickListener { openUboPopup() }
        findViewById<FrameLayout>(R.id.btn_ubo).setOnLongClickListener { openUboDashboard(); true }
        findViewById<FrameLayout>(R.id.btn_tabs).setOnClickListener { showTabs() }
        findViewById<ImageButton>(R.id.btn_menu).setOnClickListener { showMenu(it) }
    }

    private fun showMenu(anchor: View) {
        val t = current
        val pm = PopupMenu(this, anchor)
        val m = pm.menu
        if (t?.canGoForward == true) m.add(0, 1, 0, "Forward")
        m.add(0, 2, 0, if ((t?.progress ?: 100) < 100) "Stop" else "Reload")
        m.add(0, 3, 0, "New tab")
        m.add(0, 4, 0, "Downloads")
        m.add(0, 5, 0, "uBlock Origin dashboard")
        m.add(0, 6, 0, "Share page")
        m.add(0, 7, 0, "Settings")
        pm.setOnMenuItemClickListener {
            when (it.itemId) {
                1 -> t?.session?.goForward()
                2 -> if ((t?.progress ?: 100) < 100) t?.session?.stop() else t?.session?.reload()
                3 -> { newTab(null); urlBar.requestFocus(); showKeyboard() }
                4 -> startActivity(Intent(this, DownloadsActivity::class.java))
                5 -> openUboDashboard()
                6 -> t?.let { tab ->
                    startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"; putExtra(Intent.EXTRA_TEXT, tab.url)
                    }, null))
                }
                7 -> startActivity(Intent(this, SettingsActivity::class.java))
            }
            true
        }
        pm.show()
    }

    private fun toUrl(input: String): String {
        val s = input.trim()
        if (Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:").containsMatchIn(s) &&
            (s.startsWith("http") || s.startsWith("about:") || s.startsWith("moz-extension:") ||
                s.startsWith("data:") || s.startsWith("file:") || s.startsWith("view-source:"))
        ) return s
        if (!s.contains(' ') && (Patterns.WEB_URL.matcher(s).matches() || s.startsWith("localhost"))) return "https://$s"
        return Prefs.searchUrl(s)
    }

    private fun displayUrl(url: String) = url.removePrefix("https://").removePrefix("http://").removePrefix("www.").trimEnd('/')

    // ------------------------------------------------------------------- tabs

    private fun newTab(url: String?, select: Boolean = true, openSession: Boolean = true): Tab {
        val session = GeckoSession()
        val tab = Tab(session)
        installDelegates(tab)
        tabs += tab
        if (openSession) {
            session.open(Gecko.runtime)
            attachExtensions(tab)
        }
        if (url != null) {
            tab.url = url
            session.loadUri(url)
        }
        if (select) selectTab(tab)
        updateTabCount()
        return tab
    }

    private fun selectTab(tab: Tab) {
        if (!tab.session.isOpen) tab.session.open(Gecko.runtime)
        attachExtensions(tab)
        current?.let { if (it !== tab) it.session.setActive(false) }
        current = tab
        geckoView.releaseSession()
        geckoView.setSession(tab.session)
        tab.session.setActive(true)
        runCatching { Gecko.runtime.webExtensionController.setTabActive(tab.session, true) }
        if (!urlBar.hasFocus()) urlBar.setText(displayUrl(tab.url))
        updateProgress(tab)
        updateVideoBadge()
        updateUboBadge()
        updateTabCount()
    }

    /**
     * For tabs whose session we hand to GeckoView (popups, target=_blank, extension tabs):
     * GeckoView opens the session itself a moment later and crashes if we open it first,
     * so wait until it has, then show it.
     */
    private fun selectWhenOpened(tab: Tab, tries: Int = 0) {
        when {
            tab !in tabs -> return
            tab.session.isOpen -> selectTab(tab)
            tries < 100 -> geckoView.postDelayed({ selectWhenOpened(tab, tries + 1) }, 30)
            else -> { tabs.remove(tab); updateTabCount() } // GeckoView never used it
        }
    }

    private fun closeTab(tab: Tab) {
        val idx = tabs.indexOf(tab)
        tabs.remove(tab)
        if (current === tab) {
            geckoView.releaseSession()
            current = null
            if (tabs.isEmpty()) newTab(HOME) else selectTab(tabs[(idx - 1).coerceAtLeast(0)])
        }
        tab.session.close()
        updateTabCount()
    }

    private fun tabFor(session: GeckoSession?) = tabs.firstOrNull { it.session === session }

    private fun updateTabCount() {
        tabCount.text = if (tabs.size > 99) ":D" else tabs.size.toString()
    }

    private fun showTabs() {
        val sheet = BottomSheetDialog(this)
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(8), 0, dp(8)) }
        fun row(t: Tab): View = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(16), dp(10), dp(4), dp(10))
            gravity = android.view.Gravity.CENTER_VERTICAL
            if (t === current) setBackgroundColor(0x223D8BFF)
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(context).apply { text = t.label; maxLines = 1; textSize = 15f })
                addView(TextView(context).apply { text = displayUrl(t.url); maxLines = 1; textSize = 12f; alpha = 0.6f })
            }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(ImageButton(context).apply {
                setImageResource(R.drawable.ic_close)
                background = null
                setOnClickListener { closeTab(t); sheet.dismiss(); if (tabs.size > 1) showTabs() }
            })
            setOnClickListener { selectTab(t); sheet.dismiss() }
        }
        tabs.forEach { list.addView(row(it)) }
        list.addView(TextView(this).apply {
            text = "＋  New tab"
            textSize = 15f
            setPadding(dp(16), dp(14), dp(16), dp(14))
            setOnClickListener { sheet.dismiss(); newTab(null); urlBar.requestFocus(); showKeyboard() }
        })
        sheet.setContentView(android.widget.ScrollView(this).apply { addView(list) })
        sheet.show()
    }

    // -------------------------------------------------------------- delegates

    private fun installDelegates(tab: Tab) {
        val s = tab.session
        s.promptDelegate = prompts

        s.progressDelegate = object : ProgressDelegate {
            override fun onPageStart(session: GeckoSession, url: String) {
                tab.progress = 5; tab.url = url
                if (tab === current) updateProgress(tab)
            }
            override fun onPageStop(session: GeckoSession, success: Boolean) {
                tab.progress = 100
                if (tab === current) updateProgress(tab)
            }
            override fun onProgressChange(session: GeckoSession, p: Int) {
                tab.progress = p
                if (tab === current) updateProgress(tab)
            }
        }

        s.navigationDelegate = object : NavigationDelegate {
            override fun onLocationChange(
                session: GeckoSession, url: String?,
                perms: MutableList<PermissionDelegate.ContentPermission>, hasUserGesture: Boolean,
            ) {
                tab.url = url ?: ""
                if (tab === current && !urlBar.hasFocus()) urlBar.setText(displayUrl(tab.url))
            }
            override fun onCanGoBack(session: GeckoSession, canGoBack: Boolean) { tab.canGoBack = canGoBack }
            override fun onCanGoForward(session: GeckoSession, canGoForward: Boolean) { tab.canGoForward = canGoForward }

            override fun onLoadRequest(session: GeckoSession, request: NavigationDelegate.LoadRequest): GeckoResult<AllowOrDeny> {
                val uri = request.uri
                val scheme = uri.substringBefore(':').lowercase()
                if (scheme in WEB_SCHEMES) return GeckoResult.fromValue(AllowOrDeny.ALLOW)
                openExternal(uri, session)
                return GeckoResult.fromValue(AllowOrDeny.DENY)
            }

            override fun onNewSession(session: GeckoSession, uri: String): GeckoResult<GeckoSession> {
                // GeckoView opens the returned session itself, so hand back an unopened one.
                val t = newTab(null, select = false, openSession = false)
                t.url = uri
                selectWhenOpened(t)
                return GeckoResult.fromValue(t.session)
            }
        }

        s.contentDelegate = object : ContentDelegate {
            override fun onTitleChange(session: GeckoSession, title: String?) { tab.title = title ?: "" }

            override fun onFullScreen(session: GeckoSession, fullScreen: Boolean) {
                if (tab === current) setFullscreen(fullScreen)
            }

            override fun onCloseRequest(session: GeckoSession) { closeTab(tab) }

            override fun onCrash(session: GeckoSession) = recoverTab(tab, crashed = true)

            override fun onKill(session: GeckoSession) = recoverTab(tab, crashed = false)

            override fun onExternalResponse(session: GeckoSession, response: WebResponse) {
                DownloadPrompt.fromResponse(this@BrowserActivity, response, tab.url)
            }

            override fun onContextMenu(session: GeckoSession, screenX: Int, screenY: Int, element: ContentDelegate.ContextElement) {
                showContextMenu(tab, element)
            }
        }

        s.permissionDelegate = object : PermissionDelegate {
            override fun onContentPermissionRequest(
                session: GeckoSession, perm: PermissionDelegate.ContentPermission,
            ): GeckoResult<Int> {
                val allow = when (perm.permission) {
                    PermissionDelegate.PERMISSION_AUTOPLAY_INAUDIBLE,
                    PermissionDelegate.PERMISSION_AUTOPLAY_AUDIBLE,
                    PermissionDelegate.PERMISSION_PERSISTENT_STORAGE,
                    PermissionDelegate.PERMISSION_STORAGE_ACCESS -> true
                    else -> false
                }
                return GeckoResult.fromValue(
                    if (allow) PermissionDelegate.ContentPermission.VALUE_ALLOW
                    else PermissionDelegate.ContentPermission.VALUE_DENY
                )
            }
        }
    }

    /**
     * The tab's content process died. Reopen the same session and reload once; if it keeps
     * dying, stop and let the user decide instead of looping.
     */
    private fun recoverTab(tab: Tab, crashed: Boolean) {
        val now = System.currentTimeMillis()
        tab.crashTimes.removeAll { now - it > 30_000 }
        tab.crashTimes += now
        val s = tab.session
        runCatching { if (s.isOpen) s.close() }
        if (current === tab) geckoView.releaseSession()
        s.open(Gecko.runtime)
        attachExtensions(tab)
        if (current === tab) geckoView.setSession(s)
        if (tab.crashTimes.size <= 2) {
            if (tab.url.isNotBlank()) s.loadUri(tab.url)
            if (crashed) Toast.makeText(this, "Page crashed — reloading", Toast.LENGTH_SHORT).show()
        } else {
            MaterialAlertDialogBuilder(this)
                .setTitle("This page keeps crashing")
                .setMessage(tab.url)
                .setPositiveButton("Try again") { _, _ -> tab.crashTimes.clear(); s.loadUri(tab.url) }
                .setNegativeButton("Close tab") { _, _ -> closeTab(tab) }
                .show()
        }
    }

    /** If the app crashed last time, show the error so it can be copied and reported. */
    private fun showLastCrash() {
        val f = com.jaydabnknt.skyhook.SkyhookApp.crashFile(this)
        if (!f.exists()) return
        val text = runCatching { f.readText() }.getOrDefault("")
        f.delete()
        if (text.isBlank()) return
        MaterialAlertDialogBuilder(this)
            .setTitle("Skyhook crashed last time")
            .setMessage(text.take(3000))
            .setPositiveButton("Copy") { _, _ -> copy(text); Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show() }
            .setNegativeButton("Dismiss", null)
            .show()
    }

    private fun openExternal(uri: String, session: GeckoSession) {
        try {
            val intent = if (uri.startsWith("intent:")) Intent.parseUri(uri, Intent.URI_INTENT_SCHEME)
            else Intent(Intent.ACTION_VIEW, Uri.parse(uri))
            intent.addCategory(Intent.CATEGORY_BROWSABLE)
            intent.component = null
            intent.selector = null
            try {
                startActivity(intent)
            } catch (e: Exception) {
                intent.getStringExtra("browser_fallback_url")?.let { session.loadUri(it) }
                    ?: Toast.makeText(this, "No app can open this link", Toast.LENGTH_SHORT).show()
            }
        } catch (_: Exception) {
        }
    }

    private fun showContextMenu(tab: Tab, el: ContentDelegate.ContextElement) {
        val link = el.linkUri
        val src = el.srcUri
        val items = mutableListOf<Pair<String, () -> Unit>>()
        if (link != null) {
            items += "Open in new tab" to { newTab(link, select = false); Toast.makeText(this, "Opened in background", Toast.LENGTH_SHORT).show() }
            items += "Copy link" to { copy(link) }
            items += "Download link" to { DownloadPrompt.fetchWithGecko(this, link, tab.url) }
        }
        if (src != null && el.type != ContentDelegate.ContextElement.TYPE_NONE) {
            val kind = when (el.type) {
                ContentDelegate.ContextElement.TYPE_IMAGE -> "image"
                ContentDelegate.ContextElement.TYPE_AUDIO -> "audio"
                else -> "video"
            }
            if (!src.startsWith("blob:")) items += "Download $kind" to { DownloadPrompt.fetchWithGecko(this, src, tab.url) }
            items += "Copy $kind address" to { copy(src) }
            if (kind == "image") items += "Open image in new tab" to { newTab(src); Unit }
        }
        if (items.isEmpty()) return
        MaterialAlertDialogBuilder(this)
            .setTitle(link ?: src)
            .setItems(items.map { it.first }.toTypedArray()) { _, i -> items[i].second() }
            .show()
    }

    private fun copy(s: String) {
        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("link", s))
    }

    // ------------------------------------------------------------- extensions

    private fun onExtensionsReady() {
        tabs.forEach { attachExtensions(it) }
        Gecko.ubo?.let { ubo ->
            ubo.setActionDelegate(uboDelegate(null))
            ubo.setTabDelegate(object : WebExtension.TabDelegate {
                override fun onNewTab(source: WebExtension, createDetails: WebExtension.CreateTabDetails): GeckoResult<GeckoSession> {
                    val t = newTab(null, select = false, openSession = false)
                    t.url = createDetails.url ?: ""
                    selectWhenOpened(t)
                    return GeckoResult.fromValue(t.session)
                }
            })
        }
        Gecko.uboError?.let {
            Toast.makeText(this, "uBlock Origin failed to load: $it", Toast.LENGTH_LONG).show()
        }
    }

    private fun attachExtensions(tab: Tab) {
        if (!tab.session.isOpen) return
        val ctl = tab.session.webExtensionController
        Gecko.detector?.let { ctl.setMessageDelegate(it, detectorDelegate(tab), "browser") }
        Gecko.ubo?.let { ctl.setActionDelegate(it, uboDelegate(tab)) }
        tab.extensionsAttached = true
    }

    private fun detectorDelegate(tab: Tab) = object : WebExtension.MessageDelegate {
        override fun onMessage(nativeApp: String, message: Any, sender: WebExtension.MessageSender): GeckoResult<Any>? {
            val json = message as? JSONObject ?: return null
            when (json.optString("type")) {
                "reset" -> tab.media.clear()
                "media" -> {
                    val item = json.optJSONObject("item")?.let { MediaItem.fromJson(it) } ?: return null
                    tab.media[item.url] = item
                    if (item.title.isNotBlank() && tab.title.isBlank()) tab.title = item.title
                }
            }
            if (tab === current) runOnUiThread { updateVideoBadge() }
            return null
        }
    }

    private fun uboDelegate(tab: Tab?) = object : WebExtension.ActionDelegate {
        override fun onBrowserAction(extension: WebExtension, session: GeckoSession?, action: WebExtension.Action) {
            val t = tab ?: tabFor(session)
            if (t == null) uboDefaultAction = action else t.uboAction = action
            runOnUiThread { updateUboBadge() }
        }
        override fun onTogglePopup(extension: WebExtension, action: WebExtension.Action): GeckoResult<GeckoSession>? = showPopup()
        override fun onOpenPopup(extension: WebExtension, action: WebExtension.Action): GeckoResult<GeckoSession>? = showPopup()
    }

    private fun openUboPopup() {
        val action = current?.uboAction ?: uboDefaultAction
        if (action == null) {
            if (Gecko.ubo == null) Toast.makeText(this, "uBlock Origin is still loading…", Toast.LENGTH_SHORT).show()
            else openUboDashboard()
            return
        }
        current?.let { runCatching { Gecko.runtime.webExtensionController.setTabActive(it.session, true) } }
        action.click()
    }

    private fun openUboDashboard() {
        val url = Gecko.ubo?.metaData?.optionsPageUrl
        if (url == null) Toast.makeText(this, "uBlock Origin isn't ready", Toast.LENGTH_SHORT).show()
        else newTab(url)
    }

    private var popupSheet: BottomSheetDialog? = null

    private fun showPopup(): GeckoResult<GeckoSession> {
        popupSheet?.dismiss()
        val session = GeckoSession()
        session.open(Gecko.runtime)
        val view = GeckoView(this)
        val sheet = BottomSheetDialog(this)
        session.contentDelegate = object : ContentDelegate {
            override fun onCloseRequest(session: GeckoSession) { sheet.dismiss() }
        }
        session.promptDelegate = prompts
        view.setSession(session)
        sheet.setContentView(view, android.view.ViewGroup.LayoutParams(-1, (resources.displayMetrics.heightPixels * 0.7).toInt()))
        sheet.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        sheet.behavior.isDraggable = false
        sheet.setOnDismissListener {
            view.releaseSession()
            session.close()
            if (popupSheet === sheet) popupSheet = null
        }
        popupSheet = sheet
        sheet.show()
        return GeckoResult.fromValue(session)
    }

    // ---------------------------------------------------------------- visuals

    private fun updateProgress(tab: Tab) {
        val p = tab.progress
        progress.visibility = if (p in 1..99) View.VISIBLE else View.INVISIBLE
        progress.progress = p
    }

    private fun updateVideoBadge() {
        val items = current?.media?.values ?: emptyList()
        val count = items.count { it.kind != MediaKind.AUDIO || items.size == 1 }
        videoIcon.alpha = if (count > 0) 1f else 0.45f
        videoIcon.setColorFilter(if (count > 0) getColor(R.color.video_green) else getColor(R.color.on_bar))
        videoBadge.visibility = if (count > 0) View.VISIBLE else View.GONE
        videoBadge.text = if (count > 9) "9+" else count.toString()
    }

    private fun updateUboBadge() {
        val a = current?.uboAction?.let { tabAction -> uboDefaultAction?.let { tabAction.withDefault(it) } ?: tabAction }
            ?: uboDefaultAction
        uboBadge.text = a?.badgeText ?: ""
    }

    private fun setFullscreen(on: Boolean) {
        fullscreen = on
        toolbar.visibility = if (on) View.GONE else View.VISIBLE
        progress.visibility = if (on) View.GONE else View.INVISIBLE
        val c = WindowInsetsControllerCompat(window, root)
        if (on) {
            c.hide(WindowInsetsCompat.Type.systemBars())
            c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else c.show(WindowInsetsCompat.Type.systemBars())
        ViewCompat.requestApplyInsets(root)
    }

    private fun hideKeyboard() {
        getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(urlBar.windowToken, 0)
    }

    private fun showKeyboard() {
        urlBar.post { getSystemService(InputMethodManager::class.java).showSoftInput(urlBar, 0) }
    }

    companion object {
        const val HOME = "https://duckduckgo.com/"
        private val WEB_SCHEMES = setOf(
            "http", "https", "about", "data", "blob", "file", "resource", "moz-extension", "view-source", "javascript", "chrome"
        )
    }
}
