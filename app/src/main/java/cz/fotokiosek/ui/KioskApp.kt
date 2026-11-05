package cz.fotokiosek.ui

import android.app.Activity
import android.graphics.Bitmap
import android.net.Uri
import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import cz.fotokiosek.AppLog
import cz.fotokiosek.CameraController
import cz.fotokiosek.IppClient
import cz.fotokiosek.KioskSettings
import cz.fotokiosek.LogoStore
import cz.fotokiosek.PhotoStore
import cz.fotokiosek.RateLimit
import cz.fotokiosek.SendPhotoWorker
import cz.fotokiosek.SystemPrint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

sealed interface Screen {
    data object Idle : Screen
    data class Countdown(val value: Int) : Screen
    data class Review(val photo: Bitmap) : Screen
    data class Email(val photo: Bitmap, val email: String = "") : Screen
    data class Confirm(val photo: Bitmap, val email: String) : Screen
    data object Sent : Screen
    data object Admin : Screen
}

/** Krátká hláška nahoře na obrazovce (červená = chyba, zelená = info). */
data class Notice(val text: String, val error: Boolean = true)

@Composable
fun KioskApp(
    settings: KioskSettings,
    camera: CameraController,
    lastTouch: State<Long>,
    pickLogo: ((Uri?) -> Unit) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var screen by remember { mutableStateOf<Screen>(Screen.Idle) }
    var notice by remember { mutableStateOf<Notice?>(null) }
    var askPin by remember { mutableStateOf(false) }
    var printFor by remember { mutableStateOf<Bitmap?>(null) }
    var printing by remember { mutableStateOf(false) }
    val flash = remember { Animatable(0f) }

    fun goIdle() {
        screen = Screen.Idle
        askPin = false
        printFor = null
    }

    fun shoot() {
        if (screen != Screen.Idle) return
        val seconds = settings.countdown.coerceIn(0, 10)
        // Obrazovku přepnout hned (ne až v korutině) – jinak dvojité klepnutí spustí dva odpočty a dvě focení.
        screen = Screen.Countdown(seconds)
        scope.launch {
            val lead = settings.shutterLeadMs.coerceIn(0, 900).toLong()
            val end = SystemClock.elapsedRealtime() + seconds * 1000L
            suspend fun waitUntil(t: Long) = delay((t - SystemClock.elapsedRealtime()).coerceAtLeast(0))

            // Výsledek focení jako Result – chyba nesmí shodit celý scope obrazovky.
            var photo: Deferred<Result<Bitmap>>? = null
            fun fire() {
                photo = async { runCatching { camera.takePhoto() } }
            }

            for (n in seconds downTo 1) {
                screen = Screen.Countdown(n)
                if (n == 1) {
                    // Zrcadlovka: namáčknout spoušť (zaostřit) chvíli před koncem
                    waitUntil(end - PREFOCUS_MS)
                    camera.prefocus()
                    // Spoušť s předstihem – kompenzuje prodlevu fotoaparátu, fotka vznikne s „Úsměv!“
                    waitUntil(end - lead)
                    fire()
                }
                waitUntil(end - (n - 1) * 1000L)
            }
            screen = Screen.Countdown(0)
            flash.snapTo(1f)
            launch { flash.animateTo(0f, tween(600)) }
            if (photo == null) fire()

            val result = photo!!.await()
            // „Úsměv!“ ať je vidět aspoň chvilku, i když je fotka hotová hned
            waitUntil(end + 500)
            result.onSuccess { raw ->
                val final = withContext(Dispatchers.Default) {
                    runCatching { LogoStore.applyToPhoto(context, settings, raw) }.getOrDefault(raw)
                }
                screen = Screen.Review(final)
            }.onFailure { e ->
                if (e is CancellationException) throw e
                // Skutečná příčina do logu i do „Poslední chyby“ v menu správce; zákazník vidí jen obecnou hlášku.
                val reason = AppLog.causes(e)
                AppLog.e("Focení", "Fotka se nepovedla (zdroj: ${camera.source}): $reason", e)
                settings.lastError = "${java.text.DateFormat.getDateTimeInstance().format(java.util.Date())}: Focení: $reason"
                notice = Notice("Fotku se nepodařilo pořídit. Zkus to prosím znovu.")
                goIdle()
            }
        }
    }

    fun send(photo: Bitmap, email: String) {
        if (!RateLimit.allow(email)) {
            notice = Notice("Na tuhle adresu už odešlo hodně fotek. Zkus to prosím později.")
            return
        }
        screen = Screen.Sent
        scope.launch(Dispatchers.IO) {
            try {
                val file = PhotoStore.save(context, photo)
                SendPhotoWorker.enqueue(context, file, email)
                AppLog.i("Fronta", "Fotka ${file.name} zařazena k odeslání")
            } catch (e: Exception) {
                settings.lastError = "Uložení fotky selhalo: ${e.message}"
                AppLog.e("Fronta", "Uložení fotky selhalo", e)
            }
        }
    }

    fun print(photo: Bitmap, copies: Int) {
        printFor = null
        val job = "Fotokiosek ${System.currentTimeMillis() / 1000}"
        if (settings.printMode == "system") {
            // Na některých deskách tiskový systém Androidu chybí – chyba nesmí shodit kiosek.
            runCatching { (context as? Activity)?.let { SystemPrint.print(it, photo, copies, job) } }
                .onSuccess { settings.printCount += copies }
                .onFailure { e ->
                    AppLog.e("Tisk", "Systémový tisk nejde spustit", e)
                    settings.lastError = "Tisk: ${e.message}"
                    notice = Notice("Tisk se nepovedl. Požádej obsluhu.")
                }
            return
        }
        if (settings.printerUrl.isBlank()) {
            notice = Notice("Tiskárna není nastavená. Požádej obsluhu.")
            return
        }
        printing = true
        notice = Notice(if (copies == 1) "Tisknu fotku…" else "Tisknu $copies kopie…", error = false)
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                val jpeg = ByteArrayOutputStream().use { out ->
                    photo.compress(Bitmap.CompressFormat.JPEG, 93, out)
                    out.toByteArray()
                }
                IppClient.print(settings.printerUrl, jpeg, copies, job)
            }
            printing = false
            if (result.ok) {
                settings.printCount += copies
                notice = Notice("Fotka se tiskne, vyzvedni si ji u tiskárny.", error = false)
            } else {
                settings.lastError = "Tisk: ${result.message}"
                notice = Notice("Tisk se nepovedl. Požádej obsluhu.")
            }
        }
    }

    // Po nečinnosti zpět na úvodní obrazovku
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            val idleSeconds = (SystemClock.elapsedRealtime() - lastTouch.value) / 1000
            val limit = if (screen == Screen.Admin) 180 else settings.idleTimeout.coerceAtLeast(10)
            if (screen != Screen.Idle && screen !is Screen.Countdown && idleSeconds >= limit) goIdle()
            if (askPin && idleSeconds >= 30) askPin = false
        }
    }
    LaunchedEffect(screen) {
        if (screen == Screen.Sent) {
            delay(8000)
            goIdle()
        }
    }
    LaunchedEffect(notice) {
        if (notice != null) {
            delay(5000)
            notice = null
        }
    }

    KioskTheme {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            // Živý náhled běží pořád, ostatní obrazovky ho překrývají.
            LivePreview(camera, settings.fillScreen)

            when (val s = screen) {
                Screen.Idle -> IdleScreen(settings, camera.error, onShoot = { shoot() })
                is Screen.Countdown -> CountdownOverlay(s.value)
                is Screen.Review -> ReviewScreen(
                    photo = s.photo,
                    canPrint = settings.printEnabled,
                    printing = printing,
                    onRetake = { goIdle() },
                    onPrint = { printFor = s.photo },
                    onSend = { screen = Screen.Email(s.photo) },
                )
                is Screen.Email -> EmailScreen(
                    initial = s.email,
                    onBack = { screen = Screen.Review(s.photo) },
                    onDone = { email -> screen = Screen.Confirm(s.photo, email) },
                )
                is Screen.Confirm -> ConfirmScreen(
                    email = s.email,
                    onEdit = { screen = Screen.Email(s.photo, s.email) },
                    onConfirm = { send(s.photo, s.email) },
                )
                Screen.Sent -> SentScreen(onDone = { goIdle() })
                Screen.Admin -> AdminScreen(settings, camera, pickLogo, onClose = { goIdle() })
            }

            if (screen != Screen.Admin) {
                AdminCorner(Modifier.align(Alignment.TopStart)) { askPin = true }
            }
            if (askPin) {
                PinPad(
                    onCancel = { askPin = false },
                    onEnter = { pin ->
                        val ok = pin == settings.pin
                        if (ok) {
                            askPin = false
                            screen = Screen.Admin
                        }
                        ok
                    },
                )
            }

            printFor?.let { photo ->
                CopiesDialog(
                    max = settings.printMaxCopies.coerceIn(1, 20),
                    onCancel = { printFor = null },
                    onConfirm = { copies -> print(photo, copies) },
                )
            }

            notice?.let {
                Text(
                    it.text,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 140.dp, start = 24.dp, end = 24.dp)
                        .background(if (it.error) Color(0xFFC62828) else Color(0xFF2E7D32), RoundedCornerShape(16.dp))
                        .padding(horizontal = 28.dp, vertical = 16.dp),
                    fontSize = 26.sp,
                    textAlign = TextAlign.Center,
                )
            }

            if (flash.value > 0f) {
                Box(Modifier.fillMaxSize().background(Color.White.copy(alpha = flash.value)))
            }
        }
    }
}

