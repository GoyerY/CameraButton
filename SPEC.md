# CameraButton 模块技术规格（基于实机探测）

> 探测时间：2026-10-10
> 设备：OPPO PMA110（Find X9 Ultra），Android 17 / SDK 37，ColorOS 16
> Root：KernelSU (uid=0, context=u:r:ksu:s0)
> Xposed：LSPosed v2.2.1 (7912)，Zygisk Next

## 1. 关键结论：不是普通侧键，是「松脆键 SpruceKey」

参考仓库针对的是**一加/OPPO 侧边快捷键**（`StrategyActionButtonKeyLaunchApp`）。
本机**不存在**该类，ColorOS 16 已改为松脆键体系。

实机证据：
- `getevent -pl` 全量 13 个输入节点，**无任何节点上报 `KEY_CAMERA(0x2f0)`**
- `/system/usr/keylayout/gpio-keys.kl` 里有 `key 766 CAMERA`，但无设备实际使用
- 系统属性 `ro.oplus.key.actionbutton=1`
- 设置项 `oplus_action_button_switch_state=nothing`
- 前台设置页 `com.oplus.gesture/.sprucekey.SpruceSettingsActivity`

## 2. 松脆键按键协议（反编译 OplusGestureUI.apk 得出）

核心分发类：`com.oplus.gesture` 内 `lb.p`（logcat TAG = `GestureKeyEventManager`）
注册方式：`android.os.OplusKeyEventManager.getInstance().registerKeyEventObserver(...)`

### 2.1 观察者掩码
```java
int i10 = GestureUtil.isSupportSpruceKey() ? 7 | 0x1FFFFFF : 7;   // 33554447 = 0x1FFFFFF
if (GestureUtil.isSupportActionButtonKey()) i10 |= 34340880;      // 0x20C0BD0
if (GestureUtil.isSupportGameHyperKey())    i10 |= 134217728;     // 0x8000000
```

### 2.2 keyCode 分发表（`onKeyEvent`，只处理 ACTION_DOWN && repeatCount==0）
| keyCode | 含义 | 行为 |
|---|---|---|
| 670 | KEYCODE_SPRUCE? | 走 `start SpruceKeyService` 分支（keyCode!=670 判断的 else 路径） |
| 671 | lingxikey | 灵犀键 → com.oplus.games |
| 673 | Hyper 键 | 息屏启动 Hyper |
| 773 | 息屏松脆键 | `w(q())` → 启动目标应用 |
| 24/25/26 | 音量上/下/静音 | 光效 AuraAnim |
| 765/766/767/768 | 松脆键手势组 | `break`（由服务端/framework 侧预先判定，落到这里仅更新动效） |
| **769** | **松脆键动作触发** | `r(q(), "com.oplus.camera", "sprucekey", ...)` → 执行启动 |
| 781 | 截屏键 | `w(true)` 截屏 |
| 782 | 快捷键 ACTION_DOWN | 亮屏/唤醒分支 |
| 783 | 快捷键 ACTION_UP | `w(false)` 收尾 |

> 注意：769 的处理走 `r(...)`（`handleKeyClickEvent`），769 是本模块要拦截的核心 keyCode。

### 2.3 执行链
```
keyCode 769 (ACTION_DOWN)
  → lb.p.r(pkgName, "com.oplus.camera", "sprucekey", consumer)
      → za.g.c(ctx).f("android_spruce_key_launch_type", launchIntent, pkgName)
          → za.g.d(): 拼 extra
              android_camera_launch_type = "android_spruce_key_launch_type"
                                       或 "spruce_double_tap_launch_from_framework"（双击态）
              com.oplus.camera.spruce = 13(息屏) / 14(锁屏) / 15(正常)
              action = android.media.action.STILL_IMAGE_CAMERA[_SECURE]
          → pa.i.m(...) 真正启动
      → za.g.c(ctx).b()  累加双击计数（mDoubleClickCount，最高 3）
```

## 3. 相关设置键（实机读取）

### global
- `spruce_double_click_count=2`
- `need_launch_spruce_notice=0`
- `oplus_camera_continue_page_state=100`

