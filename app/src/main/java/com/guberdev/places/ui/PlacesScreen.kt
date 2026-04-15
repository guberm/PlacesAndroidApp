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
import com.guberdev.places.data.model.PlaceRecommendation
import com.guberdev.places.data.model.ProviderModel
import kotlinx.coroutines.launch

// ── Log helpers ──────────────────────────────────────────────────────────────

private fun maskKeys(raw: String, keysToMask: Map<String, String>): String {
    var masked = raw
    keysToMask.values.filter { it.length >= 6 }.forEach { key ->
        masked = masked.replace(key, "${key.take(4)}****REDACTED****")
    }
    return masked
}

private fun collectMaskedLog(context: Context, keysToMask: Map<String, String>): String? = try {
    val raw = Runtime.getRuntime()
        .exec(arrayOf("logcat", "-d", "-v", "time", "*:D"))
        .inputStream.bufferedReader().readText()
    maskKeys(raw, keysToMask)
} catch (e: Exception) {
    Log.e("PlacesVM", "collectMaskedLog failed: ${e.message}", e)
    null
}

private fun collectAndShareLogs(context: Context, keysToMask: Map<String, String>) {
    val masked = collectMaskedLog(context, keysToMask) ?: return
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, "PlacesApp diagnostic log")
        putExtra(Intent.EXTRA_TEXT, masked)
    }
    context.startActivity(Intent.createChooser(intent, "Share log"))
}

private fun exportLogsToFile(context: Context, keysToMask: Map<String, String>) {
    val masked = collectMaskedLog(context, keysToMask) ?: return
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

// ── Settings import / export ──────────────────────────────────────────

private fun exportKeysToJson(context: Context, keys: Map<String, String>) {
    try {
        val json = org.json.JSONObject()
        keys.forEach { (k, v) -> if (v.isNotBlank()) json.put(k, v) }
        val fileName = "places-settings-${System.currentTimeMillis()}.json"
        val file = java.io.File(context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS), fileName)
        file.writeText(json.toString(2))
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/json"
            putExtra(Intent.EXTRA_SUBJECT, "PlacesApp settings")
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Export settings"))
    } catch (e: Exception) {
        Log.e("PlacesVM", "exportKeysToJson failed: ${e.message}", e)
    }
}

