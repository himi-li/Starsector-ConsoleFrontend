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

    /** 是否使用 mod 内置字体（Zpix，MIT）。默认关闭。 */
    public static boolean builtinFont = false;
    /** 内置字体字号，可选 12/14/16/18/20/22/24。 */
    public static int fontSize = 16;

    /** 内置字体可选的七档字号；LunaSettings 用 Int 输入框，落到非档位值时就近吸附。 */
    public static final int[] FONT_SIZES = {12, 14, 16, 18, 20, 22, 24};

    /** 把任意字号吸附到最近的已内置档位。 */
    public static int clampFontSize(int v) {
        int best = FONT_SIZES[0];
        int bestDist = Math.abs(v - best);
        for (int i = 1; i < FONT_SIZES.length; i++) {
            int d = Math.abs(v - FONT_SIZES[i]);
            if (d < bestDist) {
                bestDist = d;
                best = FONT_SIZES[i];
            }
        }
        return best;
    }

    /**
     * 当前应使用的正文 / 标签 / 文本框字体路径。
     *
     * <p>不带字体版恒为游戏自带的 victor16.fnt（行为与 0.1.1 逐位一致）；
     * 启用内置字体时换成 mod 自带的 Zpix 像素字体
     * （graphics/fonts/cf_zpix_NN.fnt，.fnt 与同名 _0.png 一起放在 graphics/fonts 下）。
     */
    public static String paraFontPath() {
        if (!builtinFont) {
            return "graphics/fonts/victor16.fnt";
        }
        return "graphics/fonts/cf_zpix_" + clampFontSize(fontSize) + ".fnt";
    }

    /**
     * 当前字体的行高（像素）。
     *
     * <p>数值取自各 .fnt 表头的 common lineHeight：victor16 = 18，
     * Zpix 七档依次为 12/15/17/18/21/23/24。纵向布局常量全部按它缩放。
     */
    public static float paraLineHeight() {
        if (!builtinFont) {
            return 18f;
        }
        switch (clampFontSize(fontSize)) {
            case 12: return 12f;
            case 14: return 15f;
            case 16: return 17f;
            case 18: return 18f;
            case 20: return 21f;
            case 22: return 23f;
            default: return 24f;
        }
    }

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
