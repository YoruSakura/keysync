package com.devoid.keysync.ui.overlay

import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.devoid.keysync.R
import com.devoid.keysync.domain.KEYCODE_LMC
import com.devoid.keysync.domain.KEYCODE_MMC
import com.devoid.keysync.domain.KEYCODE_RMC
import com.devoid.keysync.domain.touch.MumuIcon
import com.devoid.keysync.domain.touch.MumuProfile
import kotlin.math.roundToInt

@Composable
fun MumuOverlay(
    modifier: Modifier = Modifier,
    profile: MumuProfile,
    isAiming: Boolean
) {
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val width = constraints.maxWidth.toFloat()
        val height = constraints.maxHeight.toFloat()
        profile.joystick?.let { joystick ->
            val diameter = with(LocalDensity.current) {
                (joystick.radius * minOf(width, height) * 2).toFloat().toDp()
            }
            MumuKeyMarker(
                icon = joystick.icon,
                width = width,
                height = height,
                label = "${keyLabel(joystick.up)}\n${keyLabel(joystick.left)}   ${keyLabel(joystick.right)}\n${keyLabel(joystick.down)}",
                color = Color(0xFF90CAF9),
                size = diameter
            )
        }
        profile.buttons.forEach { button ->
            MumuKeyMarker(
                icon = button.icon,
                width = width,
                height = height,
                label = keyLabel(button.key),
                color = if (button.fire) Color(0xFFFFCC80) else Color.White
            )
        }
        profile.macros.forEach { macro ->
            MumuKeyMarker(
                icon = macro.icon,
                width = width,
                height = height,
                label = keyLabel(macro.key),
                color = Color(0xFFCE93D8)
            )
        }
        profile.tips.forEach { tip ->
            MumuKeyMarker(
                icon = tip.icon,
                width = width,
                height = height,
                label = keyLabel(tip.key),
                color = Color(0xFF80CBC4)
            )
        }
        profile.camera?.let { camera ->
            val modeColor = if (isAiming) Color(0xFF80CBC4) else Color.White
            MumuKeyMarker(
                icon = camera.icon,
                width = width,
                height = height,
                label = keyLabel(camera.key),
                description = if (camera.icon.description.isBlank()) {
                    stringResource(R.string.mapping_aiming_mode)
                } else camera.icon.description,
                color = modeColor
            )
            Text(
                text = stringResource(
                    if (isAiming) R.string.mumu_aiming_mode_hint else R.string.mumu_cursor_mode_hint,
                    keyLabel(camera.key)
                ),
                color = modeColor,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 12.dp)
                    .background(Color.Black.copy(alpha = .85f), RoundedCornerShape(8.dp))
                    .border(1.dp, modeColor, RoundedCornerShape(8.dp))
                    .padding(horizontal = 14.dp, vertical = 8.dp)
            )
        }
    }
}

@Composable
private fun MumuKeyMarker(
    icon: MumuIcon,
    width: Float,
    height: Float,
    label: String,
    color: Color,
    description: String = icon.description,
    size: Dp = 64.dp
) {
    if (!icon.visible) return
    val scaledSize = size * icon.scale.toFloat()
    val halfSize = with(LocalDensity.current) { scaledSize.toPx() / 2f }
    Box(
        modifier = Modifier
            .offset {
                IntOffset(
                    (icon.point.x * width - halfSize).roundToInt(),
                    (icon.point.y * height - halfSize).roundToInt()
                )
            }
            .size(scaledSize)
            .background(Color.Black.copy(alpha = .85f), CircleShape)
            .border(1.5.dp, color, CircleShape)
            .padding(4.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = label,
                color = color,
                fontSize = 12.sp,
                lineHeight = 14.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
            if (description.isNotBlank()) {
                Text(
                    text = description,
                    color = Color.White,
                    fontSize = 9.sp,
                    lineHeight = 11.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

@Composable
private fun keyLabel(key: Int): String = when (key) {
    KEYCODE_LMC -> stringResource(R.string.left_mouse_button)
    KEYCODE_RMC -> stringResource(R.string.right_mouse_button)
    KEYCODE_MMC -> stringResource(R.string.middle_mouse_button)
    KeyEvent.KEYCODE_ALT_LEFT -> "Alt"
    KeyEvent.KEYCODE_SHIFT_LEFT -> "Shift"
    KeyEvent.KEYCODE_CTRL_LEFT -> "Ctrl"
    KeyEvent.KEYCODE_ESCAPE -> "Esc"
    KeyEvent.KEYCODE_SPACE -> "Space"
    KeyEvent.KEYCODE_TAB -> "Tab"
    KeyEvent.KEYCODE_ENTER -> "Enter"
    KeyEvent.KEYCODE_GRAVE -> "`"
    else -> KeyEvent.keyCodeToString(key).removePrefix("KEYCODE_")
}
