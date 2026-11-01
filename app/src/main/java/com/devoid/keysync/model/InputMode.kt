package com.devoid.keysync.model

import kotlinx.serialization.Serializable

/**
 * Input privilege backend selected explicitly in the app settings.
 *
 * There is deliberately no AUTO value: changing privilege backends while an
 * Android multi-touch stream is active can duplicate or strand pointers.
 */
@Serializable
enum class InputMode {
    ROOT,
    SHIZUKU
}
