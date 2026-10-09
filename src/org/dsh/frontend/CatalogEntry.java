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

    public String labelOrName() {
        return label == null || label.isEmpty() ? displayName : label;
    }

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
        return sb.toString();
    }

    public String searchBlob() {
        StringBuilder sb = new StringBuilder();
        sb.append(command == null ? "" : command.toLowerCase());
        sb.append(' ').append(labelOrName().toLowerCase());
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