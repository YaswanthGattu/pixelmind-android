package com.pixelmind

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.*
import androidx.compose.ui.text.style.*
import androidx.compose.ui.unit.*
import coil.compose.AsyncImage
import coil.request.ImageRequest
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

// ── Main Activity ─────────────────────────────────────────────────────────────

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            PixelMindTheme {
                PixelMindApp()
            }
        }
    }
}

// ── Theme ─────────────────────────────────────────────────────────────────────

@Composable
fun PixelMindTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Color(0xFF6C63FF),
            secondary = Color(0xFFFF6584),
            background = Color(0xFF080810),
            surface = Color(0xFF10101E),
            surfaceVariant = Color(0xFF181828),
            onBackground = Color(0xFFF0F0FF),
            onSurface = Color(0xFFE0E0F0),
            outline = Color(0xFF2A2A45)
        ),
        content = content
    )
}

// ── Data ──────────────────────────────────────────────────────────────────────

data class ImageItem(
    val uri: Uri,
    val contentUri: Uri,
    val name: String,
    val score: Float = 1f,
    val category: String = "Photos"
)

data class UiState(
    val query: String = "",
    val isSearching: Boolean = false,
    val hasPermission: Boolean = false,
    val screen: Screen = Screen.HOME,
    val results: List<ImageItem> = emptyList(),
    val allImages: List<ImageItem> = emptyList(),
    val totalCount: Int = 0,
    val filter: String = "All"
)

enum class Screen { HOME, RESULTS, LOADING, EMPTY, PERMISSION }

// ── Simple Vector DB (in-memory, backed by SharedPreferences) ─────────────────

object VectorDB {
    private val embeddings = mutableMapOf<String, FloatArray>()

    // Tiny deterministic "embedding" based on filename for demo
    // In full build: replace with ONNX Runtime CLIP inference
    fun embed(text: String): FloatArray {
        val concepts = mapOf(
            "train" to intArrayOf(0, 1), "rail" to intArrayOf(0, 1),
            "metro" to intArrayOf(0, 1), "subway" to intArrayOf(0, 1),
            "sunset" to intArrayOf(2, 3), "sunrise" to intArrayOf(2, 3),
            "sky" to intArrayOf(2, 3), "cloud" to intArrayOf(2, 3),
            "dog" to intArrayOf(4, 5), "cat" to intArrayOf(4, 5),
            "pet" to intArrayOf(4, 5), "animal" to intArrayOf(4, 5),
            "food" to intArrayOf(6, 7), "meal" to intArrayOf(6, 7),
            "mountain" to intArrayOf(8, 9), "beach" to intArrayOf(8, 9),
            "car" to intArrayOf(10, 11), "vehicle" to intArrayOf(10, 11),
            "person" to intArrayOf(12, 13), "selfie" to intArrayOf(12, 13),
            "receipt" to intArrayOf(14, 15), "document" to intArrayOf(14, 15),
            "screenshot" to intArrayOf(16, 17), "screen" to intArrayOf(16, 17),
            "flower" to intArrayOf(18, 19), "nature" to intArrayOf(18, 19)
        )
        val vec = FloatArray(64)
        val lower = text.lowercase()
        var found = false
        for ((kw, dims) in concepts) {
            if (lower.contains(kw)) {
                dims.forEach { vec[it] = 1f }
                found = true
            }
        }
        if (!found) {
            // hash-based fallback
            val hash = lower.fold(0) { h, c -> h * 31 + c.code }
            for (i in vec.indices) vec[i] = kotlin.math.sin(hash * (i + 1) * 0.05f).toFloat()
        }
        // normalize
        val norm = sqrt(vec.fold(0f) { s, v -> s + v * v })
        return if (norm > 0) vec.map { it / norm }.toFloatArray() else vec
    }

    fun store(key: String, emb: FloatArray) { embeddings[key] = emb }
    fun get(key: String) = embeddings[key]

    fun search(queryEmb: FloatArray, candidates: List<ImageItem>, topK: Int): List<ImageItem> {
        return candidates.map { img ->
            val storedEmb = embeddings[img.uri.toString()] ?: embed(img.name)
            val score = dotProduct(queryEmb, storedEmb)
            img.copy(score = score)
        }
            .filter { it.score > 0.1f }
            .sortedByDescending { it.score }
            .take(topK)
    }

    private fun dotProduct(a: FloatArray, b: FloatArray): Float {
        var s = 0f
        val len = minOf(a.size, b.size)
        for (i in 0 until len) s += a[i] * b[i]
        return s
    }
}

// ── Main App ──────────────────────────────────────────────────────────────────

