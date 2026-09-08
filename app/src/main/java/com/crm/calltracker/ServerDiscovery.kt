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
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

object ServerDiscovery {

    private const val SERVICE_TYPE = "_crm._tcp."
    private const val DEFAULT_PORT = 5001

    private const val MDNS_TIMEOUT = 8000L
    private const val SCAN_TIMEOUT = 15000L

    private const val HTTP_CONNECT_TIMEOUT = 800
    private const val HTTP_READ_TIMEOUT = 1000

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
            Handler(Looper.getMainLooper())

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

        val finished =
            AtomicBoolean(false)

        val nsdExecutor =
            Executors.newSingleThreadExecutor()

        var discoveryListener:
            NsdManager.DiscoveryListener? = null

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

            try {
                discoveryListener?.let {
                    val nsdManager =
                        appContext.getSystemService(
                            Context.NSD_SERVICE
                        ) as NsdManager

                    nsdManager.stopServiceDiscovery(it)
                }
            } catch (_: Exception) {
            }

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

        fun startNetworkScan() {

            if (finished.get()) {
                return
            }

            thread {

                try {

                    val wifiNetwork =
                        getWifiNetwork(
                            connectivityManager
                        )

                    if (
                        wifiNetwork == null
                    ) {

                        if (
                            finished.compareAndSet(
                                false,
                                true
                            )
                        ) {

                            mainHandler.post {
                                onError(
                                    "شبکه Wi-Fi فعال پیدا نشد."
                                )
                            }
                        }

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

                        if (
                            finished.compareAndSet(
                                false,
                                true
                            )
                        ) {

                            mainHandler.post {
                                onError(
                                    "IP شبکه Wi-Fi پیدا نشد."
                                )
                            }
                        }

                        return@thread
                    }

                    val executor =
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
                            {

                                try {
                                    executor.shutdownNow()
                                } catch (_: Exception) {
                                }

                                if (
                                    finished.compareAndSet(
                                        false,
                                        true
                                    )
                                ) {

                                    mainHandler.post {
                                        onError(
                                            "mDNS و جستجوی مستقیم شبکه نتوانستند CRM را پیدا کنند.\n\n" +
                                                "گوشی و لپ‌تاپ باید روی یک شبکه محلی باشند."
                                        )
                                    }
                                }
                            }
                        }

                    mainHandler.postDelayed(
                        timeoutRunnable,
                        SCAN_TIMEOUT
                    )

                    for (
                        host in candidates
                    ) {

                        if (
                            finished.get() ||
                            scanFinished.get()
                        ) {
                            break
                        }

                        executor.execute {

                            if (
                                finished.get() ||
                                scanFinished.get()
                            ) {
                                return@execute
                            }

                            if (
                                isCrmServer(
                                    wifiNetwork,
                                    host,
                                    DEFAULT_PORT
                                )
                            ) {

                                if (
                                    scanFinished.compareAndSet(
                                        false,
                                        true
                                    )
                                {

                                    mainHandler.removeCallbacks(
                                        timeoutRunnable
                                    )

                                    try {
                                        executor.shutdownNow()
                                    } catch (_: Exception) {
                                    }

                                    finishSuccess(
                                        "http://$host:$DEFAULT_PORT"
                                    )
                                }
                            }
                        }
                    }

                    executor.shutdown()

                } catch (e: Exception) {

                    if (
                        finished.compareAndSet(
                            false,
                            true
                        )
                    ) {

                        mainHandler.post {
                            onError(
                                "خطا در جستجوی شبکه:\n\n" +
                                    (
                                        e.message
                                            ?: e.javaClass.simpleName
                                    )
                            )
                        }
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

                    val nsdManager =
                        appContext.getSystemService(
                            Context.NSD_SERVICE
                        ) as NsdManager

                    nsdManager.resolveService(
                        serviceInfo,
                        nsdExecutor,
                        object :
                            NsdManager.ResolveListener {

                            override fun onServiceResolved(
                                info: NsdServiceInfo
                            ) {

                                if (
                                    finished.get()
                                ) {
                                    return
                                }

                                val host =
                                    info.host
                                        ?.hostAddress

                                val port =
                                    if (
                                        info.port > 0
                                    ) {
                                        info.port
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
                                info: NsdServiceInfo,
                                errorCode: Int
                            ) {
                            }
                        }
                    )

                } else {

                    @Suppress("DEPRECATION")
                    val nsdManager =
                        appContext.getSystemService(
                            Context.NSD_SERVICE
                        ) as NsdManager

                    @Suppress("DEPRECATION")
                    nsdManager.resolveService(
                        serviceInfo,
                        object :
                            NsdManager.ResolveListener {

                            override fun onServiceResolved(
                                info: NsdServiceInfo
                            ) {

                                if (
                                    finished.get()
                                ) {
                                    return
                                }

                                val host =
                                    info.host
                                        ?.hostAddress

                                val port =
                                    if (
                                        info.port > 0
                                    ) {
                                        info.port
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
                                info: NsdServiceInfo,
                                errorCode: Int
                            ) {
                            }
                        }
                    )
                }

            } catch (_: Exception) {
            }
        }

        val nsdManager =
            appContext.getSystemService(
                Context.NSD_SERVICE
            ) as NsdManager

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

                                try {
                                    nsdManager.stopServiceDiscovery(
                                        this
                                    )
                                } catch (_: Exception) {
                                }

                                startNetworkScan()
                            }

                        },
                        MDNS_TIMEOUT
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

                    val type =
                        serviceInfo.serviceType
                            ?: ""

                    if (
                        !type.contains(
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

                    try {
                        nsdManager.stopServiceDiscovery(
                            this
                        )
                    } catch (_: Exception) {
                    }

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
                HTTP_CONNECT_TIMEOUT

            connection.readTimeout =
                HTTP_READ_TIMEOUT

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
}
