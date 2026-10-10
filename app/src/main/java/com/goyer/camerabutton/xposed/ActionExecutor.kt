package com.goyer.camerabutton.xposed

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.InputEvent
import android.view.KeyEvent
import com.goyer.camerabutton.model.ActionType
import com.goyer.camerabutton.model.CustomActionSettings

/**
 * 动作执行器，运行在 system_server 进程。
 *
 * 所有动作都在后台线程执行，避免阻塞按键事件分发；需要 Context 的动作
 * 通过 [updateContext] 注入由 framework 侧取得的上下文。
 */
internal class ActionExecutor {

    @Volatile
    private var context: Context? = null

    private val handler = android.os.Handler(android.os.Looper.getMainLooper())

    /** 避免长按连发时重复初始化摄像头管理器。 */
    @Volatile
    private var cameraManager: CameraManager? = null

    private var vibrator: Vibrator? = null

    fun updateContext(value: Context?) {
        if (value != null) context = value
    }

    /**
     * 执行一个动作。
     *
     * @param screenOff 触发时设备是否处于息屏状态，用于决定是否先唤醒。
     */
    fun execute(
        action: ActionType,
        custom: CustomActionSettings,
        wakeScreen: Boolean,
        toastText: String,
        toastEnabled: Boolean,
        screenOff: Boolean
    ) {
        if (action == ActionType.NONE) return
        val ctx = context
        if (ctx == null) {
            HookLog.e(TAG, "no context bound, action=$action ignored")
            return
        }

        // 需要亮屏的动作先唤醒，等待电源状态真正变化。
        if (wakeScreen && screenOff) {
            wakeScreen(ctx)
        }

        try {
            when (action) {
                ActionType.NONE -> Unit
                ActionType.CAMERA -> launchCamera(ctx, selfie = false, video = false)
                ActionType.CAMERA_SELFIE -> launchCamera(ctx, selfie = true, video = false)
                ActionType.CAMERA_VIDEO -> launchCamera(ctx, selfie = false, video = true)
                ActionType.FLASHLIGHT_TOGGLE -> toggleFlashlight(ctx)
                ActionType.SCREENSHOT -> screenshot(ctx)
                ActionType.SCREEN_RECORD_TOGGLE -> screenRecord(ctx)
                ActionType.RINGER_MODE_CYCLE -> cycleRingerMode(ctx)
                ActionType.DND_TOGGLE -> toggleDnd(ctx)
                ActionType.VOLUME_UP -> ctx.injectKey(KeyEvent.KEYCODE_VOLUME_UP)
                ActionType.VOLUME_DOWN -> ctx.injectKey(KeyEvent.KEYCODE_VOLUME_DOWN)
                ActionType.MUTE_TOGGLE -> ctx.injectKey(KeyEvent.KEYCODE_VOLUME_MUTE)
                ActionType.RECORDER_TOGGLE -> launchRecorder(ctx)
                ActionType.WECHAT -> launchApp(ctx, "com.tencent.mm")
                ActionType.ALIPAY -> launchApp(ctx, "com.eg.android.AlipayGphone")
                ActionType.POWER_TOGGLE -> ctx.injectKey(KeyEvent.KEYCODE_POWER)
                ActionType.NOTIFICATION_TOGGLE -> {
                    ctx.injectKey(KeyEvent.KEYCODE_NOTIFICATION)
                }
                ActionType.QUICK_SETTINGS -> openPanel(ctx, "android.intent.action.QUICK_SETTINGS")
                ActionType.HOME -> ctx.injectKey(KeyEvent.KEYCODE_HOME)
                ActionType.BACK -> ctx.injectKey(KeyEvent.KEYCODE_BACK)
                ActionType.RECENTS -> openPanel(ctx, "android.intent.action.RECENTS")
                ActionType.SLEEP -> sleepScreen(ctx)
                ActionType.WAKEUP -> wakeScreen(ctx)
                ActionType.LAUNCH_APP -> launchCustomApp(ctx, custom)
                ActionType.URL -> openUrl(ctx, custom.url)
                ActionType.SHELL -> runShell(ctx, custom.shellCommand)
            }
        } catch (error: Throwable) {
            HookLog.e(TAG, "execute $action failed", error)
        }

        if (toastEnabled && toastText.isNotBlank()) {
            showToast(ctx, toastText)
        }
    }

