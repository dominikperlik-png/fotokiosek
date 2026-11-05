package cz.fotokiosek.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

val Accent = Color(0xFFFFB300)
val Dark = Color(0xFF141414)
val KeyColor = Color(0xFF2C2C2C)

@Composable
fun KioskTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Accent,
            onPrimary = Color.Black,
            background = Dark,
            surface = Dark,
            onBackground = Color.White,
            onSurface = Color.White,
        )
    ) {
        CompositionLocalProvider(LocalContentColor provides Color.White, content = content)
    }
}

/** Velké tlačítko na dotykovou obrazovku. */
@Composable
fun BigButton(
    text: String,
    modifier: Modifier = Modifier,
    primary: Boolean = true,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.heightIn(min = 84.dp),
        shape = RoundedCornerShape(42.dp),
        colors = if (primary) {
            ButtonDefaults.buttonColors()
        } else {
            ButtonDefaults.buttonColors(containerColor = Color.White.copy(alpha = 0.15f), contentColor = Color.White)
        },
        contentPadding = PaddingValues(horizontal = 32.dp, vertical = 16.dp),
    ) {
        Text(text, fontSize = 28.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
    }
}
