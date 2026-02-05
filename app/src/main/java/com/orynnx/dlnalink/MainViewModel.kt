package com.orynnx.dlnalink

import android.util.Log
import androidx.lifecycle.ViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

data class DlnaDevice(
    val uid: String,
    val friendlyName: String,
    val modelName: String,
    val manufacturer: String,
    val location: String,
    val deviceType: String,
    val controlUrl: String? = null // 新增控制地址字段
)

class MainViewModel : ViewModel() {
    private val _devices = MutableStateFlow<List<DlnaDevice>>(emptyList())
    val devices: StateFlow<List<DlnaDevice>> = _devices.asStateFlow()

    // 默认视频链接
    private val _mediaUrl = MutableStateFlow("http://vjs.zencdn.net/v/oceans.mp4")
    val mediaUrl: StateFlow<String> = _mediaUrl.asStateFlow()

    fun updateMediaUrl(url: String) {
        _mediaUrl.value = url
    }

    // 创建独立的 OkHttpClient 用于 SOAP 请求，超时时间设长一点
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()

    fun addDevice(device: UpnpDevice) {
        val newDevice = DlnaDevice(
            uid = device.udn,
            friendlyName = device.friendlyName,
            modelName = device.modelName,
            manufacturer = device.manufacturer,
            location = device.location,
            deviceType = device.deviceType,
            controlUrl = device.avTransportControlUrl
        )

        _devices.update { currentList ->
            // 如果设备已存在，更新它（因为控制地址可能在后续扫描中才解析出来）
            val index = currentList.indexOfFirst { it.uid == newDevice.uid }
            if (index >= 0) {
                val mutableList = currentList.toMutableList()
                // 只有当新对象有更多信息或者一样时才更新
                if (newDevice.controlUrl != null || mutableList[index].controlUrl == null) {
                    Log.d("DLNADiscovery", "更新设备信息: ${device.friendlyName}")
                    mutableList[index] = newDevice
                    mutableList
                } else {
                    currentList
                }
            } else {
                Log.d("DLNADiscovery", "新设备加入: ${device.friendlyName}")
                if (newDevice.controlUrl != null) {
                    Log.d("DLNADiscovery", "  ✓ 包含控制地址: ${newDevice.controlUrl}")
                } else {
                    Log.w("DLNADiscovery", "  ⚠ 未找到 AVTransport 控制地址")
                }
                currentList + newDevice
            }
        }
    }

    fun clearDevices() {
        _devices.value = emptyList()
    }

    suspend fun castToDevice(device: DlnaDevice, mediaUrl: String): Boolean = withContext(Dispatchers.IO) {
        if (device.controlUrl == null) {
            Log.e("DlnaCast", "无法投屏: 设备 '${device.friendlyName}' 没有 AVTransport 控制地址")
            return@withContext false
        }

        try {
            Log.d("DlnaCast", "========================================")
            Log.d("DlnaCast", "开始投屏流程")
            Log.d("DlnaCast", "设备: ${device.friendlyName}")
            Log.d("DlnaCast", "媒体: $mediaUrl")
            Log.d("DlnaCast", "控制点: ${device.controlUrl}")

            // 1. 发送 Stop 指令（清理之前的播放状态）
            Log.d("DlnaCast", "[1/3] 发送 Stop 指令")
            sendSoapAction(device.controlUrl, "Stop", "0")

            // 2. 发送 SetAVTransportURI 指令（包含元数据）
            Log.d("DlnaCast", "[2/3] 设置播放 URI")
            // 构造 DIDL-Lite 元数据，这对很多电视是必须的
            val metadata = createDidlMetadata(mediaUrl, "DLNA Cast Video")
            val setUriSuccess = sendSetAVTransportUri(device.controlUrl, mediaUrl, metadata)

            if (!setUriSuccess) {
                Log.e("DlnaCast", "❌ 设置 URI 失败")
                return@withContext false
            }

            // 3. 发送 Play 指令
            Log.d("DlnaCast", "[3/3] 发送 Play 指令")
            val playSuccess = sendSoapAction(device.controlUrl, "Play", "0", mapOf("Speed" to "1"))

            if (playSuccess) {
                Log.d("DlnaCast", "🎉 投屏指令发送成功！")
            } else {
                Log.e("DlnaCast", "❌ Play 指令失败")
            }

            return@withContext playSuccess

        } catch (e: Exception) {
            Log.e("DlnaCast", "投屏过程发生异常", e)
            return@withContext false
        }
    }

