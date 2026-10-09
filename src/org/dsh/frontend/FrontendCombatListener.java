package org.dsh.frontend;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.BaseEveryFrameCombatPlugin;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.input.InputEventAPI;
import org.lazywizard.console.BaseCommand.CommandContext;
import org.lazywizard.console.overlay.v2.panels.ConsoleOverlayPanel;

import java.util.List;

/** 战斗中的热键监听（经 data/config/settings.json 的 plugins 注册）。 */
public class FrontendCombatListener extends BaseEveryFrameCombatPlugin {

    private CommandContext context = CommandContext.COMBAT_CAMPAIGN;

    @Override
    public void init(CombatEngineAPI engine) {
        try {
            if (engine.isSimulation()) {
                context = CommandContext.COMBAT_SIMULATION;
            } else if (engine.isInCampaign()) {
                context = CommandContext.COMBAT_CAMPAIGN;
            } else if (engine.getMissionId() != null) {
                context = CommandContext.COMBAT_MISSION;
            } else {
                context = CommandContext.MAIN_MENU;
            }
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void processInputPreCoreControls(float amount, List<InputEventAPI> events) {
        try {
            if (Global.getCombatEngine() == null || Global.getCombatEngine().getPlayerShip() == null) {
                return;
            }
            if (ConsoleOverlayPanel.getInstance() != null) {
                return;
            }
            if (FrontendSettings.openKeybind == null || !FrontendSettings.openKeybind.isPressed(events)) {
                return;
            }
            if (FrontendPanel.isOpen()) {
                FrontendPanel.closeIfOpen();
            } else {
                FrontendPanel.open(context);
            }
            events.clear();
        } catch (Throwable t) {
            try {
                Global.getLogger(FrontendCombatListener.class).warn("处理战斗前端面板热键失败: " + t);
            } catch (Throwable ignored) {
            }
        }
    }

    @Override
    public void advance(float amount, List<InputEventAPI> events) {
        try {
            CombatEngineAPI engine = Global.getCombatEngine();
            if (engine != null && engine.isCombatOver() && FrontendPanel.isOpen()) {
                FrontendPanel.closeIfOpen();
            }
        } catch (Throwable ignored) {
        }
    }
}
