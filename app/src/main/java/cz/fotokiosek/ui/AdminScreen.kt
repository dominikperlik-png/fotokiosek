package cz.fotokiosek.ui

import android.app.Activity
import android.os.SystemClock
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.work.WorkManager
import cz.fotokiosek.Kiosk
import cz.fotokiosek.KioskCamera
import cz.fotokiosek.KioskSettings
import cz.fotokiosek.Mailer
import cz.fotokiosek.SendPhotoWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Neviditelný roh vlevo nahoře – 5 klepnutí do 3 sekund otevře zadání PINu. */
@Composable
fun AdminCorner(modifier: Modifier, onUnlock: () -> Unit) {
    val unlock by rememberUpdatedState(onUnlock)
    var taps by remember { mutableIntStateOf(0) }
    var firstTap by remember { mutableLongStateOf(0L) }
    Box(
        modifier.size(110.dp).pointerInput(Unit) {
            detectTapGestures {
                val now = SystemClock.elapsedRealtime()
                if (now - firstTap > 3000) {
                    firstTap = now
                    taps = 0
                }
                taps++
                if (taps >= 5) {
                    taps = 0
                    unlock()
                }
            }
        }
    )
}

@Composable
fun PinPad(onCancel: () -> Unit, onEnter: (String) -> Boolean) {
    var pin by remember { mutableStateOf("") }
    var wrong by remember { mutableStateOf(false) }
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.88f))
            .pointerInput(Unit) { detectTapGestures { } },
        contentAlignment = Alignment.Center,
    ) {
        Column(Modifier.width(380.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("PIN správce", fontSize = 32.sp, fontWeight = FontWeight.Bold)
            Text(if (pin.isEmpty()) " " else "•".repeat(pin.length), fontSize = 48.sp)
            Text(if (wrong) "Špatný PIN" else " ", color = Color(0xFFFF6E6E), fontSize = 22.sp)
            Spacer(Modifier.height(12.dp))
            listOf("123", "456", "789", "<0>").forEach { row ->
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    row.forEach { c ->
                        val label = when (c) {
                            '<' -> "⌫"
                            '>' -> "OK"
                            else -> c.toString()
                        }
                        Box(
                            Modifier
                                .weight(1f)
                                .height(80.dp)
                                .clip(RoundedCornerShape(16.dp))
                                .background(if (c == '>') Accent else KeyColor)
                                .clickable {
                                    when (c) {
                                        '<' -> {
                                            pin = pin.dropLast(1)
                                        }
                                        '>' -> {
                                            wrong = !onEnter(pin)
                                            pin = ""
                                        }
                                        else -> {
                                            if (pin.length < 8) pin += c
                                        }
                                    }
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(label, fontSize = 32.sp, color = if (c == '>') Color.Black else Color.White)
                        }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            BigButton("Zrušit", Modifier.fillMaxWidth(), primary = false, onClick = onCancel)
        }
    }
}

@Composable
fun AdminScreen(settings: KioskSettings, camera: KioskCamera, onClose: () -> Unit) {
    val context = LocalContext.current
    val activity = context as Activity
    val scope = rememberCoroutineScope()
    BackHandler(onBack = onClose)

    var cameraId by remember { mutableStateOf(settings.cameraId) }
    var mirror by remember { mutableStateOf(settings.mirror) }
    var fill by remember { mutableStateOf(settings.fillScreen) }
    var welcome by remember { mutableStateOf(settings.welcomeText) }
    var countdown by remember { mutableStateOf(settings.countdown.toString()) }
    var idleTimeout by remember { mutableStateOf(settings.idleTimeout.toString()) }
    var keepDays by remember { mutableStateOf(settings.keepDays.toString()) }
    var pin by remember { mutableStateOf(settings.pin) }

    var host by remember { mutableStateOf(settings.smtpHost) }
    var port by remember { mutableStateOf(settings.smtpPort.toString()) }
    var security by remember { mutableStateOf(settings.smtpSecurity) }
    var user by remember { mutableStateOf(settings.smtpUser) }
    var password by remember { mutableStateOf(settings.smtpPassword) }
    var fromAddress by remember { mutableStateOf(settings.fromAddress) }
    var fromName by remember { mutableStateOf(settings.fromName) }
    var subject by remember { mutableStateOf(settings.subject) }
    var body by remember { mutableStateOf(settings.body) }

    var pending by remember { mutableStateOf<Int?>(null) }
    var testResult by remember { mutableStateOf<String?>(null) }
    var confirmRemoveOwner by remember { mutableStateOf(false) }
    val cameras = remember { camera.cameras() }
    val isOwner = remember { Kiosk.isDeviceOwner(context) }

    LaunchedEffect(Unit) {
        pending = withContext(Dispatchers.IO) {
            runCatching {
                WorkManager.getInstance(context).getWorkInfosByTag(SendPhotoWorker.TAG).get().count { !it.state.isFinished }
            }.getOrNull()
        }
    }

    fun save() {
        settings.cameraId = cameraId
        settings.mirror = mirror
        settings.fillScreen = fill
        settings.welcomeText = welcome
        settings.countdown = countdown.toIntOrNull()?.coerceIn(0, 10) ?: 3
        settings.idleTimeout = idleTimeout.toIntOrNull()?.coerceIn(10, 600) ?: 45
        settings.keepDays = keepDays.toIntOrNull()?.coerceIn(1, 365) ?: 7
        if (pin.length >= 4) settings.pin = pin
        settings.smtpHost = host.trim()
        settings.smtpPort = port.toIntOrNull() ?: 465
        settings.smtpSecurity = security
        settings.smtpUser = user.trim()
        settings.smtpPassword = password
        settings.fromAddress = fromAddress.trim()
        settings.fromName = fromName
        settings.subject = subject
        settings.body = body

        camera.cameraId = cameraId
        camera.mirror = mirror
        camera.fill = fill
        camera.bind()
    }

    Box(Modifier.fillMaxSize().background(Dark), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier
                .widthIn(max = 900.dp)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Správa fotokiosku", fontSize = 34.sp, fontWeight = FontWeight.Bold)
            Text(
                buildString {
                    append("Správce zařízení (zamčený kiosek): ${if (isOwner) "ano" else "NE"}\n")
                    append("Kamera: ${camera.error ?: "OK (${camera.activeId})"}\n")
                    append("Ve frontě k odeslání: ${pending ?: "?"}   Odesláno celkem: ${settings.sentCount}")
                    if (settings.lastError.isNotEmpty()) append("\nPoslední chyba: ${settings.lastError}")
                },
                fontSize = 18.sp,
                modifier = Modifier.fillMaxWidth().background(KeyColor, RoundedCornerShape(12.dp)).padding(16.dp),
            )

            Section("Kamera")
            if (cameras.isEmpty()) {
                Text("Android nevidí žádnou kameru. Pošli výstup diagnostiky (diagnostika.txt).", color = Color(0xFFFF6E6E))
            }
            cameras.forEach { (id, label) ->
                Row(
                    Modifier.fillMaxWidth().clickable { cameraId = id },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = id == cameraId.ifEmpty { camera.activeId }, onClick = { cameraId = id })
                    Text(label, fontSize = 20.sp)
                }
            }
            SwitchRow("Zrcadlit (jako zrcadlo)", mirror) { mirror = it }
            SwitchRow("Náhled přes celou obrazovku (fotka se ořízne stejně)", fill) { fill = it }

            Section("Kiosek")
            Field("Uvítací text", welcome) { welcome = it }
            Field("Odpočet (sekundy)", countdown, number = true) { countdown = it }
            Field("Návrat na úvod po nečinnosti (sekundy)", idleTimeout, number = true) { idleTimeout = it }
            Field("Mazat fotky z kiosku po (dnech)", keepDays, number = true) { keepDays = it }
            Field("PIN správce (min. 4 číslice)", pin, number = true) { pin = it.filter(Char::isDigit) }

            Section("E-mail (SMTP)")
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = { host = "smtp.gmail.com"; port = "465"; security = "SSL" }) { Text("Gmail") }
                OutlinedButton(onClick = { host = "smtp.seznam.cz"; port = "465"; security = "SSL" }) { Text("Seznam") }
                OutlinedButton(onClick = { host = "smtp-relay.brevo.com"; port = "587"; security = "STARTTLS" }) { Text("Brevo") }
            }
            Field("SMTP server", host) { host = it }
            Field("Port", port, number = true) { port = it }
            Row(verticalAlignment = Alignment.CenterVertically) {
                listOf("SSL", "STARTTLS", "NONE").forEach { option ->
                    RadioButton(selected = security == option, onClick = { security = option })
                    Text(if (option == "NONE") "bez šifrování" else option, modifier = Modifier.padding(end = 16.dp))
                }
            }
            Field("Uživatel (přihlašovací e-mail)", user) { user = it }
            Field("Heslo (u Gmailu „heslo pro aplikace“)", password, password = true) { password = it }
            Field("Adresa odesílatele (prázdné = uživatel)", fromAddress) { fromAddress = it }
            Field("Jméno odesílatele", fromName) { fromName = it }
            Field("Předmět", subject) { subject = it }
            Field("Text e-mailu", body, singleLine = false) { body = it }

            Button(onClick = {
                save()
                val to = settings.fromAddress.ifEmpty { settings.smtpUser }
                testResult = "Odesílám testovací e-mail na $to…"
                scope.launch {
                    testResult = withContext(Dispatchers.IO) {
                        runCatching {
                            Mailer.send(settings, to, "Test fotokiosku", "Odesílání e-mailů z fotokiosku funguje.", null)
                            "Testovací e-mail odeslán na $to ✔"
                        }.getOrElse { "Chyba: ${it.message ?: it.javaClass.simpleName}" }
                    }
                }
            }) { Text("Uložit a poslat testovací e-mail") }
            testResult?.let { Text(it, fontSize = 18.sp) }

            Section("Systém")
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = { Kiosk.openSystemScreen(activity, Settings.ACTION_WIFI_SETTINGS) }) { Text("Wi-Fi") }
                OutlinedButton(onClick = { Kiosk.openSystemScreen(activity, Settings.ACTION_SETTINGS) }) { Text("Nastavení Androidu") }
            }
            if (isOwner) {
                OutlinedButton(onClick = { confirmRemoveOwner = true }) { Text("Odebrat správce zařízení (před odinstalací)") }
            }

            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                BigButton("Zavřít", Modifier.weight(1f), primary = false, onClick = onClose)
                BigButton("Uložit", Modifier.weight(1f)) {
                    save()
                    onClose()
                }
            }
        }
    }

    if (confirmRemoveOwner) {
        AlertDialog(
            onDismissRequest = { confirmRemoveOwner = false },
            title = { Text("Odebrat správce zařízení?") },
            text = { Text("Kiosek přestane být zamčený a aplikaci půjde odinstalovat. Zpět to jde jen přes adb.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmRemoveOwner = false
                    Kiosk.removeDeviceOwner(activity)
                    onClose()
                }) { Text("Odebrat") }
            },
            dismissButton = { TextButton(onClick = { confirmRemoveOwner = false }) { Text("Zrušit") } },
        )
    }
}

@Composable
private fun Section(title: String) {
    Text(title, color = Accent, fontSize = 24.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 16.dp))
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 20.sp, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun Field(
    label: String,
    value: String,
    number: Boolean = false,
    password: Boolean = false,
    singleLine: Boolean = true,
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = singleLine,
        keyboardOptions = KeyboardOptions(
            keyboardType = when {
                number -> KeyboardType.Number
                password -> KeyboardType.Password
                else -> KeyboardType.Text
            }
        ),
        visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
        modifier = Modifier.fillMaxWidth(),
    )
}
