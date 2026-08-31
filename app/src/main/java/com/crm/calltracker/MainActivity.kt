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

    private lateinit var phoneInput: EditText
    private lateinit var callButton: Button
    private lateinit var statusText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        createUi()

        checkCallPermission()
    }

    private fun createUi() {

        val layout = LinearLayout(this)

        layout.orientation = LinearLayout.VERTICAL
        layout.setPadding(40, 60, 40, 40)

        statusText = TextView(this)
        statusText.text = "CRM CallTracker"
        statusText.textSize = 22f

        phoneInput = EditText(this)
        phoneInput.hint = "شماره تلفن مشتری"
        phoneInput.inputType =
            android.text.InputType.TYPE_CLASS_PHONE

        callButton = Button(this)
        callButton.text = "📞 تماس"

        callButton.setOnClickListener {

            val phone = phoneInput.text
                .toString()
                .trim()

            if (phone.isEmpty()) {
                statusText.text = "لطفاً شماره تلفن را وارد کنید"
                return@setOnClickListener
            }

            makeCall(phone)
        }

        layout.addView(statusText)
        layout.addView(phoneInput)
        layout.addView(callButton)

        setContentView(layout)
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

        val intent = Intent(
            Intent.ACTION_CALL,
            Uri.parse("tel:$phone")
        )

        startActivity(intent)

        statusText.text = "در حال برقراری تماس با $phone"
    }
}
