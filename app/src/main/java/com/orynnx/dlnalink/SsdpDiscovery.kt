package com.orynnx.dlnalink

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.SocketException

data class UpnpDevice(
    val location: String,
    val friendlyName: String,
    val modelName: String,
    val manufacturer: String,
    val udn: String,
    val deviceType: String,
    val avTransportControlUrl: String? = null
)

class SsdpDiscovery(private val onDeviceFound: (UpnpDevice) -> Unit) {

    private val TAG = "SsdpDiscovery"
    private val SSDP_ADDRESS = "239.255.255.250"
    private val SSDP_PORT = 1900
    private val SEARCH_TARGET = "urn:schemas-upnp-org:device:MediaRenderer:1"

    suspend fun discover() = withContext(Dispatchers.IO) {
        var socket: DatagramSocket? = null
        try {
            Log.d(TAG, "========================================")
            Log.d(TAG, "开始 SSDP 设备发现")
            Log.d(TAG, "========================================")

            // 打印网络接口信息
            printNetworkInterfaces()

            // 创建 DatagramSocket
            Log.d(TAG, "\n[步骤 1] 创建 UDP Socket")
            socket = DatagramSocket().apply {
                reuseAddress = true
                broadcast = true
                soTimeout = 8000
            }

            val localAddress = socket.localAddress.hostAddress
            val localPort = socket.localPort
            Log.d(TAG, "✓ Socket 创建成功")
            Log.d(TAG, "  本地地址: $localAddress")
            Log.d(TAG, "  本地端口: $localPort")
            Log.d(TAG, "  ReuseAddress: ${socket.reuseAddress}")
            Log.d(TAG, "  Broadcast: ${socket.broadcast}")
            Log.d(TAG, "  SO_TIMEOUT: ${socket.soTimeout} ms")
            Log.d(TAG, "  接收缓冲区: ${socket.receiveBufferSize} bytes")
            Log.d(TAG, "  发送缓冲区: ${socket.sendBufferSize} bytes")

            // 构建 SSDP M-SEARCH 消息
            Log.d(TAG, "\n[步骤 2] 构建 SSDP M-SEARCH 消息")
            val searchMessage = buildString {
                append("M-SEARCH * HTTP/1.1\r\n")
                append("HOST: $SSDP_ADDRESS:$SSDP_PORT\r\n")
                append("MAN: \"ssdp:discover\"\r\n")
                append("MX: 3\r\n")
                append("ST: $SEARCH_TARGET\r\n")
                append("USER-AGENT: Android UPnP/1.0\r\n")
                append("\r\n")
            }

            Log.d(TAG, "消息内容:")
            Log.d(TAG, "----------------------------------------")
            searchMessage.lines().forEach { line ->
                Log.d(TAG, line)
            }
            Log.d(TAG, "----------------------------------------")
            Log.d(TAG, "消息长度: ${searchMessage.length} 字符")
            Log.d(TAG, "消息字节: ${searchMessage.toByteArray().size} bytes")

            // 验证消息格式
            val hasCorrectLineEndings = searchMessage.contains("\r\n")
            Log.d(TAG, "换行符检查: ${if (hasCorrectLineEndings) "✓ 使用 \\r\\n" else "✗ 未使用 \\r\\n"}")

            // 发送 MediaRenderer 搜索
            Log.d(TAG, "\n[步骤 3] 发送 SSDP 搜索请求")
            val searchData = searchMessage.toByteArray()
            val multicastAddr = InetAddress.getByName(SSDP_ADDRESS)
            Log.d(TAG, "多播地址: ${multicastAddr.hostAddress}")
            Log.d(TAG, "目标端口: $SSDP_PORT")

            val searchPacket = DatagramPacket(
                searchData,
                searchData.size,
                multicastAddr,
                SSDP_PORT
            )

            try {
                socket.send(searchPacket)
                Log.d(TAG, "✓ MediaRenderer 搜索请求已发送")
                Log.d(TAG, "  发送字节数: ${searchPacket.length}")
            } catch (e: Exception) {
                Log.e(TAG, "✗ 发送 MediaRenderer 搜索失败", e)
            }

            // 发送 ssdp:all 搜索
            val allDevicesSearch = searchMessage.replace(SEARCH_TARGET, "ssdp:all")
            val allDevicesData = allDevicesSearch.toByteArray()
            val allDevicesPacket = DatagramPacket(
                allDevicesData,
                allDevicesData.size,
                multicastAddr,
                SSDP_PORT
            )

            try {
                socket.send(allDevicesPacket)
                Log.d(TAG, "✓ ssdp:all 搜索请求已发送")
                Log.d(TAG, "  发送字节数: ${allDevicesPacket.length}")
            } catch (e: Exception) {
                Log.e(TAG, "✗ 发送 ssdp:all 搜索失败", e)
            }

            // 接收响应
            Log.d(TAG, "\n[步骤 4] 监听 SSDP 响应")
            Log.d(TAG, "等待响应... (超时 ${socket.soTimeout} ms)")
            Log.d(TAG, "========================================")

            val buffer = ByteArray(8192)
            val discoveredLocations = mutableSetOf<String>()
            var responseCount = 0
            val startTime = System.currentTimeMillis()

            try {
                while (true) {
                    val packet = DatagramPacket(buffer, buffer.size)

                    // 尝试接收数据
                    try {
                        socket.receive(packet)
                    } catch (e: SocketException) {
                        Log.e(TAG, "Socket 接收异常", e)
                        break
                    }

                    responseCount++
                    val elapsedTime = System.currentTimeMillis() - startTime
                    val response = String(packet.data, 0, packet.length)
                    val senderAddress = packet.address.hostAddress
                    val senderPort = packet.port

                    Log.d(TAG, "")
                    Log.d(TAG, "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
                    Log.d(TAG, "📨 响应 #$responseCount (${elapsedTime}ms)")
                    Log.d(TAG, "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
                    Log.d(TAG, "来源地址: $senderAddress:$senderPort")
                    Log.d(TAG, "数据长度: ${packet.length} bytes")
                    Log.d(TAG, "----------------------------------------")
                    Log.d(TAG, "响应内容:")
                    response.lines().forEach { line ->
                        Log.d(TAG, line)
                    }
                    Log.d(TAG, "----------------------------------------")

                    // 解析 LOCATION 头
                    val location = parseLocation(response)
                    if (location != null && !discoveredLocations.contains(location)) {
                        discoveredLocations.add(location)
                        Log.d(TAG, "✓ 发现新设备!")
                        Log.d(TAG, "  LOCATION: $location")

                        // 获取设备描述
                        fetchDeviceDescription(location)
                    } else if (location != null) {
                        Log.d(TAG, "ℹ 已存在的设备: $location")
                    } else {
                        Log.d(TAG, "⚠ 未找到 LOCATION 头")
                    }
                }
            } catch (e: java.net.SocketTimeoutException) {
                val totalTime = System.currentTimeMillis() - startTime
                Log.d(TAG, "")
                Log.d(TAG, "========================================")
                Log.d(TAG, "⏱ SSDP 搜索超时")
                Log.d(TAG, "========================================")
                Log.d(TAG, "总耗时: ${totalTime} ms")
                Log.d(TAG, "总响应数: $responseCount")
                Log.d(TAG, "发现设备: ${discoveredLocations.size}")

                if (responseCount == 0) {
                    Log.w(TAG, "")
                    Log.w(TAG, "⚠⚠⚠ 警告: 未收到任何响应! ⚠⚠⚠")
                    Log.w(TAG, "可能的原因:")
                    Log.w(TAG, "  1. 网络中没有 DLNA 设备")
                    Log.w(TAG, "  2. Android 防火墙/权限问题")
                    Log.w(TAG, "  3. WiFi 节能模式阻止了多播")
                    Log.w(TAG, "  4. 路由器 AP 隔离已启用")
                    Log.w(TAG, "  5. Multicast Lock 未正确获取")
                }

                Log.d(TAG, "========================================")
            }

        } catch (e: Exception) {
            Log.e(TAG, "========================================")
            Log.e(TAG, "✗ SSDP 发现出错", e)
            Log.e(TAG, "错误类型: ${e.javaClass.simpleName}")
            Log.e(TAG, "错误消息: ${e.message}")
            e.printStackTrace()
            Log.e(TAG, "========================================")
        } finally {
            socket?.close()
            Log.d(TAG, "\n[清理] Socket 已关闭")
        }
    }

    private fun printNetworkInterfaces() {
        try {
            Log.d(TAG, "\n[网络接口信息]")
            val interfaces = NetworkInterface.getNetworkInterfaces()
            var count = 0

            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                count++

                Log.d(TAG, "----------------------------------------")
                Log.d(TAG, "接口 #$count: ${iface.name}")
                Log.d(TAG, "  显示名: ${iface.displayName}")
                Log.d(TAG, "  已启用: ${iface.isUp}")
                Log.d(TAG, "  回环: ${iface.isLoopback}")
                Log.d(TAG, "  支持多播: ${iface.supportsMulticast()}")

                val addresses = iface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val addr = addresses.nextElement()
                    Log.d(TAG, "  地址: ${addr.hostAddress}")
                }
            }
            Log.d(TAG, "----------------------------------------")
            Log.d(TAG, "总共 $count 个网络接口")
        } catch (e: Exception) {
            Log.e(TAG, "获取网络接口信息失败", e)
        }
    }

