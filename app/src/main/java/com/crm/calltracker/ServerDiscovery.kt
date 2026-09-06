package com.crm.calltracker

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

object ServerDiscovery {

    private const val SERVICE_TYPE = "_crm._tcp."
    private const val SERVICE_NAME_PREFIX = "CRM-Server"

    private const val DISCOVERY_TIMEOUT_MS = 10000L
    private const val CONNECTION_TIMEOUT_MS = 400L
    private const val CRM_PORT = 5001

    private var finished = false
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    private val executor = Executors.newFixedThreadPool(32)

    fun findServer(
        context: Context,
        onFound: (String) -> Unit,
        onError: (String) -> Unit
    ) {

        finished = false

        val appContext = context.applicationContext

        val nsdManager =
            appContext.getSystemService(Context.NSD_SERVICE) as NsdManager

        val wifiManager =
            appContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

        val handler = Handler(Looper.getMainLooper())

        val finishedFlag = AtomicBoolean(false)

        fun finishSuccess(url: String) {

            if (!finishedFlag.compareAndSet(false, true)) {
                return
            }

            finished = true

            handler.removeCallbacksAndMessages(null)

            try {
                discoveryListener?.let {
                    nsdManager.stopServiceDiscovery(it)
                }
            } catch (_: Exception) {
            }

            discoveryListener = null

            try {
                multicastLock?.let {
                    if (it.isHeld) {
                        it.release()
                    }
                }
            } catch (_: Exception) {
            }

            multicastLock = null

            ApiConfig.SERVER_URL = url

            handler.post {
                onFound(url)
            }
        }

        fun finishError(message: String) {

            if (!finishedFlag.compareAndSet(false, true)) {
                return
            }

            finished = true

            handler.removeCallbacksAndMessages(null)

            try {
                discoveryListener?.let {
                    nsdManager.stopServiceDiscovery(it)
                }
            } catch (_: Exception) {
            }

            discoveryListener = null

            try {
                multicastLock?.let {
                    if (it.isHeld) {
                        it.release()
                    }
                }
            } catch (_: Exception) {
            }

            multicastLock = null

            handler.post {
                onError(message)
            }
        }

        // ---------------------------------------------------------
        // اجازه دریافت multicast برای mDNS
        // ---------------------------------------------------------

        try {

            multicastLock =
                wifiManager.createMulticastLock(
                    "CRM_CallTracker_mDNS"
                )

            multicastLock?.setReferenceCounted(false)

            multicastLock?.acquire()

        } catch (_: Exception) {
        }

        // ---------------------------------------------------------
        // مرحله اول: mDNS
        // ---------------------------------------------------------

        val timeoutRunnable = Runnable {

            if (finishedFlag.get()) {
                return@Runnable
            }

            // mDNS جواب نداد.
            // حالا شبکه محلی را به صورت خودکار اسکن می‌کنیم.
            startNetworkScan(
                appContext,
                handler,
                finishSuccess,
                finishError
            )
        }

        discoveryListener =
            object : NsdManager.DiscoveryListener {

                override fun onDiscoveryStarted(
                    serviceType: String
                ) {

                    handler.postDelayed(
                        timeoutRunnable,
                        DISCOVERY_TIMEOUT_MS
                    )
                }

                override fun onServiceFound(
                    serviceInfo: NsdServiceInfo
                ) {

                    if (finishedFlag.get()) {
                        return
                    }

                    val serviceName =
                        serviceInfo.serviceName ?: ""

                    val serviceType =
                        serviceInfo.serviceType ?: ""

                    val typeMatches =
                        serviceType
                            .trimEnd('.')
                            .equals(
                                SERVICE_TYPE.trimEnd('.'),
                                ignoreCase = true
                            )

                    val nameMatches =
                        serviceName.startsWith(
                            SERVICE_NAME_PREFIX,
                            ignoreCase = true
                        )

                    if (!typeMatches && !nameMatches) {
                        return
                    }

                    try {

                        nsdManager.resolveService(
                            serviceInfo,
                            object : NsdManager.ResolveListener {

                                override fun onServiceResolved(
                                    resolvedInfo: NsdServiceInfo
                                ) {

                                    if (finishedFlag.get()) {
                                        return
                                    }

                                    val address =
                                        resolvedInfo.host?.hostAddress

                                    val port =
                                        resolvedInfo.port

                                    if (
                                        address.isNullOrEmpty() ||
                                        port <= 0
                                    ) {
                                        return
                                    }

                                    finishSuccess(
                                        "http://$address:$port"
                                    )
                                }

                                override fun onResolveFailed(
                                    serviceInfo: NsdServiceInfo,
                                    errorCode: Int
                                ) {
                                    // ادامه Discovery
                                }
                            }
                        )

                    } catch (_: Exception) {
                    }
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

                    if (finishedFlag.get()) {
                        return
                    }

                    // mDNS روی این دستگاه شروع نشد.
                    // مستقیماً سراغ Scan شبکه می‌رویم.
                    handler.post {
                        startNetworkScan(
                            appContext,
                            handler,
                            finishSuccess,
                            finishError
                        )
                    }
                }

                override fun onStopDiscoveryFailed(
                    serviceType: String,
                    errorCode: Int
                ) {
                }
            }

        try {

            nsdManager.discoverServices(
                SERVICE_TYPE,
                NsdManager.PROTOCOL_DNS_SD,
                discoveryListener!!
            )

        } catch (_: Exception) {

            startNetworkScan(
                appContext,
                handler,
                finishSuccess,
                finishError
            )
        }
    }

