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
