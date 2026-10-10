package com.goyer.camerabutton.xposed

/**
 * ColorOS 松脆键（SpruceKey）按键协议常量。
 *
 * 以下取值来自 OPPO PMA110 / Find X9 Ultra（ColorOS 16，Android 17）实机探测：
 * 反编译 `/system_ext/app/OplusGestureUI/OplusGestureUI.apk`，定位到
 * `com.oplus.gesture` 包内 logcat TAG 为 `GestureKeyEventManager` 的分发类，
 * 其 `onKeyEvent` 按 keyCode 分发松脆键事件。
 *
 * 松脆键不同于普通侧键：按键由驱动上报为一系列 keyCode，
 * framework 侧的 [android.os.OplusKeyEventManager] 负责投递给注册观察者，
 * 观察者掩码需要包含 `SPRUCE_MASK` 才会收到这些事件。
 */
object SpruceKeyProtocol {

    /**
     * 松脆键功能掩码。
     *
     * 源码对应 `GestureUtil.isSupportSpruceKey() ? 7 | 0x1FFFFFF : 7`，
     * 即 33554447。额外并上 0x8000000 是灵犀/Hyper 键掩码，
     * 并上 0x20C0BD0 是 Action Button 掩码——一并注册可同时拿到音量键相关事件，
     * 用于组合键判定。
     */
    const val OBSERVER_MASK: Int = 33554447 or 0x20C0BD0 or 0x8000000

    /** 松脆键按下（对应分发类的 keyCode 769，触发启动动作）。 */
    const val KEY_SPRUCE_PRESS = 769

    /**
     * 松脆键手势组。
     *
     * 对应分发类中 `case 765..768: break` 的分支：这些 keyCode 由驱动/framework
     * 预先判定手势类型后投递，仅用于更新界面动效，不承载业务逻辑。
     * 模块一并监听以获得完整的按下/抬起时序。
     */
    val SPRUCE_GESTURE_CODES = intArrayOf(765, 766, 767, 768)

    /** 息屏状态下按下的松脆键（分发类中 `keyCode == 773`）。 */
    const val KEY_SPRUCE_SCREEN_OFF = 773

    /** 快门/截屏键（分发类中 `keyCode == 781`）。 */
    const val KEY_SHUTTER = 781

    /** 快捷键按下（分发类中 `keyCode == 782`）。 */
    const val KEY_ACTION_DOWN = 782

    /** 快捷键抬起（分发类中 `keyCode == 783`）。 */
    const val KEY_ACTION_UP = 783

    /** 灵犀键（分发类中 `keyCode == 671`）。 */
    const val KEY_LINGXI = 671

    /** Hyper 键（分发类中 `keyCode == 673`）。 */
    const val KEY_HYPER = 673

    /** 全部由模块关注的 keyCode。 */
    val WATCHED_CODES: Set<Int> = buildSet {
        add(KEY_SPRUCE_PRESS)
        add(KEY_SPRUCE_SCREEN_OFF)
        add(KEY_ACTION_DOWN)
        add(KEY_ACTION_UP)
        addAll(SPRUCE_GESTURE_CODES.toList())
    }

    /**
     * 松脆键目标应用默认包名。
     *
     * 实机 `secure` 设置中 `spruce_start_camera_name=com.oplus.camera`。
     */
    const val DEFAULT_TARGET_PACKAGE = "com.oplus.camera"

    /** 相机启动 action，ColorOS 在此基础上追加 secure 变体。 */
    const val ACTION_STILL_IMAGE_CAMERA = "android.media.action.STILL_IMAGE_CAMERA"
    const val ACTION_STILL_IMAGE_CAMERA_SECURE = "android.media.action.STILL_IMAGE_CAMERA_SECURE"
}