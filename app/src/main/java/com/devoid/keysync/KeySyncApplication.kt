package com.devoid.keysync

import android.app.Application
import android.content.Context
import android.os.Process
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.request.CachePolicy
import coil3.request.crossfade
import coil3.size.Precision
import com.devoid.keysync.data.external.RootInputConnection
import com.devoid.keysync.data.local.DataStoreManager
import com.devoid.keysync.model.InputMode
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@HiltAndroidApp
class KeySyncApplication : Application(), SingletonImageLoader.Factory {
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate() {
        super.onCreate()
        if (Process.myUid() != ROOT_UID) {
            applicationScope.launch {
                val inputMode = DataStoreManager(this@KeySyncApplication)
                    .getKeyConfig(DataStoreManager.KEYS_CONFIG)
                    .first()
                    .inputMode
                if (inputMode == InputMode.ROOT) {
                    RootInputConnection.initialize(this@KeySyncApplication)
                }
            }
        }
    }

    override fun attachBaseContext(base: Context?) {
        super.attachBaseContext(base);

    }

    override fun newImageLoader(context: PlatformContext): ImageLoader {
        return ImageLoader.Builder(context)
            .diskCachePolicy(CachePolicy.DISABLED)
            .crossfade(true)
            .precision(Precision.INEXACT)
            .build()
    }

    private companion object {
        const val ROOT_UID = 0
    }
}
