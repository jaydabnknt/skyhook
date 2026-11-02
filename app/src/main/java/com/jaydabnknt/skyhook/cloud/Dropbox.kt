package com.jaydabnknt.skyhook.cloud

import android.content.Context
import android.net.Uri
import android.util.Base64
import com.jaydabnknt.skyhook.Prefs
import com.jaydabnknt.skyhook.SkyhookApp
import com.jaydabnknt.skyhook.download.Http
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Dropbox: OAuth 2 PKCE (app key only, no secret) with a refresh token, and
 * upload sessions (start / append_v2 / finish) fed from an 8 MiB memory buffer.
 */
object Dropbox {
    const val REDIRECT = "http://localhost/skyhook-auth"
    private const val CHUNK = 8 * 1024 * 1024

    private val prefs get() = SkyhookApp.instance.getSharedPreferences("dropbox", Context.MODE_PRIVATE)

    fun isConnected() = prefs.getString("refresh", null) != null

    fun accountLabel(): String? = prefs.getString("account", null)

    fun disconnect() {
        val t = prefs.getString("access", null)
        prefs.edit().clear().apply()
        if (t != null) Thread {
            runCatching {
                Http.client.newCall(Request.Builder().url("https://api.dropboxapi.com/2/auth/token/revoke")
                    .header("Authorization", "Bearer $t").post(ByteArray(0).toRequestBody(null)).build()).execute().close()
            }
        }.start()
    }

    // ------------------------------------------------------------------ auth

    private var verifier: String? = null

    fun authUrl(): String {
        val key = Prefs.dropboxAppKey
        require(key.isNotBlank()) { "Add your Dropbox app key in Settings first" }
        val bytes = ByteArray(48).also { SecureRandom().nextBytes(it) }
        val v = Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
        verifier = v
        val challenge = Base64.encodeToString(
            MessageDigest.getInstance("SHA-256").digest(v.toByteArray()),
            Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP
        )
        return Uri.parse("https://www.dropbox.com/oauth2/authorize").buildUpon()
            .appendQueryParameter("client_id", key)
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("code_challenge", challenge)
            .appendQueryParameter("code_challenge_method", "S256")
            .appendQueryParameter("token_access_type", "offline")
            .appendQueryParameter("redirect_uri", REDIRECT)
            .build().toString()
    }

    /** Blocking: exchanges the redirect's ?code= for tokens. */
    fun completeAuth(redirectUri: String) {
        val uri = Uri.parse(redirectUri)
        uri.getQueryParameter("error")?.let { throw IOException(uri.getQueryParameter("error_description") ?: it) }
        val code = uri.getQueryParameter("code") ?: throw IOException("Dropbox returned no code")
        val body = FormBody.Builder()
            .add("code", code)
            .add("grant_type", "authorization_code")
            .add("code_verifier", verifier ?: throw IOException("Sign-in expired, try again"))
            .add("client_id", Prefs.dropboxAppKey)
            .add("redirect_uri", REDIRECT)
            .build()
        val json = Http.client.newCall(Request.Builder().url("https://api.dropboxapi.com/oauth2/token").post(body).build())
            .execute().use { r ->
                val s = r.body!!.string()
                if (!r.isSuccessful) throw IOException("Dropbox token error: ${s.take(200)}")
                JSONObject(s)
            }
        saveTokens(json)
        prefs.edit().putString("refresh", json.getString("refresh_token")).apply()
        runCatching {
            val acct = Http.client.newCall(Request.Builder().url("https://api.dropboxapi.com/2/users/get_current_account")
                .header("Authorization", "Bearer ${json.getString("access_token")}")
                .post("null".toRequestBody("application/json".toMediaType())).build()).execute().use { JSONObject(it.body!!.string()) }
            prefs.edit().putString("account", acct.optString("email")).apply()
        }
    }

    private fun saveTokens(json: JSONObject) {
        prefs.edit()
            .putString("access", json.getString("access_token"))
            .putLong("expires", System.currentTimeMillis() + json.optLong("expires_in", 14400) * 1000)
            .apply()
    }