@Composable
fun PixelMindApp() {
    val context = LocalContext.current
    var uiState by remember { mutableStateOf(UiState()) }
    val scope = rememberCoroutineScope()

    // Permission launcher
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { perms ->
        val granted = perms.values.any { it }
        if (granted) {
            uiState = uiState.copy(hasPermission = true, screen = Screen.HOME)
            scope.launch { loadImages(context) { imgs ->
                uiState = uiState.copy(allImages = imgs, totalCount = imgs.size)
            }}
        } else {
            uiState = uiState.copy(screen = Screen.PERMISSION)
        }
    }

    // Auto-request on launch
    LaunchedEffect(Unit) {
        permissionLauncher.launch(
            if (Build.VERSION.SDK_INT >= 33)
                arrayOf(Manifest.permission.READ_MEDIA_IMAGES)
            else
                arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        )
    }

    fun doSearch(query: String) {
        if (query.isBlank()) { uiState = uiState.copy(screen = Screen.HOME, results = emptyList()); return }
        scope.launch {
            uiState = uiState.copy(isSearching = true, screen = Screen.LOADING)
            delay(300)
            val qEmb = VectorDB.embed(query)
            val filtered = if (uiState.filter == "All") uiState.allImages
            else uiState.allImages.filter { it.category == uiState.filter }
            val results = VectorDB.search(qEmb, filtered, 80)
            uiState = uiState.copy(
                isSearching = false,
                results = results,
                screen = if (results.isEmpty()) Screen.EMPTY else Screen.RESULTS
            )
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.systemBars)
    ) {
        Column(Modifier.fillMaxSize()) {

            // Header
            AppHeader(uiState.totalCount)

            // Search bar
            SearchBar(
                query = uiState.query,
                isSearching = uiState.isSearching,
                onQueryChange = { q ->
                    uiState = uiState.copy(query = q)
                    if (q.length >= 2) scope.launch { delay(400); if (uiState.query == q) doSearch(q) }
                    else if (q.isEmpty()) uiState = uiState.copy(screen = Screen.HOME, results = emptyList())
                },
                onSearch = { doSearch(uiState.query) },
                onClear = { uiState = uiState.copy(query = "", screen = Screen.HOME, results = emptyList()) }
            )

            // Quick search pills
            QuickPills { pill ->
                uiState = uiState.copy(query = pill)
                doSearch(pill)
            }

            // Filter row
            FilterRow(uiState.filter) { f ->
                uiState = uiState.copy(filter = f)
                if (uiState.query.isNotEmpty()) doSearch(uiState.query)
            }

            // Content area
            Box(Modifier.weight(1f)) {
                when (uiState.screen) {
                    Screen.HOME -> HomeScreen(uiState) { q -> uiState = uiState.copy(query = q); doSearch(q) }
                    Screen.LOADING -> LoadingView()
                    Screen.RESULTS -> ResultsGrid(uiState.results)
                    Screen.EMPTY -> EmptyView(uiState.query)
                    Screen.PERMISSION -> PermissionView {
                        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.fromParts("package", context.packageName, null))
                        context.startActivity(intent)
                    }
                }
            }
        }

        // Bottom nav
        BottomNav(Modifier.align(Alignment.BottomCenter))
    }
}

// ── Header ────────────────────────────────────────────────────────────────────

@Composable
fun AppHeader(totalCount: Int) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Row(verticalAlignment = Alignment.Bottom) {
                Text("Pixel", fontSize = 26.sp, fontWeight = FontWeight.Black, color = Color.White)
                Text("Mind", fontSize = 26.sp, fontWeight = FontWeight.Black,
                    color = Color(0xFF6C63FF))
            }
            Text(
                if (totalCount > 0) "$totalCount images indexed" else "AI image search",
                fontSize = 12.sp, color = Color(0xFF7777AA)
            )
        }
        Box(
            Modifier
                .size(44.dp)
                .background(Color(0xFF181828), CircleShape)
                .border(1.5.dp, Color(0xFF6C63FF).copy(0.5f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Text("✦", fontSize = 18.sp, color = Color(0xFF6C63FF))
        }
    }
}

// ── Search Bar ────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchBar(
    query: String,
    isSearching: Boolean,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    onClear: () -> Unit
) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        placeholder = { Text("Search by meaning… try \"train\"", color = Color(0xFF444466), fontSize = 14.sp) },
        leadingIcon = {
            if (isSearching)
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = Color(0xFF6C63FF))
            else
                Icon(Icons.Default.Search, null, tint = Color(0xFF6C63FF))
        },
        trailingIcon = {
            if (query.isNotEmpty())
                IconButton(onClick = onClear) { Icon(Icons.Default.Clear, null, tint = Color(0xFF7777AA)) }
        },
        singleLine = true,
        shape = RoundedCornerShape(14.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = Color(0xFF10101E),
            unfocusedContainerColor = Color(0xFF0C0C18),
            focusedBorderColor = Color(0xFF6C63FF),
            unfocusedBorderColor = Color(0xFF2A2A45),
            cursorColor = Color(0xFF6C63FF),
            focusedTextColor = Color.White,
            unfocusedTextColor = Color.White
        ),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
            imeAction = androidx.compose.ui.text.input.ImeAction.Search
        ),
        keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = { onSearch() })
    )
}

