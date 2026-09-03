package com.crm.calltracker

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {

    private val CALL_PERMISSION_CODE = 1001

    private lateinit var rootLayout: LinearLayout
    private lateinit var serverInput: EditText
    private lateinit var loginButton: Button
    private lateinit var loginStatusText: TextView

    private var customers = mutableListOf<Customer>()

    private var selectedCustomer: Customer? = null

    private lateinit var selectedCustomerText: TextView
    private lateinit var selectedPhoneText: TextView
    private lateinit var customerListLayout: LinearLayout
    private lateinit var searchInput: EditText
    private lateinit var callButton: Button
    private lateinit var mainStatusText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        checkCallPermission()

        showLoginPage()

        discoverServer()
    }

    // =========================================================
    // LOGIN PAGE
    // =========================================================

    private fun showLoginPage() {

        rootLayout = LinearLayout(this)
        rootLayout.orientation = LinearLayout.VERTICAL
        rootLayout.setPadding(40, 60, 40, 40)

        val title = TextView(this)
        title.text = "CRM CallTracker"
        title.textSize = 26f
        title.gravity = Gravity.CENTER
        title.setPadding(0, 0, 0, 30)

        loginStatusText = TextView(this)
        loginStatusText.text =
            "در حال پیدا کردن سرور CRM..."
        loginStatusText.textSize = 16f
        loginStatusText.setPadding(0, 0, 0, 20)

        serverInput = EditText(this)
        serverInput.hint = "آدرس CRM"
        serverInput.setText(ApiConfig.SERVER_URL)

        val usernameInput = EditText(this)
        usernameInput.hint = "نام کاربری"

        val passwordInput = EditText(this)
        passwordInput.hint = "رمز عبور"
        passwordInput.inputType =
            InputType.TYPE_CLASS_TEXT or
            InputType.TYPE_TEXT_VARIATION_PASSWORD

        loginButton = Button(this)
        loginButton.text = "ورود به CRM"
        loginButton.isEnabled =
            ApiConfig.SERVER_URL.isNotEmpty()

        rootLayout.addView(title)
        rootLayout.addView(loginStatusText)
        rootLayout.addView(serverInput)
        rootLayout.addView(usernameInput)
        rootLayout.addView(passwordInput)
        rootLayout.addView(loginButton)

        setContentView(rootLayout)

        loginButton.setOnClickListener {

            val serverUrl =
                serverInput.text.toString().trim()

            val username =
                usernameInput.text.toString().trim()

            val password =
                passwordInput.text.toString()

            if (serverUrl.isEmpty()) {
                loginStatusText.text =
                    "آدرس CRM وارد نشده است"
                return@setOnClickListener
            }

            if (username.isEmpty()) {
                loginStatusText.text =
                    "نام کاربری را وارد کنید"
                return@setOnClickListener
            }

            if (password.isEmpty()) {
                loginStatusText.text =
                    "رمز عبور را وارد کنید"
                return@setOnClickListener
            }

            loginStatusText.text =
                "در حال ورود به CRM..."

            loginButton.isEnabled = false

            LoginApi.login(
                serverUrl = serverUrl,
                username = username,
                password = password,

                onSuccess = { token, fullName ->

                    runOnUiThread {

                        ApiConfig.SERVER_URL =
                            serverUrl.trimEnd('/')

                        ApiConfig.AUTH_TOKEN =
                            token

                        showMainPage(fullName)
                    }
                },

                onError = { message ->

                    runOnUiThread {

                        loginStatusText.text =
                            "خطا: $message"

                        loginButton.isEnabled = true
                    }
                }
            )
        }
    }

    // =========================================================
    // SERVER DISCOVERY
    // =========================================================

    private fun discoverServer() {

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
                        "سرور CRM پیدا شد"

                    loginButton.isEnabled = true
                }
            },

            onError = {

                runOnUiThread {

                    loginStatusText.text =
                        "سرور CRM پیدا نشد؛ آدرس را دستی وارد کنید"

                    loginButton.isEnabled = true
                }
            }
        )
    }

    // =========================================================
    // MAIN PAGE
    // =========================================================

    private fun showMainPage(fullName: String) {

        rootLayout = LinearLayout(this)
        rootLayout.orientation = LinearLayout.VERTICAL
        rootLayout.setPadding(30, 40, 30, 30)

        val title = TextView(this)
        title.text = "CRM CallTracker"
        title.textSize = 26f
        title.gravity = Gravity.CENTER
        title.setPadding(0, 0, 0, 15)

        val welcomeText = TextView(this)

        welcomeText.text =
            if (fullName.isNotEmpty()) {
                "خوش آمدید $fullName"
            } else {
                "ورود موفق"
            }

        welcomeText.textSize = 20f
        welcomeText.gravity = Gravity.CENTER
        welcomeText.setPadding(0, 0, 0, 20)

        searchInput = EditText(this)
        searchInput.hint =
            "🔎 جستجوی مشتری..."
        searchInput.inputType =
            InputType.TYPE_CLASS_TEXT

        selectedCustomerText = TextView(this)
        selectedCustomerText.text =
            "مشتری انتخاب نشده است"
        selectedCustomerText.textSize = 18f
        selectedCustomerText.setPadding(
            0, 20, 0, 5
        )

        selectedPhoneText = TextView(this)
        selectedPhoneText.text =
            "شماره تلفن: -"
        selectedPhoneText.textSize = 16f
        selectedPhoneText.setPadding(
            0, 0, 0, 15
        )

        callButton = Button(this)
        callButton.text = "📞 تماس"
        callButton.isEnabled = false

        mainStatusText = TextView(this)
        mainStatusText.text =
            "در حال دریافت مشتری‌ها..."
        mainStatusText.textSize = 15f
        mainStatusText.setPadding(
            0, 10, 0, 10
        )

        customerListLayout = LinearLayout(this)
        customerListLayout.orientation =
            LinearLayout.VERTICAL

        val scrollView = ScrollView(this)

        scrollView.addView(
            customerListLayout
        )

        rootLayout.addView(title)
        rootLayout.addView(welcomeText)
        rootLayout.addView(searchInput)
        rootLayout.addView(selectedCustomerText)
        rootLayout.addView(selectedPhoneText)
        rootLayout.addView(callButton)
        rootLayout.addView(mainStatusText)

        rootLayout.addView(
            scrollView,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        )

        setContentView(rootLayout)

        searchInput.addTextChangedListener(
            SimpleTextWatcher {
                filterCustomers(it)
            }
        )

        callButton.setOnClickListener {

            val customer =
                selectedCustomer

            if (customer == null) {
                mainStatusText.text =
                    "لطفاً ابتدا یک مشتری انتخاب کنید"
                return@setOnClickListener
            }

            if (customer.phone.isEmpty()) {
                mainStatusText.text =
                    "برای این مشتری شماره تلفن ثبت نشده است"
                return@setOnClickListener
            }

            makeCall(
                customer = customer
            )
        }

        loadCustomers()
    }

    // =========================================================
    // LOAD CUSTOMERS
    // =========================================================

    private fun loadCustomers() {

        val serverUrl =
            ApiConfig.SERVER_URL.trim()

        val token =
            ApiConfig.AUTH_TOKEN.trim()

        if (serverUrl.isEmpty()) {
            mainStatusText.text =
                "آدرس CRM موجود نیست"
            return
        }

        if (token.isEmpty()) {
            mainStatusText.text =
                "توکن CRM موجود نیست"
            return
        }

        mainStatusText.text =
            "در حال دریافت لیست مشتری‌ها..."

        CustomerApi.getCustomers(

            serverUrl = serverUrl,

            token = token,

            onSuccess = { result ->

                runOnUiThread {

                    customers.clear()
                    customers.addAll(result)

                    mainStatusText.text =
                        "${customers.size} مشتری دریافت شد"

                    displayCustomers(customers)
                }
            },

            onError = { message ->

                runOnUiThread {

                    mainStatusText.text =
                        "خطا در دریافت مشتری‌ها: $message"
                }
            }
        )
    }

    // =========================================================
    // DISPLAY CUSTOMERS
    // =========================================================

    private fun displayCustomers(
        list: List<Customer>
    ) {

        customerListLayout.removeAllViews()

        if (list.isEmpty()) {

            val emptyText = TextView(this)

            emptyText.text =
                "مشتری‌ای پیدا نشد"

            emptyText.textSize = 17f
            emptyText.setPadding(
                0, 30, 0, 30
            )

            customerListLayout.addView(
                emptyText
            )

            return
        }

        for (customer in list) {

            val button = Button(this)

            val company =
                if (customer.companyName.isNotEmpty()) {
                    " - ${customer.companyName}"
                } else {
                    ""
                }

            button.text =
                "${customer.name}$company\n${customer.phone}"

            button.textSize = 16f

            button.setOnClickListener {

                selectCustomer(customer)
            }

            customerListLayout.addView(
                button,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )
        }
    }

    // =========================================================
    // SEARCH
    // =========================================================

    private fun filterCustomers(
        text: String
    ) {

        val query =
            text.trim().lowercase()

        if (query.isEmpty()) {

            displayCustomers(customers)

            return
        }

        val filtered =
            customers.filter {

                it.name.lowercase()
                    .contains(query) ||

                it.phone.lowercase()
                    .contains(query) ||

                it.companyName.lowercase()
                    .contains(query) ||

                it.city.lowercase()
                    .contains(query) ||

                it.id.toString()
                    .contains(query)
            }

        displayCustomers(filtered)
    }

    // =========================================================
    // SELECT CUSTOMER
    // =========================================================

    private fun selectCustomer(
        customer: Customer
    ) {

        selectedCustomer =
            customer

        selectedCustomerText.text =
            "مشتری انتخاب‌شده:\n${customer.name}"

        selectedPhoneText.text =
            "شماره تلفن: ${
                if (customer.phone.isNotEmpty()) {
                    customer.phone
                } else {
                    "ثبت نشده"
                }
            }\nشناسه مشتری: ${customer.id}"

        callButton.isEnabled =
            customer.phone.isNotEmpty()

        mainStatusText.text =
            "مشتری انتخاب شد؛ آماده تماس"
    }

    // =========================================================
    // CALL
    // =========================================================

    private fun makeCall(
        customer: Customer
    ) {

        if (
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.CALL_PHONE
            ) != PackageManager.PERMISSION_GRANTED
        ) {

            checkCallPermission()

            return
        }

        val serverUrl =
            ApiConfig.SERVER_URL.trim()

        val token =
            ApiConfig.AUTH_TOKEN.trim()

        if (serverUrl.isEmpty()) {

            mainStatusText.text =
                "سرور CRM پیدا نشده است"

            return
        }

        if (token.isEmpty()) {

            mainStatusText.text =
                "توکن CRM موجود نیست؛ دوباره وارد شوید"

            return
        }

        callButton.isEnabled = false

        mainStatusText.text =
            "در حال ثبت تماس در CRM..."

        CallApi.startCall(

            serverUrl = serverUrl,

            token = token,

            customerId = customer.id,

            onSuccess = { callInfo ->

                runOnUiThread {

                    val prefs =
                        getSharedPreferences(
                            "call_tracker",
                            MODE_PRIVATE
                        )

                    prefs.edit()
                        .putInt(
                            "call_id",
                            callInfo.callId
                        )
                        .putInt(
                            "communication_id",
                            callInfo.communicationId ?: 0
                        )
                        .putString(
                            "phone",
                            callInfo.phone
                        )
                        .apply()

                    mainStatusText.text =
                        "تماس در CRM ثبت شد؛ در حال تماس..."

                    val intent = Intent(
                        Intent.ACTION_CALL,
                        Uri.parse(
                            "tel:${callInfo.phone}"
                        )
                    )

                    try {

                        startActivity(intent)

                        mainStatusText.text =
                            "در حال تماس با ${callInfo.phone}"

                    } catch (_: Exception) {

                        mainStatusText.text =
                            "برقراری تماس انجام نشد"

                        callButton.isEnabled =
                            true
                    }
                }
            },

            onError = { message ->

                runOnUiThread {

                    mainStatusText.text =
                        "خطا در ثبت تماس: $message"

                    callButton.isEnabled =
                        true
                }
            }
        )
    }

    // =========================================================
    // PERMISSIONS
    // =========================================================

    private fun checkCallPermission() {

        if (
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.CALL_PHONE
            ) != PackageManager.PERMISSION_GRANTED
        ) {

            ActivityCompat.requestPermissions(
                this,

                arrayOf(
                    Manifest.permission.CALL_PHONE,
                    Manifest.permission.READ_PHONE_STATE
                ),

                CALL_PERMISSION_CODE
            )
        }
    }

    // =========================================================
    // SIMPLE TEXT WATCHER
    // =========================================================

    private class SimpleTextWatcher(
        private val onTextChangedAction: (String) -> Unit
    ) : android.text.TextWatcher {

        override fun beforeTextChanged(
            s: CharSequence?,
            start: Int,
            count: Int,
            after: Int
        ) {
        }

        override fun onTextChanged(
            s: CharSequence?,
            start: Int,
            before: Int,
            count: Int
        ) {
            onTextChangedAction(
                s?.toString() ?: ""
            )
        }

        override fun afterTextChanged(
            s: android.text.Editable?
        ) {
        }
    }
}
