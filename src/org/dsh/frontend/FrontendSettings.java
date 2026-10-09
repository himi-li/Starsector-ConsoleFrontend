package org.dsh.frontend;

import org.lwjgl.input.Keyboard;

/**
 * 面板设置。默认值写在这里；若装了 LunaLib，则由 FrontendLuna 覆盖。
 * 不直接引用 lunalib 的类，避免未装 LunaLib 时出现 NoClassDefFoundError。
 */
public final class FrontendSettings {

    public static final String MOD_ID = "console_frontend";

    public static Hotkey openKeybind = new Hotkey(Keyboard.KEY_GRAVE, true, false, false);

    public static double panelWidthFraction = 0.80;
    public static double backgroundDarkening = 0.875;
    public static int buttonColumns = 4;
    public static float buttonHeight = 28f;
    public static int logLines = 60;
    public static boolean showUnavailable = false;
    public static boolean confirmDestructive = true;
    public static boolean showOutputLog = true;
    public static boolean rememberParams = true;
    public static boolean idShowSourceMod = true;
    public static int pickerPageSize = 40;
    public static boolean useEnglishLabels = false;

    private FrontendSettings() {
    }

    public static String hotkeyText() {
        try {
            return openKeybind == null ? "CTRL+~" : openKeybind.describe();
        } catch (Throwable t) {
            return "CTRL+~";
        }
    }
}
