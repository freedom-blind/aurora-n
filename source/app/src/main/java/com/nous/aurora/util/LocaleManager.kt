package com.nous.aurora.util

import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import java.util.Locale

object LocaleManager {
    private const val PREFS_NAME = "locale_prefs"
    private const val KEY_LANGUAGE = "app_language"

    // Language codes: "zh-CN", "zh-TW", "ja", "en", "it", "no", "lzh"
    data class Language(val code: String, val displayName: String, val locale: Locale)

    val languages = listOf(
        Language("zh-CN", "简体中文", Locale.SIMPLIFIED_CHINESE),
        Language("zh-TW", "繁體中文", Locale.TAIWAN),
        Language("ja", "日本語", Locale.JAPANESE),
        Language("en", "English", Locale.US),
        Language("it", "Italiano", Locale.ITALIAN),
        Language("no", "Norsk", Locale("no")),
        Language("lzh", "文言文", Locale("lzh")),
    )

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getCurrentLanguage(context: Context): Language {
        val code = prefs(context).getString(KEY_LANGUAGE, "zh-CN") ?: "zh-CN"
        return languages.find { it.code == code } ?: languages[0]
    }

    fun setLanguage(context: Context, code: String) {
        prefs(context).edit().putString(KEY_LANGUAGE, code).apply()
    }

    fun applyLocale(context: Context): Context {
        val lang = getCurrentLanguage(context)
        val locale = lang.locale
        Locale.setDefault(locale)
        val config = Configuration(context.resources.configuration)
        config.setLocale(locale)
        return context.createConfigurationContext(config)
    }

    /**
     * Easter egg override: force a specific language by code.
     * Returns true if the language was changed.
     */
    fun applyEasterEggLanguage(context: Context, targetCode: String): Boolean {
        val current = getCurrentLanguage(context)
        if (current.code != targetCode) {
            setLanguage(context, targetCode)
            return true
        }
        return false
    }
}
