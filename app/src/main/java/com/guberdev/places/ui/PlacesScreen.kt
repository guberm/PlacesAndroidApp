package com.guberdev.places.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Environment
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.guberdev.places.data.model.AddressSuggestion
import com.guberdev.places.data.model.PlaceRecommendation
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

// ── Location helper ──────────────────────────────────────────────────────────

/**
 * Get a fresh location fix. Tries getCurrentLocation (active GPS/network) first,
 * falls back to lastLocation (cached) if the fresh fix returns null.
 */
private suspend fun getFreshLocation(
    fusedClient: com.google.android.gms.location.FusedLocationProviderClient,
    hasFine: Boolean
): android.location.Location? {
    val priority = if (hasFine) Priority.PRIORITY_HIGH_ACCURACY else Priority.PRIORITY_BALANCED_POWER_ACCURACY
    return try {
        val cts = CancellationTokenSource()
        val fresh = suspendCancellableCoroutine<android.location.Location?> { cont ->
            cont.invokeOnCancellation { cts.cancel() }
            fusedClient.getCurrentLocation(priority, cts.token)
                .addOnSuccessListener { cont.resume(it) }
                .addOnFailureListener { cont.resume(null) }
        }
        fresh ?: suspendCancellableCoroutine { cont ->
            fusedClient.lastLocation
                .addOnSuccessListener { cont.resume(it) }
                .addOnFailureListener { cont.resume(null) }
        }
    } catch (e: Exception) {
        Log.w("Location", "getFreshLocation failed: ${e.message}")
        null
    }
}

// ── Log helpers ──────────────────────────────────────────────────────────────

private fun maskKeys(raw: String, keysToMask: Map<String, String>): String {
    var masked = raw
    keysToMask.values.filter { it.length >= 6 }.forEach { key ->
        masked = masked.replace(key, "${key.take(4)}****REDACTED****")
    }
    return masked
}

private fun collectMaskedLog(keysToMask: Map<String, String>): String? = try {
    val raw = Runtime.getRuntime()
        .exec(arrayOf("logcat", "-d", "-v", "time", "*:D"))
        .inputStream.bufferedReader().readText()
    maskKeys(raw, keysToMask)
} catch (e: Exception) {
    Log.e("PlacesVM", "collectMaskedLog failed: ${e.message}", e)
    null
}

private fun collectAndShareLogs(context: Context, keysToMask: Map<String, String>) {
    val masked = collectMaskedLog(keysToMask) ?: return
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, "PlacesApp diagnostic log")
        putExtra(Intent.EXTRA_TEXT, masked)
    }
    context.startActivity(Intent.createChooser(intent, "Share log"))
}

private fun exportLogsToFile(context: Context, keysToMask: Map<String, String>) {
    val masked = collectMaskedLog(keysToMask) ?: return
    try {
        val fileName = "places-log-${System.currentTimeMillis()}.txt"
        val file = java.io.File(context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS), fileName)
        file.writeText(masked)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "PlacesApp log export")
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Export log file"))
    } catch (e: Exception) {
        Log.e("PlacesVM", "exportLogsToFile failed: ${e.message}", e)
    }
}

private fun clearLogs() {
    try { Runtime.getRuntime().exec(arrayOf("logcat", "-c")) }
    catch (e: Exception) { Log.e("PlacesVM", "clearLogs failed: ${e.message}", e) }
}

