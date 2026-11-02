package com.devoid.keysync.data.local

import com.devoid.keysync.domain.touch.MumuProfileCodec
import kotlinx.serialization.Serializable

@Serializable
data class SavedMumuProfile(val id: String, val name: String, val json: String)

@Serializable
data class MumuProfileLibrary(
    val profiles: List<SavedMumuProfile> = emptyList(),
    val selectedId: String? = null
) {
    val selectedProfile: SavedMumuProfile?
        get() = selectedId?.let { id -> profiles.first { it.id == id } }

    /** Used at the backup boundary; locally saved libraries already satisfy these invariants. */
    fun validate() {
        require(profiles.map { it.id }.distinct().size == profiles.size) { "Duplicate MuMu profile IDs" }
        require(selectedId == null || profiles.any { it.id == selectedId }) { "Selected MuMu profile is missing" }
        profiles.forEach {
            require(it.id.isNotBlank() && it.id.length <= 128) { "Invalid MuMu profile ID" }
            require(it.name.isNotBlank() && it.name.length <= MAX_NAME_LENGTH) { "Invalid MuMu profile name" }
            MumuProfileCodec.decode(it.json)
        }
    }

    companion object {
        const val MAX_NAME_LENGTH = 120

        fun fromLegacy(text: String) = MumuProfileLibrary(
            profiles = listOf(SavedMumuProfile("legacy", "MuMu", text)),
            selectedId = "legacy"
        )
    }
}
