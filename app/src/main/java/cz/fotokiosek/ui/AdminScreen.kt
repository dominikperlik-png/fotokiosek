package cz.fotokiosek.ui

import android.app.Activity
import android.net.Uri
import android.os.SystemClock
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.work.WorkManager
import cz.fotokiosek.AppLog
import cz.fotokiosek.CameraController
import cz.fotokiosek.FoundPrinter
import cz.fotokiosek.IppClient
import cz.fotokiosek.Kiosk
import cz.fotokiosek.KioskSettings
import cz.fotokiosek.LogoStore
import cz.fotokiosek.MailDiagnostics
import cz.fotokiosek.PrinterDiscovery
import cz.fotokiosek.SendPhotoWorker
import cz.fotokiosek.Updater
import cz.fotokiosek.UsbPrinters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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
fun AdminScreen(
    settings: KioskSettings,
    camera: CameraController,
    pickLogo: ((Uri?) -> Unit) -> Unit,
    /** Psaní na systémové klávesnici není dotyk v aplikaci – hlásí se zvlášť, ať menu nezavře časovač nečinnosti. */
    onActivity: () -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val activity = context as Activity
    val scope = rememberCoroutineScope()
    BackHandler(onBack = onClose)

    // --- kamera ---
    var cameraSource by remember { mutableStateOf(settings.cameraSource) }
    var cameraId by remember { mutableStateOf(settings.cameraId) }
    var mirror by remember { mutableStateOf(settings.mirror) }
    var fill by remember { mutableStateOf(settings.fillScreen) }
    var canonRotation by remember { mutableIntStateOf(settings.canonRotation) }
    var canonSaveToCard by remember { mutableStateOf(settings.canonSaveToCard) }
    var shutterLead by remember { mutableStateOf(settings.shutterLeadMs.toString()) }
    // --- kiosek ---
    var welcome by remember { mutableStateOf(settings.welcomeText) }
    var countdown by remember { mutableStateOf(settings.countdown.toString()) }
    var idleTimeout by remember { mutableStateOf(settings.idleTimeout.toString()) }
    var keepDays by remember { mutableStateOf(settings.keepDays.toString()) }
    var newPin by remember { mutableStateOf("") }
    var newPin2 by remember { mutableStateOf("") }
    var pinMessage by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    var previewRotation by remember { mutableIntStateOf(settings.previewRotation) }
    var logoChoice by remember { mutableStateOf(settings.logoChoice) }
    var printerModel by remember { mutableStateOf(settings.printerModel) }
    val usbPrinters = remember { UsbPrinters.list(context) }
    // --- logo ---
    var logoOnScreen by remember { mutableStateOf(settings.logoOnScreen) }
    var logoOnPhoto by remember { mutableStateOf(settings.logoOnPhoto) }
    var logoPosition by remember { mutableStateOf(settings.logoPosition) }
    var logoSize by remember { mutableStateOf(settings.logoSize.toString()) }
    var logoMessage by remember { mutableStateOf<String?>(null) }
    // --- tisk ---
    var printEnabled by remember { mutableStateOf(settings.printEnabled) }
    var printMode by remember { mutableStateOf(settings.printMode) }
    var printerUrl by remember { mutableStateOf(settings.printerUrl) }
    var printerName by remember { mutableStateOf(settings.printerName) }
    var printMaxCopies by remember { mutableStateOf(settings.printMaxCopies.toString()) }
    var printerTest by remember { mutableStateOf<String?>(null) }
    val foundPrinters = remember { mutableStateListOf<FoundPrinter>() }
    var searching by remember { mutableStateOf(false) }
    // --- e-mail ---
    var host by remember { mutableStateOf(settings.smtpHost) }
    var port by remember { mutableStateOf(settings.smtpPort.toString()) }
    var security by remember { mutableStateOf(settings.smtpSecurity) }
    var user by remember { mutableStateOf(settings.smtpUser) }
    var password by remember { mutableStateOf(settings.smtpPassword) }
    var trustAll by remember { mutableStateOf(settings.smtpTrustAll) }
    var fromAddress by remember { mutableStateOf(settings.fromAddress) }
    var fromName by remember { mutableStateOf(settings.fromName) }
    var replyTo by remember { mutableStateOf(settings.replyTo) }
    var subject by remember { mutableStateOf(settings.subject) }
    var body by remember { mutableStateOf(settings.body) }
    var mailTesting by remember { mutableStateOf(false) }
    var mailReport by remember { mutableStateOf<MailDiagnostics.Report?>(null) }
    var showProtocol by remember { mutableStateOf(false) }
    // --- aktualizace ---
    var githubRepo by remember { mutableStateOf(settings.githubRepo) }
    var githubToken by remember { mutableStateOf(settings.githubToken) }
    var latest by remember { mutableStateOf<Updater.Release?>(null) }
    // --- ostatní ---
    var pending by remember { mutableStateOf<Int?>(null) }
    var confirmRemoveOwner by remember { mutableStateOf(false) }
    var showLog by remember { mutableStateOf(false) }
    var logText by remember { mutableStateOf("") }
    val cameras = remember { camera.android.cameras() }
    val usbWebcams = remember { camera.usbWebcams() }
    val androidSeesExternal = remember { camera.android.hasExternal() }
    val isOwner = remember { Kiosk.isDeviceOwner(context) }
    val discovery = remember { PrinterDiscovery(context) }

    DisposableEffect(Unit) { onDispose { discovery.stop() } }

    LaunchedEffect(Unit) {
        pending = withContext(Dispatchers.IO) {
            runCatching {
                WorkManager.getInstance(context).getWorkInfosByTag(SendPhotoWorker.TAG).get().count { !it.state.isFinished }
            }.getOrNull()
        }
        // Tichá kontrola, jestli je na GitHubu novější verze
        if (!Updater.busy) {
            latest = withContext(Dispatchers.IO) { runCatching { Updater.latest(settings) }.getOrNull() }
        }
    }

    fun save() {
        settings.cameraSource = cameraSource
        settings.cameraId = cameraId
        settings.mirror = mirror
        settings.fillScreen = fill
        settings.canonRotation = canonRotation
        settings.canonSaveToCard = canonSaveToCard
        settings.shutterLeadMs = shutterLead.toIntOrNull()?.coerceIn(0, 900) ?: 250
        settings.welcomeText = welcome
        settings.countdown = countdown.toIntOrNull()?.coerceIn(0, 10) ?: 3
        settings.idleTimeout = idleTimeout.toIntOrNull()?.coerceIn(10, 600) ?: 45
        settings.keepDays = keepDays.toIntOrNull()?.coerceIn(1, 365) ?: 7
        settings.previewRotation = previewRotation
        settings.printerModel = printerModel
        settings.logoOnScreen = logoOnScreen
        settings.logoOnPhoto = logoOnPhoto
        settings.logoPosition = logoPosition
        settings.logoSize = logoSize.toIntOrNull()?.coerceIn(5, 60) ?: 18
        settings.printEnabled = printEnabled
        settings.printMode = printMode
        settings.printerUrl = IppClient.normalize(printerUrl)
        settings.printerName = printerName
        settings.printMaxCopies = printMaxCopies.toIntOrNull()?.coerceIn(1, 20) ?: 5
        settings.smtpHost = host.trim()
        settings.smtpPort = port.toIntOrNull() ?: 465
        settings.smtpSecurity = security
        settings.smtpUser = user.trim()
        settings.smtpPassword = password
        settings.smtpTrustAll = trustAll
        settings.fromAddress = fromAddress.trim()
        settings.fromName = fromName
        settings.replyTo = replyTo.trim()
        settings.subject = subject
        settings.body = body
        settings.githubRepo = githubRepo.trim()
        settings.githubToken = githubToken.trim()
        camera.applySettings()
        AppLog.i("Správa", "Nastavení uloženo")
    }

    fun testMail(send: Boolean) {
        save()
        mailTesting = true
        mailReport = null
        val to = settings.fromAddress.ifEmpty { settings.smtpUser }
        scope.launch {
            mailReport = withContext(Dispatchers.IO) { MailDiagnostics.run(settings, if (send && to.isNotEmpty()) to else null) }
            mailTesting = false
        }
    }

    fun runUpdate() {
        save()
        Updater.busy = true
        Updater.progress = 0f
        Updater.status = "Zjišťuji nejnovější verzi…"
        scope.launch {
            try {
                val release = withContext(Dispatchers.IO) { Updater.latest(settings) }
                latest = release
                if (release.versionCode <= Updater.currentVersion) {
                    Updater.status = "Máš nejnovější verzi (${Updater.currentName})."
                    Updater.busy = false
                    return@launch
                }
                Updater.status = "Stahuji ${release.title}…"
                val apk = withContext(Dispatchers.IO) {
                    Updater.download(context, settings, release) { p -> scope.launch(Dispatchers.Main) { Updater.progress = p } }
                }
                Updater.status = "Instaluji… aplikace se za chvíli sama restartuje."
                withContext(Dispatchers.IO) { Updater.install(context, apk) }
            } catch (e: Exception) {
                AppLog.e("Aktualizace", "Aktualizace selhala", e)
                Updater.status = "Aktualizace selhala: ${e.message}"
                Updater.busy = false
            }
        }
    }

    CompositionLocalProvider(LocalAdminActivity provides onActivity) {
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
                    append("Verze: ${Updater.currentName}\n")
                    append("Správce zařízení (zamčený kiosek): ${if (isOwner) "ano" else "NE"}\n")
                    append("${camera.statusLine}\n")
                    append("Ve frontě k odeslání: ${pending ?: "?"}   Odesláno celkem: ${settings.sentCount}   Vytištěno: ${settings.printCount}")
                    if (settings.lastError.isNotEmpty()) append("\nPoslední chyba: ${settings.lastError}")
                    if (settings.pin == "1234") append("\n⚠ PIN je pořád výchozí 1234 – změň ho níže v sekci Kiosek (Změna PINu).")
                },
                fontSize = 18.sp,
                modifier = Modifier.fillMaxWidth().background(KeyColor, RoundedCornerShape(12.dp)).padding(16.dp),
            )

            // ---------------------------------------------------------------- Kamera
            Section("Kamera")
            Text("Zdroj obrazu", fontSize = 20.sp)
            ChoiceRow(
                listOf("auto" to "Automaticky", "android" to "Kamera Androidu", "canon" to "Zrcadlovka Canon"),
                cameraSource,
            ) { cameraSource = it }

            Row(
                Modifier.fillMaxWidth().background(KeyColor, RoundedCornerShape(12.dp)).padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier.size(18.dp).clip(CircleShape)
                        .background(if (camera.canonConnected) Color(0xFF43A047) else Color(0xFFE53935))
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("Zrcadlovka: ${camera.canonStatus}", fontSize = 18.sp)
                    if (!camera.canonConnected) {
                        Text(
                            "Fotoaparát zapni, dej do režimu foto (M/Av/Tv/P), v menu vypni Wi-Fi a automatické vypínání a připoj USB kabelem.",
                            fontSize = 15.sp,
                            color = Color.White.copy(alpha = 0.6f),
                        )
                    }
                }
                OutlinedButton(onClick = { save(); camera.reconnect() }) { Text("Znovu připojit") }
            }

            if (cameraSource != "canon") {
                Text("Kamera Androidu", fontSize = 20.sp)
                if (cameras.isEmpty()) {
                    Text("Android nevidí žádnou kameru. Pošli výstup diagnostiky (diagnostika.txt).", color = Color(0xFFFF6E6E))
                }
                Row(
                    Modifier.fillMaxWidth().clickable { cameraId = "" },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = cameraId.isEmpty(), onClick = { cameraId = "" })
                    Text("Automaticky (USB webkamera přednostně) – teď: ${camera.android.activeId.ifEmpty { "?" }}", fontSize = 20.sp)
                }
                if (usbWebcams.isNotEmpty()) {
                    Text(
                        "Připojená USB webkamera: ${usbWebcams.joinToString()}" +
                            if (androidSeesExternal) " ✔ Android ji vidí."
                            else "\n✘ Android ji ale jako kameru nenabízí. Zkus „Restartovat aplikaci“ (Systém). Když ani pak nebude v seznamu jako „externí USB“, systém tohoto automatu USB kamery nepodporuje – pošli mi diagnostika.txt.",
                        fontSize = 16.sp,
                        color = if (androidSeesExternal) Color(0xFF81C784) else Color(0xFFFF6E6E),
                    )
                }
                cameras.forEach { (id, label) ->
                    Row(
                        Modifier.fillMaxWidth().clickable { cameraId = id },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = id == cameraId, onClick = { cameraId = id })
                        Text(label, fontSize = 20.sp)
                    }
                }
            }
            if (cameraSource != "android") {
                Text("Otočení obrazu zrcadlovky", fontSize = 20.sp)
                ChoiceRow(listOf(0, 90, 180, 270).map { it.toString() to "$it°" }, canonRotation.toString()) { canonRotation = it.toInt() }
                SwitchRow("Ukládat fotky i na SD kartu ve fotoaparátu", canonSaveToCard) { canonSaveToCard = it }
                Text(
                    "Vypnuto = fotka jde z paměti fotoaparátu rovnou do aplikace (nejrychlejší, karta není potřeba). " +
                        "Zapnuto = zůstane i záloha na kartě, ale přenos trvá déle. Po změně klepni na „Znovu připojit“.",
                    fontSize = 15.sp,
                    color = Color.White.copy(alpha = 0.6f),
                )
            }
            if (cameraSource != "canon") {
                Text("Otočení živého náhledu vestavěné kamery", fontSize = 20.sp)
                ChoiceRow(listOf(0, 90, 180, 270).map { it.toString() to "$it°" }, previewRotation.toString()) {
                    previewRotation = it.toInt()
                    camera.android.previewRotation = previewRotation // hned vidět za menu po zavření
                }
                Text(
                    "Otáčí jen obraz na obrazovce před focením, fotka zůstává, jak je. Vyber hodnotu, při které je náhled stejně jako výsledná fotka. USB webkamery se neotáčí.",
                    fontSize = 15.sp,
                    color = Color.White.copy(alpha = 0.6f),
                )
            }
            SwitchRow("Zrcadlit (jako zrcadlo)", mirror) { mirror = it }
            SwitchRow("Náhled přes celou obrazovku (fotka se ořízne stejně)", fill) { fill = it }
            Field("Předstih spouště v ms (0–900)", shutterLead, number = true) { shutterLead = it.filter(Char::isDigit) }
            Text(
                "Spoušť se pustí o tolik dřív, aby fotka vznikla přesně s nápisem „Úsměv!“." +
                    (camera.android.lastLatencyMs.takeIf { it > 0 }?.let { " Poslední naměřená prodleva kamery: $it ms." } ?: ""),
                fontSize = 15.sp,
                color = Color.White.copy(alpha = 0.6f),
            )

            // ---------------------------------------------------------------- Kiosek
            Section("Kiosek")
            Field("Uvítací text", welcome) { welcome = it }
            Field("Odpočet (sekundy)", countdown, number = true) { countdown = it }
            Field("Návrat na úvod po nečinnosti (sekundy)", idleTimeout, number = true) { idleTimeout = it }
            Field("Mazat fotky z kiosku po (dnech)", keepDays, number = true) { keepDays = it }

            Text("Změna PINu správce", fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Field("Nový PIN (4–8 číslic)", newPin, number = true, password = true) { newPin = it.filter(Char::isDigit).take(8); pinMessage = null }
            Field("Nový PIN znovu", newPin2, number = true, password = true) { newPin2 = it.filter(Char::isDigit).take(8); pinMessage = null }
            Button(onClick = {
                pinMessage = when {
                    newPin.length !in 4..8 -> "PIN musí mít 4 až 8 číslic." to false
                    newPin != newPin2 -> "PINy se neshodují." to false
                    newPin == settings.pin -> "Tohle už je současný PIN." to false
                    !settings.savePin(newPin) -> "PIN se nepodařilo uložit, zkus to znovu." to false
                    else -> {
                        AppLog.i("Správa", "PIN správce změněn")
                        newPin = ""
                        newPin2 = ""
                        "PIN byl úspěšně změněn ✔ Platí hned – při příštím vstupu do menu zadej nový PIN." to true
                    }
                }
            }) { Text("Změnit PIN") }
            pinMessage?.let { (text, ok) ->
                Text(text, fontSize = 18.sp, color = if (ok) Color(0xFF81C784) else Color(0xFFFF6E6E))
            }

            // ---------------------------------------------------------------- Logo
            Section("Logo")
            val logoVersion = LogoStore.version
            val hasCustom = remember(logoVersion) { LogoStore.exists(context) }
            val choices = buildList {
                add("none" to "Bez loga")
                LogoStore.builtins.forEach { add(it.id to it.label) }
                if (hasCustom) add("custom" to "Vlastní (nahrané)")
            }
            Text("Vyber logo (projeví se hned na obrazovce i na dalších fotkách)", fontSize = 18.sp)
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                choices.forEach { (id, label) ->
                    // Načíst na pozadí a zmenšené – velké PNG by menu na pomalé desce zaseklo.
                    val thumb = androidx.compose.runtime.produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, id, logoVersion) {
                        value = withContext(Dispatchers.IO) { LogoStore.thumbnail(context, id)?.asImageBitmap() }
                    }.value
                    val selected = (if (logoChoice == "custom" && !hasCustom) "none" else logoChoice) == id
                    Column(
                        Modifier
                            .width(170.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(if (selected) Accent else KeyColor)
                            .clickable {
                                logoChoice = id
                                LogoStore.select(context, id)
                            }
                            .padding(10.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Box(
                            Modifier.fillMaxWidth().height(90.dp).background(Color(0xFF555555), RoundedCornerShape(8.dp)).padding(6.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (thumb != null) {
                                Image(bitmap = thumb, contentDescription = label, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
                            } else {
                                Text("—", fontSize = 28.sp, color = Color.White.copy(alpha = 0.6f))
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(label, fontSize = 16.sp, color = if (selected) Color.Black else Color.White, textAlign = TextAlign.Center)
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = {
                    logoMessage = null
                    pickLogo { uri ->
                        if (uri != null) {
                            scope.launch {
                                val r = withContext(Dispatchers.IO) { LogoStore.import(context, uri) }
                                r.onSuccess { logoChoice = "custom" }
                                logoMessage = r.fold({ "Vlastní logo uloženo a vybráno ✔" }, { "Logo nejde načíst: ${it.message}" })
                            }
                        }
                    }
                }) { Text(if (hasCustom) "Nahradit vlastní logo" else "Nahrát vlastní logo") }
                if (hasCustom) {
                    OutlinedButton(onClick = {
                        LogoStore.delete(context)
                        if (logoChoice == "custom") logoChoice = "none"
                        logoMessage = "Vlastní logo smazáno"
                    }) { Text("Smazat vlastní logo") }
                }
            }
            Text(
                "PNG s průhledností, JPG nebo SVG. Otevře se výběr souboru Androidu (např. z USB flashky nebo Stažených souborů). Logo jde nahrát i instalací: soubor logo.png vedle instalace.ps1.",
                fontSize = 15.sp,
                color = Color.White.copy(alpha = 0.6f),
            )
            logoMessage?.let { Text(it, fontSize = 18.sp) }
            SwitchRow("Logo na úvodní obrazovce", logoOnScreen) { logoOnScreen = it }
            SwitchRow("Logo na fotce (e-mail i tisk)", logoOnPhoto) { logoOnPhoto = it }
            if (logoOnPhoto) {
                Text("Umístění na fotce", fontSize = 20.sp)
                ChoiceRow(
                    listOf(
                        "TL" to "vlevo nahoře", "TC" to "nahoře uprostřed", "TR" to "vpravo nahoře",
                        "BL" to "vlevo dole", "BC" to "dole uprostřed", "BR" to "vpravo dole",
                    ),
                    logoPosition,
                ) { logoPosition = it }
                Field("Velikost loga (% šířky fotky, 5–60)", logoSize, number = true) { logoSize = it.filter(Char::isDigit) }
            }

            // ---------------------------------------------------------------- Tisk
            Section("Tisk")
            SwitchRow("Povolit tisk (tlačítko „Vytisknout“ u fotky)", printEnabled) { printEnabled = it }
            if (printEnabled) {
                Text("Model tiskárny (formát vždy 10×15 cm, 300 DPI, bez okrajů)", fontSize = 20.sp)
                ChoiceRow(
                    listOf("selphy_cp1500" to "Canon SELPHY CP1500", "dnp_qw410" to "DNP DP-QW410", "generic" to "Obecná 10×15"),
                    printerModel,
                ) { printerModel = it }
                Text(
                    when (printerModel) {
                        "selphy_cp1500" -> "SELPHY: papír KP-108IN (100×148 mm). Nejspolehlivější je připojit ji k Wi-Fi a zvolit „Síťová tiskárna“ – tiskne pak hned, bez dialogů."
                        "dnp_qw410" -> "DNP DP-QW410 má jen USB a Android pro ni nemá ovladač. Funguje přes tiskový server (např. Raspberry Pi s CUPS) jako „Síťová tiskárna“, nebo přes tiskovou službu Androidu, pokud ji výrobce nabízí."
                        else -> "Fotka se připraví jako 4×6″ (1800×1200 px) a vyplní celý papír."
                    },
                    fontSize = 15.sp,
                    color = Color.White.copy(alpha = 0.6f),
                )
                if (usbPrinters.isNotEmpty()) {
                    Text(
                        "Přes USB je připojená tiskárna: ${usbPrinters.joinToString()}. Android na ni umí tisknout jen přes tiskovou službu – zvol „Systémový tisk Androidu“.",
                        fontSize = 16.sp,
                        color = Accent,
                    )
                }
                ChoiceRow(
                    listOf("ipp" to "Síťová tiskárna (doporučeno)", "system" to "Systémový tisk Androidu (i USB)"),
                    printMode,
                ) { printMode = it }
                if (printMode == "ipp") {
                    Text(
                        "Tiskárna musí být ve stejné síti jako automat (Wi-Fi / kabel). Tiskne se rovnou, bez dialogů.",
                        fontSize = 15.sp,
                        color = Color.White.copy(alpha = 0.6f),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedButton(onClick = {
                            foundPrinters.clear()
                            searching = true
                            discovery.start { p ->
                                scope.launch(Dispatchers.Main) { if (foundPrinters.none { it.url == p.url }) foundPrinters += p }
                            }
                            scope.launch {
                                delay(8000)
                                discovery.stop()
                                searching = false
                            }
                        }, enabled = !searching) { Text(if (searching) "Hledám…" else "Vyhledat tiskárny") }
                        OutlinedButton(onClick = {
                            printerTest = "Zkouším tiskárnu…"
                            val url = IppClient.normalize(printerUrl)
                            scope.launch {
                                val r = withContext(Dispatchers.IO) { IppClient.test(url) }
                                printerTest = (if (r.ok) "✔ " else "✘ ") + r.message
                            }
                        }, enabled = printerUrl.isNotBlank()) { Text("Otestovat tiskárnu") }
                    }
                    if (searching && foundPrinters.isEmpty()) Text("Hledám tiskárny v síti…", fontSize = 16.sp)
                    if (!searching && foundPrinters.isEmpty() && printerName.isEmpty() && printerUrl.isEmpty()) {
                        Text("Žádnou tiskárnu jsem zatím nenašel – zadej její IP adresu ručně.", fontSize = 16.sp)
                    }
                    foundPrinters.forEach { p ->
                        Row(
                            Modifier.fillMaxWidth().clickable { printerUrl = p.url; printerName = p.name },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = printerUrl == p.url, onClick = { printerUrl = p.url; printerName = p.name })
                            Column {
                                Text(p.name, fontSize = 20.sp)
                                Text(p.url, fontSize = 14.sp, color = Color.White.copy(alpha = 0.6f))
                            }
                        }
                    }
                    if (printerName.isNotEmpty()) Text("Vybraná tiskárna: $printerName", fontSize = 18.sp)
                    Field("Adresa tiskárny (IP nebo ipp://…)", printerUrl) { printerUrl = it; printerName = "" }
                    printerTest?.let { Text(it, fontSize = 18.sp) }
                } else {
                    Text(
                        "Otevře se tiskový dialog Androidu. Tiskárna musí být přidaná přes tiskovou službu (Nastavení Androidu → Tisk, např. Mopria nebo aplikace výrobce tiskárny).",
                        fontSize = 15.sp,
                        color = Color.White.copy(alpha = 0.6f),
                    )
                }
                Field("Max. počet kopií pro zákazníka", printMaxCopies, number = true) { printMaxCopies = it.filter(Char::isDigit) }
            }

            // ---------------------------------------------------------------- E-mail
            Section("E-mail (SMTP)")
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = { host = "smtp.gmail.com"; port = "465"; security = "SSL" }) { Text("Gmail") }
                OutlinedButton(onClick = { host = "smtp.seznam.cz"; port = "465"; security = "SSL" }) { Text("Seznam") }
                OutlinedButton(onClick = { host = "smtp-relay.brevo.com"; port = "587"; security = "STARTTLS" }) { Text("Brevo") }
                OutlinedButton(onClick = { host = ""; port = "587"; security = "STARTTLS" }) { Text("Vlastní / firemní") }
            }
            Text(
                "Firemní e-mail: server, port a zabezpečení najdeš u svého poskytovatele (např. smtp.firma.cz, M365: smtp.office365.com 587 STARTTLS). " +
                    "Adresa odesílatele musí být schránka, do které se přihlašuješ, nebo její povolený alias – jinak server e-mail odmítne nebo skončí ve spamu.",
                fontSize = 15.sp,
                color = Color.White.copy(alpha = 0.6f),
            )
            Field("SMTP server", host) { host = it }
            Field("Port (465 = SSL, 587 = STARTTLS)", port, number = true) {
                port = it.filter(Char::isDigit)
                when (port) {
                    "465" -> security = "SSL"
                    "587", "25", "2525" -> security = "STARTTLS"
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                listOf("SSL", "STARTTLS", "NONE").forEach { option ->
                    RadioButton(selected = security == option, onClick = { security = option })
                    Text(if (option == "NONE") "bez šifrování" else option, modifier = Modifier.padding(end = 16.dp))
                }
            }
            Field("Uživatel (přihlašovací e-mail)", user) { user = it }
            Field("Heslo (u Gmailu „heslo pro aplikace“)", password, password = true) { password = it }
            Field("Adresa odesílatele (prázdné = uživatel)", fromAddress) { fromAddress = it }
            Field("Jméno odesílatele (např. název firmy)", fromName) { fromName = it }
            Field("Odpovědi posílat na (prázdné = adresa odesílatele)", replyTo) { replyTo = it }
            Field("Předmět", subject) { subject = it }
            Field("Text e-mailu", body, singleLine = false) { body = it }
            SwitchRow("Tolerovat neověřený certifikát (jen pro staré Androidy)", trustAll) { trustAll = it }

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = { testMail(send = false) }, enabled = !mailTesting) { Text("Otestovat připojení k mailu") }
                OutlinedButton(onClick = { testMail(send = true) }, enabled = !mailTesting) { Text("…a poslat testovací e-mail") }
            }
            if (mailTesting) Text("Testuji připojení…", fontSize = 18.sp)
            mailReport?.let { report ->
                Column(
                    Modifier.fillMaxWidth().background(KeyColor, RoundedCornerShape(12.dp)).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        if (report.ok) "✔ Pošta funguje" else "✘ Pošta nefunguje",
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (report.ok) Color(0xFF81C784) else Color(0xFFFF6E6E),
                    )
                    report.steps.forEach { step ->
                        Text("${if (step.ok) "✔" else "✘"} ${step.name}: ${step.detail}", fontSize = 17.sp,
                            color = if (step.ok) Color.White else Color(0xFFFF6E6E))
                        step.hint?.let { Text("→ $it", fontSize = 17.sp, color = Accent) }
                    }
                    if (report.protocolLog.isNotBlank()) {
                        TextButton(onClick = { showProtocol = !showProtocol }) {
                            Text(if (showProtocol) "Skrýt komunikaci se serverem" else "Zobrazit komunikaci se serverem")
                        }
                        if (showProtocol) Text(report.protocolLog, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
                    }
                }
            }

            // ---------------------------------------------------------------- Aktualizace
            Section("Aktualizace")
            Text("Nainstalovaná verze: ${Updater.currentName}", fontSize = 18.sp)
            latest?.let { r ->
                if (r.versionCode > Updater.currentVersion) {
                    Text("Na GitHubu je nová verze: ${r.title}", fontSize = 18.sp, color = Accent)
                    if (r.notes.isNotBlank()) Text(r.notes.take(400), fontSize = 15.sp, color = Color.White.copy(alpha = 0.7f))
                } else {
                    Text("Je nainstalovaná nejnovější verze.", fontSize = 16.sp, color = Color.White.copy(alpha = 0.6f))
                }
            }
            Button(onClick = { runUpdate() }, enabled = !Updater.busy) { Text("Aktualizovat aplikaci") }
            if (Updater.busy && Updater.progress > 0f && Updater.progress < 1f) {
                LinearProgressIndicator(progress = { Updater.progress }, modifier = Modifier.fillMaxWidth())
            }
            if (Updater.status.isNotEmpty()) Text(Updater.status, fontSize = 18.sp)
            Text(
                "Stáhne poslední vydání z GitHubu a nainstaluje ho. Nastavení, logo i tiskárna zůstanou.",
                fontSize = 15.sp,
                color = Color.White.copy(alpha = 0.6f),
            )
            Field("Repozitář na GitHubu (vlastník/název)", githubRepo) { githubRepo = it }
            Field("Token (jen pro soukromý repozitář)", githubToken, password = true) { githubToken = it }

            // ---------------------------------------------------------------- Log
            Section("Log (záznam událostí)")
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = {
                    showLog = !showLog
                    if (showLog) logText = AppLog.tail(120)
                }) { Text(if (showLog) "Skrýt log" else "Zobrazit log") }
                OutlinedButton(onClick = { AppLog.clear(); logText = "" }) { Text("Smazat log") }
            }
            if (showLog) {
                Text(
                    logText.ifEmpty { "(prázdný)" },
                    fontSize = 13.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.fillMaxWidth().background(Color.Black, RoundedCornerShape(8.dp)).padding(12.dp),
                )
            }

            // ---------------------------------------------------------------- Systém
            Section("Systém")
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = { Kiosk.openSystemScreen(activity, Settings.ACTION_WIFI_SETTINGS) }) { Text("Wi-Fi") }
                OutlinedButton(onClick = { Kiosk.openSystemScreen(activity, Settings.ACTION_SETTINGS) }) { Text("Nastavení Androidu") }
                OutlinedButton(onClick = { save(); Kiosk.restartApp(activity) }) { Text("Restartovat aplikaci") }
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

/** Hlášení „správce právě píše“ z textových polí (viz onActivity v [AdminScreen]). */
private val LocalAdminActivity = staticCompositionLocalOf<() -> Unit> { {} }

/** Řada přepínacích tlačítek (jedna volba z několika). */
@Composable
private fun ChoiceRow(options: List<Pair<String, String>>, selected: String, onSelect: (String) -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        options.forEach { (value, label) ->
            val isSelected = value == selected
            Box(
                Modifier
                    .clip(RoundedCornerShape(24.dp))
                    .background(if (isSelected) Accent else KeyColor)
                    .clickable { onSelect(value) }
                    .padding(horizontal = 18.dp, vertical = 12.dp),
            ) {
                Text(label, fontSize = 18.sp, color = if (isSelected) Color.Black else Color.White)
            }
        }
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
    val typing = LocalAdminActivity.current
    OutlinedTextField(
        value = value,
        onValueChange = { typing(); onChange(it) },
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
