package com.crm.calltracker

import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.concurrent.thread

object CallApi {

    fun startCall(
        serverUrl: String,
        token: String,
        customerId: Int,
        onSuccess: (CallInfo) -> Unit,
        onError: (String) -> Unit
    ) {
        thread {
            try {
                val url = URL(
                    "${serverUrl.trimEnd('/')}/api/mobile/call/start/$customerId"
                )

                val connection =
                    url.openConnection() as HttpURLConnection

                connection.requestMethod = "POST"
                connection.connectTimeout = 10000
                connection.readTimeout = 10000
                connection.doOutput = true

                connection.setRequestProperty(
                    "Authorization",
                    "Bearer $token"
                )

                connection.setRequestProperty(
                    "Content-Type",
                    "application/json"
                )

                connection.outputStream.use {
                    it.write("{}".toByteArray())
                }

                val responseCode = connection.responseCode

                val response = if (responseCode in 200..299) {
                    connection.inputStream.bufferedReader().use {
                        it.readText()
                    }
                } else {
                    connection.errorStream?.bufferedReader()?.use {
                        it.readText()
                    } ?: "HTTP $responseCode"
                }

                connection.disconnect()

                if (responseCode !in 200..299) {
                    onError(response)
                    return@thread
                }

                val phone =
                    extractJsonString(response, "phone")

                val callId =
                    extractJsonInt(response, "call_id")

                val communicationId =
                    extractJsonInt(response, "communication_id")

                if (phone == null || callId == null) {
                    onError("پاسخ CRM ناقص است")
                    return@thread
                }

                onSuccess(
                    CallInfo(
                        phone = phone,
                        callId = callId,
                        communicationId = communicationId
                    )
                )

            } catch (e: Exception) {
                onError(
                    e.message ?: "خطا در ارتباط با CRM"
                )
            }
        }
    }

    fun endCall(
        serverUrl: String,
        token: String,
        callId: Int,
        communicationId: Int?,
        duration: Int,
        status: String,
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {
        thread {
            try {

                val url = URL(
                    "${serverUrl.trimEnd('/')}/api/mobile/call/end"
                )

                val connection =
                    url.openConnection() as HttpURLConnection

                connection.requestMethod = "POST"
                connection.connectTimeout = 10000
                connection.readTimeout = 10000
                connection.doOutput = true

                connection.setRequestProperty(
                    "Authorization",
                    "Bearer $token"
                )

                connection.setRequestProperty(
                    "Content-Type",
                    "application/json"
                )

                val communicationJson =
                    if (communicationId != null) {
                        "\"communication_id\":$communicationId,"
                    } else {
                        ""
                    }

                val body = """
                    {
                        "call_id": $callId,
                        $communicationJson
                        "duration": $duration,
                        "status": "$status"
                    }
                """.trimIndent()

                connection.outputStream.use {
                    it.write(body.toByteArray())
                }

                val responseCode =
                    connection.responseCode

                connection.disconnect()

                if (responseCode in 200..299) {
                    onSuccess()
                } else {
                    onError(
                        "CRM HTTP $responseCode"
                    )
                }

            } catch (e: Exception) {
                onError(
                    e.message ?: "خطا در ارسال پایان تماس"
                )
            }
        }
    }

    private fun extractJsonString(
        json: String,
        key: String
    ): String? {

        val regex = Regex(
            "\"$key\"\\s*:\\s*\"([^\"]*)\""
        )

        return regex.find(json)
            ?.groupValues
            ?.getOrNull(1)
    }

    private fun extractJsonInt(
        json: String,
        key: String
    ): Int? {

        val regex = Regex(
            "\"$key\"\\s*:\\s*(\\d+)"
        )

        return regex.find(json)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
    }
}

data class CallInfo(
    val phone: String,
    val callId: Int,
    val communicationId: Int?
)