private const val PREFOCUS_MS = 800L

/**
 * Živý náhled – snímky jsou už otočené a zrcadlené stejně jako výsledná fotka
 * (viz KioskCamera.orient / CanonCamera.decode), takže tady se nic neotáčí.
 */
@Composable
private fun LivePreview(camera: CameraController, fill: Boolean) {
    Box(
        Modifier.fillMaxSize().onSizeChanged { camera.setViewSize(it.width, it.height) },
        contentAlignment = Alignment.Center,
    ) {
        val frame = camera.frame
        if (frame != null) {
            Image(
                bitmap = frame,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = if (fill) ContentScale.Crop else ContentScale.Fit,
            )
        } else if (camera.error == null) {
            Text("Spouštím kameru…", color = Color.White.copy(alpha = 0.5f), fontSize = 24.sp)
        }
    }
}

@Composable
private fun LogoBadge(modifier: Modifier) {
    val context = LocalContext.current
    val version = LogoStore.version
    val logo = remember(version) { LogoStore.load(context)?.asImageBitmap() } ?: return
    Image(
        bitmap = logo,
        contentDescription = "Logo",
        modifier = modifier.widthIn(max = 220.dp).heightIn(max = 120.dp),
        contentScale = ContentScale.Fit,
    )
}

@Composable
private fun IdleScreen(settings: KioskSettings, cameraError: String?, onShoot: () -> Unit) {
    Box(Modifier.fillMaxSize()) {
        if (settings.logoOnScreen) LogoBadge(Modifier.align(Alignment.TopEnd).padding(24.dp))
        Text(
            settings.welcomeText,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 48.dp, start = 120.dp, end = 120.dp)
                .background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(24.dp))
                .padding(horizontal = 32.dp, vertical = 16.dp),
            fontSize = 44.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
        if (cameraError != null) {
            Text(
                cameraError + "\n\nSprávce: 5× klepni do levého horního rohu.",
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(32.dp)
                    .background(Color(0xFF3A1010), RoundedCornerShape(16.dp))
                    .padding(32.dp),
                fontSize = 26.sp,
                textAlign = TextAlign.Center,
            )
        } else {
            ShutterButton(Modifier.align(Alignment.BottomCenter).padding(bottom = 64.dp), onShoot)
        }
    }
}