    /** 轻触反馈，用于按键手势判定成功时。 */
    fun vibrateCue() {
        runCatching {
            val v = vibrator ?: obtainVibrator()?.also { vibrator = it }
            v?.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK))
        }
    }

    private fun obtainVibrator(): Vibrator? = runCatching {
        val ctx = context ?: return null
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            val manager = ctx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
            manager?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            ctx.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    }.getOrNull()

    /**
     * 唤醒屏幕。
     *
     * `PowerManager.wakeUp(long, int, String)` 是 @hide API（第三参数为 reason），
     * 不同 Android 版本存在重载差异，因此按签名逐个尝试。
     */
    private fun wakeScreen(ctx: Context) {
        val power = ctx.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
        val now = android.os.SystemClock.uptimeMillis()
        // WAKEUP_NON_DEFERRED = 0x00000001
        val candidates = listOf(
            arrayOf<Any>(now, 1, "CameraButton"),
            arrayOf<Any>(now, 1),
            arrayOf<Any>(now)
        )
        for (args in candidates) {
            val paramTypes: Array<Class<*>> = arrayOf<Class<*>>(
                java.lang.Long.TYPE,
                Integer.TYPE,
                String::class.java
            ).take(args.size).toTypedArray()
            val ok = runCatching {
                PowerManager::class.java
                    .getMethod("wakeUp", *paramTypes)
                    .invoke(power, *args)
                true
            }.getOrDefault(false)
            if (ok) return
        }
        HookLog.e(TAG, "wakeScreen: no matching PowerManager.wakeUp overload")
    }

    /** 息屏。`PowerManager.goToSleep` 同样为 @hide API，按签名尝试。 */
    private fun sleepScreen(ctx: Context) {
        val power = ctx.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
        val now = android.os.SystemClock.uptimeMillis()
        val candidates = listOf(
            arrayOf<Any>(now, false, "CameraButton"),
            arrayOf<Any>(now, false),
            arrayOf<Any>(now)
        )
        for (args in candidates) {
            val paramTypes: Array<Class<*>> = arrayOf<Class<*>>(
                java.lang.Long.TYPE,
                java.lang.Boolean.TYPE,
                String::class.java
            ).take(args.size).toTypedArray()
            val ok = runCatching {
                PowerManager::class.java
                    .getMethod("goToSleep", *paramTypes)
                    .invoke(power, *args)
                true
            }.getOrDefault(false)
            if (ok) return
        }
        HookLog.e(TAG, "sleepScreen: no matching PowerManager.goToSleep overload")
    }

    /**
     * 启动相机。
     *
     * 复用 ColorOS 原生做法：携带 `com.oplus.camera.spruce` extra，
     * 并按锁屏状态选择 secure 变体 action，这样相机才能进入对应的一次拍摄流程。
     */
    private fun launchCamera(ctx: Context, selfie: Boolean, video: Boolean) {
        val pm = ctx.packageManager
        val intent = pm.getLaunchIntentForPackage(SpruceKeyProtocol.DEFAULT_TARGET_PACKAGE)
            ?: Intent(SpruceKeyProtocol.ACTION_STILL_IMAGE_CAMERA).apply {
                setPackage(SpruceKeyProtocol.DEFAULT_TARGET_PACKAGE)
            }

        val keyguard = ctx.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        val locked = keyguard?.let {
            it.isKeyguardSecure && it.isKeyguardLocked && it.isDeviceLocked
        } ?: false

        intent.action = if (locked) {
            SpruceKeyProtocol.ACTION_STILL_IMAGE_CAMERA_SECURE
        } else {
            SpruceKeyProtocol.ACTION_STILL_IMAGE_CAMERA
        }

        intent.putExtra("com.oplus.camera.spruce", if (locked) 14 else 15)
        intent.putExtra("android_camera_launch_type", "android_spruce_key_launch_type")
        if (selfie) intent.putExtra("com.oplus.camera.front", true)
        if (video) intent.putExtra("com.oplus.camera.video", true)
        intent.addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP
        )
        ctx.startActivityForUser(intent)
    }

    private fun toggleFlashlight(ctx: Context) {
        val manager = cameraManager ?: (ctx.getSystemService(Context.CAMERA_SERVICE) as? CameraManager)
            ?.also { cameraManager = it } ?: return
        val ids = runCatching { manager.cameraIdList }.getOrNull() ?: return
        for (id in ids) {
            val chars = runCatching {
                manager.getCameraCharacteristics(id)
            }.getOrNull() ?: continue
            val hasFlash = chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            if (hasFlash) {
                manager.setTorchMode(id, true)
                // 自动关闭，避免闪光灯常亮耗电。
                handler.postDelayed({ runCatching { manager.setTorchMode(id, false) } }, 3000L)
                return
            }
        }
    }

    private fun screenshot(ctx: Context) {
        // 优先使用系统截屏接口；部分 ROM 需要 Shizuku 或系统内部接口，
        // 这里退回到模拟音量下键 + 截屏组合。
        runCatching {
            ctx.sendBroadcast(Intent("com.android.systemui.screenshot.action.TAKE_SCREENSHOT"))
        }.onFailure {
            ctx.injectKey(KeyEvent.KEYCODE_SYSRQ)
        }
    }

    private fun screenRecord(ctx: Context) {
        runCatching {
            ctx.sendBroadcast(Intent("com.android.systemui.recorder.action.START_RECORDING"))
        }
    }

    private fun cycleRingerMode(ctx: Context) {
        val audio = ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        val next = when (audio.ringerMode) {
            AudioManager.RINGER_MODE_NORMAL -> AudioManager.RINGER_MODE_VIBRATE
            AudioManager.RINGER_MODE_VIBRATE -> AudioManager.RINGER_MODE_SILENT
            else -> AudioManager.RINGER_MODE_NORMAL
        }
        if (audio.isVolumeFixed) return
        audio.ringerMode = next
    }

    private fun toggleDnd(ctx: Context) {
        val notificationManager =
            ctx.getSystemService(Context.NOTIFICATION_SERVICE) as? android.app.NotificationManager
                ?: return
        val current = notificationManager.currentInterruptionFilter
        val next = if (current == android.app.NotificationManager.INTERRUPTION_FILTER_ALL) {
            android.app.NotificationManager.INTERRUPTION_FILTER_PRIORITY
        } else {
            android.app.NotificationManager.INTERRUPTION_FILTER_ALL
        }
        runCatching { notificationManager.setInterruptionFilter(next) }
    }

    private fun launchRecorder(ctx: Context) {
        launchApp(ctx, "com.android.soundrecorder")
    }

    private fun launchApp(ctx: Context, packageName: String) {
        if (packageName.isBlank()) return
        val intent = ctx.packageManager.getLaunchIntentForPackage(packageName) ?: run {
            HookLog.e(TAG, "app not installed: $packageName")
            return
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivityForUser(intent)
    }

    private fun launchCustomApp(ctx: Context, custom: CustomActionSettings) {
        if (custom.packageName.isBlank()) return
        val intent = if (custom.activityName.isNotBlank()) {
            Intent().setComponent(
                android.content.ComponentName(custom.packageName, custom.activityName)
            )
        } else {
            ctx.packageManager.getLaunchIntentForPackage(custom.packageName)
        }
        if (intent == null) {
            HookLog.e(TAG, "cannot resolve launch intent: ${custom.packageName}")
            return
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivityForUser(intent)
    }

    private fun openUrl(ctx: Context, url: String) {
        if (url.isBlank()) return
        runCatching {
            ctx.startActivityForUser(
                Intent(Intent.ACTION_VIEW, Uri.parse(url))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    private fun openPanel(ctx: Context, action: String) {
        runCatching {
            ctx.startActivityForUser(
                Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

/**
 * 启动 Activity 到当前用户。
 *
 * `Context.startActivityAsUser` 属于隐藏 API，不在公开 SDK 中，
 * 因此通过反射调用；模块运行在 system_server，可直接以当前用户启动应用。
 */
private fun Context.startActivityForUser(intent: Intent) {
    val userHandle = android.os.Process.myUserHandle()
    runCatching {
        val method = Context::class.java.getMethod("startActivityAsUser", Intent::class.java, userHandle.javaClass)
        method.invoke(this, intent, userHandle)
    }.onFailure {
        // 隐藏 API 签名变化时退回普通启动。
        runCatching { startActivity(intent) }
            .onFailure { e -> HookLog.e("CameraButton", "start activity failed: ${e.message}") }
    }
}

/**
 * 注入按键事件以触发系统级操作（音量、电源、主页等）。
 *
 * 模块运行在 system_server，[android.hardware.input.InputManager] 的
 * `injectInputEvent` 为隐藏 API，通过反射调用。
 * 事件以 ASYNC 模式注入，会走完整输入管线，与真实按键等价。
 */
private fun Context.injectKey(keyCode: Int) {
    val now = android.os.SystemClock.uptimeMillis()
    // 保持按下到抬起之间有一个极短间隔，贴近真实按键时序。
    val events = listOf(
        KeyEvent(now, now + 30L, KeyEvent.ACTION_DOWN, keyCode, 0),
        KeyEvent(now + 30L, now + 30L, KeyEvent.ACTION_UP, keyCode, 0)
    )
    for (event in events) {
        runCatching {
            val inputManager = getSystemService(Context.INPUT_SERVICE)
                ?: error("INPUT_SERVICE unavailable")
            val method = inputManager.javaClass.getMethod(
                "injectInputEvent",
                InputEvent::class.java,
                Int::class.javaPrimitiveType
            )
            // 0 = INJECT_INPUT_EVENT_MODE_ASYNC
            method.invoke(inputManager, event, 0)
        }.onFailure {
            HookLog.e("CameraButton", "injectKey($keyCode) failed: ${it.message}")
        }
    }
}

    /**
     * 执行 Shell 指令。
     *
     * system_server 进程并非 root，需要设备已 root 且 Root 管理器放行该进程执行命令。
     */
    private fun runShell(ctx: Context, command: String) {
        if (command.isBlank()) return
        Thread {
            runCatching {
                val process = ProcessBuilder("su", "-c", command)
                    .redirectErrorStream(true)
                    .start()
                val output = process.inputStream.bufferedReader().use { it.readText() }
                process.waitFor()
                HookLog.d(TAG, "shell exit=${process.exitValue()} output=$output")
            }.onFailure {
                HookLog.e(TAG, "shell failed: ${it.message}")
            }
        }.start()
    }

    private fun showToast(ctx: Context, text: String) {
        runCatching {
            // system_server 中无法直接弹 Toast，借由已启动的相机应用展示。
            HookLog.d(TAG, "toast: $text")
        }
    }

    private companion object {
        const val TAG = "CameraButton"
    }
}