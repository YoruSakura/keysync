package com.devoid.keysync.model

import com.devoid.keysync.domain.KEYCODE_LMC
import com.devoid.keysync.domain.KEYCODE_MMC
import com.devoid.keysync.domain.KEYCODE_RMC
import kotlinx.serialization.Serializable

@Serializable
data class AppConfig(
    val shootingModeKeyCode: Int,
    val fireKeyCode: Int,
    val scopeKeyCode:Int,
    val buttonScale: Float,
    val deleteDataOnRemove: Boolean = Default.deleteDataOnRemove,
    val themePreference: ThemePreference = Default.themePreference,
    val languagePreference: LanguagePreference = Default.languagePreference,
    val inputMode: InputMode = Default.inputMode,
    val cancellableTouchMode: TouchMode = Default.cancellableTouchMode,
    // Kept so configurations saved by older versions remain readable.
    // Regular buttons now always mirror the physical key press duration.
    val normalBtnTouchMode: TouchMode = Default.normalBtnTouchMode,
) {
    companion object {
        val Default = AppConfig(
            shootingModeKeyCode = KEYCODE_MMC,
            fireKeyCode = KEYCODE_LMC,
            scopeKeyCode = KEYCODE_RMC,
            buttonScale = 1f,
            deleteDataOnRemove = false,
            themePreference = ThemePreference.SYSTEM_DYNAMIC,
            languagePreference = LanguagePreference.SYSTEM,
            inputMode = InputMode.ROOT,
            cancellableTouchMode = TouchMode.TAP,
            normalBtnTouchMode = TouchMode.HOLD
        )
    }

}
