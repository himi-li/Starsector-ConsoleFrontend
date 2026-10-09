package org.dsh.frontend;

import com.fs.starfarer.api.input.InputEventAPI;
import org.lwjgl.input.Keyboard;

import java.util.List;

/** 纯 Java 的按键组合检测（语义与 Console Commands 的 Keystroke 一致，但不引用其 Kotlin 类）。 */
public final class Hotkey {

    public final int keyCode;
    public final boolean ctrl;
    public final boolean alt;
    public final boolean shift;

    public Hotkey(int keyCode, boolean ctrl, boolean alt, boolean shift) {
        this.keyCode = keyCode;
        this.ctrl = ctrl;
        this.alt = alt;
        this.shift = shift;
    }

    public boolean isPressed(List<? extends InputEventAPI> events) {
        if (events == null) {
            return false;
        }
        for (InputEventAPI e : events) {
            if (e == null || e.isConsumed() || !e.isKeyDownEvent() || e.getEventValue() != keyCode) {
                continue;
            }
            if (ctrl && !e.isCtrlDown()) {
                return false;
            }
            if (alt && !e.isAltDown()) {
                return false;
            }
            if (shift && !e.isShiftDown()) {
                return false;
            }
            e.consume();
            return true;
        }
        return false;
    }

    public String describe() {
        String s;
        try {
            s = Keyboard.getKeyName(keyCode);
        } catch (Throwable t) {
            s = null;
        }
        if (s == null || s.isEmpty()) {
            s = "KEY" + keyCode;
        }
        s = s.toUpperCase();
        if (shift) {
            s = "SHIFT+" + s;
        }
        if (alt) {
            s = "ALT+" + s;
        }
        if (ctrl) {
            s = "CTRL+" + s;
        }
        return s;
    }
}
