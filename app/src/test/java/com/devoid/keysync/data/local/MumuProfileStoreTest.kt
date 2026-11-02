package com.devoid.keysync.data.local

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import com.devoid.keysync.model.AppConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class MumuProfileStoreTest {
    @get:Rule val temporaryFolder = TemporaryFolder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val dataStore by lazy {
        PreferenceDataStoreFactory.create(scope = scope,
            migrations = listOf(MumuProfileMigration),
            produceFile = { File(temporaryFolder.root, "test.preferences_pb") })
    }
    private val store by lazy { MumuProfileStore(dataStore) }
    private val profile by lazy {
        javaClass.classLoader!!.getResourceAsStream("com.hypergryph.endfield-默认操作方案.json")!!
            .use { it.reader().readText() }
    }

    @After fun close() = runBlocking { scope.coroutineContext[Job]!!.cancelAndJoin() }

    @Test fun `migration preserves installed JSON active state and editor data`() = runBlocking {
        val oldKey = stringPreferencesKey(MumuProfileStore.LEGACY_PREFIX + GAME_A)
        val editorKey = DataStoreManager.getButtonsConfigKey(GAME_A)
        val preferences = preferencesOf(oldKey to profile, editorKey to "[]")

        assertTrue(MumuProfileMigration.shouldMigrate(preferences))
        val migrated = MumuProfileMigration.migrate(preferences)
        val library = MumuProfileStore.readLibraries(migrated).getValue(GAME_A)
        assertEquals(profile, library.selectedProfile!!.json)
        assertEquals("MuMu", library.selectedProfile!!.name)
        assertEquals("[]", migrated[editorKey])
        assertNull(migrated[oldKey])
        assertFalse(MumuProfileMigration.shouldMigrate(migrated))
    }

    @Test fun `games have independent profiles selection renames and deletion`() = runBlocking {
        store.add(GAME_A, "键位一", profile)
        val first = store.libraries().first().getValue(GAME_A).selectedId!!
        store.add(GAME_A, "键位二", "$profile\n")
        val second = store.libraries().first().getValue(GAME_A).selectedId!!
        store.add(GAME_B, "另一游戏", profile)
        val otherGame = store.libraries().first().getValue(GAME_B)

        store.select(GAME_A, first)
        store.rename(GAME_A, second, "重命名")
        assertEquals(profile, store.activeProfile(GAME_A).first())
        assertEquals(listOf("键位一", "重命名"), store.libraries().first().getValue(GAME_A).profiles.map { it.name })
        store.delete(GAME_A, second)
        assertEquals(profile, store.activeProfile(GAME_A).first())
        store.delete(GAME_A, first)
        assertNull(store.activeProfile(GAME_A).first())
        assertEquals(otherGame, store.libraries().first().getValue(GAME_B))
    }

    @Test fun `backup restores every named profile and selection including editor mode`() = runBlocking {
        store.add(GAME_A, "键位一", profile)
        store.add(GAME_A, "键位二", "$profile\n")
        store.add(GAME_B, "另一游戏", profile)
        store.select(GAME_A, null)
        val backupStore = MappingBackupStore(dataStore)
        val saved = backupStore.snapshot("com.example.keysync", 0)
        val portable = MappingBackupCodec.decode(MappingBackupCodec.encode(saved))
        assertEquals(3, portable.schemaVersion)
        assertEquals(saved, portable)

        store.add(GAME_A, "不在备份中", profile)
        store.removeGame(GAME_B)
        backupStore.restore(portable)
        assertEquals(saved.mumuLibraries, store.libraries().first())
        assertNull(store.activeProfile(GAME_A).first())
        assertEquals(profile, store.activeProfile(GAME_B).first())
    }

    @Test fun `calibration saves only the specified profile and survives backup with names and selection`() = runBlocking {
        store.add(GAME_A, "原始方案", profile)
        val originalId = store.libraries().first().getValue(GAME_A).selectedId!!
        store.add(GAME_A, "平板校准", profile)
        val calibratedId = store.libraries().first().getValue(GAME_A).selectedId!!
        store.add(GAME_B, "其他游戏", profile)
        val otherGame = store.libraries().first().getValue(GAME_B)
        store.updateLayout(GAME_A, calibratedId, "$profile\n")
        val library = store.libraries().first().getValue(GAME_A)
        assertEquals(profile, library.profiles.first { it.id == originalId }.json)
        assertEquals("$profile\n", library.selectedProfile!!.json)
        assertEquals("平板校准", library.selectedProfile!!.name)
        assertEquals(calibratedId, library.selectedId)
        assertEquals(otherGame, store.libraries().first().getValue(GAME_B))
        val backup = MappingBackupStore(dataStore).snapshot("com.example.keysync", 0)
        assertEquals(library, MappingBackupCodec.decode(MappingBackupCodec.encode(backup)).mumuLibraries[GAME_A])
    }

    @Test fun `restoring v2 then v1 replaces only imported games without resurrecting old profiles`() = runBlocking {
        store.add(GAME_A, "旧方案一", profile)
        store.add(GAME_A, "旧方案二", profile)
        store.add(GAME_B, "保留", profile)
        val otherGame = store.libraries().first().getValue(GAME_B)
        val v1 = MappingBackup(MappingBackupCodec.FORMAT, 1, 0, "com.example.keysync",
            MappingKeySettings.from(AppConfig.Default), listOf(GAME_A), mapOf(GAME_A to emptyList()), .5f, .5f)
        val backupStore = MappingBackupStore(dataStore)

        backupStore.restore(v1.copy(schemaVersion = 2, mumuProfiles = mapOf(GAME_A to "$profile\n")))
        assertEquals(1, store.libraries().first().getValue(GAME_A).profiles.size)
        assertEquals("$profile\n", store.activeProfile(GAME_A).first())
        backupStore.restore(v1)
        assertNull(store.activeProfile(GAME_A).first())
        assertEquals(otherGame, store.libraries().first().getValue(GAME_B))
        assertEquals("[]", dataStore.data.first()[DataStoreManager.getButtonsConfigKey(GAME_A)])
    }

    companion object {
        private const val GAME_A = "com.example.gamea"
        private const val GAME_B = "com.example.gameb"
    }
}
