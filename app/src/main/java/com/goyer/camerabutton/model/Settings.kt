package com.goyer.camerabutton.model

import org.json.JSONObject

/**
 * 可执行动作类型。
 *
 * [NONE] 表示该手势不执行任何操作，此时单击会立即触发而不再等待双击窗口。
 */
enum class ActionType(val value: String) {
    NONE("none"),
    CAMERA("camera"),
    CAMERA_SELFIE("camera_selfie"),
    CAMERA_VIDEO("camera_video"),
    FLASHLIGHT_TOGGLE("flashlight_toggle"),
    SCREENSHOT("screenshot"),
    SCREEN_RECORD_TOGGLE("screen_record_toggle"),
    RINGER_MODE_CYCLE("ringer_cycle"),
    DND_TOGGLE("dnd_toggle"),
    VOLUME_UP("volume_up"),
    VOLUME_DOWN("volume_down"),
    MUTE_TOGGLE("mute_toggle"),
    RECORDER_TOGGLE("recorder_toggle"),
    WECHAT("wechat"),
    ALIPAY("alipay"),
    POWER_TOGGLE("power_toggle"),
    NOTIFICATION_TOGGLE("notification_toggle"),
    QUICK_SETTINGS("quick_settings"),
    HOME("home"),
    BACK("back"),
    RECENTS("recents"),
    SLEEP("sleep"),
    WAKEUP("wakeup"),
    LAUNCH_APP("launch_app"),
    URL("url"),
    SHELL("shell");

    companion object {
        fun from(value: String?): ActionType =
            entries.firstOrNull { it.value == value } ?: NONE
    }
}

/** 自定义动作参数，按动作类型取用其中若干项。 */
data class CustomActionSettings(
    val packageName: String = "",
    val activityName: String = "",
    val url: String = "",
    val shellCommand: String = ""
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("packageName", packageName)
        put("activityName", activityName)
        put("url", url)
        put("shellCommand", shellCommand)
    }

    companion object {
        fun fromJson(json: JSONObject?): CustomActionSettings {
            if (json == null) return CustomActionSettings()
            return CustomActionSettings(
                packageName = json.optString("packageName", ""),
                activityName = json.optString("activityName", ""),
                url = json.optString("url", ""),
                shellCommand = json.optString("shellCommand", "")
            )
        }
    }
}

/**
 * 模块完整配置。
 *
 * 该实例会在 system_server 进程中通过 remoteFile 反复读取，因此保持不可变并支持热加载：
 * 修改设置后无需重启应用，只有改动 LSPosed 作用域才需要重启框架。
 */
data class CameraKeySettings(
    val enabled: Boolean = true,
    /** 短按动作。 */
    val singleAction: ActionType = ActionType.CAMERA,
    val singleCustom: CustomActionSettings = CustomActionSettings(),
    val singleWakeScreen: Boolean = true,
    /** 长按动作。 */
    val longAction: ActionType = ActionType.SCREENSHOT,
    val longCustom: CustomActionSettings = CustomActionSettings(),
    val longWakeScreen: Boolean = false,
    /** 双击动作。NONE 时单击立即触发。 */
    val doubleAction: ActionType = ActionType.NONE,
    val doubleCustom: CustomActionSettings = CustomActionSettings(),
    val doubleWakeScreen: Boolean = false,
    /** 判为长按的持续时长。 */
    val longPressMs: Long = 500L,
    /** 双击等待窗口。 */
    val doubleClickWindowMs: Long = 300L,
    /** 触发时是否振动。 */
    val vibrationEnabled: Boolean = true,
    /** 是否显示 Toast 提示。 */
    val toastEnabled: Boolean = false,
    val toastText: String = "相机键已触发",
    /**
     * 是否接管系统原有的松脆键行为。
     *
     * 关闭时只读取事件而不拦截，ColorOS 原生双击打开相机等行为保持不变。
     */
    val interceptSystemAction: Boolean = true,
    /**
     * 息屏时是否唤醒屏幕再执行动作。
     */
    val wakeScreenWhenOff: Boolean = true
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("enabled", enabled)
        put("singleAction", singleAction.value)
        put("singleCustom", singleCustom.toJson())
        put("singleWakeScreen", singleWakeScreen)
        put("longAction", longAction.value)
        put("longCustom", longCustom.toJson())
        put("longWakeScreen", longWakeScreen)
        put("doubleAction", doubleAction.value)
        put("doubleCustom", doubleCustom.toJson())
        put("doubleWakeScreen", doubleWakeScreen)
        put("longPressMs", longPressMs)
        put("doubleClickWindowMs", doubleClickWindowMs)
        put("vibrationEnabled", vibrationEnabled)
        put("toastEnabled", toastEnabled)
        put("toastText", toastText)
        put("interceptSystemAction", interceptSystemAction)
        put("wakeScreenWhenOff", wakeScreenWhenOff)
    }

    companion object {
        fun fromJson(json: JSONObject?): CameraKeySettings {
            if (json == null) return CameraKeySettings()
            val fallback = CameraKeySettings()
            return CameraKeySettings(
                enabled = json.optBoolean("enabled", fallback.enabled),
                singleAction = ActionType.from(json.optString("singleAction")),
                singleCustom = CustomActionSettings.fromJson(json.optJSONObject("singleCustom")),
                singleWakeScreen = json.optBoolean("singleWakeScreen", fallback.singleWakeScreen),
                longAction = ActionType.from(json.optString("longAction")),
                longCustom = CustomActionSettings.fromJson(json.optJSONObject("longCustom")),
                longWakeScreen = json.optBoolean("longWakeScreen", fallback.longWakeScreen),
                doubleAction = ActionType.from(json.optString("doubleAction")),
                doubleCustom = CustomActionSettings.fromJson(json.optJSONObject("doubleCustom")),
                doubleWakeScreen = json.optBoolean("doubleWakeScreen", fallback.doubleWakeScreen),
                longPressMs = json.optLong("longPressMs", fallback.longPressMs),
                doubleClickWindowMs = json.optLong("doubleClickWindowMs", fallback.doubleClickWindowMs),
                vibrationEnabled = json.optBoolean("vibrationEnabled", fallback.vibrationEnabled),
                toastEnabled = json.optBoolean("toastEnabled", fallback.toastEnabled),
                toastText = json.optString("toastText", fallback.toastText),
                interceptSystemAction = json.optBoolean(
                    "interceptSystemAction",
                    fallback.interceptSystemAction
                ),
                wakeScreenWhenOff = json.optBoolean("wakeScreenWhenOff", fallback.wakeScreenWhenOff)
            )
        }
    }
}