// ── Quick Pills ───────────────────────────────────────────────────────────────

@Composable
fun QuickPills(onPillClick: (String) -> Unit) {
    val pills = listOf("🚂 train", "🌅 sunset", "🐶 dog", "🍜 food", "⛰️ mountain",
        "🚗 car", "😊 selfie", "🏖️ beach", "🌸 flower", "📄 receipt")
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(pills) { pill ->
            AssistChip(
                onClick = { onPillClick(pill.split(" ").last()) },
                label = { Text(pill, fontSize = 13.sp) },
                colors = AssistChipDefaults.assistChipColors(
                    containerColor = Color(0xFF181828),
                    labelColor = Color(0xFFAAAAAA)
                ),
                border = AssistChipDefaults.assistChipBorder(enabled = true, borderColor = Color(0xFF2A2A45))
            )
        }
    }
}

// ── Filter Row ────────────────────────────────────────────────────────────────

@Composable
fun FilterRow(selected: String, onSelect: (String) -> Unit) {
    val filters = listOf("All", "Photos", "Documents", "Screenshots", "People", "Places")
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        items(filters) { f ->
            FilterChip(
                selected = f == selected,
                onClick = { onSelect(f) },
                label = { Text(f, fontSize = 12.sp) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = Color(0xFF6C63FF),
                    selectedLabelColor = Color.White,
                    containerColor = Color(0xFF181828),
                    labelColor = Color(0xFFAAAAAA)
                ),
                border = FilterChipDefaults.filterChipBorder(
                    enabled = true, selected = f == selected,
                    borderColor = Color(0xFF2A2A45),
                    selectedBorderColor = Color.Transparent
                )
            )
        }
    }
}

// ── Home Screen ───────────────────────────────────────────────────────────────

@Composable
fun HomeScreen(uiState: UiState, onSearch: (String) -> Unit) {
    val categories = listOf(
        Triple("🚂", "Vehicles", Color(0xFF6C63FF)),
        Triple("🌿", "Nature", Color(0xFF4CAF50)),
        Triple("📄", "Documents", Color(0xFFFF9800)),
        Triple("😊", "People", Color(0xFFE91E63)),
        Triple("🏙️", "Places", Color(0xFF00BCD4)),
        Triple("🐾", "Animals", Color(0xFFFF5722)),
        Triple("🍜", "Food", Color(0xFFFFC107)),
        Triple("📸", "Screenshots", Color(0xFF9C27B0))
    )

    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        // Stats card
        item(span = { GridItemSpan(2) }) {
            Card(
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = Color.Transparent),
                modifier = Modifier.fillMaxWidth()
            ) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .background(
                            Brush.linearGradient(listOf(Color(0xFF6C63FF).copy(0.25f), Color(0xFF0A0A20)))
                        )
                        .border(1.dp, Color(0xFF6C63FF).copy(0.2f), RoundedCornerShape(18.dp))
                        .padding(20.dp)
                ) {
                    Column {
                        Text("Your Library", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                        Spacer(Modifier.height(14.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                            Column { Text("${uiState.totalCount}", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Black); Text("Total", color = Color(0xFF8888AA), fontSize = 11.sp) }
                            Column { Text("${uiState.totalCount}", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Black); Text("Indexed", color = Color(0xFF8888AA), fontSize = 11.sp) }
                            Column { Text("8", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Black); Text("Collections", color = Color(0xFF8888AA), fontSize = 11.sp) }
                        }
                    }
                }
            }
        }

        item(span = { GridItemSpan(2) }) {
            Text("Smart Collections", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.White,
                modifier = Modifier.padding(top = 8.dp, bottom = 2.dp))
        }

        items(categories) { (emoji, name, color) ->
            Card(
                onClick = { onSearch(name.lowercase()) },
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = color.copy(0.1f)),
                border = BorderStroke(1.dp, color.copy(0.2f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(emoji, fontSize = 22.sp)
                    Text(name, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                }
            }
        }

        item(span = { GridItemSpan(2) }) { Spacer(Modifier.height(80.dp)) }
    }
}

// ── Results Grid ──────────────────────────────────────────────────────────────

