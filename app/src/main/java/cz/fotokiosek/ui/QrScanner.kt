package cz.fotokiosek.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import cz.fotokiosek.AppLog
import cz.fotokiosek.CameraController
import cz.fotokiosek.QrScan
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

/**
 * Čtečka QR kódu pro menu správce: ukazuje živý obraz kamery automatu (stejný jako pro focení)
 * a každých ~0,3 s zkusí v něm najít QR kód. Po přečtení zavolá [onResult] a zavře se.
 */
@Composable
fun QrScanDialog(camera: CameraController, title: String, hint: String, onResult: (String) -> Unit, onCancel: () -> Unit) {
    val result by rememberUpdatedState(onResult)
    LaunchedEffect(Unit) {
        var last: ImageBitmap? = null
        while (isActive) {
            delay(300)
            val frame = camera.frame ?: continue
            if (frame === last) continue // kamera neposlala nový snímek – nezpracovávat znovu
            last = frame
            val text = withContext(Dispatchers.Default) {
                runCatching {
                    // Jen střed obrazu (kde je rámeček) zmenšený na max. 640 px – šetří starý procesor
                    // a nepřečte náhodný QR kód z plakátu na kraji záběru.
                    val src = frame.asAndroidBitmap()
                    val side = (minOf(src.width, src.height) * 0.8f).toInt().coerceAtLeast(1)
                    val scale = minOf(1f, 640f / side)
                    val m = android.graphics.Matrix().apply { postScale(scale, scale) }
                    val crop = android.graphics.Bitmap.createBitmap(src, (src.width - side) / 2, (src.height - side) / 2, side, side, m, true)
                    val w = crop.width
                    val h = crop.height
                    val pixels = IntArray(w * h)
                    crop.getPixels(pixels, 0, w, 0, 0, w, h)
                    if (crop !== src) crop.recycle()
                    QrScan.decode(w, h, pixels)
                }.onFailure { AppLog.w("QR", "Čtení snímku selhalo", it) }.getOrNull()
            }
            if (text != null) {
                AppLog.i("QR", "QR kód přečten (${text.length} znaků)")
                result(text)
                break
            }
        }
    }
    Dialog(onDismissRequest = onCancel, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier
                .fillMaxWidth(0.92f)
                .widthIn(max = 720.dp)
                .background(Dark, RoundedCornerShape(28.dp))
                .padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(title, fontSize = 30.sp, fontWeight = FontWeight.Bold)
            Box(
                Modifier.fillMaxWidth().heightIn(max = 340.dp).aspectRatio(4f / 3f, matchHeightConstraintsFirst = true)
                    .background(Color.Black, RoundedCornerShape(16.dp)),
                contentAlignment = Alignment.Center,
            ) {
                val frame = camera.frame
                if (frame != null) {
                    Image(frame, contentDescription = "Obraz kamery", modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                } else {
                    Text("Kamera nejede – zkontroluj ji v sekci Kamera.", fontSize = 20.sp, color = Color.White.copy(alpha = 0.7f))
                }
                // Rámeček, kam QR kód namířit.
                Box(Modifier.fillMaxHeight(0.8f).aspectRatio(1f).border(4.dp, Accent, RoundedCornerShape(20.dp)))
            }
            Text(hint, fontSize = 20.sp, textAlign = TextAlign.Center, color = Color.White.copy(alpha = 0.75f))
            BigButton("Zrušit", Modifier.fillMaxWidth(), primary = false, onClick = onCancel)
        }
    }
}
