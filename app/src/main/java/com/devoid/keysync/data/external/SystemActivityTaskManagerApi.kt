package com.devoid.keysync.data.external

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.ComponentName
import android.os.IBinder
import android.util.Log
import java.lang.reflect.Method

/**
 * Privileged, read-only access to the package owning Android's focused task.
 *
 * Root and Shizuku both have permission to query the complete task list, while
 * the regular app process deliberately does not request Usage Access or run an
 * accessibility service. Reflection is kept behind this small wrapper because
 * IActivityTaskManager is a hidden system interface whose method shape differs
 * slightly between Android releases.
 */
@SuppressLint("PrivateApi")
internal class SystemActivityTaskManagerApi(
    private val tag: String,
    private val binderProvider: () -> IBinder
) {
    private var activityTaskManager: Any? = null
    private var focusedTaskMethod: Method? = null
    private var taskListMethods: List<Method> = emptyList()

    @Synchronized
    fun getFocusedPackageName(): String? {
        if (!ensureConnected()) return null
        val manager = activityTaskManager ?: return null

        focusedTaskMethod?.let { method ->
            val focusedPackage = runCatching {
                extractTopPackage(method.invoke(manager))
            }.onFailure {
                Log.w(tag, "Unable to query the focused root task", it)
                focusedTaskMethod = null
            }.getOrNull()
            if (focusedPackage != null) return focusedPackage
        }

        taskListMethods.forEach { method ->
            val taskPackage = runCatching {
                extractTopPackage(method.invoke(manager, *argumentsFor(method)))
            }.getOrNull()
            if (taskPackage != null) return taskPackage
        }

        return null
    }

    private fun ensureConnected(): Boolean {
        if (activityTaskManager != null && (focusedTaskMethod != null || taskListMethods.isNotEmpty())) {
            return true
        }

        return runCatching {
            val binder = binderProvider()
            val stubClass = Class.forName("android.app.IActivityTaskManager\$Stub")
            val interfaceClass = Class.forName("android.app.IActivityTaskManager")
            activityTaskManager = stubClass
                .getMethod("asInterface", IBinder::class.java)
                .invoke(null, binder)
            focusedTaskMethod = interfaceClass.methods.firstOrNull {
                it.parameterCount == 0 &&
                    (it.name == "getFocusedRootTaskInfo" || it.name == "getFocusedStackInfo")
            }
            taskListMethods = interfaceClass.methods
                .filter { it.name == "getTasks" && supports(it.parameterTypes) }
                .sortedBy(Method::getParameterCount)
            check(activityTaskManager != null) { "ActivityTaskManager service is unavailable" }
            check(focusedTaskMethod != null || taskListMethods.isNotEmpty()) {
                "No supported foreground-task query exists"
            }
        }.onFailure {
            disconnect()
            Log.w(tag, "Unable to connect to ActivityTaskManager", it)
        }.isSuccess
    }

    private fun supports(parameterTypes: Array<Class<*>>): Boolean =
        parameterTypes.isNotEmpty() && parameterTypes.all {
            it == Integer.TYPE || it == java.lang.Boolean.TYPE
        }

    private fun argumentsFor(method: Method): Array<Any> {
        var integerIndex = 0
        return method.parameterTypes.map { type ->
            when (type) {
                Integer.TYPE -> if (integerIndex++ == 0) MAX_TASKS else DEFAULT_DISPLAY
                java.lang.Boolean.TYPE -> false
                else -> error("Unsupported IActivityTaskManager.getTasks argument: $type")
            }
        }.toTypedArray()
    }

    private fun extractTopPackage(value: Any?): String? = when (value) {
        is ActivityManager.RunningTaskInfo -> value.topActivity?.packageName
        is Iterable<*> -> value.firstNotNullOfOrNull(::extractTopPackage)
        null -> null
        else -> extractTopActivityReflectively(value)?.packageName
    }

    private fun extractTopActivityReflectively(taskInfo: Any): ComponentName? = runCatching {
        taskInfo.javaClass.getField("topActivity").get(taskInfo) as? ComponentName
    }.getOrNull()

    private fun disconnect() {
        activityTaskManager = null
        focusedTaskMethod = null
        taskListMethods = emptyList()
    }

    private companion object {
        const val MAX_TASKS = 10
        const val DEFAULT_DISPLAY = 0
    }
}
