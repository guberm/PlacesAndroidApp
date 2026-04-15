# Places Android App

A modern, native Android client built with **Jetpack Compose** that calls AI provider APIs **directly on-device** — no backend server require

![Version](https://img.shields.io/badge/version-1.13-green?style=for-the-badge)
![Jetpack Compose](https://img.shields.io/badge/Jetpack_Compose-Premium_UI-blue?style=for-the-badge)
![Kotlin](https://img.shields.io/badge/Kotlin-Modern-purple?style=for-the-badge)

## Features

- **Full On-Device AI**: Calls AI provider APIs directly from the device (OpenRouter, OpenAI, Anthropic, Gemini, AzureOpenAI) — no backend server needed.
- **Auto-Update**: Checks GitHub Releases on launch and prompts to install newer versions in one tap.
- **Radius Enforcement**: Client-side distance filtering ensures results stay within the requested radius; falls back to the closest places if the AI returns nothing nearby.
- **Straight-Line Distance**: Distance badge on each card shows aerial distance with a clear label
- **Auto-Update**: Checks GitHub Releases on launch and prompts to install newer versions in one tap.
- **Radius Enforcement**: Client-side distance filtering ensures results stay within the requested radius; falls back to the closest places if the AI returns nothing nearby.
- **Straight-Line Distance**: Distance badge on each card shows aerial distance with a clear label.
- **Dynamic AI Configuration**: Define your LLM API keys on the fly. Dynamically load and assign available generation models across multiple providers including *OpenRouter*, *OpenAI*, *Anthropic*, *Gemini*, and *Azure OpenAI*.
- **Modern Jetpack Compose UI**: Glassmorphism visuals, smooth state transitions, infinite scrolling menus, and seamless Light/Dark mode themes.
- **My Location Support**: Effortlessly centers searches on your immediate surroundings without manual typing.
- **Distance Display**: See real-time walking/driving distance from your current location to each result.
- **Navigation & Sharing**: Tap any address to open Google Maps navigation or share the location with others.
- **Google Places Integration**: Add your Google Places API key to enrich results with real-world ratings, review counts, and direct website links for every place.
- **Responsive Highlight Tags**: Category highlight tags in place cards now wrap gracefully across multiple lines.

## Tech Stack
OkHttp3 (direct AI API calls)
- **Location**: Google Play Services — FusedLocationProviderClient
- **Geocoding**: Nominatim (OpenStreetMap) — free, no API key required
- **External APIs**: OpenRouter · OpenAI · Anthropic · Gemini · Azure OpenAI
- **Networking**: OkHttp3 (direct AI API calls)
- **Location**: Google Play Services — FusedLocationProviderClient
- **Geocoding**: Nominatim (OpenStreetMap) — free, no API key required
- **External APIs**: OpenRouter · OpenAI · Anthropic · Gemini · Azure OpenAI · Google Places API (New)
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
