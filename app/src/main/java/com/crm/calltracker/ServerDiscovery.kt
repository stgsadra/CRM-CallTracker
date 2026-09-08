package com.crm.calltracker

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.LinkProperties
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.URL
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

object ServerDiscovery {

    private const val SERVICE_TYPE = "_crm._tcp."
    private const val DEFAULT_PORT = 5001

    private const val MDNS_TIMEOUT_MS = 8000L
    private const val SCAN_TIMEOUT_MS = 15000L

    private const val HTTP_CONNECT_TIMEOUT_MS = 800
    private const val HTTP_READ_TIMEOUT_MS = 1000

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

        val mainHandler =
            Handler(
                Looper.getMainLooper()
            )

        if (
            Build.VERSION.SDK_INT >=
            Build.VERSION_CODES.TIRAMISU &&
            !hasNearbyWifiPermission(appContext)
        ) {

            onError(
                "مجوز دستگاه‌های نزدیک داده نشده است."
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

        val finished =
            AtomicBoolean(false)

        val nsdExecutor =
            Executors.newSingleThreadExecutor()

        var discoveryListener:
            NsdManager.DiscoveryListener? = null

        var mdnsTimeoutRunnable:
            Runnable? = null

        fun stopMdns() {

            try {

                val listener =
                    discoveryListener

                if (listener != null) {
                    nsdManager.stopServiceDiscovery(
                        listener
                    )
                }

            } catch (_: Exception) {
            }
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

            mdnsTimeoutRunnable?.let {
                mainHandler.removeCallbacks(it)
            }

            stopMdns()

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

            mdnsTimeoutRunnable?.let {
                mainHandler.removeCallbacks(it)
            }

            stopMdns()

            try {
                nsdExecutor.shutdownNow()
            } catch (_: Exception) {
            }

            mainHandler.post {
                onError(message)
            }
        }

        fun startNetworkScan() {

            if (finished.get()) {
                return
            }

            thread {

                var scanExecutor:
                    ExecutorService? = null

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
                        buildCandidates(
                            linkProperties
                        )

                    if (
                        candidates.isEmpty()
                    ) {

                        finishError(
                            "آدرس IPv4 شبکه Wi-Fi پیدا نشد."
                        )

                        return@thread
                    }

                    scanExecutor =
                        Executors.newFixedThreadPool(
                            32
                        )

                    val timeoutRunnable =
                        Runnable {

                            if (
                                !finished.get()
                            ) {

                                try {
                                    scanExecutor?.shutdownNow()
                                } catch (_: Exception) {
                                }

                                finishError(
                                    "mDNS و جستجوی مستقیم شبکه نتوانستند CRM را پیدا کنند.\n\n" +
                                        "گوشی و لپ‌تاپ باید روی یک شبکه محلی باشند."
                                )
                            }
                        }

                    mainHandler.postDelayed(
                        timeoutRunnable,
                        SCAN_TIMEOUT_MS
                    )

                    for (
                        host in candidates
                    ) {

                        if (
                            finished.get()
                        ) {
                            break
                        }

                        val executor =
                            scanExecutor
                                ?: break

                        executor.execute {

                            if (
                                finished.get()
                            ) {
                                return@execute
                            }

                            val isCrm =
                                isCrmServer(
                                    wifiNetwork,
                                    host,
                                    DEFAULT_PORT
                                )

                            if (
                                isCrm
                            ) {

                                finishSuccess(
                                    "http://$host:$DEFAULT_PORT"
                                )
                            }
                        }
                    }

                    executorShutdown(
                        scanExecutor
                    )

                } catch (e: Exception) {

                    finishError(
                        "خطا در جستجوی شبکه:\n\n" +
                            (
                                e.message
                                    ?: e.javaClass.simpleName
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

                                if (
                                    finished.get()
                                ) {
                                    return
                                }

                                val host =
                                    resolvedInfo.host
                                        ?.hostAddress

                                val port =
                                    if (
                                        resolvedInfo.port > 0
                                    ) {
                                        resolvedInfo.port
                                    } else {
                                        DEFAULT_PORT
                                    }

                                if (
                                    !host.isNullOrBlank()
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
                                // اسکن مستقیم شبکه بعد از timeout انجام می‌شود.
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

                                if (
                                    finished.get()
                                ) {
                                    return
                                }

                                val host =
                                    resolvedInfo.host
                                        ?.hostAddress

                                val port =
                                    if (
                                        resolvedInfo.port > 0
                                    ) {
                                        resolvedInfo.port
                                    } else {
                                        DEFAULT_PORT
                                    }

                                if (
                                    !host.isNullOrBlank()
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
                                // اسکن مستقیم شبکه بعد از timeout انجام می‌شود.
                            }
                        }
                    )
                }

            } catch (_: Exception) {
                // ادامه می‌دهیم تا fallback شبکه اجرا شود.
            }
        }

        discoveryListener =
            object :
                NsdManager.DiscoveryListener {

                override fun onDiscoveryStarted(
                    serviceType: String
                ) {

                    mdnsTimeoutRunnable =
                        Runnable {

                            if (
                                !finished.get()
                            ) {

                                stopMdns()
                                startNetworkScan()
                            }
                        }

                    mainHandler.postDelayed(
                        mdnsTimeoutRunnable!!,
                        MDNS_TIMEOUT_MS
                    )
                }

                override fun onServiceFound(
                    serviceInfo: NsdServiceInfo
                ) {

                    if (
                        finished.get()
                    ) {
                        return
                    }

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

                    stopMdns()
                    startNetworkScan()
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

                    startNetworkScan()

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

        } catch (_: Exception) {

            startNetworkScan()
        }
    }

    private fun getWifiNetwork(
        connectivityManager: ConnectivityManager
    ): Network? {

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

        return null
    }

    private fun buildCandidates(
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
                ((bytes[0].toInt() and 255) shl 24) or
                    ((bytes[1].toInt() and 255) shl 16) or
                    ((bytes[2].toInt() and 255) shl 8) or
                    (bytes[3].toInt() and 255)

            val mask =
                -1 shl (32 - prefix)

            val network =
                ip and mask

            val hostCount =
                1L shl (32 - prefix)

            val maxOffset =
                minOf(
                    hostCount - 2,
                    4094L
                )

            for (
                offset in 1L..maxOffset
            ) {

                val value =
                    network.toLong() +
                        offset

                val a =
                    (value shr 24) and 255

                val b =
                    (value shr 16) and 255

                val c =
                    (value shr 8) and 255

                val d =
                    value and 255

                val candidate =
                    "$a.$b.$c.$d"

                if (
                    candidate !=
                    address.hostAddress
                ) {

                    result.add(
                        candidate
                    )
                }
            }
        }

        return result.toList()
    }

    private fun isCrmServer(
        network: Network,
        host: String,
        port: Int
    ): Boolean {

        var connection:
            HttpURLConnection? = null

        try {

            val url =
                URL(
                    "http://$host:$port/"
                )

            connection =
                network.openConnection(
                    url
                ) as HttpURLConnection

            connection.requestMethod =
                "GET"

            connection.connectTimeout =
                HTTP_CONNECT_TIMEOUT_MS

            connection.readTimeout =
                HTTP_READ_TIMEOUT_MS

            connection.instanceFollowRedirects =
                false

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
                connection?.disconnect()
            } catch (_: Exception) {
            }
        }
    }

    private fun executorShutdown(
        executor: ExecutorService?
    ) {

        try {
            executor?.shutdown()
        } catch (_: Exception) {
        }
    }
}
