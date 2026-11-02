package com.jaydabnknt.skyhook.cloud

import android.content.Context
import android.net.Uri
import com.jaydabnknt.skyhook.Prefs
import com.jaydabnknt.skyhook.SkyhookApp
import com.jaydabnknt.skyhook.download.Http
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import okio.Pipe
import okio.buffer
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.atomic.AtomicReference

/**
 * Yandex Disk: OAuth implicit flow (client ID only) and a single streaming PUT to
 * the upload URL the REST API hands out. Bytes flow through an in-memory pipe —
 * nothing is written to the phone.
 */
object Yandex {
    const val REDIRECT = "https://oauth.yandex.ru/verification_code"
    private const val API = "https://cloud-api.yandex.net/v1/disk"

    private val prefs get() = SkyhookApp.instance.getSharedPreferences("yandex", Context.MODE_PRIVATE)

    fun isConnected() = prefs.getString("token", null) != null &&
        System.currentTimeMillis() < prefs.getLong("expires", Long.MAX_VALUE)

    fun accountLabel(): String? = prefs.getString("account", null)

    fun disconnect() = prefs.edit().clear().apply()

    fun authUrl(): String {
        val id = Prefs.yandexClientId
        require(id.isNotBlank()) { "Add your Yandex client ID in Settings first" }
        return Uri.parse("https://oauth.yandex.ru/authorize").buildUpon()
            .appendQueryParameter("response_type", "token")
            .appendQueryParameter("client_id", id)
            .appendQueryParameter("redirect_uri", REDIRECT)
            .appendQueryParameter("force_confirm", "yes")
            .build().toString()
    }

    fun isRedirect(uri: String) = uri.startsWith(REDIRECT) && (uri.contains("access_token=") || uri.contains("error="))

    /** Blocking: reads #access_token=… from the redirect URL. */
    fun completeAuth(redirectUri: String) {
        val frag = Uri.parse(redirectUri).fragment ?: redirectUri.substringAfter('#', "")
        val params = frag.split('&').mapNotNull { p -> p.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0] to Uri.decode(it[1]) } }.toMap()
        params["error"]?.let { throw IOException(params["error_description"] ?: it) }
        val token = params["access_token"] ?: throw IOException("Yandex returned no token")
        val expiresIn = params["expires_in"]?.toLongOrNull()
        prefs.edit()
            .putString("token", token)
            .putLong("expires", if (expiresIn != null) System.currentTimeMillis() + expiresIn * 1000 else Long.MAX_VALUE)
            .apply()
        runCatching {
            val info = get("$API/").use { JSONObject(it.body!!.string()) }
            prefs.edit().putString("account", info.optJSONObject("user")?.optString("display_name")).apply()
        }
    }

    private fun token() = prefs.getString("token", null) ?: throw IOException("Yandex Disk isn't connected")

    private fun get(url: String) = Http.client.newCall(
        Request.Builder().url(url).header("Authorization", "OAuth ${token()}").build()
    ).execute()

    private fun ensureFolder(folder: String) {
        val url = "$API/resources?path=${Uri.encode("disk:/$folder")}"
        Http.client.newCall(
            Request.Builder().url(url).header("Authorization", "OAuth ${token()}").put(ByteArray(0).toRequestBody(null)).build()
        ).execute().use { r ->
            if (!r.isSuccessful && r.code != 409) throw IOException("Yandex folder create failed: HTTP ${r.code}")
        }
    }

    /** Asks for an upload URL, picking "name (2).ext" etc. if the name is taken. */
    private fun uploadHref(folder: String, fileName: String): Pair<String, String> {
        val dot = fileName.lastIndexOf('.').takeIf { it > 0 } ?: fileName.length
        for (i in 1..50) {
            val name = if (i == 1) fileName else "${fileName.substring(0, dot)} ($i)${fileName.substring(dot)}"
            val r = get("$API/resources/upload?path=${Uri.encode("disk:/$folder/$name")}&overwrite=false")
            r.use {
                val s = it.body!!.string()
                if (it.isSuccessful) return JSONObject(s).getString("href") to name
                if (it.code == 401) throw IOException("Yandex sign-in expired — reconnect in Settings")
                if (it.code != 409) throw IOException("Yandex upload URL failed: HTTP ${it.code} ${s.take(200)}")
            }
        }
        throw IOException("Too many files with that name")
    }

    fun openSink(folder: String, fileName: String, mime: String, size: Long?): UploadSink {
        ensureFolder(folder)
        val (href, finalName) = uploadHref(folder, fileName)
        return YandexSink(href, "Yandex Disk/$folder/$finalName", mime)
    }

    private class YandexSink(href: String, private val where: String, mime: String) : UploadSink {
        private val pipe = Pipe(4L * 1024 * 1024)
        private val out: BufferedSink = pipe.sink.buffer()
        private val error = AtomicReference<Throwable?>(null)
        private val call: okhttp3.Call
        private val thread: Thread
        override var committed = 0L
            private set

        init {
            val body = object : RequestBody() {
                override fun contentType(): MediaType? = mime.toMediaTypeOrNull()
                override fun contentLength() = -1L // chunked transfer encoding
                override fun isOneShot() = true   // the pipe can only be read once
                override fun writeTo(sink: BufferedSink) {
                    sink.writeAll(pipe.source)
                }
            }
            call = Http.client.newCall(Request.Builder().url(href).put(body).build())
            thread = Thread {
                try {
                    call.execute().use { r ->
                        if (r.code != 201 && r.code != 202 && r.code != 200) {
                            throw IOException("Yandex upload failed: HTTP ${r.code}")
                        }
                    }
                } catch (t: Throwable) {
                    error.set(t)
                    runCatching { pipe.source.close() }
                }
            }.apply { name = "yandex-upload"; start() }
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            error.get()?.let { throw IOException(it.message, it) }
            try {
                out.write(b, off, len)
                committed += len
            } catch (e: IOException) {
                throw IOException(error.get()?.message ?: e.message, e)
            }
        }

        override fun finish(): String {
            out.close()
            thread.join()
            error.get()?.let { throw IOException(it.message, it) }
            return where
        }

        override fun abort() {
            call.cancel()
            runCatching { out.close() }
            runCatching { pipe.source.close() }
        }
    }
}
