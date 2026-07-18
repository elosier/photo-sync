package com.photosync.app.net

import android.content.ContentResolver
import android.net.Uri
import com.photosync.app.media.MediaItem
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okio.BufferedSink
import okio.source
import org.json.JSONObject
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Talks to the FastAPI receiver. One instance per sync run.
 *
 * Uploads stream directly from the MediaStore content URI to the socket, so a
 * multi-gigabyte video is never held in memory.
 */
class UploadClient(
    private val baseUrl: String,
    private val token: String,
    private val resolver: ContentResolver,
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.MINUTES) // large videos
        .retryOnConnectionFailure(true)
        .build()

    sealed interface Result {
        data object Stored : Result
        data object AlreadyPresent : Result
        /** Transient failure (network/5xx): caller should retry later. */
        data class Retryable(val message: String) : Result
        /** Permanent failure (4xx other than 401/403 handled separately). */
        data class Permanent(val message: String) : Result
    }

    /** Liveness + token check. Returns null on success, else an error message.
     *
     *  Responses are content-validated, not just status-checked: captive
     *  portals and interception proxies commonly answer 200 to everything,
     *  which must NOT look like a working server. */
    fun testConnection(): String? {
        return try {
            val health = Request.Builder().url("$baseUrl/health").get().build()
            client.newCall(health).execute().use { resp ->
                if (!resp.isSuccessful) return "Server returned ${resp.code} for /health"
                val body = resp.body?.string().orEmpty()
                val ok = runCatching { JSONObject(body).optString("status") == "ok" }
                    .getOrDefault(false)
                if (!ok) {
                    return "Got a reply, but not from the photo server — " +
                        "captive portal, VPN, or wrong URL path?"
                }
            }
            // Verify the token by hitting an authenticated endpoint.
            val probe = "$baseUrl/v1/exists".toHttpUrl().newBuilder()
                .addQueryParameter("device", "connection.test")
                .addQueryParameter("reldate", "2000/2000_01_01")
                .addQueryParameter("filename", "probe")
                .build()
            val req = Request.Builder().url(probe).header("Authorization", "Bearer $token").get().build()
            client.newCall(req).execute().use { resp ->
                when (resp.code) {
                    200 -> {
                        val body = resp.body?.string().orEmpty()
                        val valid = runCatching { JSONObject(body).has("exists") }
                            .getOrDefault(false)
                        if (valid) null
                        else "Got a reply, but not from the photo server — " +
                            "captive portal, VPN, or wrong URL path?"
                    }
                    401, 403 -> "Token rejected by server (${resp.code})"
                    else -> "Unexpected response ${resp.code} from /v1/exists"
                }
            }
        } catch (e: IOException) {
            "Cannot reach server: ${e.message}"
        }
    }

    fun computeSha256(uri: Uri): String? {
        val digest = MessageDigest.getInstance("SHA-256")
        return try {
            resolver.openInputStream(uri)?.use { input ->
                val buf = ByteArray(1 shl 16)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    digest.update(buf, 0, n)
                }
            } ?: return null
            digest.digest().joinToString("") { "%02x".format(it) }
        } catch (e: IOException) {
            null
        }
    }

    fun exists(device: String, item: MediaItem, sha256: String): Boolean? {
        val url = "$baseUrl/v1/exists".toHttpUrl().newBuilder()
            .addQueryParameter("device", device)
            .addQueryParameter("reldate", item.relativeDatePath())
            .addQueryParameter("filename", item.displayName)
            .addQueryParameter("sha256", sha256)
            .build()
        val req = Request.Builder().url(url).header("Authorization", "Bearer $token").get().build()
        return try {
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val text = resp.body?.string().orEmpty()
                val json = runCatching { JSONObject(text) }.getOrNull() ?: return null
                // A response without the expected field is NOT our server
                // (interception proxy) -> unknown, caller falls through to
                // upload, whose own validation is authoritative.
                if (!json.has("exists")) return null
                json.optBoolean("exists", false)
            }
        } catch (e: IOException) {
            null
        }
    }

    fun upload(device: String, item: MediaItem, sha256: String): Result {
        val body = object : RequestBody() {
            override fun contentType() = "application/octet-stream".toMediaTypeOrNull()
            override fun contentLength() = item.sizeBytes
            override fun writeTo(sink: BufferedSink) {
                val input = resolver.openInputStream(item.uri)
                    ?: throw IOException("cannot open ${item.uri}")
                input.source().use { source -> sink.writeAll(source) }
            }
        }

        val req = Request.Builder()
            .url("$baseUrl/v1/upload")
            .header("Authorization", "Bearer $token")
            .header("X-Device", device)
            .header("X-Capture-Date", item.relativeDatePath())
            // Percent-encode so non-ASCII filenames are legal in an HTTP header;
            // the server decodes with urllib unquote.
            .header("X-Filename", Uri.encode(item.displayName))
            .header("X-Sha256", sha256)
            .header("X-File-Size", item.sizeBytes.toString())
            .post(body)
            .build()

        return try {
            client.newCall(req).execute().use { resp ->
                when {
                    resp.isSuccessful -> {
                        // Never trust a bare 2xx: captive portals and proxies
                        // answer 200 to anything. Only our server can echo the
                        // SHA-256 of the bytes we just sent — require it.
                        val text = resp.body?.string().orEmpty()
                        val json = runCatching { JSONObject(text) }.getOrNull()
                        val status = json?.optString("status")
                        val echoed = json?.optString("sha256")?.lowercase()
                        when {
                            json == null || (status != "stored" && status != "exists") ->
                                Result.Retryable(
                                    "unverified reply (captive portal / proxy in the way?)"
                                )
                            echoed != sha256.lowercase() ->
                                Result.Retryable("response checksum mismatch")
                            status == "exists" -> Result.AlreadyPresent
                            else -> Result.Stored
                        }
                    }
                    resp.code == 401 || resp.code == 403 ->
                        Result.Permanent("auth rejected (${resp.code})")
                    resp.code == 422 -> Result.Retryable("checksum mismatch, will retry")
                    resp.code in 400..499 -> Result.Permanent("client error ${resp.code}")
                    else -> Result.Retryable("server error ${resp.code}")
                }
            }
        } catch (e: IOException) {
            Result.Retryable(e.message ?: "network error")
        }
    }
}
