package com.pismo.messenger.ui.chat

import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.pismo.messenger.ui.theme.PismoColors
import java.util.Calendar
import java.util.TimeZone

/**
 * Переход к дате — Android-аналог DatePickerPopup + JumpToDate на ПК:
 * там календарь висит на кнопке в шапке переписки.
 *
 * Отдаёт наружу начало выбранного дня в МЕСТНОМ времени. DatePicker
 * возвращает полночь по UTC, и без пересчёта пользователь восточнее Гринвича
 * прыгал бы на день назад.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DateJumpDialog(
    onDismiss: () -> Unit,
    onPick: (Long) -> Unit,
) {
    val state = rememberDatePickerState()

    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                enabled = state.selectedDateMillis != null,
                onClick = {
                    val utc = state.selectedDateMillis ?: return@TextButton
                    onPick(localDayStart(utc))
                },
            ) { Text("Перейти", color = PismoColors.Cyan) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Отмена", color = PismoColors.TextMuted) }
        },
        colors = androidx.compose.material3.DatePickerDefaults.colors(
            containerColor = PismoColors.BgSidebar,
        ),
    ) {
        DatePicker(
            state = state,
            title = { Text("  Перейти к дате", color = Color.White) },
            colors = androidx.compose.material3.DatePickerDefaults.colors(
                containerColor = PismoColors.BgSidebar,
            ),
        )
    }
}

/** Полночь по UTC → полночь того же календарного дня в местном поясе. */
private fun localDayStart(utcMillis: Long): Long {
    val utc = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
        timeInMillis = utcMillis
    }
    return Calendar.getInstance().apply {
        clear()
        set(
            utc.get(Calendar.YEAR),
            utc.get(Calendar.MONTH),
            utc.get(Calendar.DAY_OF_MONTH),
            0, 0, 0,
        )
    }.timeInMillis
}
