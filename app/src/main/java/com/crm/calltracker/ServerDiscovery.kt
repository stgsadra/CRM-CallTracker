package com.crm.calltracker

import android.content.Context
import android.net.wifi.WifiManager
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.os.Looper

object ServerDiscovery {

    private const val SERVICE_TYPE = "_crm._tcp."
    private const val SERVICE_NAME_PREFIX = "CRM-Server"
    private const val DISCOVERY_TIMEOUT_MS = 15000L

    private var finished = false
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    fun findServer(
        context: Context,
        onFound: (String) -> Unit,
        onError: (String) -> Unit
    ) {

        finished = false

        val nsdManager =
            context.getSystemService(Context.NSD_SERVICE) as NsdManager

        val wifiManager =
            context.applicationContext
                .getSystemService(Context.WIFI_SERVICE) as WifiManager

        val handler = Handler(Looper.getMainLooper())

        // برای دریافت بسته‌های multicast مربوط به mDNS
        try {
            multicastLock = wifiManager.createMulticastLock("CRM_CallTracker_mDNS")
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

        val timeoutRunnable = Runnable {

            if (finished) {
                return@Runnable
            }

            finished = true

            cleanup()

            handler.post {
                onError("سرور CRM از طریق mDNS پیدا نشد")
            }
        }

        discoveryListener =
            object : NsdManager.DiscoveryListener {

                override fun onDiscoveryStarted(serviceType: String) {

                    handler.postDelayed(
                        timeoutRunnable,
                        DISCOVERY_TIMEOUT_MS
                    )
                }

                override fun onServiceFound(
                    serviceInfo: NsdServiceInfo
                ) {

                    if (finished) {
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

                                    if (finished) {
                                        return
                                    }

                                    val host =
                                        resolvedInfo.host

                                    val port =
                                        resolvedInfo.port

                                    val address =
                                        host?.hostAddress

                                    if (
                                        address.isNullOrEmpty() ||
                                        port <= 0
                                    ) {
                                        return
                                    }

                                    finished = true

                                    handler.removeCallbacks(
                                        timeoutRunnable
                                    )

                                    cleanup()

                                    val serverUrl =
                                        "http://$address:$port"

                                    ApiConfig.SERVER_URL =
                                        serverUrl

                                    handler.post {
                                        onFound(serverUrl)
                                    }
                                }

                                override fun onResolveFailed(
                                    serviceInfo: NsdServiceInfo,
                                    errorCode: Int
                                ) {
                                    // اگر این سرویس resolve نشد،
                                    // سرویس‌های دیگر بررسی می‌شوند.
                                }
                            }
                        )

                    } catch (_: Exception) {
                        // Discovery ادامه پیدا می‌کند.
                    }
                }

                override fun onServiceLost(
                    serviceInfo: NsdServiceInfo
                ) {
                    // سرویس از شبکه خارج شد.
                }

                override fun onDiscoveryStopped(
                    serviceType: String
                ) {
                    // Discovery متوقف شد.
                }

                override fun onStartDiscoveryFailed(
                    serviceType: String,
                    errorCode: Int
                ) {

                    if (finished) {
                        return
                    }

                    finished = true

                    handler.removeCallbacks(
                        timeoutRunnable
                    )

                    cleanup()

                    handler.post {
                        onError(
                            "شروع جستجوی mDNS ناموفق بود. کد خطا: $errorCode"
                        )
                    }
                }

                override fun onStopDiscoveryFailed(
                    serviceType: String,
                    errorCode: Int
                ) {
                    // نیازی به اقدام دیگری نیست.
                }
            }

        try {

            nsdManager.discoverServices(
                SERVICE_TYPE,
                NsdManager.PROTOCOL_DNS_SD,
                discoveryListener!!
            )

        } catch (e: Exception) {

            if (!finished) {

                finished = true

                handler.removeCallbacks(
                    timeoutRunnable
                )

                cleanup()

                handler.post {
                    onError(
                        e.message
                            ?: "خطا در شروع mDNS"
                    )
                }
            }
        }
    }
}

