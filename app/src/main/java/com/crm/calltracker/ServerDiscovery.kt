package com.crm.calltracker

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

object ServerDiscovery {

    private const val CRM_PORT = 5001

    private const val CONNECT_TIMEOUT = 700
    private const val READ_TIMEOUT = 700

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

                val network =
                    connectivityManager.activeNetwork

                if (network == null) {
                    showResult(
                        onError,
                        "هیچ شبکه فعالی در گوشی پیدا نشد."
                    )
                    return@thread
                }

                val capabilities =
                    connectivityManager.getNetworkCapabilities(
                        network
                    )

                if (capabilities == null) {
                    showResult(
                        onError,
                        "اطلاعات شبکه فعال گوشی دریافت نشد."
                    )
                    return@thread
                }

                val isWifi =
                    capabilities.hasTransport(
                        NetworkCapabilities.TRANSPORT_WIFI
                    )

                if (!isWifi) {
                    showResult(
                        onError,
                        "گوشی به Wi-Fi متصل نیست."
                    )
                    return@thread
                }

                val linkProperties =
                    connectivityManager.getLinkProperties(
                        network
                    )

                if (linkProperties == null) {
                    showResult(
                        onError,
                        "اطلاعات IP شبکه Wi-Fi دریافت نشد."
                    )
                    return@thread
                }

                val ipv4Address =
                    findIpv4Address(
                        linkProperties
                    )

                if (ipv4Address == null) {
                    showResult(
                        onError,
                        "IP نسخه 4 گوشی پیدا نشد."
                    )
                    return@thread
                }

                val address =
                    ipv4Address.address

                val ip =
                    address.hostAddress

                if (ip.isNullOrEmpty()) {
                    showResult(
                        onError,
                        "IP گوشی قابل تشخیص نیست."
                    )
                    return@thread
                }

                val prefixLength =
                    ipv4Address.prefixLength

                if (
                    prefixLength < 16 ||
                    prefixLength > 30
                ) {
                    showResult(
                        onError,
                        "Subnet شبکه پشتیبانی نمی‌شود.\n\n" +
                            "IP گوشی: $ip\n" +
                            "Prefix: $prefixLength"
                    )
                    return@thread
                }

                val ipParts =
                    ip.split(".")

                if (ipParts.size != 4) {
                    showResult(
                        onError,
                        "IP گوشی معتبر نیست:\n$ip"
                    )
                    return@thread
                }

                val first =
                    ipParts[0].toIntOrNull()

                val second =
                    ipParts[1].toIntOrNull()

                val third =
                    ipParts[2].toIntOrNull()

                if (
                    first == null ||
                    second == null ||
                    third == null
                ) {
                    showResult(
                        onError,
                        "ساختار IP گوشی معتبر نیست:\n$ip"
                    )
                    return@thread
                }

                val subnetPrefix =
                    "$first.$second.$third"

                scanNetwork(
                    network = network,
                    subnetPrefix = subnetPrefix,
                    firstHost = 1,
                    lastHost = 254,
                    onFound = onFound,
                    onError = onError
                )

            } catch (e: Exception) {
                showResult(
                    onError,
                    "خطا در تشخیص شبکه:\n\n" +
                        (
                            e.message
                                ?: e.javaClass.simpleName
                        )
                )
            }
        }
    }

    private fun findIpv4Address(
        linkProperties: LinkProperties
    ): android.net.LinkAddress? {

        for (
            linkAddress
            in linkProperties.linkAddresses
        ) {
            val address =
                linkAddress.address

            if (
                address is Inet4Address &&
                !address.isLoopbackAddress &&
                !address.isLinkLocalAddress
            ) {
                return linkAddress
            }
        }

        return null
    }

    private fun scanNetwork(
        network: Network,
        subnetPrefix: String,
        firstHost: Int,
        lastHost: Int,
        onFound: (String) -> Unit,
        onError: (String) -> Unit
    ) {

        val found =
            AtomicBoolean(false)

        val executor =
            Executors.newFixedThreadPool(24)

        for (
            host
            in firstHost..lastHost
        ) {

            executor.execute {

                if (found.get()) {
                    return@execute
                }

                val serverUrl =
                    "http://$subnetPrefix.$host:$CRM_PORT"

                val isServer =
                    isCrmServer(
                        network,
                        serverUrl
                    )

                if (
                    isServer &&
                    found.compareAndSet(
                        false,
                        true
                    )
                ) {

                    ApiConfig.SERVER_URL =
                        serverUrl

                    executor.shutdownNow()

                    showResult(
                        onFound,
                        serverUrl
                    )
                }
            }
        }

        thread {

            try {
                executor.shutdown()

                var waited =
                    0

                while (
                    !executor.isTerminated &&
                    !found.get() &&
                    waited < 20000
                ) {

                    Thread.sleep(200)

                    waited += 200
                }

            } catch (_: Exception) {
            }

            if (!found.get()) {

                executor.shutdownNow()

                showResult(
                    onError,
                    "CRM در شبکه محلی پیدا نشد.\n\n" +
                        "Subnet: $subnetPrefix.x\n" +
                        "Port: $CRM_PORT\n\n" +
                        "گوشی و لپ‌تاپ باید روی همان Wi-Fi باشند."
                )
            }
        }
    }

    private fun isCrmServer(
        network: Network,
        serverUrl: String
    ): Boolean {

        var connection:
            HttpURLConnection? = null

        return try {

            val url =
                URL("$serverUrl/")

            connection =
                network.openConnection(
                    url
                ) as HttpURLConnection

            connection.requestMethod =
                "GET"

            connection.connectTimeout =
                CONNECT_TIMEOUT

            connection.readTimeout =
                READ_TIMEOUT

            connection.useCaches =
                false

            connection.instanceFollowRedirects =
                false

            connection.setRequestProperty(
                "Connection",
                "close"
            )

            val responseCode =
                connection.responseCode

            responseCode in 100..599

        } catch (_: Exception) {

            false

        } finally {

            try {
                connection?.disconnect()
            } catch (_: Exception) {
            }
        }
    }

    private fun showResult(
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
