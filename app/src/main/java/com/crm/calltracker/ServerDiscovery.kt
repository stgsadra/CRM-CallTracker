package com.crm.calltracker

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper

object ServerDiscovery {

    private const val SERVICE_TYPE = "_crm._tcp."
    private const val SERVICE_NAME = "CRM-Server"
    private const val TIMEOUT_MS = 15000L

    fun findServer(
        context: Context,
        onFound: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        val appContext = context.applicationContext
        val nsdManager =
            appContext.getSystemService(Context.NSD_SERVICE) as NsdManager

        val wifiManager =
            appContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

        val handler = Handler(Looper.getMainLooper())

        var finished = false
        var discoveryListener: NsdManager.DiscoveryListener? = null
        var multicastLock: WifiManager.MulticastLock? = null

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

        fun fail(message: String) {
            if (finished) return

            finished = true
            cleanup()

            handler.post {
                onError(message)
            }
        }

        fun success(url: String) {
            if (finished) return

            finished = true
            cleanup()

            ApiConfig.SERVER_URL = url

            handler.post {
                onFound(url)
            }
        }

        try {
            multicastLock =
                wifiManager.createMulticastLock("CRM_CallTracker")

            multicastLock?.setReferenceCounted(false)
            multicastLock?.acquire()
        } catch (_: Exception) {
        }

        val timeoutRunnable = Runnable {
            fail("mDNS timeout: سرویس پیدا یا Resolve نشد")
        }

        discoveryListener =
            object : NsdManager.DiscoveryListener {

                override fun onDiscoveryStarted(serviceType: String) {
                    handler.postDelayed(
                        timeoutRunnable,
                        TIMEOUT_MS
                    )
                }

                override fun onServiceFound(
                    serviceInfo: NsdServiceInfo
                ) {
                    if (finished) return

                    val name =
                        serviceInfo.serviceName ?: ""

                    val type =
                        serviceInfo.serviceType ?: ""

                    val correctName =
                        name.startsWith(
                            SERVICE_NAME,
                            ignoreCase = true
                        )

                    val correctType =
                        type.trimEnd('.').equals(
                            SERVICE_TYPE.trimEnd('.'),
                            ignoreCase = true
                        )

                    if (!correctName || !correctType) {
                        return
                    }

                    try {
                        nsdManager.resolveService(
                            serviceInfo,
                            object : NsdManager.ResolveListener {

                                override fun onServiceResolved(
                                    resolvedInfo: NsdServiceInfo
                                ) {
                                    if (finished) return

                                    val host =
                                        resolvedInfo.host?.hostAddress

                                    val port =
                                        resolvedInfo.port

                                    if (host.isNullOrBlank()) {
                                        fail(
                                            "mDNS Resolve شد ولی IP خالی است"
                                        )
                                        return
                                    }

                                    if (port <= 0) {
                                        fail(
                                            "mDNS Resolve شد ولی Port نامعتبر است"
                                        )
                                        return
                                    }

                                    success(
                                        "http://$host:$port"
                                    )
                                }

                                override fun onResolveFailed(
                                    serviceInfo: NsdServiceInfo,
                                    errorCode: Int
                                ) {
                                    fail(
                                        "mDNS سرویس پیدا شد ولی Resolve شکست خورد. کد خطا: $errorCode"
                                    )
                                }
                            }
                        )
                    } catch (e: Exception) {
                        fail(
                            "خطا در Resolve: ${
                                e.message ?: "unknown"
                            }"
                        )
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
                    fail(
                        "شروع mDNS شکست خورد. کد خطا: $errorCode"
                    )
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
        } catch (e: Exception) {
            fail(
                "خطا در شروع mDNS: ${
                    e.message ?: "unknown"
                }"
            )
        }
    }
}