@Composable
private fun ShutterButton(modifier: Modifier, onClick: () -> Unit) {
    val pulse by rememberInfiniteTransition(label = "pulse").animateFloat(
        initialValue = 1f,
        targetValue = 1.07f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "scale",
    )
    Box(
        modifier
            .size(190.dp)
            .graphicsLayer {
                scaleX = pulse
                scaleY = pulse
            }
            .border(8.dp, Color.White, CircleShape)
            .padding(14.dp)
            .clip(CircleShape)
            .background(Accent)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text("VYFOTIT", color = Color.Black, fontSize = 30.sp, fontWeight = FontWeight.Black)
    }
}

@Composable
private fun CountdownOverlay(value: Int) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            if (value > 0) value.toString() else "Úsměv!",
            fontSize = if (value > 0) 240.sp else 120.sp,
            fontWeight = FontWeight.Black,
            style = TextStyle(shadow = Shadow(Color.Black, Offset(0f, 6f), 24f)),
        )
    }
}

@Composable
private fun ReviewScreen(
    photo: Bitmap,
    canPrint: Boolean,
    printing: Boolean,
    onRetake: () -> Unit,
    onPrint: () -> Unit,
    onSend: () -> Unit,
) {
    val image = remember(photo) { photo.asImageBitmap() }
    Column(
        Modifier.fillMaxSize().background(Dark).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Jak se ti líbí?", fontSize = 40.sp, fontWeight = FontWeight.Bold)
        Image(
            bitmap = image,
            contentDescription = "Pořízená fotka",
            modifier = Modifier.weight(1f).fillMaxWidth().padding(vertical = 24.dp),
            contentScale = ContentScale.Fit,
        )
        Row(
            Modifier.widthIn(max = if (canPrint) 1200.dp else 900.dp).fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            BigButton("Znovu", Modifier.weight(1f), primary = false, onClick = onRetake)
            if (canPrint) {
                BigButton(if (printing) "Tisknu…" else "Vytisknout", Modifier.weight(1.2f), enabled = !printing, onClick = onPrint)
            }
            BigButton("Poslat na e-mail", Modifier.weight(1.4f), onClick = onSend)
        }
    }
}

