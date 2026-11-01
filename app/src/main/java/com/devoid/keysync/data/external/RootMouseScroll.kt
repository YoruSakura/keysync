package com.devoid.keysync.data.external

/** Unit conversion shared by the native Root wheel callback and app input path. */
internal object RootMouseScroll {
    const val UNITS_PER_NOTCH = 120

    fun toNotches(verticalDeltaUnits: Int): Float =
        verticalDeltaUnits.toFloat() / UNITS_PER_NOTCH
}
