package com.crm.calltracker

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.os.Looper

object ServerDiscovery {

    private const val SERVICE_TYPE = "_crm._tcp."
    private const val DISCOVERY_TIMEOUT_MS = 10000L

    private var finished = false
    private var discoveryListener: NsdManager.DiscoveryListener? = null

    fun findServer(
        context: Context,
        onFound: (String) -> Unit,
        onError: (String) -> Unit
    ) {

        finished = false

        val nsdManager =
            context.getSystemService(Context.NSD_SERVICE) as NsdManager

        val handler = Handler(Looper.getMainLooper())

        val timeoutRunnable = Runnable {

            if (finished) return@Runnable

            finished = true

            try {
                discoveryListener?.let {
                    nsdManager.stopServiceDiscovery(it)
                }
            } catch (_: Exception) {
            }

            discoveryListener = null

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

                    val typeMatches =
                        serviceInfo.serviceType
                            .trimEnd('.')
                            .equals(
                                SERVICE_TYPE.trimEnd('.'),
                                ignoreCase = true
                            )

                    val nameMatches =
                        serviceInfo.serviceName
                            .startsWith(
                                "CRM-Server",
                                ignoreCase = true
                            )

                    if (!typeMatches && !nameMatches) {
                        return
                    }

                    if (finished) {
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

                                    try {
                                        discoveryListener?.let {
                                            nsdManager.stopServiceDiscovery(it)
                                        }
                                    } catch (_: Exception) {
                                    }

                                    discoveryListener = null

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
                                    // Discovery ادامه پیدا می‌کند.
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

                    try {
                        discoveryListener?.let {
                            nsdManager.stopServiceDiscovery(it)
                        }
                    } catch (_: Exception) {
                    }

                    discoveryListener = null

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
                discoveryListener = null

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
