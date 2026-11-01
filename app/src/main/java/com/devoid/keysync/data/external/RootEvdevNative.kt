package com.devoid.keysync.data.external

internal object RootEvdevNative {
    init {
        System.loadLibrary("keysync_root")
    }

    external fun nativeRunCapture(
        callback: RootInputService,
        names: Array<String>,
        vendorIds: IntArray,
        productIds: IntArray
    ): Int

    external fun nativePrepareCapture()

    external fun nativeStopCapture()
}
