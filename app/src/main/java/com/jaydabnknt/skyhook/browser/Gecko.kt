package com.jaydabnknt.skyhook.browser

import android.content.Context
import android.util.Log
import android.net.Uri
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings
import org.mozilla.geckoview.WebExtension
import org.mozilla.geckoview.WebExtensionController

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

        // uBO is installed as a normal (AMO-signed) extension rather than a built-in one:
        // built-in mode loaded it without a working background page.
        controller.setPromptDelegate(object : WebExtensionController.PromptDelegate {
            override fun onInstallPromptRequest(
                extension: WebExtension, permissions: Array<String>, origins: Array<String>,
                dataCollectionPermissions: Array<String>,
            ): GeckoResult<WebExtension.PermissionPromptResponse> =
                GeckoResult.fromValue(WebExtension.PermissionPromptResponse(true, true, false))

            override fun onUpdatePrompt(
                extension: WebExtension, newPermissions: Array<String>, newOrigins: Array<String>,
                newDataCollectionPermissions: Array<String>,
            ): GeckoResult<AllowOrDeny> = GeckoResult.fromValue(AllowOrDeny.ALLOW)
        })
        controller.list().accept({ list ->
            val existing = list?.firstOrNull { it.id == UBO_ID }
            when {
                existing != null && !existing.isBuiltIn -> uboReady(existing)
                existing != null -> controller.uninstall(existing).accept(
                    { installUbo(context) }, { installUbo(context) })
                else -> installUbo(context)
            }
        }, { installUbo(context) })
    }

    private fun uboReady(ext: WebExtension?) {
        ubo = ext
        uboError = null
        Log.i(TAG, "uBlock Origin ready: ${ext?.metaData?.version}")
        notifyReady()
    }

    /** Installs the signed XPI bundled in the APK; falls back to downloading it from addons.mozilla.org. */
    private fun installUbo(context: Context) {
        val controller = runtime.webExtensionController
        val local = runCatching {
            val f = java.io.File(context.filesDir, "ublock-origin.xpi")
            context.assets.open("extensions/ubo.xpi").use { input -> f.outputStream().use { input.copyTo(it) } }
            Uri.fromFile(f).toString()
        }.getOrNull()
        fun fromAmo(firstError: Throwable?) {
            controller.install(AMO_UBO).accept({ uboReady(it) }, { e ->
                uboError = (firstError?.message?.let { "$it; " } ?: "") + (e?.message ?: "install failed")
                Log.e(TAG, "uBlock Origin install failed", e)
                notifyReady()
            })
        }
        if (local == null) fromAmo(null)
        else controller.install(local, WebExtensionController.INSTALLATION_METHOD_FROM_FILE)
            .accept({ uboReady(it) }, { e -> Log.w(TAG, "bundled uBO failed", e); fromAmo(e) })
    }

    private const val AMO_UBO = "https://addons.mozilla.org/firefox/downloads/latest/ublock-origin/latest.xpi"

    fun onReady(listener: () -> Unit) {
        readyListeners += listener
        if (detector != null || ubo != null) listener()
    }

    private fun notifyReady() = readyListeners.toList().forEach { it() }
}
