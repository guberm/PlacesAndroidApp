# Places Android App

A modern, native Android client built with **Jetpack Compose** that interacts with the [PlacesRecommendation](https://github.com/guberm/PlacesRecommendation) AI backend.

![Version](https://img.shields.io/badge/version-1.1-green?style=for-the-badge)
![Jetpack Compose](https://img.shields.io/badge/Jetpack_Compose-Premium_UI-blue?style=for-the-badge)
![Kotlin](https://img.shields.io/badge/Kotlin-Modern-purple?style=for-the-badge)

## Features

- **Full Parity with PlacesRecommendation API**: Supports address search, coordinate-based search (My Location), filtering by Categories, Radius distances, and exact Max Results bounds.
- **Dynamic AI Configuration**: Define your LLM API keys on the fly. Dynamically load and assign available generation models across multiple providers including *OpenRouter*, *OpenAI*, *Anthropic*, *Gemini*, and *Azure OpenAI*.
- **Modern Jetpack Compose UI**: Glassmorphism visuals, smooth state transitions, infinite scrolling menus, and seamless Light/Dark mode themes.
- **My Location Support**: Effortlessly centers searches on your immediate surroundings without manual typing.
- **Distance Display**: See real-time walking/driving distance from your current location to each result.
- **Navigation & Sharing**: Tap any address to open Google Maps navigation or share the location with others.
- **Google Places Integration**: Add your Google Places API key to enrich results with real-world ratings, review counts, and direct website links for every place.
- **Responsive Highlight Tags**: Category highlight tags in place cards now wrap gracefully across multiple lines.

## What's New in v1.1

- Distance to each place shown in the result card (requires location permission)
- Tap address row → dialog with **Navigate**, **Share**, and **Website** actions
- Real Google Places ratings (★) and review counts pulled live when Google API key is configured
- Website link per place (opens in browser)
- Category chip labels now split camelCase correctly (e.g. `TouristAttraction` → `Tourist Attraction`)
- Highlight tags in cards no longer overflow off-screen — they wrap to next line

## Tech Stack

- **UI**: Android Jetpack Compose
- **Language**: Kotlin
- **Networking**: Retrofit2 + OkHttp3
- **Location**: Google Play Services — FusedLocationProviderClient
- **External APIs**: PlacesRecommendation backend · Google Places API (New)
- **Device Support**: Android 7.0+ (Min SDK 24)

## Configuration

Navigate to the `Settings ⚙` gear in the top right to configure your API keys.

| Key | Purpose |
|-----|---------|
| OpenRouter / OpenAI / Anthropic / Gemini / AzureOpenAI | LLM provider for AI recommendations |
| **Google Places** | Enriches results with real ratings, review counts & website links |

*Note:* Keys set in this UI are injected per-request and override the backend's default configuration.

## Download

Grab the latest compiled APK from the **[Releases](../../releases)** tab.
