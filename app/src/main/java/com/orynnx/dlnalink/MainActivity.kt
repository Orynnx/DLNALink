package com.orynnx.dlnalink

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.orynnx.dlnalink.ui.theme.DLNALinkTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

import android.content.Intent
import android.text.format.Formatter
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()
    private var multicastLock: WifiManager.MulticastLock? = null
    private var ssdpDiscovery: SsdpDiscovery? = null
    private var pendingQueueId by mutableStateOf<String?>(null)
    private var selectedQueueId by mutableStateOf<String?>(null)
    private val bridgeClient = BridgeClient()
    private var incomingMime: String? = null
    private var pairingRevision by mutableIntStateOf(0)
    private val pairingScanner = registerForActivityResult(ScanContract()) { result ->
        result.contents?.let { scanned ->
            val pairing = parseBridgePairing(scanned)
            if (pairing == null) Toast.makeText(this, "Not a valid DLNALink TV pairing code", Toast.LENGTH_LONG).show()
            else lifecycleScope.launch {
                if (bridgeClient.test(pairing.address, pairing.token)) {
                    getPreferences(MODE_PRIVATE).edit().putString("bridgeAddress", pairing.address)
                        .putString("bridgeToken", pairing.token).putBoolean("bridgeEnabled", true).apply()
                    pairingRevision++
                    Toast.makeText(this@MainActivity, "TV paired. Shared videos will use VLC.", Toast.LENGTH_LONG).show()
                } else Toast.makeText(this@MainActivity, "Pairing failed. Enable the TV bridge and use the same LAN.", Toast.LENGTH_LONG).show()
            }
        }
    }

    private suspend fun castViaBridge(url: String) {
        try {
            val queued = CastSession.state.value.items.firstOrNull { it.id == selectedQueueId }
                ?: CastSession.state.value.items.firstOrNull { it.playbackUri.isNotBlank() && it.playbackUri == url }
                ?: CastSession.enqueue(this, android.net.Uri.parse(url), incomingMime)
            selectedQueueId = queued.id
            val success = CastSession.play(this, queued.id)
            Toast.makeText(this, if (success) "TV bridge accepted playback request" else
                "TV bridge failed. Check address, token and TV receiver. No DLNA fallback was attempted.", Toast.LENGTH_LONG).show()
        } catch (_: Exception) {
            Toast.makeText(this, "Could not queue or send this media", Toast.LENGTH_LONG).show()
        }
    }

    private fun enqueueMedia(uri: android.net.Uri, mime: String?) {
        val requestVersion = CastSession.sessionVersion
        lifecycleScope.launch {
            try {
                val item = CastSession.enqueue(this@MainActivity, uri, mime)
                if (requestVersion != CastSession.sessionVersion) return@launch
                selectedQueueId = item.id
                viewModel.updateMediaUrl(item.playbackUri.ifBlank { uri.toString() })
                if (!getPreferences(MODE_PRIVATE).getBoolean("bridgeEnabled", false) && CastSession.state.value.items.firstOrNull()?.id == item.id && CastSession.state.value.currentId == null) {
                    pendingQueueId = item.id
                    if (!getPreferences(MODE_PRIVATE).getBoolean("bridgeEnabled", false)) ssdpDiscovery?.discover()
                }
                Toast.makeText(this@MainActivity, "Added to session queue", Toast.LENGTH_SHORT).show()
            } catch (_: Exception) {
                Toast.makeText(this@MainActivity, "Cannot queue media. Check file access and available storage.", Toast.LENGTH_LONG).show()
            }
        }
    }

    // Debug info states
    private val _debugLogs = MutableStateFlow<List<String>>(emptyList())
    val debugLogs: StateFlow<List<String>> = _debugLogs.asStateFlow()

    private val _multicastLockAcquired = MutableStateFlow(false)
    val multicastLockAcquired: StateFlow<Boolean> = _multicastLockAcquired.asStateFlow()

    private fun addDebugLog(message: String) {
        val timestamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
        _debugLogs.update { logs ->
            (logs + "[$timestamp] $message").takeLast(30)
        }
        android.util.Log.d("DLNALink", message)
    }

    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        addDebugLog("Location permission ${if (isGranted) "granted" else "denied"}")
        if (!isGranted) {
            Toast.makeText(this, getString(R.string.location_required), Toast.LENGTH_LONG).show()
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) Toast.makeText(this, "Allow notifications to show Play / Next / Stop controls outside the app.", Toast.LENGTH_LONG).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        addDebugLog("Started native SSDP discovery")

        // 处理分享的链接
        lifecycleScope.launch { CastSession.restore(this@MainActivity) }
        if (savedInstanceState == null) handleIntent(intent)

        // Request location permission
        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            addDebugLog("Requesting location permission")
            locationPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        } else {
            addDebugLog("Location permission granted")
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        // Acquire multicast lock
        acquireMulticastLock()

        // Initialize SSDP discovery
        ssdpDiscovery = SsdpDiscovery { device ->
            addDebugLog("Device found: ${device.friendlyName}")
            viewModel.addDevice(device)
        }

        setContent {
            DLNALinkTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    val devices by viewModel.devices.collectAsState()
                    val mediaUrl by viewModel.mediaUrl.collectAsState()
                    val debugLogs by debugLogs.collectAsState()
                    val multicastLockAcquired by multicastLockAcquired.collectAsState()
                    val scope = rememberCoroutineScope()
                    val session by CastSession.state.collectAsState()
                    val sharedQueue by SharedTvQueue.state.collectAsState()
                    var sharedBusy by remember { mutableStateOf(false) }
                    val prefs = remember { getPreferences(MODE_PRIVATE) }
                    var bridgeEnabled by remember(pairingRevision) { mutableStateOf(prefs.getBoolean("bridgeEnabled", false)) }
                    var bridgeAddress by remember(pairingRevision) { mutableStateOf(prefs.getString("bridgeAddress", "").orEmpty()) }
                    var bridgeToken by remember(pairingRevision) { mutableStateOf(prefs.getString("bridgeToken", "").orEmpty()) }
                    var bridgeTesting by remember { mutableStateOf(false) }

                    LaunchedEffect(bridgeEnabled, pairingRevision) {
                        if (bridgeEnabled) lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                            while (true) {
                                SharedTvQueue.refresh(this@MainActivity)
                                kotlinx.coroutines.delay(2000)
                            }
                        }
                    }

                    LaunchedEffect(devices, pendingQueueId, bridgeEnabled) {
                        val queuedId = pendingQueueId
                        if (queuedId != null) {
                            if (bridgeEnabled) {
                                pendingQueueId = null
                                CastSession.play(this@MainActivity, queuedId)
                                return@LaunchedEffect
                            }
                            val saved = getPreferences(MODE_PRIVATE).getString("lastDevice", null)
                            val target = devices.firstOrNull { it.uid == saved && it.controlUrl != null }
                            if (target != null) {
                                pendingQueueId = null
                                CastSession.rememberDlna(this@MainActivity, target)
                                val success = CastSession.play(this@MainActivity, queuedId)
                                Toast.makeText(this@MainActivity, if (success) "Playing on ${target.friendlyName}" else "Cast failed. Pick a device to retry.", Toast.LENGTH_LONG).show()
                            }
                        }
                    }

                    MainScreen(
                        devices = devices,
                        currentMediaUrl = mediaUrl,
                        onMediaUrlChange = { selectedQueueId = null; incomingMime = null; viewModel.updateMediaUrl(it) },
                        debugLogs = debugLogs,
                        multicastLockAcquired = multicastLockAcquired,
                        onScanClick = {
                            addDebugLog("Starting SSDP scan")
                            viewModel.clearDevices()
                            scope.launch {
                                try {
                                    ssdpDiscovery?.discover()
                                } catch (e: Exception) {
                                    addDebugLog("Scan failed: ${e.message}")
                                }
                            }
                        },
                        onCastClick = { device, url ->
                            pendingQueueId = null
                            if (bridgeEnabled) {
                                scope.launch { castViaBridge(url) }
                            } else {
                                getPreferences(MODE_PRIVATE).edit().putString("lastDevice", device.uid).apply()
                                addDebugLog("Requesting playback on ${device.friendlyName}")
                                scope.launch {
                                    try {
                                    CastSession.rememberDlna(this@MainActivity, device)
                                    val queued = CastSession.state.value.items.firstOrNull { it.id == selectedQueueId }
                                        ?: CastSession.state.value.items.firstOrNull { it.playbackUri.isNotBlank() && it.playbackUri == url }
                                        ?: CastSession.enqueue(this@MainActivity, android.net.Uri.parse(url), incomingMime)
                                    selectedQueueId = queued.id
                                    val success = CastSession.play(this@MainActivity, queued.id)
                                    if (success) {
                                        Toast.makeText(this@MainActivity, getString(R.string.cast_success), Toast.LENGTH_SHORT).show()
                                    } else {
                                        Toast.makeText(this@MainActivity, getString(R.string.cast_failed), Toast.LENGTH_SHORT).show()
                                    }
                                    } catch (_: Exception) {
                                        Toast.makeText(this@MainActivity, "Could not queue or send this media", Toast.LENGTH_LONG).show()
                                    }
                                }
                            }
                        },
                        bridgeEnabled = bridgeEnabled,
                        onBridgeScan = { pairingScanner.launch(ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                            .setPrompt("Scan the QR code on DLNALink TV Bridge").setBeepEnabled(false).setOrientationLocked(false)) },
                        bridgeAddress = bridgeAddress,
                        bridgeToken = bridgeToken,
                        bridgeTesting = bridgeTesting,
                        onBridgeEnabledChange = {
                            bridgeEnabled = it
                            prefs.edit().putBoolean("bridgeEnabled", it).apply()
                        },
                        onBridgeAddressChange = { bridgeAddress = it },
                        onBridgeTokenChange = { bridgeToken = it },
                        onBridgeSaveTest = {
                            prefs.edit().putString("bridgeAddress", bridgeAddress.trim())
                                .putString("bridgeToken", bridgeToken.trim()).apply()
                            bridgeTesting = true
                            scope.launch {
                                try {
                                    val success = bridgeClient.test(bridgeAddress.trim(), bridgeToken.trim())
                                    Toast.makeText(this@MainActivity, if (success) "TV bridge reachable; pairing saved" else
                                        "Saved, but TV bridge test failed. Check address and token.", Toast.LENGTH_LONG).show()
                                } finally { bridgeTesting = false }
                            }
                        },
                        onBridgePlay = { scope.launch { castViaBridge(mediaUrl) } },
                        session = session,
                        sharedQueue = sharedQueue,
                        sharedBusy = sharedBusy,
                        onSharedAction = { action, id, index, revision ->
                            sharedBusy = true
                            scope.launch {
                                try { SharedTvQueue.command(this@MainActivity, action, id, index, revision) }
                                finally { sharedBusy = false }
                            }
                        },
                        onSharedRefresh = { scope.launch { SharedTvQueue.refresh(this@MainActivity) } },
                        onQueueUrl = { enqueueMedia(android.net.Uri.parse(mediaUrl), incomingMime) },
                        onQueuePlay = { id -> selectedQueueId = id; scope.launch { CastSession.play(this@MainActivity, id) } },
                        onQueueRemove = { id -> scope.launch {
                            try { CastSession.remove(this@MainActivity, id) } catch (_: Exception) {
                                Toast.makeText(this@MainActivity, "Could not remove queue item", Toast.LENGTH_SHORT).show()
                            }
                            if (selectedQueueId == id) { selectedQueueId = null; viewModel.updateMediaUrl("") }
                        } },
                        onQueueNext = { scope.launch { CastSession.next(this@MainActivity) } },
                        onSessionStop = { pendingQueueId = null; selectedQueueId = null; viewModel.updateMediaUrl(""); CastSession.stop(this@MainActivity) },
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        incomingMime = intent?.type
        if (intent?.action == Intent.ACTION_SEND_MULTIPLE) {
            val uris = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, android.net.Uri::class.java).orEmpty()
            val batchVersion = CastSession.sessionVersion
            lifecycleScope.launch {
                for (uri in uris) {
                    if (batchVersion != CastSession.sessionVersion) break
                    try {
                        val item = CastSession.enqueue(this@MainActivity, uri, intent?.type)
                        if (batchVersion != CastSession.sessionVersion) break
                        selectedQueueId = item.id
                        viewModel.updateMediaUrl(item.playbackUri.ifBlank { uri.toString() })
                    } catch (_: Exception) {
                        if (batchVersion != CastSession.sessionVersion) break
                        Toast.makeText(this@MainActivity, "An item could not be queued", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            return
        }
        val localUri = if (intent?.action == Intent.ACTION_VIEW) intent.data else if (intent?.action == Intent.ACTION_SEND) intent.getParcelableExtra(Intent.EXTRA_STREAM, android.net.Uri::class.java) else null
        if (localUri?.scheme in listOf("content", "file")) {
            enqueueMedia(localUri!!, intent?.type)
            return
        }
        if (intent?.action == Intent.ACTION_VIEW) {
            val uri = intent.data
            if (uri != null && uri.scheme in listOf("http", "https")) {
                if (extractSharedMediaLinks(uri.toString(), intent.type).isNotEmpty()) {
                    enqueueMedia(android.net.Uri.parse(processLocalhostUrl(uri.toString())), intent.type)
                } else Toast.makeText(this, "Not a recognized video or stream URL", Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(this, "Only TV-reachable HTTP(S) streams are supported.", Toast.LENGTH_LONG).show()
            }
            return
        }
        if (intent?.action == Intent.ACTION_SEND) {
            intent.getStringExtra(Intent.EXTRA_TEXT)?.let { sharedText ->
                val links = extractSharedMediaLinks(sharedText, intent.type)
                if (links.isEmpty()) Toast.makeText(this, "No direct video, audio or stream link found", Toast.LENGTH_LONG).show()
                else {
                    val version = CastSession.sessionVersion
                    lifecycleScope.launch {
                        var appended = 0
                        for (link in links) {
                            if (version != CastSession.sessionVersion) break
                            try {
                                val item = CastSession.enqueue(this@MainActivity, android.net.Uri.parse(link), intent.type)
                                if (version != CastSession.sessionVersion) break
                                selectedQueueId = item.id
                                appended++
                                viewModel.updateMediaUrl(item.playbackUri.ifBlank { link })
                            } catch (_: Exception) {
                                if (version != CastSession.sessionVersion) break
                                Toast.makeText(this@MainActivity, "Could not append media link", Toast.LENGTH_SHORT).show()
                            }
                        }
                        if (appended > 0 && version == CastSession.sessionVersion) {
                            Toast.makeText(this@MainActivity, "$appended media link(s) queued; check TV queue status", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        }
    }

    private fun extractUrl(text: String): String {
        val match = Regex("(https?://\\S+)").find(text)
        return match?.value ?: text
    }

    private fun processLocalhostUrl(url: String): String {
        if (url.contains("127.0.0.1") || url.contains("localhost")) {
            val ip = getLocalIpAddress()
            if (ip != null) {
                addDebugLog("Replaced localhost with LAN address")
                return url.replace("127.0.0.1", ip).replace("localhost", ip)
            } else {
                addDebugLog("LAN address unavailable; URI unchanged")
            }
        }
        return url
    }

    private fun getLocalIpAddress(): String? {
        try {
            val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val ipAddress = wifiManager.connectionInfo.ipAddress
            // 格式化 IP 地址 (int -> string)
            return Formatter.formatIpAddress(ipAddress)
        } catch (e: Exception) {
            addDebugLog("Address lookup failed: ${e.message}")
        }
        return null
    }

    override fun onDestroy() {
        super.onDestroy()
        releaseMulticastLock()
    }

    private fun acquireMulticastLock() {
        try {
            val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            multicastLock = wifiManager.createMulticastLock("DLNALink_MulticastLock").apply {
                setReferenceCounted(true)
                acquire()
            }
            _multicastLockAcquired.value = true
            addDebugLog("Multicast lock acquired")
        } catch (e: Exception) {
            addDebugLog("Multicast lock failed: ${e.message}")
        }
    }

    private fun releaseMulticastLock() {
        try {
            multicastLock?.let {
                if (it.isHeld) {
                    it.release()
                    _multicastLockAcquired.value = false
                    addDebugLog("Multicast lock released")
                }
            }
            multicastLock = null
        } catch (e: Exception) {
            addDebugLog("Multicast unlock failed: ${e.message}")
        }
    }
}

// Helper function to get WiFi SSID
private fun getWifiSsid(context: Context): String {
    return try {
        if (ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return context.getString(R.string.location_required)
        }

        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

        // 首先检查 WiFi 是否启用
        if (!wifiManager.isWifiEnabled) {
            return "Wi-Fi disabled"
        }

        val ssid = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Android 12+ (API 31+)
            val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val network = connectivityManager.activeNetwork

            if (network == null) {
                // 如果 activeNetwork 为 null，尝试使用传统方法
                android.util.Log.d("WiFiSSID", "activeNetwork is null, trying legacy method")
                @Suppress("DEPRECATION")
                wifiManager.connectionInfo.ssid
            } else {
                val capabilities = connectivityManager.getNetworkCapabilities(network)

                if (capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) {
                    val wifiInfo = capabilities.transportInfo as? WifiInfo
                    val wifiSsid = wifiInfo?.ssid

                    android.util.Log.d("WiFiSSID", "Android 12+ WifiInfo SSID: $wifiSsid")

                    // 如果通过新 API 获取失败，尝试传统方法
                    if (wifiSsid.isNullOrEmpty() || wifiSsid == "<unknown ssid>") {
                        @Suppress("DEPRECATION")
                        val legacySsid = wifiManager.connectionInfo.ssid
                        android.util.Log.d("WiFiSSID", "Fallback to legacy SSID: $legacySsid")
                        legacySsid
                    } else {
                        wifiSsid
                    }
                } else {
                    // 不是 WiFi 连接
                    android.util.Log.d("WiFiSSID", "Not connected via WiFi")
                    @Suppress("DEPRECATION")
                    wifiManager.connectionInfo.ssid
                }
            }
        } else {
            // Android 11 及以下
            @Suppress("DEPRECATION")
            wifiManager.connectionInfo.ssid
        }

        android.util.Log.d("WiFiSSID", "Final SSID value: $ssid")

        // 检查是否为无效 SSID
        if (ssid == "<unknown ssid>" || ssid == "0x" || ssid.isNullOrEmpty()) {
            return "Wi-Fi disconnected"
        }

        // 移除引号
        val cleanSsid = if (ssid.startsWith("\"") && ssid.endsWith("\"")) {
            ssid.substring(1, ssid.length - 1)
        } else {
            ssid
        }

        cleanSsid
    } catch (e: Exception) {
        android.util.Log.e("WiFiSSID", "Error getting WiFi SSID", e)
        "Error: ${e.message}"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    devices: List<DlnaDevice>,
    currentMediaUrl: String,
    onMediaUrlChange: (String) -> Unit,
    debugLogs: List<String>,
    multicastLockAcquired: Boolean,
    onScanClick: () -> Unit,
    onCastClick: (DlnaDevice, String) -> Unit,
    bridgeEnabled: Boolean = false,
    bridgeAddress: String = "",
    bridgeToken: String = "",
    bridgeTesting: Boolean = false,
    onBridgeEnabledChange: (Boolean) -> Unit = {},
    onBridgeAddressChange: (String) -> Unit = {},
    onBridgeTokenChange: (String) -> Unit = {},
    onBridgeSaveTest: () -> Unit = {},
    onBridgePlay: () -> Unit = {},
    onBridgeScan: () -> Unit = {},
    session: SessionState = SessionState(),
    onQueueUrl: () -> Unit = {},
    onQueuePlay: (String) -> Unit = {},
    onQueueRemove: (String) -> Unit = {},
    onQueueNext: () -> Unit = {},
    onSessionStop: () -> Unit = {},
    sharedQueue: SharedQueueState = SharedQueueState(),
    sharedBusy: Boolean = false,
    onSharedAction: (String, String?, Int?, Long?) -> Unit = { _, _, _, _ -> },
    onSharedRefresh: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    var showBottomSheet by remember { mutableStateOf(false) }
    var isScanning by remember { mutableStateOf(false) }
    var debugExpanded by remember { mutableStateOf(false) }
    var confirmClearShared by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState()
    val context = LocalContext.current

    var ssid by remember { mutableStateOf(context.getString(R.string.checking)) }

    LaunchedEffect(Unit) {
        while (true) {
            ssid = getWifiSsid(context)
            kotlinx.coroutines.delay(1000)
        }
    }

    LaunchedEffect(isScanning) {
        if (isScanning) {
            kotlinx.coroutines.delay(5000)
            isScanning = false
            showBottomSheet = true
        }
    }

    Column(modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        if (bridgeEnabled) {
            Card(modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text("Shared TV queue", style = MaterialTheme.typography.titleLarge)
                    Text(if (sharedQueue.connected) "${sharedQueue.items.size} items • ${sharedQueue.control.replace('_', ' ')}" else "TV queue disconnected")
                    Text("All paired clients share these controls. Commands are requests, not confirmed playback state.", style = MaterialTheme.typography.bodySmall)
                    sharedQueue.lastError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    if (sharedBusy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Row {
                        TextButton(onClick = { onSharedAction("pause", null, null, null) }, enabled = !sharedBusy && sharedQueue.connected) { Text("Pause") }
                        TextButton(onClick = { onSharedAction("resume", null, null, null) }, enabled = !sharedBusy && sharedQueue.connected) { Text("Resume") }
                        TextButton(onClick = { onSharedAction("next", null, null, null) }, enabled = !sharedBusy && sharedQueue.items.isNotEmpty()) { Text("Next") }
                    }
                    Row {
                        TextButton(onClick = { onSharedAction("stop", null, null, null) }, enabled = !sharedBusy && sharedQueue.connected) { Text("Stop playback") }
                        TextButton(onClick = { confirmClearShared = true }, enabled = !sharedBusy && sharedQueue.items.isNotEmpty()) { Text("Clear queue") }
                        TextButton(onClick = onSharedRefresh, enabled = !sharedBusy) { Text("Refresh") }
                    }
                    sharedQueue.items.forEachIndexed { index, item ->
                        HorizontalDivider()
                        Text("${index + 1}. ${item.title}${if (sharedQueue.currentId == item.id) " — selected" else ""}", style = MaterialTheme.typography.titleSmall)
                        Text(item.status.replace('_', ' '), style = MaterialTheme.typography.bodySmall)
                        Row {
                            TextButton(onClick = { onSharedAction("play", item.id, null, null) }, enabled = !sharedBusy) { Text("Play") }
                            TextButton(onClick = { onSharedAction("remove", item.id, null, null) }, enabled = !sharedBusy) { Text("Remove") }
                            TextButton(onClick = { onSharedAction("move", item.id, index - 1, sharedQueue.revision) }, enabled = !sharedBusy && index > 0) { Text("Up") }
                            TextButton(onClick = { onSharedAction("move", item.id, index + 1, sharedQueue.revision) }, enabled = !sharedBusy && index < sharedQueue.items.lastIndex) { Text("Down") }
                        }
                    }
                }
            }
        }
        Card(modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text("On this phone", style = MaterialTheme.typography.titleLarge)
                Text("${session.items.size} queued • ${session.cachedBytes / (1024 * 1024)} MiB cached / 2048 MiB limit")
                Text("${session.status}", style = MaterialTheme.typography.bodySmall)
                if (session.busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                session.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Row {
                    TextButton(onClick = onQueueNext, enabled = !session.busy && session.items.isNotEmpty()) { Text("Next") }
                    TextButton(onClick = onSessionStop, enabled = session.active || session.busy || session.items.isNotEmpty()) { Text("Close session") }
                }
                Text("Close session stops phone sharing and clears only cached copies. Originals stay untouched. It does not stop TV playback.", style = MaterialTheme.typography.bodySmall)
                session.items.forEachIndexed { index, item ->
                    HorizontalDivider()
                    Text("${index + 1}. ${item.title}${if (session.currentId == item.id) " — selected" else ""}", style = MaterialTheme.typography.titleSmall)
                    Text(if (item.sizeBytes >= 0) "${item.sizeBytes / (1024 * 1024)} MiB" else "Remote stream", style = MaterialTheme.typography.bodySmall)
                    Row {
                        TextButton(onClick = { onQueuePlay(item.id) }, enabled = !session.busy) { Text("Play") }
                        TextButton(onClick = { onQueueRemove(item.id) }, enabled = !session.busy) { Text("Remove") }
                    }
                }
            }
        }
        // Debug Panel
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp)
                .clickable { debugExpanded = !debugExpanded },
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        stringResource(R.string.debug_info),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Row {
                        TextButton(
                            onClick = {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                val logText = debugLogs.joinToString("\n")
                                val clip = ClipData.newPlainText("DLNA diagnostics", logText)
                                clipboard.setPrimaryClip(clip)
                                Toast.makeText(context, context.getString(R.string.logs_copied), Toast.LENGTH_SHORT).show()
                            }
                        ) {
                            Text(stringResource(R.string.copy), style = MaterialTheme.typography.bodySmall)
                        }
                        Text(
                            if (debugExpanded) "▼" else "▶",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }

                AnimatedVisibility(visible = debugExpanded) {
                    Column(modifier = Modifier.padding(top = 8.dp)) {
                        StatusRow(stringResource(R.string.multicast_lock), multicastLockAcquired)
                        StatusRow(stringResource(R.string.location_permission), ContextCompat.checkSelfPermission(
                            context, Manifest.permission.ACCESS_FINE_LOCATION
                        ) == android.content.pm.PackageManager.PERMISSION_GRANTED)

                        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                        Text(
                            stringResource(R.string.recent_logs, debugLogs.size),
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Bold
                        )

                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(200.dp)
                        ) {
                            items(debugLogs.size) { index ->
                                val log = debugLogs[debugLogs.size - 1 - index]
                                Text(
                                    log,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontSize = 10.sp,
                                    modifier = Modifier.padding(vertical = 2.dp)
                                )
                            }
                        }
                    }
                }
            }
        }

        TextField(
            value = currentMediaUrl,
            onValueChange = onMediaUrlChange,
            label = { Text("Media Link (M3U8/MP4)") },
            modifier = Modifier.fillMaxWidth()
        )
        TextButton(onClick = onQueueUrl, enabled = !session.busy && currentMediaUrl.isNotBlank()) { Text("Add link to queue") }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = bridgeEnabled, onCheckedChange = onBridgeEnabledChange)
            Text("Paired TV bridge (instead of DLNA)")
        }
        Button(onClick = onBridgeScan, modifier = Modifier.fillMaxWidth()) { Text("Scan TV pairing QR") }
        if (bridgeEnabled) {
            OutlinedTextField(value = bridgeAddress, onValueChange = onBridgeAddressChange,
                label = { Text("TV IP address / hostname (port 46901)") },
                singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = bridgeToken, onValueChange = onBridgeTokenChange,
                label = { Text("TV pairing token") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
            Row {
                Button(onClick = onBridgeSaveTest, enabled = !bridgeTesting) {
                    Text(if (bridgeTesting) "Testing…" else "Save / test bridge")
                }
                TextButton(onClick = onBridgePlay, enabled = !bridgeTesting && currentMediaUrl.isNotBlank()) {
                    Text("Play current URL")
                }
            }
            Text("Uses saved pairing. Bridge failures never fall back to DLNA.", style = MaterialTheme.typography.bodySmall)
        }

        Button(
            onClick = {
                if (!isScanning) {
                    onScanClick()
                    isScanning = true
                }
            },
            modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
            enabled = !isScanning && !bridgeEnabled
        ) {
            Text(stringResource(if (isScanning) R.string.scanning else R.string.scan))
        }

        Text(text = stringResource(R.string.ssid, ssid))
        Text(
            text = stringResource(R.string.device_count, devices.size),
            style = MaterialTheme.typography.bodyMedium,
            color = if (devices.isNotEmpty()) Color.Green else Color.Gray,
            modifier = Modifier.padding(top = 8.dp)
        )

        if (showBottomSheet) {
            ModalBottomSheet(
                onDismissRequest = { showBottomSheet = false },
                sheetState = sheetState
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        stringResource(R.string.choose_device),
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            stringResource(R.string.device_count, devices.size),
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.Gray
                        )
                        TextButton(onClick = { onScanClick() }) {
                            Text(stringResource(R.string.refresh))
                        }
                    }
                }

                LazyColumn {
                    items(devices) { device ->
                        ListItem(
                            headlineContent = { Text(device.friendlyName) },
                            supportingContent = { Text("${device.manufacturer} - ${device.modelName}") },
                            modifier = Modifier.clickable {
                                onCastClick(device, currentMediaUrl)
                                showBottomSheet = false
                            }
                        )
                    }
                    if (devices.isEmpty()) {
                        item {
                            Column(
                                modifier = Modifier.fillMaxWidth().padding(32.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(
                                    stringResource(R.string.no_devices),
                                    style = MaterialTheme.typography.titleMedium,
                                    modifier = Modifier.padding(bottom = 8.dp)
                                )
                                Text(
                                    stringResource(R.string.discovery_help),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color.Gray,
                                    textAlign = TextAlign.Center
                                )
                            }
                        }
                    }
                }
            }
        }
    }
    if (confirmClearShared) AlertDialog(
        onDismissRequest = { confirmClearShared = false },
        title = { Text("Clear the shared TV queue?") },
        text = { Text("Removes entries from every client. Current TV playback continues; phone caches are not deleted.") },
        confirmButton = { TextButton(onClick = { confirmClearShared = false; onSharedAction("clear", null, null, null) }) { Text("Clear queue") } },
        dismissButton = { TextButton(onClick = { confirmClearShared = false }) { Text("Cancel") } }
    )
}

@Composable
fun StatusRow(label: String, isOk: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall)
        Text(
            stringResource(if (isOk) R.string.ok else R.string.unavailable),
            color = if (isOk) Color.Green else Color.Red,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Bold
        )
    }
}

@Preview(showBackground = true)
@Composable
fun MainScreenPreview() {
    DLNALinkTheme {
        MainScreen(
            devices = emptyList(),
            debugLogs = listOf("[12:00:00] 应用启动"),
            multicastLockAcquired = true,
            onScanClick = {},
            onCastClick = { _, _ -> },
            currentMediaUrl = "http://example.com/video.mp4",
            onMediaUrlChange = {}
        )
    }
}
