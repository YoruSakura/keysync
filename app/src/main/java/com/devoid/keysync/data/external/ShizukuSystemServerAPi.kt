package com.devoid.keysync.data.external

import android.content.Context
import android.content.pm.PackageManager
import android.view.InputEvent
import androidx.core.content.ContextCompat
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.ShizukuProvider
import rikka.shizuku.SystemServiceHelper

class ShizukuSystemServerAPi(private val context: Context) {
    private val inputManagerApi = SystemInputManagerApi(TAG) {
        ShizukuBinderWrapper(SystemServiceHelper.getSystemService("input"))
    }
    private val activityTaskManagerApi = SystemActivityTaskManagerApi(TAG) {
        ShizukuBinderWrapper(SystemServiceHelper.getSystemService("activity_task"))
    }

    fun isAvailable(): Boolean = runCatching {
        Shizuku.pingBinder() && hasPermission() && inputManagerApi.isAvailable()
    }.getOrDefault(false)

    fun inject(event: InputEvent, waitForResult: Boolean = true): Boolean =
        inputManagerApi.inject(event, waitForResult)

    fun cancelCurrentTouch(): Boolean = inputManagerApi.cancelCurrentTouch()

    fun getFocusedPackageName(): String? {
        if (!hasPermission() || !Shizuku.pingBinder()) return null
        return activityTaskManagerApi.getFocusedPackageName()
    }

    private fun hasPermission(): Boolean =
        if (Shizuku.isPreV11() || Shizuku.getVersion() < 11) {
            ContextCompat.checkSelfPermission(
                context,
                ShizukuProvider.PERMISSION
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        }

    private companion object {
        const val TAG = "ShizukuSystemServerApi"
    }
}
