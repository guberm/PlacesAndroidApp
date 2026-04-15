# Places Android App

A modern, native Android client built with **Jetpack Compose** that interacts with the [PlacesRecommendation](https://github.com/guberm/PlacesRecommendation) AI backend.

![Jetpack Compose](https://img.shields.io/badge/Jetpack_Compose-Premium_UI-blue?style=for-the-badge)
![Kotlin](https://img.shields.io/badge/Kotlin-Modern-purple?style=for-the-badge)

## Features

- **Full Parity with PlacesRecommendation API**: Supports address search, coordinate-based search (My Location), filtering by Categories, Radius distances, and exact Max Results bounds.
- **Dynamic AI Configuration**: Define your LLM API keys on the fly. Dynamically load and assign available generation models across multiple providers including *OpenRouter*, *OpenAI*, *Anthropic*, *Gemini*, and *Azure OpenAI*. 
- **Modern Jetpack Compose UI**: Glassmorphism visuals, smooth state transitions, infinite scrolling menus, and seamless Light/Dark mode themes.
- **My Location Support**: Effortlessly centers searches on your immediate surroundings without manual typing.
- **Google Places integration**: Inject your keys to support the Google API directly for real-time validation and review caching.

## Tech Stack

- **UI**: Android Jetpack Compose 
- **Language**: Kotlin 
- **Networking**: Retrofit2 + OkHttp3
- **Device Support**: Android 7.0+ (Min SDK 24)

## Configuration

Navigate to the `Settings ⚙` gear in the top right to configure your API keys. 
*Note:* Keys set in this UI are injected via memory and override the backend's core configuration files gracefully per individual request.

## Download

Grab the latest compiled APK from the **Releases** tab.
