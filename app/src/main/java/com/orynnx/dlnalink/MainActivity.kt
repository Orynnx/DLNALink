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
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.orynnx.dlnalink.ui.theme.DLNALinkTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

import android.content.Intent
import android.text.format.Formatter

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()
    private var multicastLock: WifiManager.MulticastLock? = null
    private var ssdpDiscovery: SsdpDiscovery? = null

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
        addDebugLog("位置权限${if (isGranted) "已授予" else "被拒绝"}")
        if (!isGranted) {
            Toast.makeText(this, "需要位置权限才能发现DLNA设备", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        addDebugLog("应用启动 - 原生 SSDP 实现")

        // 处理分享的链接
        handleIntent(intent)

        // Request location permission
        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            addDebugLog("请求位置权限")
            locationPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        } else {
            addDebugLog("位置权限已授予")
        }

        // Acquire multicast lock
        acquireMulticastLock()

        // Initialize SSDP discovery
        ssdpDiscovery = SsdpDiscovery { device ->
            addDebugLog("★ 发现设备: ${device.friendlyName}")
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

                    MainScreen(
                        devices = devices,
                        currentMediaUrl = mediaUrl,
                        onMediaUrlChange = { viewModel.updateMediaUrl(it) },
                        debugLogs = debugLogs,
                        multicastLockAcquired = multicastLockAcquired,
                        onScanClick = {
                            addDebugLog("手动触发 SSDP 扫描")
                            viewModel.clearDevices()
                            scope.launch {
                                try {
                                    ssdpDiscovery?.discover()
                                } catch (e: Exception) {
                                    addDebugLog("扫描出错: ${e.message}")
                                }
                            }
                        },
                        onCastClick = { device, url ->
                            addDebugLog("开始投屏到: ${device.friendlyName}")
                            scope.launch {
                                val success = viewModel.castToDevice(device, url)
                                if (success) {
                                    Toast.makeText(this@MainActivity, "投屏成功", Toast.LENGTH_SHORT).show()
                                } else {
                                    Toast.makeText(this@MainActivity, "投屏失败", Toast.LENGTH_SHORT).show()
                                }
                            }
                        },
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            intent.getStringExtra(Intent.EXTRA_TEXT)?.let { sharedText ->
                addDebugLog("收到分享内容: $sharedText")
                val url = extractUrl(sharedText)
                if (url.isNotEmpty()) {
                    val processedUrl = processLocalhostUrl(url)
                    viewModel.updateMediaUrl(processedUrl)
                    addDebugLog("解析链接: $processedUrl")
                    Toast.makeText(this, "已填入分享链接", Toast.LENGTH_SHORT).show()
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
                addDebugLog("替换 Localhost 为本地 IP: $ip")
                return url.replace("127.0.0.1", ip).replace("localhost", ip)
            } else {
                addDebugLog("无法获取本地 IP，保持 URL 不变")
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
            addDebugLog("获取 IP 失败: ${e.message}")
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
            addDebugLog("Multicast 锁已获取")
        } catch (e: Exception) {
            addDebugLog("获取 Multicast 锁失败: ${e.message}")
        }
    }

    private fun releaseMulticastLock() {
        try {
            multicastLock?.let {
                if (it.isHeld) {
                    it.release()
                    _multicastLockAcquired.value = false
                    addDebugLog("Multicast 锁已释放")
                }
            }
            multicastLock = null
        } catch (e: Exception) {
            addDebugLog("释放 Multicast 锁出错: ${e.message}")
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
            return "需要位置权限"
        }

        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

        // 首先检查 WiFi 是否启用
        if (!wifiManager.isWifiEnabled) {
            return "WiFi 未启用"
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
            return "未连接WiFi"
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
        "错误: ${e.message}"
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
    modifier: Modifier = Modifier
) {
    var showBottomSheet by remember { mutableStateOf(false) }
    var isScanning by remember { mutableStateOf(false) }
    var debugExpanded by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState()
    val context = LocalContext.current

    var ssid by remember { mutableStateOf("检查中...") }

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

    Column(modifier = modifier.fillMaxSize().padding(16.dp)) {
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
                        "调试信息",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Row {
                        TextButton(
                            onClick = {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                val logText = debugLogs.joinToString("\n")
                                val clip = ClipData.newPlainText("DLNA调试日志", logText)
                                clipboard.setPrimaryClip(clip)
                                Toast.makeText(context, "日志已复制", Toast.LENGTH_SHORT).show()
                            }
                        ) {
                            Text("复制", style = MaterialTheme.typography.bodySmall)
                        }
                        Text(
                            if (debugExpanded) "▼" else "▶",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }

                AnimatedVisibility(visible = debugExpanded) {
                    Column(modifier = Modifier.padding(top = 8.dp)) {
                        StatusRow("Multicast 锁", multicastLockAcquired)
                        StatusRow("位置权限", ContextCompat.checkSelfPermission(
                            context, Manifest.permission.ACCESS_FINE_LOCATION
                        ) == android.content.pm.PackageManager.PERMISSION_GRANTED)

                        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                        Text(
                            "最近日志 (共 ${debugLogs.size} 条):",
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

        Button(
            onClick = {
                if (!isScanning) {
                    onScanClick()
                    isScanning = true
                }
            },
            modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
            enabled = !isScanning
        ) {
            Text(if (isScanning) "扫描中..." else "扫描 DLNA 设备")
        }

        Text(text = "当前 SSID: $ssid")
        Text(
            text = "已发现设备: ${devices.size}",
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
                        "选择设备",
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            "已发现 ${devices.size} 个设备",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.Gray
                        )
                        TextButton(onClick = { onScanClick() }) {
                            Text("刷新")
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
                                    "未找到设备",
                                    style = MaterialTheme.typography.titleMedium,
                                    modifier = Modifier.padding(bottom = 8.dp)
                                )
                                Text(
                                    "请确保:\n• 设备与手机在同一WiFi\n• DLNA设备已开启\n• 路由器未启用AP隔离",
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
}

@Composable
fun StatusRow(label: String, isOk: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall)
        Text(
            if (isOk) "正常" else "异常",
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
