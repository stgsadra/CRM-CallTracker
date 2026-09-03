package com.crm.calltracker

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

data class Customer(
    val id: Int,
    val name: String,
    val phone: String,
    val companyName: String,
    val city: String
)

object CustomerApi {

    fun getCustomers(
        serverUrl: String,
        token: String,
        onSuccess: (List<Customer>) -> Unit,
        onError: (String) -> Unit
    ) {
        thread {
            try {
                val url = URL(
                    "${serverUrl.trimEnd('/')}/api/mobile/customers"
                )

                val connection =
                    url.openConnection() as HttpURLConnection

                connection.requestMethod = "GET"
                connection.connectTimeout = 10000
                connection.readTimeout = 10000

                connection.setRequestProperty(
                    "Authorization",
                    "Bearer $token"
                )

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
                    responseCode !in 200..299 ||
                    !json.optBoolean("success", false)
                ) {
                    onError(
                        json.optString(
                            "message",
                            "دریافت مشتری‌ها ناموفق بود"
                        )
                    )
                    return@thread
                }

                val customersJson =
                    json.optJSONArray("customers")

                val customers = mutableListOf<Customer>()

                if (customersJson != null) {

                    for (i in 0 until customersJson.length()) {

                        val item =
                            customersJson.getJSONObject(i)

                        customers.add(
                            Customer(
                                id = item.optInt("id"),
                                name = item.optString("name"),
                                phone = item.optString("phone"),
                                companyName =
                                    item.optString("company_name"),
                                city =
                                    item.optString("city")
                            )
                        )
                    }
                }

                onSuccess(customers)

            } catch (e: Exception) {

                onError(
                    e.message
                        ?: "خطا در دریافت لیست مشتری‌ها"
                )
            }
        }
    }
}
