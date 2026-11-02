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
        val mumuLibraries = MumuProfileStore.readLibraries(preferences)
        return MappingBackup(
            format = MappingBackupCodec.FORMAT,
            schemaVersion = if (mumuLibraries.isEmpty()) 1 else MappingBackupCodec.SCHEMA_VERSION,
            createdAtMillis = nowMillis,
            sourceApplicationId = sourceApplicationId,
            keySettings = MappingKeySettings.from(config),
            addedPackages = preferences[DataStoreManager.ADDED_PACKAGES]?.let {
                Json.decodeFromString<List<String>>(it).distinct()
            } ?: emptyList(),
            mappings = mappings,
            pointerSensitivity = preferences[DataStoreManager.POINTER_SENSITIVITY] ?: 0.5f,
            overlayOpacity = preferences[DataStoreManager.OVERLAY_OPACITY] ?: 0.5f,
            mumuLibraries = mumuLibraries
        ).also(MappingBackupCodec::validate)
    }

    suspend fun restore(backup: MappingBackup) {
        MappingBackupCodec.validate(backup)
        // Serialize before editing; a bad profile cannot produce a partial restore.
        val profiles = backup.mappings.mapValues { (_, items) ->
            Json.encodeToString(items.map { it.migrateLegacyMouseKeyCode() })
        }
        val libraries = (backup.mumuProfiles.mapValues { MumuProfileLibrary.fromLegacy(it.value) } + backup.mumuLibraries)
            .mapValues { Json.encodeToString(it.value) }
        val importedPackages = (backup.addedPackages + profiles.keys + libraries.keys).distinct()
        store.edit { preferences ->
            val config = preferences[DataStoreManager.KEYS_CONFIG]?.let {
                Json.decodeFromString<AppConfig>(it)
            } ?: AppConfig.Default
            val existingApps = preferences[DataStoreManager.ADDED_PACKAGES]?.let {
                Json.decodeFromString<List<String>>(it)
            } ?: emptyList()
            profiles.forEach { (packageName, profile) ->
                preferences[stringPreferencesKey(PREFIX + packageName)] = profile
            }
            importedPackages.forEach { packageName ->
                val library = libraries[packageName]
                if (library == null) preferences.remove(MumuProfileStore.key(packageName))
                else preferences[MumuProfileStore.key(packageName)] = library
                preferences.remove(stringPreferencesKey(MumuProfileStore.LEGACY_PREFIX + packageName))
            }
            preferences[DataStoreManager.ADDED_PACKAGES] = Json.encodeToString(
                (existingApps + importedPackages).distinct()
            )
            preferences[DataStoreManager.KEYS_CONFIG] = Json.encodeToString(backup.keySettings.applyTo(config))
            preferences[DataStoreManager.POINTER_SENSITIVITY] = backup.pointerSensitivity
            preferences[DataStoreManager.OVERLAY_OPACITY] = backup.overlayOpacity
        }
    }

    private companion object { const val PREFIX = "buttons_config_" }
}
