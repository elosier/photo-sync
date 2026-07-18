package com.photosync.app.data

import android.content.Context
import android.os.Build
import android.provider.Settings

/**
 * Best-effort auto-detection of the server folder name, in the project's
 * `phonename-phonemodel` shape (all lowercase, e.g. `myphone-galaxys25ultra`).
 *
 * - **phonename** comes from the system Device name (Settings ▸ About phone ▸
 *   Device name), which the user can set to whatever they like (e.g. "myphone").
 * - **phonemodel** comes from the OEM marketing name where it's exposed via a
 *   system property (Samsung/Xiaomi/etc. often set "Galaxy S25 Ultra"); otherwise
 *   it falls back to [Build.MODEL] (e.g. "SM-S938B"), which the user can tweak.
 *
 * The result is always run through [SettingsStore.sanitizeDevice] so it matches
 * what the server accepts (spaces stripped, lowercased).
 */
object DeviceInfo {

    fun suggestedFolder(context: Context): String {
        val name = deviceName(context)
        val model = marketingModel()
        val combined = listOf(name, model).filter { it.isNotBlank() }.joinToString("-")
        return SettingsStore.sanitizeDevice(combined)
    }

    private fun deviceName(context: Context): String {
        val fromSettings = runCatching {
            Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME)
        }.getOrNull()
        return fromSettings?.trim().orEmpty()
    }

    private fun marketingModel(): String {
        // 1. OEM-provided marketing name via system property (works on many
        //    Xiaomi/OnePlus/etc.; Pixels already put a friendly name in MODEL).
        for (key in listOf(
            "ro.product.marketname",
            "ro.vendor.product.marketname",
            "ro.vendor.oplus.market.name",   // OnePlus / Oppo
            "ro.config.marketing_name",
        )) {
            val v = systemProperty(key)
            if (!v.isNullOrBlank()) return v.trim()
        }
        // 2. Some OEMs report MODEL as a code (Samsung "SM-S938W", OnePlus
        //    "CPH2515"); map known codes to marketing names.
        samsungMarketingName(Build.MODEL)?.let { return it }
        OTHER_MODELS[Build.MODEL?.uppercase()?.trim()]?.let { return it }
        // 3. Fall back to the raw model code (user can edit it).
        return Build.MODEL.orEmpty()
    }

    /** Map a Samsung model code to its marketing name, ignoring the region/
     *  variant suffix (e.g. "SM-S938W" and "SM-S938B" both -> the S25 Ultra).
     *  Returns null for unknown/non-Samsung models. Extend as needed. */
    private fun samsungMarketingName(model: String?): String? {
        val m = model?.uppercase()?.trim() ?: return null
        val base = Regex("^(SM-[A-Z]+[0-9]{3})").find(m)?.groupValues?.get(1) ?: return null
        return SAMSUNG_MODELS[base]
    }

    private val SAMSUNG_MODELS = mapOf(
        // Galaxy S25 (2025)
        "SM-S931" to "Galaxy S25",
        "SM-S936" to "Galaxy S25+",
        "SM-S937" to "Galaxy S25 Edge",
        "SM-S938" to "Galaxy S25 Ultra",
        // Galaxy S24 (2024)
        "SM-S721" to "Galaxy S24 FE",
        "SM-S921" to "Galaxy S24",
        "SM-S926" to "Galaxy S24+",
        "SM-S928" to "Galaxy S24 Ultra",
        // Galaxy S23 (2023)
        "SM-S711" to "Galaxy S23 FE",
        "SM-S911" to "Galaxy S23",
        "SM-S916" to "Galaxy S23+",
        "SM-S918" to "Galaxy S23 Ultra",
        // Foldables
        "SM-F731" to "Galaxy Z Flip5",
        "SM-F946" to "Galaxy Z Fold5",
        "SM-F741" to "Galaxy Z Flip6",
        "SM-F956" to "Galaxy Z Fold6",
        "SM-F766" to "Galaxy Z Flip7",
        "SM-F966" to "Galaxy Z Fold7",
        // A-series (common)
        "SM-A356" to "Galaxy A35",
        "SM-A556" to "Galaxy A55",
    )

    /** Exact-code map for non-Samsung OEMs whose marketname property is
     *  unavailable. Extend as new family phones join the sync. */
    private val OTHER_MODELS = mapOf(
        "CPH2515" to "OnePlus Nord N30 5G",
    )

    /** Read a read-only system property via reflection; returns null if the
     *  hidden API is unavailable/blocked (then we fall back to Build.MODEL). */
    private fun systemProperty(key: String): String? = runCatching {
        @Suppress("PrivateApi")
        val cls = Class.forName("android.os.SystemProperties")
        val get = cls.getMethod("get", String::class.java)
        get.invoke(null, key) as? String
    }.getOrNull()
}
