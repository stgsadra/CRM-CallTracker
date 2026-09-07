package com.crm.calltracker

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.LinkAddress
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.Socket
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

object ServerDiscovery {

    private const val SERVICE_TYPE = "_crm._tcp."
    private const val SERVER_PORT = 5001

    private const val MDNS_TIMEOUT_MS = 15000L
    private const val SCAN_TIMEOUT_MS = 15000L

    fun hasNearbyWifiPermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.NEARBY_WIFI_DEVICES
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    fun findServer(
        context: Context,
        onFound: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        val appContext = context.applicationContext

        if (!hasNearbyWifiPermission(appContext)) {
            onError(
                "مجوز «دستگاه‌های نزدیک» برای پیدا کردن سرور داده نشده است."
            )
            return
        }

        val connectivityManager =
            appContext.getSystemService(
                Context.CONNECTIVITY_SERVICE
            ) as ConnectivityManager

        val nsdManager =
            appContext.getSystemService(
                Context.NSD_SERVICE
            ) as NsdManager

        val handler =
            Handler(Looper.getMainLooper())

        val finished =
            AtomicBoolean(false)

        val executor =
            Executors.newCachedThreadPool()

        var discoveryListener:
            NsdManager.DiscoveryListener? = null

        fun cleanupNsd() {
            try {
                discoveryListener?.let {
                    nsdManager.stopServiceDiscovery(it)
                }
            } catch (_: Exception) {
            }

            discoveryListener = null
        }

        fun shutdown() {
            cleanupNsd()

            try {
                executor.shutdownNow()
            } catch (_: Exception) {
            }
        }

        fun success(url: String) {
            if (!finished.compareAndSet(false, true)) {
                return
            }

            shutdown()

            val finalUrl =
                url.trimEnd('/')

            ApiConfig.SERVER_URL = finalUrl

            handler.post {
                onFound(finalUrl)
            }
        }

        fun error(message: String) {
            if (!finished.compareAndSet(false, true)) {
                return
            }

            shutdown()

            handler.post {
                onError(message)
            }
        }

        fun startNetworkScan() {

            if (finished.get()) {
                return
            }

            thread {

                try {

                    val network =
                        getWifiNetwork(connectivityManager)

                    if (network == null) {
                        error(
                            "شبکه Wi-Fi فعال پیدا نشد."
                        )
                        return@thread
                    }

                    val linkProperties =
                        connectivityManager.getLinkProperties(
                            network
                        )

                    val candidates =
                        buildCandidateAddresses(
                            linkProperties
                        )

                    if (candidates.isEmpty()) {
                        error(
                            "آدرس‌های شبکه Wi-Fi برای جستجوی سرور پیدا نشد."
                        )
                        return@thread
                    }

                    val scanExecutor =
                        Executors.newFixedThreadPool(32)

                    val scanFinished =
                        AtomicBoolean(false)

                    val scanTimeout =
                        handler.postDelayed(
                            {
                                if (
                                    scanFinished.compareAndSet(
                                        false,
                                        true
                                    )
                                ) {
                                    try {
                                        scanExecutor.shutdownNow()
                                    } catch (_: Exception) {
                                    }

                                    error(
                                        "mDNS و جستجوی مستقیم شبکه نتوانستند CRM را پیدا کنند.\n\n" +
                                        "لطفاً مطمئن شوید گوشی و لپ‌تاپ به یک Wi-Fi متصل هستند."
                                    )
                                }
                            },
                            SCAN_TIMEOUT_MS
                        )

                    for (address in candidates) {

                        if (
                            finished.get() ||
                            scanFinished.get()
                        ) {
                            break
                        }

                        scanExecutor.execute {

                            if (
                                finished.get() ||
                                scanFinished.get()
                            ) {
                                return@execute
                            }

                            if (
                                isCrmServer(
                                    address,
                                    SERVER_PORT
                                )
                            ) {

                                if (
                                    scanFinished.compareAndSet(
                                        false,
                                        true
                                    )
                                ) {

                                    handler.removeCallbacks(
                                        scanTimeout
                                    )

                                    try {
                                        scanExecutor.shutdownNow()
                                    } catch (_: Exception) {
                                    }

                                    success(
                                        "http://$address:$SERVER_PORT"
                                    )
                                }
                            }
                        }
                    }

                } catch (e: Exception) {

                    error(
                        "خطا در جستجوی سرور در شبکه:\n" +
                            (e.message ?: "خطای نامشخص")
                    )
                }
            }
        }

        fun resolveService(
            serviceInfo: NsdServiceInfo
        ) {

            try {

                if (
                    Build.VERSION.SDK_INT >=
                    Build.VERSION_CODES.TIRAMISU
                ) {

                    nsdManager.resolveService(
                        serviceInfo,
                        executor,
                        object :
                            NsdManager.ResolveListener {

                            override fun onServiceResolved(
                                resolvedInfo: NsdServiceInfo
                            ) {

                                if (finished.get()) {
                                    return
                                }

                                val host =
                                    resolvedInfo.host
                                        ?.hostAddress

                                val port =
                                    resolvedInfo.port

                                if (
                                    !host.isNullOrBlank() &&
                                    port > 0
                                ) {
                                    success(
                                        "http://$host:$port"
                                    )
                                }
                            }

                            override fun onResolveFailed(
                                serviceInfo: NsdServiceInfo,
                                errorCode: Int
                            ) {
                                // Resolve شکست خورد.
                                // فعلاً discovery را ادامه می‌دهیم.
                            }
                        }
                    )

                } else {

                    @Suppress("DEPRECATION")
                    nsdManager.resolveService(
                        serviceInfo,
                        object :
                            NsdManager.ResolveListener {

                            override fun onServiceResolved(
                                resolvedInfo: NsdServiceInfo
                            ) {

                                if (finished.get()) {
                                    return
                                }

                                val host =
                                    resolvedInfo.host
                                        ?.hostAddress

                                val port =
                                    resolvedInfo.port

                                if (
                                    !host.isNullOrBlank() &&
                                    port > 0
                                ) {
                                    success(
                                        "http://$host:$port"
                                    )
                                }
                            }

                            override fun onResolveFailed(
                                serviceInfo: NsdServiceInfo,
                                errorCode: Int
                            ) {
                                // ادامه discovery
                            }
                        }
                    )
                }

            } catch (_: Exception) {
                // در صورت شکست Resolve، discovery ادامه پیدا می‌کند.
            }
        }

        discoveryListener =
            object :
                NsdManager.DiscoveryListener {

                override fun onDiscoveryStarted(
                    serviceType: String
                ) {

                    handler.postDelayed(
                        {
                            if (!finished.get()) {

                                cleanupNsd()

                                startNetworkScan()
                            }
                        },
                        MDNS_TIMEOUT_MS
                    )
                }

                override fun onServiceFound(
                    serviceInfo: NsdServiceInfo
                ) {

                    if (finished.get()) {
                        return
                    }

                    val name =
                        serviceInfo.serviceName ?: ""

                    val type =
                        serviceInfo.serviceType ?: ""

                    if (
                        !type.contains(
                            "_crm._tcp",
                            ignoreCase = true
                        )
                    ) {
                        return
                    }

                    if (
                        !name.contains(
                            "CRM-Server",
                            ignoreCase = true
                        )
                    ) {
                        return
                    }

                    resolveService(
                        serviceInfo
                    )
                }

                override fun onServiceLost(
                    serviceInfo: NsdServiceInfo
                ) {
                }

                override fun onDiscoveryStopped(
                    serviceType: String
                ) {
                }

                override fun onStartDiscoveryFailed(
                    serviceType: String,
                    errorCode: Int
                ) {

                    if (!finished.get()) {

                        cleanupNsd()

                        startNetworkScan()
                    }
                }

                override fun onStopDiscoveryFailed(
                    serviceType: String,
                    errorCode: Int
                ) {
                }
            }

        try {

            if (
                Build.VERSION.SDK_INT >=
                Build.VERSION_CODES.TIRAMISU
            ) {

                val network =
                    getWifiNetwork(
                        connectivityManager
                    )

                if (network == null) {

                    startNetworkScan()

                    return
                }

                nsdManager.discoverServices(
                    SERVICE_TYPE,
                    NsdManager.PROTOCOL_DNS_SD,
                    network,
                    executor,
                    discoveryListener!!
                )

            } else {

                @Suppress("DEPRECATION")
                nsdManager.discoverServices(
                    SERVICE_TYPE,
                    NsdManager.PROTOCOL_DNS_SD,
                    discoveryListener!!
                )
            }

        } catch (e: SecurityException) {

            cleanupNsd()

            startNetworkScan()

        } catch (e: Exception) {

            cleanupNsd()

            startNetworkScan()
        }
    }

