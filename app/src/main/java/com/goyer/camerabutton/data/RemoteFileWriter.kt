package com.goyer.camerabutton.data

import android.content.Context
import android.util.Log
import com.goyer.camerabutton.xposed.SettingsStore
import java.io.File

/**
 * 把设置写入模块 remoteFile，供 system_server 侧读取。
 *
 * libxposed 的 remoteFile 位于 `/data/adb/modules/<module>/` 之外的应用私有目录，
 * 这里通过 Xposed API 提供的模块服务写入；若当前进程未加载为 Xposed 模块
 * （例如直接打开应用时），退回到把配置写到共享目录。
 */
object RemoteFileWriter {

    private const val TAG = "CameraButton"

    fun write(context: Context, json: String) {
        writeViaModuleScope(context, json) ?: writeToSharedFile(context, json)
    }

    /**
     * 首选路径：以模块自身 uid 写入 remoteFile 对应的物理文件。
     *
     * LSPosed 会把 remoteFile 映射到 `/data/user/0/<moduleId>/files/`。
     */
    private fun writeViaModuleScope(context: Context, json: String): Boolean = runCatching {
        val dir = File("/data/user/0/com.goyer.camerabutton/files")
        if (!dir.exists()) dir.mkdirs()
        val target = File(dir, SettingsStore.REMOTE_FILE_NAME)
        target.writeText(json)
        true
    }.getOrElse {
        Log.w(TAG, "write via module scope failed: ${it.message}")
        false
    }

    /** 回退路径：写入 root 可读的共享位置。 */
    private fun writeToSharedFile(context: Context, json: String): Boolean = runCatching {
        val dir = File("/data/local/tmp/camerabutton")
        if (!dir.exists()) dir.mkdirs()
        File(dir, SettingsStore.REMOTE_FILE_NAME).writeText(json)
        true
    }.getOrElse {
        Log.w(TAG, "write to shared file failed: ${it.message}")
        false
    }
}