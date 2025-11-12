package com.somedeveloper.smsforwarder

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.ContactsContract
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.tooling.preview.Preview
import androidx.core.content.ContextCompat
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import com.somedeveloper.smsforwarder.ui.theme.SmsForwarderTheme
import android.provider.Telephony
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.core.content.edit
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp

class MainActivity : ComponentActivity() {
    private val PREFS = "sms_prefs"
    private val KEY_INPUT = "input_number"
    private val KEY_OUTPUT = "output_number"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Start the foreground forwarding service so a persistent notification is shown
        try {
            val svc = android.content.Intent(this, ForwardingService::class.java)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                startForegroundService(svc)
            } else {
                startService(svc)
            }
        } catch (_: Exception) {
            // ignore if service cannot be started yet
        }

        setContent {
            SmsForwarderTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    MainContent(
                        context = this,
                        prefsName = PREFS,
                        keyInput = KEY_INPUT,
                        keyOutput = KEY_OUTPUT
                    )
                }
            }
        }
    }
}

// Add lastMessage to ChatItem so UI can show a one-line preview
data class ChatItem(val name: String?, val number: String, val lastMessage: String?)

private fun normalizeNumber(n: String): String = n.filter { it.isDigit() || it == '+' }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainContent(
    context: Context,
    prefsName: String,
    keyInput: String,
    keyOutput: String
) {
    val prefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
    // Stored input is normalized; load as-is
    var inputNumber by remember { mutableStateOf(prefs.getString(keyInput, "") ?: "") }
    var outputNumber by remember { mutableStateOf(prefs.getString(keyOutput, "") ?: "") }

    // Permission states
    var hasReceive by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.RECEIVE_SMS) == PackageManager.PERMISSION_GRANTED) }
    var hasSend by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED) }
    var hasReadSms by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED) }
    var hasContacts by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED) }
    // Android 13+ requires POST_NOTIFICATIONS runtime permission
    var hasPostNotifications by remember { mutableStateOf(if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED else true) }

    // Permission launcher
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        hasReceive = ContextCompat.checkSelfPermission(context, Manifest.permission.RECEIVE_SMS) == PackageManager.PERMISSION_GRANTED
        hasSend = ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED
        hasReadSms = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED
        hasContacts = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED
        hasPostNotifications = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED else true
    }

    // Dedicated launcher for POST_NOTIFICATIONS so user can grant it separately if needed
    val postNotifLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted: Boolean ->
        hasPostNotifications = granted
    }

    // Request required permissions at startup
    LaunchedEffect(Unit) {
        // Build permission list and include POST_NOTIFICATIONS for Android 13+
        val perms = mutableListOf<String>(Manifest.permission.RECEIVE_SMS, Manifest.permission.SEND_SMS, Manifest.permission.READ_SMS, Manifest.permission.READ_CONTACTS)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) perms.add(android.Manifest.permission.POST_NOTIFICATIONS)
        permissionLauncher.launch(perms.toTypedArray())
    }

    // Ensure the foreground service (notification) is started/restarted after notification permission granted
    LaunchedEffect(hasPostNotifications) {
        if (hasPostNotifications) {
            try {
                val svc = android.content.Intent(context, ForwardingService::class.java)
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    context.startForegroundService(svc)
                } else {
                    context.startService(svc)
                }
            } catch (_: Exception) {
            }
        }
    }

    // Chats loaded from SMS provider
    var chats by remember { mutableStateOf<List<ChatItem>>(emptyList()) }

    // Load chats after READ_SMS granted or when contacts permission changes (to resolve names)
    LaunchedEffect(hasReadSms, hasContacts) {
        if (hasReadSms) {
            val loaded = withContext(Dispatchers.IO) {
                val resolver = context.contentResolver
                val seen = linkedSetOf<String>()
                val result = mutableListOf<ChatItem>()
                // query body as well and order by date desc so the first hit per address is the latest message
                val uris = listOf(Telephony.Sms.Inbox.CONTENT_URI, Telephony.Sms.Sent.CONTENT_URI)
                for (uri in uris) {
                    val cursor = resolver.query(uri, arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE), null, null, "date DESC")
                    cursor?.use {
                        while (it.moveToNext() && seen.size < 100) {
                            val addr = it.getString(0) ?: continue
                            if (addr.isBlank()) continue
                            if (seen.add(addr)) {
                                var name: String? = null
                                if (hasContacts) {
                                    name = lookupContactName(resolver, addr)
                                }
                                val body = try { it.getString(1) } catch (_: Exception) { null }
                                result.add(ChatItem(name = name, number = addr, lastMessage = body))
                            }
                        }
                    }
                }
                result
            }
            chats = loaded
        }
    }

    // UI selection state
    // `showDialog` controls the chat selector dialog; `expanded` removed

    // Compute display text for selected input (match by normalized number)
    val selectedChat = chats.find { normalizeNumber(it.number) == inputNumber }
    val displayText = when {
        selectedChat != null && !selectedChat.name.isNullOrBlank() -> "${selectedChat.name} (${selectedChat.number})"
        inputNumber.isNotBlank() -> inputNumber
        else -> "Select chat..."
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(text = "SMS Forwarder", modifier = Modifier.padding(bottom = 8.dp))

        Text(text = "Input (sender) — select a chat to filter by number (only number is stored):")
        // Use a clickable overlay so the dialog reliably opens even if TextField eats clicks
        var showDialog by remember { mutableStateOf(false) }

        Box(modifier = Modifier
            .padding(top = 4.dp)
            .fillMaxWidth(0.9f)
        ) {
            OutlinedTextField(
                value = displayText,
                onValueChange = {},
                readOnly = true,
                modifier = Modifier.fillMaxWidth()
            )
            // Transparent clickable overlay ensures taps open the dialog
            Box(modifier = Modifier
                .matchParentSize()
                .clickable { showDialog = true }
            ) {}
        }

        if (showDialog) {
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { showDialog = false },
                title = { Text("Select chat") },

                text = {
                    if (chats.isEmpty()) {
                        Text("No chats available or permission missing")
                    } else {
                        LazyColumn(modifier = Modifier.height(240.dp)) {
                            items(chats) { chat ->
                                Column(modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        val normalized = normalizeNumber(chat.number)
                                        inputNumber = normalized
                                        prefs.edit { putString(keyInput, inputNumber) }
                                        showDialog = false
                                    }
                                    .padding(8.dp)
                                ) {
                                    Text(text = chat.name?.let { "$it (${chat.number})" } ?: chat.number, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(text = chat.lastMessage ?: "", maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 13.sp)
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    Button(onClick = { showDialog = false }) { Text("Close") }
                }
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        Text(text = "Output phone number (destination):")
        // Provide the same chat-selection UI for output as we did for input
        val selectedChatOutput = chats.find { normalizeNumber(it.number) == outputNumber }
        val displayTextOutput = when {
            selectedChatOutput != null && !selectedChatOutput.name.isNullOrBlank() -> "${selectedChatOutput.name} (${selectedChatOutput.number})"
            outputNumber.isNotBlank() -> outputNumber
            else -> "Select chat or type number..."
        }

        var showDialogOutput by remember { mutableStateOf(false) }

        Box(modifier = Modifier
            .padding(top = 4.dp)
            .fillMaxWidth(0.9f)
        ) {
            OutlinedTextField(
                value = displayTextOutput,
                onValueChange = { /* keep readOnly-like behaviour: user can type via dialog or Save button */ },
                readOnly = true,
                modifier = Modifier.fillMaxWidth()
            )
            Box(modifier = Modifier
                .matchParentSize()
                .clickable { showDialogOutput = true }
            ) {}
        }

        if (showDialogOutput) {
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { showDialogOutput = false },
                title = { Text("Select chat for output") },
                text = {
                    if (chats.isEmpty()) {
                        Text("No chats available or permission missing")
                    } else {
                        LazyColumn(modifier = Modifier.height(240.dp)) {
                            items(chats) { chat ->
                                Column(modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        val normalized = normalizeNumber(chat.number)
                                        outputNumber = normalized
                                        prefs.edit { putString(keyOutput, outputNumber) }
                                        showDialogOutput = false
                                    }
                                    .padding(8.dp)
                                ) {
                                    Text(text = chat.name?.let { "$it (${chat.number})" } ?: chat.number, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(text = chat.lastMessage ?: "", maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 13.sp)
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    Button(onClick = { showDialogOutput = false }) { Text("Close") }
                }
            )
        }

        // Manual editable input field (auto-saves normalized on change)
        OutlinedTextField(
            value = inputNumber,
            onValueChange = { raw ->
                val normalized = normalizeNumber(raw)
                inputNumber = normalized
                prefs.edit { putString(keyInput, inputNumber) }
            },
            modifier = Modifier
                .padding(top = 8.dp)
                .fillMaxWidth(0.9f),
            label = { Text("Or type input number manually") }
        )

        Spacer(modifier = Modifier.height(8.dp))

        // Manual editable output field (auto-saves normalized on change)
        OutlinedTextField(
            value = outputNumber,
            onValueChange = { raw ->
                val normalized = normalizeNumber(raw)
                outputNumber = normalized
                prefs.edit { putString(keyOutput, outputNumber) }
            },
            modifier = Modifier
                .padding(top = 8.dp)
                .fillMaxWidth(0.9f),
            label = { Text("Or type output number manually") }
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(text = "Permissions:")
        Text(text = "RECEIVE_SMS: ${if (hasReceive) "Granted" else "Missing"}")
        Text(text = "SEND_SMS: ${if (hasSend) "Granted" else "Missing"}")
        Text(text = "READ_SMS: ${if (hasReadSms) "Granted" else "Missing"}")
        Text(text = "READ_CONTACTS: ${if (hasContacts) "Granted" else "Missing"}")

        Spacer(modifier = Modifier.height(8.dp))

        // Show request-permissions button only when at least one required permission is missing
        val requirePost = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU
        val allGranted = hasReceive && hasSend && hasReadSms && hasContacts && (!requirePost || hasPostNotifications)
        if (!allGranted) {
            Button(onClick = {
                // Request permissions manually as well
                val perms = mutableListOf<String>(Manifest.permission.RECEIVE_SMS, Manifest.permission.SEND_SMS, Manifest.permission.READ_SMS, Manifest.permission.READ_CONTACTS)
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) perms.add(android.Manifest.permission.POST_NOTIFICATIONS)
                permissionLauncher.launch(perms.toTypedArray())
            }) {
                Text("Request Permissions")
            }
        }

        // Show explicit notification permission state and allow request separately
        if (requirePost) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(text = "Notifications: ${if (hasPostNotifications) "Granted" else "Missing"}")
            if (!hasPostNotifications) {
                Button(onClick = { postNotifLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS) }) {
                    Text("Enable Notifications")
                }
            }
        }

        // Debug helper: allow manually restarting the foreground service to force the notification to appear
        Spacer(modifier = Modifier.height(8.dp))
        Button(onClick = {
            try {
                val svc = android.content.Intent(context, ForwardingService::class.java)
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    context.startForegroundService(svc)
                } else {
                    context.startService(svc)
                }
            } catch (e: Exception) {
                // no-op
            }
        }) {
            Text("Restart service")
        }

        Spacer(modifier = Modifier.height(8.dp))

        Text(text = "Note: The app will forward incoming SMS that match the selected chat's number (or all if none selected) to the configured output number.")
    }
}

private fun lookupContactName(resolver: android.content.ContentResolver, number: String): String? {
    return try {
        val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
        val cursor = resolver.query(uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)
        cursor?.use {
            if (it.moveToFirst()) {
                return it.getString(0)
            }
        }
        null
    } catch (_: Exception) {
        null
    }
}

@Preview(showBackground = true)
@Composable
fun MainPreview() {
    SmsForwarderTheme {
        // Preview uses empty context — use placeholders
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Text(text = "SMS Forwarder Preview")
        }
    }
}