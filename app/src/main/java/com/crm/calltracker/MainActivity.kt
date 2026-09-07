package com.crm.calltracker

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.telephony.PhoneNumberUtils
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var serverInput: EditText
    private lateinit var loginButton: Button
    private lateinit var loginStatusText: TextView

    private lateinit var loginLayout: LinearLayout
    private lateinit var mainLayout: LinearLayout

    private lateinit var customerListView: ListView
    private lateinit var customerStatusText: TextView

    private var customers = mutableListOf<Customer>()
    private var customerNames = mutableListOf<String>()

    private val nearbyWifiPermissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { granted ->

            if (granted) {

                discoverServer()

            } else {

                loginStatusText.text =
                    "برای پیدا کردن خودکار CRM، " +
                    "مجوز «دستگاه‌های نزدیک» لازم است."

                loginButton.isEnabled = true
            }
        }

    private val callPermissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { permissions ->

            val phoneGranted =
                permissions[Manifest.permission.CALL_PHONE] == true

            val stateGranted =
                permissions[Manifest.permission.READ_PHONE_STATE] == true

            if (!phoneGranted || !stateGranted) {

                // در صورت نیاز دوباره از کاربر درخواست می‌کنیم.
                checkCallPermission()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.activity_main)

        initializeViews()

        checkCallPermission()

        showLoginPage()

        checkNearbyWifiPermissionAndDiscover()
    }

    private fun initializeViews() {

        serverInput =
            findViewById(R.id.serverInput)

        loginButton =
            findViewById(R.id.loginButton)

        loginStatusText =
            findViewById(R.id.loginStatusText)

        loginLayout =
            findViewById(R.id.loginLayout)

        mainLayout =
            findViewById(R.id.mainLayout)

        customerListView =
            findViewById(R.id.customerListView)

        customerStatusText =
            findViewById(R.id.customerStatusText)

        loginButton.setOnClickListener {

            login()
        }
    }

    private fun checkNearbyWifiPermissionAndDiscover() {

        if (Build.VERSION.SDK_INT <
            Build.VERSION_CODES.TIRAMISU
        ) {

            discoverServer()

            return
        }

        val granted =
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.NEARBY_WIFI_DEVICES
            ) == PackageManager.PERMISSION_GRANTED

        if (granted) {

            discoverServer()

        } else {

            loginStatusText.text =
                "برای پیدا کردن خودکار سرور CRM، " +
                "مجوز «دستگاه‌های نزدیک» لازم است."

            nearbyWifiPermissionLauncher.launch(
                Manifest.permission.NEARBY_WIFI_DEVICES
            )
        }
    }

    private fun discoverServer() {

        loginStatusText.text =
            "در حال پیدا کردن سرور CRM..."

        loginButton.isEnabled = false

        ServerDiscovery.findServer(

            context = this,

            onFound = { serverUrl ->

                runOnUiThread {

                    ApiConfig.SERVER_URL =
                        serverUrl.trimEnd('/')

                    serverInput.setText(
                        ApiConfig.SERVER_URL
                    )

                    loginStatusText.text =
                        "سرور CRM پیدا شد:\n" +
                        ApiConfig.SERVER_URL

                    loginButton.isEnabled = true
                }
            },

            onError = { message ->

                runOnUiThread {

                    loginStatusText.text =
                        "خطای mDNS:\n$message"

                    loginButton.isEnabled = true
                }
            }
        )
    }

    private fun showLoginPage() {

        loginLayout.visibility =
            View.VISIBLE

        mainLayout.visibility =
            View.GONE

        loginStatusText.text =
            "در حال پیدا کردن سرور CRM..."

        loginButton.isEnabled = false
    }

    private fun login() {

        val serverUrl =
            serverInput.text
                .toString()
                .trim()
                .trimEnd('/')

        if (serverUrl.isBlank()) {

            loginStatusText.text =
                "آدرس سرور CRM را وارد کنید."

            return
        }

        if (
            !serverUrl.startsWith("http://") &&
            !serverUrl.startsWith("https://")
        ) {

            loginStatusText.text =
                "آدرس باید با http:// یا https:// شروع شود."

            return
        }

        ApiConfig.SERVER_URL =
            serverUrl

        loginButton.isEnabled = false

        loginStatusText.text =
            "در حال ورود..."

        Thread {

            try {

                val result =
                    LoginApi.login(
                        serverUrl = ApiConfig.SERVER_URL
                    )

                runOnUiThread {

                    loginButton.isEnabled = true

                    if (result.success) {

                        ApiConfig.AUTH_TOKEN =
                            result.token ?: ""

                        loginStatusText.text =
                            "ورود موفق بود."

                        showMainPage()

                        loadCustomers()

                    } else {

                        loginStatusText.text =
                            result.message
                                ?: "ورود ناموفق بود."
                    }
                }

            } catch (e: Exception) {

                runOnUiThread {

                    loginButton.isEnabled = true

                    loginStatusText.text =
                        "خطا در اتصال به CRM:\n" +
                        (e.message ?: "خطای نامشخص")
                }
            }

        }.start()
    }

    private fun showMainPage() {

        loginLayout.visibility =
            View.GONE

        mainLayout.visibility =
            View.VISIBLE
    }

    private fun loadCustomers() {

        customerStatusText.text =
            "در حال دریافت مشتریان..."

        Thread {

            try {

                val result =
                    CallApi.getCustomers(
                        ApiConfig.SERVER_URL,
                        ApiConfig.AUTH_TOKEN
                    )

                runOnUiThread {

                    if (result.success) {

                        customers =
                            result.customers
                                ?.toMutableList()
                                ?: mutableListOf()

                        customerNames.clear()

                        customers.forEach { customer ->

                            customerNames.add(
                                customer.name
                                    ?: customer.phone
                                    ?: "مشتری"
                            )
                        }

                        val adapter =
                            ArrayAdapter(
                                this,
                                android.R.layout.simple_list_item_1,
                                customerNames
                            )

                        customerListView.adapter =
                            adapter

                        customerStatusText.text =
                            "تعداد مشتریان: ${customers.size}"

                    } else {

                        customerStatusText.text =
                            result.message
                                ?: "دریافت مشتریان ناموفق بود."
                    }
                }

            } catch (e: Exception) {

                runOnUiThread {

                    customerStatusText.text =
                        "خطا در دریافت مشتریان:\n" +
                        (e.message ?: "خطای نامشخص")
                }
            }

        }.start()
    }

    private fun makeCall(customer: Customer) {

        val phone =
            customer.phone?.trim()

        if (phone.isNullOrBlank()) {

            return
        }

        val normalizedPhone =
            PhoneNumberUtils.normalizeNumber(
                phone
            )

        try {

            val intent =
                Intent(
                    Intent.ACTION_CALL
                )

            intent.data =
                android.net.Uri.parse(
                    "tel:$normalizedPhone"
                )

            startActivity(intent)

        } catch (e: Exception) {

            try {

                val intent =
                    Intent(
                        Intent.ACTION_DIAL
                    )

                intent.data =
                    android.net.Uri.parse(
                        "tel:$normalizedPhone"
                    )

                startActivity(intent)

            } catch (_: Exception) {
            }
        }
    }

    private fun checkCallPermission() {

        val permissions =
            mutableListOf<String>()

        if (
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.CALL_PHONE
            ) != PackageManager.PERMISSION_GRANTED
        ) {

            permissions.add(
                Manifest.permission.CALL_PHONE
            )
        }

        if (
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.READ_PHONE_STATE
            ) != PackageManager.PERMISSION_GRANTED
        ) {

            permissions.add(
                Manifest.permission.READ_PHONE_STATE
            )
        }

        if (permissions.isNotEmpty()) {

            callPermissionLauncher.launch(
                permissions.toTypedArray()
            )
        }
    }

    override fun onResume() {
        super.onResume()

        // اینجا عمداً discovery دوباره اجرا نمی‌شود.
        // فقط هنگام شروع صفحه Login انجام می‌شود.
    }
}
