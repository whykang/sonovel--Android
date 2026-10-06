package com.wang.sonovel.ui.components

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.wang.sonovel.data.AppSettings
import com.wang.sonovel.ui.theme.DefaultSeedColor
import com.wang.sonovel.ui.theme.parseSeedColor

/** 预设主题色 */
val ThemePresets: List<Pair<String, String>> = listOf(
    "翠绿" to "#2E7D32",
    "青色" to "#00838F",
    "天蓝" to "#0277BD",
    "靛蓝" to "#3949AB",
    "紫色" to "#7B1FA2",
    "粉色" to "#C2185B",
    "红色" to "#C62828",
    "橙色" to "#EF6C00",
    "琥珀" to "#F9A825",
    "棕色" to "#6D4C41",
    "蓝灰" to "#546E7A",
)

private fun Color.toHex(): String = "#%06X".format(toArgb() and 0xFFFFFF)

/** 当前主题色的名称，用于设置项的说明文字 */
fun themeColorLabel(s: AppSettings): String {
    val seed = s.themeSeed
    return when {
        seed != null -> ThemePresets.firstOrNull { it.second.equals(seed, true) }?.first ?: "自定义 ${seed.uppercase()}"
        s.dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> "跟随壁纸"
        else -> "默认绿"
    }
}

/**
 * 主题色选择：跟随壁纸（Android 12+）/ 默认绿 / 预设色 / 自定义。选择后立即生效。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ThemeColorDialog(
    settings: AppSettings,
    onChange: (dynamic: Boolean, seed: String?) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val supportsWallpaper = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val seed = settings.themeSeed
    val usingWallpaper = seed == null && settings.dynamicColor && supportsWallpaper
    val usingDefault = seed == null && !usingWallpaper
    val isCustom = seed != null && ThemePresets.none { it.second.equals(seed, true) }

    // 自定义：色相滑块 + 十六进制输入
    val seedColor = parseSeedColor(seed)
    var hue by remember {
        mutableFloatStateOf(
            seedColor?.let { c ->
                FloatArray(3).also { android.graphics.Color.colorToHSV(c.toArgb(), it) }[0]
            } ?: 160f
        )
    }
    var hexInput by remember { mutableStateOf(seed?.removePrefix("#")?.uppercase().orEmpty()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("主题色") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (supportsWallpaper) {
                        val wallpaper = remember { dynamicLightColorScheme(context).primary }
                        Swatch("壁纸", wallpaper, usingWallpaper) { onChange(true, null) }
                    }
                    Swatch("默认绿", DefaultSeedColor, usingDefault) { onChange(false, null) }
                    ThemePresets.forEach { (name, hex) ->
                        val c = parseSeedColor(hex) ?: return@forEach
                        Swatch(name, c, seed.equals(hex, true)) {
                            hexInput = hex.removePrefix("#")
                            onChange(false, hex)
                        }
                    }
                }

                Spacer(Modifier.height(18.dp))
                Text(
                    "自定义" + if (isCustom) "（当前）" else "",
                    style = MaterialTheme.typography.labelLarge,
                    color = if (isCustom) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Box(contentAlignment = Alignment.Center) {
                    // 彩虹色带作为滑块背景
                    Box(
                        Modifier.fillMaxWidth().height(14.dp).clip(RoundedCornerShape(50)).background(
                            Brush.horizontalGradient(List(13) { Color.hsv(it * 30f % 360f, 0.7f, 0.85f) })
                        )
                    )
                    Slider(
                        value = hue,
                        onValueChange = {
                            hue = it
                            val c = Color.hsv(it.coerceIn(0f, 359.9f), 0.7f, 0.8f)
                            hexInput = c.toHex().removePrefix("#")
                            onChange(false, c.toHex())
                        },
                        valueRange = 0f..360f,
                        colors = SliderDefaults.colors(
                            activeTrackColor = Color.Transparent,
                            inactiveTrackColor = Color.Transparent,
                            thumbColor = MaterialTheme.colorScheme.primary,
                        ),
                    )
                }
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val valid = parseSeedColor(hexInput) != null
                    OutlinedTextField(
                        value = hexInput,
                        onValueChange = { v ->
                            val t = v.removePrefix("#").filter { it.isLetterOrDigit() }.take(6).uppercase()
                            hexInput = t
                            parseSeedColor(t)?.let { onChange(false, "#$t") }
                        },
                        label = { Text("颜色代码") },
                        prefix = { Text("#") },
                        singleLine = true,
                        isError = hexInput.isNotEmpty() && !valid,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(12.dp))
                    Box(
                        Modifier.size(44.dp).clip(CircleShape)
                            .background(parseSeedColor(hexInput) ?: MaterialTheme.colorScheme.surfaceVariant)
                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
                    )
                }
                Text(
                    "应用会根据所选颜色自动生成整套浅色 / 深色配色",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 10.dp),
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("完成") } },
    )
}

@Composable
private fun Swatch(label: String, color: Color, selected: Boolean, onClick: () -> Unit) {
    Column(
        Modifier.width(54.dp).clip(RoundedCornerShape(10.dp)).clickable(onClick = onClick).padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.size(40.dp).clip(CircleShape).background(color)
                .then(if (selected) Modifier.border(3.dp, MaterialTheme.colorScheme.onSurface, CircleShape) else Modifier),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) {
                Icon(Icons.Outlined.Check, null, Modifier.size(22.dp), tint = if (color.luminance() > 0.5f) Color.Black else Color.White)
            }
        }
        Text(
            label, style = MaterialTheme.typography.labelSmall, maxLines = 1, textAlign = TextAlign.Center,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}
