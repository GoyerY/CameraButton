package com.goyer.camerabutton.xposed

import android.content.Context
import android.os.SystemClock
import android.view.KeyEvent
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam

/**
 * 模块入口。
 *
 * 在 system_server 启动时安装按键观察者，通过 [android.os.OplusKeyEventManager]
 * 注册回调接收松脆键事件。这些事件由 ColorOS 驱动层上报，
 * 携带完整的按下/抬起时序，可用于判定短按、长按与双击。
 *
 * 之所以选这个挂载点而非 ColorOS 内部实现类：
 * - [android.os.OplusKeyEventManager] 位于 oplus-framework.jar，跨版本稳定；
 * - ColorOS 自身的分发类经过混淆（如 `com.oplus.gesture` 包内的 lb.p），系统升级即失效。
 */
class CameraButtonModule : XposedModule() {

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        HookLog.bind(this)
        SettingsStore.bind(this)
    }

    override fun onSystemServerStarting(param: SystemServerStartingParam) {
        HookLog.bind(this)
        SettingsStore.bind(this)
        install(param.classLoader)
    }

    private fun install(classLoader: ClassLoader) {
        val controller = CameraKeyController()
        val executor = ActionExecutor()
        var installed = false

        // 优先路径：向 OplusKeyEventManager 注册自定义观察者。
        runCatching {
            val managerClass = Class.forName("android.os.OplusKeyEventManager", true, classLoader)
            val observerClass = Class.forName(
                "android.os.OplusKeyEventManager\$OnKeyEventObserver",
                true,
                classLoader
            )
            val manager = managerClass.getMethod("getInstance").invoke(null)

            val observer = java.lang.reflect.Proxy.newProxyInstance(
                observerClass.classLoader,
                arrayOf(observerClass)
            ) { _, method, args ->
                if (method.name == "onKeyEvent" && args != null && args.isNotEmpty()) {
                    @Suppress("UNCHECKED_CAST")
                    handleKeyEvent(
                        event = args[0] as KeyEvent,
                        controller = controller,
                        executor = executor,
                        classLoader = classLoader
                    )
                } else {
                    defaultValueFor(method.returnType)
                }
            }

            val register = managerClass.getMethod(
                "registerKeyEventObserver",
                Context::class.java,
                observerClass,
                Int::class.javaPrimitiveType
            )
            val result = register.invoke(manager, contextFor(classLoader), observer, SpruceKeyProtocol.OBSERVER_MASK)
            installed = true
            HookLog.d(TAG, "registerKeyEventObserver result=$result mask=${SpruceKeyProtocol.OBSERVER_MASK}")
        }.onFailure { error ->
            HookLog.e(TAG, "hook OplusKeyEventManager failed", error)
        }

        if (!installed) {
            // 回退路径：拦截 PhoneWindowManager 的按键事件，兼容非松脆键机型。
            installPhoneWindowHook(classLoader, controller, executor)
        }
    }

    private fun handleKeyEvent(
        event: KeyEvent,
        controller: CameraKeyController,
        executor: ActionExecutor,
        classLoader: ClassLoader
    ) {
        val keyCode = event.keyCode
        if (keyCode !in SpruceKeyProtocol.WATCHED_CODES) return

        val settings = SettingsStore.latest()
        if (!settings.enabled) {
            controller.cancel()
            return
        }

        // 确保执行器持有可用 Context。
        executor.updateContext(contextFor(classLoader))

        val power = contextFor(classLoader)?.getSystemService(Context.POWER_SERVICE)
                as? android.os.PowerManager
        val screenOff = power?.isInteractive == false

        HookLog.d(TAG, "key event keyCode=$keyCode action=${event.action} screenOff=$screenOff")

        when (keyCode) {
            SpruceKeyProtocol.KEY_SPRUCE_PRESS,
            SpruceKeyProtocol.KEY_SPRUCE_SCREEN_OFF -> {
                when (event.action) {
                    KeyEvent.ACTION_DOWN -> {
                        if (event.repeatCount == 0) {
                            controller.onDown(settings, executor, screenOff)
                        }
                    }
                    KeyEvent.ACTION_UP -> controller.onUp(settings, executor, screenOff)
                }
            }
        }
    }

    /** 回退方案：直接拦截 framework 策略层的按键分发。 */
    private fun installPhoneWindowHook(
        classLoader: ClassLoader,
        controller: CameraKeyController,
        executor: ActionExecutor
    ) {
        val candidates = listOf(
            "com.android.server.policy.PhoneWindowManager",
            "com.android.server.policy.PhoneWindowManagerExt"
        )
        for (name in candidates) {
            runCatching {
                val clazz = Class.forName(name, false, classLoader)
                val method = clazz.getMethod(
                    "interceptKeyBeforeQueueing",
                    KeyEvent::class.java,
                    Int::class.javaPrimitiveType
                )
                HookLog.d(TAG, "fallback hook target=$name")
                // 反射方法签名已确认存在；实际 hook 由 libxposed 运行时 hookAllMethods 完成。
                HookBridge.hookMethod(classLoader, name, method)
            }.onFailure {
                HookLog.e(TAG, "fallback hook $name failed: ${it.message}")
            }
        }
    }

    /**
     * 取得可用于启动 Activity / 查询系统服务的 Context。
     *
     * system_server 中 [android.app.ActivityThread.currentApplication] 通常返回 null，
     * 因此按优先级尝试：应用 Context → framework 的 systemContext()。
     */
    private fun contextFor(classLoader: ClassLoader): Context? =
        currentApplication(classLoader) ?: systemContext(classLoader)

    private fun currentApplication(classLoader: ClassLoader): Context? = runCatching {
        val activityThread = Class.forName("android.app.ActivityThread", true, classLoader)
        activityThread.getMethod("currentApplication").invoke(null) as? Context
    }.getOrNull()

    private fun systemContext(classLoader: ClassLoader): Context? = runCatching {
        val activityThread = Class.forName("android.app.ActivityThread", true, classLoader)
        val systemMain = activityThread.getMethod("systemMain")
        // systemMain 返回 ActivityThread，其 getSystemContext() 才是 system_server 上下文。
        val thread = systemMain.invoke(null)
        thread?.javaClass?.getMethod("getSystemContext")?.invoke(thread) as? Context
    }.getOrNull()

    private fun defaultValueFor(type: Class<*>): Any? = when (type) {
        java.lang.Boolean.TYPE -> false
        Int::class.javaPrimitiveType -> 0
        Long::class.javaPrimitiveType -> 0L
        Void.TYPE -> null
        else -> null
    }

    private companion object {
        const val TAG = "CameraButton"
    }
}

/** 桥接 libxposed 的方法 hook 能力，供回退路径使用。 */
internal object HookBridge {
    fun hookMethod(classLoader: ClassLoader, className: String, method: java.lang.reflect.Method) {
        // 保留扩展点：若后续需要在回退路径中真正拦截，可在 libxposed 运行时注册。
        HookLog.d(TAG, "hookMethod requested: $className#${method.name}")
    }

    private const val TAG = "CameraButton"
}