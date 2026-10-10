package com.goyer.camerabutton.xposed

import android.os.ParcelFileDescriptor
import com.goyer.camerabutton.model.CameraKeySettings
import io.github.libxposed.api.XposedInterface
import org.json.JSONObject
import java.io.FileNotFoundException
import java.nio.charset.StandardCharsets

/**
 * system_server 侧的设置读取。
 *
 * 设置由设置界面写入模块的 remoteFile，本对象在每次手势判定前热加载，
 * 因此修改设置后无需重启应用或框架。
 */
internal object SettingsStore {

    const val REMOTE_FILE_NAME = "camerabutton_settings.json"

    @Volatile
    private var api: XposedInterface? = null

    @Volatile
    private var cached: CameraKeySettings = CameraKeySettings()

    @Volatile
    private var lastReadAt = 0L

    fun bind(value: XposedInterface) {
        api = value
    }

    /** 返回最新设置，短时间内（200ms）复用缓存以避免高频磁盘读取。 */
    fun latest(): CameraKeySettings {
        val now = android.os.SystemClock.uptimeMillis()
        if (now - lastReadAt < 200L && lastReadAt != 0L) return cached
        lastReadAt = now
        readRemote()?.let { cached = it }
        return cached
    }

    private fun readRemote(): CameraKeySettings? {
        val module = api ?: return null
        return try {
            val descriptor = module.openRemoteFile(REMOTE_FILE_NAME)
            if (descriptor.statSize <= 0L) {
                descriptor.close()
                null
            } else {
                ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { input ->
                    val text = input.readBytes().toString(StandardCharsets.UTF_8)
                    CameraKeySettings.fromJson(JSONObject(text))
                }
            }
        } catch (_: FileNotFoundException) {
            null
        } catch (error: Throwable) {
            HookLog.e(TAG, "read settings failed: ${error.message}")
            null
        }
    }

    private const val TAG = "CameraButton"
}