package com.jaydabnknt.skyhook.cloud

import android.content.Context
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Tasks
import com.jaydabnknt.skyhook.SkyhookApp
import com.jaydabnknt.skyhook.download.Http
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Google Drive via the Android AuthorizationClient (no client secret in the app;
 * Google matches the app by package name + signing-key SHA-1) and the Drive v3
 * resumable upload protocol, which accepts chunks without knowing the total size.
 */
object GoogleDrive {
    const val SCOPE = "https://www.googleapis.com/auth/drive.file"
    private const val CHUNK = 8 * 1024 * 1024 // must be a multiple of 256 KiB

    private val prefs get() = SkyhookApp.instance.getSharedPreferences("google", Context.MODE_PRIVATE)
    @Volatile private var cachedToken: String? = null
    @Volatile private var cachedAt = 0L

    fun isConnected() = prefs.getBoolean("connected", false)

    fun authRequest(): AuthorizationRequest =
        AuthorizationRequest.builder().setRequestedScopes(listOf(Scope(SCOPE))).build()

    /** Called by Settings once the user has approved access. */
    fun onAuthorized(result: AuthorizationResult) {
        cachedToken = result.accessToken
        cachedAt = System.currentTimeMillis()
        prefs.edit().putBoolean("connected", cachedToken != null).apply()
    }

    fun disconnect() {
        val t = cachedToken
        cachedToken = null
        prefs.edit().clear().apply()
        if (t != null) Thread {
            runCatching {
                Http.client.newCall(
                    Request.Builder().url("https://oauth2.googleapis.com/revoke?token=$t")
                        .post(ByteArray(0).toRequestBody(null)).build()
                ).execute().close()
            }
        }.start()
    }

    /** Blocking. Silently gets a fresh access token once the user has granted access. */
    @Synchronized
    fun token(forceRefresh: Boolean = false): String {
        val now = System.currentTimeMillis()
        cachedToken?.let { if (!forceRefresh && now - cachedAt < 40 * 60_000) return it }
        val client = Identity.getAuthorizationClient(SkyhookApp.instance)
        val result = Tasks.await(client.authorize(authRequest()), 60, TimeUnit.SECONDS)
        if (result.hasResolution() || result.accessToken == null) {
            prefs.edit().putBoolean("connected", false).apply()
            throw IOException("Google Drive needs you to sign in again (Settings → Google Drive)")
        }
        cachedToken = result.accessToken
        cachedAt = now
        return result.accessToken!!
    }

    private fun authed(b: Request.Builder) = b.header("Authorization", "Bearer ${token()}")

    /** Finds (or creates) the app's folder in My Drive. drive.file scope only sees files this app made. */
    @Synchronized
    private fun folderId(name: String): String {
        prefs.getString("folder:$name", null)?.let { id ->
            // Make sure it still exists and isn't trashed.
            val ok = runCatching {
                Http.client.newCall(authed(Request.Builder().url(
                    "https://www.googleapis.com/drive/v3/files/$id?fields=id,trashed"
                )).build()).execute().use { r ->
                    r.isSuccessful && !JSONObject(r.body!!.string()).optBoolean("trashed")
                }
            }.getOrDefault(false)
            if (ok) return id
        }
        val q = "name = '${name.replace("'", "\\'")}' and mimeType = 'application/vnd.google-apps.folder' and trashed = false"
        val found = Http.client.newCall(authed(Request.Builder().url(
            "https://www.googleapis.com/drive/v3/files?q=${URLEncoder.encode(q, "UTF-8")}&fields=files(id)"
        )).build()).execute().use { r ->
            if (!r.isSuccessful) throw IOException("Drive folder lookup failed: HTTP ${r.code}")
            JSONObject(r.body!!.string()).optJSONArray("files")?.optJSONObject(0)?.optString("id")
        }
        val id = found ?: Http.client.newCall(authed(Request.Builder().url("https://www.googleapis.com/drive/v3/files?fields=id"))
            .post(JSONObject().put("name", name).put("mimeType", "application/vnd.google-apps.folder")
                .toString().toRequestBody("application/json; charset=UTF-8".toMediaType()))
            .build()).execute().use { r ->
            if (!r.isSuccessful) throw IOException("Could not create Drive folder: HTTP ${r.code}")
            JSONObject(r.body!!.string()).getString("id")
        }
        prefs.edit().putString("folder:$name", id).apply()
        return id
    }

