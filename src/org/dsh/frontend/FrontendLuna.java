package org.dsh.frontend;

import com.fs.starfarer.api.Global;
import lunalib.lunaSettings.LunaSettings;
import lunalib.lunaSettings.LunaSettingsListener;
import org.lwjgl.input.Keyboard;

/**
 * LunaLib 设置接入。
 * 本类只会在确认 lunalib 已启用后才被加载（ConsoleFrontendModPlugin 里做 isModEnabled 判断），
 * 因此未安装 LunaLib 时不会出现 NoClassDefFoundError。
 */
public final class FrontendLuna {

    private static final String MOD = FrontendSettings.MOD_ID;
    private static boolean installed;

    private FrontendLuna() {
    }

    public static void install() {
        if (installed) {
            return;
        }
        installed = true;
        LunaSettings.addSettingsListener(new LunaSettingsListener() {
            @Override
            public void settingsChanged(String modID) {
                if (MOD.equals(modID)) {
                    update();
                }
            }
        });
        update();
    }

    public static void update() {
        try {
            Integer key = LunaSettings.getInt(MOD, "cf_openKeybind");
            Boolean ctrl = LunaSettings.getBoolean(MOD, "cf_holdCTRL");
            Boolean alt = LunaSettings.getBoolean(MOD, "cf_holdALT");
            Boolean shift = LunaSettings.getBoolean(MOD, "cf_holdSHIFT");
            if (key != null) {
                FrontendSettings.openKeybind = new Hotkey(key.intValue(),
                        ctrl != null && ctrl.booleanValue(),
                        alt != null && alt.booleanValue(),
                        shift != null && shift.booleanValue());
            }
        } catch (Throwable ignored) {
        }
        try {
            Double v = LunaSettings.getDouble(MOD, "cf_panelWidthFraction");
            if (v != null) {
                FrontendSettings.panelWidthFraction = v.doubleValue();
            }
        } catch (Throwable ignored) {
        }
        try {
            Double v = LunaSettings.getDouble(MOD, "cf_backgroundDarkening");
            if (v != null) {
                FrontendSettings.backgroundDarkening = v.doubleValue();
            }
        } catch (Throwable ignored) {
        }
        try {
            Integer v = LunaSettings.getInt(MOD, "cf_buttonColumns");
            if (v != null) {
                FrontendSettings.buttonColumns = Math.max(1, v.intValue());
            }
        } catch (Throwable ignored) {
        }
        try {
            Integer v = LunaSettings.getInt(MOD, "cf_logLines");
            if (v != null) {
                FrontendSettings.logLines = Math.max(5, v.intValue());
            }
        } catch (Throwable ignored) {
        }
        try {
            Integer v = LunaSettings.getInt(MOD, "cf_pickerPageSize");
            if (v != null) {
                FrontendSettings.pickerPageSize = Math.max(5, v.intValue());
            }
        } catch (Throwable ignored) {
        }
        Boolean b;
        b = safeBool("cf_showUnavailable");
        if (b != null) {
            FrontendSettings.showUnavailable = b.booleanValue();
        }
        b = safeBool("cf_confirmDestructive");
        if (b != null) {
            FrontendSettings.confirmDestructive = b.booleanValue();
        }
        b = safeBool("cf_showOutputLog");
        if (b != null) {
            FrontendSettings.showOutputLog = b.booleanValue();
        }
        b = safeBool("cf_rememberParams");
        if (b != null) {
            FrontendSettings.rememberParams = b.booleanValue();
        }
        b = safeBool("cf_idShowSourceMod");
        if (b != null) {
            FrontendSettings.idShowSourceMod = b.booleanValue();
        }
        b = safeBool("cf_useEnglishLabels");
        if (b != null) {
            FrontendSettings.useEnglishLabels = b.booleanValue();
        }
        // 界面字体不进 LunaSettings：LunaLib 的 Radio/Multichoice 选项来自
        // LunaSettings.csv 的静态 secondaryValue（编译期写死），而可用字体列表是
        // 运行时扫出来的，填不进去。字体由面板内的字体选择器承载，
        // 选择结果存在 FontStore（saves/common/config/console_frontend_font.json.data）。
    }

    private static Boolean safeBool(String key) {
        try {
            return LunaSettings.getBoolean(MOD, key);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 供 LunaSettings 的 Keycode 字段使用的默认值（~ 键）。 */
    public static int defaultKeyCode() {
        return Keyboard.KEY_GRAVE;
    }

    public static void logMissing() {
        try {
            Global.getLogger(FrontendLuna.class).info("未检测到 LunaLib，控制台前端使用内置默认设置。");
        } catch (Throwable ignored) {
        }
    }
}
