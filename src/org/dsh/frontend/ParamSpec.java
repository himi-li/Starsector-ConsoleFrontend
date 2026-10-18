package org.dsh.frontend;

import java.util.ArrayList;
import java.util.List;

/** 一个命令参数槽的定义（来自 frontend_labels.json 的 params 数组）。 */
public class ParamSpec {

    public static final String TYPE_INT = "int";
    public static final String TYPE_FLOAT = "float";
    public static final String TYPE_TEXT = "text";
    public static final String TYPE_ID = "id";
    public static final String TYPE_ENUM = "enum";
    public static final String TYPE_BOOL = "bool";

    public String key = "";
    public String label = "";
    public String type = TYPE_TEXT;
    public String source = null;
    public String defaultValue = "";
    public Double min = null;
    public Double max = null;
    public List<String> options = new ArrayList<String>();
    /** 与 {@link #options} 一一对应的中文显示名（JSON 里写成 {label,value} 时才有）。 */
    public List<String> optionLabels = new ArrayList<String>();
    public String primary = "name";
    public String secondary = "id";
    public boolean searchable = true;
    public String hint = "";
    /** 必填：值为空时点击主按钮不会执行，而是展开参数区提示填写。 */
    public boolean required = false;

    public boolean isNumeric() {
        return TYPE_INT.equals(type) || TYPE_FLOAT.equals(type);
    }

    public boolean isPicker() {
        return TYPE_ID.equals(type) || TYPE_ENUM.equals(type);
    }

    public String labelOrKey() {
        return label == null || label.isEmpty() ? key : label;
    }

    /** 枚举值的显示名：有中文名用中文名，否则原样返回（空串显示成「（留空）」）。 */
    public String optionLabel(int index, String value) {
        if (optionLabels != null && index >= 0 && index < optionLabels.size()) {
            String s = optionLabels.get(index);
            if (s != null && !s.isEmpty()) {
                return s;
            }
        }
        return value == null || value.isEmpty() ? "（留空）" : value;
    }

    public double clamp(double v) {
        double r = v;
        if (min != null && r < min.doubleValue()) {
            r = min.doubleValue();
        }
        if (max != null && r > max.doubleValue()) {
            r = max.doubleValue();
        }
        return r;
    }

    public String format(String raw) {
        if (raw == null) {
            return "";
        }
        if (TYPE_INT.equals(type)) {
            try {
                return String.valueOf((long) clamp(Double.parseDouble(raw.trim())));
            } catch (Throwable t) {
                return raw;
            }
        }
        if (TYPE_FLOAT.equals(type)) {
            try {
                return trim(Double.parseDouble(raw.trim()));
            } catch (Throwable t) {
                return raw;
            }
        }
        return raw;
    }

    public static String trim(double v) {
        if (v == Math.floor(v) && !Double.isInfinite(v)) {
            return String.valueOf((long) v);
        }
        String s = String.valueOf(v);
        if (s.contains(".")) {
            s = s.replaceAll("0+$", "");
            if (s.endsWith(".")) {
                s = s.substring(0, s.length() - 1);
            }
        }
        return s;
    }
}