    private fun sendSetAVTransportUri(controlUrl: String, mediaUrl: String, metadata: String): Boolean {
        val action = "SetAVTransportURI"
        val serviceType = "urn:schemas-upnp-org:service:AVTransport:1"

        // 注意：metadata 需要被转义放入 XML 标签中，但 createDidlMetadata 已经生成了转义后的 XML 字符串
        // 这里直接放入 CurrentURIMetaData 标签

        val soapBody = """<?xml version="1.0" encoding="utf-8"?>
<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
    <s:Body>
        <u:$action xmlns:u="$serviceType">
            <InstanceID>0</InstanceID>
            <CurrentURI>$mediaUrl</CurrentURI>
            <CurrentURIMetaData>$metadata</CurrentURIMetaData>
        </u:$action>
    </s:Body>
</s:Envelope>"""

        return executeSoapRequest(controlUrl, action, serviceType, soapBody)
    }

    private fun sendSoapAction(controlUrl: String, action: String, instanceId: String, extraArgs: Map<String, String> = emptyMap()): Boolean {
        val serviceType = "urn:schemas-upnp-org:service:AVTransport:1"

        val argsBuilder = StringBuilder()
        argsBuilder.append("<InstanceID>$instanceId</InstanceID>")
        extraArgs.forEach { (k, v) -> argsBuilder.append("<$k>$v</$k>") }

        val soapBody = """<?xml version="1.0" encoding="utf-8"?>
<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
    <s:Body>
        <u:$action xmlns:u="$serviceType">
            $argsBuilder
        </u:$action>
    </s:Body>
</s:Envelope>"""

        return executeSoapRequest(controlUrl, action, serviceType, soapBody)
    }

    private fun executeSoapRequest(controlUrl: String, action: String, serviceType: String, soapBody: String): Boolean {
        try {
            val mediaType = "text/xml; charset=\"utf-8\"".toMediaType()
            val requestBody = soapBody.toRequestBody(mediaType)

            val request = Request.Builder()
                .url(controlUrl)
                .post(requestBody)
                .addHeader("SOAPAction", "\"$serviceType#$action\"")
                .addHeader("User-Agent", "Android DLNA/1.0")
                .addHeader("Connection", "close")
                .build()

            httpClient.newCall(request).execute().use { response ->
                val body = response.body?.string() ?: ""
                Log.d("DlnaCast", "SOAP $action 响应: ${response.code}")
                if (!response.isSuccessful) {
                    Log.w("DlnaCast", "错误响应内容: $body")
                }
                return response.isSuccessful
            }
        } catch (e: Exception) {
            Log.e("DlnaCast", "SOAP 请求异常: $action", e)
            return false
        }
    }

    // 生成 DIDL-Lite 元数据，这对于某些电视（如索尼、三星）是必须的
    private fun createDidlMetadata(mediaUrl: String, title: String): String {
        // XML 转义函数
        fun escape(s: String) = s.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")

        val escapedUrl = escape(mediaUrl)
        val escapedTitle = escape(title)

        // 注意：这里生成的字符串会被放入 XML 标签中，所以需要是转义过的 XML 实体
        return "&lt;DIDL-Lite xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\" xmlns:dc=\"http://purl.org/dc/elements/1.1/\" xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\" xmlns:dlna=\"urn:schemas-dlna-org:metadata-1-0/\"&gt;" +
               "&lt;item id=\"1\" parentID=\"0\" restricted=\"1\"&gt;" +
               "&lt;dc:title&gt;$escapedTitle&lt;/dc:title&gt;" +
               "&lt;upnp:class&gt;object.item.videoItem&lt;/upnp:class&gt;" +
               "&lt;res protocolInfo=\"http-get:*:video/mp4:DLNA.ORG_OP=01;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000\"&gt;$escapedUrl&lt;/res&gt;" +
               "&lt;/item&gt;" +
               "&lt;/DIDL-Lite&gt;"
    }
}
