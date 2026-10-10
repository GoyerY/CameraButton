package com.goyer.camerabutton.data

import android.content.Context
import androidx.core.content.edit
import com.goyer.camerabutton.model.CameraKeySettings
import com.goyer.camerabutton.xposed.SettingsStore

/**
 * 设置界面的持久化层。
 *
 * 配置写入普通 SharedPreferences（应用私有），同时通过 Xposed remoteFile
 * 同步一份给 system_server 侧的 [com.goyer.camerabutton.xposed.SettingsStore] 读取。
 */
object SettingsRepository {

    private const val PREF_NAME = "camerabutton_settings"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    fun load(context: Context): CameraKeySettings {
        val raw = prefs(context).getString(SettingsStore.REMOTE_FILE_NAME, null)
            ?: return CameraKeySettings()
        return runCatching {
            CameraKeySettings.fromJson(org.json.JSONObject(raw))
        }.getOrDefault(CameraKeySettings())
    }

    /** 保存设置并同步到 system_server。 */
    fun save(context: Context, settings: CameraKeySettings) {
        val json = settings.toJson().toString()
        prefs(context).edit { putString(SettingsStore.REMOTE_FILE_NAME, json) }
        RemoteFileWriter.write(context, json)
    }

    /** 更新单个动作类型，避免整对象重建导致的字段丢失。 */
    fun update(
        context: Context,
        transform: (CameraKeySettings) -> CameraKeySettings
    ) {
        save(context, transform(load(context)))
    }
}