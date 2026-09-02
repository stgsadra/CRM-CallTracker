package com.crm.calltracker

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {

    private val CALL_PERMISSION_CODE = 1001

    private lateinit var serverInput: EditText
    private lateinit var usernameInput: EditText
    private lateinit var passwordInput: EditText

    private lateinit var loginButton: Button
    private lateinit var callButton: Button

    private lateinit var customerInput: EditText
    private lateinit var phoneInput: EditText

    private lateinit var statusText: TextView

    private var activeCall: CallInfo? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        createUi()
        checkCallPermission()

        
    }

    private fun discoverServer() {

        statusText.text = "در حال پیدا کردن سرور CRM..."

        loginButton.isEnabled = false

        ServerDiscovery.findServer(
            context = this,

            onFound = { serverUrl ->

                runOnUiThread {

                    ApiConfig.SERVER_URL = serverUrl

                    serverInput.setText(serverUrl)

                    statusText.text =
                        "سرور CRM پیدا شد"

                    loginButton.isEnabled = true
                }
            },

            onError = { message ->

                runOnUiThread {

                    statusText.text =
                        "سرور CRM پیدا نشد؛ آدرس را دستی وارد کنید"

                    loginButton.isEnabled = true
                }
            }
        )
    }

    private fun createUi() {

        val layout = LinearLayout(this)

        layout.orientation = LinearLayout.VERTICAL
        layout.setPadding(40, 60, 40, 40)

        statusText = TextView(this)
        statusText.text = "CRM CallTracker"
        statusText.textSize = 22f

        serverInput = EditText(this)
        serverInput.hint = "آدرس CRM"
        serverInput.setText(ApiConfig.SERVER_URL)

        usernameInput = EditText(this)
        usernameInput.hint = "نام کاربری"

        passwordInput = EditText(this)
        passwordInput.hint = "رمز عبور"
        passwordInput.inputType =
            android.text.InputType.TYPE_CLASS_TEXT or
            android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD

        loginButton = Button(this)
        loginButton.text = "ورود به CRM"

        customerInput = EditText(this)
        customerInput.hint = "شناسه مشتری (Customer ID)"
        customerInput.inputType =
            android.text.InputType.TYPE_CLASS_NUMBER

        phoneInput = EditText(this)
        phoneInput.hint = "شماره تلفن مشتری"
        phoneInput.inputType =
            android.text.InputType.TYPE_CLASS_PHONE

        callButton = Button(this)
        callButton.text = "📞 تماس"
        callButton.isEnabled = false

        layout.addView(statusText)
        layout.addView(serverInput)
        layout.addView(usernameInput)
        layout.addView(passwordInput)
        layout.addView(loginButton)
        layout.addView(customerInput)
        layout.addView(phoneInput)
        layout.addView(callButton)

        setContentView(layout)

        loginButton.setOnClickListener {
            login()
        }

        callButton.setOnClickListener {

            val phone = phoneInput.text
                .toString()
                .trim()

            if (phone.isEmpty()) {

                statusText.text =
                    "لطفاً شماره تلفن را وارد کنید"

                return@setOnClickListener
            }

            makeCall(phone)
        }
    }

    private fun login() {

        val serverUrl =
            serverInput.text.toString().trim()

        val username =
            usernameInput.text.toString().trim()

        val password =
            passwordInput.text.toString()

        if (serverUrl.isEmpty()) {

            statusText.text =
                "آدرس CRM پیدا نشده است"

            return
        }

        if (username.isEmpty()) {

            statusText.text =
                "نام کاربری را وارد کنید"

            return
        }

        if (password.isEmpty()) {

            statusText.text =
                "رمز عبور را وارد کنید"

            return
        }

        statusText.text =
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

                    statusText.text =
                        if (fullName.isNotEmpty()) {
                            "ورود موفق: $fullName"
                        } else {
                            "ورود موفق"
                        }

                    loginButton.isEnabled = true
                    callButton.isEnabled = true
                }
            },

            onError = { message ->

                runOnUiThread {

                    statusText.text =
                        "خطا: $message"

                    loginButton.isEnabled = true
                    callButton.isEnabled = false
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

    private fun makeCall(phone: String) {

        if (
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.CALL_PHONE
            ) != PackageManager.PERMISSION_GRANTED
        ) {

            checkCallPermission()
            return
        }

        val customerIdText =
            customerInput.text.toString().trim()

        if (customerIdText.isEmpty()) {

            statusText.text =
                "لطفاً شناسه مشتری را وارد کنید"

            return
        }

        val customerId =
            customerIdText.toIntOrNull()

        if (customerId == null) {

            statusText.text =
                "شناسه مشتری نامعتبر است"

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

                    phoneInput.setText(
                        callInfo.phone
                    )

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
