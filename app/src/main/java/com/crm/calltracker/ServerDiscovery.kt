package com.crm.calltracker

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

object ServerDiscovery {

    private const val TEST_SERVER_URL =
        "http://192.168.100.4:5001"

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

        thread {

            var connection: HttpURLConnection? = null

            try {

                val url =
                    URL(TEST_SERVER_URL)

                connection =
                    url.openConnection()
                        as HttpURLConnection

                connection.requestMethod = "GET"

                connection.connectTimeout = 5000
                connection.readTimeout = 5000

                connection.setRequestProperty(
                    "Connection",
                    "close"
                )

                val responseCode =
                    connection.responseCode

                val responseText =
                    try {
                        connection.inputStream
                            .bufferedReader()
                            .use {
                                it.readText()
                            }
                    } catch (_: Exception) {
                        ""
                    }

                connection.disconnect()
                connection = null

                Handler(
                    Looper.getMainLooper()
                ).post {

                    if (
                        responseCode in 200..599
                    ) {

                        ApiConfig.SERVER_URL =
                            TEST_SERVER_URL

                        onFound(
                            TEST_SERVER_URL
                        )

                    } else {

                        onError(
                            "اتصال به CRM برقرار شد، " +
                                "اما پاسخ HTTP نامعتبر بود.\n\n" +
                                "HTTP: $responseCode\n\n" +
                                responseText.take(300)
                        )
                    }
                }

            } catch (e: Exception) {

                try {
                    connection?.disconnect()
                } catch (_: Exception) {
                }

                val errorMessage =
                    e.message
                        ?: e.javaClass.simpleName

                Handler(
                    Looper.getMainLooper()
                ).post {

                    onError(
                        "اتصال مستقیم به CRM شکست خورد.\n\n" +
                            "آدرس:\n" +
                            "$TEST_SERVER_URL\n\n" +
                            "خطا:\n" +
                            errorMessage
                    )
                }
            }
        }
    }
}