class ThemeColors(isDark: Boolean) {
    val bgGradient = if (isDark) listOf(Color(0xFF0F172A), Color(0xFF1E293B)) else listOf(Color(0xFFF8FAFC), Color(0xFFE2E8F0))
    val textPrime = if (isDark) Color.White else Color(0xFF0F172A)
    val textSec = if (isDark) Color(0xFF94A3B8) else Color(0xFF475569)
    val container = if (isDark) Color(0xFF1E293B) else Color.White
    val primary = if (isDark) Color(0xFF3B82F6) else Color(0xFF2563EB)
    val scoreBg = if (isDark) Color(0xFF3B82F6).copy(alpha = 0.2f) else Color(0xFFDBEAFE)
    val scoreText = if (isDark) Color(0xFF60A5FA) else Color(0xFF1D4ED8)
    val pillBg = if (isDark) Color(0xFF334155) else Color(0xFFF1F5F9)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlacesScreen(viewModel: PlacesViewModel = viewModel()) {
    val uiState by viewModel.uiState.collectAsState()
    val suggestions by viewModel.suggestions.collectAsState()
    var searchQuery by remember { mutableStateOf("") }
    var selectedCategory by remember { mutableStateOf("All") }
    var selectedSubcategory by remember { mutableStateOf("Any") }
    
    // New parameters 
    var radius by remember { mutableStateOf(1000f) }
    var maxResults by remember { mutableStateOf(10) }
    var forceRefresh by remember { mutableStateOf(false) }

    var isDarkTheme by remember { mutableStateOf(true) }

    var userLat by remember { mutableStateOf<Double?>(null) }
    var userLng by remember { mutableStateOf<Double?>(null) }
    val scope = rememberCoroutineScope()

    val context = LocalContext.current
    var showSettings by remember { mutableStateOf(false) }
    var pendingUpdate by remember { mutableStateOf<com.guberdev.places.data.UpdateInfo?>(null) }
    val fusedLocationClient = remember { LocationServices.getFusedLocationProviderClient(context) }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val hasFine = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true
        val hasCoarse = permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (hasFine || hasCoarse) {
            searchQuery = "Locating…"
            scope.launch {
                val location = getFreshLocation(fusedLocationClient, hasFine)
                if (location != null) {
                    userLat = location.latitude; userLng = location.longitude
                    searchQuery = viewModel.reverseGeocodeFullAddress(location.latitude, location.longitude)
                        ?: "My Location"
                } else { searchQuery = "" }
            }
        }
    }

    fun fetchCurrentLocation() {
        val hasFine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val hasCoarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (hasFine || hasCoarse) {
            searchQuery = "Locating…"
            scope.launch {
                val location = getFreshLocation(fusedLocationClient, hasFine)
                if (location != null) {
                    userLat = location.latitude; userLng = location.longitude
                    searchQuery = viewModel.reverseGeocodeFullAddress(location.latitude, location.longitude)
                        ?: "My Location"
                } else { searchQuery = "" }
            }
        } else {
            permissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
        }
    }

    val categories = listOf("All", "Restaurant", "Cafe", "TouristAttraction", "Museum", "Park", "Bar", "Hotel", "Shopping", "Entertainment")
    val subcategories = subcategoriesFor(selectedCategory)
    val colors = ThemeColors(isDarkTheme)

    LaunchedEffect(selectedCategory) {
        selectedSubcategory = "Any"
    }

    if (showSettings) {
        SettingsDialog(colors, onDismiss = { showSettings = false })
    }

