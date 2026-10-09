package org.dsh.frontend;

import com.fs.starfarer.api.GameState;
import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.BaseCustomUIPanelPlugin;
import com.fs.starfarer.api.campaign.CampaignUIAPI;
import com.fs.starfarer.api.campaign.InteractionDialogAPI;
import com.fs.starfarer.api.campaign.SectorEntityToken;
import com.fs.starfarer.api.input.InputEventAPI;
import com.fs.starfarer.api.ui.Alignment;
import com.fs.starfarer.api.ui.ButtonAPI;
import com.fs.starfarer.api.ui.CustomPanelAPI;
import com.fs.starfarer.api.ui.CutStyle;
import com.fs.starfarer.api.ui.LabelAPI;
import com.fs.starfarer.api.ui.PositionAPI;
import com.fs.starfarer.api.ui.ScrollPanelAPI;
import com.fs.starfarer.api.ui.TextFieldAPI;
import com.fs.starfarer.api.ui.TooltipMakerAPI;
import com.fs.starfarer.api.ui.UIComponentAPI;
import com.fs.starfarer.api.ui.UIPanelAPI;
import com.fs.starfarer.api.util.Misc;
import com.fs.state.AppDriver;
import org.lazywizard.console.BaseCommand.CommandContext;
import org.lazywizard.console.Console;
import org.lazywizard.console.overlay.v2.panels.ConsoleOverlayPanel;
import org.lwjgl.input.Keyboard;
import org.lwjgl.opengl.GL11;

import java.awt.Color;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 控制台前端面板：热键呼出的覆盖层，点击按钮即自动执行对应的 Console Commands 命令。
 *
 * 带参数的命令支持自定义参数并记住（ParamStore）；ID 类参数提供
 * 「游戏内名称优先、ID 作为注释」的可搜索下拉选择器，同时允许直接输入。
 */
public class FrontendPanel extends BaseCustomUIPanelPlugin {

    private static FrontendPanel instance;

    public static FrontendPanel getInstance() {
        return instance;
    }

    public static boolean isOpen() {
        return instance != null;
    }

    // ---------- 状态 ----------
    private final CommandContext context;
    private boolean wasPaused;
    private Object placeHolderDialog;
    private CustomPanelAPI parent;
    private CustomPanelAPI bgPanel;

    private final CommandCatalog catalog = new CommandCatalog();
    private String category = "all";
    private String query = "";
    private int page = 0;
    private int pageSize = 40;

    private String editing;
    private final Map<String, String> draft = new LinkedHashMap<String, String>();

    private boolean pickerOpen;
    private String pickerCommand;
    private String pickerParamKey;
    private String pickerSource;
    private String pickerQuery = "";
    private int pickerPage = 0;

    private final Map<String, List<IdOption>> idCache = new HashMap<String, List<IdOption>>();

    private final Map<String, TextFieldAPI> fields = new LinkedHashMap<String, TextFieldAPI>();
    private final Map<String, String> lastFieldText = new LinkedHashMap<String, String>();
    private LabelAPI previewLabel;
    private boolean needsRebuild;
    private String statusLine = "";

    private FrontendPanel(CommandContext ctx) {
        this.context = ctx;
        instance = this;
        mount();
    }

    public static void open(CommandContext ctx) {
        if (instance != null) {
            return;
        }
        try {
            new FrontendPanel(ctx);
        } catch (Throwable t) {
            instance = null;
            warn("打开前端面板失败: " + t);
        }
    }

    public static void closeIfOpen() {
        FrontendPanel p = instance;
        if (p != null) {
            p.close();
        }
    }

    // ================= 挂载 / 卸载 =================

    private void mount() {
        if (Global.getCurrentState() == GameState.COMBAT) {
            try {
                wasPaused = Global.getCombatEngine().isPaused();
                Global.getCombatEngine().setPaused(true);
            } catch (Throwable ignored) {
            }
        } else if (Global.getCurrentState() == GameState.CAMPAIGN) {
            try {
                wasPaused = Global.getSector().isPaused();
                Global.getSector().setPaused(true);
            } catch (Throwable ignored) {
            }
        }

        // 战役中用一个透明的提示对话框占位：既阻止其它 mod 抢输入，又不隐藏战役 UI
        if (context != null && context.isInCampaign()) {
            try {
                CampaignUIAPI ui = Global.getSector().getCampaignUI();
                if (ui != null && !ui.isShowingDialog()) {
                    ui.showMessageDialog("");
                    Object screenPanel = Reflect.get(ui, "screenPanel");
                    Object dialog = Reflect.findChildWithMethod(screenPanel, "getOptionMap");
                    if (dialog != null) {
                        Reflect.invoke(dialog, "setOpacity", Float.valueOf(0f));
                        Reflect.invoke(dialog, "setBackgroundDimAmount", Float.valueOf(0f));
                        Reflect.invoke(dialog, "setAbsorbOutsideEvents", Boolean.FALSE);
                        Reflect.invoke(dialog, "makeOptionInstant", Integer.valueOf(0));
                        placeHolderDialog = dialog;
                    }
                }
            } catch (Throwable ignored) {
            }
        }

        Object state = null;
        try {
            state = AppDriver.getInstance().getCurrentState();
        } catch (Throwable ignored) {
        }
        Object screenPanel = Reflect.invoke(state, "getScreenPanel");
        if (!(screenPanel instanceof UIPanelAPI)) {
            throw new IllegalStateException("无法取得 screenPanel（反射失败）");
        }
        UIPanelAPI sp = (UIPanelAPI) screenPanel;
        float w = sp.getPosition() == null ? Global.getSettings().getScreenWidth() : sp.getPosition().getWidth();
        float h = sp.getPosition() == null ? Global.getSettings().getScreenHeight() : sp.getPosition().getHeight();

        parent = Global.getSettings().createCustom(w, h, null);
        sp.addComponent(parent);
        parent.getPosition().inTL(0f, 0f);

        try {
            catalog.build(context);
        } catch (Throwable t) {
            warn("构建命令目录失败: " + t);
        }
        rebuild();
    }

