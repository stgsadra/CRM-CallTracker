package com.crm.calltracker

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.os.Looper

object ServerDiscovery {

    private const val SERVICE_TYPE = "_crm._tcp."
    private const val FALLBACK_SERVER_URL = "http://192.168.100.2:5001"
    private const val DISCOVERY_TIMEOUT_MS = 5000L

    private var finished = false

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
            if (!finished) {
                finished = true

                try {
                    nsdManager.stopServiceDiscovery(discoveryListener)
                } catch (_: Exception) {
                }

                ApiConfig.SERVER_URL = FALLBACK_SERVER_URL
                onFound(FALLBACK_SERVER_URL)
            }
        }

        discoveryListener = object : NsdManager.DiscoveryListener {

            override fun onDiscoveryStarted(serviceType: String) {
                handler.postDelayed(timeoutRunnable, DISCOVERY_TIMEOUT_MS)
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {

                if (
                    serviceInfo.serviceType == SERVICE_TYPE ||
                    serviceInfo.serviceName.startsWith("CRM-Server")
                ) {
                    try {
                        nsdManager.resolveService(
                            serviceInfo,
                            object : NsdManager.ResolveListener {

                                override fun onServiceResolved(
                                    resolvedInfo: NsdServiceInfo
                                ) {
                                    if (finished) return

                                    val host = resolvedInfo.host
                                    val port = resolvedInfo.port
                                    val address = host?.hostAddress

                                    if (
                                        address.isNullOrEmpty() ||
                                        port <= 0
                                    ) {
                                        return
                                    }

                                    finished = true
                                    handler.removeCallbacks(timeoutRunnable)

                                    try {
                                        nsdManager.stopServiceDiscovery(
                                            discoveryListener
                                        )
                                    } catch (_: Exception) {
                                    }

                                    val serverUrl =
                                        "http://$address:$port"

                                    ApiConfig.SERVER_URL = serverUrl
                                    onFound(serverUrl)
                                }

                                override fun onResolveFailed(
                                    serviceInfo: NsdServiceInfo,
                                    errorCode: Int
                                ) {
                                    // اگر resolve نشد، timeout بعداً fallback می‌کند
                                }
                            }
                        )
                    } catch (_: Exception) {
                        // timeout بعداً fallback می‌کند
                    }
                }
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
            }

            override fun onDiscoveryStopped(serviceType: String) {
            }

            override fun onStartDiscoveryFailed(
                serviceType: String,
                errorCode: Int
            ) {
                if (!finished) {
                    finished = true

                    try {
                        nsdManager.stopServiceDiscovery(
                            discoveryListener
                        )
                    } catch (_: Exception) {
                    }

                    ApiConfig.SERVER_URL = FALLBACK_SERVER_URL
                    onFound(FALLBACK_SERVER_URL)
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
                discoveryListener
            )
        } catch (e: Exception) {
            if (!finished) {
                finished = true
                ApiConfig.SERVER_URL = FALLBACK_SERVER_URL
                onFound(FALLBACK_SERVER_URL)
            }
        }
    }

    private lateinit var discoveryListener: NsdManager.DiscoveryListener
}
