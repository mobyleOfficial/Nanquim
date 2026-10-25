package com.mobyle.nanquim.text

import android.annotation.SuppressLint
import android.content.Context
import android.text.Editable
import android.text.InputType
import android.text.Selection
import android.text.SpannableStringBuilder
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager

@SuppressLint("ViewConstructor")
class TextInputView(
    context: Context,
    private val editor: TextEditor,
) : View(context) {
    private val mirror = SpannableStringBuilder()
    private val inputMethods = context.getSystemService(InputMethodManager::class.java)

    init {
        isFocusable = true
        isFocusableInTouchMode = true
    }

    override fun onCheckIsTextEditor(): Boolean = true

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_FULLSCREEN or EditorInfo.IME_ACTION_NONE
        syncFromEditor()
        outAttrs.initialSelStart = Selection.getSelectionStart(mirror)
        outAttrs.initialSelEnd = Selection.getSelectionEnd(mirror)
        return Connection()
    }

    fun showKeyboard() {
        requestFocus()
        syncFromEditor()
        inputMethods.restartInput(this)
        inputMethods.showSoftInput(this, 0)
    }

    fun hideKeyboard() {
        inputMethods.hideSoftInputFromWindow(windowToken, 0)
    }

    fun syncFromEditor(restart: Boolean = true) {
        val editing = editor.editing ?: return
        if (mirror.toString() != editing.text) {
            mirror.replace(0, mirror.length, editing.text)
            BaseInputConnection.removeComposingSpans(mirror)
            if (restart) inputMethods.restartInput(this)
        }
        if (Selection.getSelectionStart(mirror) != editing.selectionStart || Selection.getSelectionEnd(mirror) != editing.selectionEnd) {
            Selection.setSelection(
                mirror,
                editing.selectionStart.coerceIn(0, mirror.length),
                editing.selectionEnd.coerceIn(0, mirror.length),
            )
            val composingStart = BaseInputConnection.getComposingSpanStart(mirror)
            val composingEnd = BaseInputConnection.getComposingSpanEnd(mirror)
            inputMethods.updateSelection(this, editing.selectionStart, editing.selectionEnd, composingStart, composingEnd)
        }
    }

    private fun publish() {
        editor.setTextAndSelection(
            mirror.toString(),
            Selection.getSelectionStart(mirror).coerceAtLeast(0),
            Selection.getSelectionEnd(mirror).coerceAtLeast(0),
        )
    }

    override fun onKeyDown(
        keyCode: Int,
        event: KeyEvent,
    ): Boolean {
        val shift = event.isShiftPressed
        when (keyCode) {
            KeyEvent.KEYCODE_DEL -> editor.deleteBackward()
            KeyEvent.KEYCODE_FORWARD_DEL -> editor.deleteForward()
            KeyEvent.KEYCODE_DPAD_LEFT -> editor.moveCaret(-1, shift)
            KeyEvent.KEYCODE_DPAD_RIGHT -> editor.moveCaret(1, shift)
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> editor.insert("\n")
            KeyEvent.KEYCODE_ESCAPE -> editor.finish()
            else -> {
                val character = event.unicodeChar
                if (character == 0 || event.isCtrlPressed) return super.onKeyDown(keyCode, event)
                editor.insert(String(Character.toChars(character)))
            }
        }
        syncFromEditor(restart = false)
        return true
    }

    private inner class Connection : BaseInputConnection(this@TextInputView, true) {
        override fun getEditable(): Editable = mirror

        override fun commitText(
            text: CharSequence?,
            newCursorPosition: Int,
        ): Boolean = super.commitText(text, newCursorPosition).also { publish() }

        override fun setComposingText(
            text: CharSequence?,
            newCursorPosition: Int,
        ): Boolean = super.setComposingText(text, newCursorPosition).also { publish() }

        override fun finishComposingText(): Boolean = super.finishComposingText().also { publish() }

        override fun deleteSurroundingText(
            beforeLength: Int,
            afterLength: Int,
        ): Boolean = super.deleteSurroundingText(beforeLength, afterLength).also { publish() }

        override fun deleteSurroundingTextInCodePoints(
            beforeLength: Int,
            afterLength: Int,
        ): Boolean = super.deleteSurroundingTextInCodePoints(beforeLength, afterLength).also { publish() }

        override fun setSelection(
            start: Int,
            end: Int,
        ): Boolean = super.setSelection(start, end).also { publish() }

        override fun setComposingRegion(
            start: Int,
            end: Int,
        ): Boolean = super.setComposingRegion(start, end).also { publish() }

        override fun sendKeyEvent(event: KeyEvent): Boolean {
            if (event.action == KeyEvent.ACTION_DOWN) onKeyDown(event.keyCode, event)
            return true
        }
    }
}
