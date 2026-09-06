package com.crm.calltracker

import android.content.Context
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
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
    private const val SCAN_TIMEOUT_MS = 20000L
    private const val CONNECTION_TIMEOUT_MS = 500

    private const val CRM_PORT = 5001

    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    private val executor = Executors.newFixedThreadPool(32)

    fun findServer(
        context: Context,
        onFound: (String) -> Unit,
        onError: (String) -> Unit
    ) {

        val appContext = context.applicationContext

        val nsdManager =
            appContext.getSystemService(
                Context.NSD_SERVICE
            ) as NsdManager

        val wifiManager =
            appContext.getSystemService(
                Context.WIFI_SERVICE
            ) as WifiManager

        val handler =
            Handler(Looper.getMainLooper())

        val finished =
            AtomicBoolean(false)

        fun cleanup() {

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
        }

        fun success(url: String) {

            if (!finished.compareAndSet(false, true)) {
                return
            }

            cleanup()

            ApiConfig.SERVER_URL = url

            handler.post {
                onFound(url)
            }
        }

        fun failure(message: String) {

            if (!finished.compareAndSet(false, true)) {
                return
            }

            cleanup()

            handler.post {
                onError(message)
            }
        }

        // ---------------------------------------------------------
        // فعال کردن multicast برای mDNS
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
        // اگر mDNS جواب نداد، Scan شبکه
        // ---------------------------------------------------------

        val scanRunnable = Runnable {

            if (finished.get()) {
                return@Runnable
            }

            scanLocalNetwork(
                appContext,
                handler,
                finished,
                ::success,
                ::failure
            )
        }

        // ---------------------------------------------------------
        // Timeout مربوط به mDNS
        // ---------------------------------------------------------

        val mdnsTimeout = Runnable {

            if (finished.get()) {
                return@Runnable
            }

            scanLocalNetwork(
                appContext,
                handler,
                finished,
                ::success,
                ::failure
            )
        }

        // ---------------------------------------------------------
        // mDNS Discovery
        // ---------------------------------------------------------

        discoveryListener =
            object : NsdManager.DiscoveryListener {

                override fun onDiscoveryStarted(
                    serviceType: String
                ) {

                    handler.postDelayed(
                        mdnsTimeout,
                        DISCOVERY_TIMEOUT_MS
                    )
                }

                override fun onServiceFound(
                    serviceInfo: NsdServiceInfo
                ) {

                    if (finished.get()) {
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
                            object :
                                NsdManager.ResolveListener {

                                override fun onServiceResolved(
                                    resolvedInfo: NsdServiceInfo
                                ) {

                                    if (finished.get()) {
                                        return
                                    }

                                    val address =
                                        resolvedInfo.host
                                            ?.hostAddress

                                    val port =
                                        resolvedInfo.port

                                    if (
                                        address.isNullOrEmpty() ||
                                        port <= 0
                                    ) {
                                        return
                                    }

                                    success(
                                        "http://$address:$port"
                                    )
                                }

                                override fun onResolveFailed(
                                    serviceInfo: NsdServiceInfo,
                                    errorCode: Int
                                ) {
                                    // ادامه جستجو
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

                    if (finished.get()) {
                        return
                    }

                    handler.post {
                        scanLocalNetwork(
                            appContext,
                            handler,
                            finished,
                            ::success,
                            ::failure
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

            scanLocalNetwork(
                appContext,
                handler,
                finished,
                ::success,
                ::failure
            )
        }
    }

    // =============================================================
    // Scan خودکار شبکه محلی
    // =============================================================

    private fun scanLocalNetwork(
        context: Context,
        handler: Handler,
        finished: AtomicBoolean,
        onFound: (String) -> Unit,
        onError: (String) -> Unit
    ) {

        if (finished.get()) {
            return
        }

        val connectivityManager =
            context.getSystemService(
                Context.CONNECTIVITY_SERVICE
            ) as ConnectivityManager

        val network =
            connectivityManager.activeNetwork

        if (network == null) {

            handler.post {
                onError(
                    "شبکه Wi-Fi فعال پیدا نشد"
                )
            }

            return
        }

        val linkProperties =
            connectivityManager.getLinkProperties(network)

        if (linkProperties == null) {

            handler.post {
                onError(
                    "اطلاعات شبکه Wi-Fi پیدا نشد"
                )
            }

            return
        }

        val localAddress =
            linkProperties.linkAddresses
                .mapNotNull {
                    it.address
                }
                .filterIsInstance<Inet4Address>()
                .firstOrNull {
                    !it.isLoopbackAddress &&
                    !it.isLinkLocalAddress
                }

        if (localAddress == null) {

            handler.post {
                onError(
                    "آدرس IP گوشی پیدا نشد"
                )
            }

            return
        }

        val bytes =
            localAddress.address

        val prefix =
            "${bytes[0].toInt() and 255}." +
            "${bytes[1].toInt() and 255}." +
            "${bytes[2].toInt() and 255}."

        val scanFinished =
            AtomicBoolean(false)

        for (i in 1..254) {

            if (
                finished.get() ||
                scanFinished.get()
            ) {
                break
            }

            val candidate =
                "$prefix$i"

            executor.execute {

                if (
                    finished.get() ||
                    scanFinished.get()
                ) {
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

                        if (
                            !scanFinished.compareAndSet(
                                false,
                                true
                            )
                        ) {
                            return@use
                        }

                        handler.post {

                            onFound(
                                "http://$candidate:$CRM_PORT"
                            )
                        }
                    }

                } catch (_: Exception) {
                    // این IP سرور نیست.
                }
            }
        }

        handler.postDelayed(
            {

                if (
                    !finished.get() &&
                    !scanFinished.get()
                ) {

                    scanFinished.set(true)

                    onError(
                        "سرور CRM در شبکه پیدا نشد"
                    )
                }

            },
            SCAN_TIMEOUT_MS
        )
    }
}

