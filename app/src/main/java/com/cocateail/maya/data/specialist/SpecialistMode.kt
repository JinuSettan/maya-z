package com.cocateail.maya.data.specialist

data class SpecialistMode(
    val type: SpecialistType,
    val displayName: String,
    val description: String,
    val supportedTechnologies: List<String>,
    val systemInstructions: String
)

object SpecialistRegistry {
    val GENERAL = SpecialistMode(
        type = SpecialistType.GENERAL,
        displayName = "General Companion",
        description = "Standard conversational AI companion.",
        supportedTechnologies = listOf("General Conversation", "Knowledge Q&A", "Empathy & Assistance"),
        systemInstructions = ""
    )

    val WEB_DEV = SpecialistMode(
        type = SpecialistType.WEB_DEV,
        displayName = "Web Development Specialist",
        description = "Expert in web technologies, frontend, backend, APIs, and databases.",
        supportedTechnologies = listOf(
            "HTML", "CSS", "JavaScript", "TypeScript",
            "React", "Next.js", "Node.js", "APIs", "Databases"
        ),
        systemInstructions = """
            Specialist Capability [Web Development]:
            You have expert-level domain mastery in Web Software Engineering.
            - Provide production-ready, clean, modular, and secure solutions using HTML, CSS, JavaScript, TypeScript, React, Next.js, Node.js, REST APIs, and modern databases.
            - Focus on responsive web design, state management, security best practices, and elegant modern web UI.
            - Write clear, executable code snippets and provide concise architectural explanations.
        """.trimIndent()
    )

    val APP_DEV = SpecialistMode(
        type = SpecialistType.APP_DEV,
        displayName = "App Development Specialist",
        description = "Expert in Android, iOS, cross-platform apps, Kotlin, Flutter, and React Native.",
        supportedTechnologies = listOf(
            "Android", "Kotlin", "Java", "Jetpack Compose",
            "React Native", "Expo", "Flutter", "Dart", "Gradle", "APK Development"
        ),
        systemInstructions = """
            Specialist Capability [App Development]:
            You have expert-level domain mastery in Mobile Application Engineering.
            - Deep expertise in Android (Kotlin, Jetpack Compose, Java, Coroutines, Room, MVVM/Clean Architecture, Gradle), Flutter/Dart, and React Native/Expo.
            - Focus on native performance, clean lifecycle management, smooth touch interactions, Material 3 design, edge-to-edge UI, and mobile security.
            - Provide clear, idiomatic code examples, architectural guidance, and mobile build resolution strategies.
        """.trimIndent()
    )

    val GAME_DEV = SpecialistMode(
        type = SpecialistType.GAME_DEV,
        displayName = "Game Development Specialist",
        description = "Expert in Unity, Unreal Engine, Godot, game systems, physics, and AI.",
        supportedTechnologies = listOf(
            "Unity", "C#", "Unreal Engine", "C++", "Blueprints",
            "Godot", "GDScript", "Game Physics", "Animation", "Game AI", "Game Systems"
        ),
        systemInstructions = """
            Specialist Capability [Game Development]:
            You have expert-level domain mastery in Game Engine Development and Game Design.
            - Deep expertise in Unity (C#, MonoBehaviours, ScriptableObjects, Shader Graph, Physics), Unreal Engine (C++, Blueprints, Gameplay Systems), and Godot (GDScript, Node systems, Signals).
            - Focus on player controllers, game physics, animation state machines, game loop performance, AI behaviors (Behavior Trees, FSMs), UI Canvas, level design, and game architecture.
            - Provide high-quality game code, clear engine configuration steps, and math/physics explanations.
        """.trimIndent()
    )

    private val registry = mapOf(
        SpecialistType.GENERAL to GENERAL,
        SpecialistType.WEB_DEV to WEB_DEV,
        SpecialistType.APP_DEV to APP_DEV,
        SpecialistType.GAME_DEV to GAME_DEV
    )

    fun get(type: SpecialistType): SpecialistMode {
        return registry[type] ?: GENERAL
    }

    fun getAll(): List<SpecialistMode> {
        return registry.values.toList()
    }
}