    // Auto-fetch location + check for updates on launch
    LaunchedEffect(Unit) {
        // If permission is already granted, get a fresh location fix immediately
        val hasFine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val hasCoarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (hasFine || hasCoarse) {
            searchQuery = "Locating…"
            val location = getFreshLocation(fusedLocationClient, hasFine)
            if (location != null) {
                userLat = location.latitude; userLng = location.longitude
                searchQuery = viewModel.reverseGeocodeFullAddress(location.latitude, location.longitude)
                    ?: "My Location"
            } else { searchQuery = "" }
        }
        // Check for app updates
        val installedVersion = try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: ""
        } catch (e: Exception) { "" }
        val update = com.guberdev.places.data.UpdateChecker.check(installedVersion)
        if (update != null) pendingUpdate = update
    }

    pendingUpdate?.let { update ->
        UpdateDialog(
            colors = colors,
            update = update,
            onDismiss = { pendingUpdate = null },
            onUpdate = { url ->
                pendingUpdate = null
                downloadAndInstallApk(context, url)
            }
        )
    }

    Column(modifier = Modifier.fillMaxSize().background(Brush.verticalGradient(colors.bgGradient)).padding(top = 48.dp, start = 16.dp, end = 16.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column {
                Text("Discover Places", style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold, color = colors.textPrime))
                Text("Find real nearby places from Google Maps.", style = MaterialTheme.typography.bodyMedium.copy(color = colors.textSec))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { showSettings = true }) { Icon(Icons.Default.Settings, contentDescription = "Settings", tint = colors.textSec) }
                Switch(checked = isDarkTheme, onCheckedChange = { isDarkTheme = it }, colors = SwitchDefaults.colors(checkedThumbColor = colors.primary, checkedTrackColor = colors.primary.copy(alpha = 0.5f)))
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        Box(modifier = Modifier.fillMaxWidth()) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = {
                    searchQuery = it
                    userLat = null
                    userLng = null
                    viewModel.onSearchQueryChanged(it)
                },
                placeholder = { Text("City, Address or Lat,Lng", color = colors.textSec) },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = "Search", tint = colors.textSec) },
                trailingIcon = {
                    IconButton(onClick = {
                        viewModel.clearSuggestions()
                        fetchCurrentLocation()
                    }) { Icon(Icons.Default.LocationOn, contentDescription = "Location", tint = colors.primary) }
                },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = TextFieldDefaults.outlinedTextFieldColors(containerColor = colors.container, unfocusedBorderColor = Color.Transparent, focusedBorderColor = colors.primary, focusedTextColor = colors.textPrime, unfocusedTextColor = colors.textPrime),
                singleLine = true
            )
            DropdownMenu(
                expanded = suggestions.isNotEmpty(),
                onDismissRequest = { viewModel.clearSuggestions() },
                modifier = Modifier.fillMaxWidth().background(colors.container)
            ) {
                suggestions.forEach { suggestion ->
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(suggestion.shortLine, color = colors.textPrime, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                if (suggestion.secondLine.isNotBlank())
                                    Text(suggestion.secondLine, color = colors.textSec, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        },
                        leadingIcon = {
                            Icon(Icons.Default.LocationOn, contentDescription = null, tint = colors.textSec, modifier = Modifier.size(16.dp))
                        },
                        onClick = {
                            searchQuery = suggestion.displayName
                            userLat = suggestion.latitude
                            userLng = suggestion.longitude
                            viewModel.clearSuggestions()
                        }
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(horizontal = 2.dp)
        ) {
            items(categories) { category ->
                CategoryChip(
                    text = category, isSelected = category == selectedCategory, colors = colors,
                    onClick = { selectedCategory = category }
                )
            }
        }

        AnimatedVisibility(visible = subcategories.isNotEmpty()) {
            Column {
                Spacer(modifier = Modifier.height(10.dp))
                @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 2.dp)
                ) {
                    subcategories.forEach { type ->
                        CategoryChip(
                            text = type,
                            isSelected = type == selectedSubcategory,
                            colors = colors,
                            onClick = { selectedSubcategory = type }
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
        
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Radius: ${radius.toInt()}m", color = colors.textSec, fontSize = 12.sp)
                Slider(
                    value = radius,
                    onValueChange = { radius = it },
                    valueRange = 500f..5000f,
                    steps = 8,
                    colors = SliderDefaults.colors(thumbColor = colors.primary, activeTrackColor = colors.primary)
                )
            }
            Spacer(modifier = Modifier.width(16.dp))
            Column {
                Text("Max", color = colors.textSec, fontSize = 12.sp)
                var expanded by remember { mutableStateOf(false) }
                Box {
                    TextButton(onClick = { expanded = true }) { Text("$maxResults", color = colors.textPrime) }
                    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }, modifier = Modifier.background(colors.container)) {
                        listOf(5, 10, 15, 20).forEach { 
                            DropdownMenuItem(text = { Text("$it", color = colors.textPrime) }, onClick = { maxResults = it; expanded = false }) 
                        }
                    }
                }
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = forceRefresh, onCheckedChange = { forceRefresh = it }, colors = CheckboxDefaults.colors(checkedColor = colors.primary))
            Text("Force Refresh (bypass cache)", color = colors.textSec, fontSize = 14.sp)
        }

        Spacer(modifier = Modifier.height(8.dp))

        Button(
            onClick = {
                if (searchQuery.isNotEmpty() || (userLat != null && userLng != null)) {
                    viewModel.clearSuggestions()
                    viewModel.searchPlaces(
                        query = searchQuery,
                        lat = userLat,
                        lng = userLng,
                        category = selectedCategory,
                        subcategory = selectedSubcategory.takeIf { subcategories.isNotEmpty() && it != "Any" },
                        radiusMeters = radius.toInt(),
                        maxResults = maxResults,
                        forceRefresh = forceRefresh
                    )
                }
            },
            modifier = Modifier.fillMaxWidth().height(56.dp),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(containerColor = colors.primary)
        ) {
            Text("Explore", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color.White)
        }

        Spacer(modifier = Modifier.height(16.dp))

        Crossfade(targetState = uiState, label = "State Animation") { state ->
            when (state) {
                is PlacesUiState.Initial -> Box(Modifier.fillMaxSize(), Alignment.Center) { Text("Search for a location to begin.", color = colors.textSec) }
                is PlacesUiState.Loading -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            CircularProgressIndicator(color = colors.primary)
                            if (state.slowWarning) {
                                Text(
                                    "This is taking longer than usual…\nPlaces data is still loading.",
                                    color = colors.textSec,
                                    fontSize = 13.sp,
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                                )
                            }
                        }
                    }
                is PlacesUiState.Success -> {
                    // Use the response's resolved origin (works for both GPS and typed address)
                    val originLat = state.response.latitude.takeIf { it != 0.0 } ?: userLat
                    val originLng = state.response.longitude.takeIf { it != 0.0 } ?: userLng
                    if (state.response.recommendations.isEmpty()) {
                        Box(Modifier.fillMaxSize(), Alignment.Center) {
                            Text("No ${selectedCategory.replace(Regex("(?<=[a-z])(?=[A-Z])"), " ").lowercase()} found within ${radius.toInt()}m.", color = colors.textSec)
                        }
                    } else {
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
                            items(state.response.recommendations) { place -> PlaceCard(place, colors, originLat, originLng) }
                        }
                    }
                }
                is PlacesUiState.Error -> Box(Modifier.fillMaxSize(), Alignment.Center) { Text("Error: ${state.message}", color = Color(0xFFEF4444)) }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsDialog(colors: ThemeColors, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
        modifier = Modifier.fillMaxWidth(0.95f).fillMaxHeight(0.9f),
        containerColor = colors.container,
        title = { Text("Settings", color = colors.textPrime, fontWeight = FontWeight.Bold) },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(24.dp)) {
                item {
                    Text("Search uses your Places API with OpenStreetMap fallback.", color = colors.textSec, fontSize = 13.sp)
                }
            }
        },
        confirmButton = { Button(colors = ButtonDefaults.buttonColors(containerColor = colors.primary), onClick = onDismiss) { Text("Close", color = Color.White) } },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                val ctx = LocalContext.current
                var logsMenuExpanded by remember { mutableStateOf(false) }
                var showClearedToast by remember { mutableStateOf(false) }

                // Logs dropdown
                Box {
                    TextButton(onClick = { logsMenuExpanded = true }) {
                        Text("Logs ▾", color = colors.textSec, fontSize = 12.sp)
                    }
                    DropdownMenu(
                        expanded = logsMenuExpanded,
                        onDismissRequest = { logsMenuExpanded = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text("Share", color = colors.textPrime, fontSize = 13.sp) },
                            onClick = { logsMenuExpanded = false; collectAndShareLogs(ctx, emptyMap()) }
                        )
                        DropdownMenuItem(
                            text = { Text("Export as file", color = colors.textPrime, fontSize = 13.sp) },
                            onClick = { logsMenuExpanded = false; exportLogsToFile(ctx, emptyMap()) }
                        )
                        Divider(modifier = Modifier.padding(horizontal = 8.dp), color = colors.pillBg)
                        DropdownMenuItem(
                            text = { Text("Clear logs", color = Color(0xFFEF4444), fontSize = 13.sp) },
                            onClick = { logsMenuExpanded = false; clearLogs(); showClearedToast = true }
                        )
                    }
                }

                if (showClearedToast) {
                    LaunchedEffect(Unit) {
                        kotlinx.coroutines.delay(1500)
                        showClearedToast = false
                    }
                    Text("Logs cleared", color = colors.textSec, fontSize = 11.sp)
                }

                TextButton(onClick = onDismiss) { Text("Cancel", color = colors.textSec) }
            }
        }
    )
}

