package com.molina.studio.terminal

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.inputmethod.InputMethodManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.molina.terminal.TerminalSession
import com.molina.terminal.TerminalSessionClient
import com.molina.terminal.view.TerminalView
import com.molina.terminal.view.TerminalViewClient

private const val TAG = "MolinaTerminal"

/** State tombol modifier virtual (dibaca oleh TerminalView). */
class TerminalModifiers {
    var ctrl by mutableStateOf(false)
    var alt by mutableStateOf(false)
    var ctrlLocked by mutableStateOf(false)
    var altLocked by mutableStateOf(false)

    /** Tap: sekali pakai; bila sedang dikunci, tap membuka kunci. */
    fun tapCtrl() { if (ctrlLocked) { ctrlLocked = false; ctrl = false } else ctrl = !ctrl }
    fun tapAlt() { if (altLocked) { altLocked = false; alt = false } else alt = !alt }

    /** Long-press: kunci / buka kunci modifier. */
    fun toggleCtrlLock() { ctrlLocked = !ctrlLocked; ctrl = ctrlLocked }
    fun toggleAltLock() { altLocked = !altLocked; alt = altLocked }
}

class MolinaSessionClient : TerminalSessionClient {
    @Volatile var view: TerminalView? = null

    private fun clipboard(): ClipboardManager? =
        view?.context?.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager

    override fun onTextChanged(changedSession: TerminalSession) {
        if (changedSession === TerminalSessions.currentSession) view?.onScreenUpdated()
    }
    override fun onTitleChanged(changedSession: TerminalSession) { TerminalSessions.titleChanged() }
    override fun onSessionFinished(finishedSession: TerminalSession) {
        TerminalSessions.onSessionFinished(finishedSession)
    }
    override fun onCopyTextToClipboard(session: TerminalSession, text: String) {
        clipboard()?.setPrimaryClip(ClipData.newPlainText("molina", text))
    }
    override fun onPasteTextFromClipboard(session: TerminalSession?) {
        val ctx = view?.context ?: return
        val text = clipboard()?.primaryClip?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)?.coerceToText(ctx)?.toString()
        if (!text.isNullOrEmpty()) PasteGuard.pasteGuarded(ctx, text) { session?.emulator?.paste(it) }
    }
    override fun onBell(session: TerminalSession) {}
    override fun onColorsChanged(session: TerminalSession) { view?.onScreenUpdated() }
    override fun onTerminalCursorStateChange(state: Boolean) {}
    override fun getTerminalCursorStyle(): Int? = null

    override fun logError(tag: String, message: String) { MolinaLog.e(tag, message) }
    override fun logWarn(tag: String, message: String) { MolinaLog.w(tag, message) }
    override fun logInfo(tag: String, message: String) { MolinaLog.i(tag, message) }
    override fun logDebug(tag: String, message: String) { MolinaLog.d(tag, message) }
    override fun logVerbose(tag: String, message: String) { MolinaLog.v(tag, message) }
    override fun logStackTraceWithMessage(tag: String, message: String, e: Exception) { MolinaLog.e(tag, message, e) }
    override fun logStackTrace(tag: String, e: Exception) { MolinaLog.e(tag, TAG, e) }
}

class MolinaViewClient(
    private val view: TerminalView,
    private val mods: TerminalModifiers
) : TerminalViewClient {
    /** Pinch-to-zoom: tiap lonjakan skala menggeser font 1sp (rentang 8-14sp, disimpan). */
    override fun onScale(scale: Float): Float {
        if (scale < 0.9f || scale > 1.1f) {
            TerminalPrefs.setFontSp(TerminalPrefs.fontSp + if (scale > 1f) 1 else -1)
            return 1.0f
        }
        return scale
    }
    override fun onSingleTapUp(e: MotionEvent) {
        if (!TerminalPrefs.softKeyboardAllowed(view.context)) return
        val imm = view.context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT)
    }
    override fun shouldBackButtonBeMappedToEscape(): Boolean = false
    override fun shouldEnforceCharBasedInput(): Boolean = true
    override fun shouldUseCtrlSpaceWorkaround(): Boolean = false
    override fun isTerminalViewSelected(): Boolean = true
    override fun copyModeChanged(copyMode: Boolean) {}
    override fun onKeyDown(keyCode: Int, e: KeyEvent, session: TerminalSession): Boolean {
        MolinaLog.key(TAG, "onKeyDown(keyCode=$keyCode, action=${e.action})")
        return false
    }
    override fun onKeyUp(keyCode: Int, e: KeyEvent): Boolean {
        MolinaLog.key(TAG, "onKeyUp(keyCode=$keyCode)")
        return false
    }
    override fun onLongPress(event: MotionEvent): Boolean = false
    override fun readControlKey(): Boolean = mods.ctrl
    override fun readAltKey(): Boolean = mods.alt
    override fun readShiftKey(): Boolean = false
    override fun readFnKey(): Boolean = false
    override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession): Boolean {
        MolinaLog.key(TAG, "onCodePoint(codePoint=$codePoint, ctrlDown=$ctrlDown)")
        if (mods.ctrl && !mods.ctrlLocked) mods.ctrl = false // sekali pakai kecuali dikunci
        if (mods.alt && !mods.altLocked) mods.alt = false
        return false
    }
    override fun onEmulatorSet() {}

    override fun logError(tag: String, message: String) { MolinaLog.e(tag, message) }
    override fun logWarn(tag: String, message: String) { MolinaLog.w(tag, message) }
    override fun logInfo(tag: String, message: String) { MolinaLog.i(tag, message) }
    override fun logDebug(tag: String, message: String) { MolinaLog.d(tag, message) }
    override fun logVerbose(tag: String, message: String) { MolinaLog.v(tag, message) }
    override fun logStackTraceWithMessage(tag: String, message: String, e: Exception) { MolinaLog.e(tag, message, e) }
    override fun logStackTrace(tag: String, e: Exception) { MolinaLog.e(tag, TAG, e) }
}
