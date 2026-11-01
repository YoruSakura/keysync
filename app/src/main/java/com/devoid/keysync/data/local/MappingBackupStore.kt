package com.devoid.keysync.data.local

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.devoid.keysync.domain.migrateLegacyMouseKeyCode
import com.devoid.keysync.domain.migrateLegacyMouseKeyCodes
import com.devoid.keysync.model.AppConfig
import com.devoid.keysync.model.DraggableItem
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json

/** Takes one consistent snapshot and restores all imported keys in a single transaction. */
internal class MappingBackupStore(private val store: DataStore<Preferences>) {
    suspend fun snapshot(sourceApplicationId: String, nowMillis: Long): MappingBackup {
        val preferences = store.data.first()
        val config = preferences[DataStoreManager.KEYS_CONFIG]?.let {
            Json.decodeFromString<AppConfig>(it).migrateLegacyMouseKeyCodes()
        } ?: AppConfig.Default
        val mappings = preferences.asMap().entries
            .filter { it.key.name.startsWith(PREFIX) }
            .associate { (key, value) ->
                key.name.removePrefix(PREFIX) to Json.decodeFromString<List<DraggableItem>>(value as String)
                    .map { it.migrateLegacyMouseKeyCode() }
            }
        val mumuProfiles = preferences.asMap().entries
            .filter { it.key.name.startsWith(DataStoreManager.MUMU_PREFIX) }
            .associate { (key, value) -> key.name.removePrefix(DataStoreManager.MUMU_PREFIX) to (value as String) }
        return MappingBackup(
            format = MappingBackupCodec.FORMAT,
            schemaVersion = if (mumuProfiles.isEmpty()) 1 else MappingBackupCodec.SCHEMA_VERSION,
            createdAtMillis = nowMillis,
            sourceApplicationId = sourceApplicationId,
            keySettings = MappingKeySettings.from(config),
            addedPackages = preferences[DataStoreManager.ADDED_PACKAGES]?.let {
                Json.decodeFromString<List<String>>(it).distinct()
            } ?: emptyList(),
            mappings = mappings,
            pointerSensitivity = preferences[DataStoreManager.POINTER_SENSITIVITY] ?: 0.5f,
            overlayOpacity = preferences[DataStoreManager.OVERLAY_OPACITY] ?: 0.5f,
            mumuProfiles = mumuProfiles
        ).also(MappingBackupCodec::validate)
    }

    suspend fun restore(backup: MappingBackup) {
        MappingBackupCodec.validate(backup)
        // Serialize before editing; a bad profile cannot produce a partial restore.
        val profiles = backup.mappings.mapValues { (_, items) ->
            Json.encodeToString(items.map { it.migrateLegacyMouseKeyCode() })
        }
        store.edit { preferences ->
            val config = preferences[DataStoreManager.KEYS_CONFIG]?.let {
                Json.decodeFromString<AppConfig>(it)
            } ?: AppConfig.Default
            val existingApps = preferences[DataStoreManager.ADDED_PACKAGES]?.let {
                Json.decodeFromString<List<String>>(it)
            } ?: emptyList()
            profiles.forEach { (packageName, profile) ->
                preferences[stringPreferencesKey(PREFIX + packageName)] = profile
                if (packageName !in backup.mumuProfiles) preferences.remove(stringPreferencesKey(DataStoreManager.MUMU_PREFIX + packageName))
            }
            backup.mumuProfiles.forEach { (packageName, profile) ->
                preferences[stringPreferencesKey(DataStoreManager.MUMU_PREFIX + packageName)] = profile
            }
            preferences[DataStoreManager.ADDED_PACKAGES] = Json.encodeToString(
                (existingApps + backup.addedPackages + backup.mappings.keys + backup.mumuProfiles.keys).distinct()
            )
            preferences[DataStoreManager.KEYS_CONFIG] = Json.encodeToString(backup.keySettings.applyTo(config))
            preferences[DataStoreManager.POINTER_SENSITIVITY] = backup.pointerSensitivity
            preferences[DataStoreManager.OVERLAY_OPACITY] = backup.overlayOpacity
        }
    }

    private companion object { const val PREFIX = "buttons_config_" }
}