@Composable
fun CategoryChip(text: String, isSelected: Boolean, colors: ThemeColors, onClick: () -> Unit) {
    val bgColor = if (isSelected) colors.primary else colors.container
    val textColor = if (isSelected) Color.White else colors.textSec
    val label = text.replace(Regex("(?<=[a-z])(?=[A-Z])"), " ")
    Box(modifier = Modifier.clip(RoundedCornerShape(20.dp)).background(bgColor).clickable { onClick() }.padding(horizontal = 14.dp, vertical = 8.dp), contentAlignment = Alignment.Center) {
        Text(text = label, color = textColor, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, maxLines = 1)
    }
}

private fun subcategoriesFor(category: String): List<String> = when (category) {
    "Restaurant" -> listOf("Any", "Sushi", "Japanese", "Indian", "Chinese", "Pizza", "Burgers", "Mexican", "Italian", "Thai", "Middle Eastern", "Seafood", "Steakhouse", "Vegetarian", "Breakfast", "Fast Food", "BBQ", "Sandwich")
    "Cafe" -> listOf("Any", "Coffee", "Tea", "Bakery", "Breakfast", "Dessert", "Work Friendly", "Patio")
    "TouristAttraction" -> listOf("Any", "Kids", "Romantic", "Sport", "Nature", "Historic", "Viewpoints", "Tours", "Free")
    "Museum" -> listOf("Any", "Art", "History", "Science", "Kids", "Gallery", "Local")
    "Park" -> listOf("Any", "Playground", "Trail", "Picnic", "Dog Park", "Sports", "Waterfront", "Garden")
    "Bar" -> listOf("Any", "Pub", "Cocktail", "Wine", "Sports Bar", "Live Music", "Brewery", "Patio")
    "Hotel" -> listOf("Any", "Budget", "Family", "Romantic", "Luxury", "Pet Friendly", "Pool", "Spa")
    "Shopping" -> listOf("Any", "Mall", "Grocery", "Pharmacy", "Clothing", "Electronics", "Market", "Outlet")
    "Entertainment" -> listOf("Any", "Kids", "Romantic", "Sport", "Cinema", "Theatre", "Arcade", "Bowling", "Live Music", "Nightlife")
    else -> emptyList()
}

