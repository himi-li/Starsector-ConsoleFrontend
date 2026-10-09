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
