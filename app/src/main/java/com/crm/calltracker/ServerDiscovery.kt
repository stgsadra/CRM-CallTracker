package com.crm.calltracker

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.os.Looper

object ServerDiscovery {

    private const val SERVICE_TYPE = "_crm._tcp."
    private const val SERVICE_NAME = "CRM-Server"
    private const val DISCOVERY_TIMEOUT_MS = 10000L

    private var finished = false
    private var discoveryListener: NsdManager.DiscoveryListener? = null

    fun findServer(
        context: Context,
        onFound: (String) -> Unit,
        onError: (String) -> Unit
    ) {

        finished = false

        val nsdManager =
            context.getSystemService(Context.NSD_SERVICE) as NsdManager

        val handler = Handler(Looper.getMainLooper())

        fun finishDiscovery(serverUrl: String) {

            if (finished) return

            finished = true

            try {
                discoveryListener?.let {
                    nsdManager.stopServiceDiscovery(it)
                }
            } catch (_: Exception) {
            }

            discoveryListener = null

            ApiConfig.SERVER_URL = serverUrl

            handler.post {
                onFound(serverUrl)
            }
        }

        val timeoutRunnable = Runnable {

            if (!finished) {
                finished = true

                try {
                    discovery
