package com.crm.calltracker

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.os.Looper

object ServerDiscovery {

    private const val SERVICE_TYPE = "_crm._tcp."
    private const val DISCOVERY_TIMEOUT_MS = 10000L

    private var finished = false

    private var discoveryListener:
        NsdManager.DiscoveryListener? = null

    fun findServer(
        context: Context,
        onFound: (String) -> Unit,
        onError: (String) -> Unit
    ) {

        finished = false

        val nsdManager =
            context.getSystemService(Context.NSD_SERVICE) as NsdManager

        val handler = Handler(Looper.getMainLooper())

        val timeoutRunnable = Runnable {

            if (!finished) {

                finished = true

                try {
                    discoveryListener?.let {
                        nsdManager.stopServiceDiscovery(it)
                    }
                } catch (_: Exception) {
                }

                discoveryListener = null

                handler.post {
                    onError(
                        "سرور CRM از طریق mDNS پیدا نشد"
                    )
                }
            }
        }

        discoveryListener =
            object : NsdManager.DiscoveryListener {

                override fun onDiscoveryStarted(
                    serviceType: String
                ) {

                    handler.postDelayed(
                        timeoutRunnable,
                        DISCOVERY_TIMEOUT_MS
                    )
                }

                override fun onServiceFound(
                    serviceInfo: NsdServiceInfo
                ) {

                    val serviceTypeMatches =
                        serviceInfo.serviceType
                            .trimEnd('.')
                            .equals(
                                SERVICE_TYPE.trimEnd('.'),
                                ignoreCase = true
                            )

                    val serviceNameMatches =
                        serviceInfo.serviceName
                            .startsWith(
                                "CRM-Server",
                                ignoreCase = true
                            )

                    if (
                        !serviceTypeMatches &&
                        !serviceNameMatches
                    ) {
                        return
                    }

                    try {

                        nsdManager.resolveService(
                            serviceInfo,
                            object : NsdManager.ResolveListener {

                                override fun onServiceResolved(
                                    resolvedInfo:
