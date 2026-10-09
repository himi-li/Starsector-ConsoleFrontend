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
            panel.advance(amount);
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
            // 宿主面板自己负责绘制子控件
            host.render(1f);
            host.advance(0f);
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void processInputPreCoreControls(float amount, List<InputEventAPI> events) {
        if (!started) {
            return;
        }
        try {
            panel.processInput(events);
        } catch (Throwable ignored) {
        }
    }
}
