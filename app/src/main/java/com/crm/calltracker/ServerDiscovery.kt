package com.crm.calltracker

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper

object ServerDiscovery {

    private const val SERVICE_TYPE = "_crm._tcp."
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
        var listener: NsdManager.DiscoveryListener? = null
        var multicastLock: WifiManager.MulticastLock? = null

        fun cleanup() {
            try {
                listener?.let {
                    nsdManager.stopServiceDiscovery(it)
                }
            } catch (_: Exception) {
            }

            listener = null

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
            if (finished) return

            finished = true
            cleanup()

            ApiConfig.SERVER_URL = url

            handler.post {
                onFound(url)
            }
        }

        fun failure(message: String) {
            if (finished) return

            finished = true
            cleanup()

            handler.post {
                onError(message)
            }
        }

        try {
            multicastLock =
                wifiManager.createMulticastLock("CRM_CallTracker")

            multicastLock?.setReferenceCounted(false)
            multicastLock?.acquire()

        } catch (e: Exception) {

            failure(
                "MulticastLock خطا داد: ${e.message}"
            )
            return
        }

        val timeout = Runnable {
            failure(
                "Android هیچ سرویس mDNS با نوع _crm._tcp پیدا نکرد"
            )
        }

        listener = object : NsdManager.DiscoveryListener {

            override fun onDiscoveryStarted(
                serviceType: String
            ) {

                handler.postDelayed(
                    timeout,
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

                /*
                 * فعلاً اسم سرویس را بررسی نمی‌کنیم.
                 *
                 * فقط اگر Android هر سرویس _crm._tcp
                 * پیدا کند، آن را Resolve می‌کنیم.
                 */

                if (!type.contains(
                        "_crm._tcp",
                        ignoreCase = true
                    )
                ) {
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

                                    failure(
                                        "سرویس پیدا شد اما IP آن خالی است\n" +
                                        "نام: $name\n" +
                                        "نوع: $type"
                                    )

                                    return
                                }

                                if (port <= 0) {

                                    failure(
                                        "سرویس پیدا شد اما Port نامعتبر است: $port\n" +
                                        "نام: $name"
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

                                failure(
                                    "سرویس mDNS پیدا شد ولی Resolve نشد.\n" +
                                    "نام: ${serviceInfo.serviceName}\n" +
                                    "نوع: ${serviceInfo.serviceType}\n" +
                                    "کد خطا: $errorCode"
                                )
                            }
                        }
                    )

                } catch (e: Exception) {

                    failure(
                        "خطا در Resolve mDNS:\n${e.message}"
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

                failure(
                    "شروع جستجوی mDNS شکست خورد.\n" +
                    "نوع: $serviceType\n" +
                    "کد خطا: $errorCode"
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
                listener!!
            )

        } catch (e: Exception) {

            failure(
                "خطا در شروع mDNS:\n${e.message}"
            )
        }
    }
}
