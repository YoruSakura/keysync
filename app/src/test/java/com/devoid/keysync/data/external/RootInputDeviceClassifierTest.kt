package com.devoid.keysync.data.external

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RootInputDeviceClassifierTest {
    @Test
    fun `recognizes Xiaomi pogo keyboard controller families`() {
        assertTrue(RootInputDeviceClassifier.isXiaomiPrivateKeyboard("Nanosic Keyboard"))
        assertTrue(RootInputDeviceClassifier.isXiaomiPrivateKeyboard("Xiaomi Keyboard"))
        assertTrue(RootInputDeviceClassifier.isXiaomiPrivateKeyboard("Mi PTMS3 Keyboard"))
        assertTrue(RootInputDeviceClassifier.isXiaomiPrivateKeyboard("Mi Keyboard"))
    }

    @Test
    fun `does not treat generic MIUI navigation bridge as a keyboard cover`() {
        assertFalse(RootInputDeviceClassifier.isXiaomiPrivateKeyboard("uinput_nav"))
        assertFalse(RootInputDeviceClassifier.isXiaomiPrivateKeyboard("Mi PTMS3 Mouse"))
    }

}
