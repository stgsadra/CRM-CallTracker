package com.crm.calltracker

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import java.net.HttpURLConnection
import java.net.URL
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

            val connectivityManager =
                appContext.getSystemService(
                    Context.CONNECTIVITY_SERVICE
                ) as ConnectivityManager

            val network =
                connectivityManager.activeNetwork

            if (network == null) {

                showResult(
                    appContext,
                    onError,
                    "خطا: Active Network برابر null است."
                )

                return@thread
            }

            val capabilities =
                connectivityManager
                    .getNetworkCapabilities(network)

            val isWifi =
                capabilities?.hasTransport(
                    NetworkCapabilities.TRANSPORT_WIFI
                ) == true

            if (!isWifi) {

                showResult(
                    appContext,
                    onError,
                    "خطا: Active Network وای‌فای نیست.\n\n" +
                        capabilities
                )

                return@thread
            }

            testConnection(
                appContext,
                network,
                onFound,
                onError
            )
        }
    }

    private fun testConnection(
        context: Context,
        network: android.net.Network,
        onFound: (String) -> Unit,
        onError: (String) -> Unit
    ) {

        thread {

            var connection:
                HttpURLConnection? = null

            try {

                val url =
                    URL(
                        "http://192.168.100.2:5001/"
                    )

                connection =
                    network.openConnection(
                        url
                    ) as HttpURLConnection

                connection.requestMethod =
                    "GET"

                connection.connectTimeout =
                    5000

                connection.readTimeout =
                    5000

                connection.setRequestProperty(
                    "Connection",
                    "close"
                )

                val responseCode =
                    connection.responseCode

                val text =
                    try {

                        if (
                            responseCode in 200..399
                        ) {

                            connection.inputStream
                                .bufferedReader()
                                .use {
                                    it.readText()
                                }

                        } else {

                            connection.errorStream
                                ?.bufferedReader()
                                ?.use {
                                    it.readText()
                                }
                                ?: ""
                        }

                    } catch (_: Exception) {
                        ""
                    }

                connection.disconnect()
                connection = null

                if (
                    responseCode in 100..599
                ) {

                    ApiConfig.SERVER_URL =
                        "http://192.168.100.2:5001"

                    showResult(
                        context,
                        onFound,
                        "اتصال مستقیم از داخل Network اندروید موفق شد.\n\n" +
                            "HTTP: $responseCode\n\n" +
                            "CRM:\n" +
                            "http://192.168.100.2:5001\n\n" +
                            "پاسخ:\n" +
                            text.take(300)
                    )

                } else {

                    showResult(
                        context,
                        onError,
                        "پاسخ HTTP نامعتبر بود.\n\n" +
                            "HTTP: $responseCode"
                    )
                }

            } catch (e: Exception) {

                try {
                    connection?.disconnect()
                } catch (_: Exception) {
                }

                showResult(
                    context,
                    onError,
                    "اتصال داخل Network اندروید شکست خورد.\n\n" +
                        "آدرس:\n" +
                        "http://192.168.100.2:5001\n\n" +
                        "خطا:\n" +
                        (
                            e.message
                                ?: e.javaClass.simpleName
                        )
                )
            }
        }
    }

    private fun showResult(
        context: Context,
        callback: (String) -> Unit,
        message: String
    ) {

        Handler(
            Looper.getMainLooper()
        ).post {

            callback(message)
        }
    }
}