private fun parseKeysFromJson(context: Context, uri: Uri): Map<String, String>? {
    return try {
        val text = context.contentResolver.openInputStream(uri)?.bufferedReader()?.readText()
            ?: return null
        val json = org.json.JSONObject(text)
        buildMap { json.keys().forEach { key -> put(key, json.getString(key)) } }
    } catch (e: Exception) {
        Log.e("PlacesVM", "parseKeysFromJson failed: ${e.message}", e)
        null
    }
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
    var searchQuery by remember { mutableStateOf("") }
    var selectedCategory by remember { mutableStateOf("All") }
    
    // New parameters 
    var radius by remember { mutableStateOf(1000f) }
    var maxResults by remember { mutableStateOf(10) }
    var forceRefresh by remember { mutableStateOf(false) }

    var isDarkTheme by remember { mutableStateOf(true) }

    var userLat by remember { mutableStateOf<Double?>(null) }
    var userLng by remember { mutableStateOf<Double?>(null) }

    val context = LocalContext.current
    var userApiKeys by remember {
        val prefs = context.getSharedPreferences("places_prefs", Context.MODE_PRIVATE)
        val saved = prefs.all
            .filter { it.key.startsWith("api_key_") }
            .mapKeys { it.key.removePrefix("api_key_") }
            .mapValues { it.value as? String ?: "" }
        mutableStateOf<Map<String, String>>(saved)
    }
    var showSettings by remember { mutableStateOf(false) }
    val fusedLocationClient = remember { LocationServices.getFusedLocationProviderClient(context) }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true || permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true) {
            try { fusedLocationClient.lastLocation.addOnSuccessListener { location -> 
                if (location != null) { userLat = location.latitude; userLng = location.longitude; searchQuery = "Current Location" }
            }} catch (e: Exception) {}
        }
    }

    fun fetchCurrentLocation() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            try { fusedLocationClient.lastLocation.addOnSuccessListener { location -> 
                if (location != null) { userLat = location.latitude; userLng = location.longitude; searchQuery = "Current Location" }
            }} catch (e: Exception) {}
        } else {
            permissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
        }
    }

    val categories = listOf("All", "Restaurant", "Cafe", "TouristAttraction", "Museum", "Park", "Bar", "Hotel", "Shopping", "Entertainment")
    val colors = ThemeColors(isDarkTheme)

    if (showSettings) {
        SettingsDialog(colors, viewModel, userApiKeys, onDismiss = { showSettings = false }) { updatedKeys ->
            userApiKeys = updatedKeys
            val prefs = context.getSharedPreferences("places_prefs", Context.MODE_PRIVATE)
            prefs.edit().apply {
                // Clear old keys then write fresh
                prefs.all.keys.filter { it.startsWith("api_key_") }.forEach { remove(it) }
                updatedKeys.forEach { (k, v) -> if (v.isNotBlank()) putString("api_key_$k", v) }
            }.apply()
            showSettings = false
        }
    }

    Column(modifier = Modifier.fillMaxSize().background(Brush.verticalGradient(colors.bgGradient)).padding(top = 48.dp, start = 16.dp, end = 16.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column {
                Text("Discover Places", style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold, color = colors.textPrime))
                Text("Find the best spots curated by AI.", style = MaterialTheme.typography.bodyMedium.copy(color = colors.textSec))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { showSettings = true }) { Icon(Icons.Default.Settings, contentDescription = "Settings", tint = colors.textSec) }
                Switch(checked = isDarkTheme, onCheckedChange = { isDarkTheme = it }, colors = SwitchDefaults.colors(checkedThumbColor = colors.primary, checkedTrackColor = colors.primary.copy(alpha = 0.5f)))
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it; userLat = null; userLng = null },
            placeholder = { Text("City, Address or Lat,Lng", color = colors.textSec) },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = "Search", tint = colors.textSec) },
            trailingIcon = { IconButton(onClick = { fetchCurrentLocation() }) { Icon(Icons.Default.LocationOn, contentDescription = "Location", tint = colors.primary) } },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = TextFieldDefaults.outlinedTextFieldColors(containerColor = colors.container, unfocusedBorderColor = Color.Transparent, focusedBorderColor = colors.primary, focusedTextColor = colors.textPrime, unfocusedTextColor = colors.textPrime),
            singleLine = true
        )

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
                    viewModel.searchPlaces(searchQuery, userLat, userLng, selectedCategory, radius.toInt(), maxResults, forceRefresh, userApiKeys.takeIf { it.isNotEmpty() })
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
                                    "This is taking longer than usual…\nThe AI is still working on your request.",
                                    color = colors.textSec,
                                    fontSize = 13.sp,
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                                )
                            }
                        }
                    }
                is PlacesUiState.Success -> {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
                        items(state.response.recommendations) { place -> PlaceCard(place, colors, userLat, userLng) }
                    }
                }
                is PlacesUiState.Error -> Box(Modifier.fillMaxSize(), Alignment.Center) { Text("Error: ${state.message}", color = Color(0xFFEF4444)) }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsDialog(colors: ThemeColors, viewModel: PlacesViewModel, initialKeys: Map<String, String>, onDismiss: () -> Unit, onSave: (Map<String, String>) -> Unit) {
    var localKeys by remember { mutableStateOf(initialKeys.toMap()) }
    val providers = listOf("OpenRouter", "OpenAI", "Anthropic", "Gemini", "AzureOpenAI")
    val ctx = LocalContext.current

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            parseKeysFromJson(ctx, it)?.let { imported -> localKeys = localKeys + imported }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
        modifier = Modifier.fillMaxWidth(0.95f).fillMaxHeight(0.9f),
        containerColor = colors.container,
        title = { Text("Settings", color = colors.textPrime, fontWeight = FontWeight.Bold) },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(24.dp)) {
                items(providers) { provider ->
                    ProviderSettingsItem(colors, viewModel, provider, localKeys) { k, v ->
                        localKeys = localKeys + (k to v)
                    }
                }
                item {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Text("Google Places", color = colors.textPrime, fontWeight = FontWeight.SemiBold)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text("Enables real ratings, review counts & website links for each result", color = colors.textSec, fontSize = 11.sp)
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(
                            value = localKeys["GooglePlaces"] ?: "",
                            onValueChange = { localKeys = localKeys + ("GooglePlaces" to it) },
                            placeholder = { Text("API Key", color = colors.textSec) },
                            modifier = Modifier.fillMaxWidth(),
                            colors = OutlinedTextFieldDefaults.colors(focusedTextColor = colors.textPrime, unfocusedTextColor = colors.textPrime)
                        )
                    }
                }
            }
        },
        confirmButton = { Button(colors = ButtonDefaults.buttonColors(containerColor = colors.primary), onClick = { onSave(localKeys) }) { Text("Save", color = Color.White) } },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                val ctx = LocalContext.current
                var logsMenuExpanded by remember { mutableStateOf(false) }
                var keysMenuExpanded by remember { mutableStateOf(false) }
                var showClearedToast by remember { mutableStateOf(false) }

                // Keys dropdown
                Box {
                    TextButton(onClick = { keysMenuExpanded = true }) {
                        Text("Keys ▾", color = colors.textSec, fontSize = 12.sp)
                    }
                    DropdownMenu(
                        expanded = keysMenuExpanded,
                        onDismissRequest = { keysMenuExpanded = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("Export to JSON", color = colors.textPrime, fontSize = 13.sp) },
                            onClick = { keysMenuExpanded = false; exportKeysToJson(ctx, localKeys) }
                        )
                        DropdownMenuItem(
                            text = { Text("Import from JSON", color = colors.textPrime, fontSize = 13.sp) },
                            onClick = { keysMenuExpanded = false; importLauncher.launch(arrayOf("application/json", "text/plain", "*/*")) }
                        )
                    }
                }

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
                            onClick = { logsMenuExpanded = false; collectAndShareLogs(ctx, localKeys) }
                        )
                        DropdownMenuItem(
                            text = { Text("Export as file", color = colors.textPrime, fontSize = 13.sp) },
                            onClick = { logsMenuExpanded = false; exportLogsToFile(ctx, localKeys) }
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProviderSettingsItem(colors: ThemeColors, viewModel: PlacesViewModel, provider: String, keys: Map<String, String>, onUpdate: (String, String) -> Unit) {
    var apiKey by remember { mutableStateOf(keys[provider] ?: "") }
    var endpoint by remember { mutableStateOf(keys["AzureOpenAIEndpoint"] ?: "") }
    var selectedModelId by remember { mutableStateOf(keys["${provider}Model"] ?: "") }
    
    var loadedModels by remember { mutableStateOf<List<ProviderModel>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var expanded by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Column(modifier = Modifier.fillMaxWidth()) {
        Text(provider, color = colors.textPrime, fontWeight = FontWeight.SemiBold)
        Spacer(modifier = Modifier.height(8.dp))
        
        OutlinedTextField(
            value = apiKey,
            onValueChange = { apiKey = it.trim(); onUpdate(provider, apiKey) },
            placeholder = { Text("API Key (leave blank for server default)", color = colors.textSec, fontSize = 12.sp) },
            modifier = Modifier.fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors(focusedTextColor = colors.textPrime, unfocusedTextColor = colors.textPrime)
        )
        
        if (provider == "AzureOpenAI") {
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = endpoint,
                onValueChange = { endpoint = it.trim(); onUpdate("AzureOpenAIEndpoint", endpoint) },
                placeholder = { Text("Endpoint URL", color = colors.textSec) },
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(focusedTextColor = colors.textPrime, unfocusedTextColor = colors.textPrime)
            )
        }

        Spacer(modifier = Modifier.height(8.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Box(modifier = Modifier.weight(1f)) {
                OutlinedTextField(
                    value = selectedModelId,
                    onValueChange = {},
                    readOnly = true,
                    placeholder = { Text("Model (Server default)", color = colors.textSec, fontSize = 12.sp) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(focusedTextColor = colors.textPrime, unfocusedTextColor = colors.textPrime)
                )
                if (loadedModels.isNotEmpty()) {
                    Box(modifier = Modifier.matchParentSize().clickable { expanded = true })
                }
            }
            Spacer(modifier = Modifier.width(8.dp))
            Button(
                onClick = {
                    scope.launch {
                        isLoading = true
                        errorMsg = null
                        try {
                            val res = viewModel.getModels(provider, apiKey.takeIf { it.isNotBlank() }, endpoint.takeIf { it.isNotBlank() })
                            loadedModels = res.models
                            errorMsg = res.warning
                        } catch(e: Exception) {
                            errorMsg = e.message
                        } finally { isLoading = false }
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = colors.scoreBg)
            ) {
                if (isLoading) CircularProgressIndicator(modifier = Modifier.size(16.dp), color = colors.primary)
                else Text("Get Models", fontSize = 12.sp, color = colors.primary)
            }
        }
        
        if (errorMsg != null) {
            Text(errorMsg!!, color = Color.Red, fontSize = 10.sp)
        }
        
        if (expanded) {
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }, modifier = Modifier.background(colors.container)) {
                loadedModels.forEach { model ->
                    DropdownMenuItem(text = { Text(model.name, color = colors.textPrime) }, onClick = {
                        selectedModelId = model.id
                        onUpdate("${provider}Model", model.id)
                        expanded = false
                    })
                }
            }
        }
    }
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

@Composable
fun PlaceCard(place: PlaceRecommendation, colors: ThemeColors, userLat: Double? = null, userLng: Double? = null) {
    var showDialog by remember { mutableStateOf(false) }
    val context = LocalContext.current

    val distance = remember(userLat, userLng, place.latitude, place.longitude) {
        if (userLat != null && userLng != null && place.latitude != null && place.longitude != null) {
            val results = FloatArray(1)
            android.location.Location.distanceBetween(userLat, userLng, place.latitude, place.longitude, results)
            val d = results[0]
            if (d < 1000) "${d.toInt()} m" else "${"%,.1f".format(d / 1000)} km"
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
                    Button(
                        onClick = {
                            showDialog = false
                            val lat = place.latitude ?: 0.0
                            val lng = place.longitude ?: 0.0
                            val navIntent = Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=$lat,$lng")).apply { setPackage("com.google.android.apps.maps") }
                            try { context.startActivity(navIntent) } catch (e: Exception) {
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("geo:$lat,$lng?q=$lat,$lng(${Uri.encode(place.name)})")))
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

    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = colors.container.copy(alpha = 0.8f))) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(place.name, style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold, color = colors.textPrime), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Box(modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(colors.scoreBg).padding(horizontal = 8.dp, vertical = 4.dp)) {
                    Text("Score: ${(place.confidenceScore * 100).toInt()}", color = colors.scoreText, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            place.rating?.let { rating ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("\u2605", color = Color(0xFFFBBF24), fontSize = 15.sp)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("${"%,.1f".format(rating)}", color = colors.textPrime, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    place.userRatingsTotal?.let { total ->
                        Text(" ($total reviews)", color = colors.textSec, fontSize = 12.sp)
                    }
                }
                Spacer(modifier = Modifier.height(6.dp))
            }
            if (place.address != null || distance != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { showDialog = true }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.LocationOn, contentDescription = "Location", tint = colors.primary, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(text = place.address ?: "", color = colors.textSec, fontSize = 14.sp, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    distance?.let {
                        Spacer(modifier = Modifier.width(8.dp))
                        Column(horizontalAlignment = Alignment.End) {
                            Text(it, color = colors.primary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                            Text("straight line", color = colors.textSec, fontSize = 9.sp)
                        }
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
            }
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