    private fun getWifiNetwork(
        connectivityManager: ConnectivityManager
    ): Network? {

        try {

            val activeNetwork =
                connectivityManager.activeNetwork

            if (activeNetwork != null) {

                val capabilities =
                    connectivityManager.getNetworkCapabilities(
                        activeNetwork
                    )

                if (
                    capabilities?.hasTransport(
                        NetworkCapabilities.TRANSPORT_WIFI
                    ) == true
                ) {
                    return activeNetwork
                }
            }

            for (
                network in connectivityManager.allNetworks
            ) {

                val capabilities =
                    connectivityManager.getNetworkCapabilities(
                        network
                    )

                if (
                    capabilities?.hasTransport(
                        NetworkCapabilities.TRANSPORT_WIFI
                    ) == true
                ) {
                    return network
                }
            }

        } catch (_: Exception) {
        }

        return null
    }

    private fun buildCandidateAddresses(
        linkProperties: LinkProperties?
    ): List<String> {

        if (linkProperties == null) {
            return emptyList()
        }

        val result =
            mutableListOf<String>()

        for (
            linkAddress in linkProperties.linkAddresses
        ) {

            val address =
                linkAddress.address

            if (address !is Inet4Address) {
                continue
            }

            val prefix =
                linkAddress.prefixLength

            if (prefix < 16 || prefix > 30) {
                continue
            }

            val bytes =
                address.address

            val ip =
                ((bytes[0].toInt() and 0xFF) shl 24) or
                    ((bytes[1].toInt() and 0xFF) shl 16) or
                    ((bytes[2].toInt() and 0xFF) shl 8) or
                    (bytes[3].toInt() and 0xFF)

            val mask =
                (-1 shl (32 - prefix))

            val network =
                ip and mask

            val hostCount =
                1L shl (32 - prefix)

            // برای جلوگیری از اسکن شبکه‌های بسیار بزرگ،
            // حداکثر 1024 آدرس را بررسی می‌کنیم.
            val maxHosts =
                minOf(
                    hostCount - 2,
                    1024L
                )

            for (i in 1..maxHosts) {

                val candidate =
                    network + i

                val a =
                    (candidate shr 24) and 0xFF

                val b =
                    (candidate shr 16) and 0xFF

                val c =
                    (candidate shr 8) and 0xFF

                val d =
                    candidate and 0xFF

                result.add(
                    "$a.$b.$c.$d"
                )
            }
        }

        return result.distinct()
    }

    private fun isCrmServer(
        host: String,
        port: Int
    ): Boolean {

        var socket: Socket? = null
        var connection: HttpURLConnection? = null

        try {

            socket =
                Socket()

            socket.connect(
                java.net.InetSocketAddress(
                    host,
                    port
                ),
                700
            )

            socket.close()
            socket = null

            val url =
                URL(
                    "http://$host:$port/"
                )

            connection =
                url.openConnection()
                    as HttpURLConnection

            connection.connectTimeout = 1200
            connection.readTimeout = 1200
            connection.requestMethod = "GET"

            connection.setRequestProperty(
                "Connection",
                "close"
            )

            val responseCode =
                connection.responseCode

            return responseCode in 100..599

        } catch (_: Exception) {

            return false

        } finally {

            try {
                socket?.close()
            } catch (_: Exception) {
            }

            try {
                connection?.disconnect()
            } catch (_: Exception) {
            }
        }
    }
}
