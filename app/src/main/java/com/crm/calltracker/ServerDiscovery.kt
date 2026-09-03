```kotlin
package com.crm.calltracker

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo

object ServerDiscovery {

    private const val SERVICE_TYPE = "_crm._tcp."
    private const val SERVICE_NAME = "CRM-Server"

    fun findServer(
        context: Context,
        onFound: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        val nsdManager =
            context.getSystemService(Context.NSD_SERVICE) as NsdManager

        val listener = object : NsdManager.DiscoveryListener {

            override fun onDiscoveryStarted(serviceType: String) {
                // Discovery started
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {

                val correctService =
                    serviceInfo.serviceName == SERVICE_NAME ||
                    serviceInfo.serviceName.startsWith(SERVICE_NAME) ||
                    serviceInfo.serviceType == SERVICE_TYPE

                if (!correctService) {
                    return
                }

                nsdManager.resolveService(
                    serviceInfo,
                    object : NsdManager.ResolveListener {

                        override fun onServiceResolved(
                            resolvedInfo: NsdServiceInfo
                        ) {
                            val host = resolvedInfo.host
                            val port = resolvedInfo.port

                            if (port <= 0) {
                                onError("پورت سرور CRM معتبر نیست")
                                return
                            }

                            val address = host?.hostAddress

                            if (address.isNullOrEmpty()) {
                                onError("IP سرور CRM پیدا نشد")
                                return
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
                            onError(
                                "سرور CRM پیدا شد ولی اتصال برقرار نشد: $errorCode"
                            )
                        }
                    }
                )
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                // Service lost
            }

            override fun onDiscoveryStopped(serviceType: String) {
                // Discovery stopped
            }

            override fun onStartDiscoveryFailed(
                serviceType: String,
                errorCode: Int
            ) {
                try {
                    nsdManager.stopServiceDiscovery(this)
                } catch (_: Exception) {
                }

                onError(
                    "جستجوی سرور شروع نشد: $errorCode"
                )
            }

            override fun onStopDiscoveryFailed(
                serviceType: String,
                errorCode: Int
            ) {
                // Ignore
            }
        }

        try {
            nsdManager.discoverServices(
                SERVICE_TYPE,
                NsdManager.PROTOCOL_DNS_SD,
                listener
            )
        } catch (e: Exception) {
            onError(
                e.message ?: "خطا در جستجوی سرور CRM"
            )
        }
    }
}
```
