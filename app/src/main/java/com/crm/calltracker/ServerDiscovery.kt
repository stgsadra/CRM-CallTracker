package com.crm.calltracker

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import java.net.Inet4Address
import kotlin.concurrent.thread

object ServerDiscovery {

    fun hasNearbyWifiPermission(
        context: Context
    ): Boolean {
        return true
    }

    fun findServer(
        context: Context,
        onFound: (String) -> Unit,
        onError: (String) -> Unit
    ) {

        val appContext =
            context.applicationContext

        thread {

            try {

                val connectivityManager =
                    appContext.getSystemService(
                        Context.CONNECTIVITY_SERVICE
                    ) as ConnectivityManager

                val activeNetwork =
                    connectivityManager.activeNetwork

                val capabilities =
                    if (activeNetwork != null) {
                        connectivityManager
                            .getNetworkCapabilities(
                                activeNetwork
                            )
                    } else {
                        null
                    }

                val isWifi =
                    capabilities?.hasTransport(
                        NetworkCapabilities.TRANSPORT_WIFI
                    ) == true

                val linkProperties =
                    if (activeNetwork != null) {
                        connectivityManager
                            .getLinkProperties(
                                activeNetwork
                            )
                    } else {
                        null
                    }

                val wifiManager =
                    appContext.getSystemService(
                        Context.WIFI_SERVICE
                    ) as WifiManager

                @Suppress("DEPRECATION")
                val wifiInfo =
                    wifiManager.connectionInfo

                @Suppress("DEPRECATION")
                val wifiIp =
                    intToIp(
                        wifiInfo.ipAddress
                    )

                val linkInfo =
                    getLinkInfo(
                        linkProperties
                    )

                val message =
                    buildString {

                        appendLine(
                            "تشخیص شبکه گوشی"
                        )

                        appendLine()
                        appendLine(
                            "Wi-Fi: ${
                                if (isWifi) {
                                    "متصل"
                                } else {
                                    "متصل نیست"
                                }
                            }"
                        )

                        appendLine()
                        appendLine(
                            "Wi-Fi IP:"
                        )

                        appendLine(
                            wifiIp
                        )

                        appendLine()
                        appendLine(
                            "LinkProperties:"
                        )

                        appendLine(
                            linkInfo
                        )

                        appendLine()
                        appendLine(
                            "Active Network:"
                        )

                        appendLine(
                            activeNetwork?.toString()
                                ?: "null"
                        )

                        appendLine()
                        appendLine(
                            "Capabilities:"
                        )

                        appendLine(
                            capabilities?.toString()
                                ?: "null"
                        )

                        appendLine()
                        appendLine(
                            "CRM فعلی:"
                        )

                        appendLine(
                            "192.168.100.2:5001"
                        )
                    }

                Handler(
                    Looper.getMainLooper()
                ).post {

                    Toast.makeText(
                        appContext,
                        message,
                        Toast.LENGTH_LONG
                    ).show()

                    onError(message)
                }

            } catch (e: Exception) {

                val error =
                    "خطا در تشخیص شبکه:\n\n" +
                        (
                            e.message
                                ?: e.javaClass.simpleName
                        )

                Handler(
                    Looper.getMainLooper()
                ).post {
                    onError(error)
                }
            }
        }
    }

    private fun getLinkInfo(
        linkProperties: LinkProperties?
    ): String {

        if (linkProperties == null) {
            return "null"
        }

        return buildString {

            for (
                linkAddress in
                linkProperties.linkAddresses
            ) {

                val address =
                    linkAddress.address

                appendLine(
                    "IP: ${
                        address.hostAddress
                    }"
                )

                appendLine(
                    "Prefix: ${
                        linkAddress.prefixLength
                    }"
                )

                appendLine()
            }

            appendLine(
                "Routes:"
            )

            for (
                route in
                linkProperties.routes
            ) {

                appendLine(
                    route.toString()
                )
            }

            appendLine()
            appendLine(
                "DNS:"
            )

            for (
                dns in
                linkProperties.dnsServers
            ) {

                appendLine(
                    dns.hostAddress
                )
            }
        }
    }

    private fun intToIp(
        value: Int
    ): String {

        return "${value and 0xFF}." +
            "${value shr 8 and 0xFF}." +
            "${value shr 16 and 0xFF}." +
            "${value shr 24 and 0xFF}"
    }
}
