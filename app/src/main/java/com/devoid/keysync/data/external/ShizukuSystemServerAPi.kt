package com.devoid.keysync.data.external

import android.os.IBinder
import android.util.Log
import android.view.InputEvent
import com.devoid.keysync.domain.EventHandler
import com.devoid.keysync.domain.EventInjectorImpl
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.SystemServiceHelper
import java.lang.reflect.Method

class ShizukuSystemServerAPi {
    private val TAG = "ShizukuSystemServerApi"
    private var injectInputEventFunction : Method? = null
    private  var inputManagerInstance:Any? =null
    init {
        injectInputEventFunction = getInputApi()
    }
        private fun getInputApi(): Method? {
            val inputManagerBinder: IBinder =
                ShizukuBinderWrapper(SystemServiceHelper.getSystemService("input"))
            val inputManagerStub = Class.forName("android.hardware.input.IInputManager\$Stub")
            val inputManagerClass = Class.forName("android.hardware.input.IInputManager")
             inputManagerInstance =
                inputManagerStub.getMethod("asInterface", IBinder::class.java)
                    .invoke(null, inputManagerBinder)
            val injectInputEvent = inputManagerClass.getMethod(
                "injectInputEvent",
                InputEvent::class.java,
                Int::class.java
            )
            return injectInputEvent
        }

    fun getEventHandler(): EventHandler {
        if (injectInputEventFunction==null||inputManagerInstance==null){
            throw IllegalStateException("Input manager Service not connected")
       }
       return EventHandler(object : EventInjectorImpl(){
           override fun inject(event: InputEvent) {
//               Log.d(TAG, "inject: injecting: $event")
               injectWithReconnect(event)
           }
       })
    }

    @Synchronized
    private fun injectWithReconnect(event: InputEvent) {
        val firstAttempt = runCatching { invokeInputEvent(event) }
        if (firstAttempt.isSuccess) return

        Log.w(TAG, "Input injection failed; reconnecting to InputManager", firstAttempt.exceptionOrNull())
        runCatching {
            injectInputEventFunction = getInputApi()
            invokeInputEvent(event)
        }.onFailure { error ->
            Log.e(TAG, "Input injection retry failed", error)
        }
    }

    private fun invokeInputEvent(event: InputEvent) {
        val function = injectInputEventFunction
            ?: throw IllegalStateException("Input injection function is unavailable")
        val inputManager = inputManagerInstance
            ?: throw IllegalStateException("Input manager service is unavailable")
        function.invoke(inputManager, event, 1)
    }



}
