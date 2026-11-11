package com.wangzan101.mika.ui

import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.wangzan101.mika.R
import com.wangzan101.mika.core.prompt.PromptLibrary
import com.wangzan101.mika.i18n.tr
import com.wangzan101.mika.prompt.PromptFolder
import com.wangzan101.mika.ui.components.ButtonKind
import com.wangzan101.mika.ui.components.ButtonSize
import com.wangzan101.mika.ui.components.EmptyNote
import com.wangzan101.mika.ui.components.FooterNote
import com.wangzan101.mika.ui.components.GroupCard
import com.wangzan101.mika.ui.components.GroupLabel
import com.wangzan101.mika.ui.components.ListRow
import com.wangzan101.mika.ui.components.MikaButton
import com.wangzan101.mika.ui.components.MikaDialog
import com.wangzan101.mika.ui.components.PageScaffold
import com.wangzan101.mika.ui.components.RowChevron
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.ui.theme.MikaType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 设置 › 开发者选项 › 提示词:目录在哪、现在有几份、哪几份自己改过或不能用,
 * 一份一份或者整个恢复成 APK 里的那份。
 */
@Composable
fun PromptScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf(PromptFolder.status) }
    var items by remember { mutableStateOf(emptyList<PromptLibrary.Item>()) }
    var confirmingReset by remember { mutableStateOf(false) }

    fun reload() = scope.launch {
        val (s, list) = withContext(Dispatchers.IO) {
            PromptFolder.install(context)
            PromptFolder.inspect()
            PromptFolder.status to PromptFolder.items()
        }
        status = s
        items = list
    }

    // 从系统的「所有文件访问权限」回来时重新对齐一次(那一下目录会换成公开的那份)
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) reload() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(Unit) { reload() }

    fun restored(ok: Boolean) {
        Toast.makeText(context, tr(if (ok) R.string.prompts_restored else R.string.prompts_restore_failed), Toast.LENGTH_SHORT).show()
        reload()
    }

    PageScaffold(tr(R.string.prompts_title), onBack) {
        status?.let { s ->
            GroupLabel(tr(R.string.prompts_folder))
            GroupCard {
                ListRow(
                    null, s.dir.absolutePath,
                    if (s.granted) tr(R.string.prompts_access_on) else tr(R.string.prompts_access_off),
                    first = true,
                    onClick = if (s.granted) null else ({ openAllFilesAccess(context) }),
                ) { if (!s.granted) RowChevron() }
                ListRow(null, tr(R.string.prompts_counts, s.files, s.edited, s.ignored))
            }
        }

        val changed = items.filter { !it.present || it.edited || it.rejected != null }
        GroupLabel(tr(R.string.prompts_changed))
        GroupCard {
            if (changed.isEmpty()) {
                EmptyNote(tr(R.string.prompts_all_plain))
            } else {
                changed.forEachIndexed { i, item ->
                    ListRow(
                        null, item.path,
                        when {
                            item.rejected != null -> tr(R.string.prompts_rejected, item.rejected)
                            !item.present -> tr(R.string.prompts_deleted)
                            else -> tr(R.string.prompts_edited)
                        },
                        first = i == 0,
                    ) {
                        MikaButton(
                            tr(R.string.prompts_restore),
                            { scope.launch { restored(withContext(Dispatchers.IO) { PromptFolder.restore(context, item.path) }) } },
                            kind = ButtonKind.Tonal, size = ButtonSize.Small,
                        )
                    }
                }
            }
        }

        GroupCard {
            ListRow(
                null, tr(R.string.prompts_restore_all), tr(R.string.prompts_restore_all_sub),
                first = true, onClick = { confirmingReset = true },
            ) { RowChevron() }
        }
        FooterNote(tr(R.string.prompts_note))
    }

    if (confirmingReset) {
        MikaDialog(
            onDismissRequest = { confirmingReset = false },
            title = { Text(tr(R.string.prompts_restore_all), style = MikaType.Title, color = MikaTheme.colors.ink) },
            text = { Text(tr(R.string.prompts_restore_all_ask), style = MikaType.Body, color = MikaTheme.colors.ink2) },
            confirmButton = {
                MikaButton(
                    tr(R.string.prompts_restore_all),
                    {
                        confirmingReset = false
                        scope.launch { restored(withContext(Dispatchers.IO) { PromptFolder.reset(context) }) }
                    },
                    kind = ButtonKind.Primary, size = ButtonSize.Small,
                )
            },
            dismissButton = {
                MikaButton(tr(R.string.cancel), { confirmingReset = false }, kind = ButtonKind.Text, size = ButtonSize.Small)
            },
        )
    }
}