/** Dialog s počtem kopií: − / + (1 až [max]) a potvrzení. */
@Composable
private fun CopiesDialog(max: Int, onCancel: () -> Unit, onConfirm: (Int) -> Unit) {
    var copies by remember { mutableIntStateOf(1) }
    Dialog(onDismissRequest = onCancel, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier
                .width(560.dp)
                .background(Dark, RoundedCornerShape(28.dp))
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Kolik kopií vytisknout?", fontSize = 34.sp, fontWeight = FontWeight.Bold, color = Color.White)
            Spacer(Modifier.height(28.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(32.dp)) {
                RoundKey("−", enabled = copies > 1) { copies-- }
                Text(copies.toString(), fontSize = 80.sp, fontWeight = FontWeight.Black, color = Accent, modifier = Modifier.width(90.dp), textAlign = TextAlign.Center)
                RoundKey("+", enabled = copies < max) { copies++ }
            }
            Text("max. $max", fontSize = 20.sp, color = Color.White.copy(alpha = 0.6f))
            Spacer(Modifier.height(28.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                BigButton("Zrušit", Modifier.weight(1f), primary = false, onClick = onCancel)
                BigButton("Vytisknout", Modifier.weight(1.3f)) { onConfirm(copies) }
            }
        }
    }
}

@Composable
private fun RoundKey(label: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(110.dp)
            .clip(CircleShape)
            .background(if (enabled) KeyColor else KeyColor.copy(alpha = 0.4f))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, fontSize = 56.sp, fontWeight = FontWeight.Bold, color = if (enabled) Color.White else Color.Gray)
    }
}

@Composable
private fun ConfirmScreen(email: String, onEdit: () -> Unit, onConfirm: () -> Unit) {
    BackHandler(onBack = onEdit)
    Column(
        Modifier.fillMaxSize().background(Dark).padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("Pošleme fotku na:", fontSize = 34.sp)
        Spacer(Modifier.height(16.dp))
        Text(email, color = Accent, fontSize = 46.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        Spacer(Modifier.height(16.dp))
        Text("Je adresa správně?", fontSize = 30.sp)
        Spacer(Modifier.height(48.dp))
        Row(
            Modifier.widthIn(max = 900.dp).fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            BigButton("Opravit", Modifier.weight(1f), primary = false, onClick = onEdit)
            BigButton("Ano, poslat", Modifier.weight(1f), onClick = onConfirm)
        }
    }
}

@Composable
private fun SentScreen(onDone: () -> Unit) {
    Column(
        Modifier.fillMaxSize().background(Dark).clickable(onClick = onDone).padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("Hotovo!", color = Accent, fontSize = 80.sp, fontWeight = FontWeight.Black)
        Spacer(Modifier.height(24.dp))
        Text(
            "Fotka je na cestě do tvého e-mailu.\nKdyby nedorazila, mrkni i do spamu.",
            fontSize = 32.sp,
            textAlign = TextAlign.Center,
        )
    }
}
