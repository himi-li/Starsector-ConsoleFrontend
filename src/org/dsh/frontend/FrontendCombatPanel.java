package org.dsh.frontend;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.ViewportAPI;
import com.fs.starfarer.api.input.InputEventAPI;
import com.fs.starfarer.api.ui.CustomPanelAPI;
import com.fs.starfarer.api.ui.PositionAPI;
import com.fs.starfarer.api.ui.TooltipMakerAPI;
import org.lwjgl.opengl.GL11;

import java.util.List;

/**
 * 战斗中的前端面板。
 *
 * <p>战斗中无法使用 {@code CampaignUIAPI.showInteractionDialog}（那是战役专用），
 * 因此改为把面板挂到一个 {@code BaseEveryFrameCombatPlugin} 上：
 * {@code CombatEngineAPI.addPlugin(plugin)} 是公共 API，插件通过
 * {@code renderInUICoords} 自绘、{@code processInputPreCoreControls} 收输入，
 * 全程不需要反射。
 */
public class FrontendCombatPanel extends com.fs.starfarer.api.combat.BaseEveryFrameCombatPlugin {

    private final FrontendPanel panel;
    private CustomPanelAPI host;
    private boolean started;

    public FrontendCombatPanel(FrontendPanel panel) {
        this.panel = panel;
    }

    @Override
    public void init(CombatEngineAPI engine) {
        try {
            float w = Global.getSettings().getScreenWidth();
            float h = Global.getSettings().getScreenHeight();
            // 用公共 API 建一个铺满屏幕的面板作为宿主
            host = Global.getSettings().createCustom(w, h, null);
            panel.attachToDialog(host);
            started = true;
            try {
                Global.getLogger(FrontendCombatPanel.class).info(
                        "[ConsoleFrontend] 战斗面板已挂载 " + w + "x" + h);
            } catch (Throwable ignored) {
            }
        } catch (Throwable t) {
            try {
                Global.getLogger(FrontendCombatPanel.class).warn(
                        "[ConsoleFrontend] 战斗面板挂载失败: " + t, t);
            } catch (Throwable ignored) {
            }
        }
    }

    @Override
    public void advance(float amount, List<InputEventAPI> events) {
        if (!started) {
            return;
        }
        try {
            // 关键修复（与输入同源）：host 不在游戏的 UI 树里，没人替它推进子控件，
            // 因此这里用【真实帧时间】手工推进整棵树。
            //
            // 为什么必须推进：控件的 Fader 默认处于「淡出」状态，靠 advance 才会
            // 走到「可见」；而 OOOo.processInput 的开头就是
            //   if (getFader().isFadedOut() || getOpacity() <= 0f) return;
            // 不推进的话事件会被这个判空直接挡掉 —— 表现就是「按钮明明画出来了，
            // 点它却毫无反应」。渲染回调里那句 advance(0f) 推进不了任何东西。
            //
            // 只调 host.advance 即可：树里 bgPanel 的 plugin 就是 FrontendPanel
            // 自己，遍历到它时 FrontendPanel.advance 会被调用，故不重复调 panel.advance。
            if (host != null) {
                host.advance(amount);
            } else {
                panel.advance(amount);
            }
            if (panel.isClosed()) {
                Global.getCombatEngine().removePlugin(this);
            }
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void renderInUICoords(ViewportAPI viewport) {
        if (!started || host == null) {
            return;
        }
        try {
            panel.renderBelow(1f);
            // 宿主面板自己负责绘制子控件（advance 已在 advance() 里用真实帧时间推进）
            host.render(1f);
        } catch (Throwable ignored) {
        }
    }

    /** 是否已经打印过「开始接收输入」的日志（只打一次，避免刷屏）。 */
    private boolean loggedInput;

    @Override
    public void processInputPreCoreControls(float amount, List<InputEventAPI> events) {
        if (!started || host == null || events == null || events.isEmpty()) {
            return;
        }
        if (!loggedInput) {
            loggedInput = true;
            try {
                Global.getLogger(FrontendCombatPanel.class).info(
                        "[ConsoleFrontend] 战斗面板开始接收输入，事件数=" + events.size());
            } catch (Throwable ignored) {
            }
        }
        // 关键修复：必须把输入【投递进面板树】。
        //
        // host 是 createCustom() 建出来的游离面板，从未挂进任何游戏 UI 树（战斗中
        // 既没有对话框、也没有 screenPanel 挂载），所以游戏永远不会自己遍历它 ——
        // 面板能画出来，只是因为 renderInUICoords 里手动调了 host.render()。
        // 同理 host.processInput() 也从来没人调，于是：
        //   bgPanel 的子控件（按钮）永远收不到鼠标事件，
        //   点击也就永远到不了 bgPanel 的 plugin（=FrontendPanel.buttonPressed）。
        // 之前这里只调 panel.processInput(events)，而它只处理输入框聚焦 / ESC / G /
        // 滚轮，从不派发按钮点击 —— 这正是「战斗中面板能开、能画，但所有按钮都点不动」。
        //
        // 手动投递的链路：host.processInput -> 子面板 bgPanel -> 各 UI 元素/按钮
        // -> bgPanel 的 plugin.processInput（FrontendPanel）。顺序与对话框路线一致：
        // 子控件先处理（按钮点击会 consume 事件），再由面板做聚焦 / 滚轮收尾。
        try {
            host.processInput(events);
        } catch (Throwable t) {
            // 兜底：至少保证面板自身的键盘 / 滚轮逻辑仍然可用
            try {
                panel.processInput(events);
            } catch (Throwable ignored) {
            }
        }
    }
}