    public void close() {
        try {
            ParamStore.save();
        } catch (Throwable ignored) {
        }
        try {
            if (placeHolderDialog != null) {
                Reflect.invoke(placeHolderDialog, "dismiss", Integer.valueOf(0));
                placeHolderDialog = null;
            }
        } catch (Throwable ignored) {
        }
        try {
            if (Global.getCurrentState() == GameState.COMBAT) {
                if (!wasPaused) {
                    Global.getCombatEngine().setPaused(false);
                }
            } else if (Global.getCurrentState() == GameState.CAMPAIGN) {
                Global.getSector().setPaused(wasPaused);
            }
        } catch (Throwable ignored) {
        }
        try {
            Object p = Reflect.invoke(parent, "getParent");
            if (p != null) {
                Reflect.invoke(p, "removeComponent", parent);
            }
        } catch (Throwable ignored) {
        }
        parent = null;
        bgPanel = null;
        instance = null;
    }

    // ================= 重建 =================

    private void rebuild() {
        if (parent == null) {
            return;
        }
        if (bgPanel == null) {
            bgPanel = parent.createCustomPanel(parent.getPosition().getWidth(), parent.getPosition().getHeight(), this);
            parent.addComponent(bgPanel);
            bgPanel.getPosition().inTL(0f, 0f);
        }
        clearPanel(bgPanel);
        fields.clear();
        lastFieldText.clear();
        previewLabel = null;

        float w = bgPanel.getPosition().getWidth();
        float h = bgPanel.getPosition().getHeight();
        float margin = 40f;

        try {
            buildHeader(w, h, margin);
            buildSearchRow(w, h, margin);
            buildCategoryRow(w, h, margin);
            float paramsH = buildParamsArea(w, h, margin);
            buildListArea(w, h, margin, paramsH);
            buildLogArea(w, h, margin, paramsH);
            if (pickerOpen) {
                buildPicker(w, h, margin);
            }
        } catch (Throwable t) {
            warn("重建面板失败: " + t);
        }
    }

    private void clearPanel(UIPanelAPI panel) {
        try {
            Object children = Reflect.invoke(panel, "getChildrenCopy");
            if (children instanceof Iterable) {
                List<Object> copy = new ArrayList<Object>();
                for (Object c : (Iterable<?>) children) {
                    copy.add(c);
                }
                for (Object c : copy) {
                    if (c instanceof UIComponentAPI) {
                        panel.removeComponent((UIComponentAPI) c);
                    }
                }
            }
        } catch (Throwable ignored) {
        }
    }

    // ================= 各区域 =================

    private void buildHeader(float w, float h, float margin) {
        float rowW = w - margin * 2f;
        CustomPanelAPI row = newPanel(rowW, 30f);

        TooltipMakerAPI tm = row.createUIElement(rowW - 140f, 30f, false);
        tm.setParaFontVictor14();
        tm.addPara("控制台前端 · 命令按钮", 6f, Misc.getBrightPlayerColor());
        row.addUIElement(tm).inTL(0f, 0f);

        button(row, rowW - 130f, 2f, 124f, 24f, "关闭 (ESC)", "close", "关闭面板并恢复游戏状态");
        place(row, margin, 12f);
    }

    private void buildSearchRow(float w, float h, float margin) {
        float rowW = w - margin * 2f;
        CustomPanelAPI row = newPanel(rowW, 30f);

        TooltipMakerAPI tm = row.createUIElement(44f, 30f, false);
        tm.addPara("搜索", 6f, Misc.getGrayColor());
        row.addUIElement(tm).inTL(0f, 8f);

        float fieldW = rowW - 250f;
        TextFieldAPI f = textField(row, 44f, 2f, fieldW, 24f, query, "search");
        f.setMaxChars(120);

        button(row, rowW - 240f, 2f, 112f, 24f, "刷新目录", "refresh", "重新读取全部命令与 ID 列表");
        button(row, rowW - 122f, 2f, 112f, 24f, "清空搜索", "clearsearch", null);
        place(row, margin, 52f);
    }

    private void buildCategoryRow(float w, float h, float margin) {
        float rowW = w - margin * 2f;
        List<String> cats = new ArrayList<String>();
        cats.add("all");
        for (FrontendLabels.Category c : FrontendLabels.sortedCategories()) {
            if (!"all".equals(c.id) && catalog.categoriesUsed().contains(c.id)) {
                cats.add(c.id);
            }
        }
        for (String used : catalog.categoriesUsed()) {
            if (!cats.contains(used)) {
                cats.add(used);
            }
        }

        float gap = 4f;
        float bw = Math.min(150f, (rowW - gap * (cats.size() - 1)) / Math.max(1, cats.size()));
        float rowH = 26f;
        CustomPanelAPI row = newPanel(rowW, rowH);
        float x = 0f;
        for (String id : cats) {
            String name = "all".equals(id) ? "全部" : categoryName(id);
            ButtonAPI b = button(row, x, 0f, bw, rowH - 2f, name, "tab|" + id, null);
            if (id.equals(category)) {
                b.setEnabled(false);
            }
            x += bw + gap;
        }
        place(row, margin, 88f);
    }

