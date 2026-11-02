package com.devoid.keysync.data.local

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.devoid.keysync.domain.migrateLegacyMouseKeyCode
import com.devoid.keysync.domain.migrateLegacyMouseKeyCodes
import com.devoid.keysync.model.AppConfig
import com.devoid.keysync.model.DraggableItem
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import javax.inject.Inject

val Context.datastore by preferencesDataStore("app_configurations", produceMigrations = { listOf(MumuProfileMigration) })

class DataStoreManager @Inject constructor(private val context: Context) {
    private val mumuStore = MumuProfileStore(context.datastore)

    suspend fun createMappingBackup(): MappingBackup =
        MappingBackupStore(context.datastore).snapshot(context.packageName, System.currentTimeMillis())

    suspend fun restoreMappingBackup(backup: MappingBackup) =
        MappingBackupStore(context.datastore).restore(backup)

    fun getMumuProfile(packageName: String): Flow<String?> = mumuStore.activeProfile(packageName)
    fun getMumuLibraries() = mumuStore.libraries()
    suspend fun saveMumuProfile(packageName: String, name: String, text: String) = mumuStore.add(packageName, name, text)
    suspend fun selectMumuProfile(packageName: String, id: String?) = mumuStore.select(packageName, id)
    suspend fun renameMumuProfile(packageName: String, id: String, name: String) = mumuStore.rename(packageName, id, name)
    suspend fun updateMumuLayout(packageName: String, id: String, text: String) = mumuStore.updateLayout(packageName, id, text)
    suspend fun deleteMumuProfile(packageName: String, id: String) = mumuStore.delete(packageName, id)
    suspend fun removeMumuProfiles(packageName: String) = mumuStore.removeGame(packageName)

    companion object {
        fun getButtonsConfigKey(packageName: String): Preferences.Key<String> =
            stringPreferencesKey("buttons_config_$packageName")

        val ADDED_PACKAGES = stringPreferencesKey("added_packages")
        val POINTER_SENSITIVITY = floatPreferencesKey("pointer_sensitivity")
        val OVERLAY_OPACITY = floatPreferencesKey("overlay_opacity")
        val KEYS_CONFIG = stringPreferencesKey("keys_config")
    }
    
    fun getButtonsConfigKeys(): Flow<List<Preferences.Key<String>>> {
       return context.datastore.data.map { it.asMap().keys.map { it as Preferences.Key<String> }.filter { prefKey->prefKey.name.startsWith("buttons_config") } }
    }

    suspend fun remove(key: Preferences.Key<String>) {
        context.datastore.edit { pref ->
            pref.remove(key)
        }
    }

    suspend fun save(key: Preferences.Key<String>, value: List<DraggableItem>) {
        val json = Json.encodeToString(value)
        context.datastore.edit { pref ->
            pref[key] = json
        }
    }

    fun getButtons(key: Preferences.Key<String>): Flow<List<DraggableItem>> {
        return context.datastore.data.map { pref ->
            val json = pref[key] ?: return@map emptyList()
            Json.decodeFromString<List<DraggableItem>>(json)
                .map { item -> item.migrateLegacyMouseKeyCode() }
        }
    }

    suspend fun saveList(key: Preferences.Key<String>, value: List<String>) {
        val json = Json.encodeToString(value)
        context.datastore.edit { pref ->
            pref[key] = json
        }
    }

    fun getList(key: Preferences.Key<String>): Flow<List<String>> {
        return context.datastore.data.map { pref ->
            val json = pref[key] ?: return@map emptyList()
            Json.decodeFromString(json)
        }
    }

    suspend fun save(key: Preferences.Key<String>, value: AppConfig) {
        val json = Json.encodeToString(value)
        context.datastore.edit { pref ->
            pref[key] = json
        }
    }

    fun getKeyConfig(key: Preferences.Key<String>): Flow<AppConfig> {
        return context.datastore.data.map { pref ->
            val json = pref[key] ?: return@map AppConfig.Default
            Json.decodeFromString<AppConfig>(json).migrateLegacyMouseKeyCodes()
        }
    }

    suspend fun save(key: Preferences.Key<String>, value: String) {
        context.datastore.edit { pref ->
            pref[key] = value
        }
    }


    fun getString(key: Preferences.Key<String>): Flow<String?> {
        return context.datastore.data.map { pref ->
            pref[key]
        }
    }

    suspend fun save(key: Preferences.Key<Int>, value: Int) {
        context.datastore.edit { pref ->
            pref[key] = value
        }
    }

    fun getInt(key: Preferences.Key<Int>): Flow<Int?> {
        return context.datastore.data.map { pref ->
            pref[key]
        }
    }

    suspend fun save(key: Preferences.Key<Float>, value: Float) {
        context.datastore.edit { pref ->
            pref[key] = value
        }
    }

    fun getFloat(key: Preferences.Key<Float>): Flow<Float?> {
        return context.datastore.data.map { pref ->
            pref[key]
        }
    }


}