    @Synchronized
    fun token(): String {
        val access = prefs.getString("access", null)
        if (access != null && System.currentTimeMillis() < prefs.getLong("expires", 0) - 120_000) return access
        val refresh = prefs.getString("refresh", null) ?: throw IOException("Dropbox isn't connected")
        val body = FormBody.Builder()
            .add("grant_type", "refresh_token")
            .add("refresh_token", refresh)
            .add("client_id", Prefs.dropboxAppKey)
            .build()
        val json = Http.client.newCall(Request.Builder().url("https://api.dropboxapi.com/oauth2/token").post(body).build())
            .execute().use { r ->
                val s = r.body!!.string()
                if (!r.isSuccessful) throw IOException("Dropbox sign-in expired — reconnect in Settings")
                JSONObject(s)
            }
        saveTokens(json)
        return json.getString("access_token")
    }

    // ---------------------------------------------------------------- upload

    fun openSink(folder: String, fileName: String, mime: String, size: Long?): UploadSink = DropboxSink(folder, fileName)

    /** Dropbox-API-Arg must be ASCII; escape everything else as \\uXXXX. */
    private fun apiArg(o: JSONObject): String = buildString {
        for (c in o.toString()) if (c.code < 0x7f) append(c) else append("\\u%04x".format(c.code))
    }

    private fun call(endpoint: String, arg: JSONObject, data: ByteArray?, len: Int): JSONObject {
        var attempt = 0
        while (true) {
            try {
                val req = Request.Builder().url("https://content.dropboxapi.com/2/files/$endpoint")
                    .header("Authorization", "Bearer ${token()}")
                    .header("Dropbox-API-Arg", apiArg(arg))
                    .post((data ?: ByteArray(0)).toRequestBody("application/octet-stream".toMediaType(), 0, if (data == null) 0 else len))
                    .build()
                Http.client.newCall(req).execute().use { r ->
                    val s = r.body?.string().orEmpty()
                    if (r.isSuccessful) return if (s.isBlank() || s == "null") JSONObject() else JSONObject(s)
                    if (r.code == 401) prefs.edit().putLong("expires", 0).apply()
                    if (r.code == 409) throw DropboxConflict(s)
                    if (r.code in 400..499 && r.code != 401 && r.code != 429) throw IOException("Dropbox: ${s.take(200)}")
                    throw IOException("Dropbox HTTP ${r.code}")
                }
            } catch (e: DropboxConflict) {
                throw e
            } catch (e: IOException) {
                if (e.message?.startsWith("Dropbox:") == true || ++attempt > 5) throw e
                Thread.sleep(1000L * attempt)
            }
        }
    }

    private class DropboxConflict(val body: String) : IOException("Dropbox: ${body.take(200)}")

    private class DropboxSink(private val folder: String, fileName: String) : ChunkedSink(CHUNK) {
        private var sessionId: String? = null
        private val path = "/${folder.trim('/')}/$fileName"

        private fun commit() = JSONObject().put("path", path).put("mode", "add").put("autorename", true).put("mute", false)

        override fun upload(data: ByteArray, len: Int, offset: Long, last: Boolean) {
            val sid = sessionId
            if (sid == null) {
                if (last) { // whole file fits in one chunk
                    call("upload", commit(), data, len)
                    return
                }
                sessionId = call("upload_session/start", JSONObject().put("close", false), data, len).getString("session_id")
                return
            }
            val cursor = JSONObject().put("session_id", sid).put("offset", offset)
            if (last) {
                call("upload_session/finish", JSONObject().put("cursor", cursor).put("commit", commit()), data, len)
            } else {
                try {
                    call("upload_session/append_v2", JSONObject().put("cursor", cursor).put("close", false), data, len)
                } catch (e: DropboxConflict) {
                    // A retried append may already have landed; Dropbox tells us the real offset.
                    val correct = Regex("\"correct_offset\"\\s*:\\s*(\\d+)").find(e.body)
                        ?.groupValues?.get(1)?.toLongOrNull() ?: -1L
                    if (correct != offset + len) throw e
                }
            }
        }

        override fun abort() {}

        override fun location() = "Dropbox/${folder.trim('/')}"
    }
}
