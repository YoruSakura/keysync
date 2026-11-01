package com.devoid.keysync.model

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class AppConfigLocalizationTest {

    @Test
    fun `configuration saved before language support follows the system`() {
        val legacyConfig = """
            {
              "shootingModeKeyCode": 1002,
              "fireKeyCode": 1001,
              "scopeKeyCode": 1003,
              "buttonScale": 1.0
            }
        """.trimIndent()

        val config = Json.decodeFromString<AppConfig>(legacyConfig)

        assertEquals(LanguagePreference.SYSTEM, config.languagePreference)
        assertEquals(InputMode.ROOT, config.inputMode)
        assertEquals(TouchMode.HOLD, config.normalBtnTouchMode)
    }

    @Test
    fun `Shizuku mode survives configuration serialization`() {
        val saved = AppConfig.Default.copy(inputMode = InputMode.SHIZUKU)

        val restored = Json.decodeFromString<AppConfig>(Json.encodeToString(saved))

        assertEquals(InputMode.SHIZUKU, restored.inputMode)
    }

    @Test
    fun `language preferences use Android locale tags`() {
        assertEquals("", LanguagePreference.SYSTEM.languageTag)
        assertEquals("zh-CN", LanguagePreference.SIMPLIFIED_CHINESE.languageTag)
        assertEquals("en", LanguagePreference.ENGLISH.languageTag)
    }
}
