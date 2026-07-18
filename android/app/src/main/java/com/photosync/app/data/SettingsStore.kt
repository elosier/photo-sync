package com.photosync.app.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Encrypted, on-device storage for the server URL, bearer token and the
 * per-device folder name (e.g. "myphone-galaxys25ultra").
 *
 * The token never leaves the device except as a Bearer header over HTTPS.
 */
class SettingsStore(context: Context) {

    private val prefs: SharedPreferences

    init {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        prefs = EncryptedSharedPreferences.create(
            context,
            "photosync_secure",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    /** Base URL of the receiver, e.g. https://your-domain.duckdns.org:8443/photos */
    var serverBaseUrl: String
        get() = prefs.getString(KEY_URL, "")!!
        set(value) = prefs.edit().putString(KEY_URL, value.trim().trimEnd('/')).apply()

    var token: String
        get() = prefs.getString(KEY_TOKEN, "")!!
        set(value) = prefs.edit().putString(KEY_TOKEN, value.trim()).apply()

    /** Folder name on the server: phonename-phonemodel, lowercase. */
    var deviceFolder: String
        get() = prefs.getString(KEY_DEVICE, "")!!
        set(value) = prefs.edit().putString(KEY_DEVICE, sanitizeDevice(value)).apply()

    var syncEnabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_ENABLED, value).apply()

    /** Only upload while on unmetered (Wi-Fi) networks when true. */
    var wifiOnly: Boolean
        get() = prefs.getBoolean(KEY_WIFI_ONLY, true)
        set(value) = prefs.edit().putBoolean(KEY_WIFI_ONLY, value).apply()

    /** Only sync the camera roll (DCIM, minus screenshots) when true;
     *  otherwise sync every image/video visible in MediaStore. */
    var cameraOnly: Boolean
        get() = prefs.getBoolean(KEY_CAMERA_ONLY, true)
        set(value) = prefs.edit().putBoolean(KEY_CAMERA_ONLY, value).apply()

    var lastRunMillis: Long
        get() = prefs.getLong(KEY_LAST_RUN, 0L)
        set(value) = prefs.edit().putLong(KEY_LAST_RUN, value).apply()

    var lastResult: String
        get() = prefs.getString(KEY_LAST_RESULT, "")!!
        set(value) = prefs.edit().putString(KEY_LAST_RESULT, value).apply()

    fun isConfigured(): Boolean =
        serverBaseUrl.isNotEmpty() && token.isNotEmpty() && deviceFolder.isNotEmpty()

    companion object {
        private const val KEY_URL = "server_url"
        private const val KEY_TOKEN = "token"
        private const val KEY_DEVICE = "device_folder"
        private const val KEY_ENABLED = "sync_enabled"
        private const val KEY_WIFI_ONLY = "wifi_only"
        private const val KEY_CAMERA_ONLY = "camera_only"
        private const val KEY_LAST_RUN = "last_run"
        private const val KEY_LAST_RESULT = "last_result"

        /** Lowercase and keep only a-z0-9._- to match the server's validation. */
        fun sanitizeDevice(raw: String): String =
            raw.trim().lowercase().replace(Regex("[^a-z0-9._-]"), "")
    }
}