    private String categoryName(String id) {
        FrontendLabels.Category c = FrontendLabels.categories().get(id);
        return c == null ? id : c.name;
    }

    /** 参数编辑区；返回其占用高度（0 表示未展开）。 */
    private float buildParamsArea(float w, float h, float margin) {
        if (editing == null) {
            return 0f;
        }
        final CatalogEntry e = catalog.byCommand(editing);
        if (e == null) {
            editing = null;
            return 0f;
        }
        float rowW = w - margin * 2f;
        float y = 122f;
        float lineH = 30f;
        int lines = 1 + Math.max(1, e.params.size()) + 1;
        float areaH = lines * lineH + 16f;

        CustomPanelAPI box = newPanel(rowW, areaH);
        TooltipMakerAPI tm = box.createUIElement(rowW, areaH, false);
        tm.setParaFontVictor14();
        tm.addPara("参数设置 · " + e.labelOrName(), 6f, Misc.getBrightPlayerColor());
        box.addUIElement(tm).inTL(8f, 4f);

        float fy = 26f;
        for (ParamSpec p : e.params) {
            float labelW = 120f;
            TooltipMakerAPI lt = box.createUIElement(labelW, lineH, false);
            lt.addPara(p.labelOrKey(), 6f, Misc.getGrayColor());
            box.addUIElement(lt).inTL(8f, fy + 6f);

            String key = fieldKey(e.command, p.key);
            float ctlX = 8f + labelW;
            float ctlW = Math.max(120f, rowW - ctlX - 210f);
            String val = valueOf(e, p);

            if (p.isPicker()) {
                float pickW = ctlW - 34f;
                textField(box, ctlX, fy + 2f, pickW, 24f, val, key);
                button(box, ctlX + pickW + 4f, fy + 2f, 28f, 24f, "▼",
                        "pickopen|" + e.command + "|" + p.key, "打开" + IdSource.displayNameOf(p.source) + "选择器");
            } else if (p.isNumeric()) {
                float stepW = 28f;
                float fw = Math.max(80f, ctlW - (stepW + 4f) * 2f - 4f);
                textField(box, ctlX, fy + 2f, fw, 24f, val, key);
                button(box, ctlX + fw + 4f, fy + 2f, stepW, 24f, "-", "pstep|" + e.command + "|" + p.key + "|dec", "减少");
                button(box, ctlX + fw + stepW + 8f, fy + 2f, stepW, 24f, "+", "pstep|" + e.command + "|" + p.key + "|inc", "增加");
            } else if (ParamSpec.TYPE_BOOL.equals(p.type)) {
                boolean on = isOn(val);
                button(box, ctlX, fy + 2f, 120f, 24f, on ? "开 (ON)" : "关 (OFF)",
                        "pbool|" + e.command + "|" + p.key, "点击切换");
            } else {
                textField(box, ctlX, fy + 2f, ctlW, 24f, val, key);
            }

            if (p.hint != null && !p.hint.isEmpty()) {
                TooltipMakerAPI ht = box.createUIElement(200f, lineH, false);
                ht.addPara(p.hint, 6f, Misc.getGrayColor());
                box.addUIElement(ht).inTL(rowW - 208f, fy + 6f);
            }
            fy += lineH;
        }
        if (e.params.isEmpty()) {
            TooltipMakerAPI nt = box.createUIElement(rowW - 20f, lineH, false);
            nt.addPara("该命令没有参数，点击主按钮直接执行。", 6f, Misc.getGrayColor());
            box.addUIElement(nt).inTL(8f, fy + 6f);
            fy += lineH;
        }

        TooltipMakerAPI pv = box.createUIElement(Math.max(120f, rowW - 400f), lineH, false);
        previewLabel = pv.addPara(previewText(e), 6f, Misc.getHighlightColor());
        box.addUIElement(pv).inTL(8f, fy + 6f);

        button(box, rowW - 300f, fy + 2f, 90f, 24f, "执行", "run|" + e.command, "用当前参数执行一次");
        button(box, rowW - 204f, fy + 2f, 100f, 24f, "恢复默认", "reset|" + e.command, "清除该命令已记住的参数");
        button(box, rowW - 98f, fy + 2f, 90f, 24f, "收起", "editclose", null);

        place(box, margin, y);
        return areaH + 8f;
    }