    private fun parseLocation(response: String): String? {
        val lines = response.split("\r\n")
        for (line in lines) {
            if (line.startsWith("LOCATION:", ignoreCase = true) ||
                line.startsWith("Location:", ignoreCase = true)) {
                val location = line.substringAfter(":").trim()
                return location
            }
        }
        return null
    }

    private suspend fun fetchDeviceDescription(location: String) = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "\n[获取设备描述]")
            Log.d(TAG, "URL: $location")

            val client = okhttp3.OkHttpClient.Builder()
                .connectTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
                .build()

            val request = okhttp3.Request.Builder()
                .url(location)
                .get()
                .build()

            val startTime = System.currentTimeMillis()
            val response = client.newCall(request).execute()
            val elapsed = System.currentTimeMillis() - startTime

            Log.d(TAG, "HTTP 响应码: ${response.code}")
            Log.d(TAG, "耗时: ${elapsed} ms")

            if (response.isSuccessful) {
                val xml = response.body?.string() ?: return@withContext
                Log.d(TAG, "✓ 成功获取设备描述")
                Log.d(TAG, "XML 长度: ${xml.length} 字符")

                // 只显示前500字符
                val preview = if (xml.length > 500) xml.substring(0, 500) + "..." else xml
                Log.d(TAG, "XML 预览:")
                Log.d(TAG, "----------------------------------------")
                Log.d(TAG, preview)
                Log.d(TAG, "----------------------------------------")

                val device = parseDeviceDescription(xml, location)
                if (device != null) {
                    Log.d(TAG, "✓ 设备解析成功!")
                    Log.d(TAG, "  名称: ${device.friendlyName}")
                    Log.d(TAG, "  型号: ${device.modelName}")
                    Log.d(TAG, "  制造商: ${device.manufacturer}")
                    onDeviceFound(device)
                } else {
                    Log.w(TAG, "✗ 设备解析失败")
                }
            } else {
                Log.e(TAG, "✗ HTTP 请求失败: ${response.code}")
            }

        } catch (e: Exception) {
            Log.e(TAG, "获取设备描述异常: $location", e)
        }
    }

    private fun parseDeviceDescription(xml: String, location: String): UpnpDevice? {
        try {
            val friendlyName = extractXmlTag(xml, "friendlyName") ?: "Unknown Device"
            val modelName = extractXmlTag(xml, "modelName") ?: "Unknown Model"
            val manufacturer = extractXmlTag(xml, "manufacturer") ?: "Unknown"
            val udn = extractXmlTag(xml, "UDN") ?: "unknown-udn"
            val deviceType = extractXmlTag(xml, "deviceType") ?: "unknown"

            // Extract AVTransport Control URL
            var avTransportControlUrl: String? = null

            // Find the service block for AVTransport
            val avTransportTag = "urn:schemas-upnp-org:service:AVTransport:1"
            val serviceTypeIndex = xml.indexOf(avTransportTag)

            if (serviceTypeIndex != -1) {
                // Search for controlURL within the vicinity of this service type
                // We look forward from the service type to find the next controlURL
                val controlUrlStartTag = "<controlURL>"
                val controlUrlEndTag = "</controlURL>"

                val startIndex = xml.indexOf(controlUrlStartTag, serviceTypeIndex)
                if (startIndex != -1) {
                    val endIndex = xml.indexOf(controlUrlEndTag, startIndex)
                    if (endIndex != -1) {
                        val relativeUrl = xml.substring(startIndex + controlUrlStartTag.length, endIndex).trim()

                        // Construct absolute URL
                        val baseUrl = if (location.endsWith("/")) location.dropLast(1) else location
                        avTransportControlUrl = if (relativeUrl.startsWith("/")) {
                            // Extract host from location (http://ip:port)
                            val urlParts = java.net.URL(location)
                            "${urlParts.protocol}://${urlParts.host}:${urlParts.port}$relativeUrl"
                        } else {
                            "$baseUrl/$relativeUrl"
                        }

                        Log.d(TAG, "Found AVTransport Control URL: $avTransportControlUrl")
                    }
                }
            }

            return UpnpDevice(
                location = location,
                friendlyName = friendlyName,
                modelName = modelName,
                manufacturer = manufacturer,
                udn = udn,
                deviceType = deviceType,
                avTransportControlUrl = avTransportControlUrl
            )

        } catch (e: Exception) {
            Log.e(TAG, "解析设备描述失败", e)
            return null
        }
    }

    private fun extractXmlTag(xml: String, tag: String): String? {
        val startTag = "<$tag>"
        val endTag = "</$tag>"
        val startIndex = xml.indexOf(startTag)
        if (startIndex == -1) return null
        val endIndex = xml.indexOf(endTag, startIndex)
        if (endIndex == -1) return null
        return xml.substring(startIndex + startTag.length, endIndex).trim()
    }
}