@Composable
fun UpdateDialog(
    colors: ThemeColors,
    update: com.guberdev.places.data.UpdateInfo,
    onDismiss: () -> Unit,
    onUpdate: (String) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.container,
        title = { Text("Update available — v${update.versionName}", color = colors.textPrime, fontWeight = FontWeight.Bold) },
        text = {
            LazyColumn {
                item {
                    Text("What's new:", color = colors.textPrime, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        update.releaseNotes.ifBlank { "No release notes." },
                        color = colors.textSec,
                        fontSize = 13.sp,
                        lineHeight = 20.sp
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onUpdate(update.downloadUrl) },
                colors = ButtonDefaults.buttonColors(containerColor = colors.primary)
            ) { Text("Update", color = Color.White) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Later", color = colors.textSec) }
        }
    )
}

fun downloadAndInstallApk(context: android.content.Context, url: String) {
    kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.IO) {
        try {
            val client = okhttp3.OkHttpClient.Builder()
                .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(180, java.util.concurrent.TimeUnit.SECONDS)
                .build()
            val req = okhttp3.Request.Builder().url(url).build()
            val bytes = client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@launch
                resp.body?.bytes() ?: return@launch
            }
            val file = java.io.File(context.cacheDir, "update.apk")
            file.writeBytes(bytes)
            val uri = androidx.core.content.FileProvider.getUriForFile(
                context, "${context.packageName}.provider", file
            )
            val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e("UpdateInstall", "Download/install failed: ${e.message}")
        }
    }
}

