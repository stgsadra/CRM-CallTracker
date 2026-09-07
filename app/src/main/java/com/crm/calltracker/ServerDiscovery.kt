package com.crm.calltracker

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

object ServerDiscovery {

    private const val SERVICE_TYPE = "_crm._tcp."
    private const val SERVER_PORT = 5001

    private const val MDNS_TIMEOUT_MS = 10000L
    private const val SCAN_TIMEOUT_MS = 20000L

    private const val CONNECT_TIMEOUT_MS = 500
    private const val HTTP_TIMEOUT_MS = 1000

    fun hasNearbyWifiPermission(
        context: Context
    ): Boolean {

        return if (
            Build.VERSION.SDK_INT >=
            Build.VERSION_CODES.TIRAMISU
        ) {

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

        val appContext =
            context.applicationContext

        if (
            !hasNearbyWifiPermission(
                appContext
            )
        ) {

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

        val mainHandler =
            Handler(
                Looper.getMainLooper()
            )

        val finished =
            AtomicBoolean(false)

        val nsdExecutor =
            Executors.newSingleThreadExecutor()

        var discoveryListener:
            NsdManager.DiscoveryListener? = null

        fun stopNsd() {

            try {

                discoveryListener?.let {
                    nsdManager.stopServiceDiscovery(it)
                }

            } catch (_: Exception) {
            }

            discoveryListener = null
        }

        fun finishSuccess(
            serverUrl: String
        ) {

            if (
                !finished.compareAndSet(
                    false,
                    true
                )
            ) {
                return
            }

            stopNsd()

            try {
                nsdExecutor.shutdownNow()
            } catch (_: Exception) {
            }

            val finalUrl =
                serverUrl.trimEnd('/')

            ApiConfig.SERVER_URL =
                finalUrl

            mainHandler.post {
                onFound(finalUrl)
            }
        }

        fun finishError(
            message: String
        ) {

            if (
                !finished.compareAndSet(
                    false,
                    true
                )
            ) {
                return
            }

            stopNsd()

            try {
                nsdExecutor.shutdownNow()
            } catch (_: Exception) {
            }

            mainHandler.post {
                onError(message)
            }
        }

        fun resolveService(
            serviceInfo: NsdServiceInfo
        ) {

            if (finished.get()) {
                return
            }

            try {

                if (
                    Build.VERSION.SDK_INT >=
                    Build.VERSION_CODES.TIRAMISU
                ) {

                    nsdManager.resolveService(
                        serviceInfo,
                        nsdExecutor,
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

                                    finishSuccess(
                                        "http://$host:$port"
                                    )
                                }
                            }

                            override fun onResolveFailed(
                                serviceInfo: NsdServiceInfo,
                                errorCode: Int
                            ) {
                                // اگر Resolve شکست خورد،
                                // تا پایان زمان mDNS صبر می‌کنیم.
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

                                    finishSuccess(
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
                // ادامه discovery
            }
        }

        fun scanNetwork() {

            if (finished.get()) {
                return
            }

            thread {

                var scanExecutor:
                    java.util.concurrent.ExecutorService? =
                    null

                try {

                    val wifiNetwork =
                        getWifiNetwork(
                            connectivityManager
                        )

                    if (
                        wifiNetwork == null
                    ) {

                        finishError(
                            "شبکه Wi-Fi فعال پیدا نشد."
                        )

                        return@thread
                    }

                    val linkProperties =
                        connectivityManager
                            .getLinkProperties(
                                wifiNetwork
                            )

                    val candidates =
                        getNetworkCandidates(
                            linkProperties
                        )

                    if (
                        candidates.isEmpty()
                    ) {

                        finishError(
                            "بازه IP شبکه Wi-Fi پیدا نشد."
                        )

                        return@thread
                    }

                    scanExecutor =
                        Executors.newFixedThreadPool(
                            32
                        )

                    val scanFinished =
                        AtomicBoolean(false)

                    val timeoutRunnable =
                        Runnable {

                            if (
                                scanFinished.compareAndSet(
                                    false,
                                    true
                                )
                            ) {

                                try {
                                    scanExecutor?.shutdownNow()
                                } catch (_: Exception) {
                                }

                                finishError(
                                    "CRM در mDNS و جستجوی مستقیم شبکه پیدا نشد.\n\n" +
                                        "گوشی و لپ‌تاپ باید روی یک شبکه محلی باشند."
                                )
                            }
                        }

                    mainHandler.postDelayed(
                        timeoutRunnable,
                        SCAN_TIMEOUT_MS
                    )

                    for (
                        candidate in candidates
                    ) {

                        if (
                            finished.get() ||
                            scanFinished.get()
                        ) {
                            break
                        }

                        val executor =
                            scanExecutor
                                ?: break

                        executor.execute {

                            if (
                                finished.get() ||
                                scanFinished.get()
                            ) {
                                return@execute
                            }

                            if (
                                isCrmServer(
                                    candidate,
                                    SERVER_PORT
                                )
                            ) {

                                if (
                                    scanFinished.compareAndSet(
                                        false,
                                        true
                                    )
                                ) {

                                    mainHandler
                                        .removeCallbacks(
                                            timeoutRunnable
                                        )

                                    try {
                                        executor.shutdownNow()
                                    } catch (_: Exception) {
                                    }

                                    finishSuccess(
                                        "http://$candidate:$SERVER_PORT"
                                    )
                                }
                            }
                        }
                    }

                } catch (e: Exception) {

                    finishError(
                        "خطا در جستجوی شبکه:\n" +
                            (
                                e.message
                                    ?: "خطای نامشخص"
                            )
                    )

                } finally {

                    try {
                        scanExecutor?.shutdown()
                    } catch (_: Exception) {
                    }
                }
            }
        }

        discoveryListener =
            object :
                NsdManager.DiscoveryListener {

                override fun onDiscoveryStarted(
                    serviceType: String
                ) {

                    mainHandler.postDelayed(
                        {

                            if (
                                !finished.get()
                            ) {

                                stopNsd()

                                scanNetwork()
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

                    val serviceName =
                        serviceInfo.serviceName
                            ?: ""

                    val serviceType =
                        serviceInfo.serviceType
                            ?: ""

                    if (
                        !serviceType.contains(
                            "_crm._tcp",
                            ignoreCase = true
                        )
                    ) {
                        return
                    }

                    if (
                        !serviceName.contains(
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

                    if (
                        !finished.get()
                    ) {

                        stopNsd()

                        scanNetwork()
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

                val wifiNetwork =
                    getWifiNetwork(
                        connectivityManager
                    )

                if (
                    wifiNetwork == null
                ) {

                    scanNetwork()

                    return
                }

                nsdManager.discoverServices(
                    SERVICE_TYPE,
                    NsdManager.PROTOCOL_DNS_SD,
                    wifiNetwork,
                    nsdExecutor,
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

        } catch (_: SecurityException) {

            stopNsd()

            scanNetwork()

        } catch (_: Exception) {

            stopNsd()

            scanNetwork()
        }
    }

    private fun getWifiNetwork(
        connectivityManager: ConnectivityManager
    ): Network? {

        try {

            val activeNetwork =
                connectivityManager.activeNetwork

            if (
                activeNetwork != null
            ) {

                val capabilities =
                    connectivityManager
                        .getNetworkCapabilities(
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
                network in
                connectivityManager.allNetworks
            ) {

                val capabilities =
                    connectivityManager
                        .getNetworkCapabilities(
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

    private fun getNetworkCandidates(
        linkProperties: LinkProperties?
    ): List<String> {

        if (
            linkProperties == null
        ) {
            return emptyList()
        }

        val result =
            LinkedHashSet<String>()

        for (
            linkAddress in
            linkProperties.linkAddresses
        ) {

            val address =
                linkAddress.address

            if (
                address !is Inet4Address
            ) {
                continue
            }

            val prefix =
                linkAddress.prefixLength

            if (
                prefix < 16 ||
                prefix > 30
            ) {
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
                -1 shl (32 - prefix)

            val networkAddress =
                ip and mask

            val hostCount =
                1L shl (32 - prefix)

            val maxHosts =
                minOf(
                    hostCount - 2,
                    4094L
                )

            for (
                offset in 1L..maxHosts
            ) {

                val candidate =
                    networkAddress.toLong() +
                        offset

                val a =
                    (candidate shr 24) and 0xFF

                val b =
                    (candidate shr 16) and 0xFF

                val c =
                    (candidate shr 8) and 0xFF

                val d =
                    candidate and 0xFF

                val ipAddress =
                    "$a.$b.$c.$d"

                if (
                    ipAddress !=
                    address.hostAddress
                ) {

                    result.add(
                        ipAddress
                    )
                }
            }
        }

        return result.toList()
    }

    private fun isCrmServer(
        host: String,
        port: Int
    ): Boolean {

        var socket: Socket? = null
        var connection:
            HttpURLConnection? = null

        try {

            socket =
                Socket()

            socket.connect(
                InetSocketAddress(
                    host,
                    port
                ),
                CONNECT_TIMEOUT_MS
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

            connection.connectTimeout =
                HTTP_TIMEOUT_MS

            connection.readTimeout =
                HTTP_TIMEOUT_MS

            connection.requestMethod =
                "GET"

            connection.instanceFollowRedirects =
                false

            connection.setRequestProperty(
                "Connection",
                "close"
            )

            val responseCode =
                connection.responseCode

            /*
             * هر پاسخ HTTP یعنی یک HTTP server
             * روی پورت 5001 وجود دارد.
             *
             * CRM Flask نیز در همین حالت پاسخ می‌دهد.
             */
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
