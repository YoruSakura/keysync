package com.devoid.keysync.domain.touch

import kotlinx.serialization.json.*

enum class MumuLayoutField { WORK, ICON, PRESS, RELEASE }
data class MumuLayoutTarget(val mappingIndex: Int, val field: MumuLayoutField, val actionIndex: Int = 0)
data class MumuLayoutHandle(
    val target: MumuLayoutTarget,
    val key: Int,
    val point: TouchPoint,
    val description: String,
    val radius: Double? = null
)

/** Edits actual touch targets, including macro clicks; leaves all action policies intact. */
object MumuLayoutEditor {
    fun handles(profile: MumuProfile): List<MumuLayoutHandle> = buildList {
        profile.buttons.forEach {
            add(MumuLayoutHandle(MumuLayoutTarget(it.icon.mappingIndex, MumuLayoutField.WORK),
                it.key, it.point, it.icon.description))
        }
        profile.joystick?.let {
            add(MumuLayoutHandle(MumuLayoutTarget(it.icon.mappingIndex, MumuLayoutField.WORK),
                it.up, it.center, "WASD", it.radius))
        }
        profile.camera?.let {
            add(MumuLayoutHandle(MumuLayoutTarget(it.icon.mappingIndex, MumuLayoutField.WORK),
                it.key, it.anchor, it.icon.description))
        }
        profile.observe?.let {
            add(MumuLayoutHandle(MumuLayoutTarget(it.icon.mappingIndex, MumuLayoutField.WORK),
                it.key, it.anchor, it.icon.description))
        }
        profile.tips.forEach {
            add(MumuLayoutHandle(MumuLayoutTarget(it.icon.mappingIndex, MumuLayoutField.ICON),
                it.key, it.icon.point, it.icon.description))
        }
        profile.macros.forEach { macro ->
            add(MumuLayoutHandle(MumuLayoutTarget(macro.icon.mappingIndex, MumuLayoutField.ICON),
                macro.key, macro.icon.point, macro.icon.description))
            listOf(MumuLayoutField.PRESS to macro.press, MumuLayoutField.RELEASE to macro.release)
                .forEach { (field, actions) ->
                    actions.forEachIndexed { index, action ->
                        if (action is MacroAction.Click) {
                            add(MumuLayoutHandle(MumuLayoutTarget(macro.icon.mappingIndex, field, index),
                                macro.key, action.point, macro.icon.description))
                        }
                    }
                }
        }
    }

    fun apply(profile: MumuProfile, positions: Map<MumuLayoutTarget, TouchPoint>): MumuProfile {
        val mappings = profile.source.getValue("keymaps").jsonArray.toMutableList()
        positions.forEach { (target, point) ->
            val item = mappings[target.mappingIndex].jsonObject
            val position = buildJsonObject { put("rel_x", point.x); put("rel_y", point.y) }
            mappings[target.mappingIndex] = when (target.field) {
                MumuLayoutField.WORK, MumuLayoutField.ICON -> {
                    val icon = item["icon"]?.jsonObject ?: buildJsonObject {}
                    JsonObject(item + buildMap {
                        put("icon", JsonObject(icon + ("rel_position" to position)))
                        if (target.field == MumuLayoutField.WORK) put("rel_work_position", position)
                    })
                }
                MumuLayoutField.PRESS, MumuLayoutField.RELEASE -> {
                    val field = if (target.field == MumuLayoutField.PRESS) "press_actions" else "release_actions"
                    val actions = item.getValue(field).jsonArray.toMutableList()
                    actions[target.actionIndex] = JsonPrimitive("click_rel:(${point.x},${point.y})")
                    JsonObject(item + (field to JsonArray(actions)))
                }
            }
        }
        return MumuProfileCodec.decode(JsonObject(profile.source + ("keymaps" to JsonArray(mappings))).toString())
    }
}