    fun openSink(folder: String, fileName: String, mime: String, size: Long?): UploadSink =
        DriveSink(folder, fileName, mime.ifBlank { "application/octet-stream" }, size)

    private class DriveSink(private val folder: String, fileName: String, private val mime: String, size: Long?) : ChunkedSink(CHUNK) {
        private val sessionUrl: String

        init {
            val meta = JSONObject().put("name", fileName).put("parents", org.json.JSONArray().put(folderId(folder)))
            val req = authed(Request.Builder().url("https://www.googleapis.com/upload/drive/v3/files?uploadType=resumable&fields=id"))
                .header("X-Upload-Content-Type", mime)
                .apply { if (size != null && size > 0) header("X-Upload-Content-Length", size.toString()) }
                .post(meta.toString().toRequestBody("application/json; charset=UTF-8".toMediaType()))
                .build()
            sessionUrl = Http.client.newCall(req).execute().use { r ->
                if (!r.isSuccessful) throw IOException("Drive upload start failed: HTTP ${r.code} ${r.body?.string()?.take(200)}")
                r.header("Location") ?: throw IOException("Drive gave no upload URL")
            }
        }

        override fun upload(data: ByteArray, len: Int, offset: Long, last: Boolean) {
            var sent = 0 // bytes of this chunk Drive has confirmed
            var attempt = 0
            while (true) {
                try {
                    val remaining = len - sent
                    val start = offset + sent
                    val total = if (last) (offset + len).toString() else "*"
                    val range = if (remaining == 0) "bytes */$total" else "bytes $start-${start + remaining - 1}/$total"
                    val req = Request.Builder().url(sessionUrl)
                        .header("Content-Range", range)
                        .put(data.toRequestBody(mime.toMediaTypeOrNull(), sent, remaining))
                        .build()
                    Http.client.newCall(req).execute().use { r ->
                        when {
                            r.code == 200 || r.code == 201 -> return
                            r.code == 308 -> {
                                val confirmed = confirmedEnd(r.header("Range")) - offset
                                if (!last && confirmed >= len) return
                                sent = confirmed.coerceIn(0, len.toLong()).toInt()
                                attempt++ // partial accept; loop to send the rest
                            }
                            r.code == 404 || r.code == 410 -> throw IOException("Drive upload session expired")
                            r.code in 400..499 && r.code != 408 && r.code != 429 ->
                                throw IOException("Drive rejected chunk: HTTP ${r.code} ${r.body?.string()?.take(200)}")
                            else -> throw IOException("Drive HTTP ${r.code}")
                        }
                    }
                } catch (e: IOException) {
                    if (e.message?.startsWith("Drive rejected") == true || e.message?.contains("expired") == true) throw e
                    attempt++
                    if (attempt > 6) throw e
                    Thread.sleep(1000L * attempt)
                    sent = (queryCommitted(last, offset + len) - offset).coerceIn(0, len.toLong()).toInt()
                }
                if (attempt > 12) throw IOException("Drive upload kept failing")
            }
        }

        /** Asks Drive how much of the file it has. Returns the absolute committed byte count. */
        private fun queryCommitted(last: Boolean, total: Long): Long = runCatching {
            Http.client.newCall(
                Request.Builder().url(sessionUrl)
                    .header("Content-Range", "bytes */${if (last) total.toString() else "*"}")
                    .put(ByteArray(0).toRequestBody(null)).build()
            ).execute().use { r -> if (r.code == 308) confirmedEnd(r.header("Range")) else committed }
        }.getOrDefault(committed)

        private fun confirmedEnd(range: String?): Long =
            range?.substringAfter('-', "")?.toLongOrNull()?.plus(1) ?: 0L

        override fun abort() {
            runCatching { Http.client.newCall(Request.Builder().url(sessionUrl).delete().build()).execute().close() }
        }

        override fun location() = "Google Drive/$folder"
    }
}
