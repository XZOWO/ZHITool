package com.zhitool.rearlyric.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * miuix 数字输入框（移植自词幕）：只负责把输入过滤为可编辑的数字文本，
 * 范围校验由外层决定。
 */
@Composable
fun NumberTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String = "",
    allowDecimal: Boolean = false,
    allowNegative: Boolean = true,
    autoSelectOnFocus: Boolean = false,
    isError: Boolean = false,
    borderColor: Color = if (isError) MiuixTheme.colorScheme.error else MiuixTheme.colorScheme.primary,
) {
    val initialTf = remember(value) {
        TextFieldValue(text = value, selection = TextRange(value.length))
    }

    var textFieldValueState by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(initialTf)
    }

    var initialSelectionDone by remember { mutableStateOf(false) }
    var isFocused by remember { mutableStateOf(false) }
    var shouldSelectAll by remember { mutableStateOf(false) }

    LaunchedEffect(value) {
        if (textFieldValueState.text != value) {
            if (!isFocused) {
                textFieldValueState =
                    TextFieldValue(text = value, selection = TextRange(value.length))
            } else {
                val sel = textFieldValueState.selection
                val clamped = sel.end.coerceIn(0, value.length)
                textFieldValueState = TextFieldValue(text = value, selection = TextRange(clamped))
            }
        } else if (!initialSelectionDone) {
            textFieldValueState = textFieldValueState.copy(selection = TextRange(value.length))
        }
        initialSelectionDone = true
    }

    LaunchedEffect(shouldSelectAll) {
        if (shouldSelectAll && textFieldValueState.text.isNotEmpty()) {
            textFieldValueState = textFieldValueState.copy(
                selection = TextRange(0, textFieldValueState.text.length)
            )
        }
        shouldSelectAll = false
    }

    Column(modifier = modifier) {
        TextField(
            borderColor = borderColor,
            label = label,
            value = textFieldValueState,
            onValueChange = { newValue ->
                val filtered = filterNumericInput(
                    input = newValue.text,
                    allowDecimal = allowDecimal,
                    allowNegative = allowNegative
                )
                val rawSel = newValue.selection.end
                val clampedSel = rawSel.coerceIn(0, filtered.length)

                textFieldValueState = TextFieldValue(
                    text = filtered,
                    selection = TextRange(clampedSel)
                )
                onValueChange(filtered)
            },
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged { focusState ->
                    if (focusState.isFocused && !isFocused && autoSelectOnFocus) {
                        shouldSelectAll = true
                    }
                    isFocused = focusState.isFocused
                },
            keyboardOptions = KeyboardOptions(
                keyboardType = if (allowDecimal) KeyboardType.Decimal else KeyboardType.Number
            ),
            singleLine = true
        )
    }
}

/** 过滤数字输入：只保留数字、可选小数点（最多一个）与可选负号（仅开头）。 */
internal fun filterNumericInput(
    input: String,
    allowDecimal: Boolean,
    allowNegative: Boolean
): String {
    if (input.isEmpty()) return input

    var result = input.filter { char ->
        char.isDigit() ||
                (char == '.' && allowDecimal) ||
                (char == '-' && allowNegative)
    }

    if (allowNegative) {
        val hasLeadingNegative = result.startsWith('-')
        result = result.replace("-", "")
        if (hasLeadingNegative) {
            result = "-$result"
        }
    }

    if (allowDecimal) {
        val firstDot = result.indexOf('.')
        if (firstDot != -1) {
            result = result.take(firstDot + 1) +
                    result.substring(firstDot + 1).replace(".", "")
        }
    }

    return result
}
