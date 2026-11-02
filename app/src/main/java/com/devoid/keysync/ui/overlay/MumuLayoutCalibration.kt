package com.devoid.keysync.ui.overlay

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.devoid.keysync.R
import com.devoid.keysync.domain.touch.*
import kotlin.math.roundToInt

@Composable
fun MumuLayoutCalibration(
    profile: MumuProfile,
    positions: Map<MumuLayoutTarget, TouchPoint>,
    saving: Boolean,
    onMove: (MumuLayoutTarget, TouchPoint) -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit
) {
    val handles = remember(profile) { MumuLayoutEditor.handles(profile) }
    var selectedIndex by remember(profile) { mutableIntStateOf(0) }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val width = constraints.maxWidth - 1.0
        val height = constraints.maxHeight - 1.0
        handles.forEachIndexed { index, handle ->
            val point by rememberUpdatedState(positions[handle.target] ?: handle.point)
            val selected = selectedIndex == index
            val diameter = with(LocalDensity.current) {
                handle.radius?.let { (it * minOf(width + 1, height + 1) * 2).toFloat().toDp() } ?: 64.dp
            }
            val halfSize = with(LocalDensity.current) { diameter.toPx() / 2 }
            val color = if (selected) Color.Cyan else Color.White.copy(alpha = .65f)
            Box(
                Modifier.zIndex(if (selected) 1f else 0f)
                    .offset { IntOffset((point.x * width - halfSize).roundToInt(), (point.y * height - halfSize).roundToInt()) }
                    .size(diameter)
                    .background(Color.Black.copy(alpha = .35f), CircleShape)
                    .border(1.5.dp, color, CircleShape)
                    .clickable(enabled = !saving) { selectedIndex = index }
                    .pointerInput(handle.target, saving) {
                        if (!saving) detectDragGestures(onDragStart = { selectedIndex = index }) { change, drag ->
                            change.consume()
                            onMove(handle.target, TouchPoint(
                                (point.x + drag.x / width).coerceIn(0.0, 1.0),
                                (point.y + drag.y / height).coerceIn(0.0, 1.0)
                            ))
                        }
                    }
            ) {
                Canvas(Modifier.fillMaxSize()) {
                    val arm = 7.dp.toPx()
                    drawLine(color, center - Offset(arm, 0f), center + Offset(arm, 0f), 1.dp.toPx())
                    drawLine(color, center - Offset(0f, arm), center + Offset(0f, arm), 1.dp.toPx())
                }
                Text(
                    text = if (handle.radius != null) "WASD" else keyLabel(handle.key),
                    color = color,
                    fontSize = 12.sp,
                    modifier = Modifier.align(Alignment.TopCenter).padding(top = 8.dp)
                )
            }
        }
        Surface(
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 12.dp).zIndex(2f),
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surface.copy(alpha = .96f)
        ) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(stringResource(R.string.mumu_layout_hint), style = MaterialTheme.typography.bodySmall)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(enabled = !saving, onClick = { selectedIndex = (selectedIndex + handles.size - 1) % handles.size }) { Text("‹") }
                    val handle = handles[selectedIndex]
                    Column(Modifier.widthIn(min = 120.dp, max = 240.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("${keyLabel(handle.key)} ${handle.description}", textAlign = TextAlign.Center)
                        Text(stringResource(when (handle.target.field) {
                            MumuLayoutField.WORK -> R.string.mumu_layout_touch
                            MumuLayoutField.ICON -> R.string.mumu_layout_icon
                            MumuLayoutField.PRESS -> R.string.mumu_layout_macro_press
                            MumuLayoutField.RELEASE -> R.string.mumu_layout_macro_release
                        }), style = MaterialTheme.typography.labelSmall)
                    }
                    TextButton(enabled = !saving, onClick = { selectedIndex = (selectedIndex + 1) % handles.size }) { Text("›") }
                    TextButton(enabled = !saving, onClick = onCancel) { Text(stringResource(R.string.cancel)) }
                    TextButton(enabled = !saving, onClick = onSave) { Text(stringResource(R.string.save)) }
                }
            }
        }
    }
}
