package org.dsh.frontend;

import com.fs.starfarer.api.Global;
import org.json.JSONObject;

/**
 * 玩家在面板里选定的界面字体（写入 Starsector 公共数据目录，实际落盘为
 * saves/common/config/console_frontend_font.json.data）。
 *
 * <p>只存一个 classpath 相对名（如 {@code graphics/fonts/cf_xxx.fnt}）；
 * 空串表示使用游戏自带的 victor16。选择本身不做校验 —— 字体是否还在由
 * {@link FontCatalog} 在每次重建时核对，玩家删掉字体/mod 后会自动回退。
 *
 * <p><b>为什么不放 LunaSettings</b>：LunaLib 的 Radio/Multichoice 选项来自
 * LuaSettings.csv 的静态 secondaryValue（编译期写死），而字体列表是运行时扫出来的，
 * 填不进去，所以字体选择只能由面板自己的选择器承载、自己落盘。
 */
public final class FontStore {

    /** 公共数据目录里的相对路径（与 ParamStore 同一套读写接口）。 */
    public static final String PATH = "config/console_frontend_font.json";
    private static final int VERSION = 1;

    /** 当前选定的字体路径；空串 = 游戏自带字体。 */
    private static String selected = "";
    private static boolean loaded = false;
    private static boolean dirty = false;

    private FontStore() {
    }

    /** 从公共数据目录读回上次的选择；失败一律当「未选择」。 */
    public static synchronized void load() {
        loaded = true;
        dirty = false;
        selected = "";
        try {
            String raw = Global.getSettings().readTextFileFromCommon(PATH);
            if (raw == null || raw.trim().isEmpty()) {
                return;
            }
            String v = new JSONObject(raw).optString("font", "");
            selected = v == null ? "" : v.trim();
        } catch (Throwable t) {
            selected = "";
        }
    }

    private static void ensureLoaded() {
        if (!loaded) {
            load();
        }
    }

    /** 当前选定的字体路径（空串 = 游戏自带）。 */
    public static synchronized String selected() {
        ensureLoaded();
        return selected;
    }

    /** 记录选择并立刻落盘（传空串 = 回到游戏自带字体）。 */
    public static synchronized void select(String path) {
        ensureLoaded();
        selected = path == null ? "" : path.trim();
        dirty = true;
        save();
    }

    private static synchronized void save() {
        if (!dirty) {
            return;
        }
        try {
            JSONObject root = new JSONObject();
            root.put("version", VERSION);
            root.put("font", selected);
            Global.getSettings().writeTextFileToCommon(PATH, root.toString(2));
            dirty = false;
        } catch (Throwable t) {
            try {
                Global.getLogger(FontStore.class).warn("无法保存界面字体设置: " + t);
            } catch (Throwable ignored) {
            }
        }
    }
}
