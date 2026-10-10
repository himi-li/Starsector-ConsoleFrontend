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

    /** 未选择自定义字体时使用的游戏自带字体，也是自定义字体不可用时的回退字体。 */
    public static final String FALLBACK_FONT_PATH = "graphics/fonts/victor16.fnt";

    /** 回退字体的行高（victor16.fnt 表头 common lineHeight）。 */
    public static final float FALLBACK_LINE_HEIGHT = 18f;

    /**
     * 界面字体由 <b>运行时探测 + 玩家选择</b>决定，本 mod 不打包任何字体。
     *
     * <p>{@link FontCatalog} 扫描游戏本体与各 enabled mod 的 {@code graphics/fonts}
     * 目录（读 .fnt 表头拿行高与中文字形数），玩家在面板内的字体选择器里挑；
     * 选中的路径由 {@link FontStore} 记住（saves/common/config/console_frontend_font.json.data）。
     *
     * <p><b>注意</b>：字体路径写进控件前必须先经 {@code SettingsAPI.loadFont(path)} 注册 ——
     * 游戏按路径查字体表（{@code com.fs.graphics.A.D} 只查表、不按需加载），
     * 未注册的路径查表得 null，随后测量文本对 null 调 {@code $dynfontRawNominal()}
     * 抛 NPE，整个面板变空白（实测）。注册成功与否由
     * {@code FrontendPanel.prepareFont()} 判定，失败一律回退到自带 victor16。
     */

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
