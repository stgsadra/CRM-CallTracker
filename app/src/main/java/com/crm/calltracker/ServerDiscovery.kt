package com.crm.calltracker

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo

object ServerDiscovery {

    private const val SERVICE_TYPE = "_crm._tcp."

    fun findServer(
        context: Context,
        onFound: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        val nsdManager =
            context.getSystemService(Context.NSD_SERVICE) as NsdManager

        val listener = object : NsdManager.DiscoveryListener {

            override fun onDiscoveryStarted(serviceType: String) {
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {

                if (serviceInfo.serviceType == SERVICE_TYPE ||
                    serviceInfo.serviceName.startsWith("CRM-Server")
                ) {

                    nsdManager.resolveService(
                        serviceInfo,
                        object : NsdManager.ResolveListener {

                            override fun onServiceResolved(
                                resolvedInfo: NsdServiceInfo
                            ) {
                                val host = resolvedInfo.host
                                val port = resolvedInfo.port

                                val serverUrl =
                                    "http://${host.hostAddress}:$port"

                                ApiConfig.SERVER_URL = serverUrl

                                onFound(serverUrl)
                            }

                            override fun onResolveFailed(
                                serviceInfo: NsdServiceInfo,
                                errorCode: Int
                            ) {
                                onError("سرور پیدا شد ولی اتصال برقرار نشد")
                            }
                        }
                    )
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
                nsdManager.stopServiceDiscovery(this)
                onError("جستجوی سرور شروع نشد")
            }

            override fun onStopDiscoveryFailed(
                serviceType: String,
                errorCode: Int
            ) {
            }
        }

        nsdManager.discoverServices(
            SERVICE_TYPE,
            NsdManager.PROTOCOL_DNS_SD,
            listener
        )
    }
}
