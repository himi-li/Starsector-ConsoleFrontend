package org.dsh.frontend;

import com.fs.starfarer.api.Global;
import org.json.JSONObject;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 命令参数的持久化存储：写入 Starsector 的公共数据目录
 * （实际落盘为 saves/common/config/console_frontend_params.json.data）。
 * 语义：玩家设置过的参数会被记住，下次使用仍是上次的值，直到再次修改。
 */
public final class ParamStore {

    public static final String PATH = "config/console_frontend_params.json";
    private static final int VERSION = 1;

    private static final Map<String, Map<String, String>> values = new LinkedHashMap<String, Map<String, String>>();
    private static boolean loaded = false;
    private static boolean dirty = false;

    private ParamStore() {
    }

    public static synchronized void load() {
        values.clear();
        loaded = true;
        dirty = false;
        try {
            String raw = Global.getSettings().readTextFileFromCommon(PATH);
            if (raw == null || raw.trim().isEmpty()) {
                return;
            }
            JSONObject root = new JSONObject(raw);
            JSONObject params = root.optJSONObject("params");
            if (params == null) {
                return;
            }
            for (Iterator<?> it = params.keys(); it.hasNext(); ) {
                String cmd = String.valueOf(it.next());
                JSONObject entry = params.optJSONObject(cmd);
                if (entry == null) {
                    continue;
                }
                Map<String, String> map = new LinkedHashMap<String, String>();
                for (Iterator<?> kt = entry.keys(); kt.hasNext(); ) {
                    String key = String.valueOf(kt.next());
                    map.put(key, String.valueOf(entry.opt(key)));
                }
                values.put(cmd.toLowerCase(), map);
            }
        } catch (Throwable t) {
            values.clear();
        }
    }

    private static void ensureLoaded() {
        if (!loaded) {
            load();
        }
    }

    public static synchronized String get(String command, String key, String fallback) {
        ensureLoaded();
        if (command == null || key == null) {
            return fallback;
        }
        Map<String, String> map = values.get(command.toLowerCase());
        if (map == null) {
            return fallback;
        }
        String v = map.get(key);
        return v == null ? fallback : v;
    }

    public static synchronized boolean has(String command, String key) {
        ensureLoaded();
        if (command == null || key == null) {
            return false;
        }
        Map<String, String> map = values.get(command.toLowerCase());
        return map != null && map.containsKey(key);
    }

    public static synchronized void set(String command, String key, String value) {
        ensureLoaded();
        if (command == null || key == null) {
            return;
        }
        if (!FrontendSettings.rememberParams) {
            return;
        }
        String cmd = command.toLowerCase();
        Map<String, String> map = values.get(cmd);
        if (map == null) {
            map = new LinkedHashMap<String, String>();
            values.put(cmd, map);
        }
        if (value == null) {
            map.remove(key);
        } else {
            map.put(key, value);
        }
        dirty = true;
        save();
    }

    public static synchronized void reset(String command) {
        ensureLoaded();
        if (command == null) {
            return;
        }
        values.remove(command.toLowerCase());
        dirty = true;
        save();
    }

    public static synchronized void resetAll() {
        ensureLoaded();
        values.clear();
        dirty = true;
        save();
    }

    public static synchronized void save() {
        if (!dirty) {
            return;
        }
        try {
            JSONObject root = new JSONObject();
            root.put("version", VERSION);
            JSONObject params = new JSONObject();
            for (Map.Entry<String, Map<String, String>> e : values.entrySet()) {
                JSONObject entry = new JSONObject();
                for (Map.Entry<String, String> kv : e.getValue().entrySet()) {
                    entry.put(kv.getKey(), kv.getValue());
                }
                params.put(e.getKey(), entry);
            }
            root.put("params", params);
            Global.getSettings().writeTextFileToCommon(PATH, root.toString(2));
            dirty = false;
        } catch (Throwable t) {
            // 写失败时退化为会话内记忆
            try {
                Global.getLogger(ParamStore.class).warn("无法保存前端面板参数: " + t);
            } catch (Throwable ignored) {
            }
        }
    }

    public static synchronized int size() {
        ensureLoaded();
        return values.size();
    }
}
