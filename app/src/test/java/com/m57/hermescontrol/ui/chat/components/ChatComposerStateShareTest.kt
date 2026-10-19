package com.m57.hermescontrol.ui.chat.components

import androidx.compose.foundation.text.input.TextFieldState
import org.junit.Assert.assertEquals
import org.junit.Test

class ChatComposerStateShareTest {
    @Test
    fun `shared text fills an empty composer`() {
        val state = TextFieldState()
        state.appendSharedComposerDraft("https://example.com")
        assertEquals("https://example.com", state.text.toString())
    }

    @Test
    fun `shared text never replaces an existing draft`() {
        val state = TextFieldState("half-written thought")
        state.appendSharedComposerDraft("https://example.com")
        assertEquals("half-written thought\n\nhttps://example.com", state.text.toString())
    }

    @Test
    fun `blank shared text leaves the draft alone`() {
        val state = TextFieldState("keep me")
        state.appendSharedComposerDraft("   ")
        assertEquals("keep me", state.text.toString())
    }
}
