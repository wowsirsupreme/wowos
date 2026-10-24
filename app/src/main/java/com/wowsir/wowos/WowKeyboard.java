package com.wowsir.wowos;

import android.inputmethodservice.InputMethodService;
import android.text.InputType;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;

/** WOW Keyboard: an on-screen keyboard made for TV remotes. */
public class WowKeyboard extends InputMethodService implements KeysView.Listener {

    private KeysView keys;
    private EditorInfo editor;

    @Override
    public View onCreateInputView() {
        keys = new KeysView(this);
        keys.setListener(this);
        return keys;
    }

    /** Never take over the whole TV screen with the big "extract" text box. */
    @Override
    public boolean onEvaluateFullscreenMode() {
        return false;
    }

    @Override
    public void onStartInputView(EditorInfo info, boolean restarting) {
        super.onStartInputView(info, restarting);
        editor = info;
        if (keys == null) return;
        int cls = info.inputType & InputType.TYPE_MASK_CLASS;
        boolean numeric = cls == InputType.TYPE_CLASS_NUMBER || cls == InputType.TYPE_CLASS_PHONE
                || cls == InputType.TYPE_CLASS_DATETIME;
        keys.reset(numeric);
        keys.setEnterLabel(enterLabel(info));
        boolean capWords = (info.inputType & (InputType.TYPE_TEXT_FLAG_CAP_SENTENCES | InputType.TYPE_TEXT_FLAG_CAP_WORDS)) != 0;
        if (capWords && isAtStart()) keys.toggleShift();
    }

    private boolean isAtStart() {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return false;
        CharSequence before = ic.getTextBeforeCursor(1, 0);
        return TextUtils.isEmpty(before);
    }

    private static String enterLabel(EditorInfo info) {
        if ((info.imeOptions & EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0) return "↵";
        switch (info.imeOptions & EditorInfo.IME_MASK_ACTION) {
            case EditorInfo.IME_ACTION_GO: return "Go";
            case EditorInfo.IME_ACTION_SEARCH: return "Search";
            case EditorInfo.IME_ACTION_SEND: return "Send";
            case EditorInfo.IME_ACTION_NEXT: return "Next";
            case EditorInfo.IME_ACTION_DONE: return "Done";
            default: return "↵";
        }
    }

    // ------------------------------------------------------------------ remote keys

    private boolean shown() {
        return keys != null && isInputViewShown();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (!shown()) return super.onKeyDown(keyCode, event);
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_UP:
                if (!keys.move(-1, 0)) requestHideSelf(0);
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                keys.move(1, 0);
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                keys.move(0, -1);
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                keys.move(0, 1);
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                // Holding OK repeats only for backspace and the cursor arrows.
                if (event.getRepeatCount() > 0) {
                    int code = keys.selected().code;
                    if (code != KeysView.DEL && code != KeysView.LEFT && code != KeysView.RIGHT) return true;
                }
                keys.press();
                return true;
            default:
                return super.onKeyDown(keyCode, event);
        }
    }

    /** Swallow the matching key-ups so the app behind doesn't also react. */
    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (shown()) {
            switch (keyCode) {
                case KeyEvent.KEYCODE_DPAD_UP:
                case KeyEvent.KEYCODE_DPAD_DOWN:
                case KeyEvent.KEYCODE_DPAD_LEFT:
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                case KeyEvent.KEYCODE_NUMPAD_ENTER:
                    return true;
                default:
                    break;
            }
        }
        return super.onKeyUp(keyCode, event);
    }

    // ------------------------------------------------------------------ typing

    @Override
    public void onKey(KeysView.Key key) {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;
        switch (key.code) {
            case KeysView.SHIFT:
                keys.toggleShift();
                break;
            case KeysView.MODE:
                keys.toggleMode();
                break;
            case KeysView.DEL:
                CharSequence sel = ic.getSelectedText(0);
                if (!TextUtils.isEmpty(sel)) ic.commitText("", 1);
                else ic.deleteSurroundingText(1, 0);
                break;
            case KeysView.LEFT:
                sendDownUpKeyEvents(KeyEvent.KEYCODE_DPAD_LEFT);
                break;
            case KeysView.RIGHT:
                sendDownUpKeyEvents(KeyEvent.KEYCODE_DPAD_RIGHT);
                break;
            case KeysView.ENTER:
                enter(ic);
                break;
            default:
                String s = keys.labelFor(key);
                if (key.code == ' ') s = " ";
                ic.commitText(s, 1);
                if (Character.isLetter(key.code)) keys.consumeShift();
                break;
        }
    }

    private void enter(InputConnection ic) {
        EditorInfo info = editor;
        if (info != null) {
            int action = info.imeOptions & EditorInfo.IME_MASK_ACTION;
            boolean multiLine = (info.inputType & InputType.TYPE_TEXT_FLAG_MULTI_LINE) != 0;
            boolean noAction = (info.imeOptions & EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0;
            if (multiLine && noAction) {
                ic.commitText("\n", 1);
                return;
            }
            if (!noAction && action != EditorInfo.IME_ACTION_NONE && action != EditorInfo.IME_ACTION_UNSPECIFIED) {
                ic.performEditorAction(action);
                return;
            }
            if (multiLine) {
                ic.commitText("\n", 1);
                return;
            }
        }
        sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER);
    }
}
