package com.nous.aurora.util

import java.util.Calendar

object EasterEgg {
    data class Theme(
        val buttonColor: Int,
        val isGlitch: Boolean = false,
        val forceLayout: String? = null,    // "default" / "bottom" / null (no override)
        val forceLanguage: String? = null,  // language code / null (no override)
        val forceFocusScheme: Int? = null   // 0=paragraph, 1=sentence / null
    )

    fun getTheme(): Theme {
        val cal = Calendar.getInstance()
        val month = cal.get(Calendar.MONTH) + 1
        val day = cal.get(Calendar.DAY_OF_MONTH)

        return when {
            // Feb 28: pink theme, force bottom layout
            month == 2 && day == 28 -> Theme(
                buttonColor = 0xFFF8A8CE.toInt(),
                forceLayout = "bottom",
                forceFocusScheme = 1  // sentence mode
            )
            // Sep 16: blood red, glitch, force sentence focus
            month == 9 && day == 16 -> Theme(
                buttonColor = 0xFFCC0000.toInt(),
                isGlitch = true,
                forceLayout = "bottom",
                forceLanguage = "lzh"  // 文言文
            )
            else -> Theme(
                buttonColor = 0xFF1B5E20.toInt()
            )
        }
    }

    /**
     * Called when user changes a setting that the Easter egg also controls.
     * Currently a no-op placeholder — Easter egg always wins on date match.
     */
    fun onSettingChanged() {}
}
