package com.devoid.keysync.domain.touch

import android.view.KeyEvent
import com.devoid.keysync.domain.KEYCODE_LMC
import com.devoid.keysync.domain.KEYCODE_MMC
import com.devoid.keysync.domain.KEYCODE_RMC
import kotlinx.serialization.json.*
import java.io.InputStream
import java.io.ByteArrayOutputStream

data class MumuIcon(val point: TouchPoint, val description: String, val visible: Boolean, val scale: Double,
                    val mappingIndex: Int)
data class MumuTip(val key: Int, val icon: MumuIcon)
data class MumuButton(val key: Int, val point: TouchPoint, val fire: Boolean, val metadata: JsonObject, val icon: MumuIcon)
data class MumuCamera(val key: Int, val anchor: TouchPoint, val width: Double, val height: Double,
                      val sensitivityX: Double, val sensitivityY: Double, val reset: Boolean, val icon: MumuIcon)
data class MumuJoystick(val center: TouchPoint, val radius: Double,
                        val up: Int, val left: Int, val down: Int, val right: Int, val icon: MumuIcon)
data class MumuObserve(val key: Int, val anchor: TouchPoint, val sensitivityX: Double,
                       val sensitivityY: Double, val icon: MumuIcon)
sealed interface MacroAction {
    data class Click(val point: TouchPoint) : MacroAction
    data object ClickCursor : MacroAction
    data class Sleep(val millis: Long) : MacroAction
    data class MouseButton(val pressed: Boolean) : MacroAction
    data class Keyboard(val key: SimulatedKey, val pressed: Boolean) : MacroAction
    data class Curve(val points: List<TouchPoint>, val firstWaitMillis: Long, val holdEnd: Boolean) : MacroAction
    data object ReleaseAll : MacroAction
    data object ExitStaring : MacroAction
}
data class MumuMacro(
    val key: Int,
    val press: List<MacroAction>,
    val release: List<MacroAction>,
    val icon: MumuIcon
)
data class MumuProfile(val source: JsonObject, val buttons: List<MumuButton>, val camera: MumuCamera?,
                       val joystick: MumuJoystick?, val macros: List<MumuMacro>, val tips: List<MumuTip>,
                       val observe: MumuObserve? = null)

