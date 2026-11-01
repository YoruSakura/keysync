package com.devoid.keysync.data.external

/** Device-name rules shared by Android candidate discovery and native capture. */
internal object RootInputDeviceClassifier {
    fun isXiaomiPrivateKeyboard(name: String): Boolean {
        val normalized = name.lowercase()
        val explicitlyKeyboard = "keyboard" in normalized || "kbd" in normalized
        val miNamedKeyboard = explicitlyKeyboard && (
            normalized.startsWith("mi ") || " mi " in normalized
        )
        return "xiaomi" in normalized ||
            "nanosic" in normalized ||
            ("ptms" in normalized && explicitlyKeyboard) ||
            miNamedKeyboard
    }

}
