package cz.fotokiosek.ui

import android.graphics.Bitmap
import androidx.compose.animation.core.LinearEasing
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cz.fotokiosek.AiStyle

/** Výběr AI stylu po vyfocení: mřížka karet (ikona + název), plus původní fotka a případně poslední AI fotka. */
@Composable
fun AiPickScreen(
    styles: List<AiStyle>,
    triesLeft: Int,
    lastResult: Bitmap?,
    onPick: (AiStyle) -> Unit,
    onOriginal: () -> Unit,
    onLastResult: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().background(Dark).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Vyber si AI styl", fontSize = 40.sp, fontWeight = FontWeight.Bold)
        Text(
            if (triesLeft > 0) "AI z tvé fotky udělá obrázek ve vybraném stylu." else "Všechny AI pokusy jsou vyčerpané – vyber hotovou fotku.",
            fontSize = 22.sp,
            color = Color.White.copy(alpha = 0.7f),
        )
        Spacer(Modifier.height(16.dp))
        LazyVerticalGrid(
            columns = GridCells.Adaptive(180.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.weight(1f).fillMaxWidth(),
        ) {
            if (lastResult != null) {
                item(key = "last") { ResultCard(lastResult, "Poslední AI fotka", onLastResult) }
            }
            item(key = "original") { StyleCard("📷", "Bez úpravy (původní fotka)", enabled = true, highlight = false, onClick = onOriginal) }
            items(styles, key = { it.id }) { style ->
                StyleCard(style.icon, style.name, enabled = triesLeft > 0, highlight = true) { onPick(style) }
            }
        }
        Spacer(Modifier.height(12.dp))
        Text(
            "Fotku na chvíli pošleme k úpravě do služby Google AI." +
                if (triesLeft > 0) " Zbývá pokusů: $triesLeft." else "",
            fontSize = 18.sp,
            color = Color.White.copy(alpha = 0.55f),
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun StyleCard(icon: String, name: String, enabled: Boolean, highlight: Boolean, onClick: () -> Unit) {
    Column(
        Modifier
            .height(150.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(if (enabled) KeyColor else KeyColor.copy(alpha = 0.4f))
            .border(2.dp, if (highlight && enabled) Accent.copy(alpha = 0.7f) else Color.Transparent, RoundedCornerShape(20.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(icon.ifBlank { "✨" }, fontSize = 48.sp, color = if (enabled) Color.White else Color.White.copy(alpha = 0.4f))
        Spacer(Modifier.height(8.dp))
        Text(
            name,
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            color = if (enabled) Color.White else Color.White.copy(alpha = 0.4f),
        )
    }
}

@Composable
private fun ResultCard(photo: Bitmap, label: String, onClick: () -> Unit) {
    val image = remember(photo) { photo.asImageBitmap() }
    Column(
        Modifier
            .height(150.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(KeyColor)
            .border(2.dp, Accent, RoundedCornerShape(20.dp))
            .clickable(onClick = onClick)
            .padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Image(image, contentDescription = label, modifier = Modifier.weight(1f).fillMaxWidth(), contentScale = ContentScale.Fit)
        Text(label, fontSize = 20.sp, fontWeight = FontWeight.Bold)
    }
}

/** Čekání na AI: točící se kruh a text, tlačítko Zrušit (zpět na výběr stylu). */
@Composable
fun AiWorkingScreen(style: AiStyle, onCancel: () -> Unit) {
    val transition = rememberInfiniteTransition(label = "ai")
    val angle by transition.animateFloat(0f, 360f, infiniteRepeatable(tween(1400, easing = LinearEasing)), label = "angle")
    val pulse by transition.animateFloat(0.4f, 1f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "pulse")
    Column(
        Modifier.fillMaxSize().background(Dark).padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(Modifier.size(220.dp), contentAlignment = Alignment.Center) {
            // Otáčející se oblouk (3/4 kruhu).
            Box(
                Modifier.size(220.dp).drawBehind {
                    val w = 10.dp.toPx()
                    drawArc(
                        color = Accent,
                        startAngle = angle,
                        sweepAngle = 270f,
                        useCenter = false,
                        topLeft = Offset(w / 2, w / 2),
                        size = Size(size.width - w, size.height - w),
                        style = Stroke(width = w, cap = StrokeCap.Round),
                    )
                },
            )
            Text(style.icon.ifBlank { "✨" }, fontSize = 80.sp)
        }
        Spacer(Modifier.height(32.dp))
        Text("AI připravuje tvoji fotku…", fontSize = 44.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        Text(
            "Styl: ${style.name}",
            fontSize = 28.sp,
            color = Accent.copy(alpha = pulse),
            modifier = Modifier.padding(top = 12.dp),
        )
        Text(
            "Obvykle to trvá 10–40 sekund.",
            fontSize = 22.sp,
            color = Color.White.copy(alpha = 0.6f),
            modifier = Modifier.padding(top = 12.dp),
        )
        Spacer(Modifier.height(40.dp))
        BigButton("Zrušit", Modifier.widthIn(min = 260.dp), primary = false, onClick = onCancel)
    }
}

/** AI selhala: srozumitelná hláška a nabídka původní fotky nebo jiného stylu. */
@Composable
fun AiFailedScreen(
    message: String,
    canTryAgain: Boolean,
    onOriginal: () -> Unit,
    onOtherStyle: () -> Unit,
    /** Dřívější povedená AI fotka k téže fotce (null = žádná). */
    onLastResult: (() -> Unit)?,
) {
    Column(
        Modifier.fillMaxSize().background(Dark).padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("😕", fontSize = 90.sp)
        Text("AI úprava se tentokrát nepovedla", fontSize = 40.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        Text(
            message,
            fontSize = 24.sp,
            color = Color.White.copy(alpha = 0.75f),
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 16.dp).widthIn(max = 900.dp),
        )
        Spacer(Modifier.height(40.dp))
        Row(Modifier.widthIn(max = 1000.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            if (canTryAgain) BigButton("Zkusit jiný styl", Modifier.weight(1f), primary = false, onClick = onOtherStyle)
            if (onLastResult != null) BigButton("Poslední AI fotka", Modifier.weight(1f), primary = false, onClick = onLastResult)
            BigButton("Použít původní fotku", Modifier.weight(1.3f), onClick = onOriginal)
        }
    }
}
