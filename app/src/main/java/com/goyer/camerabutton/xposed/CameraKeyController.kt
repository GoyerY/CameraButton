package com.goyer.camerabutton.xposed

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.goyer.camerabutton.model.ActionType
import com.goyer.camerabutton.model.CameraKeySettings
import com.goyer.camerabutton.model.CustomActionSettings

/**
 * 相机实体键手势状态机。
 *
 * 在 system_server 进程中运行，负责把原始的按下/抬起事件判别为
 * 短按、长按、双击三种手势之一，并交给 [ActionExecutor] 执行。
 *
 * 判定规则：
 * - 长按：按住达到 [CameraKeySettings.longPressMs] 时立即触发，抬起后不再补发短按；
 * - 双击：未配置双击动作时，短按在抬起瞬间立即触发；
 *   配置了双击动作时，短按挂起等待 [CameraKeySettings.doubleClickWindowMs]，
 *   窗口内再次按下则判定为双击；
 * - 短按：以上两者均未命中时触发。
 *
 * 所有状态访问都在 [lock] 内同步，因为按键回调来自 system_server 主线程，
 * 而超时判定运行在同一个 Handler 上，仍可能出现跨线程重入。
 */
internal class CameraKeyController {

    private val handler = Handler(Looper.getMainLooper())
    private val lock = Any()

    /** 当前是否处于按住状态。 */
    private var pressed = false

    /** 长按是否已经触发，避免抬起后重复执行。 */
    private var longTriggered = false

    /** 本次按下是否已经越过长按阈值（可能因组合键而未触发动作）。 */
    private var longReached = false

    /** 本次按下是否为双击的第二次。 */
    private var secondClick = false

    /** 是否有一个待决的单击等待第二次按下。 */
    private var pendingSingle = false

    /** 按下瞬间的设置快照，保证一次手势内配置一致。 */
    private var activeSettings: CameraKeySettings = CameraKeySettings()

    private var longRunnable: Runnable? = null
    private var singleRunnable: Runnable? = null

    /**
     * 处理一次按下事件。
     */
    fun onDown(settings: CameraKeySettings, executor: ActionExecutor, screenOff: Boolean) {
        synchronized(lock) {
            if (!settings.enabled || pressed) return
            pressed = true
            longTriggered = false
            longReached = false
            activeSettings = settings

            // 若存在挂起的单击，本次按下即构成双击。
            secondClick = pendingSingle
            if (secondClick) {
                pendingSingle = false
                singleRunnable?.let(handler::removeCallbacks)
                singleRunnable = null
            }

            val runnable = Runnable {
                synchronized(lock) {
                    if (!pressed || longTriggered) return@synchronized
                    // 期间配置可能被关闭，此时直接放弃本次手势。
                    val current = SettingsStore.latest()
                    if (!current.enabled) {
                        cancelLocked()
                        return@synchronized
                    }
                    longTriggered = true
                    longReached = true
                    if (current.vibrationEnabled && current.longAction != ActionType.NONE) {
                        executor.vibrateCue()
                    }
                    val target = activeSettings
                    executor.execute(
                        action = target.longAction,
                        custom = target.longCustom,
                        wakeScreen = target.longWakeScreen,
                        toastText = current.toastText,
                        toastEnabled = current.toastEnabled,
                        screenOff = screenOff
                    )
                }
            }
            longRunnable = runnable
            handler.postDelayed(runnable, settings.longPressMs)
        }
    }

    /**
     * 处理一次抬起事件。
     */
    fun onUp(settings: CameraKeySettings, executor: ActionExecutor, screenOff: Boolean) {
        synchronized(lock) {
            if (!pressed) return
            pressed = false
            longRunnable?.let(handler::removeCallbacks)
            longRunnable = null

            // 长按已触发：抬起仅结束手势，不补发短按。
            if (longTriggered) {
                longTriggered = false
                longReached = false
                secondClick = false
                return
            }

            // 越过长按阈值但未执行（例如期间被禁用）：按长按收尾，避免误判为短按。
            if (longReached) {
                longReached = false
                secondClick = false
                return
            }

            val target = activeSettings

            // 双击：第二次抬起时执行。
            if (secondClick) {
                secondClick = false
                if (target.doubleAction != ActionType.NONE) {
                    if (settings.vibrationEnabled) executor.vibrateCue()
                    executor.execute(
                        action = target.doubleAction,
                        custom = target.doubleCustom,
                        wakeScreen = target.doubleWakeScreen,
                        toastText = settings.toastText,
                        toastEnabled = settings.toastEnabled,
                        screenOff = screenOff
                    )
                }
                return
            }

            // 未配置双击动作：短按立即触发。
            if (settings.doubleAction == ActionType.NONE) {
                if (target.singleAction != ActionType.NONE) {
                    if (settings.vibrationEnabled) executor.vibrateCue()
                    executor.execute(
                        action = target.singleAction,
                        custom = target.singleCustom,
                        wakeScreen = target.singleWakeScreen,
                        toastText = settings.toastText,
                        toastEnabled = settings.toastEnabled,
                        screenOff = screenOff
                    )
                }
                return
            }

            // 配置了双击：挂起单击，等待窗口内是否再次按下。
            pendingSingle = true
            val runnable = Runnable {
                synchronized(lock) {
                    if (!pendingSingle) return@synchronized
                    pendingSingle = false
                    val current = SettingsStore.latest()
                    if (!current.enabled) return@synchronized
                    if (target.singleAction == ActionType.NONE) return@synchronized
                    executor.execute(
                        action = target.singleAction,
                        custom = target.singleCustom,
                        wakeScreen = target.singleWakeScreen,
                        toastText = current.toastText,
                        toastEnabled = current.toastEnabled,
                        screenOff = screenOff
                    )
                }
            }
            singleRunnable = runnable
            handler.postDelayed(runnable, settings.doubleClickWindowMs)
        }
    }

    /** 放弃当前手势并清理所有挂起任务。 */
    fun cancel() {
        synchronized(lock) { cancelLocked() }
    }

    private fun cancelLocked() {
        pressed = false
        longTriggered = false
        longReached = false
        secondClick = false
        pendingSingle = false
        longRunnable?.let(handler::removeCallbacks)
        longRunnable = null
        singleRunnable?.let(handler::removeCallbacks)
        singleRunnable = null
    }

    companion object {
        /** 记录手势判定时间，便于排查长按/双击是否误判。 */
        fun logGesture(tag: String, message: String) {
            HookLog.d(tag, "$message @${SystemClock.uptimeMillis()}")
        }
    }
}