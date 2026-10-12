package org.dsh.frontend;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.listeners.CampaignInputListener;
import com.fs.starfarer.api.input.InputEventAPI;
import org.lazywizard.console.overlay.v2.panels.ConsoleOverlayPanel;

import java.util.List;

/** 战役地图上的热键监听：优先级略低于 Console Commands，真控制台打开时让位。 */
public class FrontendCampaignListener implements CampaignInputListener {

    @Override
    public int getListenerInputPriority() {
        return Integer.MAX_VALUE - 20;
    }

    @Override
    public void processCampaignInputPreCore(List<InputEventAPI> events) {
        try {
            if (Global.getSector().getCampaignUI().isShowingMenu()) {
                return;
            }
            // 真控制台优先：它开着时我们完全不响应
            if (ConsoleOverlayPanel.getInstance() != null) {
                return;
            }
            // 【覆盖层必须在这里接管输入】
            // 覆盖层是手工挂到 screenPanel 上的，而市场 / 殖民地界面把自己的面板压在
            // 我们之上；screenPanel 按「后加的先收」派发，鼠标事件会被界面先吃掉，
            // 我们的按钮永远点不动（实测：面板能显示、完全无法点击）。
            // processCampaignInputPreCore 比 screenPanel 的派发更早，在这里直接把事件
            // 喂进面板树即可绕过层级问题。派发放在热键判定之前，避免热键被重复触发。
            FrontendPanel open = FrontendPanel.getInstance();
            if (open != null && open.isOverlayMounted()) {
                open.dispatchInput(events);
            }
            if (FrontendSettings.openKeybind == null || !FrontendSettings.openKeybind.isPressed(events)) {
                return;
            }
            if (FrontendPanel.isOpen()) {
                FrontendPanel.closeIfOpen();
            } else {
                FrontendPanel.open(FrontendPanel.detectContext());
            }
            events.clear();
        } catch (Throwable t) {
            try {
                Global.getLogger(FrontendCampaignListener.class).warn("处理前端面板热键失败: " + t);
            } catch (Throwable ignored) {
            }
        }
    }

    @Override
    public void processCampaignInputPreFleetControl(List<InputEventAPI> events) {
    }

    @Override
    public void processCampaignInputPostCore(List<InputEventAPI> events) {
    }
}
