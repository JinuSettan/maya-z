# Maya AI Companion (Android)

Maya AI Companion is a modern, native Android application built using Kotlin and Jetpack Compose.

## Features

- **Interactive Companion Characters**: Switch between **MAYA** (warm & empathetic AI companion) and **ROCKY** (witty & energetic sidekick).
- **Animated Companion Face**: Dynamic visual companion face with blinking eyes, pupil tracking, bobbing animation, and mouth movements synchronized with thinking & speech states.
- **Multimodal AI & Speech**: Integrated with Gemini 3.5 Flash REST API with vision support (photo attachment analysis), text completion, and Android Text-To-Speech (TTS) voice synthesis.
- **Room Local Database**: Persistent conversation history, message bookmarking/pinning, and message management stored locally on device.
- **Developer Mode & Safe Controls**: Hidden developer unlock (`/developerceo/maya/unlockcode/trainer`), custom system instructions, personality tuning, response rules, speech rate/pitch adjustments, and custom Gemini API key configuration.
- **Deep Obsidian Cinematic Theme**: Material 3 dark design system with vermilion/sunset orange accents and custom adaptive launcher icon.

## Tech Stack

- **Language**: Kotlin
- **UI Framework**: Jetpack Compose (Material 3)
- **Database**: Room Database (KSP)
- **Networking**: OkHttp, Kotlinx Serialization
- **Architecture**: MVVM (Model-View-ViewModel) + Coroutines & Flow
