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
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {

    private val CALL_PERMISSION_CODE = 1001

    private lateinit var rootLayout: LinearLayout

    private var activeCall: CallInfo? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        checkCallPermission()
        showLoginPage()
        discoverServer()
    }

    private fun showLoginPage() {

        rootLayout = LinearLayout(this)
        rootLayout.orientation = LinearLayout.VERTICAL
        rootLayout.setPadding(40, 60, 40, 40)

        val title = TextView(this)
        title.text = "CRM CallTracker"
        title.textSize = 26f
        title.gravity = Gravity.CENTER
        title.setPadding(0, 0, 0, 30)

        val statusText = TextView(this)
        statusText.text = "در حال پیدا کردن سرور CRM..."
        statusText.textSize = 16f
        statusText.setPadding(0, 0, 0, 20)

        val serverInput = EditText(this)
        serverInput.hint = "آدرس CRM"
        serverInput.setText(ApiConfig.SERVER_URL)

        val usernameInput = EditText(this)
        usernameInput.hint = "نام کاربری"

        val passwordInput = EditText(this)
        passwordInput.hint = "رمز عبور"
        passwordInput.inputType =
            InputType.TYPE_CLASS_TEXT or
            InputType.TYPE_TEXT_VARIATION_PASSWORD

        val loginButton = Button(this)
        loginButton.text = "ورود به CRM"
        loginButton.isEnabled = ApiConfig.SERVER_URL.isNotEmpty()

        rootLayout.addView(title)
        rootLayout.addView(statusText)
        rootLayout.addView(serverInput)
        rootLayout.addView(usernameInput)
        rootLayout.addView(passwordInput)
        rootLayout.addView(loginButton)

        setContentView(rootLayout)

        loginButton.setOnClickListener {

            val serverUrl = serverInput.text.toString().trim()
            val username = usernameInput.text.toString().trim()
            val password = passwordInput.text.toString()

            if (serverUrl.isEmpty()) {
                statusText.text = "آدرس CRM وارد نشده است"
                return@setOnClickListener
            }

            if (username.isEmpty()) {
                statusText.text = "نام کاربری را وارد کنید"
                return@setOnClickListener
            }

            if (password.isEmpty()) {
                statusText.text = "رمز عبور را وارد کنید"
                return@setOnClickListener
            }

            statusText.text = "در حال ورود به CRM..."
            loginButton.isEnabled = false

            LoginApi.login(
                serverUrl = serverUrl,
                username = username,
                password = password,

                onSuccess = { token, fullName ->

                    runOnUiThread {

                        ApiConfig.SERVER_URL =
                            serverUrl.trimEnd('/')

                        ApiConfig.AUTH_TOKEN = token

                        showMainPage(
                            fullName = fullName
                        )
                    }
                },

                onError = { message ->

                    runOnUiThread {

                        statusText.text =
                            "خطا: $message"

                        loginButton.isEnabled = true
                    }
                }
            )
        }
    }

    private fun showMainPage(fullName: String) {

        rootLayout = LinearLayout(this)
        rootLayout.orientation = LinearLayout.VERTICAL
        rootLayout.setPadding(40, 60, 40, 40)

        val title = TextView(this)
        title.text = "CRM CallTracker"
        title.textSize = 26f
        title.gravity = Gravity.CENTER
        title.setPadding(0, 0, 0, 25)

        val welcomeText = TextView(this)
        welcomeText.text =
            if (fullName.isNotEmpty()) {
                "خوش آمدید $fullName"
            } else {
                "ورود موفق"
            }

        welcomeText.textSize = 20f
        welcomeText.gravity = Gravity.CENTER
        welcomeText.setPadding(0, 0, 0, 30)

        val customerInput = EditText(this)
        customerInput.hint = "شناسه مشتری (Customer ID)"
        customerInput.inputType =
            InputType.TYPE_CLASS_NUMBER

        val phoneInput = EditText(this)
        phoneInput.hint = "شماره تلفن مشتری"
        phoneInput.inputType =
            InputType.TYPE_CLASS_PHONE

        val callButton = Button(this)
        callButton.text = "📞 تماس"

        val statusText = TextView(this)
        statusText.text = "آماده برقراری تماس"
        statusText.textSize = 16f
        statusText.setPadding(0, 25, 0, 0)

        rootLayout.addView(title)
        rootLayout.addView(welcomeText)
        rootLayout.addView(customerInput)
        rootLayout.addView(phoneInput)
        rootLayout.addView(callButton)
        rootLayout.addView(statusText)

        setContentView(rootLayout)

        callButton.setOnClickListener {

            val phone = phoneInput.text.toString().trim()

            if (phone.isEmpty()) {
                statusText.text =
                    "لطفاً شماره تلفن را وارد کنید"
                return@setOnClickListener
            }

            val customerIdText =
                customerInput.text.toString().trim()

            if (customerIdText.isEmpty()) {
                statusText.text =
                    "لطفاً شناسه مشتری را وارد کنید"
                return@setOnClickListener
            }

            val customerId =
                customerIdText.toIntOrNull()

            if (customerId == null) {
                statusText.text =
                    "شناسه مشتری نامعتبر است"
                return@setOnClickListener
            }

            makeCall(
                phone = phone,
                customerId = customerId,
                statusText = statusText,
                callButton = callButton
            )
        }
    }

    private fun discoverServer() {

        ServerDiscovery.findServer(

            context = this,

            onFound = { serverUrl ->

                runOnUiThread {

                    ApiConfig.SERVER_URL = serverUrl

                    val currentView =
                        rootLayout

                    if (currentView.childCount > 1) {

                        val statusView =
                            currentView.getChildAt(1)

                        if (statusView is TextView) {
                            statusView.text =
                                "سرور CRM پیدا شد"
                        }
                    }
                }
            },

            onError = { message ->

                runOnUiThread {

                    val currentView =
                        rootLayout

                    if (currentView.childCount > 1) {

                        val statusView =
                            currentView.getChildAt(1)

                        if (statusView is TextView) {
                            statusView.text =
                                "سرور CRM پیدا نشد"
                        }
                    }
                }
            }
        )
    }

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

    private fun makeCall(
        phone: String,
        customerId: Int,
        statusText: TextView,
        callButton: Button
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
            statusText.text =
                "سرور CRM پیدا نشده است"
            return
        }

        if (token.isEmpty()) {
            statusText.text =
                "توکن CRM موجود نیست؛ دوباره وارد شوید"
            return
        }

        callButton.isEnabled = false

        statusText.text =
            "در حال ثبت تماس در CRM..."

        CallApi.startCall(

            serverUrl = serverUrl,

            token = token,

            customerId = customerId,

            onSuccess = { callInfo ->

                runOnUiThread {

                    activeCall = callInfo

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

                    statusText.text =
                        "تماس در CRM ثبت شد؛ در حال تماس..."

                    val intent = Intent(
                        Intent.ACTION_CALL,
                        Uri.parse(
                            "tel:${callInfo.phone}"
                        )
                    )

                    try {

                        startActivity(intent)

                        statusText.text =
                            "در حال تماس با ${callInfo.phone}"

                    } catch (e: Exception) {

                        statusText.text =
                            "برقراری تماس انجام نشد"

                        callButton.isEnabled = true
                    }
                }
            },

            onError = { message ->

                runOnUiThread {

                    statusText.text =
                        "خطا در ثبت تماس: $message"

                    callButton.isEnabled = true
                }
            }
        )
    }
}
