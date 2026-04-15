# Places Android App

A modern, native Android client built with **Jetpack Compose** that calls AI provider APIs **directly on-device** — no backend server required.

![Version](https://img.shields.io/badge/version-1.16-green?style=for-the-badge)
![Jetpack Compose](https://img.shields.io/badge/Jetpack_Compose-Premium_UI-blue?style=for-the-badge)
![Kotlin](https://img.shields.io/badge/Kotlin-Modern-purple?style=for-the-badge)

## Features

- **Full On-Device AI**: Calls AI provider APIs directly from the device (OpenRouter, OpenAI, Anthropic, Gemini, AzureOpenAI) — no backend server needed.
- **Address Autocomplete**: Start typing any address or city — suggestions appear instantly via Nominatim, works for any country worldwide.
- **My Location Support**: Tap the GPS icon to auto-detect your location with a full street address (house number + road + city + province).
- **Accurate Distances**: Place coordinates are always verified via Nominatim geocoding — AI-hallucinated coordinates are never trusted for distance calculation.
- **Straight-Line Distance**: Distance badge on each card shows aerial distance with a clear "straight line" label.
- **Navigation & Sharing**: Tap any address to open Google Maps navigation or share the location with others.
- **Google Places Integration**: Add your Google Places API key to enrich results with real-world ratings, review counts, and direct website links.
- **Radius Enforcement**: Client-side distance filtering ensures results stay within the requested radius; falls back to the closest places if the AI returns nothing nearby.
- **Dynamic AI Configuration**: Configure LLM API keys on the fly and load available models per provider (OpenRouter, OpenAI, Anthropic, Gemini, Azure OpenAI).
- **Modern Jetpack Compose UI**: Glassmorphism visuals, smooth state transitions, Light/Dark mode themes.
- **Auto-Update**: Checks GitHub Releases on launch and prompts to install newer versions in one tap.
- **Diagnostic Logs**: Share masked logs from Settings — API keys are automatically redacted before sharing.

## Tech Stack

- **UI**: Jetpack Compose + Material3
- **Networking**: OkHttp3 (direct AI API calls)
- **Location**: Google Play Services — FusedLocationProviderClient
- **Geocoding / Autocomplete**: Nominatim (OpenStreetMap) — free, no API key required
- **External APIs**: OpenRouter · OpenAI · Anthropic · Gemini · Azure OpenAI · Google Places API (New)
- **Device Support**: Android 7.0+ (Min SDK 24)

## Configuration

Navigate to the `Settings ⚙` gear in the top right to configure your API keys.

| Key | Purpose |
|-----|---------|
| OpenRouter / OpenAI / Anthropic / Gemini / AzureOpenAI | LLM provider for AI recommendations |
| **Google Places** | Enriches results with real ratings, review counts & website links |

Keys are persisted in SharedPreferences and survive app reinstalls/updates.

## Download

Grab the latest compiled APK from the **[Releases](../../releases)** tab.