    private void buildListArea(float w, float h, float margin, float paramsH) {
        float rowW = w - margin * 2f;
        float y = 122f + paramsH;
        float bottom = FrontendSettings.showOutputLog ? 200f : 40f;
        float areaH = Math.max(140f, h - y - bottom);

        List<CatalogEntry> list = catalog.filter(category, query, FrontendSettings.showUnavailable);
        int cols = Math.max(1, FrontendSettings.buttonColumns);
        float gap = 6f;
        float rowH = FrontendSettings.buttonHeight + 6f;
        int rows = Math.max(1, (int) ((areaH - 50f) / rowH));
        pageSize = Math.max(1, rows * cols);
        int pages = Math.max(1, (int) Math.ceil(list.size() / (double) pageSize));
        if (page >= pages) {
            page = pages - 1;
        }
        if (page < 0) {
            page = 0;
        }
        int from = page * pageSize;
        int to = Math.min(list.size(), from + pageSize);

        CustomPanelAPI area = newPanel(rowW, areaH);
        TooltipMakerAPI content = area.createUIElement(rowW, areaH, true);
        area.addUIElement(content).inTL(0f, 0f);

        float cellW = (rowW - gap * (cols - 1) - 14f) / cols;
        for (int i = from; i < to; i++) {
            final CatalogEntry e = list.get(i);
            int idx = i - from;
            float cx = (idx % cols) * (cellW + gap);
            float cy = (idx / cols) * rowH;

            boolean hasEdit = e.hasParams();
            float editW = hasEdit ? 24f : 0f;
            float mainW = cellW - editW - (hasEdit ? 3f : 0f);

            CustomPanelAPI cell = newPanel(cellW, FrontendSettings.buttonHeight);
            ButtonAPI main = button(cell, 0f, 0f, mainW, FrontendSettings.buttonHeight,
                    e.labelOrName(), "cmd|" + e.command, e.describe());
            if (!e.applicable) {
                main.setEnabled(false);
            }
            if (hasEdit) {
                button(cell, mainW + 3f, 0f, editW, FrontendSettings.buttonHeight, "⚙",
                        "edit|" + e.command, "调整「" + e.labelOrName() + "」的参数");
            }
            UIComponentAPI added = content.addCustom(cell, 0f);
            if (added != null) {
                added.getPosition().inTL(cx, cy);
            } else {
                cell.getPosition().inTL(cx, cy);
            }
        }

        TooltipMakerAPI footer = area.createUIElement(Math.max(120f, rowW - 280f), 26f, false);
        String info = "共 " + list.size() + " 条 · 第 " + (page + 1) + "/" + pages + " 页";
        if (statusLine != null && !statusLine.isEmpty()) {
            info = statusLine + "    " + info;
        }
        footer.addPara(info, 6f, Misc.getGrayColor());
        area.addUIElement(footer).inTL(6f, areaH - 24f);

        if (pages > 1) {
            button(area, rowW - 260f, areaH - 26f, 80f, 22f, "上一页", "page|prev", null);
            button(area, rowW - 176f, areaH - 26f, 80f, 22f, "下一页", "page|next", null);
        }

        place(area, margin, y);
    }

    private void buildLogArea(float w, float h, float margin, float paramsH) {
        if (!FrontendSettings.showOutputLog) {
            return;
        }
        float rowW = w - margin * 2f;
        float areaH = 150f;
        float y = h - areaH - 30f;

        CustomPanelAPI area = newPanel(rowW, areaH);
        TooltipMakerAPI tm = area.createUIElement(rowW - 110f, 26f, false);
        tm.setParaFontVictor14();
        tm.addPara("命令输出", 6f, Misc.getBrightPlayerColor());
        area.addUIElement(tm).inTL(8f, 4f);

        button(area, rowW - 100f, 2f, 92f, 22f, "清空日志", "clearlog", "清空控制台输出缓冲");

        String out = "";
        try {
            out = ConsoleOverlayPanel.getOutput();
        } catch (Throwable ignored) {
        }
        String tail = tailLines(out, FrontendSettings.logLines);
        TooltipMakerAPI log = area.createUIElement(rowW - 16f, areaH - 36f, true);
        log.addPara(tail.isEmpty() ? "（暂无输出）" : tail, 4f, Misc.getTextColor());
        area.addUIElement(log).inTL(8f, 32f);
        try {
            ScrollPanelAPI sc = log.getExternalScroller();
            if (sc != null) {
                sc.setYOffset(Float.MAX_VALUE);
            }
        } catch (Throwable ignored) {
        }

        place(area, margin, y);
    }

