package com.crm.calltracker

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

object LoginApi {

    fun login(
        serverUrl: String,
        username: String,
        password: String,
        onSuccess: (String, String) -> Unit,
        onError: (String) -> Unit
    ) {
        thread {
            try {
                val url = URL(
                    "${serverUrl.trimEnd('/')}/api/mobile/login"
                )

                val connection =
                    url.openConnection() as HttpURLConnection

                connection.requestMethod = "POST"
                connection.connectTimeout = 10000
                connection.readTimeout = 10000
                connection.doOutput = true

                connection.setRequestProperty(
                    "Content-Type",
                    "application/json"
                )

                val body = JSONObject().apply {
                    put("username", username)
                    put("password", password)
                }.toString()

                connection.outputStream.use {
                    it.write(body.toByteArray(Charsets.UTF_8))
                }

                val responseCode = connection.responseCode

                val response = if (responseCode in 200..299) {
                    connection.inputStream.bufferedReader().use {
                        it.readText()
                    }
                } else {
                    connection.errorStream
                        ?.bufferedReader()
                        ?.use { it.readText() }
                        ?: ""
                }

                connection.disconnect()

                val json = JSONObject(response)

                if (
                    responseCode in 200..299 &&
                    json.optBoolean("success", false)
                ) {
                    val token = json.optString("token", "")

                    val fullName =
                        json.optJSONObject("user")
                            ?.optString("full_name", "")
                            ?: ""

                    if (token.isEmpty()) {
                        onError("توکن از CRM دریافت نشد")
                        return@thread
                    }

                    onSuccess(token, fullName)

                } else {
                    onError(
                        json.optString(
                            "message",
                            "ورود ناموفق بود"
                        )
                    )
                }

            } catch (e: Exception) {
                onError(
                    e.message ?: "خطا در ارتباط با CRM"
                )
            }
        }
    }
}
