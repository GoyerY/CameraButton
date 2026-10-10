package com.goyer.camerabutton.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.goyer.camerabutton.data.SettingsRepository
import com.goyer.camerabutton.model.ActionType
import com.goyer.camerabutton.model.CameraKeySettings

/**
 * 设置界面。
 *
 * 所有改动即时写入并同步到 system_server，无需重启应用；
 * 首次启用模块或调整 LSPosed 作用域后需重启系统框架。
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                SettingsScreen()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsScreen() {
    val context = androidx.compose.ui.platform.LocalContext.current
    var settings by remember { mutableStateOf(SettingsRepository.load(context)) }

    fun persist(next: CameraKeySettings) {
        settings = next
        SettingsRepository.save(context, next)
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("相机实体键定制") }) }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            SettingSwitch(
                title = "启用自定义相机键",
                subtitle = "关闭后模块不再拦截按键，系统原有行为恢复",
                checked = settings.enabled,
                onChange = { persist(settings.copy(enabled = it)) }
            )

            HorizontalDivider()

            SettingSwitch(
                title = "接管系统原有行为",
                subtitle = "开启后由模块决定按键行为；关闭则只读取不拦截",
                checked = settings.interceptSystemAction,
                onChange = { persist(settings.copy(interceptSystemAction = it)) }
            )

            SettingSwitch(
                title = "触发时振动",
                checked = settings.vibrationEnabled,
                onChange = { persist(settings.copy(vibrationEnabled = it)) }
            )

            HorizontalDivider()

            ActionSelector(
                title = "短按动作",
                action = settings.singleAction,
                onSelect = { persist(settings.copy(singleAction = it)) }
            )

            ActionSelector(
                title = "长按动作",
                action = settings.longAction,
                onSelect = { persist(settings.copy(longAction = it)) }
            )

            ActionSelector(
                title = "双击动作",
                subtitle = "设为「不执行操作」时，短按在松开后立即触发",
                action = settings.doubleAction,
                onSelect = { persist(settings.copy(doubleAction = it)) }
            )

            HorizontalDivider()

            SliderSetting(
                title = "长按判定时长",
                value = settings.longPressMs.toFloat(),
                range = 200f..2000f,
                steps = 17,
                valueLabel = "${settings.longPressMs} ms",
                onChange = { persist(settings.copy(longPressMs = it.toLong())) }
            )

            SliderSetting(
                title = "双击等待窗口",
                value = settings.doubleClickWindowMs.toFloat(),
                range = 150f..1000f,
                steps = 16,
                valueLabel = "${settings.doubleClickWindowMs} ms",
                onChange = { persist(settings.copy(doubleClickWindowMs = it.toLong())) }
            )

            HorizontalDivider()

            Text(
                text = "说明：短按默认打开相机，长按默认截屏。修改后立即生效；" +
                    "若按键仍走系统原有行为，请在 LSPosed 中确认作用域包含" +
                    "「Android 系统框架」，并重启系统框架。",
                style = MaterialTheme.typography.bodySmall
            )

            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun SettingSwitch(
    title: String,
    subtitle: String? = null,
    checked: Boolean,
    onChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = checked, onClick = { onChange(!checked) })
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun ActionSelector(
    title: String,
    subtitle: String? = null,
    action: ActionType,
    onSelect: (ActionType) -> Unit
) {
    Column {
        Text(title, style = MaterialTheme.typography.titleMedium)
        if (subtitle != null) {
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.height(6.dp))
        Card(modifier = Modifier.fillMaxWidth()) {
            Column {
                SELECTABLE_ACTIONS.forEach { option ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(
                                selected = option == action,
                                onClick = { onSelect(option) }
                            )
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = option == action, onClick = { onSelect(option) })
                        Spacer(Modifier.height(0.dp))
                        Text(labelOf(option), modifier = Modifier.padding(start = 8.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun SliderSetting(
    title: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    valueLabel: String,
    onChange: (Float) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(valueLabel, style = MaterialTheme.typography.bodyMedium)
        }
        Slider(
            value = value.coerceIn(range),
            onValueChange = onChange,
            valueRange = range,
            steps = steps
        )
    }
}

/** 设置界面中列出的动作。 */
private val SELECTABLE_ACTIONS = listOf(
    ActionType.NONE,
    ActionType.CAMERA,
    ActionType.CAMERA_SELFIE,
    ActionType.CAMERA_VIDEO,
    ActionType.SCREENSHOT,
    ActionType.SCREEN_RECORD_TOGGLE,
    ActionType.FLASHLIGHT_TOGGLE,
    ActionType.RINGER_MODE_CYCLE,
    ActionType.DND_TOGGLE,
    ActionType.VOLUME_UP,
    ActionType.VOLUME_DOWN,
    ActionType.MUTE_TOGGLE,
    ActionType.WECHAT,
    ActionType.ALIPAY,
    ActionType.LAUNCH_APP,
    ActionType.URL,
    ActionType.SHELL
)

private fun labelOf(action: ActionType): String = when (action) {
    ActionType.NONE -> "不执行操作"
    ActionType.CAMERA -> "打开相机"
    ActionType.CAMERA_SELFIE -> "打开相机（前置）"
    ActionType.CAMERA_VIDEO -> "打开相机（录像）"
    ActionType.FLASHLIGHT_TOGGLE -> "手电筒"
    ActionType.SCREENSHOT -> "截屏"
    ActionType.SCREEN_RECORD_TOGGLE -> "屏幕录制"
    ActionType.RINGER_MODE_CYCLE -> "响铃/振动/静音循环"
    ActionType.DND_TOGGLE -> "免打扰开关"
    ActionType.VOLUME_UP -> "音量加"
    ActionType.VOLUME_DOWN -> "音量减"
    ActionType.MUTE_TOGGLE -> "静音切换"
    ActionType.RECORDER_TOGGLE -> "录音机"
    ActionType.WECHAT -> "微信"
    ActionType.ALIPAY -> "支付宝"
    ActionType.POWER_TOGGLE -> "电源键"
    ActionType.NOTIFICATION_TOGGLE -> "通知栏"
    ActionType.QUICK_SETTINGS -> "快捷设置"
    ActionType.HOME -> "主页"
    ActionType.BACK -> "返回"
    ActionType.RECENTS -> "多任务"
    ActionType.SLEEP -> "息屏"
    ActionType.WAKEUP -> "亮屏"
    ActionType.LAUNCH_APP -> "启动应用"
    ActionType.URL -> "打开链接"
    ActionType.SHELL -> "Shell 指令"
}