package com.guberdev.places.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
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
    
    var userApiKeys by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var showSettings by remember { mutableStateOf(false) }

    val context = LocalContext.current
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

        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
                is PlacesUiState.Loading -> Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator(color = colors.primary) }
                is PlacesUiState.Success -> {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
                        items(state.response.recommendations) { place -> PlaceCard(place, colors) }
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
    var localKeys by remember { mutableStateOf(initialKeys.toMutableMap()) }
    val providers = listOf("OpenRouter", "OpenAI", "Anthropic", "Gemini", "AzureOpenAI")

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
                        localKeys[k] = v
                    }
                }
                item {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Text("Google Places", color = colors.textPrime, fontWeight = FontWeight.SemiBold)
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(
                            value = localKeys["GooglePlaces"] ?: "",
                            onValueChange = { localKeys["GooglePlaces"] = it },
                            placeholder = { Text("API Key", color = colors.textSec) },
                            modifier = Modifier.fillMaxWidth(),
                            colors = OutlinedTextFieldDefaults.colors(focusedTextColor = colors.textPrime, unfocusedTextColor = colors.textPrime)
                        )
                    }
                }
            }
        },
        confirmButton = { Button(colors = ButtonDefaults.buttonColors(containerColor = colors.primary), onClick = { onSave(localKeys) }) { Text("Save", color = Color.White) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = colors.textSec) } }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProviderSettingsItem(colors: ThemeColors, viewModel: PlacesViewModel, provider: String, keys: MutableMap<String, String>, onUpdate: (String, String) -> Unit) {
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
    Box(modifier = Modifier.clip(RoundedCornerShape(20.dp)).background(bgColor).clickable { onClick() }.padding(horizontal = 16.dp, vertical = 8.dp), contentAlignment = Alignment.Center) {
        Text(text = text, color = textColor, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun PlaceCard(place: PlaceRecommendation, colors: ThemeColors) {
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = colors.container.copy(alpha = 0.8f))) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(place.name, style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold, color = colors.textPrime), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Box(modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(colors.scoreBg).padding(horizontal = 8.dp, vertical = 4.dp)) {
                    Text("Score: ${(place.confidenceScore * 100).toInt()}", color = colors.scoreText, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            place.address?.let {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.LocationOn, contentDescription = "Location", tint = colors.textSec, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(text = it, color = colors.textSec, fontSize = 14.sp)
                }
                Spacer(modifier = Modifier.height(8.dp))
            }
            Text(place.description, color = colors.textPrime.copy(alpha = 0.8f), fontSize = 15.sp, lineHeight = 22.sp)
            if (place.highlights.isNotEmpty()) {
                Spacer(modifier = Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    place.highlights.take(3).forEach { highlight ->
                        Box(modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(colors.pillBg).padding(horizontal = 8.dp, vertical = 4.dp)) {
                            Text(highlight, color = colors.textSec, fontSize = 12.sp)
                        }
                    }
                }
            }
        }
    }
}
