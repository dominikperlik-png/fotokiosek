package cz.fotokiosek.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cz.fotokiosek.EmailCheck

private val letterRows = listOf("1234567890", "qwertzuiop", "asdfghjkl", "yxcvbnm")
private val domains = listOf("@gmail.com", "@seznam.cz", "@email.cz", "@centrum.cz", "@icloud.com")
private const val MAX_LENGTH = 80

/** Zadání e-mailu vlastní klávesnicí (systémová klávesnice na automatech bývá nepoužitelná). */
@Composable
fun EmailScreen(initial: String, onBack: () -> Unit, onDone: (String) -> Unit) {
    var email by remember { mutableStateOf(initial) }
    var error by remember { mutableStateOf<String?>(null) }
    val suggestion = remember(email) { EmailCheck.suggestion(email) }
    BackHandler(onBack = onBack)

    fun type(text: String) {
        error = null
        if (text == "@" && email.contains('@')) return
        if (email.length + text.length <= MAX_LENGTH) email += text
    }

    fun addDomain(domain: String) {
        error = null
        val local = email.substringBefore('@')
        if (local.isEmpty()) return
        email = local + domain
    }

    fun submit() {
        val value = email.trim().lowercase()
        if (EmailCheck.isValid(value)) onDone(value) else error = "Tohle nevypadá jako e-mailová adresa."
    }

    Column(
        Modifier.fillMaxSize().background(Dark).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(
            Modifier.widthIn(max = 1000.dp).fillMaxWidth().weight(1f),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Kam ti fotku pošleme?", fontSize = 40.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(24.dp))

            Box(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 96.dp)
                    .background(Color.White, RoundedCornerShape(18.dp))
                    .padding(horizontal = 24.dp, vertical = 12.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                Text(
                    buildAnnotatedString {
                        if (email.isEmpty()) {
                            withStyle(SpanStyle(color = Color.Gray)) { append("tvuj@email.cz") }
                        } else {
                            withStyle(SpanStyle(color = Color.Black)) { append(email) }
                            withStyle(SpanStyle(color = Accent)) { append("|") }
                        }
                    },
                    fontSize = 38.sp,
                    maxLines = 2,
                )
            }

            Box(Modifier.heightIn(min = 72.dp).padding(top = 12.dp), contentAlignment = Alignment.Center) {
                when {
                    suggestion != null -> Text(
                        "Nemyslel(a) jsi $suggestion?  Klepni sem.",
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(Accent)
                            .clickable { email = suggestion }
                            .padding(horizontal = 20.dp, vertical = 10.dp),
                        color = Color.Black,
                        fontSize = 26.sp,
                    )
                    error != null -> Text(error!!, color = Color(0xFFFF6E6E), fontSize = 26.sp)
                }
            }

            Spacer(Modifier.weight(1f))

            Keyboard(onType = ::type, onDomain = ::addDomain, onBackspace = {
                error = null
                email = email.dropLast(1)
            })

            Spacer(Modifier.height(24.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                BigButton("Zpět", Modifier.weight(1f), primary = false, onClick = onBack)
                BigButton("Pokračovat", Modifier.weight(1.4f), enabled = email.isNotEmpty(), onClick = ::submit)
            }
            Spacer(Modifier.height(12.dp))
            Text(
                "E-mail použijeme jen k odeslání této fotky.",
                color = Color.White.copy(alpha = 0.6f),
                fontSize = 18.sp,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun Keyboard(onType: (String) -> Unit, onDomain: (String) -> Unit, onBackspace: () -> Unit) {
    val gap = Arrangement.spacedBy(8.dp)
    Column(verticalArrangement = gap) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = gap) {
            domains.forEach { Key(it, fontSize = 20.sp, color = Color(0xFF3D3420)) { onDomain(it) } }
        }
        letterRows.forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = gap) {
                // odsazení řádků jako na skutečné klávesnici
                val indent = (10 - row.length) / 2f
                if (indent > 0) Spacer(Modifier.weight(indent))
                row.forEach { c -> Key(c.toString()) { onType(c.toString()) } }
                if (indent > 0) Spacer(Modifier.weight(indent))
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = gap) {
            Key("@", color = Color(0xFF3D3420)) { onType("@") }
            Key(".") { onType(".") }
            Key("-") { onType("-") }
            Key("_") { onType("_") }
            Key(".cz", weight = 1.4f) { onType(".cz") }
            Key(".com", weight = 1.4f) { onType(".com") }
            Key("⌫", weight = 2.2f, color = Color(0xFF4A2020)) { onBackspace() }
        }
    }
}

@Composable
private fun RowScope.Key(
    label: String,
    weight: Float = 1f,
    fontSize: TextUnit = 30.sp,
    color: Color = KeyColor,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .weight(weight)
            .height(76.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(color)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, fontSize = fontSize, maxLines = 1)
    }
}
