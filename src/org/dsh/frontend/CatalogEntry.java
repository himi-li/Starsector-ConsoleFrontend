package org.dsh.frontend;

import java.util.ArrayList;
import java.util.List;

/** 命令目录中的一个条目：既可能是精选的中文按钮，也可能是自动枚举出来的命令。 */
public class CatalogEntry {

    public String command = "";
    public String displayName = "";
    public String label = "";
    public String description = "";
    public String syntax = "";
    public String help = "";
    public String source = "";
    public List<String> tags = new ArrayList<String>();
    public String category = "all";
    public boolean applicable = true;
    public boolean curated = false;
    public boolean confirm = false;
    public List<ParamSpec> params = new ArrayList<ParamSpec>();
    public List<String> run = new ArrayList<String>();

    public boolean hasParams() {
        return params != null && !params.isEmpty();
    }

    /** 是否有可编辑的参数槽（决定按钮旁是否出现齿轮）。 */
    public boolean appliedParams() {
        return hasParams();
    }

    /**
     * 按钮显示文本。
     *
     * <p>当设置项 {@code cf_useEnglishLabels} 打开时（未安装中文字体、中文显示为方块），
     * 一律回退为命令名本身，避免出现乱码按钮。
     */
    public String labelOrName() {
        if (FrontendSettings.useEnglishLabels) {
            return displayName == null || displayName.isEmpty() ? command : displayName;
        }
        return label == null || label.isEmpty() ? displayName : label;
    }

    /**
     * 文本里是否含中日韩字符（含 CJK 标点与全角字符）。
     *
     * <p>两处用到：按钮字体选择（含中文必须用 Victor14，纯英文可以用真正有
     * 小写字形的默认按钮字体）、以及自动枚举命令的按钮文字来源。
     */
    public static boolean hasCjk(String s) {
        if (s == null) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= 0x3400 && c <= 0x4DBF) {
                return true;
            }
            if (c >= 0x4E00 && c <= 0x9FFF) {
                return true;
            }
            if (c >= 0xF900 && c <= 0xFAFF) {
                return true;
            }
            if (c >= 0x3000 && c <= 0x303F) {
                return true;
            }
            if (c >= 0xFF00 && c <= 0xFFEF) {
                return true;
            }
        }
        return false;
    }

    /**
     * 省略号字符。
     *
     * <p>不用三个半角点：一个字库里 U+2026 只占 1 个字形宽度（等于 2 个半角单位），
     * 而且 victor14 / victor16 / orbitron12condensed 三套 .fnt 里都有它的位图
     * （逐 id 核对过），不会渲染成方块。
     */
    public static final String ELLIPSIS = "\u2026";

    /** 半角单位宽度：东亚宽字符（含 CJK 与全角符号）算 2，其余算 1。 */
    public static boolean isWide(int cp) {
        return (cp >= 0x1100 && cp <= 0x115F)
                || (cp >= 0x2E80 && cp <= 0x303E)
                || (cp >= 0x3041 && cp <= 0x33FF)
                || (cp >= 0x3400 && cp <= 0x4DBF)
                || (cp >= 0x4E00 && cp <= 0x9FFF)
                || (cp >= 0xA000 && cp <= 0xA4CF)
                || (cp >= 0xAC00 && cp <= 0xD7A3)
                || (cp >= 0xF900 && cp <= 0xFAFF)
                || (cp >= 0xFE30 && cp <= 0xFE6F)
                || (cp >= 0xFF00 && cp <= 0xFF60)
                || (cp >= 0xFFE0 && cp <= 0xFFE6);
    }

    /** 文本占多少个「半角单位」（供按钮宽度换算，见 {@link #ellipsize}）。 */
    public static int displayUnits(String s) {
        if (s == null) {
            return 0;
        }
        int n = 0;
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            n += isWide(cp) ? 2 : 1;
            i += Character.charCount(cp);
        }
        return n;
    }

    /**
     * 按「半角单位」预算截断，超出部分用省略号，且省略号本身也算在预算内
     * （结果一定不超过 {@code maxUnits} 个单位）。
     *
     * <p>用单位而不是字符数：按钮宽度是像素，「加金币」与「addcredits」占的宽度
     * 差一倍，只数字符会让中文按钮溢出、英文按钮留下大片空白。也不按码元切，
     * 避免把代理对（emoji 之类）劈成半个字符。
     */
    public static String ellipsize(String s, int maxUnits) {
        if (s == null) {
            return "";
        }
        String t = s.trim();
        // 至少留 1 个字符 + 省略号，否则按钮上空无一物看不出是个按钮。
        int budgetTotal = Math.max(4, maxUnits);
        if (displayUnits(t) <= budgetTotal) {
            return t;
        }
        int budget = budgetTotal - 2; // 省略号占 2 个单位
        StringBuilder sb = new StringBuilder();
        int used = 0;
        for (int i = 0; i < t.length(); ) {
            int cp = t.codePointAt(i);
            int w = isWide(cp) ? 2 : 1;
            if (used + w > budget) {
                break;
            }
            sb.appendCodePoint(cp);
            used += w;
            i += Character.charCount(cp);
        }
        return sb.append(ELLIPSIS).toString();
    }

    /** 按钮悬浮提示：中文说明 + 语法 + 来源 Mod。 */
    public String describe() {
        StringBuilder sb = new StringBuilder();
        if (description != null && !description.isEmpty()) {
            sb.append(description);
        }
        if (syntax != null && !syntax.isEmpty()) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append("语法: ").append(syntax);
        }
        if (source != null && !source.isEmpty()) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append("来源: ").append(source);
        }
        if (sb.length() == 0) {
            sb.append("执行命令 ").append(command);
        }
        return sb.toString();
    }

    /** 供搜索框使用的匹配文本（中英文与 ID 都能命中）。 */
    public String searchBlob() {
        StringBuilder sb = new StringBuilder();
        sb.append(command == null ? "" : command.toLowerCase());
        if (label != null) {
            sb.append(' ').append(label.toLowerCase());
        }
        if (displayName != null) {
            sb.append(' ').append(displayName.toLowerCase());
        }
        if (description != null) {
            sb.append(' ').append(description.toLowerCase());
        }
        if (tags != null) {
            for (String t : tags) {
                sb.append(' ').append(t.toLowerCase());
            }
        }
        return sb.toString();
    }
}
