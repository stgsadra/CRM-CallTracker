package com.crm.calltracker

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkAddress
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetAddress
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

object ServerDiscovery {

    private const val CRM_PORT = 5001

    private const val CONNECT_TIMEOUT = 700
    private const val READ_TIMEOUT = 700

    private const val MAX_HOSTS = 254

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

        val appContext = context.applicationContext

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
                    connectivityManager
                        .getNetworkCapabilities(network)

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
                    connectivityManager
                        .getLinkProperties(network)

                if (linkProperties == null) {

                    showResult(
                        onError,
                        "اطلاعات IP شبکه Wi-Fi دریافت نشد."
                    )

                    return@thread
                }

                val ipv4 =
                    findIpv4Address(
                        linkProperties
                    )

                if (ipv4 == null) {

                    showResult(
                        onError,
                        "IP نسخه 4 گوشی پیدا نشد."
                    )

                    return@thread
                }

                val prefixLength =
                    ipv4.networkPrefixLength

                val localAddress =
                    ipv4.address

                if (prefixLength < 16 || prefixLength > 30) {

                    showResult(
                        onError,
                        "Subnet شبکه پشتیبانی نمی‌شود.\n\n" +
                            "IP گوشی: ${localAddress.hostAddress}\n" +
                            "Prefix: $prefixLength"
                    )

                    return@thread
                }

                val subnetInfo =
                    calculateSubnet(
                        localAddress,
                        prefixLength
                    )

                if (subnetInfo == null) {

                    showResult(
                        onError,
                        "محاسبه Subnet شبکه انجام نشد."
                    )

                    return@thread
                }

                val networkPrefix =
                    subnetInfo.networkPrefix

                val firstHost =
                    subnetInfo.firstHost

                val lastHost =
                    subnetInfo.lastHost

                showResult(
                    null,
                    null,
                    "شروع جستجوی CRM...\n" +
                        "IP گوشی: ${localAddress.hostAddress}\n" +
                        "Prefix: $prefixLength\n" +
                        "Subnet: $networkPrefix.0/24"
                )

                scanNetwork(
                    context = appContext,
                    network = network,
                    firstHost = firstHost,
                    lastHost = lastHost,
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
    ): LinkAddress? {

        for (linkAddress in linkProperties.linkAddresses) {

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

    private data class SubnetInfo(
        val networkPrefix: String,
        val firstHost: Int,
        val lastHost: Int
    )

    private fun calculateSubnet(
        address: InetAddress,
        prefixLength: Int
    ): SubnetInfo? {

        val ipv4 =
            address as? Inet4Address
                ?: return null

        val bytes =
            ipv4.address

        val ip =
            (
                (bytes[0].toInt() and 0xff) shl 24
                ) or
                (
                    (bytes[1].toInt() and 0xff) shl 16
                    ) or
                (
                    (bytes[2].toInt() and 0xff) shl 8
                    ) or
                (
                    bytes[3].toInt() and 0xff
                    )

        val mask =
            if (prefixLength == 32) {
                -1
            } else {
                (-1 shl (32 - prefixLength))
            }

        val networkAddress =
            ip and mask

        val broadcastAddress =
            networkAddress or mask.inv()

        val firstHost =
            networkAddress + 1

        val lastHost =
            broadcastAddress - 1

        if (
            firstHost >= lastHost
        ) {
            return null
        }

        val prefix =
            listOf(
                (networkAddress ushr 24) and 0xff,
                (networkAddress ushr 16) and 0xff,
                (networkAddress ushr 8) and 0xff
            ).joinToString(".")

        return SubnetInfo(
            networkPrefix = prefix,
            firstHost = firstHost,
            lastHost = lastHost
        )
    }

    private fun scanNetwork(
        context: Context,
        network: Network,
        firstHost: Int,
        lastHost: Int,
        onFound: (String) -> Unit,
        onError: (String) -> Unit
    ) {

        val found =
            AtomicBoolean(false)

        val executor =
            Executors.newFixedThreadPool(24)

        val totalHosts =
            lastHost - firstHost + 1

        var submitted =
            0

        for (hostNumber in firstHost..lastHost) {

            if (submitted >= MAX_HOSTS) {
                break
            }

            submitted++

            executor.execute {

                if (found.get()) {
                    return@execute
                }

                val ip =
                    intToIp(hostNumber)

                val serverUrl =
                    "http://$ip:$CRM_PORT"

                if (
                    isCrmServer(
                        network,
                        serverUrl
                    )
                ) {

                    if (
                        found.compareAndSet(
                            false,
                            true
                        )
                    {

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
                        "تعداد IPهای بررسی‌شده: $submitted\n" +
                        "پورت بررسی‌شده: $CRM_PORT\n\n" +
                        "گوشی و لپ‌تاپ باید به همان شبکه Wi-Fi متصل باشند."
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

    private fun intToIp(
        value: Int
    ): String {

        return listOf(
            (value ushr 24) and 0xff,
            (value ushr 16) and 0xff,
            (value ushr 8) and 0xff,
            value and 0xff
        ).joinToString(".")
    }

    private fun showResult(
        callback: ((String) -> Unit)?,
        message: String?
    ) {

        if (
            callback == null ||
            message == null
        ) {
            return
        }

        Handler(
            Looper.getMainLooper()
        ).post {
            callback(message)
        }
    }
}
