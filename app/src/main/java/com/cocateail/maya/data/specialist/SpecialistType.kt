package com.cocateail.maya.data.specialist

import kotlinx.serialization.Serializable

@Serializable
enum class SpecialistType(
    val codeName: String,
    val displayName: String
) {
    GENERAL("GENERAL", "General Companion"),
    WEB_DEV("WEB_DEV", "Web Development Specialist"),
    APP_DEV("APP_DEV", "App Development Specialist"),
    GAME_DEV("GAME_DEV", "Game Development Specialist");

    companion object {
        fun fromCodeName(codeName: String): SpecialistType {
            return entries.firstOrNull { it.codeName.equals(codeName, ignoreCase = true) } ?: GENERAL
        }
    }
}
