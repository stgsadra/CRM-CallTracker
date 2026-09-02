package com.crm.calltracker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.TelephonyManager
import android.widget.Toast

class CallStateReceiver : BroadcastReceiver() {

    companion object {

        private const val PREFS = "call_tracker"

        private const val KEY_CALL_ID = "call_id"
        private const val KEY_COMMUNICATION_ID =
            "communication_id"

        private const val KEY_START_TIME =
            "start_time"

        private const val KEY_PHONE = "phone"

        private const val STATE_OFFHOOK =
            "OFFHOOK"

        private const val STATE_IDLE =
            "IDLE"
    }

    override fun onReceive(
        context: Context,
        intent: Intent
    ) {

        if (
            intent.action !=
            TelephonyManager.ACTION_PHONE_STATE_CHANGED
        ) {
            return
        }

        val state =
            intent.getStringExtra(
                TelephonyManager.EXTRA_STATE
            ) ?: return

        val prefs =
            context.getSharedPreferences(
                PREFS,
                Context.MODE_PRIVATE
            )

        when (state) {

            STATE_OFFHOOK -> {

                if (
                    !prefs.contains(
                        KEY_START_TIME
                    )
                ) {

                    prefs.edit()
                        .putLong(
                            KEY_START_TIME,
                            System.currentTimeMillis()
                        )
                        .apply()
                }
            }

            STATE_IDLE -> {

                val startTime =
                    prefs.getLong(
                        KEY_START_TIME,
                        0L
                    )

                if (startTime == 0L) {
                    return
                }

                val durationSeconds =
                    (
                        System.currentTimeMillis() -
                        startTime
                    ) / 1000

                val callId =
                    prefs.getInt(
                        KEY_CALL_ID,
                        0
                    )

                val communicationId =
                    prefs.getInt(
                        KEY_COMMUNICATION_ID,
                        0
                    )

                if (callId == 0) {
                    clearCall(prefs)
                    return
                }

                sendCallEnd(
                    context = context,
                    prefs = prefs,
                    callId = callId,
                    communicationId =
                        if (communicationId != 0) {
                            communicationId
                        } else {
                            null
                        },
                    duration =
                        durationSeconds.toInt()
                )
            }
        }
    }

    private fun sendCallEnd(
        context: Context,
        prefs: android.content.SharedPreferences,
        callId: Int,
        communicationId: Int?,
        duration: Int
    ) {

        val serverUrl =
            ApiConfig.SERVER_URL.trim()

        val token =
            ApiConfig.AUTH_TOKEN.trim()

        if (
            serverUrl.isEmpty() ||
            token.isEmpty()
        ) {

            Toast.makeText(
                context,
                "آدرس سرور یا توکن CRM تنظیم نشده است",
                Toast.LENGTH_LONG
            ).show()

            return
        }

        CallApi.endCall(
            serverUrl = serverUrl,
            token = token,
            callId = callId,
            communicationId = communicationId,
            duration = duration,
            status =
                if (duration > 0) {
                    "answered"
                } else {
                    "failed"
                },

            onSuccess = {

                // فقط وقتی CRM با موفقیت پاسخ داد
                // اطلاعات تماس را از گوشی پاک می‌کنیم.
                clearCall(prefs)

                Toast.makeText(
                    context,
                    "مدت تماس ثبت شد: $duration ثانیه",
                    Toast.LENGTH_SHORT
                ).show()
            },

            onError = { message ->

                // در صورت خطا، اطلاعات تماس را پاک نمی‌کنیم
                // تا امکان ارسال مجدد وجود داشته باشد.

                Toast.makeText(
                    context,
                    "خطا در ثبت تماس: $message",
                    Toast.LENGTH_LONG
                ).show()
            }
        )
    }

    private fun clearCall(
        prefs: android.content.SharedPreferences
    ) {

        prefs.edit()
            .remove(KEY_CALL_ID)
            .remove(KEY_COMMUNICATION_ID)
            .remove(KEY_START_TIME)
            .remove(KEY_PHONE)
            .apply()
    }
}
