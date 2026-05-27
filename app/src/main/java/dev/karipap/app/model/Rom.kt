package dev.karipap.app.model

import java.io.File

data class Rom(
    val id: Long,
    val path: File,
    val platformTag: String,
    val displayName: String,
    val tags: String? = null,
    val artFile: File? = null,
    val launchTarget: LaunchTarget = LaunchTarget.RetroArch,
    val discFiles: List<File>? = null,
    val raGameId: Int? = null,
    val description: String? = null,
    val rating: Float? = null,
    val releaseDate: String? = null,
    val developer: String? = null,
    val publisher: String? = null,
    val genre: String? = null,
    val players: String? = null,
)
