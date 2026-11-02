package com.devoid.keysync.data.local

import androidx.compose.ui.geometry.Offset
import com.devoid.keysync.domain.migrateLegacyMouseKeyCode
import com.devoid.keysync.domain.migrateLegacyMouseKeyCodes
import com.devoid.keysync.model.AppConfig
import com.devoid.keysync.model.DraggableItem
import com.devoid.keysync.model.TouchMode
import com.devoid.keysync.domain.touch.MumuProfileCodec
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.io.InputStream

/** Portable data only: permissions, engine selection and UI preferences stay on the device. */
@Serializable
data class MappingBackup(
    val format: String,
    val schemaVersion: Int,
    val createdAtMillis: Long,
    val sourceApplicationId: String,
    val keySettings: MappingKeySettings,
    val addedPackages: List<String>,
    val mappings: Map<String, List<DraggableItem>>,
    val pointerSensitivity: Float,
    val overlayOpacity: Float,
    val mumuProfiles: Map<String, String> = emptyMap(),
    val mumuLibraries: Map<String, MumuProfileLibrary> = emptyMap()
)

@Serializable
data class MappingKeySettings(
    val shootingModeKeyCode: Int,
    val fireKeyCode: Int,
    val scopeKeyCode: Int,
    val buttonScale: Float,
    val cancellableTouchMode: TouchMode,
    val normalBtnTouchMode: TouchMode
) {
    fun applyTo(current: AppConfig): AppConfig = current.copy(
        shootingModeKeyCode = shootingModeKeyCode,
        fireKeyCode = fireKeyCode,
        scopeKeyCode = scopeKeyCode,
        buttonScale = buttonScale,
        cancellableTouchMode = cancellableTouchMode,
        normalBtnTouchMode = normalBtnTouchMode
    ).migrateLegacyMouseKeyCodes()

    companion object {
        fun from(config: AppConfig) = MappingKeySettings(
            config.shootingModeKeyCode, config.fireKeyCode, config.scopeKeyCode,
            config.buttonScale, config.cancellableTouchMode, config.normalBtnTouchMode
        )
    }
}

object MappingBackupCodec {
    const val FORMAT = "keysync-keymap-backup"
    const val SCHEMA_VERSION = 3
    const val MAX_BYTES = 4 * 1024 * 1024
    private val packagePattern = Regex("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)+")
    private val json = Json { prettyPrint = true; encodeDefaults = true; ignoreUnknownKeys = true }

    fun encode(backup: MappingBackup): String {
        validate(backup)
        return json.encodeToString(backup).also {
            require(it.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "Backup is too large" }
        }
    }

    fun decode(text: String): MappingBackup {
        require(text.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "Backup is too large" }
        val backup = json.decodeFromString<MappingBackup>(text)
        validate(backup)
        return backup.copy(mappings = backup.mappings.mapValues { (_, items) ->
            items.map { it.migrateLegacyMouseKeyCode() }
        })
    }

    fun read(input: InputStream): MappingBackup {
        val bytes = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            require(bytes.size() + count <= MAX_BYTES) { "Backup is too large" }
            bytes.write(buffer, 0, count)
        }
        return decode(bytes.toByteArray().toString(Charsets.UTF_8))
    }

    fun validate(backup: MappingBackup) {
        require(backup.format == FORMAT) { "Not a KeySync mapping backup" }
        require(backup.schemaVersion in 1..SCHEMA_VERSION) { "Unsupported backup version" }
        require(backup.addedPackages.size <= 1024 && backup.mappings.size <= 1024)
        require(backup.addedPackages.distinct().size == backup.addedPackages.size)
        require(backup.mumuProfiles.size <= 1024)
        require(backup.schemaVersion >= 2 || backup.mumuProfiles.isEmpty())
        require(backup.mumuLibraries.size <= 1024)
        require(backup.schemaVersion >= 3 || backup.mumuLibraries.isEmpty())
        require(backup.mumuProfiles.keys.intersect(backup.mumuLibraries.keys).isEmpty())
        (backup.addedPackages + backup.mappings.keys + backup.mumuProfiles.keys + backup.mumuLibraries.keys).forEach {
            require(it.length <= 255 && packagePattern.matches(it)) { "Invalid app package" }
        }
        require(backup.pointerSensitivity.isFinite() && backup.pointerSensitivity in 0f..1f)
        require(backup.overlayOpacity.isFinite() && backup.overlayOpacity in 0f..1f)
        require(backup.keySettings.buttonScale.isFinite() && backup.keySettings.buttonScale in 0f..2f)
        backup.mumuProfiles.values.forEach(MumuProfileCodec::decode)
        backup.mumuLibraries.values.forEach(MumuProfileLibrary::validate)
        backup.mappings.values.forEach { items ->
            require(items.size <= 2048 && items.map { it.id }.distinct().size == items.size)
            items.forEach { item ->
                requireValidPosition(item.position)
                when (item) {
                    is DraggableItem.VariableKey -> require(item.size in -10000..10000)
                    is DraggableItem.FixedKey -> require(item.size in -10000..10000)
                    is DraggableItem.CancelableKey -> {
                        require(item.size in -10000..10000)
                        requireValidPosition(item.cancelPosition)
                    }
                    is DraggableItem.WASDGroup -> {
                        require(item.scale.isFinite() && item.scale in 0f..100f)
                        listOf(item.center, item.w, item.a, item.s, item.d).forEach(::requireValidPosition)
                    }
                }
            }
        }
    }

    private fun requireValidPosition(position: Offset) {
        require(position.x.isFinite() && position.y.isFinite())
        require(position.x in -1_000_000f..1_000_000f && position.y in -1_000_000f..1_000_000f)
    }
}
