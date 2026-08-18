package com.pismo.messenger.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pismo.messenger.ui.theme.PismoColors

/**
 * Палитра готовых цветов — порт списка swatch-панелей из ServersForm.cs.
 *
 * Цвета взяты с ПК ОДИН В ОДИН, и это не косметика: цвет роли лежит в базе
 * строкой и рисуется обоими клиентами. Если наборы разойдутся, один и тот же
 * список ролей будет выглядеть на телефоне и на компьютере по-разному.
 *
 * На ПК рядом с палитрой есть кнопка «Ещё…» с системным ColorDialog. На
 * Android системного выбора цвета нет вовсе, поэтому произвольный оттенок
 * по-прежнему вводится шестнадцатеричным кодом — но теперь это запасной путь,
 * а не единственный.
 */
val ROLE_PALETTE = listOf(
    "#1ABC9C", "#2ECC71", "#3498DB", "#9B59B6", "#E91E63", "#F1C40F",
    "#E67E22", "#E74C3C", "#95A5A6", "#607D8B", "#3BA55D", "#5865F2",
)

/** Разбор «#RRGGBB» с запасным значением — строка приходит из базы и от пользователя. */
fun parseHexColor(hex: String, fallback: Color = Color(0xFF5865F2)): Color = runCatching {
    val clean = hex.trim().removePrefix("#")
    when (clean.length) {
        6 -> Color(("FF$clean").toLong(16))
        8 -> Color(clean.toLong(16))
        else -> fallback
    }
}.getOrDefault(fallback)

/**
 * Сетка образцов. [selected] — текущий код цвета, [onPick] отдаёт выбранный
 * в том же формате «#RRGGBB», в каком он лежит в базе.
 */
@Composable
fun ColorPalettePicker(
    selected: String,
    onPick: (String) -> Unit,
    columns: Int = 6,
) {
    val current = selected.trim().removePrefix("#").uppercase()
    Column {
        ROLE_PALETTE.chunked(columns).forEach { row ->
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                row.forEach { hex ->
                    val isSelected = hex.removePrefix("#").uppercase() == current
                    Box(
                        Modifier
                            .size(30.dp)
                            .clip(CircleShape)
                            .background(parseHexColor(hex))
                            .border(
                                width = if (isSelected) 3.dp else 1.dp,
                                color = if (isSelected) PismoColors.TextPrimary
                                else PismoColors.Divider,
                                shape = CircleShape,
                            )
                            .clickable { onPick(hex) }
                    )
                }
                // Добиваем ряд пустыми местами, иначе последний ряд из
                // неполного числа образцов растянется по ширине.
                repeat(columns - row.size) { Spacer(Modifier.size(30.dp)) }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

/** Живое превью: как будет выглядеть имя, выкрашенное в выбранный цвет. */
@Composable
fun ColorPreviewRow(hex: String, sample: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(20.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(parseHexColor(hex))
                .border(1.dp, PismoColors.Divider, RoundedCornerShape(4.dp))
        )
        Spacer(Modifier.width(8.dp))
        Text(
            sample.ifBlank { "Название роли" },
            color = parseHexColor(hex),
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}
