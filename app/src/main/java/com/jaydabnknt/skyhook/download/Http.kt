package com.jaydabnknt.skyhook.download

import kotlinx.coroutines.delay
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit

object Http {
    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .retryOnConnectionFailure(true)
        .build()

    const val FALLBACK_UA =
        "Mozilla/5.0 (Android 15; Mobile; rv:140.0) Gecko/140.0 Firefox/140.0"

    fun request(url: String, headers: Map<String, String>, range: LongRange? = null, rangeFrom: Long? = null): Request {
        val b = Request.Builder().url(url)
        var hasUa = false
        for ((k, v) in headers) {
            if (k.equals("range", true) || k.equals("host", true) || k.equals("accept-encoding", true)) continue
            if (k.equals("user-agent", true)) hasUa = true
            runCatching { b.header(k, v) }
        }
        if (!hasUa) b.header("User-Agent", FALLBACK_UA)
        when {
            range != null -> b.header("Range", "bytes=${range.first}-${range.last}")
            rangeFrom != null && rangeFrom > 0 -> b.header("Range", "bytes=$rangeFrom-")
        }
        return b.build()
    }

    /** Opens a streaming response; caller must close it. Throws on HTTP errors. */
    fun open(url: String, headers: Map<String, String>, range: LongRange? = null, rangeFrom: Long? = null): Response {
        val resp = client.newCall(request(url, headers, range, rangeFrom)).execute()
        if (!resp.isSuccessful) {
            val code = resp.code
            resp.close()
            throw HttpException(code, url)
        }
        return resp
    }

    suspend fun bytes(url: String, headers: Map<String, String>, range: LongRange? = null, attempts: Int = 5): ByteArray =
        retry(attempts) { open(url, headers, range).use { it.body!!.bytes() } }

    suspend fun text(url: String, headers: Map<String, String>): Pair<String, String> =
        retry(4) { open(url, headers).use { it.request.url.toString() to it.body!!.string() } }

    suspend fun <T> retry(attempts: Int, block: () -> T): T {
        var last: Exception? = null
        repeat(attempts) { i ->
            try {
                return block()
            } catch (e: HttpException) {
                if (e.code in 400..499 && e.code != 408 && e.code != 429) throw e
                last = e
            } catch (e: IOException) {
                last = e
            }
            delay(700L * (1 shl i))
        }
        throw last ?: IOException("failed")
    }
}

class HttpException(val code: Int, url: String) : IOException("HTTP $code for ${url.take(120)}")
