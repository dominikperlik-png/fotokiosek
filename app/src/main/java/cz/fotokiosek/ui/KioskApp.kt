package cz.fotokiosek.ui

import android.graphics.Bitmap
import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.camera.view.PreviewView
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import cz.fotokiosek.KioskCamera
import cz.fotokiosek.KioskSettings
import cz.fotokiosek.PhotoStore
import cz.fotokiosek.RateLimit
import cz.fotokiosek.SendPhotoWorker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

sealed interface Screen {
    data object Idle : Screen
    data class Countdown(val value: Int) : Screen
    data class Review(val photo: Bitmap) : Screen
    data class Email(val photo: Bitmap, val email: String = "") : Screen
    data class Confirm(val photo: Bitmap, val email: String) : Screen
    data object Sent : Screen
    data object Admin : Screen
}

@Composable
fun KioskApp(settings: KioskSettings, camera: KioskCamera, lastTouch: State<Long>) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var screen by remember { mutableStateOf<Screen>(Screen.Idle) }
    var message by remember { mutableStateOf<String?>(null) }
    var askPin by remember { mutableStateOf(false) }
    val flash = remember { Animatable(0f) }

    fun goIdle() {
        screen = Screen.Idle
        askPin = false
    }

    fun shoot() {
        if (screen != Screen.Idle) return
        scope.launch {
            for (n in settings.countdown.coerceIn(0, 10) downTo 1) {
                screen = Screen.Countdown(n)
                delay(1000)
            }
            screen = Screen.Countdown(0)
            flash.snapTo(1f)
            launch { flash.animateTo(0f, tween(600)) }
            try {
                screen = Screen.Review(camera.takePhoto())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                message = "Fotku se nepodařilo pořídit. Zkus to prosím znovu."
                goIdle()
            }
        }
    }

    fun send(photo: Bitmap, email: String) {
        if (!RateLimit.allow(email)) {
            message = "Na tuhle adresu už odešlo hodně fotek. Zkus to prosím později."
            return
        }
        screen = Screen.Sent
        scope.launch(Dispatchers.IO) {
            try {
                val file = PhotoStore.save(context, photo)
                SendPhotoWorker.enqueue(context, file, email)
            } catch (e: Exception) {
                settings.lastError = "Uložení fotky selhalo: ${e.message}"
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
    LaunchedEffect(message) {
        if (message != null) {
            delay(5000)
            message = null
        }
    }

    KioskTheme {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            // Živý náhled kamery běží pořád, ostatní obrazovky ho překrývají.
            AndroidView(
                factory = { ctx ->
                    PreviewView(ctx).apply {
                        implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                        camera.attach(this)
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )

            when (val s = screen) {
                Screen.Idle -> IdleScreen(settings.welcomeText, camera.error, onShoot = { shoot() })
                is Screen.Countdown -> CountdownOverlay(s.value)
                is Screen.Review -> ReviewScreen(
                    photo = s.photo,
                    onRetake = { goIdle() },
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
                Screen.Admin -> AdminScreen(settings, camera, onClose = { goIdle() })
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

            message?.let {
                Text(
                    it,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 140.dp, start = 24.dp, end = 24.dp)
                        .background(Color(0xFFC62828), RoundedCornerShape(16.dp))
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

@Composable
private fun IdleScreen(title: String, cameraError: String?, onShoot: () -> Unit) {
    Box(Modifier.fillMaxSize()) {
        Text(
            title,
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
private fun ReviewScreen(photo: Bitmap, onRetake: () -> Unit, onSend: () -> Unit) {
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
            Modifier.widthIn(max = 900.dp).fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            BigButton("Znovu", Modifier.weight(1f), primary = false, onClick = onRetake)
            BigButton("Poslat na e-mail", Modifier.weight(1.4f), onClick = onSend)
        }
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
