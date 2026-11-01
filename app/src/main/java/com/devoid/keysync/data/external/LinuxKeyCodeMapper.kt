package com.devoid.keysync.data.external

import android.util.Log
import android.view.KeyEvent
import java.io.File

/** Converts Linux evdev scan codes using Android's own Generic.kl mapping. */
internal object LinuxKeyCodeMapper {
    private val keyMap: Map<Int, Int> by lazy { loadGenericKeyLayout() }

    fun toAndroidKeyCode(scanCode: Int): Int? = keyMap[scanCode]

    private fun loadGenericKeyLayout(): Map<Int, Int> {
        val keyLayout = GENERIC_KEY_LAYOUTS
            .asSequence()
            .map(::File)
            .firstOrNull(File::isFile)
        if (keyLayout == null) {
            Log.e(TAG, "Android Generic.kl was not found")
            return FALLBACK_KEYS
        }

        val parsed = runCatching {
            keyLayout.useLines { lines ->
                lines.mapNotNull(::parseKeyLine).toMap()
            }
        }.onFailure {
            Log.e(TAG, "Unable to parse ${keyLayout.path}", it)
        }.getOrDefault(emptyMap())

        return FALLBACK_KEYS + parsed
    }

    private fun parseKeyLine(line: String): Pair<Int, Int>? {
        val match = KEY_LINE.matchEntire(line.substringBefore('#')) ?: return null
        val scanCodeText = match.groupValues[1]
        val scanCode = if (scanCodeText.startsWith("0x", ignoreCase = true)) {
            scanCodeText.substring(2).toIntOrNull(16)
        } else {
            scanCodeText.toIntOrNull()
        } ?: return null
        val androidKeyCode = KeyEvent.keyCodeFromString("KEYCODE_${match.groupValues[2]}")
        return scanCode.takeIf { androidKeyCode != KeyEvent.KEYCODE_UNKNOWN }
            ?.let { it to androidKeyCode }
    }

    private val KEY_LINE = Regex("""\s*key\s+(0x[0-9a-fA-F]+|\d+)\s+([A-Z0-9_]+).*""")
    private val GENERIC_KEY_LAYOUTS = listOf(
        "/system/usr/keylayout/Generic.kl",
        "/product/usr/keylayout/Generic.kl",
        "/vendor/usr/keylayout/Generic.kl"
    )
    private val FALLBACK_KEYS = mapOf(
        1 to KeyEvent.KEYCODE_ESCAPE,
        2 to KeyEvent.KEYCODE_1,
        3 to KeyEvent.KEYCODE_2,
        4 to KeyEvent.KEYCODE_3,
        5 to KeyEvent.KEYCODE_4,
        17 to KeyEvent.KEYCODE_W,
        30 to KeyEvent.KEYCODE_A,
        31 to KeyEvent.KEYCODE_S,
        32 to KeyEvent.KEYCODE_D
    )
    private const val TAG = "LinuxKeyCodeMapper"
}
