package com.crm.calltracker

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat

object ServerDiscovery {

    private const val SERVICE_TYPE = "_crm._tcp."
    private const val TIMEOUT_MS = 15000L

    fun hasNearbyWifiPermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.NEARBY_WIFI_DEVICES
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    fun findServer(
        context: Context,
        onFound: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        if (!hasNearbyWifiPermission(context)) {
            onError(
                "مجوز «دستگاه‌های نزدیک» برای mDNS داده نشده است."
            )
            return
        }

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

            ApiConfig.SERVER_URL =
                url.trimEnd('/')

            handler.post {
                onFound(url.trimEnd('/'))
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
                wifiManager.createMulticastLock(
                    "CRM_CallTracker_mDNS"
                )

            multicastLock?.setReferenceCounted(false)

            if (multicastLock?.isHeld != true) {
                multicastLock?.acquire()
            }

        } catch (e: Exception) {

            failure(
                "فعال‌سازی MulticastLock ناموفق بود:\n${e.message}"
            )

            return
        }

        val timeoutRunnable =
            Runnable {

                failure(
                    "Android در مدت ۱۵ ثانیه هیچ سرویس " +
                    "_crm._tcp پیدا نکرد."
                )
            }

        listener =
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

                    if (finished) return

                    val name =
                        serviceInfo.serviceName ?: ""

                    val type =
                        serviceInfo.serviceType ?: ""

                    if (!type.contains(
                            "_crm._tcp",
                            ignoreCase = true
                        )
                    ) {
                        return
                    }

                    if (!name.contains(
                            "CRM-Server",
                            ignoreCase = true
                        )
                    ) {
                        return
                    }

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {

                        try {

                            nsdManager.resolveService(
                                serviceInfo,
                                object :
                                    NsdManager.ResolveListener {

                                    override fun onServiceResolved(
                                        resolvedInfo: NsdServiceInfo
                                    ) {

                                        if (finished) return

                                        val host =
                                            resolvedInfo.host
                                                ?.hostAddress

                                        val port =
                                            resolvedInfo.port

                                        if (
                                            host.isNullOrBlank()
                                        ) {

                                            failure(
                                                "CRM پیدا شد ولی IP آن دریافت نشد."
                                            )

                                            return
                                        }

                                        if (port <= 0) {

                                            failure(
                                                "CRM پیدا شد ولی Port نامعتبر است: $port"
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
                                            "CRM توسط mDNS پیدا شد، " +
                                            "اما Resolve شکست خورد.\n" +
                                            "کد خطا: $errorCode"
                                        )
                                    }
                                }
                            )

                        } catch (e: Exception) {

                            failure(
                                "خطا در Resolve سرویس CRM:\n${e.message}"
                            )
                        }

                    } else {

                        try {

                            @Suppress("DEPRECATION")
                            nsdManager.resolveService(
                                serviceInfo,
                                object :
                                    NsdManager.ResolveListener {

                                    override fun onServiceResolved(
                                        resolvedInfo: NsdServiceInfo
                                    ) {

                                        if (finished) return

                                        val host =
                                            resolvedInfo.host
                                                ?.hostAddress

                                        val port =
                                            resolvedInfo.port

                                        if (
                                            host.isNullOrBlank()
                                        ) {

                                            failure(
                                                "CRM پیدا شد ولی IP آن دریافت نشد."
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
                                            "Resolve سرویس CRM شکست خورد.\n" +
                                            "کد خطا: $errorCode"
                                        )
                                    }
                                }
                            )

                        } catch (e: Exception) {

                            failure(
                                "خطا در Resolve سرویس CRM:\n${e.message}"
                            )
                        }
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
                        "شروع mDNS شکست خورد.\n" +
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

        } catch (e: SecurityException) {

            failure(
                "Android اجازه mDNS نداد.\n" +
                "لطفاً مجوز «دستگاه‌های نزدیک» را فعال کنید.\n\n" +
                e.message
            )

        } catch (e: Exception) {

            failure(
                "خطا در شروع mDNS:\n${e.message}"
            )
        }
    }
}
