package com.example.jaecoovoice.engine

import java.util.Locale

/** Which installed model answers a request from another app. */
object LanguageResolver {

    /** `vi-VN`, `vi_VN`, `VI` → `vi`; blank → null. */
    fun normalize(tag: String?): String? = tag?.trim()
        ?.substringBefore('-')
        ?.substringBefore('_')
        ?.lowercase(Locale.ROOT)
        ?.ifEmpty { null }

    /**
     * The requested language (EXTRA_LANGUAGE) if its model is installed, else the device language,
     * else any installed model: people here speak Vietnamese whatever the phone app asks for, and
     * Android 11 has no "language not supported" error to give back. Null when no model is installed.
     */
    fun forOtherApp(requested: String?, system: String, installed: Set<String>): String? {
        val wanted = listOfNotNull(normalize(requested), normalize(system))
        return wanted.firstOrNull { it in installed } ?: installed.sorted().firstOrNull()
    }
}
