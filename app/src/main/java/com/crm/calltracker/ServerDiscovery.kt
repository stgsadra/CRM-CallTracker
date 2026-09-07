package com.crm.calltracker

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import java.util.concurrent.Executors

object ServerDiscovery {

    private const val SERVICE_TYPE = "_crm._tcp."
    private const val TIMEOUT_MS = 15000L

    fun hasNearbyWifiPermission(context: Context): Boolean {

        return if (
            Build.VERSION.SDK_INT >=
            Build.VERSION_CODES.TIRAMISU
        ) {

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

        val appContext =
            context.applicationContext

        val nsdManager =
            appContext.getSystemService(
                Context.NSD_SERVICE
            ) as NsdManager

        val connectivityManager =
            appContext.getSystemService(
                Context.CONNECTIVITY_SERVICE
            ) as ConnectivityManager

        val handler =
            Handler(
                Looper.getMainLooper()
            )

        val executor =
            Executors.newSingleThreadExecutor()

        var finished = false

        var listener:
            NsdManager.DiscoveryListener? = null

        fun cleanup() {

            try {

                listener?.let {

                    nsdManager.stopServiceDiscovery(
                        it
                    )
                }

            } catch (_: Exception) {
            }

            listener = null

            try {
                executor.shutdownNow()
            } catch (_: Exception) {
            }
        }

        fun success(
            url: String
        ) {

            if (finished) {
                return
            }

            finished = true

            cleanup()

            val finalUrl =
                url.trimEnd('/')

            ApiConfig.SERVER_URL =
                finalUrl

            handler.post {

                onFound(
                    finalUrl
                )
            }
        }

        fun failure(
            message: String
        ) {

            if (finished) {
                return
            }

            finished = true

            cleanup()

            handler.post {

                onError(
                    message
                )
            }
        }

        val timeoutRunnable =
            Runnable {

                failure(
                    "Android در مدت ۱۵ ثانیه هیچ سرویس " +
                    "_crm._tcp پیدا نکرد.\n\n" +
                    "بررسی شد: Wi-Fi و مجوز دستگاه‌های نزدیک."
                )
            }

        listener =
            object :
                NsdManager.DiscoveryListener {

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

                    if (finished) {
                        return
                    }

                    val name =
                        serviceInfo.serviceName
                            ?: ""

                    val type =
                        serviceInfo.serviceType
                            ?: ""

                    if (
                        !type.contains(
                            "_crm._tcp",
                            ignoreCase = true
                        )
                    ) {
                        return
                    }

                    if (
                        !name.contains(
                            "CRM-Server",
                            ignoreCase = true
                        )
                    ) {
                        return
                    }

                    resolveService(
                        nsdManager =
                            nsdManager,
                        serviceInfo =
                            serviceInfo,
                        executor =
                            executor,
                        onSuccess =
                            { host, port ->

                                if (
                                    host.isNullOrBlank()
                                ) {

                                    failure(
                                        "CRM پیدا شد ولی IP آن دریافت نشد."
                                    )

                                    return@resolveService
                                }

                                if (port <= 0) {

                                    failure(
                                        "CRM پیدا شد ولی Port نامعتبر است: $port"
                                    )

                                    return@resolveService
                                }

                                success(
                                    "http://$host:$port"
                                )
                            },
                        onError =
                            { message ->

                                failure(
                                    message
                                )
                            }
                    )
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

            /*
             * Android 13 / API 33 به بعد:
             *
             * Discovery را روی Network فعال Wi-Fi
             * انجام می‌دهیم، نه روی همه مسیرهای شبکه.
             */
            if (
                Build.VERSION.SDK_INT >=
                Build.VERSION_CODES.TIRAMISU
            ) {

                val network =
                    getActiveNetwork(
                        connectivityManager
                    )

                nsdManager.discoverServices(
                    SERVICE_TYPE,
                    NsdManager.PROTOCOL_DNS_SD,
                    network,
                    executor,
                    listener!!
                )

            } else {

                @Suppress("DEPRECATION")

                nsdManager.discoverServices(
                    SERVICE_TYPE,
                    NsdManager.PROTOCOL_DNS_SD,
                    listener!!
                )
            }

        } catch (e: SecurityException) {

            failure(
                "Android اجازه دسترسی به شبکه محلی برای mDNS را نداد.\n\n" +
                (e.message ?: "")
            )

        } catch (e: Exception) {

            failure(
                "خطا در شروع mDNS:\n" +
                (e.message ?: "خطای نامشخص")
            )
        }
    }

    private fun getActiveNetwork(
        connectivityManager: ConnectivityManager
    ): Network? {

        return try {

            connectivityManager.activeNetwork

        } catch (_: Exception) {

            null
        }
    }

    private fun resolveService(
        nsdManager: NsdManager,
        serviceInfo: NsdServiceInfo,
        executor: java.util.concurrent.Executor,
        onSuccess: (String?, Int) -> Unit,
        onError: (String) -> Unit
    ) {

        try {

            if (
                Build.VERSION.SDK_INT >=
                Build.VERSION_CODES.TIRAMISU
            ) {

                nsdManager.resolveService(
                    serviceInfo,
                    executor,
                    object :
                        NsdManager.ResolveListener {

                        override fun onServiceResolved(
                            resolvedInfo:
                                NsdServiceInfo
                        ) {

                            val host =
                                resolvedInfo.host
                                    ?.hostAddress

                            val port =
                                resolvedInfo.port

                            onSuccess(
                                host,
                                port
                            )
                        }

                        override fun onResolveFailed(
                            serviceInfo:
                                NsdServiceInfo,
                            errorCode: Int
                        ) {

                            onError(
                                "CRM توسط mDNS پیدا شد، " +
                                "اما Resolve شکست خورد.\n" +
                                "کد خطا: $errorCode"
                            )
                        }
                    }
                )

            } else {

                @Suppress("DEPRECATION")

                nsdManager.resolveService(
                    serviceInfo,
                    object :
                        NsdManager.ResolveListener {

                        override fun onServiceResolved(
                            resolvedInfo:
                                NsdServiceInfo
                        ) {

                            val host =
                                resolvedInfo.host
                                    ?.hostAddress

                            val port =
                                resolvedInfo.port

                            onSuccess(
                                host,
                                port
                            )
                        }

                        override fun onResolveFailed(
                            serviceInfo:
                                NsdServiceInfo,
                            errorCode: Int
                        ) {

                            onError(
                                "Resolve سرویس CRM شکست خورد.\n" +
                                "کد خطا: $errorCode"
                            )
                        }
                    }
                )
            }

        } catch (e: SecurityException) {

            onError(
                "Android اجازه Resolve سرویس mDNS را نداد.\n" +
                (e.message ?: "")
            )

        } catch (e: Exception) {

            onError(
                "خطا در Resolve سرویس CRM:\n" +
                (e.message ?: "خطای نامشخص")
            )
        }
    }
}