@Composable
fun PlaceDetailLine(label: String, value: String, colors: ThemeColors, clickable: Boolean = false, onClick: () -> Unit = {}) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (clickable) Modifier.clip(RoundedCornerShape(8.dp)).clickable { onClick() } else Modifier)
            .padding(vertical = 3.dp),
        verticalAlignment = Alignment.Top
    ) {
        Text("$label:", color = colors.textSec, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(68.dp))
        Text(value, color = colors.textPrime.copy(alpha = 0.85f), fontSize = 13.sp, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun PlaceCard(place: PlaceRecommendation, colors: ThemeColors, userLat: Double? = null, userLng: Double? = null) {
    var showDialog by remember { mutableStateOf(false) }
    val context = LocalContext.current

    val distanceInfo = remember(userLat, userLng, place.latitude, place.longitude) {
        if (userLat != null && userLng != null && place.latitude != null && place.longitude != null) {
            val results = FloatArray(1)
            android.location.Location.distanceBetween(userLat, userLng, place.latitude, place.longitude, results)
            val d = results[0]
            val label = if (d < 1000) "${d.toInt()} m" else "${"%,.1f".format(d / 1000)} km"
            Pair(label, d)
        } else null
    }

    if (showDialog) {
        AlertDialog(
            onDismissRequest = { showDialog = false },
            containerColor = colors.container,
            title = { Text(place.name, color = colors.textPrime, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            text = {
                Column {
                    place.address?.let { Text(it, color = colors.textSec, fontSize = 14.sp) }
                    place.phoneNumber?.let {
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(it, color = colors.textSec, fontSize = 14.sp)
                    }
                    place.websiteUri?.let {
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(it, color = colors.primary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            },
            confirmButton = {
                Row {
                    place.websiteUri?.let { url ->
                        TextButton(onClick = {
                            showDialog = false
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                        }) { Text("Website", color = colors.scoreText) }
                    }
                    place.phoneNumber?.let { phone ->
                        TextButton(onClick = {
                            showDialog = false
                            context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:${Uri.encode(phone)}")))
                        }) { Text("Call", color = colors.scoreText) }
                    }
                    Button(
                        onClick = {
                            showDialog = false
                            val navAddress = place.address?.takeIf { it.isNotBlank() }
                            if (navAddress != null) {
                                val navIntent = Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=${Uri.encode(navAddress)}")).apply { setPackage("com.google.android.apps.maps") }
                                try { context.startActivity(navIntent) } catch (e: Exception) {
                                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=${Uri.encode(navAddress)}")))
                                }
                            } else {
                                val lat = place.latitude ?: 0.0
                                val lng = place.longitude ?: 0.0
                                val navIntent = Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=$lat,$lng")).apply { setPackage("com.google.android.apps.maps") }
                                try { context.startActivity(navIntent) } catch (e: Exception) {
                                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("geo:$lat,$lng?q=$lat,$lng(${Uri.encode(place.name)})")))
                                }
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = colors.primary)
                    ) { Text("Navigate", color = Color.White) }
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showDialog = false
                    val shareText = buildString {
                        append(place.name)
                        place.address?.let { append("\n$it") }
                        place.phoneNumber?.let { append("\n$it") }
                        if (place.latitude != null && place.longitude != null)
                            append("\nhttps://maps.google.com/?q=${place.latitude},${place.longitude}")
                        place.websiteUri?.let { append("\n$it") }
                    }
                    context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, shareText)
                    }, null))
                }) { Text("Share", color = colors.primary) }
            }
        )
    }

    Card(
        modifier = Modifier.fillMaxWidth().clickable { showDialog = true },
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = colors.container.copy(alpha = 0.8f))
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(place.name, style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold, color = colors.textPrime), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Box(modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(colors.scoreBg).padding(horizontal = 8.dp, vertical = 4.dp)) {
                    Text(
                        place.rating?.let { "\u2605 ${"%,.1f".format(it)}" } ?: "No rating",
                        color = colors.scoreText,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp
                    )
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            PlaceDetailLine("Rating", place.rating?.let { "${"%,.1f".format(it)}${place.userRatingsTotal?.let { total -> " ($total reviews)" } ?: ""}" } ?: "No rating", colors)
            PlaceDetailLine("Address", place.address ?: "Address unavailable", colors, clickable = place.address != null) { showDialog = true }
            PlaceDetailLine("Phone", place.phoneNumber ?: "Phone unavailable", colors)
            val distanceText = distanceInfo?.let { (label, _) -> "$label straight line" } ?: "Distance unavailable"
            PlaceDetailLine("Distance", distanceText, colors)
            Spacer(modifier = Modifier.height(8.dp))
            Text(place.description, color = colors.textPrime.copy(alpha = 0.8f), fontSize = 15.sp, lineHeight = 22.sp)
            if (place.highlights.isNotEmpty()) {
                Spacer(modifier = Modifier.height(12.dp))
                @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    place.highlights.take(3).forEach { highlight ->
                        Box(modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(colors.pillBg).padding(horizontal = 8.dp, vertical = 4.dp)) {
                            Text(highlight, color = colors.textSec, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
}
