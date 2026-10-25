package assistant.mobile.design.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import assistant.mobile.design.GeneratedColors

enum class StatusTone(internal val tokenName: String) {
    Success("success"),
    Warning("warning"),
    Destructive("destructive"),
}

/** Read a generated semantic token from the palette applied by NativeTheme. */
@Composable
fun generatedColor(name: String): Color {
    val palette = if (MaterialTheme.colorScheme.primary == GeneratedColors.dark.getValue("primary")) {
        GeneratedColors.dark
    } else {
        GeneratedColors.light
    }
    return palette.getValue(name)
}

val StatusTone.contentColor: Color
    @Composable get() = generatedColor(tokenName)

val StatusTone.containerColor: Color
    @Composable get() = generatedColor("$tokenName-surface")

@Composable
fun StatusBadge(text: String, tone: StatusTone, modifier: Modifier = Modifier) {
    Box(
        modifier.height(20.dp)
            .background(tone.containerColor, RoundedCornerShape(50))
            .padding(horizontal = 8.dp, vertical = 2.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            color = tone.contentColor,
            fontSize = 12.sp,
            lineHeight = 12.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
        )
    }
}
