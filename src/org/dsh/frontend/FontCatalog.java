package org.dsh.frontend;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.ModSpecAPI;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 可用字体探测：扫描游戏与各 enabled mod 的 {@code graphics/fonts} 目录，
 * 读出每个 {@code .fnt} 的行高与中文字形数，供面板里的字体选择器使用。
 *
 * <p><b>为什么不能用 java.io.File 扫目录</b>：游戏对 mod 脚本有类加载黑名单
 * （{@code com.fs.starfarer.loading.scripts.B}），{@code java.io.File} /
 * {@code java.nio.file.Files} 都在黑名单里，脚本一碰就抛
 * {@code SecurityException: File access and reflection are not allowed to scripts.}。
 * 唯一可用的目录列举是游戏自己的资源管理器 {@code com.fs.util.C}：
 * <pre>
 *   com.fs.util.C c = com.fs.util.C.Ó00000();              // 单例
 *   List&lt;String&gt; files = c.o00000(dir, ".fnt", true);      // 目录列举，返回绝对路径
 *   InputStream in = c.Ó00000(absolutePath, true);          // 开流（BufferedReader 在白名单里）
 * </pre>
 * 注意方法名里的 {@code Ó} 是 U+00D3、{@code Ô} 是 U+00D4，不是 ASCII 的 O；
 * 另外 {@code Ó00000(String)} 单参重载返回的是 List（内部 setter），
 * 开流必须用两参的 {@code Ó00000(String,boolean)}。
 *
 * <p><b>为什么要读表头</b>：游戏没有任何「列举字体」的 API
 * （{@code SettingsAPI} 只有 {@code loadFont(String)} / {@code openStream(String)}），
 * 也没用配置文件登记字体清单，所以只能自己扫目录 + 解析 .fnt 前几行：
 * {@code common lineHeight=N} 给行高，{@code chars count=N} 给字形总数，
 * 再数 {@code char id=N} 落在 U+4E00–U+9FFF 的条数判断是否含中文
 * （实测：含中文的字体 chars 约 6500–6750、cjk 约 6300–6500；
 * 纯拉丁字体 chars 仅 95–256、cjk=0）。{@code chars count} 行出现在 char 行之前，
 * 所以字形总数很小的字体可以直接停止读取，不必逐行数完。
 */
public final class FontCatalog {

    /** 一个可选字体。 */
    public static final class Entry {
        /** 注册进游戏字体表用的 classpath 相对名，如 {@code graphics/fonts/victor16.fnt}。 */
        public final String path;
        /** 磁盘绝对路径（只用于读取表头）。 */
        public final String absolute;
        /** 显示名（文件名去掉 .fnt）。 */
        public final String name;
        /** 来源：游戏本体 或 mod 名。 */
        public final String source;
        /** 行高（.fnt 表头 common lineHeight）。 */
        public final int lineHeight;
        /** 字形总数（chars count）。 */
        public final int chars;
        /** 其中落在 U+4E00–U+9FFF 的字形数；0 表示没有中文字形。 */
        public final int cjk;

        Entry(String path, String absolute, String name, String source,
              int lineHeight, int chars, int cjk) {
            this.path = path;
            this.absolute = absolute;
            this.name = name;
            this.source = source;
            this.lineHeight = lineHeight;
            this.chars = chars;
            this.cjk = cjk;
        }

        /** 是否含中文字形（面板正文多为中文，缺字形会显示成方框）。 */
        public boolean hasCjk() {
            return cjk > 0;
        }
    }

    /** 目录列举返回结果里的文件名后缀。 */
    private static final String SUFFIX = ".fnt";

    /** 注册路径的前缀：游戏与 mod 的资源根都是各自目录，故相对名统一是这个。 */
    private static final String PREFIX = "graphics/fonts/";

    /** 字形总数低于此值必定是纯拉丁字体，不必再逐行数中文。 */
    private static final int CJK_SCAN_THRESHOLD = 1000;

    /** 未选择字体时字体选择器上显示的文案（即游戏自带那套）。 */
    public static final String FALLBACK_LABEL = "游戏自带";

    private static List<Entry> cache;

    private FontCatalog() {
    }

