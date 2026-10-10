package com.goyer.camerabutton.xposed

import android.util.Log
import io.github.libxposed.api.XposedInterface

/**
 * 日志出口。
 *
 * system_server 进程中普通 logcat 未必可读，统一转发到 libxposed 日志接口；
 * 接口未绑定时退回 [Log]，保证任何环境下都不会因日志失败而中断按键流程。
 */
internal object HookLog {

    private const val TAG = "CameraButton"

    @Volatile
    private var api: XposedInterface? = null

    fun bind(value: XposedInterface) {
        api = value
    }

    fun d(tag: String, message: String) {
        write(Log.INFO, tag, message, null)
    }

    fun e(tag: String, message: String) {
        write(Log.ERROR, tag, message, null)
    }

    fun e(tag: String, message: String, throwable: Throwable) {
        write(Log.ERROR, tag, message, throwable)
    }

    private fun write(priority: Int, tag: String, message: String, error: Throwable?) {
        val current = api
        if (current != null) {
            runCatching { current.log(priority, "$TAG/$tag", message, error) }
        } else {
            if (error != null) Log.e(TAG, message, error) else Log.println(priority, TAG, message)
        }
    }
}