/** Strict supported subset, preserves original policy metadata without guessing its timing units. */
object MumuProfileCodec {
    const val MAX_BYTES = 1024 * 1024
    private val relativeClick = Regex("click_rel:\\(([-+0-9.eE]+),\\s*([-+0-9.eE]+)\\)")
    private val curvePoint = Regex("\\(([-+0-9.eE]+),\\s*([-+0-9.eE]+)\\)")
    fun read(input: InputStream): String {
        val bytes = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            require(bytes.size() + count <= MAX_BYTES) { "MuMu profile too large" }
            bytes.write(buffer, 0, count)
        }
        return bytes.toByteArray().toString(Charsets.UTF_8).also(::decode)
    }
    fun decode(text: String): MumuProfile {
        require(text.toByteArray(Charsets.UTF_8).size <= MAX_BYTES)
        val root = Json.parseToJsonElement(text).jsonObject
        require(root["info"]?.jsonObject?.get("is_joystick")?.jsonPrimitive?.booleanOrNull != true) {
            "Gamepad profiles are not touch profiles"
        }
        require(root["param"]?.jsonObject?.get("virtual_joystick")?.jsonObject
            ?.get("enable")?.jsonPrimitive?.booleanOrNull != true)
        val mappings = root.getValue("keymaps").jsonArray
        require(mappings.size in 1..512)
        val buttons = mutableListOf<MumuButton>()
        val macros = mutableListOf<MumuMacro>()
        val tips = mutableListOf<MumuTip>()
        val showIcons = root["info"]?.jsonObject?.get("visibility")?.jsonPrimitive?.boolean ?: true
        var camera: MumuCamera? = null
        var joystick: MumuJoystick? = null
        var observe: MumuObserve? = null
        mappings.forEachIndexed { index, element ->
            val item = element.jsonObject
            val icon = icon(item, showIcons, index)
            when (val type = item.getValue("type").jsonPrimitive.content) {
                "Tip" -> tips += MumuTip(key(item.getValue("key").jsonObject), icon)
                "Click", "Fire" -> {
                    if (type == "Fire") require(item["need_fire_and_drag"]?.jsonPrimitive?.booleanOrNull != true) {
                        "Fire-and-drag is not implemented"
                    }
                    buttons += MumuButton(key(item.getValue("key").jsonObject), point(item.getValue("rel_work_position")), type == "Fire", item, icon)
                }
                "Swipehold" -> {
                    require(joystick == null)
                    joystick = MumuJoystick(point(item.getValue("rel_work_position")),
                        number(item, "rel_radius", .0001..1.0), key(item.getValue("top_key").jsonObject),
                        key(item.getValue("left_key").jsonObject), key(item.getValue("bottom_key").jsonObject),
                        key(item.getValue("right_key").jsonObject), icon)
                    require(listOf(joystick!!.up, joystick!!.left, joystick!!.down, joystick!!.right).distinct().size == 4)
                }
                "Staring" -> {
                    require(camera == null)
                    val range = item.getValue("staring_range").jsonObject
                    val reset = item.getValue("reset_policy").jsonObject
                    require(reset["reset_type"]?.jsonPrimitive?.content == "Range_Window")
                    // No guessed idle timeout. Unsupported auto-reset must be explicit, not silently ignored.
                    require(reset["auto_reset_period"]?.jsonPrimitive?.intOrNull == -1)
                    camera = MumuCamera(key(item.getValue("key").jsonObject), point(item.getValue("rel_work_position")),
                        number(range, "rel_width", .01..1.0), number(range, "rel_height", .01..1.0),
                        number(item, "sensitivity_x", 0.0..500.0), number(item, "sensitivity_y", 0.0..500.0),
                        reset["need_reset"]?.jsonPrimitive?.booleanOrNull ?: false, icon)
                }
                "Observe" -> {
                    require(observe == null && camera == null) { "Only one camera control is supported" }
                    observe = MumuObserve(key(item.getValue("key").jsonObject), point(item.getValue("rel_work_position")),
                        number(item, "sensitivity_x", 0.0..500.0), number(item, "sensitivity_y", 0.0..500.0), icon)
                }
                "Macro" -> macros += MumuMacro(
                    key(item.getValue("key").jsonObject),
                    actions(item["press_actions"]),
                    actions(item["release_actions"]),
                    icon
                )
                else -> error("Unsupported MuMu action: $type")
            }
        }
        require(camera == null || observe == null) { "Only one camera control is supported" }
        val assigned = buttons.map { it.key } + macros.map { it.key } + listOfNotNull(camera?.key, observe?.key) +
            (joystick?.let { listOf(it.up, it.left, it.down, it.right) } ?: emptyList())
        require(assigned.distinct().size == assigned.size) { "Duplicate action bindings" }
        require(buttons.isNotEmpty() || camera != null || observe != null || joystick != null || macros.isNotEmpty())
        return MumuProfile(root, buttons, camera, joystick, macros, tips, observe)
    }
    private fun icon(item: JsonObject, showIcons: Boolean, index: Int): MumuIcon {
        val icon = item["icon"]?.jsonObject
        val scale = item["editor_icon_scale"]?.jsonPrimitive?.double ?: 1.0
        require(scale.isFinite() && scale > 0.0) { "Invalid icon scale" }
        return MumuIcon(
            point(icon?.get("rel_position") ?: item.getValue("rel_work_position")),
            icon?.get("description")?.jsonPrimitive?.content ?: "",
            showIcons && (icon?.get("visibility")?.jsonPrimitive?.boolean ?: true),
            scale,
            index
        )
    }
    private fun actions(value: JsonElement?): List<MacroAction> {
        val actions = value?.jsonArray ?: return emptyList()
        require(actions.size <= 64)
        var firstWaitMillis = 0L
        var holdEnd = false
        var totalSleep = 0L
        return actions.mapNotNull {
            val text = it.jsonPrimitive.content
            when {
                text == "exit_staring" -> MacroAction.ExitStaring
                text == "release_all" -> MacroAction.ReleaseAll
                text == "click_rel:mouse" -> MacroAction.ClickCursor
                text == "mouse_press:1" -> MacroAction.MouseButton(true)
                text == "mouse_release:1" -> MacroAction.MouseButton(false)
                text.startsWith("keyboard_press:") || text.startsWith("keyboard_release:") -> {
                    // Macro keyboard commands use Linux scan codes, unlike binding virtual_key (Windows VK).
                    val scan = text.substringAfter(':').toInt()
                    val code = when (scan) {
                        11 -> KeyEvent.KEYCODE_0
                        158 -> KeyEvent.KEYCODE_BACK
                        else -> error("Unsupported macro keyboard scan code: $scan")
                    }
                    MacroAction.Keyboard(SimulatedKey(code, scan), text.startsWith("keyboard_press:"))
                }
                text.startsWith("sleep:") -> {
                    val millis = text.substringAfter(':').toLong()
                    require(millis in 0..60_000)
                    totalSleep += millis
                    require(totalSleep <= 60_000) { "Macro is too long" }
                    MacroAction.Sleep(millis)
                }
                text.startsWith("curve_first_point_sleep_time:") -> {
                    firstWaitMillis = text.substringAfter(':').toLong()
                    require(firstWaitMillis in 0..60_000)
                    null
                }
                text == "curve_last_point_sleep_time:until_release_cmd" -> { holdEnd = true; null }
                text.startsWith("curve_rel:mouse;") -> {
                    val points = text.substringAfter(';').split(';').map { coordinate ->
                        val match = curvePoint.matchEntire(coordinate) ?: error("Invalid macro curve point: $coordinate")
                        val x = match.groupValues[1].toDouble(); val y = match.groupValues[2].toDouble()
                        require(x.isFinite() && y.isFinite() && x in -16.0..16.0 && y in -16.0..16.0)
                        TouchPoint(x, y)
                    }
                    require(points.size in 1..64)
                    totalSleep += firstWaitMillis
                    require(totalSleep <= 60_000) { "Macro is too long" }
                    MacroAction.Curve(points, firstWaitMillis, holdEnd)
                }
                else -> {
                    val match = relativeClick.matchEntire(text) ?: error("Unsupported macro instruction: $text")
                    MacroAction.Click(normalized(match.groupValues[1].toDouble(), match.groupValues[2].toDouble()))
                }
            }
        }
    }
    private fun point(value: JsonElement): TouchPoint = value.jsonObject.let {
        normalized(number(it, "rel_x", 0.0..1.0), number(it, "rel_y", 0.0..1.0))
    }
    private fun normalized(x: Double, y: Double): TouchPoint {
        require(x.isFinite() && y.isFinite() && x in 0.0..1.0 && y in 0.0..1.0)
        return TouchPoint(x, y)
    }
    private fun number(value: JsonObject, name: String, range: ClosedFloatingPointRange<Double>): Double {
        val number = value.getValue(name).jsonPrimitive.double
        require(number.isFinite() && number in range) { "Invalid $name" }
        return number
    }
    private fun key(value: JsonObject): Int {
        val device = value.getValue("device").jsonPrimitive.content
        val code = value.getValue("virtual_key").jsonPrimitive.int
        if (device == "mouse") return when (code) { 1 -> KEYCODE_LMC; 2 -> KEYCODE_RMC; 4 -> KEYCODE_MMC; else -> error("Unsupported mouse key") }
        require(device == "keyboard")
        // Windows VK, not Android keycode or Linux scan_code. Do not conflate the three.
        return when (code) {
            in 65..90 -> KeyEvent.KEYCODE_A + code - 65
            in 48..57 -> KeyEvent.KEYCODE_0 + code - 48
            in 112..123 -> KeyEvent.KEYCODE_F1 + code - 112
            16 -> KeyEvent.KEYCODE_SHIFT_LEFT
            17 -> KeyEvent.KEYCODE_CTRL_LEFT
            18 -> KeyEvent.KEYCODE_ALT_LEFT
            27 -> KeyEvent.KEYCODE_ESCAPE
            32 -> KeyEvent.KEYCODE_SPACE
            9 -> KeyEvent.KEYCODE_TAB
            13 -> KeyEvent.KEYCODE_ENTER
            192 -> KeyEvent.KEYCODE_GRAVE
            else -> error("Unsupported Windows VK: $code")
        }
    }
}