    /** 已探测到的字体（首次调用扫描一次，之后走缓存）。 */
    public static synchronized List<Entry> all() {
        if (cache != null) {
            return cache;
        }
        Map<String, Entry> byFileName = new LinkedHashMap<String, Entry>();

        // 游戏本体：相对名（游戏进程 CWD 就是 starsector-core）+ user.dir 兜底，
        // 两者通常指向同一目录，按文件名去重。
        scan(byFileName, "graphics/fonts", "游戏本体");
        try {
            String cwd = System.getProperty("user.dir");
            if (cwd != null && !cwd.isEmpty()) {
                scan(byFileName, cwd + "/graphics/fonts", "游戏本体");
            }
        } catch (Throwable ignored) {
        }

        // 各 enabled mod 自带字体。getPath() 给的是 mod 根目录绝对路径。
        try {
            for (ModSpecAPI spec : Global.getSettings().getModManager().getEnabledModsCopy()) {
                if (spec == null) {
                    continue;
                }
                String root = spec.getPath();
                if (root == null || root.isEmpty()) {
                    continue;
                }
                String label = spec.getName();
                if (label == null || label.isEmpty()) {
                    label = spec.getId();
                }
                scan(byFileName, root + "/graphics/fonts", label);
            }
        } catch (Throwable ignored) {
        }

        List<Entry> list = new ArrayList<Entry>(byFileName.values());
        // 含中文的排前面（面板正文是中文），同组按名称排序，保证列表稳定可预期。
        Collections.sort(list, new Comparator<Entry>() {
            @Override
            public int compare(Entry a, Entry b) {
                if (a.hasCjk() != b.hasCjk()) {
                    return a.hasCjk() ? -1 : 1;
                }
                return a.name.compareToIgnoreCase(b.name);
            }
        });
        cache = list;
        return cache;
    }

    /** 清空缓存，下次访问重新扫描（「刷新目录」按钮会调）。 */
    public static synchronized void invalidate() {
        cache = null;
    }

    /** 按注册路径查表，找不到返回 null。 */
    public static Entry find(String path) {
        if (path == null || path.isEmpty()) {
            return null;
        }
        for (Entry e : all()) {
            if (e.path.equals(path)) {
                return e;
            }
        }
        return null;
    }

    private static void scan(Map<String, Entry> out, String dir, String source) {
        for (String absolute : list(dir)) {
            String fileName = fileNameOf(absolute);
            if (fileName == null || !fileName.toLowerCase().endsWith(SUFFIX)) {
                continue;
            }
            if (out.containsKey(fileName)) {
                continue;
            }
            int[] header = header(absolute);
            if (header == null || header[0] <= 0) {
                // 表头读不出或没有 lineHeight 的（例如残缺文件）直接跳过。
                continue;
            }
            String name = fileName.substring(0, fileName.length() - SUFFIX.length());
            out.put(fileName, new Entry(PREFIX + fileName, absolute, name, source,
                    header[0], header[1], header[2]));
        }
    }

    /** 目录列举（com.fs.util.C.o00000(dir, suffix, true)），返回绝对路径；失败返回空表。 */
    private static List<String> list(String dir) {
        List<String> out = new ArrayList<String>();
        try {
            com.fs.util.C c = com.fs.util.C.Ó00000();
            Object raw = c.o00000(dir, SUFFIX, true);
            if (raw instanceof Iterable) {
                for (Object o : (Iterable<?>) raw) {
                    if (o != null) {
                        out.add(String.valueOf(o));
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    /**
     * 读 .fnt 表头，返回 {@code {lineHeight, chars, cjk}}；失败返回 null。
     *
     * <p>用 {@code com.fs.util.C.Ó00000(path, true)} 开流（注意是两参重载），
     * 再交给 {@code BufferedReader(new InputStreamReader(in, "UTF-8"))} ——
     * 这几个类都在脚本沙箱白名单里。
     */
    private static int[] header(String absolute) {
        InputStream in = null;
        try {
            com.fs.util.C c = com.fs.util.C.Ó00000();
            in = c.Ó00000(absolute, true);
            BufferedReader reader = new BufferedReader(new InputStreamReader(in, "UTF-8"));
            int lineHeight = -1;
            int chars = -1;
            int cjk = 0;
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("common")) {
                    lineHeight = intAfter(line, "lineHeight=");
                } else if (line.startsWith("chars count")) {
                    chars = intAfter(line, "count=");
                    if (chars >= 0 && chars < CJK_SCAN_THRESHOLD) {
                        break;
                    }
                } else if (line.startsWith("char id=")) {
                    int id = intAfter(line, "char id=");
                    if (id >= 0x4E00 && id <= 0x9FFF) {
                        cjk++;
                    }
                }
            }
            reader.close();
            return new int[]{lineHeight, chars, cjk};
        } catch (Throwable t) {
            return null;
        } finally {
            try {
                if (in != null) {
                    in.close();
                }
            } catch (Throwable ignored) {
            }
        }
    }

    /** 取 {@code key} 之后的连续数字（可带负号），没找到返回 -1。 */
    private static int intAfter(String line, String key) {
        int i = line.indexOf(key);
        if (i < 0) {
            return -1;
        }
        i += key.length();
        int j = i;
        while (j < line.length()) {
            char ch = line.charAt(j);
            if ((ch >= '0' && ch <= '9') || ch == '-') {
                j++;
            } else {
                break;
            }
        }
        try {
            return Integer.parseInt(line.substring(i, j).trim());
        } catch (Throwable t) {
            return -1;
        }
    }

    /** 从绝对路径里取文件名（不用 java.io.File，它被脚本沙箱拦）。 */
    private static String fileNameOf(String path) {
        if (path == null) {
            return null;
        }
        int cut = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        return cut >= 0 ? path.substring(cut + 1) : path;
    }
}
