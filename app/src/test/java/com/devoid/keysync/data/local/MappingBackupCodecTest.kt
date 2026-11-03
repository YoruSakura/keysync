package com.devoid.keysync.data.local

import androidx.compose.ui.geometry.Offset
import com.devoid.keysync.model.DraggableItem
import com.devoid.keysync.model.AppConfig
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class MappingBackupCodecTest {
    @Test fun `v1 backups remain readable and v2 preserves MuMu policies without conversion`() {
        val text = requireNotNull(javaClass.classLoader!!.getResourceAsStream("com.hypergryph.endfield-默认操作方案.json"))
            .use { it.reader(Charsets.UTF_8).readText() }
        val backup = MappingBackup(MappingBackupCodec.FORMAT, 1, 0L, "com.example.keysync",
            MappingKeySettings.from(AppConfig.Default), emptyList(), emptyMap(), .5f, .5f)
        assertEquals(backup, MappingBackupCodec.decode(MappingBackupCodec.encode(backup)))
        val withProfile = backup.copy(schemaVersion = 2, mumuProfiles = mapOf("com.hypergryph.endfield" to text))
        assertEquals(withProfile, MappingBackupCodec.decode(MappingBackupCodec.encode(withProfile)))
        val oldFields = Json.parseToJsonElement(MappingBackupCodec.encode(backup)).jsonObject - "aimSensitivity"
        assertEquals(0.5f, MappingBackupCodec.decode(JsonObject(oldFields).toString()).aimSensitivity)
    }

    @Test
    fun `backup round trip preserves app mapping and global settings`() {
        val item = DraggableItem.VariableKey(7, Offset(0.25f, 0.75f), keyCode = 33, size = 90)
        val backup = MappingBackup(
            MappingBackupCodec.FORMAT,
            MappingBackupCodec.SCHEMA_VERSION,
            123L,
            "com.example.keysync",
            MappingKeySettings.from(AppConfig.Default),
            listOf("com.example.game"),
            mapOf("com.example.game" to listOf(item)),
            0.5f,
            0.8f,
            aimSensitivity = 0.23f
        )

        val decoded = MappingBackupCodec.decode(MappingBackupCodec.encode(backup))

        assertEquals(backup, decoded)
    }

    @Test
    fun `backup rejects invalid package names`() {
        val backup = MappingBackup(
            MappingBackupCodec.FORMAT,
            MappingBackupCodec.SCHEMA_VERSION,
            123L,
            "com.example.keysync",
            MappingKeySettings.from(AppConfig.Default),
            listOf("invalid package"),
            emptyMap(),
            0.5f,
            0.8f
        )

        assertThrows(IllegalArgumentException::class.java) {
            MappingBackupCodec.validate(backup)
        }
    }
}
