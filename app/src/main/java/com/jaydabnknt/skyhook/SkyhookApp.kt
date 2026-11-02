package com.jaydabnknt.skyhook

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.SharedPreferences
import com.jaydabnknt.skyhook.cloud.Destination

class SkyhookApp : Application() {
    override fun onCreate() {
        super.onCreate()
        instance = this
        // Gecko content processes also run Application.onCreate; keep this light.
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_DOWNLOADS, getString(R.string.channel_downloads), NotificationManager.IMPORTANCE_LOW)
        )
    }

    companion object {
        const val CHANNEL_DOWNLOADS = "downloads"
        lateinit var instance: SkyhookApp
            private set
    }
}

/** All user settings in one place. */
object Prefs {
    private val p: SharedPreferences
        get() = SkyhookApp.instance.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var destination: Destination
        get() = runCatching { Destination.valueOf(p.getString("destination", null) ?: "") }.getOrDefault(Destination.LOCAL)
        set(v) = p.edit().putString("destination", v.name).apply()

    var folderName: String
        get() = p.getString("folder", "Skyhook")!!.ifBlank { "Skyhook" }
        set(v) = p.edit().putString("folder", v.trim()).apply()

    var searchEngine: String
        get() = p.getString("search", SEARCH_ENGINES.keys.first())!!
        set(v) = p.edit().putString("search", v).apply()

    var dropboxAppKey: String
        get() = p.getString("dropbox_key", null)?.takeIf { it.isNotBlank() } ?: BuildConfig.DROPBOX_APP_KEY
        set(v) = p.edit().putString("dropbox_key", v.trim()).apply()

    var yandexClientId: String
        get() = p.getString("yandex_id", null)?.takeIf { it.isNotBlank() } ?: BuildConfig.YANDEX_CLIENT_ID
        set(v) = p.edit().putString("yandex_id", v.trim()).apply()

    var openTabs: List<String>
        get() = p.getString("tabs", "")!!.split('\n').filter { it.isNotBlank() }
        set(v) = p.edit().putString("tabs", v.joinToString("\n")).apply()

    var activeTab: Int
        get() = p.getInt("active_tab", 0)
        set(v) = p.edit().putInt("active_tab", v).apply()

    fun searchUrl(q: String): String =
        (SEARCH_ENGINES[searchEngine] ?: SEARCH_ENGINES.values.first()) + android.net.Uri.encode(q)

    val SEARCH_ENGINES = linkedMapOf(
        "DuckDuckGo" to "https://duckduckgo.com/?q=",
        "Google" to "https://www.google.com/search?q=",
        "Startpage" to "https://www.startpage.com/do/search?q=",
        "Bing" to "https://www.bing.com/search?q=",
    )
}
