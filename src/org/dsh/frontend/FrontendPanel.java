package org.dsh.frontend;

import com.fs.starfarer.api.GameState;
import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.Script;
import com.fs.starfarer.api.SettingsAPI;
import com.fs.starfarer.api.campaign.BaseCustomUIPanelPlugin;
import com.fs.starfarer.api.campaign.CustomDialogDelegate.CustomDialogCallback;
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
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 控制台前端面板：热键呼出的覆盖层，点击按钮即自动执行对应的 Console Commands 命令。
 *
 * <p>带参数的命令支持自定义参数并记住（{@link ParamStore}）；ID 类参数提供
 * 【游戏内名称优先、ID 作为注释】的可搜索下拉选择器，同时允许直接输入。
 *
 * <p>三个关键实现约束（均来自对已装 Mod 与 Console Commands 源码的核对）：
 * <ol>
 *   <li>覆盖层通过反射挂到 {@code screenPanel} 上（与 Console Commands 的 V2 面板同路线）。</li>
 *   <li>只有主面板使用本插件；子面板使用 {@link ChildPlugin}，它把 buttonPressed/processInput
 *       转发回来但不参与渲染 —— 否则全屏遮罩会被每个子面板重复绘制、互相覆盖。</li>
 *   <li>控件先 addUIElement 挂到面板、再 addButton/addTextField，确保按钮监听器能找到宿主面板。</li>
 *   <li><b>不使用</b>原生 TextField 的焦点机制：原版 TextField 一旦获得焦点就会吞掉所有按键
 *       （含 ESC），见 RefitFilters/SearchBarFilterPanel.kt:62-65。改为自行记录聚焦字段并
 *       在 processInput 中转发按键（{@link #forwardKey}），ESC 始终优先。</li>
 * </ol>
 */
public class FrontendPanel extends BaseCustomUIPanelPlugin {

    private static FrontendPanel instance;

    public static FrontendPanel getInstance() {
        return instance;
    }

    public static boolean isOpen() {
        return instance != null;
    }

    /** 子面板插件：转发按钮/输入事件，但不渲染（避免全屏遮罩重复绘制）。 */
    private class ChildPlugin extends BaseCustomUIPanelPlugin {
        @Override
        public void buttonPressed(Object buttonId) {
            FrontendPanel.this.buttonPressed(buttonId);
        }

        @Override
        public void processInput(List<InputEventAPI> events) {
            FrontendPanel.this.processInput(events);
        }
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

    /**
     * 命令自身自动补全给出的候选（{@link SuggestionSource}）。
     *
     * <p>非空时选择器只列这些候选 —— 这正是玩家反馈的诉求：gcsAddKemomimi 的第一个
     * 参数应该列 gcs_janus / gcs_anato 这类真实取值，而不是游戏里的全部商品。
     * 为 null 表示该命令没有实现建议接口，此时回落到按参数来源铺全量列表。
     */
    private List<IdOption> pickerSuggestions;

    /**
     * 选择器的候选是否为「该参数的预设取值」（枚举 options）。
     *
     * <p>与 {@link #pickerSuggestions} 的区别在于文案：枚举选项来自命令参数自身的定义
     * （devmode 的状态 = 留空 / on / off），不是命令的自动补全接口，标题与页脚要分开说。
     */
    private boolean pickerEnum;

    private final Map<String, List<IdOption>> idCache = new HashMap<String, List<IdOption>>();

    private final Map<String, TextFieldAPI> fields = new LinkedHashMap<String, TextFieldAPI>();
    private final Map<String, String> lastFieldText = new LinkedHashMap<String, String>();
    private final List<UIComponentAPI> interactive = new ArrayList<UIComponentAPI>();
    private LabelAPI previewLabel;
    private boolean needsRebuild;
    private String statusLine = "";

    /**
     * 聚焦输入框的轮询去抖状态。
     *
     * <p>见 {@link #pollFields()}：输入框一旦 grabFocus，原生 TextField 会先把按键
     * 处理掉，回车等事件到不了 {@code processInput}，所以只能主动读控件文本。
     * 为避免每敲一个字符就重建整个面板（会销毁并重建 TextFieldAPI，可能打断
     * 中文 IME 组合），文本要连续 {@link #POLL_STABLE_FRAMES} 帧不变才同步。
     */
    private String pendingFocusText;
    private int pendingFocusFrames;
    private static final int POLL_STABLE_FRAMES = 6;

    /**
     * 日志区的滚动器。
     *
     * <p>不在 buildLogArea 里直接设偏移，是因为正确偏移要等内容完成布局才有意义；
     * 改由 {@link #advance(float)} 每帧把日志滚到底（{@link #scrollLogToBottom()}）。
     */
    private ScrollPanelAPI logScroller;

    /** 日志滚动器是否支持 {@code scrollToBottom()}（需经反射调用，见 buildLogArea 注释）。 */
    private boolean logScrollToBottom;

    /**
     * 日志区当前页（0 = 最新一行在最上方）。
     *
     * <p>日志区改成一页一屏 + 最新在上：整页正好铺满可视高度，既不会被下边缘裁掉，
     * 也不依赖那个始终画不出来的滚动条；翻页由「上一页 / 下一页」按钮或滚轮完成。
     */
    private int logPage = 0;

    /** 日志区面板的位置，用于判断滚轮是否落在日志区上。 */
    private PositionAPI logAreaPos;

    /** 上一次构建日志时输出串的长度；一变就跳回第 1 页。 */
    private int logSeenLen = -1;

    // ---- 纵向布局常量（自上而下，单位像素）----
    //
    // 注意：原版 TextFieldAPI 的<b>实际渲染高度大于请求高度</b>——
    // 请求 24px 时实测约占 40px（与字体行高有关）。因此行距不能按请求高度算，
    // 必须按【最坏情况渲染高度】留白，否则搜索框底部会压到下一行的标签栏。
    // 下面每行的可用高度都按 40px 预留。
    private static final float Y_HEADER = 8f;
    private static final float H_HEADER = 30f;

    private static final float Y_SEARCH = 46f;
    private static final float H_SEARCH = 34f;
    /** 搜索框请求高度（实际渲染高度约为请求值 +20，故下一行要按 44px 预留）。 */
    private static final float H_SEARCH_FIELD = 22f;

    /** 标签行的 y：必须 ≥ Y_SEARCH + 44，实测取 104 时仍有轻微接触，故留到 108。 */
    private static final float Y_CATEGORY = 108f;
    private static final float H_CATEGORY = 30f;

    /** 参数区 / 按钮列表区的起始 y。 */
    private static final float Y_CONTENT = 152f;

    /** 日志区高度（比早期的 150 加高，一页能多显示两行）。 */
    private static final float LOG_AREA_H = 190f;

    /** 日志正文行高（victor16 行高 18，留 1px 余量）。 */
    private static final float LOG_LINE_H = 19f;

    /**
     * 正文 / 标签 / 文本框字体。
     *
     * <p>{@code TooltipMakerAPI} 没有 {@code setParaFontVictor16()}，但 {@code setParaFont(String)}
     * 会把传入值直接写进 paraFont 字段（字节码确认），因此可以传任意字面路径；
     * 文本框的 4 参重载 {@code addTextField(w,h,font,pad)} 同样接受字面路径。
     * victor16.fnt 行高 18（victor14 只有 13），中文 6738 字形齐全。
     *
     * <p><b>按钮不能跟着换</b>：{@code setButtonFont*} 只有 7 个固定字面量，没有
     * {@code setButtonFont(String)}；其中最大的 orbitron20aa/24aa 缺 181 个汉字
     * （逐字形核对），物品/船名会变方块 —— 故按钮保持 Victor14。
     */
    private static final String FONT_PARA = "graphics/fonts/victor16.fnt";

    private float lastW;
    private float lastH;
    private boolean loggedRender;
    private boolean closed;
    private boolean mountedInDialog;
    /** 当前正在编辑的输入框 key（不依赖原生焦点机制，见 {@link #forwardKey}）。 */
    private String focusedField;

    /**
     * 对话框给的关闭回调（{@link CustomDialogCallback}）。
     *
     * <p><b>为什么必须自己拿它</b>：对话框自带的「关闭 (G)」按钮点击后只调用
     * {@code fader.fadeIn()}，真正关闭要等 {@code advance()} 里
     * {@code fader.getBrightness() == 1f} 这个精确相等判断成立（已反汇编
     * {@code com.fs.starfarer.ui.newui.super} 确认）。该链路一旦卡住按钮就形同虚设。
     * 而 {@code dismissCustomDialog(int)} 的字节码是直接 {@code invokevirtual dismiss(I)}
     * ——完全不经过 fader，是确定性的关闭路径。
     */
    private CustomDialogCallback dialogCallback;

    /** 由 {@link FrontendDialogDelegate} 在挂载时注入。 */
    public void setDialogCallback(CustomDialogCallback callback) {
        this.dialogCallback = callback;
    }

    public FrontendPanel(CommandContext ctx) {
        this.context = ctx;
        instance = this;
        try {
            catalog.build(context);
            info("命令目录构建完成: " + catalog.entries().size() + " 条");
        } catch (Throwable t) {
            warn("构建命令目录失败: " + t);
        }
    }

    /**
     * 打开前端面板。
     *
     * <p>走<b>公共 API 对话框</b>路线（{@code showInteractionDialog} +
     * {@code showCustomDialog}），不使用任何反射 —— 实测游戏对 mod 脚本施加了
     * SecurityManager 限制，自建反射会被拒绝，而这条路线完全不需要反射。
     */
    public static void open(final CommandContext ctx) {
        if (instance != null) {
            return;
        }
        final GameState st = Global.getCurrentState();
        try {
            if (st == GameState.COMBAT) {
                // 战斗中：挂一个战斗插件自绘面板（addPlugin 是公共 API）
                Global.getCombatEngine().addPlugin(new FrontendCombatPanel(
                        new FrontendPanel(ctx == null ? detectContext() : ctx)));
                return;
            }
        } catch (Throwable t) {
            instance = null;
            warnStack("战斗中打开前端面板失败: " + t, t);
            return;
        }

        // 战役中：延后一帧再开对话框。
        // 直接在当前输入回调里 showInteractionDialog 会与正在遍历的输入事件冲突
        // （Console Commands 也是用一个 transient 脚本延后打开的）。
        try {
            Global.getSector().addTransientScript(new com.fs.starfarer.api.EveryFrameScript() {
                private boolean done;

                @Override
                public boolean runWhilePaused() {
                    return true;
                }

                @Override
                public boolean isDone() {
                    return done;
                }

                @Override
                public void advance(float amount) {
                    if (done) {
                        return;
                    }
                    done = true;
                    try {
                        FrontendDialogPlugin.openDialog();
                    } catch (Throwable t) {
                        warnStack("打开前端面板对话框失败: " + t, t);
                    }
                }
            });
        } catch (Throwable t) {
            instance = null;
            warnStack("安排前端面板打开失败: " + t, t);
        }
    }

    public static void closeIfOpen() {
        FrontendPanel p = instance;
        if (p != null) {
            p.close();
        }
    }

    // ================= 挂载 / 卸载 =================

    /**
     * 由 {@link FrontendDialogDelegate} 在对话框创建时回调，拿到宿主面板。
     * 全部后续控件都在这个 CustomPanelAPI 上构建，无需反射。
     */
    public void attachToDialog(CustomPanelAPI host) {
        if (host == null) {
            return;
        }
        parent = host;
        mountedInDialog = true;
        try {
            lastW = host.getPosition().getWidth();
            lastH = host.getPosition().getHeight();
        } catch (Throwable ignored) {
        }
        // 暂停游戏（与 Console Commands 的行为一致）
        try {
            if (Global.getCurrentState() == GameState.COMBAT) {
                wasPaused = Global.getCombatEngine().isPaused();
                Global.getCombatEngine().setPaused(true);
            } else if (Global.getCurrentState() == GameState.CAMPAIGN) {
                wasPaused = Global.getSector().isPaused();
                Global.getSector().setPaused(true);
            }
        } catch (Throwable ignored) {
        }
        rebuild();
        info("面板已挂载到对话框: 尺寸=" + lastW + "x" + lastH
                + " 按钮=" + interactive.size() + " | " + Reflect.status());
    }

    /** 供对话框插件判断面板是否已请求关闭。 */
    public boolean isClosed() {
        return closed;
    }

    private void legacyMount() {
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

        // 先拿到 screenPanel —— 占位对话框也挂在它上面，不是 CampaignUIAPI 的字段
        Object state = null;
        try {
            state = AppDriver.getInstance().getCurrentState();
        } catch (Throwable ignored) {
        }
        Object screenPanel = Reflect.findScreenPanel(state);
        if (screenPanel == null) {
            // 失败时把 state 上所有候选方法名写进日志，便于定位
            throw new IllegalStateException("无法取得 screenPanel。"
                    + Reflect.describePanelCandidates(state));
        }
        // 同样不做 instanceof 校验：脚本类加载器与 API 类加载器可能不同，
        // 同一个接口会有两个 Class 对象，instanceof 会误判为 false。
        // 后续全部通过接口/反射调用，不做强制类型转换。
        UIPanelAPI sp = screenPanel instanceof UIPanelAPI ? (UIPanelAPI) screenPanel : null;
        Object spObj = screenPanel;

        // 战役中创建一个透明的提示对话框占位：
        //   作用 —— 拦住其它 mod 与地图输入；
        //   关键 —— 必须把它设为完全透明，否则它会盖在面板上方，
        //          玩家只会看到一个空对话框 + 【确定】按钮（实测现象）；
        //   查找 —— 对话框挂在 screenPanel 上，通过【含 getOptionMap 的子面板】定位，
        //          与 Console Commands 的做法一致（原实现误从 CampaignUIAPI 取字段，永远取不到）。
        if (context != null && context.isInCampaign()) {
            try {
                CampaignUIAPI ui = Global.getSector().getCampaignUI();
                if (ui != null && !ui.isShowingDialog()) {
                    ui.showMessageDialog("");
                    // 官方做法是从 CampaignUIAPI 的运行时对象上取 screenPanel 字段
                    // （接口上没有，但实现类 CampaignUI 上有），再从它的子组件里找对话框。
                    // 两条路径都试，任一成功即可。
                    Object dialog = null;
                    Object uiScreenPanel = Reflect.getFieldValue(ui, "screenPanel");
                    if (uiScreenPanel != null) {
                        dialog = Reflect.findChildWithMethod(uiScreenPanel, "getOptionMap");
                    }
                    if (dialog == null) {
                        dialog = Reflect.findChildWithMethod(spObj, "getOptionMap");
                    }
                    if (dialog != null) {
                        Reflect.invoke(dialog, "setOpacity", Float.valueOf(0f));
                        Reflect.invoke(dialog, "setBackgroundDimAmount", Float.valueOf(0f));
                        Reflect.invoke(dialog, "setAbsorbOutsideEvents", Boolean.FALSE);
                        Reflect.invoke(dialog, "makeOptionInstant", Integer.valueOf(0));
                        placeHolderDialog = dialog;
                        info("占位对话框已透明化: " + dialog.getClass().getName());
                    } else {
                        warn("screenPanel 中找不到占位对话框（getOptionMap），面板可能被遮挡");
                    }
                }
            } catch (Throwable t) {
                warn("创建透明占位对话框失败: " + t);
            }
        }
        Object spPos = Reflect.invoke(spObj, "getPosition");
        float w = Global.getSettings().getScreenWidth();
        float h = Global.getSettings().getScreenHeight();
        if (spPos != null) {
            Object pw = Reflect.invoke(spPos, "getWidth");
            Object ph = Reflect.invoke(spPos, "getHeight");
            if (pw instanceof Number) {
                w = ((Number) pw).floatValue();
            }
            if (ph instanceof Number) {
                h = ((Number) ph).floatValue();
            }
        }
        lastW = w;
        lastH = h;

        // 父面板始终占满 screenPanel，便于居中定位；真正的内容宽度由 bgPanel 按设置决定
        parent = Global.getSettings().createCustom(w, h, null);
        Reflect.invoke(spObj, "addComponent", parent);
        parent.getPosition().inTL(0f, 0f);
        // 提到最上层：占位对话框是后于我们之外挂到 screenPanel 上的，
        // 不提升层级的话面板会被它压在下面（实测表现为【只有一个空对话框 + 确定按钮】）。
        try {
            Reflect.invoke(spObj, "bringComponentToTop", parent);
        } catch (Throwable ignored) {
        }

        try {
            catalog.build(context);
            info("命令目录构建完成: " + catalog.entries().size() + " 条");
        } catch (Throwable t) {
            warn("构建命令目录失败: " + t);
        }
        rebuild();
        info("面板挂载完成: screenPanel=" + spObj.getClass().getName()
                + " 尺寸=" + w + "x" + h + " 按钮=" + interactive.size());
    }

    public void close() {
        // 幂等守卫：close() 内会调用 dialogCallback.dismissCustomDialog(0)，
        // 而游戏那边 dismiss(0) 又会回调 customDialogConfirm() -> panel.close()，
        // 没有这道守卫就会无限递归。
        if (closed) {
            return;
        }
        try {
            ParamStore.save();
        } catch (Throwable ignored) {
        }
        closed = true;
        // 对话框路线：主动走确定性关闭回调（不依赖 fader 动画）。
        // FrontendDialogPlugin.advance() 里的 isClosed() 检测作为兜底仍然保留。
        try {
            if (dialogCallback != null) {
                info("close(): 调用 dismissCustomDialog(0)");
                dialogCallback.dismissCustomDialog(0);
            }
        } catch (Throwable t) {
            warn("close(): dismissCustomDialog 失败: " + t);
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
        parent = null;
        bgPanel = null;
        instance = null;
    }

    /** 对话框挂载完成后的标记（供诊断日志）。 */
    public void markMountedInDialog() {
        info("showCustomDialog 已提交");
    }

    // ================= 重建 =================

    private void rebuild() {
        if (parent == null) {
            return;
        }
        float fullW = parent.getPosition().getWidth();
        float fullH = parent.getPosition().getHeight();
        // 对话框路线下 parent 就是对话框给的宿主面板，直接用它的尺寸铺满
        float contentW = fullW;
        float contentH = fullH;
        if (!mountedInDialog) {
            contentW = (float) (fullW * clampFraction(FrontendSettings.panelWidthFraction));
            contentH = Math.max(200f, fullH - 60f);
        }
        if (bgPanel == null) {
            bgPanel = parent.createCustomPanel(contentW, contentH, this);
            parent.addComponent(bgPanel);
        } else {
            bgPanel.getPosition().setSize(contentW, contentH);
        }
        bgPanel.getPosition().inTL(mountedInDialog ? 0f : (fullW - contentW) / 2f, mountedInDialog ? 0f : 30f);

        // 记住当前聚焦的字段：重建后要恢复它。
        // 若这里清成 null，则【按键 -> 重建 -> 失焦】会让玩家每次只能输入一个字符（实测问题）。
        final String keepFocus = focusedField;

        clearPanel(bgPanel);
        fields.clear();
        lastFieldText.clear();
        interactive.clear();
        previewLabel = null;
        logScroller = null;
        logScrollToBottom = false;
        logAreaPos = null;
        focusedField = null;
        pendingFocusText = null;
        pendingFocusFrames = 0;

        float w = bgPanel.getPosition().getWidth();
        float h = bgPanel.getPosition().getHeight();
        // 左右各留 8px 安全边距：host 面板若比请求尺寸略窄，
        // 用 rowW = w - margin*2 计算的右对齐元素会被裁掉（实测右列缺一半）。
        float margin = 20f;
        if (mountedInDialog) {
            margin = 14f;
        }

        try {
            buildHeader(w, h, margin);
            if (pickerOpen) {
                // 选择器独占整个面板：不渲染底层按钮网格 / 参数区 / 日志区。
                // 这样底层不存在任何可接收事件的控件，鼠标不会【漏】到下面
                // （原先选择器只是叠在网格之上，底层按钮仍在收事件）。
                buildPicker(w, h, margin);
            } else {
                buildSearchRow(w, h, margin);
                buildCategoryRow(w, h, margin);
                float paramsH = buildParamsArea(w, h, margin);
                buildListArea(w, h, margin, paramsH);
                buildLogArea(w, h, margin, paramsH);
            }
        } catch (Throwable t) {
            warn("重建面板失败: " + t);
        }

        // 恢复重建前的聚焦字段（对应的新 TextFieldAPI 实例），
        // 否则【按键 -> 重建 -> 失焦】会让玩家每按一次键就要重新点一次输入框。
        if (keepFocus != null && fields.containsKey(keepFocus)) {
            focusedField = keepFocus;
            try {
                TextFieldAPI f = fields.get(keepFocus);
                if (f != null) {
                    f.setText(lastFieldText.get(keepFocus) == null ? "" : lastFieldText.get(keepFocus));
                    f.showCursor();
                    // 重建会销毁旧 TextFieldAPI，原生焦点随之丢失；不重新 grabFocus，
                    // 玩家回车触发重建后想接着改搜索词就得再点一次输入框。
                    try {
                        f.grabFocus(true);
                    } catch (Throwable ignored) {
                    }
                }
            } catch (Throwable ignored) {
            }
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
        CustomPanelAPI row = newPanel(rowW, H_HEADER);

        TooltipMakerAPI tm = row.createUIElement(rowW - 140f, H_HEADER, false);
        row.addUIElement(tm).inTL(0f, 4f);
        tm.setParaFont(FONT_PARA);
        tm.addPara("控制台前端 · 命令按钮    热键 " + FrontendSettings.hotkeyText(), 8f, Misc.getBrightPlayerColor());

        button(row, rowW - 130f, 2f, 124f, 24f, "关闭 (ESC)", "close", "关闭面板并恢复游戏状态");
        place(row, margin, Y_HEADER);
    }

    private void buildSearchRow(float w, float h, float margin) {
        float rowW = w - margin * 2f;
        CustomPanelAPI row = newPanel(rowW, H_SEARCH);

        // 与搜索框同 x/y；paraLabel 会把 ink 垂直居中到 H_SEARCH_FIELD 这条带
        //（addPara 的先天偏移由其内部抵消，见其注释）。
        paraLabel(row, 0f, 0f, 44f, H_SEARCH_FIELD, "搜索", Misc.getGrayColor());

        // 右侧两个按钮各 112 宽，从 rowW-240 与 rowW-122 起；
        // 输入框必须在其左侧留出间隙（这里留 12px），否则会横向压到按钮上。
        float fieldW = Math.max(120f, rowW - 44f - 250f - 12f);
        textField(row, 44f, 0f, fieldW, H_SEARCH_FIELD, query, "search");

        button(row, rowW - 240f, 0f, 112f, 22f, "刷新目录", "refresh", "重新读取全部命令与 ID 列表");
        button(row, rowW - 122f, 0f, 112f, 22f, "清空搜索", "clearsearch", null);
        place(row, margin, Y_SEARCH);
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
        CustomPanelAPI row = newPanel(rowW, H_CATEGORY);
        float x = 0f;
        for (String id : cats) {
            String name = "all".equals(id) ? "全部" : categoryName(id);
            ButtonAPI b = button(row, x, 0f, bw, H_CATEGORY - 6f, name, "tab|" + id, null);
            if (id.equals(category)) {
                b.setEnabled(false);
            }
            x += bw + gap;
        }
        place(row, margin, Y_CATEGORY);
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
        float y = Y_CONTENT;
        // 行高 46：给原生文本框的实际渲染高度留余量。文本框请求 24 时实测渲染约 40
        // （对应 victor14，行高 13）；victor16 行高 18，按比例再加约 5px ⇒ 46。
        // 行距不放宽的话，相邻两行的文本框会互相压字（实测过）。
        final float lineH = 46f;
        int lines = 1 + Math.max(1, e.params.size()) + 1;
        float areaH = lines * lineH + 24f;

        CustomPanelAPI box = newPanel(rowW, areaH);
        // 九宫格背景：游戏没有整块窗口素材，panel00_* 是 32x32 的九宫格切片。
        // 用 SolidBgPlugin 直接铺一层不透明底色（最稳），避免与下方列表区文字穿透。
        drawPanelBackground(box, rowW, areaH);

        TooltipMakerAPI tm = box.createUIElement(rowW - 20f, 32f, false);
        box.addUIElement(tm).inTL(12f, 6f);
        tm.setParaFont(FONT_PARA);
        tm.addPara(escapePercent("参数设置 · " + e.labelOrName()), 8f, Misc.getBrightPlayerColor());

        // 右侧预留：数字参数的 -/+ 两个按钮（各 30 宽）或选择器的 v 按钮（30 宽）
        final float rightReserve = 78f;
        final float labelW = 120f;
        final float leftPad = 12f;

        float fy = 40f;
        for (ParamSpec p : e.params) {
            // 标签与控件同 y、垂直居中（文本框实际渲染更高，标签偏上会显错位）
            TooltipMakerAPI lt = box.createUIElement(labelW, lineH, false);
            box.addUIElement(lt).inTL(leftPad, fy);
            lt.setParaFont(FONT_PARA);
            LabelAPI lab = lt.addPara(escapePercent(p.labelOrKey() + (p.required ? " *" : "")), 8f,
                    p.required ? Misc.getHighlightColor() : Misc.getGrayColor());
            try {
                lab.setAlignment(Alignment.LMID);
            } catch (Throwable ignored) {
            }

            String key = fieldKey(e.command, p.key);
            float ctlX = leftPad + labelW;
            // 控件宽度 = 行宽 - 标签 - 右侧预留；不要再额外减，否则右侧会溢出到行外
            float ctlW = Math.max(80f, rowW - ctlX - rightReserve);
            String val = valueOf(e, p);

            if (p.isPicker()) {
                float pickW = Math.max(80f, ctlW - 32f);
                textField(box, ctlX, fy, pickW, 24f, val, key);
                String pickTip = ParamSpec.TYPE_ENUM.equals(p.type)
                        ? "打开该参数的取值列表（可搜索）"
                        : "打开" + IdSource.displayNameOf(p.source) + "选择器（可搜索，名称优先）";
                button(box, ctlX + pickW + 4f, fy, 28f, 24f, "v",
                        "pickopen|" + e.command + "|" + p.key,
                        escapePercent(pickTip));
            } else if (p.isNumeric()) {
                float stepW = 30f;
                float fw = Math.max(80f, ctlW - stepW * 2f - 8f);
                textField(box, ctlX, fy, fw, 24f, val, key);
                button(box, ctlX + fw + 4f, fy, stepW, 24f, "-",
                        "pstep|" + e.command + "|" + p.key + "|dec", "减少");
                button(box, ctlX + fw + stepW + 4f, fy, stepW, 24f, "+",
                        "pstep|" + e.command + "|" + p.key + "|inc", "增加");
            } else if (ParamSpec.TYPE_BOOL.equals(p.type)) {
                boolean on = isOn(val);
                button(box, ctlX, fy, 120f, 24f, on ? "开 (ON)" : "关 (OFF)",
                        "pbool|" + e.command + "|" + p.key, "点击切换");
            } else {
                textField(box, ctlX, fy, ctlW, 24f, val, key);
            }
            fy += lineH;
        }
        if (e.params.isEmpty()) {
            TooltipMakerAPI nt = box.createUIElement(rowW - 24f, lineH, false);
            box.addUIElement(nt).inTL(leftPad, fy);
            nt.addPara("该命令没有参数，点击主按钮直接执行。", 6f, Misc.getGrayColor());
            fy += lineH;
        }

        // 底部行：三个按钮靠右固定，预览文本占据左侧剩余宽度
        float btnW1 = 90f;
        float btnW2 = 100f;
        float btnW3 = 90f;
        float gapB = 6f;
        float btnsTotal = btnW1 + btnW2 + btnW3 + gapB * 2f;
        float pvX = leftPad;
        float pvW = Math.max(80f, rowW - pvX - btnsTotal - 16f);

        TooltipMakerAPI pv = box.createUIElement(pvW, lineH, false);
        box.addUIElement(pv).inTL(pvX, fy);
        previewLabel = pv.addPara(escapePercent(previewText(e)), 6f, Misc.getHighlightColor());

        float bx = rowW - btnsTotal - 8f;
        button(box, bx, fy, btnW1, 24f, "执行", "run|" + e.command, "用当前参数执行一次");
        bx += btnW1 + gapB;
        button(box, bx, fy, btnW2, 24f, "恢复默认", "reset|" + e.command, "清除该命令已记住的参数");
        bx += btnW2 + gapB;
        button(box, bx, fy, btnW3, 24f, "收起", "editclose", null);

        place(box, margin, y);
        // 返回真实占用高度供列表区下移；必须 >= areaH，
        // 否则列表区会叠到参数区上（实测：加物品参数页与按钮重叠）。
        return areaH + 12f;
    }

    private void buildListArea(float w, float h, float margin, float paramsH) {
        float rowW = w - margin * 2f;
        float y = Y_CONTENT + paramsH;
        // 210 而不是早期的 200：日志区加高到 190 且底部留白 14，需要相应下移列表区下边界。
        float bottom = FrontendSettings.showOutputLog ? 212f : 40f;
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

        // 再减 10px：给右边界留余量，避免最后一列被面板边缘裁切（实测右列缺一半）
        float cellW = (rowW - gap * (cols - 1) - 24f) / cols;
        // 逐行构建：每行是一个 row 面板，行内按钮用绝对位置，行与行之间交给 addCustom 的流式布局
        for (int start = from; start < to; start += cols) {
            int end = Math.min(to, start + cols);
            float rowW2 = (end - start) * cellW + (end - start - 1) * gap;
            CustomPanelAPI rowPanel = newPanel(rowW2, FrontendSettings.buttonHeight);
            for (int i = start; i < end; i++) {
                final CatalogEntry e = list.get(i);
                float cx = (i - start) * (cellW + gap);

                boolean hasEdit = e.hasParams();
                float editW = hasEdit ? 24f : 0f;
                float mainW = cellW - editW - (hasEdit ? 3f : 0f);

                ButtonAPI main = button(rowPanel, cx, 0f, mainW, FrontendSettings.buttonHeight,
                        e.labelOrName(), "cmd|" + e.command, escapePercent(e.describe()));
                if (!e.applicable) {
                    main.setEnabled(false);
                }
                if (hasEdit) {
                    // 按钮文字用 *：齿轮符号 U+2699 在游戏字体里没有字形，
                    // 会显示成问号（实测）。
                    button(rowPanel, cx + mainW + 3f, 0f, editW, FrontendSettings.buttonHeight, "*",
                            "edit|" + e.command, escapePercent("调整【" + e.labelOrName() + "】的参数"));
                }
            }
            content.addCustom(rowPanel, 4f);
        }

        TooltipMakerAPI footer = area.createUIElement(Math.max(120f, rowW - 280f), 32f, false);
        area.addUIElement(footer).inTL(6f, areaH - 24f);
        String info = "共 " + list.size() + " 条 · 第 " + (page + 1) + "/" + pages + " 页";
        if (statusLine != null && !statusLine.isEmpty()) {
            info = statusLine + "    " + info;
        }
        footer.setParaFont(FONT_PARA);
        footer.addPara(escapePercent(info), 8f, Misc.getGrayColor());

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
        float areaH = LOG_AREA_H;
        float y = h - areaH - 14f;

        CustomPanelAPI area = newPanel(rowW, areaH);
        logAreaPos = area.getPosition();

        String out = "";
        try {
            out = ConsoleOverlayPanel.getOutput();
        } catch (Throwable ignored) {
        }
        if (out == null) {
            out = "";
        }
        // 输出长度一变就跳回第 1 页（最新），避免玩家正翻着旧页时内容被顶走。
        if (out.length() != logSeenLen) {
            logSeenLen = out.length();
            logPage = 0;
        }

        List<String> lines = logLines(out, FrontendSettings.logLines);
        // 倒序：最新的一行排到最上方。
        Collections.reverse(lines);

        int perPage = Math.max(1, (int) ((areaH - 52f) / LOG_LINE_H));
        int pages = Math.max(1, (int) Math.ceil(lines.size() / (double) perPage));
        if (logPage >= pages) {
            logPage = pages - 1;
        }
        if (logPage < 0) {
            logPage = 0;
        }
        int from = logPage * perPage;
        int to = Math.min(lines.size(), from + perPage);

        StringBuilder buf = new StringBuilder();
        for (int i = from; i < to; i++) {
            if (buf.length() > 0) {
                buf.append('\n');
            }
            buf.append(lines.get(i));
        }
        String pageText = buf.length() == 0 ? "（暂无输出）" : buf.toString();

        // 标题里顺带报行数与页码：整页正好铺满一屏，不再需要视觉滚动条。
        String title = "命令输出";
        if (pages > 1) {
            title = title + "      共 " + lines.size() + " 行 · 第 " + (logPage + 1) + "/" + pages
                    + " 页（滚轮或右侧按钮翻页）";
        }
        TooltipMakerAPI tm = area.createUIElement(Math.max(120f, rowW - 320f), 32f, false);
        area.addUIElement(tm).inTL(8f, 4f);
        tm.setParaFont(FONT_PARA);
        tm.addPara(escapePercent(title), 6f, Misc.getBrightPlayerColor());

        button(area, rowW - 100f, 2f, 92f, 22f, "清空日志", "clearlog", "清空控制台输出缓冲");
        if (pages > 1 && rowW > 400f) {
            button(area, rowW - 170f, 2f, 64f, 22f, "下一页", "logpage|next", "查看更早的输出（滚轮向下同理）");
            button(area, rowW - 240f, 2f, 64f, 22f, "上一页", "logpage|prev", "回到更新的输出（滚轮向上同理）");
        }

        TooltipMakerAPI log = area.createUIElement(rowW - 16f, areaH - 44f, true);
        area.addUIElement(log).inTL(8f, 40f);
        log.setParaFont(FONT_PARA);
        log.addPara(escapePercent(pageText), 6f, Misc.getTextColor());
        // 只记录滚动器，滚动动作交给 advance() 每帧执行。
        //
        // 【重要】绝不能写成 sc.setYOffset(Float.MAX_VALUE)：
        // ScrollPanelAPI.setYOffset(float) 的实现（com.fs.starfarer.ui.g，字节码确认）是
        //   putfield yOffset; g$Oo.forceOffset(xOffset, yOffset);
        // 而 forceOffset 又把该值原样写进内容容器的 position.setOffset(...)，【全程没有任何钳位】。
        // 于是内容被推到无穷远，整个滚动区连占位文字一起消失
        // （实测：日志区标题在、正文全空白，连「（暂无输出）」都画不出来）。
        //
        // 正确做法是调具体滚动实现上的 scrollToBottom()：它按
        //   yOffset = 内容高 - 视口高，且视口高 > 内容高时取 0
        // 自行钳位（字节码确认）。但该方法只在 ScrollPanelAPI 的实现类上，
        // 接口里没有，因此只能经反射调用。
        try {
            ScrollPanelAPI sc = log.getExternalScroller();
            if (sc != null) {
                logScroller = sc;
                logScrollToBottom = Reflect.hasMethodOfName(sc, "scrollToBottom");
            }
        } catch (Throwable ignored) {
        }

        place(area, margin, y);
    }

    private void buildPicker(float w, float h, float margin) {
        // 独占窗口：铺满面板可用区域（去掉顶部标题行的高度）
        float rowW = w - margin * 2f;
        float areaH = Math.max(300f, h - Y_CATEGORY - 16f);
        float x = margin;
        float y = Y_CATEGORY;

        CustomPanelAPI box = newPanel(rowW, areaH);
        // 与参数区一致：九宫格背景，避免与底层内容视觉穿透
        drawPanelBackground(box, rowW, areaH);

        TooltipMakerAPI tm = box.createUIElement(rowW - 24f, 32f, false);
        box.addUIElement(tm).inTL(12f, 8f);
        tm.setParaFont(FONT_PARA);
        // 有命令自带建议时，选择器的语义是「这个参数允许的取值」，
        // 不再是「游戏里的所有商品/物品」，标题要跟着变。
        String pickerTitle = pickerEnum
                ? "选择参数值（该参数的预设选项）"
                : (pickerSuggestions != null
                ? "选择参数值（候选来自该命令的自动补全）"
                : "选择" + IdSource.displayNameOf(pickerSource) + "（名称优先，括号内为 ID）");
        tm.addPara(escapePercent(pickerTitle), 8f, Misc.getBrightPlayerColor());

        // 第二行：搜索框（左侧）+ 来源标签（右侧）+ 取消
        final float row2Y = 48f;
        final float tabW = 92f;
        final float tabGap = 6f;
        final float cancelW = 72f;
        // 建议模式下没有「来源」可切（候选本来就不是按 spec 枚举来的），不显示标签页。
        List<String> tabs = pickerSuggestions != null
                ? new ArrayList<String>() : IdSource.tabsFor(pickerSource);
        float rightBlock = tabs.size() * (tabW + tabGap) + cancelW + 12f;
        float pickFieldW = Math.max(120f, rowW - 24f - rightBlock);
        textField(box, 12f, row2Y, pickFieldW, 24f, pickerQuery, "picker");

        float tx = 12f + pickFieldW + 12f;
        for (String t : tabs) {
            ButtonAPI b = button(box, tx, row2Y, tabW, 24f, IdSource.displayNameOf(t),
                    "picksrc|" + t, null);
            if (t.equals(pickerSource)) {
                b.setEnabled(false);
            }
            tx += tabW + tabGap;
        }
        button(box, rowW - cancelW - 12f, row2Y, cancelW, 24f, "取消", "pickcancel", null);

        List<IdOption> all = pickerSuggestions != null ? pickerSuggestions : idOptions(pickerSource);
        List<IdOption> filtered = IdSource.filter(all, pickerQuery);

        float listY = 82f;
        float listH = Math.max(40f, areaH - listY - 48f);
        // 行高 34：victor16 行高 18（victor14 为 13），行内按钮与 ID 注释都要跟着长高。
        float lineH = 34f;
        float innerW = rowW - 56f;

        // ---- 分页（滚动条方案已放弃）----
        // 试过两轮"内容面板撑到总高 + createUIElement(...,true)"的三层结构
        // （官方范例 MEM_ShipPicker 的写法），界面上始终不出现滚动条。
        // 现在改回分页：每页行数【用整除算】，保证最后一行完整——
        // 若按 listH/lineH 直接取整之外再多放一行，那行会只露出半截（用户明确要求避免）。
        int perPage = Math.max(1, (int) (listH / lineH));
        int pages = Math.max(1, (int) Math.ceil(filtered.size() / (double) perPage));
        if (pickerPage >= pages) {
            pickerPage = pages - 1;
        }
        if (pickerPage < 0) {
            pickerPage = 0;
        }
        int from = pickerPage * perPage;
        int to = Math.min(filtered.size(), from + perPage);

        // 行数与行高都不再需要留滚动余量，容器高度就等于本页实际高度。
        float pageH = Math.max(lineH, (to - from) * lineH);
        CustomPanelAPI listPanel = newPanel(rowW - 24f, pageH);
        TooltipMakerAPI list = listPanel.createUIElement(rowW - 24f, pageH, false);
        listPanel.addUIElement(list).inTL(0f, 0f);
        for (int i = from; i < to; i++) {
            final IdOption o = filtered.get(i);
            CustomPanelAPI line = newPanel(innerW, lineH);
            // 名称按钮占 62%，ID 注释占 36%（名称优先，ID 作注释）。
            // 两者同高同 y，注释垂直居中 —— 否则按钮文字居中而注释顶端对齐，视觉错位（实测）。
            float rowH = lineH - 2f;
            // 建议候选本身就是 id（没有游戏内显示名），再补一列 (id) 只是重复。
            boolean showId = pickerSuggestions == null;
            float nameW = showId ? innerW * 0.62f : innerW;
            String pickTip = showId ? o.primary() + "\n" + o.secondary() : o.primary();
            button(line, 0f, 0f, nameW, rowH, shorten(o.primary(), 52),
                    "pick|" + pickerSource + "|" + o.id,
                    escapePercent(pickTip));

            if (showId) {
                TooltipMakerAPI idt = line.createUIElement(innerW * 0.36f, rowH, false);
                line.addUIElement(idt).inTL(nameW + 4f, 0f);
                idt.setParaFont(FONT_PARA);
                LabelAPI idLab = idt.addPara(escapePercent(o.secondary()), 8f, Misc.getGrayColor());
                try {
                    idLab.setAlignment(Alignment.LMID);
                } catch (Throwable ignored) {
                }
            }

            // pad 0：行高严格等于 lineH，perPage 的整除计算才成立（多一行就会露半截）。
            list.addCustom(line, 0f);
        }
        box.addComponent(listPanel);
        listPanel.getPosition().inTL(8f, listY);

        // 翻页按钮放在页脚右侧，与页脚同一基线
        float pgW = 80f;
        float pgGap = 6f;
        button(box, rowW - (pgW * 2f + pgGap) - 12f, areaH - 36f, pgW, 26f, "上一页", "pickerpage|prev", null);
        button(box, rowW - pgW - 12f, areaH - 36f, pgW, 26f, "下一页", "pickerpage|next", null);

        TooltipMakerAPI foot = box.createUIElement(Math.max(120f, rowW - (pgW * 2f + pgGap) - 60f), 28f, false);
        box.addUIElement(foot).inTL(12f, areaH - 36f);
        String footText = "共 " + filtered.size() + " 项 · 第 " + (pickerPage + 1) + "/" + pages + " 页（每页 " + perPage + " 项）";
        footText += pickerEnum
                ? " · 也可直接在输入框里填写其他取值"
                : (pickerSuggestions != null
                ? " · 候选由该命令自身的自动补全接口给出"
                : " · 可直接输入名称或 ID 搜索");
        foot.addPara(escapePercent(footText), 5f, Misc.getGrayColor());

        place(box, x, y);
    }

    // ================= 控件工厂 =================

    private static double clampFraction(double v) {
        if (v < 0.4) {
            return 0.4;
        }
        return v > 1.0 ? 1.0 : v;
    }

    /** 屏幕尺寸变化（切换分辨率 / 窗口化）时重新定位内容面板并重建。 */
    private void resize() {
        try {
            if (parent == null) {
                return;
            }
            float w = Global.getSettings().getScreenWidth();
            float h = Global.getSettings().getScreenHeight();
            Object spPos = Reflect.invoke(
                    Reflect.invoke(AppDriver.getInstance().getCurrentState(), "getScreenPanel"), "getPosition");
            if (spPos instanceof PositionAPI) {
                w = ((PositionAPI) spPos).getWidth();
                h = ((PositionAPI) spPos).getHeight();
            }
            parent.getPosition().setSize(w, h);
            parent.getPosition().inTL(0f, 0f);
            if (bgPanel != null) {
                float contentW = (float) (w * clampFraction(FrontendSettings.panelWidthFraction));
                float contentH = Math.max(200f, h - 60f);
                bgPanel.getPosition().setSize(contentW, contentH);
                bgPanel.getPosition().inTL((w - contentW) / 2f, 30f);
            }
            rebuild();
        } catch (Throwable ignored) {
        }
    }

    /**
     * 给面板铺一层游戏风格的九宫格背景。
     *
     * <p>游戏没有「整块窗口」素材：{@code panel00_*} 是 32x32 的九宫格切片
     * （四角 + 四边 + 中心）。官方做法是用 {@code addImages(w, h, pad, pad, 九个路径)}
     * 一次拼出可缩放的窗框（MechExpansionModule 的 MEM_MotherShipFleetInfo.java:228 有范例）。
     * 这里直接调用它，避免自己拉伸中心图（实测单用 center 会出现条纹且不遮字）。
     */
    /**
     * 给面板铺一层纯色背景 + 边框。
     *
     * <p>曾用游戏的 panel00 九宫格，但那是装饰性花纹，拉伸后呈条纹状、观感杂乱（实测反馈）。
     * 这里改为自带的纯色贴图：深灰底 + 一圈亮色边框，简洁且不与文字争视觉。
     * 贴图在 graphics/cf_bg.png 与 graphics/cf_line.png，通过本 mod 的 settings.json 注册。
     */
    /**
     * 给面板画一圈边框。
     *
     * <p>曾经铺一层深灰纯色底，但实测观感发闷、且中间那块会在半透明区域透出
     * 突兀的色块（用户反馈「太抽象」→「不要背景了」）。现在<b>只保留 2px 边框</b>，
     * 内部完全透明，直接复用对话框自身的背景。
     */
    private void drawPanelBackground(CustomPanelAPI host, float w, float h) {
        try {
            String line = resolveSprite("cf_line", "graphics/cf_line.png");
            // 四条边各一张窄图，必须各自独立容器（addImage 是顺序堆叠的，无法原地定位）
            final float t = 2f;
            addRect(host, line, 0f, 0f, w, t);              // 上
            addRect(host, line, 0f, h - t, w, t);           // 下
            addRect(host, line, 0f, 0f, t, h);              // 左
            addRect(host, line, w - t, 0f, t, h);           // 右
        } catch (Throwable t) {
            warn("绘制面板边框失败: " + t);
        }
    }

    /** 解析贴图名：先查 settings.json 的 graphics.ui 注册，失败则用字面路径。 */
    private static String resolveSprite(String key, String fallback) {
        try {
            String s = Global.getSettings().getSpriteName("ui", key);
            if (s != null && !s.isEmpty()) {
                return s;
            }
        } catch (Throwable ignored) {
        }
        return fallback;
    }

    /** 在指定位置画一张贴图（各自独立容器，避免顺序堆叠）。 */
    private void addRect(CustomPanelAPI host, String sprite, float x, float y, float w, float h) {
        if (w <= 0f || h <= 0f) {
            return;
        }
        try {
            TooltipMakerAPI tm = host.createUIElement(w, h, false);
            host.addUIElement(tm).inTL(x, y);
            tm.addImage(sprite, w, h, 0f);
        } catch (Throwable ignored) {
        }
    }

    private CustomPanelAPI newPanel(float w, float h) {
        return bgPanel.createCustomPanel(Math.max(1f, w), Math.max(1f, h), new ChildPlugin());
    }

    private void place(CustomPanelAPI p, float x, float y) {
        bgPanel.addComponent(p);
        p.getPosition().inTL(x, y);
    }

    /**
     * 在 host 内的指定位置放一个按钮。
     * 顺序很关键：先 createUIElement -> addUIElement 挂到面板 -> 再 addButton，
     * 这样按钮的监听器才能解析到宿主面板（否则点击不会有任何反应）。
     */
    private ButtonAPI button(CustomPanelAPI host, float x, float y, float w, float h,
                             String text, Object id, String tooltip) {
        float cw = Math.max(1f, w);
        float ch = Math.max(1f, h);
        TooltipMakerAPI tm = host.createUIElement(cw, ch, false);
        host.addUIElement(tm).inTL(x, y);
        // 按钮字体只能从 7 个 setButtonFont* 里选（没有 setButtonFont(String)）。
        // Victor14 行高 13、中文 6735 字形；更大的 orbitron20aa 行高 20 但缺 181 个汉字，
        // 会把物品/船名显示成方块，故中文一律走 Victor14。
        // 纯英文必须换字体：victor10/victor14/victor16 是「小型大写」字库，小写字形
        // 就是大写形状（逐字形位图比对：victor10 26/26 相同、victor14 10/26 相同），
        // 于是 psm_addShipXP 会显示成 PSM_ADDSHIPXP。setButtonFontDefault() =
        // orbitron12condensed.fnt（lineHeight 16、6506 字形，26 个小写字母都是独立字形），
        // 且不在 addButton 对 victor10/victor14 的特判分支里，是唯一可用的真小写按钮字体。
        if (CatalogEntry.hasCjk(text)) {
            tm.setButtonFontVictor14();
        } else {
            tm.setButtonFontDefault();
        }
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
                    // addPara 内部会走 String.format，正文里的 '%' 会被当成格式符，
                    // 触发 UnknownFormatConversionException（实测闪退：Conversion = ' '，
                    // 来自 desc【补到约 50% 载货量】）。这里统一转义为 %%。
                    tooltip.addPara(escapePercent(body), 6f, Misc.getTextColor());
                }
            }, TooltipMakerAPI.TooltipLocation.BELOW);
        }
        interactive.add(b);
        return b;
    }

    /**
     * addPara 的 pad 到「文字 ink 垂直中心」的距离（逻辑像素）。
     *
     * <p>实测标定：搜索行里 tooltip 放在 y=0、{@code addPara("搜索", 8f, ...)}，
     * 截图 ink 落在图像 184..207（中心 195.5）；同一行的文本框填充带为 141..175，
     * 而该文本框的逻辑 y 正是 0（图像 141 ↔ 逻辑 0，比例 1.6）⇒ ink 中心在逻辑 34.1，
     * 即 {@code 8 + 26.1}。所以本常数 = 26f（victor16）。
     */
    private static final float LABEL_INK_CENTER = 26f;

    /**
     * 一段纯文字标签（真正的 {@link LabelAPI}，纵向与同排控件居中）。
     *
     * <p>为什么需要这个助手：{@code addPara} 生成的标签先天比 {@code y + pad} 低
     * 一个行高左右（见 {@link #LABEL_INK_CENTER} 的标定），而按钮与文本框没有这个偏移，
     * 于是同一行里「搜索」二字会明显掉到搜索框下面。反汇编
     * {@code StandardTooltipV2Expandable.addPara(String,Color,float)} 可见首个元素就是
     * {@code panel.add(label); inTL(0, pad)}，偏移不来自这里、也无法从 API 侧关掉，
     * 只能在调用侧用 pad 抵消。
     *
     * <p>{@code h} 传<b>同排可见控件的高度</b>（搜索框 22、按钮 24…），标签的 ink 中心
     * 就被摆到 {@code y + h / 2}。刻意<b>不</b>改成「透明按钮」画文字（试过）：按钮自带
     * 描边，肉眼一看就是个按钮，却又不该点，比偏移更糟。
     */
    private LabelAPI paraLabel(CustomPanelAPI host, float x, float y, float w, float h,
                               String text, Color color) {
        float cw = Math.max(1f, w);
        float ch = Math.max(1f, h);
        TooltipMakerAPI tm = host.createUIElement(cw, ch, false);
        host.addUIElement(tm).inTL(x, y);
        tm.setParaFont(FONT_PARA);
        // ink 中心 = pad + LABEL_INK_CENTER，令其等于 h/2 即得此行要求的 pad。
        LabelAPI lab = tm.addPara(escapePercent(text), h / 2f - LABEL_INK_CENTER, color);
        try {
            lab.setAlignment(Alignment.TL);
        } catch (Throwable ignored) {
        }
        return lab;
    }

    /**
     * 建一个原生文本框。
     *
     * <p>按 RefitFilters 的 UIExtensions.kt:343-349 的写法：
     * 用 4 参重载 {@code addTextField(w, h, font, pad)} 并<b>直接 addComponent 到宿主面板</b>，
     * 而不是塞进自己新建的 TooltipMakerAPI。这样渲染与输入都走游戏自己的实现，
     * ChineseInputFix 的 Win32 IME 也能正常投递中文。
     */
    private TextFieldAPI textField(CustomPanelAPI host, float x, float y, float w, float h,
                                   String initial, String key) {
        float cw = Math.max(1f, w);
        float ch = Math.max(1f, h);
        TextFieldAPI f;
        try {
            TooltipMakerAPI tm = host.createUIElement(cw, ch, false);
            host.addUIElement(tm).inTL(x, y);
            f = tm.addTextField(cw, ch, FONT_PARA, 0f);
        } catch (Throwable t) {
            // 退路：3 参重载
            TooltipMakerAPI tm = host.createUIElement(cw, ch, false);
            host.addUIElement(tm).inTL(x, y);
            f = tm.addTextField(cw, ch);
        }
        if (initial != null && !initial.isEmpty()) {
            f.setText(initial);
        }
        try {
            f.setMaxChars(200);
        } catch (Throwable ignored) {
        }
        interactive.add(f);
        fields.put(key, f);
        lastFieldText.put(key, initial == null ? "" : initial);
        return f;
    }

    // ================= 输入 =================

    /**
     * 输入处理。
     *
     * <p>刻意<b>不</b>使用原生 TextFieldAPI 的焦点机制：原版 TextField 一旦 grabFocus()
     * 就会吞掉所有按键（包括 ESC），既无法关闭面板，也会让热键失效
     * （RefitFilters 的 SearchBarFilterPanel.kt:62-65 对此有明确注释）。
     * 这里改为自行记录【当前聚焦字段】，把按键转发给它，ESC 始终优先处理。
     */
    @Override
    public void processInput(List<InputEventAPI> events) {
        if (events == null || parent == null) {
            return;
        }
        try {
            for (InputEventAPI e : events) {
                if (e == null || e.isConsumed()) {
                    continue;
                }
                // 1) ESC 永远优先
                if (e.isKeyDownEvent() && e.getEventValue() == Keyboard.KEY_ESCAPE) {
                    e.consume();
                    onEscape();
                    return;
                }
                // 2) 鼠标按下：落在输入框上则聚焦；落在按钮上则先提交并取消聚焦。
                //    注意：落在【我们控件之外】时<b>不 consume</b> ——
                //    对话框自带的「关闭 [G]」按钮不在我们的控件列表里，
                //    之前一律 consume 导致它永远收不到点击（实测按钮无效）。
                if (e.isMouseDownEvent() || e.isLMBDownEvent()) {
                    String hit = fieldAt(e);
                    if (hit != null) {
                        setFocus(hit);
                        e.consume();
                    } else {
                        if (focusedField != null) {
                            commitFocused();
                            setFocus(null);
                        }
                        // 不 consume：交给对话框自己的按钮处理
                    }
                    continue;
                }
                // 2.5) G 键 = 关闭（对话框自带按钮上写的快捷键）。
                //      对话框自己那条 G 链路依赖 fader 淡入完成（已反汇编确认会卡住），
                //      这里由我们自己接管：没有输入框聚焦、也没在选择器里时按 G 即关闭。
                if (e.isKeyDownEvent() && e.getEventValue() == Keyboard.KEY_G
                        && focusedField == null && !pickerOpen) {
                    e.consume();
                    info("G 键关闭");
                    close();
                    return;
                }
                // 3) 键盘事件交给原生控件与对话框。
                //    不 consume：否则会打断原生输入、IME 组合。
                //    注意：聚焦字段已 grabFocus，逐字符输入由原生 TextField 与
                //    ChineseInputFix 的 IME 直接写进控件，本插件<b>不</b>再转发字符
                //    （转发会与原生输入重复）。但面板状态不会自己更新，所以回车必须
                //    在这里做一次收尾：把控件里的文字同步进面板，否则搜索条件永远是空的。
                if (e.isKeyboardEvent()) {
                    if (focusedField != null && (e.isKeyDownEvent() || e.isRepeat())) {
                        int v = e.getEventValue();
                        if (v == Keyboard.KEY_RETURN || v == Keyboard.KEY_NUMPADENTER) {
                            commitFocused();
                        }
                    }
                    continue;
                }
                // 4) 滚轮：选择器打开时翻页（一格一页）；鼠标落在日志区上时也翻页；
                //    其余情况吞掉，避免滚到战役/战斗 UI。
                if (e.isMouseScrollEvent()) {
                    if (pickerOpen) {
                        pickerScroll(e.getEventValue());
                    } else {
                        try {
                            if (logAreaPos != null && logAreaPos.containsEvent(e)) {
                                logScroll(e.getEventValue());
                            }
                        } catch (Throwable ignored) {
                        }
                    }
                    e.consume();
                }
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * 滚轮翻页。
     *
     * <p>早先版本把滚轮做成像素级滚动（{@code setYOffset}），但
     * {@code InputEventAPI.getEventValue()} 的量纲在各平台/驱动下不一致，
     * 归一化成 ±1 再乘步长后实测"滚一格页面直接空白"——用户连报两轮"非常非常快"。
     * 既然已改回分页，滚轮就直接等价于【翻一页】，一格一页，速度天然可控。
     * 上下界由 {@link #buildPicker} 钳位（pickerPage 越界会被夹回来）。
     */
    private void pickerScroll(int wheelDelta) {
        if (wheelDelta == 0) {
            return;
        }
        // 上滚（正值）= 往前翻
        pickerPage += wheelDelta > 0 ? -1 : 1;
        if (pickerPage < 0) {
            pickerPage = 0;
        }
        needsRebuild = true;
    }

    /**
     * 日志区滚轮翻页。
     *
     * <p>与选择器同一套语义：上滚往前（更新的输出）、下滚往后（更早的输出），一格一页。
     * 上下界由 {@link #buildLogArea} 钳位。
     */
    private void logScroll(int wheelDelta) {
        if (wheelDelta == 0) {
            return;
        }
        logPage += wheelDelta > 0 ? -1 : 1;
        if (logPage < 0) {
            logPage = 0;
        }
        needsRebuild = true;
    }

    /** 找出事件落点所在的输入框 key。 */
    private String fieldAt(InputEventAPI e) {
        for (Map.Entry<String, TextFieldAPI> en : fields.entrySet()) {
            TextFieldAPI f = en.getValue();
            if (f == null) {
                continue;
            }
            try {
                PositionAPI p = f.getPosition();
                if (p != null && p.containsEvent(e)) {
                    return en.getKey();
                }
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private void setFocus(String key) {
        if (focusedField != null && !focusedField.equals(key)) {
            commitFocused();
        }
        focusedField = key;
        try {
            if (key != null) {
                TextFieldAPI f = fields.get(key);
                if (f != null) {
                    // 让原生控件真正获得焦点：ChineseInputFix 通过 Win32 IME 组合 +
                    // 模拟 Ctrl+V 投递到【有焦点的原生控件】，不 grabFocus 则中文无法输入。
                    try {
                        f.grabFocus(true);
                    } catch (Throwable ignored) {
                    }
                    f.showCursor();
                }
            }
        } catch (Throwable ignored) {
        }
    }

    /** 把聚焦字段的内容写回面板状态。 */
    private void commitFocused() {
        if (focusedField == null) {
            return;
        }
        TextFieldAPI f = fields.get(focusedField);
        if (f == null) {
            return;
        }
        String now;
        try {
            now = f.getText();
        } catch (Throwable t) {
            return;
        }
        lastFieldText.put(focusedField, now);
        applyFieldValue(focusedField, now);
    }

    /** 把某个字段的新值同步到面板状态。 */
    private void applyFieldValue(String key, String value) {
        if ("search".equals(key)) {
            query = value == null ? "" : value;
            page = 0;
            needsRebuild = true;
        } else if ("picker".equals(key)) {
            pickerQuery = value == null ? "" : value;
            pickerPage = 0;
            needsRebuild = true;
        } else {
            int bar = key.indexOf('|');
            if (bar > 0) {
                draft.put(key, value == null ? "" : value);
                updatePreview(key.substring(0, bar));
            }
        }
    }

    /**
     * 把按键转发给当前聚焦的输入框。
     *
     * <p><b>当前实现下本方法不再被 processInput 调用</b>：聚焦字段已 grabFocus，
     * 字符输入由原生 TextField 与 IME 直接写入控件，再转发一次会导致字符重复。
     * 回车等【收尾动作】改由 processInput 的键盘分支调用 {@link #commitFocused()}。
     * 这里保留整段逻辑作为「自绘输入」方案的退路（若将来放弃 grabFocus）。
     *
     * @return 是否已处理该事件
     */
    private boolean forwardKey(InputEventAPI e) {
        TextFieldAPI f = fields.get(focusedField);
        if (f == null) {
            return false;
        }
        if (!e.isKeyDownEvent() && !e.isRepeat()) {
            return false;
        }
        int v = e.getEventValue();
        try {
            if (v == Keyboard.KEY_RETURN || v == Keyboard.KEY_NUMPADENTER) {
                commitFocused();
                if (!"search".equals(focusedField) && !"picker".equals(focusedField)) {
                    int bar = focusedField.indexOf('|');
                    if (bar > 0) {
                        runCommand(focusedField.substring(0, bar));
                    }
                }
                setFocus(null);
                return true;
            }
            if (v == Keyboard.KEY_BACK) {
                String cur = f.getText();
                if (cur != null && !cur.isEmpty()) {
                    f.setText(cur.substring(0, cur.length() - 1));
                }
                applyFieldValue(focusedField, f.getText());
                return true;
            }
            if (v == Keyboard.KEY_DELETE) {
                f.deleteAll();
                applyFieldValue(focusedField, "");
                return true;
            }
            if (v == Keyboard.KEY_V && e.isCtrlDown()) {
                pasteFromClipboard(f);
                return true;
            }
            if (e.isCtrlDown() || e.isAltDown()) {
                return false;
            }
            char c = e.getEventChar();
            if (c == 0 || c == '\u0000') {
                return false;
            }
            boolean ok = false;
            try {
                // 不再用 isValidChar 做白名单：它会挡掉中文字符（实测搜索栏无法输入中文），
                // 而游戏字体已含 CJK 字形，中文完全可以显示。
                // 这里只排除控制字符，其余交给 appendCharIfPossible。
                if (c >= 0x20 && c != 0x7F) {
                    ok = f.appendCharIfPossible(c);
                    if (!ok) {
                        // 某些版本对非 ASCII 返回 false，退化为直接 setText 拼接
                        String cur = f.getText();
                        f.setText((cur == null ? "" : cur) + c);
                        ok = true;
                    }
                }
            } catch (Throwable ignored) {
                ok = false;
            }
            if (ok) {
                applyFieldValue(focusedField, f.getText());
            }
            return ok;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private void pasteFromClipboard(TextFieldAPI f) {
        try {
            Object clip = java.awt.Toolkit.getDefaultToolkit().getSystemClipboard()
                    .getData(java.awt.datatransfer.DataFlavor.stringFlavor);
            if (clip == null) {
                return;
            }
            String s = String.valueOf(clip);
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                if (c == '\n' || c == '\r') {
                    continue;
                }
                try {
                    f.appendCharIfPossible(c);
                } catch (Throwable ignored) {
                }
            }
            applyFieldValue(focusedField, f.getText());
        } catch (Throwable ignored) {
        }
    }

    private boolean insideAnyElement(InputEventAPI e) {
        for (UIComponentAPI c : interactive) {
            try {
                PositionAPI p = c.getPosition();
                if (p != null && p.containsEvent(e)) {
                    return true;
                }
            } catch (Throwable ignored) {
            }
        }
        return false;
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
            if (mountedInDialog) {
                // 对话框路线：生命周期由 FrontendDialogPlugin 管理
                pollFields();
                if (needsRebuild) {
                    needsRebuild = false;
                    rebuild();
                }
                scrollLogToBottom();
                return;
            }
            GameState st = Global.getCurrentState();
            if (st != GameState.CAMPAIGN && st != GameState.COMBAT) {
                close();
                return;
            }
            if (ConsoleOverlayPanel.getInstance() != null) {
                close();
                return;
            }
            // 屏幕尺寸变化（切换分辨率 / 窗口化）时重建
            float sw = Global.getSettings().getScreenWidth();
            float sh = Global.getSettings().getScreenHeight();
            if (sw != lastW || sh != lastH) {
                lastW = sw;
                lastH = sh;
                resize();
            }
            pollFields();
            if (needsRebuild) {
                needsRebuild = false;
                rebuild();
            }
            scrollLogToBottom();
        } catch (Throwable ignored) {
        }
    }

    /**
     * 把日志区滚到底部（显示最新输出）。
     *
     * <p>必须每帧调用而非只在重建时调用：内容高度要等游戏完成一次布局才有效，
     * 重建当帧拿到的往往是 0，滚到底会退化成停在顶部；逐帧调用可以自愈。
     * 这里不用 {@code setYOffset}（不钳位，会把内容推飞），只用带钳位的
     * {@code scrollToBottom()}；不支持该方法的滚动器就保持不动。
     */
    private void scrollLogToBottom() {
        if (logScroller == null || !logScrollToBottom || pickerOpen) {
            return;
        }
        try {
            Reflect.invoke(logScroller, "scrollToBottom");
        } catch (Throwable ignored) {
        }
    }

    /**
     * 轮询输入框变化。
     *
     * <p><b>为什么必须轮询</b>：输入框一旦 {@code grabFocus}，原生 TextField 会先把
     * 按键处理掉——回车等事件到不了 {@code processInput}（实测：框里已经输入
     * 「命令历史」并按回车，列表却完全没过滤）。所以不能只靠键盘事件，
     * 这里改为主动读控件文本，无论回车是否被吞掉搜索都会生效。
     *
     * <p>聚焦中的输入框做去抖（连续 {@link #POLL_STABLE_FRAMES} 帧不变才同步），
     * 其余输入框一旦变化立即同步。
     */
    private void pollFields() {
        if (focusedField != null) {
            pollFocusedField();
            return;
        }
        pendingFocusText = null;
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
            applyFieldValue(key, now);
        }
    }

    /**
     * 轮询【正在编辑】的输入框。
     *
     * <p>文本连续若干帧不变才同步：单帧内 IME 还在组合，过早同步会拿到半成品；
     * 且同步会置 {@code needsRebuild} 触发整面板重建（旧 TextFieldAPI 被销毁），
     * 每敲一字重建一次会打断中文输入。参数类输入框（含 {@code '|'}）只需
     * 去抖后同步预览，不重建。
     */
    private void pollFocusedField() {
        TextFieldAPI f = fields.get(focusedField);
        if (f == null) {
            pendingFocusText = null;
            return;
        }
        String now;
        try {
            now = f.getText();
        } catch (Throwable t) {
            return;
        }
        String before = lastFieldText.get(focusedField);
        if (now == null ? before == null : now.equals(before)) {
            pendingFocusText = null;
            pendingFocusFrames = 0;
            return;
        }
        if (now.equals(pendingFocusText)) {
            pendingFocusFrames++;
        } else {
            pendingFocusText = now;
            pendingFocusFrames = 1;
            return;
        }
        if (pendingFocusFrames < POLL_STABLE_FRAMES) {
            return;
        }
        pendingFocusFrames = 0;
        pendingFocusText = null;
        lastFieldText.put(focusedField, now);
        applyFieldValue(focusedField, now);
    }

    private void updatePreview(String command) {
        if (previewLabel == null) {
            return;
        }
        try {
            CatalogEntry e = catalog.byCommand(command);
            if (e != null) {
                previewLabel.setText(escapePercent(previewText(e)));
            }
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void renderBelow(float alphaMult) {
        if (parent == null) {
            return;
        }
        // 对话框路线下由游戏自己绘制背景，无需全屏遮罩
        if (mountedInDialog) {
            return;
        }
        if (!loggedRender) {
            loggedRender = true;
            info("renderBelow 首次被调用（bgPanel=" + (bgPanel == null ? "null" : "ok") + "）");
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
            // 内容区再叠一层，形成【面板内更暗】的层次
            if (bgPanel != null && bgPanel.getPosition() != null) {
                PositionAPI bp = bgPanel.getPosition();
                float bx = bp.getX();
                float by = bp.getY();
                GL11.glColor4f(0.02f, 0.03f, 0.05f, Math.min(1f, a + 0.08f) * alphaMult);
                GL11.glRectf(bx, by, bx + bp.getWidth(), by + bp.getHeight());
            }
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
                pickerSuggestions = null;
                pickerEnum = false;
                needsRebuild = true;
            } else if (id.startsWith("pickerpage|")) {
                pickerPage += "next".equals(id.substring(11)) ? 1 : -1;
                if (pickerPage < 0) {
                    pickerPage = 0;
                }
                needsRebuild = true;
            } else if (id.startsWith("logpage|")) {
                logPage += "next".equals(id.substring(8)) ? 1 : -1;
                if (logPage < 0) {
                    logPage = 0;
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
            } else if ("clear".equals(id)) {
                try {
                    ConsoleOverlayPanel.setOutput("");
                } catch (Throwable ignored) {
                }
                needsRebuild = true;
            } else if ("clearlog".equals(id)) {
                try {
                    ConsoleOverlayPanel.setOutput("");
                } catch (Throwable ignored) {
                }
                logPage = 0;
                logSeenLen = -1;
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
                v = spec.defaultValue == null || spec.defaultValue.isEmpty()
                        ? 0d : Double.parseDouble(spec.defaultValue);
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
        for (int i = 0; i < e.params.size(); i++) {
            ParamSpec p = e.params.get(i);
            if (p.key.equals(paramKey)) {
                pickerCommand = command;
                pickerParamKey = paramKey;
                // 枚举参数（devmode 的状态、god 的目标…）的可选值就写在参数定义里，
                // 之前没有用它，反倒因为「没有 source」回落成全部商品列表（实测报错就是这个）。
                boolean isEnum = ParamSpec.TYPE_ENUM.equals(p.type);
                List<IdOption> choices = isEnum ? enumChoices(p) : null;
                boolean derived = p.source == null || p.source.isEmpty();
                pickerEnum = choices != null;
                if (pickerEnum) {
                    pickerSource = "";
                    pickerSuggestions = choices;
                } else {
                    pickerSource = derived ? IdSource.COMMODITY_SPECIAL : p.source;
                    // 只有「参数没有精选来源」时才用命令自带的建议：
                    // 精选过 source 的命令，我们已经有带中文名与来源标签的全量列表，
                    // 比只给一串 ID（建议接口只返回 String）更好用。
                    pickerSuggestions = derived ? suggestionsFor(e, i) : null;
                }
                pickerOpen = true;
                pickerPage = 0;
                pickerQuery = "";
                needsRebuild = true;
                return;
            }
        }
    }

    /**
     * 取第 {@code index} 个参数上「命令自身给出的建议」。
     *
     * <p>Console Commands 的约定：{@code previous} 是已经输入的前序参数值（小写）。
     * 这里从面板草稿 / 已记住的参数里按顺序取前序值，供 parameter&gt;0 的建议使用
     * （范例是 AddSpecialSuggestionsListener：第二个参数的候选取决于第一个参数）。
     *
     * @return 候选列表；命令没实现建议接口时返回 null（调用方回落到全量列表）
     */
    private List<IdOption> suggestionsFor(CatalogEntry e, int index) {
        if (e == null || e.params == null || index < 0 || index >= e.params.size()) {
            return null;
        }
        List<String> previous = new ArrayList<String>();
        for (int i = 0; i < index; i++) {
            String v = valueOf(e, e.params.get(i));
            previous.add(v == null ? "" : v.trim().toLowerCase());
        }
        List<IdOption> got;
        try {
            got = SuggestionSource.forCommand(e.command, index, previous, context);
        } catch (Throwable t) {
            warn("读取命令自动补全失败: " + e.command + " -> " + t);
            return null;
        }
        return got == null || got.isEmpty() ? null : got;
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
        pickerSuggestions = null;
        pickerEnum = false;
        statusLine = "已选择: " + (id == null || id.isEmpty() ? "（留空）" : id);
        needsRebuild = true;
    }

    /**
     * 枚举参数的候选：优先用参数定义里的 options；没有 options 的 on/off 类参数给开/关两项。
     *
     * <p>空串选项（如 devmode 的「留空=切换」）会显示成「（留空）」，
     * 否则按钮上会是一段没有文字的空白。
     */
    private static List<IdOption> enumChoices(ParamSpec p) {
        List<IdOption> out = new ArrayList<IdOption>();
        List<String> opts = p == null ? null : p.options;
        if (opts != null && !opts.isEmpty()) {
            for (String o : opts) {
                String v = o == null ? "" : o;
                out.add(new IdOption(v, enumLabel(v), "", ""));
            }
            return out;
        }
        out.add(new IdOption("on", "开 (on)", "", ""));
        out.add(new IdOption("off", "关 (off)", "", ""));
        return out;
    }

    private static String enumLabel(String v) {
        return v == null || v.isEmpty() ? "（留空）" : v;
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

    /** 解析所有参数（ID 类做名称->ID 映射），生成要执行的命令行。 */
    private List<String> buildLines(CatalogEntry e) {
        List<String> lines = new ArrayList<String>();
        Map<String, String> vals = new LinkedHashMap<String, String>();
        for (ParamSpec p : e.params) {
            String v = valueOf(e, p);
            boolean hasSource = p.source != null && !p.source.isEmpty();
            if (ParamSpec.TYPE_ID.equals(p.type) && hasSource && v != null && !v.trim().isEmpty()) {
                List<IdOption> opts = idOptions(p.source);
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

        // 必填参数缺失时不执行，直接展开参数区让玩家填写
        List<String> missing = new ArrayList<String>();
        for (ParamSpec p : e.params) {
            if (!p.required) {
                continue;
            }
            String v = valueOf(e, p);
            if (v == null || v.trim().isEmpty()) {
                missing.add(p.labelOrKey());
            }
        }
        if (!missing.isEmpty()) {
            StringBuilder sb = new StringBuilder("请先设置：");
            for (int i = 0; i < missing.size(); i++) {
                if (i > 0) {
                    sb.append("、");
                }
                sb.append(missing.get(i));
            }
            statusLine = sb.toString();
            openEditor(command);
            return;
        }

        final List<String> lines = buildLines(e);
        if (lines.isEmpty()) {
            return;
        }

        // 危险操作二次确认（设置项 cf_confirmDestructive）
        if (e.confirm && FrontendSettings.confirmDestructive
                && Global.getCurrentState() == GameState.CAMPAIGN) {
            try {
                CampaignUIAPI ui = Global.getSector().getCampaignUI();
                if (ui != null) {
                    StringBuilder preview = new StringBuilder();
                    for (int i = 0; i < lines.size(); i++) {
                        if (i > 0) {
                            preview.append("  ;  ");
                        }
                        preview.append(lines.get(i));
                    }
                    final CatalogEntry entry = e;
                    boolean shown = ui.showConfirmDialog(
                            "确认执行【" + e.labelOrName() + "】？",
                            "该操作可能无法撤销。\n\n将要执行：\n" + preview,
                            "执行",
                            new Script() {
                                @Override
                                public void run() {
                                    persistAndRun(entry, lines);
                                }
                            },
                            null);
                    if (shown) {
                        return;
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        persistAndRun(e, lines);
    }

    /** 保存参数后逐条执行。 */
    private void persistAndRun(CatalogEntry e, List<String> lines) {
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
            if (Global.getCurrentState() == GameState.CAMPAIGN) {
                try {
                    Global.getSector().getCampaignUI().addMessage("前端面板: " + commandLine,
                            Misc.getHighlightColor());
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable t) {
            try {
                Console.showException("前端面板执行失败: " + commandLine, t);
            } catch (Throwable ignored) {
            }
        }
    }

    // ================= 工具 =================

    /**
     * 取输出的最后 maxLines 行（时间正序）。
     *
     * <p>日志区现在一页一屏、最新在最上方，所以这里只负责截断尾部（保留最近的内容）；
     * 倒序与分页都交给 {@link #buildLogArea}。
     */
    private static List<String> logLines(String text, int maxLines) {
        List<String> all = new ArrayList<String>();
        if (text != null && !text.isEmpty()) {
            String[] parts = text.split("\n", -1);
            for (String p : parts) {
                all.add(p);
            }
        }
        int max = Math.max(1, maxLines);
        if (all.size() > max) {
            return new ArrayList<String>(all.subList(all.size() - max, all.size()));
        }
        return all;
    }

    /**
     * 转义文本里的 '%'。
     *
     * <p>游戏 UI 的 {@code addPara(String, float, Color)} 内部使用
     * {@code String.format}，正文中未配对的 '%' 会抛
     * {@code UnknownFormatConversionException} 并导致游戏闪退
     * （实测：desc【补到约 50% 载货量】触发 Conversion = ' '）。
     * 所有来自数据文件 / 命令帮助 / ID 名称的动态文本都必须先过这里。
     */
    static String escapePercent(String s) {
        if (s == null || s.indexOf('%') < 0) {
            return s;
        }
        return s.replace("%", "%%");
    }

    private static String shorten(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    /** 诊断日志：排查【面板没出现】这类问题时看 starsector.log 里的 [ConsoleFrontend]。 */
    private static void info(String msg) {
        try {
            Global.getLogger(FrontendPanel.class).info("[ConsoleFrontend] " + msg);
        } catch (Throwable ignored) {
        }
    }

    private static void warn(String msg) {
        try {
            Global.getLogger(FrontendPanel.class).warn(msg);
        } catch (Throwable ignored) {
        }
    }

    /** 带栈的告警：排查反射被游戏安全策略拦截时使用。 */
    private static void warnStack(String msg, Throwable t) {
        try {
            Global.getLogger(FrontendPanel.class).warn(msg, t);
        } catch (Throwable ignored) {
            try {
                Global.getLogger(FrontendPanel.class).warn(msg);
            } catch (Throwable ignored2) {
            }
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
