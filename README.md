# 相机实体键定制（CameraButton）

为 OPPO / 一加 机型的**相机实体键（ColorOS 松脆键 SpruceKey）**自定义短按、长按、双击动作的 LSPosed 模块。

基于 [SlimeNull/OppoCustomSideButtonFunctions](https://github.com/SlimeNull/OppoCustomSideButtonFunctions) 的思路重构，针对 ColorOS 16（Android 17）上实测的松脆键协议重新实现。

## 实机适配信息

模块在以下环境上完成协议探测与适配：

| 项目 | 值 |
| --- | --- |
| 机型 | OPPO PMA110（Find X9 Ultra） |
| 系统 | ColorOS 16，Android 17 / SDK 37 |
| Root | KernelSU |
| Xposed | LSPosed v2.2.1 + Zygisk Next |

## 为什么要重写

参考模块挂钩的是 `com.android.server.policy.StrategyActionButtonKeyLaunchApp`（一加侧键）。

在 ColorOS 16 上实测：

- `getevent -pl` 枚举全部 13 个输入节点，**没有任何节点上报 `KEY_CAMERA`（0x2f0）**
- `services.jar` 中**不存在** `StrategyActionButtonKeyLaunchApp` 类
- 相机实体键走的是另一套体系：**松脆键 SpruceKey**

因此模块改为挂钩 `android.os.OplusKeyEventManager`，该类位于 `/system/framework/oplus-framework.jar`，跨版本稳定；ColorOS 自身的分发类经过混淆，系统升级即失效，不适合作为挂载点。

## 松脆键按键协议

通过对 `/system_ext/app/OplusGestureUI/OplusGestureUI.apk` 反编译，定位到 logcat TAG 为 `GestureKeyEventManager` 的分发类，其 `onKeyEvent` 按 keyCode 分发：

| keyCode | 含义 |
| --- | --- |
| 769 | 松脆键动作触发（核心） |
| 773 | 息屏松脆键 |
| 765~768 | 松脆键手势组 |
| 781 | 截屏键 |
| 782 / 783 | 快捷键按下 / 抬起 |

观察者通过 `OplusKeyEventManager.getInstance().registerKeyEventObserver(...)` 注册，需带上松脆键掩码 `33554447`。

相关系统设置键（实机读取）：

```
secure: spruce_start_camera_name        = com.oplus.camera
secure: oplus_camera_double_click_spruce = 1
secure: oplus_camera_spruce_strength_config = 2
global: spruce_double_click_count       = 2
```

## 功能

- **短按 / 长按 / 双击**分别绑定动作
- 双击未配置时，短按在松开瞬间立即触发（无额外延迟）
- 长按阈值 200~2000ms、双击窗口 150~1000ms 可调
- 动作类型：相机（前置/录像）、截屏、屏幕录制、手电筒、响铃模式循环、免打扰、音量、微信/支付宝快捷入口、启动应用、打开链接、Shell 指令
- 设置改动即时生效（热加载），无需重启应用

## 安装

1. 安装 APK，在 LSPosed 中启用模块，将 **Android 系统框架（`android`）** 加入作用域
2. 重启设备使系统框架加载模块
3. 打开「相机实体键定制」配置动作

## 构建

需要 Android SDK 与项目 Gradle Wrapper：

```powershell
.\gradlew.bat :app:assembleDebug
.\gradlew.bat :app:assembleRelease
```

产物位于 `app/build/outputs/apk/`。

## 已知限制

- 截屏走广播调用，ColorOS 上可能需要系统内部权限才能真正截屏
- `LAUNCH_APP` / `URL` / `SHELL` 等动作依赖 root 或系统权限，视 ROM 而定
- 若系统升级后松脆键 keyCode 变化，需重新探测（见 `SPEC.md`）

## 许可证

LGPL-3.0