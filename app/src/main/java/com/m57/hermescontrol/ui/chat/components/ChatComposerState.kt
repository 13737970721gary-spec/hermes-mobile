package com.m57.hermescontrol.ui.chat.components

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.ui.text.input.TextFieldValue
import com.m57.hermescontrol.ui.chat.ChatInputPolicy

/** Apply an intentional external draft replacement, never a value mirror during ordinary typing. */
internal fun TextFieldState.replaceComposerDraft(text: String) {
    val replacement = ChatInputPolicy.commandFieldValue(text)
    edit {
        replace(0, length, replacement.text)
        selection = replacement.selection
    }
}

/** Recheck the live draft when a rejected send is returned; it may have changed since dispatch. */
internal fun TextFieldState.restoreRejectedComposerDraft(rejectedText: String) {
    val current = TextFieldValue(text.toString(), selection)
    val restored = ChatInputPolicy.restoreRejectedText(rejectedText, current)
    edit {
        replace(0, length, restored.text)
        selection = restored.selection
    }
}

/** Shared-in text goes after whatever the user already typed; an existing draft is never replaced (#1601). */
internal fun TextFieldState.appendSharedComposerDraft(sharedText: String) {
    if (sharedText.isBlank()) return
    val current = text.toString()
    replaceComposerDraft(if (current.isBlank()) sharedText else "$current\n\n$sharedText")
}

/** Speech results arrive asynchronously: append to the draft as it exists at result time. */
internal fun TextFieldState.appendSpeechComposerDraft(spokenText: String) {
    if (spokenText.isBlank()) return
    val current = text.toString()
    replaceComposerDraft(if (current.isBlank()) spokenText else "$current $spokenText")
}
