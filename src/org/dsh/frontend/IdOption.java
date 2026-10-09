package org.dsh.frontend;

/** ID 选择器中的一个候选项：游戏内显示名 + 真实 ID（作为注释保留）。 */
public class IdOption {

    public final String id;
    public final String name;
    public final String note;
    public final String source;

    public IdOption(String id, String name, String note, String source) {
        this.id = id == null ? "" : id;
        this.name = name == null || name.trim().isEmpty() ? this.id : name;
        this.note = note == null ? "" : note;
        this.source = source == null ? "" : source;
    }

    /** 主显示文本：游戏内名称（用户要求列表以名称为主）。 */
    public String primary() {
        return name;
    }

    /** 副显示文本：ID 注释（+ 可选来源 Mod）。 */
    public String secondary() {
        StringBuilder sb = new StringBuilder();
        sb.append('(').append(id).append(')');
        if (note != null && !note.isEmpty()) {
            sb.append(' ').append(note);
        }
        return sb.toString();
    }

    public String searchBlob() {
        return (name + " " + id + " " + note).toLowerCase();
    }

    @Override
    public String toString() {
        return primary() + " " + secondary();
    }
}
