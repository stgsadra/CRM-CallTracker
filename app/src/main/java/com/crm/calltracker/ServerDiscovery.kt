package com.crm.calltracker

import android.content.Context
import android.net.wifi.WifiManager
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.os.Looper

object ServerDiscovery {

    private const val SERVICE_TYPE = "_crm._tcp."
    private const val TIMEOUT_MS = 15000L

    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private var isFinished = false

    fun findServer(
        context: Context,
        onFound: (String) -> Unit,
        onError: (String) -> Unit
    ) {

        isFinished = false

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

        // ---------------------------------------------------------
        // فعال کردن Multicast برای دریافت mDNS
        // ---------------------------------------------------------

        try {

            multicastLock =
                wifiManager.createMulticastLock(
                    "CRM_CallTracker"
                )

            multicastLock?.setReferenceCounted(false)

            multicastLock?.acquire()

        } catch (_: Exception) {
        }

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

        fun found(url: String) {

            if (isFinished) {
                return
            }

            isFinished = true

            cleanup()

            ApiConfig.SERVER_URL = url

            handler.post {
                onFound(url)
            }
        }

        fun failed(message: String) {

            if (isFinished) {
                return
            }

            isFinished = true

            cleanup()

            handler.post {
                onError(message)
            }
        }

        // ---------------------------------------------------------
        // Timeout
        // ---------------------------------------------------------

        val timeoutRunnable = Runnable {

            if (isFinished) {
                return@Runnable
            }

            failed(
                "سرور CRM از طریق mDNS پیدا نشد"
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
                        timeoutRunnable,
                        TIMEOUT_MS
                    )
                }

                override fun onServiceFound(
                    serviceInfo: NsdServiceInfo
                ) {

                    if (isFinished) {
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
                            "CRM-Server",
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

                                    if (isFinished) {
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

                                    found(
                                        "http://$address:$port"
                                    )
                                }

                                override fun onResolveFailed(
                                    serviceInfo: NsdServiceInfo,
                                    errorCode: Int
                                ) {
                                    // Discovery ادامه پیدا می‌کند.
                                }
                            }
                        )

                    } catch (_: Exception) {
                        // خطای resolve باعث Crash نمی‌شود.
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

                    if (isFinished) {
                        return
                    }

                    failed(
                        "شروع mDNS ناموفق بود. کد خطا: $errorCode"
                    )
                }

                override fun onStopDiscoveryFailed(
                    serviceType: String,
                    errorCode: Int
                ) {
                }
            }

        // ---------------------------------------------------------
        // شروع Discovery
        // ---------------------------------------------------------

        try {

            nsdManager.discoverServices(
                SERVICE_TYPE,
                NsdManager.PROTOCOL_DNS_SD,
                discoveryListener!!
            )

        } catch (e: Exception) {

            failed(
                e.message
                    ?: "خطا در شروع mDNS"
            )
        }
    }
}

