package com.jaydabnknt.skyhook.browser

import com.jaydabnknt.skyhook.media.MediaItem
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.WebExtension

class Tab(val session: GeckoSession) {
    var url: String = ""
    var title: String = ""
    var progress: Int = 100
    var canGoBack = false
    var canGoForward = false
    var uboAction: WebExtension.Action? = null
    var extensionsAttached = false
    val crashTimes = mutableListOf<Long>()

    /** Media seen on the current page, in detection order. */
    val media = LinkedHashMap<String, MediaItem>()

    val label: String get() = title.ifBlank { url.ifBlank { "New tab" } }
}
