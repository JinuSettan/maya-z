package com.cocateail.maya.data.specialist

object TaskClassifier {

    fun isBuildRequest(prompt: String): Boolean {
        val clean = prompt.lowercase().trim()
        
        // Exclude normal QA
        if (clean.startsWith("explain ") ||
            clean.startsWith("who is ") ||
            clean.startsWith("what is ") ||
            clean.startsWith("tell me ") ||
            clean.startsWith("how are ") ||
            clean.startsWith("hi") ||
            clean.startsWith("hello")
        ) {
            return false
        }
        
        // Exclude image or video generation explicitly
        if (clean.contains("generate a cinematic") || clean.contains("create a cinematic") || clean.contains("image of") || clean.contains("video of") || clean.contains("generate an image") || clean.contains("generate a video")) {
            val isAppOrGame = clean.contains("app") || clean.contains("game") || clean.contains("editor") || clean.contains("web")
            if (!isAppOrGame) {
                return false
            }
        }
        
        val builderVerbs = listOf("build", "create", "make", "develop", "modify", "fix", "add")
        val builderNouns = listOf("app", "game", "website", "project", "editor", "calculator", "prototype", "screen", "mode", "filter")
        
        val hasVerb = builderVerbs.any { clean.contains(it) }
        val hasNoun = builderNouns.any { clean.contains(it) }
        
        return hasVerb && hasNoun
    }

    fun classify(prompt: String): SpecialistType {
        if (prompt.isBlank()) return SpecialistType.GENERAL

        val clean = prompt.lowercase().trim()

        // Fast exclusion for common general conversational / non-dev questions
        if (clean.length < 5 ||
            clean.startsWith("explain ") ||
            clean.startsWith("who is ") ||
            clean.startsWith("what is ") ||
            clean.startsWith("tell me ") ||
            clean.startsWith("how are ") ||
            clean.startsWith("hi") ||
            clean.startsWith("hello") ||
            clean == "leave" ||
            clean.contains("my mind is")
        ) {
            val hasExplicitDevIntent = clean.contains("build") || clean.contains("create") || clean.contains("make") || clean.contains("develop")
            if (!hasExplicitDevIntent) {
                return SpecialistType.GENERAL
            }
        }

        var gameScore = 0
        var appScore = 0
        var webScore = 0

        // Game Dev Keywords
        val gameKeywords = listOf(
            "game", "unity", "unreal", "godot", "gdscript", "c# script", "blueprints",
            "gameplay", "player controller", "2d platformer", "3d game", "rpg game", "fps game",
            "game engine", "game physics", "game mechanics", "sprite animation", "level design",
            "make a game", "build a game", "football game", "racing game", "puzzle game"
        )
        for (kw in gameKeywords) {
            if (clean.contains(kw)) {
                gameScore += if (kw.contains("game") || kw == "unity" || kw == "unreal" || kw == "godot") 3 else 2
            }
        }

        // App Dev Keywords
        val appKeywords = listOf(
            "android app", "ios app", "mobile app", "jetpack compose", "flutter", "react native",
            "expo", "kotlin", "java app", "apk", "apk build", "gradle", "create an app",
            "build an app", "app for my", "android", "swift", "app development"
        )
        for (kw in appKeywords) {
            if (clean.contains(kw)) {
                appScore += if (kw.contains("app") || kw == "android" || kw == "flutter" || kw == "apk") 3 else 2
            }
        }

        // Web Dev Keywords
        val webKeywords = listOf(
            "website", "web app", "web application", "html", "css", "javascript", "typescript",
            "react website", "next.js", "nextjs", "node.js", "express", "tailwind", "landing page",
            "frontend", "backend api", "rest api", "web dev", "restaurant website", "e-commerce site"
        )
        for (kw in webKeywords) {
            if (clean.contains(kw)) {
                webScore += if (kw.contains("website") || kw == "html" || kw == "css" || kw == "next.js" || kw == "web app") 3 else 2
            }
        }

        val maxScore = maxOf(gameScore, appScore, webScore)

        if (maxScore < 2) {
            return SpecialistType.GENERAL
        }

        return when {
            gameScore == maxScore && gameScore > appScore && gameScore > webScore -> SpecialistType.GAME_DEV
            appScore == maxScore && appScore > gameScore && appScore > webScore -> SpecialistType.APP_DEV
            webScore == maxScore && webScore > gameScore && webScore > appScore -> SpecialistType.WEB_DEV
            else -> SpecialistType.GENERAL
        }
    }
}
