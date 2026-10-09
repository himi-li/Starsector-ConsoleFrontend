package org.dsh.frontend;

import com.fs.starfarer.api.Global;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 加载 data/strings/frontend_labels.json：中文标签、分类、参数 schema、run 模板。 */
public final class FrontendLabels {

    public static class Category {
        public String id = "";
        public String name = "";
        public int order = 500;
    }

    /** 精选命令定义（JSON 里的 commands.<key>）。 */
    public static class Curated {
        public String command = "";
        public String label = "";
        public String description = "";
        public String category = "all";
        public boolean confirm = false;
        public List<String> run = new ArrayList<String>();
        public List<ParamSpec> params = new ArrayList<ParamSpec>();
    }

    private static final Map<String, Curated> curated = new LinkedHashMap<String, Curated>();
    private static final Map<String, Category> categories = new LinkedHashMap<String, Category>();
    private static final List<String> order = new ArrayList<String>();
    private static boolean loaded = false;
    private static boolean ok = false;

    private FrontendLabels() {
    }

    public static boolean isLoadedOk() {
        return ok;
    }

    public static Map<String, Category> categories() {
        ensure();
        return categories;
    }

    public static List<String> order() {
        ensure();
        return order;
    }

    public static Curated get(String command) {
        ensure();
        if (command == null) {
            return null;
        }
        return curated.get(command.toLowerCase());
    }

    public static synchronized void reload() {
        loaded = false;
        ok = false;
        ensure();
    }

    private static synchronized void ensure() {
        if (loaded) {
            return;
        }
        loaded = true;
        curated.clear();
        categories.clear();
        order.clear();
        try {
            String raw = Global.getSettings().loadText("data/strings/frontend_labels.json", FrontendSettings.MOD_ID);
            if (raw == null || raw.trim().isEmpty()) {
                return;
            }
            JSONObject root = new JSONObject(raw);

            JSONObject cats = root.optJSONObject("categories");
            if (cats != null) {
                for (Iterator<?> it = cats.keys(); it.hasNext(); ) {
                    String id = String.valueOf(it.next());
                    JSONObject c = cats.optJSONObject(id);
                    Category cat = new Category();
                    cat.id = id;
                    cat.name = c == null ? id : c.optString("name", id);
                    cat.order = c == null ? 500 : c.optInt("order", 500);
                    categories.put(id, cat);
                }
            }

            JSONArray ord = root.optJSONArray("order");
            if (ord != null) {
                for (int i = 0; i < ord.length(); i++) {
                    order.add(String.valueOf(ord.get(i)).toLowerCase());
                }
            }

            JSONObject cmds = root.optJSONObject("commands");
            if (cmds != null) {
                for (Iterator<?> it = cmds.keys(); it.hasNext(); ) {
                    String name = String.valueOf(it.next());
                    JSONObject c = cmds.optJSONObject(name);
                    if (c == null) {
                        continue;
                    }
                    Curated cur = new Curated();
                    cur.command = name.toLowerCase();
                    cur.label = c.optString("label", name);
                    cur.description = c.optString("desc", "");
                    cur.category = c.optString("category", "all");
                    cur.confirm = c.optBoolean("confirm", false);

                    JSONArray runArr = c.optJSONArray("run");
                    if (runArr != null) {
                        for (int i = 0; i < runArr.length(); i++) {
                            cur.run.add(String.valueOf(runArr.get(i)));
                        }
                    } else {
                        String runStr = c.optString("run", null);
                        if (runStr != null && !runStr.trim().isEmpty()) {
                            cur.run.add(runStr);
                        }
                    }

                    JSONArray ps = c.optJSONArray("params");
                    if (ps != null) {
                        for (int i = 0; i < ps.length(); i++) {
                            JSONObject p = ps.optJSONObject(i);
                            if (p == null) {
                                continue;
                            }
                            ParamSpec spec = new ParamSpec();
                            spec.key = p.optString("key", "");
                            spec.label = p.optString("label", spec.key);
                            spec.type = p.optString("type", ParamSpec.TYPE_TEXT);
                            spec.source = p.optString("source", null);
                            spec.primary = p.optString("primary", "name");
                            spec.secondary = p.optString("secondary", "id");
                            spec.searchable = p.optBoolean("searchable", true);
                            spec.hint = p.optString("hint", "");
                            spec.required = p.optBoolean("required", false);
                            if (p.has("min")) {
                                spec.min = p.optDouble("min");
                            }
                            if (p.has("max")) {
                                spec.max = p.optDouble("max");
                            }
                            if (p.has("default")) {
                                Object d = p.opt("default");
                                spec.defaultValue = d == null ? "" : String.valueOf(d);
                            }
                            JSONArray opts = p.optJSONArray("options");
                            if (opts != null) {
                                for (int k = 0; k < opts.length(); k++) {
                                    spec.options.add(String.valueOf(opts.get(k)));
                                }
                            }
                            if (spec.key != null && !spec.key.isEmpty()) {
                                cur.params.add(spec);
                            }
                        }
                    }
                    curated.put(cur.command, cur);
                }
            }
            ok = true;
        } catch (Throwable t) {
            ok = false;
            try {
                Global.getLogger(FrontendLabels.class).warn("加载 frontend_labels.json 失败，改用自动枚举模式: " + t);
            } catch (Throwable ignored) {
            }
        }
    }

    public static List<Category> sortedCategories() {
        ensure();
        List<Category> l = new ArrayList<Category>(categories.values());
        Collections.sort(l, new Comparator<Category>() {
            @Override
            public int compare(Category a, Category b) {
                return a.order != b.order ? Integer.compare(a.order, b.order) : a.id.compareTo(b.id);
            }
        });
        return l;
    }
}