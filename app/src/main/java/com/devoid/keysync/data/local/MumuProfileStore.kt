package com.devoid.keysync.data.local

import androidx.datastore.core.DataMigration
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import java.util.UUID

internal class MumuProfileStore(private val store: DataStore<Preferences>) {
    fun libraries() = store.data.map(::readLibraries)

    fun activeProfile(packageName: String) = store.data.map { preferences ->
        preferences[key(packageName)]?.let { Json.decodeFromString<MumuProfileLibrary>(it).selectedProfile?.json }
    }

    /** The file importer supplies JSON already validated by MumuProfileCodec.read. */
    suspend fun add(packageName: String, name: String, text: String) {
        val profile = SavedMumuProfile(UUID.randomUUID().toString(), name.trim(), text)
        require(profile.name.isNotEmpty() && profile.name.length <= MumuProfileLibrary.MAX_NAME_LENGTH)
        store.edit { preferences ->
            val library = preferences[key(packageName)]?.let { Json.decodeFromString<MumuProfileLibrary>(it) }
                ?: MumuProfileLibrary()
            preferences[key(packageName)] = Json.encodeToString(library.copy(
                profiles = library.profiles + profile, selectedId = profile.id
            ))
        }
    }

    suspend fun select(packageName: String, id: String?) {
        store.edit { preferences ->
            val library = preferences[key(packageName)]?.let { Json.decodeFromString<MumuProfileLibrary>(it) }
                ?: MumuProfileLibrary()
            require(id == null || library.profiles.any { it.id == id })
            preferences[key(packageName)] = Json.encodeToString(library.copy(selectedId = id))
        }
    }

    suspend fun rename(packageName: String, id: String, name: String) {
        val newName = name.trim()
        require(newName.isNotEmpty() && newName.length <= MumuProfileLibrary.MAX_NAME_LENGTH)
        store.edit { preferences ->
            val library = Json.decodeFromString<MumuProfileLibrary>(preferences[key(packageName)]!!)
            preferences[key(packageName)] = Json.encodeToString(library.copy(
                profiles = library.profiles.map { if (it.id == id) it.copy(name = newName) else it }
            ))
        }
    }

    /** The layout editor supplies a profile produced by MumuProfileCodec. */
    suspend fun updateLayout(packageName: String, id: String, text: String) {
        store.edit { preferences ->
            val library = Json.decodeFromString<MumuProfileLibrary>(preferences[key(packageName)]!!)
            preferences[key(packageName)] = Json.encodeToString(library.copy(
                profiles = library.profiles.map { if (it.id == id) it.copy(json = text) else it }
            ))
        }
    }

    suspend fun delete(packageName: String, id: String) {
        store.edit { preferences ->
            val library = Json.decodeFromString<MumuProfileLibrary>(preferences[key(packageName)]!!)
            preferences[key(packageName)] = Json.encodeToString(library.copy(
                profiles = library.profiles.filterNot { it.id == id },
                selectedId = library.selectedId.takeUnless { it == id }
            ))
        }
    }

    suspend fun removeGame(packageName: String) {
        store.edit { it.remove(key(packageName)) }
    }

    companion object {
        const val PREFIX = "mumu_library_"
        const val LEGACY_PREFIX = "mumu_profile_"
        fun key(packageName: String) = stringPreferencesKey(PREFIX + packageName)

        fun readLibraries(preferences: Preferences): Map<String, MumuProfileLibrary> =
            preferences.asMap().entries.filter { it.key.name.startsWith(PREFIX) }.associate { (key, value) ->
                key.name.removePrefix(PREFIX) to Json.decodeFromString<MumuProfileLibrary>(value as String)
            }
    }
}

/** Upgrade the installed single-profile format atomically, keeping its original JSON and active state. */
internal object MumuProfileMigration : DataMigration<Preferences> {
    override suspend fun shouldMigrate(currentData: Preferences) =
        currentData.asMap().keys.any { it.name.startsWith(MumuProfileStore.LEGACY_PREFIX) }

    override suspend fun migrate(currentData: Preferences): Preferences = currentData.toMutablePreferences().apply {
        currentData.asMap().entries.filter { it.key.name.startsWith(MumuProfileStore.LEGACY_PREFIX) }
            .forEach { (oldKey, value) ->
                val newKey = MumuProfileStore.key(oldKey.name.removePrefix(MumuProfileStore.LEGACY_PREFIX))
                this[newKey] = Json.encodeToString(MumuProfileLibrary.fromLegacy(value as String))
                remove(stringPreferencesKey(oldKey.name))
            }
    }

    override suspend fun cleanUp() = Unit
}
