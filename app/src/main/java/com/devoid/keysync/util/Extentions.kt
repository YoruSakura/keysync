package com.devoid.keysync.util

import android.view.KeyEvent
import com.devoid.keysync.domain.KEYCODE_LMC
import com.devoid.keysync.domain.KEYCODE_MMC
import com.devoid.keysync.domain.KEYCODE_RMC

fun Int.keyCodeToString(
    unknownLabel: String = "Unknown",
    leftMouseButtonLabel: String = "LMB",
    rightMouseButtonLabel: String = "RMB",
    middleMouseButtonLabel: String = "MMB"
):String{
    return when(this){
        KEYCODE_LMC -> leftMouseButtonLabel
        KEYCODE_RMC -> rightMouseButtonLabel
        KEYCODE_MMC -> middleMouseButtonLabel
        else-> KeyEvent.keyCodeToString(this).replace("KEYCODE_","").takeIf { it!="1001" }?:unknownLabel
    }
}
