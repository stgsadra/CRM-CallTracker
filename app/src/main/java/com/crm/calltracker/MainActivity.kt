package com.crm.calltracker

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.telephony.PhoneNumberUtils
import android.view.Gravity
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {

    companion object {
        private const val PREFS = "call_tracker"

        private const val KEY_CALL_ID = "call_id"
        private const val KEY_COMMUNICATION_ID = "communication_id"
        private const val KEY_PHONE = "phone"
    }

    private lateinit var serverInput: EditText
    private lateinit var usernameInput: EditText
    private lateinit var passwordInput: EditText

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
                    "مجوز «دستگاه‌های نزدیک» داده نشد.\n" +
                    "می‌توانید آدرس سرور را دستی وارد کنید."

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
                loginStatusText.text =
                    "برای برقراری تماس و تشخیص وضعیت تماس، " +
                    "مجوزهای تلفن لازم است."
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        buildUserInterface()

        checkCallPermission()

        showLoginPage()

        checkNearbyWifiPermissionAndDiscover()
    }

    private fun buildUserInterface() {

        val root =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(32, 32, 32, 32)
            }
            
        setContentView(root)

        // ---------------------------------------------------------
        // LOGIN LAYOUT
        // ---------------------------------------------------------

        loginLayout =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
            }

        root.addView(
            loginLayout,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        val title =
            TextView(this).apply {
                text = "CRM CallTracker"
                textSize = 26f
                gravity = Gravity.CENTER
                setPadding(0, 20, 0, 30)
            }

        loginLayout.addView(title)

        serverInput =
            EditText(this).apply {
                hint = "آدرس سرور CRM"
                setSingleLine(true)
                textSize = 16f
            }

        loginLayout.addView(
            serverInput,
            createFieldParams()
        )

        usernameInput =
            EditText(this).apply {
                hint = "نام کاربری"
                setSingleLine(true)
                textSize = 16f
            }

        loginLayout.addView(
            usernameInput,
            createFieldParams()
        )

        passwordInput =
            EditText(this).apply {
                hint = "رمز عبور"
                setSingleLine(true)
                inputType =
                    android.text.InputType.TYPE_CLASS_TEXT or
                    android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
                textSize = 16f
            }

        loginLayout.addView(
            passwordInput,
            createFieldParams()
        )

        loginButton =
            Button(this).apply {
                text = "ورود به CRM"
                isEnabled = false
            }

        loginLayout.addView(
            loginButton,
            createButtonParams()
        )

        loginStatusText =
            TextView(this).apply {
                text = "در حال پیدا کردن سرور CRM..."
                textSize = 15f
                gravity = Gravity.CENTER
                setPadding(0, 20, 0, 20)
            }

        loginLayout.addView(
            loginStatusText,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        loginButton.setOnClickListener {
            login()
        }

        // ---------------------------------------------------------
        // MAIN LAYOUT
        // ---------------------------------------------------------

        mainLayout =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                visibility = View.GONE
            }

        root.addView(
            mainLayout,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
        )

        val mainTitle =
            TextView(this).apply {
                text = "مشتریان CRM"
                textSize = 24f
                gravity = Gravity.CENTER
                setPadding(0, 20, 0, 20)
            }

        mainLayout.addView(mainTitle)

        customerStatusText =
            TextView(this).apply {
                text = "در حال دریافت مشتریان..."
                textSize = 15f
                gravity = Gravity.CENTER
                setPadding(0, 10, 0, 20)
            }

        mainLayout.addView(
            customerStatusText,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        customerListView =
            ListView(this).apply {
                dividerHeight = 1
            }

        mainLayout.addView(
            customerListView,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        )

        customerListView.setOnItemClickListener { _, _, position, _ ->

            if (position >= 0 && position < customers.size) {
                startCustomerCall(
                    customers[position]
                )
            }
        }
    }

    private fun createFieldParams():
        LinearLayout.LayoutParams {

        return LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            setMargins(0, 0, 0, 16)
        }
    }

    private fun createButtonParams():
        LinearLayout.LayoutParams {

        return LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            setMargins(0, 10, 0, 10)
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

            loginButton.isEnabled = false

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

        val username =
            usernameInput.text
                .toString()
                .trim()

        val password =
            passwordInput.text
                .toString()

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
                "آدرس سرور باید با http:// یا https:// شروع شود."

            return
        }

        if (username.isBlank()) {

            loginStatusText.text =
                "نام کاربری را وارد کنید."

            return
        }

        if (password.isBlank()) {

            loginStatusText.text =
                "رمز عبور را وارد کنید."

            return
        }

        ApiConfig.SERVER_URL =
            serverUrl

        loginButton.isEnabled = false

        loginStatusText.text =
            "در حال ورود به CRM..."

        LoginApi.login(

            serverUrl = ApiConfig.SERVER_URL,

            username = username,

            password = password,

            onSuccess = { token, fullName ->

                runOnUiThread {

                    ApiConfig.AUTH_TOKEN =
                        token

                    loginButton.isEnabled = true

                    loginStatusText.text =
                        if (fullName.isNotBlank()) {
                            "ورود موفق بود.\n$fullName"
                        } else {
                            "ورود موفق بود."
                        }

                    showMainPage()

                    loadCustomers()
                }
            },

            onError = { message ->

                runOnUiThread {

                    loginButton.isEnabled = true

                    loginStatusText.text =
                        "ورود ناموفق بود:\n$message"
                }
            }
        )
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

        CustomerApi.getCustomers(

            serverUrl =
                ApiConfig.SERVER_URL,

            token =
                ApiConfig.AUTH_TOKEN,

            onSuccess = { list ->

                runOnUiThread {

                    customers =
                        list.toMutableList()

                    customerNames.clear()

                    customers.forEach { customer ->

                        val displayName =
                            when {

                                customer.name.isNotBlank() &&
                                customer.companyName.isNotBlank() ->
                                    "${customer.name} - ${customer.companyName}"

                                customer.name.isNotBlank() ->
                                    customer.name

                                customer.phone.isNotBlank() ->
                                    customer.phone

                                else ->
                                    "مشتری"
                            }

                        customerNames.add(
                            displayName
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
                }
            },

            onError = { message ->

                runOnUiThread {

                    customerStatusText.text =
                        "خطا در دریافت مشتریان:\n$message"
                }
            }
        )
    }

    private fun startCustomerCall(
        customer: Customer
    ) {

        val phone =
            customer.phone
                .trim()

        if (phone.isBlank()) {

            customerStatusText.text =
                "شماره تلفن این مشتری ثبت نشده است."

            return
        }

        val normalizedPhone =
            PhoneNumberUtils.normalizeNumber(
                phone
            )

        if (normalizedPhone.isBlank()) {

            customerStatusText.text =
                "شماره تلفن مشتری معتبر نیست."

            return
        }

        val serverUrl =
            ApiConfig.SERVER_URL.trim()

        val token =
            ApiConfig.AUTH_TOKEN.trim()

        if (
            serverUrl.isBlank() ||
            token.isBlank()
        ) {

            customerStatusText.text =
                "اتصال CRM معتبر نیست. دوباره وارد شوید."

            return
        }

        customerStatusText.text =
            "در حال ثبت شروع تماس..."

        CallApi.startCall(

            serverUrl = serverUrl,

            token = token,

            customerId = customer.id,

            onSuccess = { callInfo ->

                saveCallInfo(
                    callInfo = callInfo
                )

                runOnUiThread {

                    customerStatusText.text =
                        "تماس با ${customer.name} در حال برقراری است..."

                    makePhoneCall(
                        callInfo.phone
                    )
                }
            },

            onError = { message ->

                runOnUiThread {

                    customerStatusText.text =
                        "خطا در شروع تماس:\n$message"
                }
            }
        )
    }

    private fun saveCallInfo(
        callInfo: CallInfo
    ) {

        val prefs =
            getSharedPreferences(
                PREFS,
                MODE_PRIVATE
            )

        val editor =
            prefs.edit()
                .putInt(
                    KEY_CALL_ID,
                    callInfo.callId
                )
                .putString(
                    KEY_PHONE,
                    callInfo.phone
                )

        if (callInfo.communicationId != null) {

            editor.putInt(
                KEY_COMMUNICATION_ID,
                callInfo.communicationId
            )

        } else {

            editor.remove(
                KEY_COMMUNICATION_ID
            )
        }

        editor.apply()
    }

    private fun makePhoneCall(
        phone: String
    ) {

        val normalizedPhone =
            PhoneNumberUtils.normalizeNumber(
                phone
            )

        try {

            val intent =
                Intent(
                    Intent.ACTION_CALL
                ).apply {

                    data =
                        Uri.parse(
                            "tel:$normalizedPhone"
                        )
                }

            if (
                ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.CALL_PHONE
                ) != PackageManager.PERMISSION_GRANTED
            ) {

                checkCallPermission()
                return
            }

            startActivity(intent)

        } catch (e: Exception) {

            try {

                val intent =
                    Intent(
                        Intent.ACTION_DIAL
                    ).apply {

                        data =
                            Uri.parse(
                                "tel:$normalizedPhone"
                            )
                    }

                startActivity(intent)

            } catch (dialException: Exception) {

                customerStatusText.text =
                    "خطا در برقراری تماس:\n" +
                    (
                        dialException.message
                            ?: e.message
                            ?: "خطای نامشخص"
                    )
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
}

