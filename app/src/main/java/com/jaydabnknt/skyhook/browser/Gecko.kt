package com.jaydabnknt.skyhook.browser

import android.content.Context
import android.util.Log
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings
import org.mozilla.geckoview.WebExtension

/**
 * Process-wide GeckoRuntime plus the two built-in extensions:
 *  - uBlock Origin (the real extension, bundled into assets at build time)
 *  - the Skyhook media detector
 */
object Gecko {
    private const val TAG = "Gecko"
    const val UBO_ID = "uBlock0@raymondhill.net"
    const val DETECTOR_ID = "detector@skyhook.jaydabnknt"

    lateinit var runtime: GeckoRuntime
        private set

    var ubo: WebExtension? = null
        private set
    var detector: WebExtension? = null
        private set
    var uboError: String? = null
        private set

    private val readyListeners = mutableListOf<() -> Unit>()

    fun init(context: Context) {
        if (::runtime.isInitialized) return
        val settings = GeckoRuntimeSettings.Builder()
            .consoleOutput(false)
            .aboutConfigEnabled(true)
            .build()
        runtime = GeckoRuntime.create(context.applicationContext, settings)

        val controller = runtime.webExtensionController
        controller.ensureBuiltIn("resource://android/assets/extensions/detector/", DETECTOR_ID)
            .accept({ ext ->
                detector = ext
                Log.i(TAG, "detector ready")
                notifyReady()
            }, { e -> Log.e(TAG, "detector failed", e) })

        controller.ensureBuiltIn("resource://android/assets/extensions/ubo/", UBO_ID)
            .accept({ ext ->
                ubo = ext
                Log.i(TAG, "uBlock Origin ready: ${ext?.metaData?.version}")
                notifyReady()
            }, { e ->
                uboError = e?.message ?: "unknown error"
                Log.e(TAG, "uBlock Origin failed", e)
                notifyReady()
            })
    }

    fun onReady(listener: () -> Unit) {
        readyListeners += listener
        if (detector != null || ubo != null) listener()
    }

    private fun notifyReady() = readyListeners.toList().forEach { it() }
}
