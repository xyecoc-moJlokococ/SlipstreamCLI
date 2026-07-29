package app.vaydns.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.vaydns.S
import app.vaydns.subscription.Subscription
import app.vaydns.t
import app.vaydns.ui.theme.SlipnetTextPrimary

/** Everything the folder editor collects. */
data class FolderDraft(
    /** Null for a folder being created. */
    val id: String? = null,
    val name: String = "",
    val url: String = "",
    val enabled: Boolean = true,
    val autoUpdate: Boolean = true,
    val allowReorder: Boolean = false,
    val showInfo: Boolean = true
) {
    companion object {
        fun of(sub: Subscription) = FolderDraft(
            id = sub.id,
            name = sub.name,
            url = sub.url,
            enabled = sub.enabled,
            autoUpdate = sub.updateIntervalMinutes > 0,
            allowReorder = sub.allowReorder,
            showInfo = sub.showInfo
        )
    }
}

/**
 * Create / edit a subscription folder. One dialog for both, because the fields are the same and
 * "add" is just an edit of something that does not exist yet.
 *
 * Kept composed while animating out (see [AnimatedModalCard]) — the caller flips [visible] and
 * leaves the draft in place.
 */
@Composable
fun FolderEditorDialog(
    visible: Boolean,
    draft: FolderDraft,
    onDismiss: () -> Unit,
    onSave: (FolderDraft) -> Unit
) {
    // Seeded per folder, so reopening the dialog starts from what is stored rather than from
    // whatever was typed and abandoned last time.
    var edited by remember(draft.id, visible) { mutableStateOf(draft) }
    AnimatedModalCard(
        visible = visible,
        onDismissRequest = onDismiss,
        modifier = Modifier.widthIn(max = 420.dp)
    ) {
        Text(
            text = if (draft.id == null) t(S.FOLDER_NEW_TITLE) else t(S.FOLDER_EDIT_TITLE),
            color = SlipnetTextPrimary,
            fontSize = 16.sp
        )
        Spacer(Modifier.height(12.dp))
        LabeledField(t(S.FOLDER_NAME)) {
            SlipnetTextField(edited.name, { edited = edited.copy(name = it) })
        }
        LabeledField(t(S.FOLDER_URL)) {
            SlipnetTextField(
                edited.url,
                { edited = edited.copy(url = it) },
                hint = "https://…"
            )
        }
        Column(Modifier.fillMaxWidth()) {
            SlipnetCheckbox(
                checked = edited.enabled,
                onCheckedChange = { edited = edited.copy(enabled = it) },
                label = t(S.FOLDER_UPDATES_ENABLED)
            )
            SlipnetCheckbox(
                checked = edited.autoUpdate,
                onCheckedChange = { edited = edited.copy(autoUpdate = it) },
                label = t(S.FOLDER_AUTO_UPDATE)
            )
            SlipnetCheckbox(
                checked = edited.allowReorder,
                onCheckedChange = { edited = edited.copy(allowReorder = it) },
                label = t(S.FOLDER_ALLOW_REORDER)
            )
            SlipnetCheckbox(
                checked = edited.showInfo,
                onCheckedChange = { edited = edited.copy(showInfo = it) },
                label = t(S.FOLDER_SHOW_INFO)
            )
        }
        Spacer(Modifier.height(12.dp))
        PrimaryButton(t(S.SAVE_BTN), { onSave(edited) })
    }
}