@Composable
fun ResultsGrid(results: List<ImageItem>) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        contentPadding = PaddingValues(bottom = 80.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        item(span = { GridItemSpan(3) }) {
            Text("${results.size} results", color = Color(0xFF7777AA), fontSize = 13.sp,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp))
        }
        items(results, key = { it.uri.toString() }) { img ->
            var visible by remember { mutableStateOf(false) }
            LaunchedEffect(Unit) { delay(30); visible = true }
            AnimatedVisibility(visible, enter = fadeIn() + scaleIn(initialScale = 0.9f)) {
                Box(
                    Modifier
                        .aspectRatio(1f)
                        .clip(RoundedCornerShape(2.dp))
                ) {
                    AsyncImage(
                        model = ImageRequest.Builder(LocalContext.current)
                            .data(img.contentUri)
                            .crossfade(true)
                            .size(300)
                            .build(),
                        contentDescription = img.name,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                    Box(
                        Modifier
                            .align(Alignment.TopEnd)
                            .padding(3.dp)
                            .background(Color(0xFF6C63FF).copy(0.9f), RoundedCornerShape(4.dp))
                            .padding(horizontal = 4.dp, vertical = 2.dp)
                    ) {
                        Text("${(img.score * 100).toInt()}%", color = Color.White,
                            fontSize = 9.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

// ── Supporting Screens ────────────────────────────────────────────────────────

@Composable
fun LoadingView() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
            CircularProgressIndicator(color = Color(0xFF6C63FF), strokeWidth = 3.dp)
            Text("Searching with AI…", color = Color(0xFF7777AA), fontSize = 14.sp)
        }
    }
}

@Composable
fun EmptyView(query: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(40.dp)) {
            Text("🔍", fontSize = 52.sp)
            Text("No results for \"$query\"", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 17.sp, textAlign = TextAlign.Center)
            Text("Try different keywords or index more images", color = Color(0xFF7777AA), fontSize = 13.sp, textAlign = TextAlign.Center)
        }
    }
}

@Composable
fun PermissionView(onOpenSettings: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.padding(40.dp)) {
            Text("🖼️", fontSize = 52.sp)
            Text("Photo Access Needed", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Text("PixelMind needs access to your photos to index and search them. All processing is done on-device.",
                color = Color(0xFF7777AA), fontSize = 13.sp, textAlign = TextAlign.Center)
            Button(onClick = onOpenSettings,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6C63FF)),
                shape = RoundedCornerShape(12.dp)) {
                Text("Open Settings", fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
fun BottomNav(modifier: Modifier = Modifier) {
    var selected by remember { mutableIntStateOf(0) }
    val items = listOf("🔍" to "Search", "🖼️" to "Library", "✨" to "Smart", "⚙️" to "Settings")
    NavigationBar(
        modifier = modifier,
        containerColor = Color(0xFF08080F).copy(0.95f),
        tonalElevation = 0.dp
    ) {
        items.forEachIndexed { i, (icon, label) ->
            NavigationBarItem(
                selected = i == selected,
                onClick = { selected = i },
                icon = { Text(icon, fontSize = 22.sp) },
                label = { Text(label, fontSize = 10.sp) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = Color(0xFF6C63FF),
                    selectedTextColor = Color(0xFF6C63FF),
                    unselectedIconColor = Color(0xFF7777AA),
                    unselectedTextColor = Color(0xFF7777AA),
                    indicatorColor = Color(0xFF6C63FF).copy(0.1f)
                )
            )
        }
    }
}

// ── Image Loading ─────────────────────────────────────────────────────────────

suspend fun loadImages(context: Context, onLoaded: (List<ImageItem>) -> Unit) {
    withContext(Dispatchers.IO) {
        val images = mutableListOf<ImageItem>()
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.RELATIVE_PATH
        )
        context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection, null, null,
            "${MediaStore.Images.Media.DATE_MODIFIED} DESC"
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
            val pathCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.RELATIVE_PATH)
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                val name = cursor.getString(nameCol) ?: continue
                val path = cursor.getString(pathCol) ?: ""
                val contentUri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
                val category = classifyFromPath(path + name)
                val emb = VectorDB.embed(name + " " + path)
                VectorDB.store(contentUri.toString(), emb)
                images.add(ImageItem(contentUri, contentUri, name, 1f, category))
            }
        }
        withContext(Dispatchers.Main) { onLoaded(images) }
    }
}

fun classifyFromPath(text: String): String {
    val t = text.lowercase()
    return when {
        t.contains("screenshot") || t.contains("screen") -> "Screenshots"
        t.contains("document") || t.contains("receipt") || t.contains("scan") -> "Documents"
        t.contains("whatsapp") || t.contains("telegram") || t.contains("signal") -> "People"
        t.contains("dcim") || t.contains("camera") -> "Photos"
        else -> "Photos"
    }
}
