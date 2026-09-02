package com.moatazvid.app

import android.content.Context

class UserPreferences(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("user_preferences", Context.MODE_PRIVATE)

    var language: String
        get() = prefs.getString(KEY_LANGUAGE, LANGUAGE_ARABIC) ?: LANGUAGE_ARABIC
        set(value) {
            require(value in setOf(LANGUAGE_SYSTEM, LANGUAGE_ARABIC, LANGUAGE_ENGLISH))
            prefs.edit().putString(KEY_LANGUAGE, value).apply()
        }

    var performanceMode: String
        get() = prefs.getString(KEY_PERFORMANCE, "AUTO") ?: "AUTO"
        set(value) {
            require(value in setOf("AUTO", "BATTERY_SAVER", "BALANCED", "MAX_PERFORMANCE"))
            prefs.edit().putString(KEY_PERFORMANCE, value).apply()
        }

    var cloudTextAllowed: Boolean
        get() = prefs.getBoolean(KEY_CLOUD_TEXT, false)
        set(value) { prefs.edit().putBoolean(KEY_CLOUD_TEXT, value).apply() }

    var visionAllowed: Boolean
        get() = prefs.getBoolean(KEY_VISION, false)
        set(value) { prefs.edit().putBoolean(KEY_VISION, value).apply() }

    companion object {
        const val LANGUAGE_SYSTEM = "system"
        const val LANGUAGE_ARABIC = "ar"
        const val LANGUAGE_ENGLISH = "en"
        private const val KEY_LANGUAGE = "language"
        private const val KEY_PERFORMANCE = "performance_mode"
        private const val KEY_CLOUD_TEXT = "cloud_text_allowed"
        private const val KEY_VISION = "vision_allowed"
    }
}