### secure
- `oplus_camera_single_click_spruce=0`（单击动作类型）
- `oplus_camera_double_click_spruce=1`（双击使能）
- `oplus_camera_anti_mistouch_spruce=1`（防误触）
- `oplus_camera_light_press_spruce=0`（轻压）
- `oplus_camera_slide_spruce=0`（滑动手势）
- `oplus_camera_focus_adjust_type_spruce=1`
- `oplus_camera_spruce_strength_config=2`（触发强度）
- `spruce_start_camera_name=com.oplus.camera`（目标应用）
- `spruce_support_launch_camera=1`

## 4. 结论：三条实现路线

| 路线 | 做法 | 评价 |
|---|---|---|
| A. hook `OplusKeyEventManager` | 在 system_server 注册自定义 `OnKeyEventObserver`，拦截 765~769，自行判定短按/长按/双击 | **推荐**。拿到原始事件，不与系统状态机打架 |
| B. 改写 Settings | 直接改 `oplus_camera_*_spruce` 系列设置 | 能力受限（只有单击/双击/轻压/滑动），无法支持长按自定义动作 |
| C. hook `lb.p.onKeyEvent` | 直接 hook ColorOS 分发类 | 类名混淆（`lb.p`），升级即失效 |

**选定路线 A**：hook `android.os.OplusKeyEventManager`（framework 公开类，跨版本稳定），
在其观察者链中插入自定义逻辑，用独立手势状态机实现短按/长按/双击。

## 5. 手势判定参数
- 长按阈值：默认 500ms（可调 200~2000）
- 双击窗口：默认 300ms（可调 150~1000）
- 事件来源：`KeyEvent` DOWN/UP，`getRepeatCount()` 过滤长按重复

## 6. 模块挂载点（路线 A 实现细节）

### 6.1 类定位
`android.os.OplusKeyEventManager` 与内部接口 `android.os.OplusKeyEventManager$OnKeyEventObserver`
位于 `/system/framework/oplus-framework.jar`（实测 grep 命中）。

```
oplus-framework.jar:  OplusKeyEventManager=2, OnKeyEventObserver=2, registerKeyEventObserver=6
framework.jar:        OplusKeyEventManager=1, OnKeyEventObserver=0, registerKeyEventObserver=0
services.jar:         全部为 0
oplus-services.jar:   OplusKeyEventManager=7, OnKeyEventObserver=3, registerKeyEventObserver=4
```

结论：观察者接口在 oplus-framework.jar，system_server 启动时可直接反射加载。

### 6.2 注册方式
```java
OplusKeyEventManager.getInstance()
    .registerKeyEventObserver(context, observer, mask);
```
`mask` 需含松脆键位 `33554447`（`7 | 0x1FFFFFF`），否则收不到 769 事件。
模块额外并上 `0x20C0BD0`（Action Button）与 `0x8000000`（灵犀/Hyper），
以覆盖组合键场景。

### 6.3 实现方式
`OnKeyEventObserver` 是接口，用 `java.lang.reflect.Proxy` 动态实现，
避免编译期依赖 oplus-framework.jar（该 jar 不在公开 SDK 中）。

### 6.4 Context 获取
system_server 中 `ActivityThread.currentApplication()` 返回 null，
因此 Context 从 `PolicyManager` / `ActivityThread.systemMain()` 侧获取，
失败时降级为 null 并在日志中标注，动作执行会被跳过而不崩溃。

## 7. 复现探测的命令

```bash
# 输入节点与按键能力
adb shell su -c "getevent -pl" | grep -E "add device|name:|KEY_|ABS_"

# 松脆键相关设置
adb shell su -c "settings list secure" | grep -i spruce
adb shell su -c "settings list global" | grep -i spruce

# hook 目标类是否存在于哪个 jar
adb shell su -c "grep -c OnKeyEventObserver /system/framework/oplus-framework.jar"

# 反编译分析
jadx -d out --no-res /system_ext/app/OplusGestureUI/OplusGestureUI.apk
```