package app.smugly.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.smugly.S
import app.smugly.subscription.Subscription
import app.smugly.t
import app.smugly.ui.theme.SmuglyTextPrimary

/** How a brand-new folder is seeded — only meaningful while [FolderDraft.id] is null. */
enum class FolderCreateMode {
    /** Name only; no subscription URL, no auto-refresh. */
    EMPTY,
    /** User pastes / types a subscription URL (fetched on save). */
    FROM_LINK,
    /** Opens the file picker; no folder is saved from this dialog. */
    FROM_FILE
}

/** Everything the folder editor collects. */
data class FolderDraft(
    /** Null for a folder being created. */
    val id: String? = null,
    val name: String = "",
    val url: String = "",
    val enabled: Boolean = true,
    val autoUpdate: Boolean = true,
    val allowReorder: Boolean = true,
    val showInfo: Boolean = true,
    /** Only used when creating: empty / link / file. */
    val createMode: FolderCreateMode = FolderCreateMode.EMPTY
) {
    companion object {
        fun of(sub: Subscription) = FolderDraft(
            id = sub.id,
            name = sub.name,
            url = sub.url,
            enabled = sub.url.isNotBlank() && sub.enabled,
            autoUpdate = sub.url.isNotBlank() && sub.updateIntervalMinutes > 0,
            allowReorder = sub.allowReorder,
            showInfo = sub.showInfo,
            createMode = if (sub.url.isBlank()) FolderCreateMode.EMPTY else FolderCreateMode.FROM_LINK
        )

        /** Blank new-folder draft — mode is chosen inside the dialog. */
        fun newFolder() = FolderDraft(
            createMode = FolderCreateMode.EMPTY,
            enabled = false,
            autoUpdate = false,
            allowReorder = true,
            showInfo = true
        )
    }
}

/**
 * Create / edit a subscription folder.
 *
 * For a **new** folder the dialog starts with a source picker (empty / link / file), then the
 * matching fields. Editing an existing folder skips the picker.
 */
@Composable
fun FolderEditorDialog(
    visible: Boolean,
    draft: FolderDraft,
    onDismiss: () -> Unit,
    onSave: (FolderDraft) -> Unit,
    /** New folder → "from file": close the dialog and open the system file picker. */
    onImportFile: () -> Unit = {}
) {
    // Do NOT key on [visible]: when the dialog closes, [visible] flips false while the exit
    // animation still shows this content. Re-seeding from [draft] (always EMPTY for a new folder)
    // made the source pill snap back to "Empty" for a frame and looked broken.
    var edited by remember { mutableStateOf(draft) }
    LaunchedEffect(visible, draft.id) {
        if (visible) edited = draft
    }
    val isNew = draft.id == null

    AnimatedModalCard(
        visible = visible,
        onDismissRequest = onDismiss,
        modifier = Modifier.widthIn(max = 420.dp)
    ) {
        Text(
            text = if (isNew) t(S.FOLDER_NEW_TITLE) else t(S.FOLDER_EDIT_TITLE),
            color = SmuglyTextPrimary,
            fontSize = 16.sp
        )
        Spacer(Modifier.height(12.dp))

        // New folder: choose source first (empty / link / file) inside this window, not the + menu.
        if (isNew) {
            LabeledField(t(S.FOLDER_SOURCE)) {
                PillSelector(
                    options = listOf(
                        t(S.FOLDER_SOURCE_EMPTY),
                        t(S.FOLDER_SOURCE_LINK),
                        t(S.FOLDER_SOURCE_FILE)
                    ),
                    selectedIndex = when (edited.createMode) {
                        FolderCreateMode.EMPTY -> 0
                        FolderCreateMode.FROM_LINK -> 1
                        FolderCreateMode.FROM_FILE -> 2
                    }
                ) { idx ->
                    val mode = when (idx) {
                        1 -> FolderCreateMode.FROM_LINK
                        2 -> FolderCreateMode.FROM_FILE
                        else -> FolderCreateMode.EMPTY
                    }
                    edited = when (mode) {
                        FolderCreateMode.EMPTY -> edited.copy(
                            createMode = mode,
                            url = "",
                            enabled = false,
                            autoUpdate = false
                        )
                        FolderCreateMode.FROM_LINK -> edited.copy(
                            createMode = mode,
                            enabled = true,
                            autoUpdate = true
                        )
                        FolderCreateMode.FROM_FILE -> edited.copy(createMode = mode)
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
        }

        if (isNew && edited.createMode == FolderCreateMode.FROM_FILE) {
            HintText(t(S.FOLDER_SOURCE_FILE_HINT))
            Spacer(Modifier.height(12.dp))
            PrimaryButton(
                text = t(S.FOLDER_PICK_FILE_BTN),
                onClick = {
                    onDismiss()
                    onImportFile()
                }
            )
            return@AnimatedModalCard
        }

        LabeledField(t(S.FOLDER_NAME)) {
            SmuglyTextField(
                edited.name,
                { edited = edited.copy(name = it) },
                hint = t(S.FOLDER_NAME_HINT)
            )
        }

        val showUrl = !isNew || edited.createMode == FolderCreateMode.FROM_LINK ||
            edited.url.isNotBlank()
        if (showUrl) {
            LabeledField(t(S.FOLDER_URL)) {
                SmuglyTextField(
                    edited.url,
                    { next ->
                        val blank = next.isBlank()
                        edited = edited.copy(
                            url = next,
                            enabled = if (blank) false else true,
                            autoUpdate = if (blank) false else true
                        )
                    },
                    hint = "https://…"
                )
            }
            if (edited.url.isNotBlank()) {
                Column(Modifier.fillMaxWidth()) {
                    SmuglyCheckbox(
                        checked = edited.enabled,
                        onCheckedChange = { on ->
                            edited = edited.copy(
                                enabled = on,
                                autoUpdate = if (on) edited.autoUpdate else false
                            )
                        },
                        label = t(S.FOLDER_UPDATES_ENABLED)
                    )
                    SmuglyCheckbox(
                        checked = edited.autoUpdate && edited.enabled,
                        onCheckedChange = { on ->
                            edited = edited.copy(
                                autoUpdate = on,
                                enabled = if (on) true else edited.enabled
                            )
                        },
                        label = t(S.FOLDER_AUTO_UPDATE)
                    )
                }
            }
        }

        Column(Modifier.fillMaxWidth()) {
            SmuglyCheckbox(
                checked = edited.allowReorder,
                onCheckedChange = { edited = edited.copy(allowReorder = it) },
                label = t(S.FOLDER_ALLOW_REORDER)
            )
            if (edited.url.isNotBlank() || (!isNew && draft.url.isNotBlank())) {
                SmuglyCheckbox(
                    checked = edited.showInfo,
                    onCheckedChange = { edited = edited.copy(showInfo = it) },
                    label = t(S.FOLDER_SHOW_INFO)
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        PrimaryButton(
            text = t(S.SAVE_BTN),
            onClick = {
                val url = edited.url.trim()
                val cleaned = if (url.isBlank()) {
                    edited.copy(
                        url = "",
                        enabled = false,
                        autoUpdate = false,
                        createMode = FolderCreateMode.EMPTY,
                        name = edited.name.trim().ifBlank { t(S.FOLDER_DEFAULT_EMPTY_NAME) }
                    )
                } else {
                    edited.copy(
                        url = url,
                        name = edited.name.trim(),
                        enabled = edited.enabled,
                        autoUpdate = edited.autoUpdate && edited.enabled,
                        createMode = FolderCreateMode.FROM_LINK
                    )
                }
                onSave(cleaned)
            }
        )
    }
}
