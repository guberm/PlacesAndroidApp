# Places Android App

A modern, native Android client built with **Jetpack Compose** that finds nearby places from Google Maps through a private Places API, with OpenStreetMap fallback.

![Version](https://img.shields.io/badge/version-1.29-green?style=for-the-badge)
![Jetpack Compose](https://img.shields.io/badge/Jetpack_Compose-Premium_UI-blue?style=for-the-badge)
![Kotlin](https://img.shields.io/badge/Kotlin-Modern-purple?style=for-the-badge)

## Features

- **Google Maps Place Search**: Uses `places.guber.dev` for Google Maps place data including ratings, phone numbers, websites, and opening details.
- **Contextual Subcategory Filters**: Selecting categories opens wrapped chips, such as restaurant cuisines or Tourist Attraction filters like Kids, Romantic, Sport, Nature, Historic, and Viewpoints.
- **OpenStreetMap Fallback**: Falls back to OpenStreetMap / Photon / Overpass when the private API key is missing or the API request fails.
- **Address Autocomplete**: Start typing any address or city — suggestions appear instantly via Photon, works for any country worldwide.
- **My Location Support**: Tap the GPS icon to auto-detect your location with a full street address (house number + road + city + province).
- **Rich Result Cards**: Cards show rating, address, phone number, and straight-line distance when coordinates are available.
- **Call, Navigation & Sharing**: Tap a card to call, navigate, open the website, or share the place.
- **Radius Enforcement**: Client-side distance filtering ensures results stay within the requested radius.
- **Modern Jetpack Compose UI**: Glassmorphism visuals, smooth state transitions, Light/Dark mode themes.
- **Auto-Update**: Checks GitHub Releases on launch and prompts to install newer versions in one tap.
- **Diagnostic Logs**: Share logs from Settings.

## Tech Stack

- **UI**: Jetpack Compose + Material3
- **Networking**: OkHttp3
- **Location**: Google Play Services — FusedLocationProviderClient
- **Geocoding / Autocomplete**: Photon / OpenStreetMap, with Nominatim fallback
- **Place Search**: Private Places API backed by Google Maps scraping, with OpenStreetMap fallback
- **Device Support**: Android 7.0+ (Min SDK 24)

## Configuration

For Google Maps-backed results, add the private Places API key locally:

```properties
placesApiKey=your_api_key_here
```

Put it in `local.properties` or set `PLACES_API_KEY` in the build environment. The app still falls back to OpenStreetMap when the key is empty.

## Download

Grab the latest compiled APK from the **[Releases](../../releases)** tab.