    // =============================================================
    // پیدا کردن شبکه فعلی و اسکن خودکار پورت 5001
    // =============================================================

    private fun startNetworkScan(
        context: Context,
        handler: Handler,
        onFound: (String) -> Unit,
        onError: (String) -> Unit
    ) {

        val connectivityManager =
            context.getSystemService(
                Context.CONNECTIVITY_SERVICE
            ) as ConnectivityManager

        val network: Network =
            connectivityManager.activeNetwork
                ?: run {
                    handler.post {
                        onError("شبکه Wi-Fi پیدا نشد")
                    }
                    return
                }

        val linkProperties: LinkProperties =
            connectivityManager.getLinkProperties(network)
                ?: run {
                    handler.post {
                        onError("اطلاعات شبکه Wi-Fi پیدا نشد")
                    }
                    return
                }

        val localIp =
            linkProperties.linkAddresses
                .mapNotNull { it.address }
                .filterIsInstance<Inet4Address>()
                .firstOrNull {
                    !it.isLoopbackAddress &&
                    !it.isLinkLocalAddress
                }

        if (localIp == null) {

            handler.post {
                onError("آدرس شبکه گوشی پیدا نشد")
            }

            return
        }

        val addressBytes =
            localIp.address

        val subnetPrefix =
            "${addressBytes[0].toInt() and 0xFF}." +
            "${addressBytes[1].toInt() and 0xFF}." +
            "${addressBytes[2].toInt() and 0xFF}."

        val scanFinished =
            AtomicBoolean(false)

        // ---------------------------------------------------------
        // اسکن همزمان آدرس‌های شبکه
        // ---------------------------------------------------------

        for (i in 1..254) {

            if (scanFinished.get()) {
                break
            }

            val candidate =
                "$subnetPrefix$i"

            executor.execute {

                if (scanFinished.get()) {
                    return@execute
                }

                try {

                    Socket().use { socket ->

                        socket.connect(
                            InetSocketAddress(
                                candidate,
                                CRM_PORT
                            ),
                            CONNECTION_TIMEOUT_MS
                        )

                        if (!scanFinished.compareAndSet(
                                false,
                                true
                            )
                        ) {
                            return@use
                        }

                        val url =
                            "http://$candidate:$CRM_PORT"

                        ApiConfig.SERVER_URL = url

                        handler.post {
                            onFound(url)
                        }
                    }

                } catch (_: Exception) {
                    // این IP سرور CRM نیست.
                }
            }
        }

        // اگر هیچ دستگاهی جواب نداد
        handler.postDelayed({

            if (!scanFinished.get()) {

                scanFinished.set(true)

                handler.post {
                    onError(
                        "سرور CRM در شبکه پیدا نشد"
                    )
                }
            }

        }, 15000L)
    }
}

