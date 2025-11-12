package com.somedeveloper.smsforwarder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import android.telephony.SmsManager
import android.telephony.SmsMessage
import android.provider.Telephony
import android.util.Log

@Suppress("DEPRECATION")
class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Log.d(TAG, "onReceive called with intent: ${intent.action}")
        try {
            if (intent.action == Telephony.Sms.Intents.SMS_RECEIVED_ACTION || intent.action == "android.provider.Telephony.SMS_RECEIVED") {
                Log.d(TAG, "SMS_RECEIVED_ACTION detected")

                // Ensure foreground service is running so notification is visible while processing/forwarding
                try {
                    // Check notification permission on Android 13+
                    val hasNotif = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
                    } else true

                    if (!hasNotif) {
                        Log.w(TAG, "POST_NOTIFICATIONS not granted; notification may not be shown")
                    }

                    val svcIntent = Intent(context, ForwardingService::class.java)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        context.startForegroundService(svcIntent)
                    } else {
                        context.startService(svcIntent)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Could not start ForwardingService: ${e.message}")
                }

                val messages: Array<SmsMessage> = Telephony.Sms.Intents.getMessagesFromIntent(intent)
                Log.d(TAG, "Number of SMS messages received: ${messages.size}")
                if (messages.isEmpty()) {
                    Log.d(TAG, "No SMS messages found in the intent")
                    return
                }

                // We'll forward each message (most devices deliver parts separately but getMessagesFromIntent handles concatenation in many cases)
                for (message in messages) {
                    val origin = message.originatingAddress ?: "Unknown"
                    val body = message.messageBody ?: ""
                    Log.d(TAG, "Processing SMS from: $origin, body: ${body.take(50)}")

                    val prefs = context.getSharedPreferences("sms_prefs", Context.MODE_PRIVATE)
                    val input = prefs.getString("input_number", "") ?: ""
                    val output = prefs.getString("output_number", "") ?: ""

                    val normOrigin = normalizeNumber(origin)
                    Log.d(TAG, "Normalized sender number: $normOrigin")

                    // Check if the sender matches the input filter or a stored contact code
                    val contactCode = prefs.getString("contact_code", "") ?: ""
                    val matchesContactCode = contactCode.isNotBlank() && normOrigin.contains(contactCode)
                    Log.d(TAG, "Contact code $contactCode match: $matchesContactCode")

                    // If no input filter set -> forward all. Otherwise, only forward matching sender numbers or contact codes.
                    if (input.isBlank() || normalizeNumber(input) == normOrigin || matchesContactCode) {
                        if (output.isBlank()) {
                            Log.w(TAG, "Output number not configured; skipping forward")
                            continue
                        }

                        try {
                            val smsManager = SmsManager.getDefault()
                            if (body.length > 160) {
                                val parts = smsManager.divideMessage(body)
                                smsManager.sendMultipartTextMessage(output, null, parts, null, null)
                            } else {
                                smsManager.sendTextMessage(output, null, body, null, null)
                            }
                            Log.i(TAG, "Forwarded SMS from $origin to $output: ${body.take(80)}")
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to forward SMS to $output: ${e.message}", e)
                        }
                    } else {
                        Log.d(TAG, "Received SMS from $origin does not match filter or contact code")
                    }
                }
            } else {
                Log.d(TAG, "Intent action does not match SMS_RECEIVED_ACTION")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in SmsReceiver: ${e.message}", e)
        }
    }

    private fun normalizeNumber(n: String): String = n.filter { it.isDigit() || it == '+' }

    companion object {
        private const val TAG = "SmsReceiver"
    }
}