    private void buildPicker(float w, float h, float margin) {
        float rowW = Math.min(780f, w - margin * 2f);
        float areaH = Math.min(520f, h - 200f);
        float x = (w - rowW) / 2f;
        float y = 140f;

        CustomPanelAPI box = newPanel(rowW, areaH);
        TooltipMakerAPI tm = box.createUIElement(rowW - 20f, 26f, false);
        tm.setParaFontVictor14();
        tm.addPara("选择" + IdSource.displayNameOf(pickerSource) + "（名称优先，括号内为 ID）", 6f, Misc.getBrightPlayerColor());
        box.addUIElement(tm).inTL(8f, 4f);

        textField(box, 8f, 28f, rowW - 300f, 24f, pickerQuery, "picker");

        List<String> tabs = IdSource.tabsFor(pickerSource);
        float tx = rowW - 288f;
        for (String t : tabs) {
            ButtonAPI b = button(box, tx, 28f, 84f, 24f, IdSource.displayNameOf(t), "picksrc|" + t, null);
            if (t.equals(pickerSource)) {
                b.setEnabled(false);
            }
            tx += 88f;
        }
        button(box, rowW - 80f, 28f, 72f, 24f, "取消", "pickcancel", null);

        List<IdOption> all = idOptions(pickerSource);
        List<IdOption> filtered = IdSource.filter(all, pickerQuery);
        int ps = Math.max(5, FrontendSettings.pickerPageSize);
        int pages = Math.max(1, (int) Math.ceil(filtered.size() / (double) ps));
        if (pickerPage >= pages) {
            pickerPage = pages - 1;
        }
        if (pickerPage < 0) {
            pickerPage = 0;
        }
        int from = pickerPage * ps;
        int to = Math.min(filtered.size(), from + ps);

        float listY = 58f;
        float listH = areaH - listY - 34f;
        CustomPanelAPI listPanel = newPanel(rowW - 16f, listH);
        TooltipMakerAPI list = listPanel.createUIElement(rowW - 16f, listH, true);
        listPanel.addUIElement(list).inTL(0f, 0f);

        float lineH = 24f;
        float innerW = rowW - 40f;
        for (int i = from; i < to; i++) {
            final IdOption o = filtered.get(i);
            CustomPanelAPI line = newPanel(innerW, lineH);
            button(line, 0f, 0f, innerW * 0.60f, lineH - 2f, shorten(o.primary(), 44),
                    "pick|" + pickerSource + "|" + o.id, o.primary() + "\n" + o.secondary());

            TooltipMakerAPI idt = line.createUIElement(innerW * 0.38f, lineH, false);
            idt.addPara(o.secondary(), 6f, Misc.getGrayColor());
            line.addUIElement(idt).inTL(innerW * 0.62f, 4f);

            UIComponentAPI added = list.addCustom(line, 0f);
            if (added != null) {
                added.getPosition().inTL(0f, (i - from) * lineH);
            } else {
                line.getPosition().inTL(0f, (i - from) * lineH);
            }
        }
        box.addComponent(listPanel);
        listPanel.getPosition().inTL(8f, listY);

        TooltipMakerAPI foot = box.createUIElement(Math.max(120f, rowW - 200f), 24f, false);
        foot.addPara("共 " + filtered.size() + " 项 · 第 " + (pickerPage + 1) + "/" + pages
                + " 页（可直接输入名称或 ID，也可滚动 / 搜索）", 4f, Misc.getGrayColor());
        box.addUIElement(foot).inTL(8f, areaH - 24f);
        if (pages > 1) {
            button(box, rowW - 170f, areaH - 26f, 76f, 22f, "上一页", "pickerpage|prev", null);
            button(box, rowW - 90f, areaH - 26f, 76f, 22f, "下一页", "pickerpage|next", null);
        }

        place(box, x, y);
    }

    // ================= 控件工厂 =================

    private CustomPanelAPI newPanel(float w, float h) {
        return bgPanel.createCustomPanel(Math.max(1f, w), Math.max(1f, h), this);
    }

    private void place(CustomPanelAPI p, float x, float y) {
        bgPanel.addComponent(p);
        p.getPosition().inTL(x, y);
    }

    /** 在 host 内指定位置放一个按钮；tooltip 与按钮同属一个 TooltipMakerAPI，因此 tooltip 能正常显示。 */
    private ButtonAPI button(CustomPanelAPI host, float x, float y, float w, float h,
                             String text, Object id, String tooltip) {
        float cw = Math.max(1f, w);
        float ch = Math.max(1f, h);
        TooltipMakerAPI tm = host.createUIElement(cw, ch, false);
        tm.setButtonFontVictor10();
        ButtonAPI b = tm.addButton(text, id, Misc.getButtonTextColor(), Misc.getDarkPlayerColor(),
                Alignment.MID, CutStyle.ALL, cw, ch, 0f);
        if (tooltip != null && !tooltip.trim().isEmpty()) {
            final String body = tooltip;
            tm.addTooltipToPrevious(new TooltipMakerAPI.TooltipCreator() {
                @Override
                public boolean isTooltipExpandable(Object tooltipParam) {
                    return false;
                }

                @Override
                public float getTooltipWidth(Object tooltipParam) {
                    return 420f;
                }

                @Override
                public void createTooltip(TooltipMakerAPI tooltip, boolean expanded, Object tooltipParam) {
                    tooltip.addPara(body, 6f, Misc.getTextColor());
                }
            }, TooltipMakerAPI.TooltipLocation.BELOW);
        }
        host.addUIElement(tm).inTL(x, y);
        return b;
    }

    private TextFieldAPI textField(CustomPanelAPI host, float x, float y, float w, float h,
                                   String initial, String key) {
        float cw = Math.max(1f, w);
        float ch = Math.max(1f, h);
        TooltipMakerAPI tm = host.createUIElement(cw, ch, false);
        TextFieldAPI f = tm.addTextField(cw, ch);
        if (initial != null && !initial.isEmpty()) {
            f.setText(initial);
        }
        host.addUIElement(tm).inTL(x, y);
        fields.put(key, f);
        lastFieldText.put(key, initial == null ? "" : initial);
        return f;
    }

    // ================= 输入 =================

