package com.zhitool.rearlyric.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.ListPopupColumn
import top.yukonga.miuix.kmp.basic.ListPopupDefaults
import top.yukonga.miuix.kmp.basic.PopupPositionProvider
import top.yukonga.miuix.kmp.basic.SpinnerDefaults
import top.yukonga.miuix.kmp.basic.SpinnerEntry
import top.yukonga.miuix.kmp.basic.SpinnerItemImpl
import top.yukonga.miuix.kmp.overlay.OverlayListPopup
import top.yukonga.miuix.kmp.preference.ArrowPreference

/** 下拉选择项。有限枚举统一使用该组件，数值输入仍使用底部表单弹窗。 */
data class DropdownOption<T>(
    val label: String,
    val value: T,
)

/**
 * REAREye 风格的设置下拉栏：点击整行后在行尾展开列表，并保持设置行的按下态。
 *
 * 选择时先关闭弹层，下一帧再提交值，避免切换歌词源/样式后锚点与弹层在同一帧被移除。
 */
@Composable
fun <T> DropdownPreference(
    title: String,
    summary: String?,
    selectedValue: T,
    options: List<DropdownOption<T>>,
    onSelected: (T) -> Unit,
) {
    val popupScope = rememberCoroutineScope()
    var expanded by remember { mutableStateOf(false) }
    val entries = remember(options) { options.map { SpinnerEntry(title = it.label) } }

    Box {
        ArrowPreference(
            title = title,
            summary = summary,
            holdDownState = expanded,
            onClick = { if (options.isNotEmpty()) expanded = true },
        )

        OverlayListPopup(
            show = expanded,
            popupModifier = Modifier,
            popupPositionProvider = ListPopupDefaults.DropdownPositionProvider,
            alignment = PopupPositionProvider.Align.End,
            enableWindowDim = true,
            onDismissRequest = { expanded = false },
            maxHeight = null,
            minWidth = 220.dp,
            renderInRootScaffold = true,
        ) {
            ListPopupColumn {
                options.forEachIndexed { index, option ->
                    SpinnerItemImpl(
                        entry = entries[index],
                        entryCount = options.size,
                        isSelected = option.value == selectedValue,
                        index = index,
                        spinnerColors = SpinnerDefaults.spinnerColors(),
                        onSelectedIndexChange = {
                            expanded = false
                            popupScope.launch {
                                withFrameNanos { }
                                onSelected(option.value)
                            }
                        },
                    )
                }
            }
        }
    }
}
