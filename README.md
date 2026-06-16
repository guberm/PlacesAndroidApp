# Places Android App

A modern, native Android client built with **Jetpack Compose** that finds nearby places from OpenStreetMap — no backend server or API keys required.

![Version](https://img.shields.io/badge/version-1.27-green?style=for-the-badge)
![Jetpack Compose](https://img.shields.io/badge/Jetpack_Compose-Premium_UI-blue?style=for-the-badge)
![Kotlin](https://img.shields.io/badge/Kotlin-Modern-purple?style=for-the-badge)

## Features

- **Keyless Place Search**: Finds real nearby places from OpenStreetMap via Overpass — no LLM or Google Places keys.
- **Address Autocomplete**: Start typing any address or city — suggestions appear instantly via Nominatim, works for any country worldwide.
- **My Location Support**: Tap the GPS icon to auto-detect your location with a full street address (house number + road + city + province).
- **Accurate Distances**: Place coordinates come from OpenStreetMap and are filtered client-side by radius.
- **Straight-Line Distance**: Distance badge on each card shows aerial distance with a clear "straight line" label.
- **Navigation & Sharing**: Tap any address to open Google Maps navigation or share the location with others.
- **Radius Enforcement**: Client-side distance filtering ensures results stay within the requested radius.
- **Modern Jetpack Compose UI**: Glassmorphism visuals, smooth state transitions, Light/Dark mode themes.
- **Auto-Update**: Checks GitHub Releases on launch and prompts to install newer versions in one tap.
- **Diagnostic Logs**: Share logs from Settings.

## Tech Stack

- **UI**: Jetpack Compose + Material3
- **Networking**: OkHttp3
- **Location**: Google Play Services — FusedLocationProviderClient
- **Geocoding / Autocomplete**: Photon / OpenStreetMap — free, no API key required
- **Place Search**: Overpass API / OpenStreetMap — free, no API key required
- **Device Support**: Android 7.0+ (Min SDK 24)

## Configuration

No API keys are required.

## Download

Grab the latest compiled APK from the **[Releases](../../releases)** tab.