    @Override
    public void processInput(List<InputEventAPI> events) {
        if (events == null) {
            return;
        }
        try {
            for (InputEventAPI e : events) {
                if (e == null || e.isConsumed()) {
                    continue;
                }
                if (e.isKeyDownEvent() && e.getEventValue() == Keyboard.KEY_ESCAPE) {
                    e.consume();
                    onEscape();
                    return;
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private void onEscape() {
        if (pickerOpen) {
            pickerOpen = false;
            needsRebuild = true;
            return;
        }
        if (editing != null) {
            commitFields();
            editing = null;
            draft.clear();
            needsRebuild = true;
            return;
        }
        close();
    }

    @Override
    public void advance(float amount) {
        super.advance(amount);
        if (parent == null) {
            return;
        }
        try {
            GameState st = Global.getCurrentState();
            if (st != GameState.CAMPAIGN && st != GameState.COMBAT) {
                close();
                return;
            }
            if (ConsoleOverlayPanel.getInstance() != null) {
                close();
                return;
            }
            pollFields();
            if (needsRebuild) {
                needsRebuild = false;
                rebuild();
            }
        } catch (Throwable ignored) {
        }
    }

    /** 轮询输入框变化：只更新状态，不重建（避免光标丢失）。 */
    private void pollFields() {
        for (Map.Entry<String, TextFieldAPI> en : fields.entrySet()) {
            String key = en.getKey();
            TextFieldAPI f = en.getValue();
            if (f == null) {
                continue;
            }
            String now;
            try {
                now = f.getText();
            } catch (Throwable t) {
                continue;
            }
            String before = lastFieldText.get(key);
            if (now == null ? before == null : now.equals(before)) {
                continue;
            }
            lastFieldText.put(key, now);
            if ("search".equals(key)) {
                query = now == null ? "" : now;
                page = 0;
                needsRebuild = true;
            } else if ("picker".equals(key)) {
                pickerQuery = now == null ? "" : now;
                pickerPage = 0;
                needsRebuild = true;
            } else {
                int bar = key.indexOf('|');
                if (bar > 0) {
                    draft.put(key, now == null ? "" : now);
                    updatePreview(key.substring(0, bar));
                }
            }
        }
    }

    private void updatePreview(String command) {
        if (previewLabel == null) {
            return;
        }
        try {
            CatalogEntry e = catalog.byCommand(command);
            if (e != null) {
                previewLabel.setText(previewText(e));
            }
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void renderBelow(float alphaMult) {
        if (parent == null) {
            return;
        }
        try {
            float a = (float) FrontendSettings.backgroundDarkening;
            Color c = Color.BLACK;
            GL11.glPushMatrix();
            GL11.glDisable(GL11.GL_TEXTURE_2D);
            GL11.glDisable(GL11.GL_CULL_FACE);
            GL11.glEnable(GL11.GL_BLEND);
            GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
            GL11.glColor4f(c.getRed() / 255f, c.getGreen() / 255f, c.getBlue() / 255f, a * alphaMult);
            GL11.glRectf(0f, 0f, Global.getSettings().getScreenWidth(), Global.getSettings().getScreenHeight());
            GL11.glPopMatrix();
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void positionChanged(PositionAPI position) {
        super.positionChanged(position);
    }

    // ================= 按钮回调 =================

    @Override
    public void buttonPressed(Object buttonId) {
        String id = String.valueOf(buttonId);
        try {
            playClick();
            if (id.startsWith("cmd|")) {
                runCommand(id.substring(4));
            } else if (id.startsWith("edit|")) {
                openEditor(id.substring(5));
            } else if (id.startsWith("run|")) {
                runCommand(id.substring(4));
            } else if (id.startsWith("reset|")) {
                resetParams(id.substring(6));
            } else if ("editclose".equals(id)) {
                commitFields();
                editing = null;
                draft.clear();
                needsRebuild = true;
            } else if (id.startsWith("pstep|")) {
                stepParam(id.substring(6));
            } else if (id.startsWith("pbool|")) {
                toggleBool(id.substring(6));
            } else if (id.startsWith("pickopen|")) {
                int a = id.indexOf('|', 9);
                if (a > 0) {
                    openPicker(id.substring(9, a), id.substring(a + 1));
                }
            } else if (id.startsWith("pick|")) {
                int a = id.indexOf('|', 5);
                if (a > 0) {
                    choosePick(id.substring(a + 1));
                }
            } else if (id.startsWith("picksrc|")) {
                pickerSource = id.substring(8);
                pickerPage = 0;
                pickerQuery = "";
                needsRebuild = true;
            } else if ("pickcancel".equals(id)) {
                pickerOpen = false;
                needsRebuild = true;
            } else if (id.startsWith("pickerpage|")) {
                pickerPage += "next".equals(id.substring(11)) ? 1 : -1;
                if (pickerPage < 0) {
                    pickerPage = 0;
                }
                needsRebuild = true;
            } else if (id.startsWith("tab|")) {
                commitFields();
                category = id.substring(4);
                page = 0;
                needsRebuild = true;
            } else if (id.startsWith("page|")) {
                page += "next".equals(id.substring(5)) ? 1 : -1;
                if (page < 0) {
                    page = 0;
                }
                needsRebuild = true;
            } else if ("refresh".equals(id)) {
                commitFields();
                idCache.clear();
                IdSource.invalidate();
                FrontendLabels.reload();
                catalog.build(context);
                statusLine = "目录已刷新";
                needsRebuild = true;
            } else if ("clearsearch".equals(id)) {
                query = "";
                page = 0;
                needsRebuild = true;
            } else if ("clearlog".equals(id)) {
                try {
                    ConsoleOverlayPanel.setOutput("");
                } catch (Throwable ignored) {
                }
                needsRebuild = true;
            } else if ("close".equals(id)) {
                close();
            }
        } catch (Throwable t) {
            warn("按钮处理失败 (" + id + "): " + t);
        }
    }

    private void playClick() {
        try {
            Global.getSoundPlayer().playUISound("ui_button_pressed", 1f, 1f);
        } catch (Throwable ignored) {
        }
    }

    // ================= 参数与执行 =================

    private String fieldKey(String command, String key) {
        return command + "|" + key;
    }

    private String valueOf(CatalogEntry e, ParamSpec p) {
        String d = draft.get(fieldKey(e.command, p.key));
        if (d != null) {
            return d;
        }
        return ParamStore.get(e.command, p.key, p.defaultValue == null ? "" : p.defaultValue);
    }

    private void commitFields() {
        for (Map.Entry<String, TextFieldAPI> en : fields.entrySet()) {
            String key = en.getKey();
            if ("search".equals(key) || "picker".equals(key)) {
                continue;
            }
            int bar = key.indexOf('|');
            if (bar <= 0 || en.getValue() == null) {
                continue;
            }
            try {
                draft.put(key, en.getValue().getText());
            } catch (Throwable ignored) {
            }
        }
    }

    private void openEditor(String command) {
        commitFields();
        editing = command;
        draft.clear();
        CatalogEntry e = catalog.byCommand(command);
        if (e != null) {
            for (ParamSpec p : e.params) {
                draft.put(fieldKey(command, p.key),
                        ParamStore.get(command, p.key, p.defaultValue == null ? "" : p.defaultValue));
            }
        }
        needsRebuild = true;
    }

    private void resetParams(String command) {
        ParamStore.reset(command);
        draft.clear();
        CatalogEntry e = catalog.byCommand(command);
        if (e != null) {
            for (ParamSpec p : e.params) {
                draft.put(fieldKey(command, p.key), p.defaultValue == null ? "" : p.defaultValue);
            }
        }
        statusLine = "已恢复默认参数";
        needsRebuild = true;
    }

    private void stepParam(String payload) {
        String[] p = payload.split("\\|");
        if (p.length != 3) {
            return;
        }
        CatalogEntry e = catalog.byCommand(p[0]);
        if (e == null) {
            return;
        }
        ParamSpec spec = null;
        for (ParamSpec s : e.params) {
            if (s.key.equals(p[1])) {
                spec = s;
                break;
            }
        }
        if (spec == null) {
            return;
        }
        String key = fieldKey(p[0], p[1]);
        TextFieldAPI f = fields.get(key);
        String cur = f != null ? f.getText() : valueOf(e, spec);
        double v;
        try {
            v = Double.parseDouble(cur.trim());
        } catch (Throwable t) {
            try {
                v = spec.defaultValue == null || spec.defaultValue.isEmpty() ? 0d : Double.parseDouble(spec.defaultValue);
            } catch (Throwable t2) {
                v = 0d;
            }
        }
        double step = Math.max(1d, Math.abs(v) * 0.1d);
        if (spec.min != null && spec.max != null && (spec.max - spec.min) <= 100) {
            step = 1d;
        }
        v += "inc".equals(p[2]) ? step : -step;
        v = spec.clamp(v);
        String nv = ParamSpec.TYPE_INT.equals(spec.type) ? String.valueOf((long) v) : ParamSpec.trim(v);
        draft.put(key, nv);
        if (f != null) {
            try {
                f.setText(nv);
                lastFieldText.put(key, nv);
            } catch (Throwable ignored) {
            }
        }
        ParamStore.set(p[0], p[1], nv);
        updatePreview(p[0]);
    }

    private void toggleBool(String payload) {
        int bar = payload.indexOf('|');
        if (bar <= 0) {
            return;
        }
        String cmd = payload.substring(0, bar);
        String key = payload.substring(bar + 1);
        CatalogEntry e = catalog.byCommand(cmd);
        if (e == null) {
            return;
        }
        String cur = draft.get(fieldKey(cmd, key));
        if (cur == null) {
            cur = ParamStore.get(cmd, key, "");
        }
        String nv = isOn(cur) ? "false" : "true";
        draft.put(fieldKey(cmd, key), nv);
        ParamStore.set(cmd, key, nv);
        needsRebuild = true;
    }

    private static boolean isOn(String v) {
        return v != null && ("true".equalsIgnoreCase(v) || "on".equalsIgnoreCase(v) || "1".equals(v));
    }

    private void openPicker(String command, String paramKey) {
        commitFields();
        CatalogEntry e = catalog.byCommand(command);
        if (e == null) {
            return;
        }
        for (ParamSpec p : e.params) {
            if (p.key.equals(paramKey)) {
                pickerCommand = command;
                pickerParamKey = paramKey;
                pickerSource = p.source == null || p.source.isEmpty() ? IdSource.COMMODITY_SPECIAL : p.source;
                pickerOpen = true;
                pickerPage = 0;
                pickerQuery = "";
                needsRebuild = true;
                return;
            }
        }
    }

    private void choosePick(String id) {
        if (pickerCommand == null || pickerParamKey == null) {
            pickerOpen = false;
            needsRebuild = true;
            return;
        }
        draft.put(fieldKey(pickerCommand, pickerParamKey), id);
        ParamStore.set(pickerCommand, pickerParamKey, id);
        pickerOpen = false;
        statusLine = "已选择: " + id;
        needsRebuild = true;
    }

    private List<IdOption> idOptions(String source) {
        List<IdOption> cached = idCache.get(source);
        if (cached != null) {
            return cached;
        }
        List<IdOption> built = IdSource.build(source);
        idCache.put(source, built);
        return built;
    }

    /** 解析所有参数（ID 类做名称→ID 映射），生成要执行的命令行。 */
    private List<String> buildLines(CatalogEntry e) {
        List<String> lines = new ArrayList<String>();
        Map<String, String> vals = new LinkedHashMap<String, String>();
        for (ParamSpec p : e.params) {
            String v = valueOf(e, p);
            if (ParamSpec.TYPE_ID.equals(p.type) && v != null && !v.trim().isEmpty()) {
                String src = p.source == null || p.source.isEmpty() ? IdSource.COMMODITY_SPECIAL : p.source;
                List<IdOption> opts = idOptions(src);
                String resolved = IdSource.resolve(opts, v);
                if (!resolved.equals(v.trim())) {
                    List<IdOption> cand = IdSource.candidates(opts, v, 4);
                    if (cand.size() > 1) {
                        StringBuilder sb = new StringBuilder("多个匹配，已采用 " + resolved + "：");
                        for (IdOption o : cand) {
                            sb.append(' ').append(o.primary()).append('(').append(o.id).append(')');
                        }
                        statusLine = sb.toString();
                    }
                }
                vals.put(p.key, resolved);
            } else {
                vals.put(p.key, p.format(v));
            }
        }

        if (e.run != null && !e.run.isEmpty()) {
            for (String tpl : e.run) {
                String line = tpl;
                for (Map.Entry<String, String> en : vals.entrySet()) {
                    line = line.replace("%" + en.getKey() + "%", en.getValue() == null ? "" : en.getValue());
                }
                line = line.replaceAll("\\s+", " ").trim();
                if (!line.isEmpty()) {
                    lines.add(line);
                }
            }
        } else {
            StringBuilder sb = new StringBuilder(e.command);
            for (ParamSpec p : e.params) {
                String v = vals.get(p.key);
                if (v != null && !v.trim().isEmpty()) {
                    sb.append(' ').append(v.trim());
                }
            }
            lines.add(sb.toString().trim());
        }
        return lines;
    }

    private String previewText(CatalogEntry e) {
        try {
            List<String> lines = buildLines(e);
            StringBuilder sb = new StringBuilder("将执行:  ");
            for (int i = 0; i < lines.size(); i++) {
                if (i > 0) {
                    sb.append("  ;  ");
                }
                sb.append(lines.get(i));
            }
            return sb.toString();
        } catch (Throwable t) {
            return "将执行:  " + e.command;
        }
    }

    /** 点击按钮直接执行（使用已记住的参数）。 */
    private void runCommand(String command) {
        CatalogEntry e = catalog.byCommand(command);
        if (e == null) {
            return;
        }
        commitFields();
        List<String> lines = buildLines(e);
        if (lines.isEmpty()) {
            return;
        }
        for (ParamSpec p : e.params) {
            String v = valueOf(e, p);
            ParamStore.set(e.command, p.key, p.format(v));
        }
        for (String line : lines) {
            execute(line);
        }
        needsRebuild = true;
    }

    private void execute(String commandLine) {
        try {
            Console.parseInput(commandLine, context);
        } catch (Throwable t) {
            try {
                Console.showException("前端面板执行失败: " + commandLine, t);
            } catch (Throwable ignored) {
            }
        }
    }

    // ================= 工具 =================

    private static String tailLines(String text, int maxLines) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        String[] lines = text.split("\n", -1);
        int start = Math.max(0, lines.length - maxLines);
        StringBuilder sb = new StringBuilder();
        for (int i = start; i < lines.length; i++) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(lines[i]);
        }
        return sb.toString();
    }

    private static String shorten(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    private static void warn(String msg) {
        try {
            Global.getLogger(FrontendPanel.class).warn(msg);
        } catch (Throwable ignored) {
        }
    }

    /** 根据当前游戏状态推断 Console 的命令上下文。 */
    public static CommandContext detectContext() {
        try {
            GameState st = Global.getCurrentState();
            if (st == GameState.COMBAT) {
                com.fs.starfarer.api.combat.CombatEngineAPI engine = Global.getCombatEngine();
                if (engine == null) {
                    return CommandContext.COMBAT_CAMPAIGN;
                }
                if (engine.isSimulation()) {
                    return CommandContext.COMBAT_SIMULATION;
                }
                if (engine.isInCampaign()) {
                    return CommandContext.COMBAT_CAMPAIGN;
                }
                if (engine.getMissionId() != null) {
                    return CommandContext.COMBAT_MISSION;
                }
                return CommandContext.MAIN_MENU;
            }
            if (st == GameState.CAMPAIGN) {
                CampaignUIAPI ui = Global.getSector().getCampaignUI();
                InteractionDialogAPI d = ui == null ? null : ui.getCurrentInteractionDialog();
                SectorEntityToken t = d == null ? null : d.getInteractionTarget();
                if (t != null && t.getMarket() != null) {
                    return CommandContext.CAMPAIGN_MARKET;
                }
                return CommandContext.CAMPAIGN_MAP;
            }
        } catch (Throwable ignored) {
        }
        return CommandContext.MAIN_MENU;
    }
}
