package org.dsh.frontend;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignUIAPI;
import com.fs.starfarer.api.campaign.InteractionDialogAPI;
import com.fs.starfarer.api.campaign.InteractionDialogPlugin;
import com.fs.starfarer.api.campaign.OptionPanelAPI;
import com.fs.starfarer.api.campaign.rules.MemoryAPI;
import com.fs.starfarer.api.combat.EngagementResultAPI;

import java.util.HashMap;
import java.util.Map;

/**
 * 承载前端面板的交互对话框插件。
 *
 * <p>全部使用公共 API，不涉及任何反射：
 * {@code CampaignUIAPI.showInteractionDialog(plugin, token)} -> 本类的 init() ->
 * {@code dialog.showCustomDialog(w, h, delegate)} -> 得到 CustomPanelAPI。
 */
public class FrontendDialogPlugin implements InteractionDialogPlugin {

    private InteractionDialogAPI dialog;
    private FrontendPanel panel;

    /** 面板尺寸（对话框内部区域）。 */
    private static final float MARGIN_X = 60f;
    private static final float MARGIN_Y = 60f;

    @Override
    public void init(InteractionDialogAPI dialog) {
        this.dialog = dialog;
        try {
            // 隐藏对话框自带的文字区与选项区，把整块区域让给我们的面板
            dialog.hideTextPanel();
            dialog.setPromptText("");
            OptionPanelAPI opts = dialog.getOptionPanel();
            if (opts != null) {
                opts.clearOptions();
            }
        } catch (Throwable ignored) {
        }

        float w = Math.max(400f, Global.getSettings().getScreenWidth() - MARGIN_X * 2f);
        float h = Math.max(300f, Global.getSettings().getScreenHeight() - MARGIN_Y * 2f);

        panel = new FrontendPanel(FrontendPanel.detectContext());
        // 用 showCustomDialog 挂载。
        //
        // 曾尝试改用 VisualPanelAPI.showCustomPanel 以消除对话框自带的确认按钮，
        // 但它把面板放进对话框的【视觉子区域】，可用尺寸被限制，
        // 而本面板按全屏尺寸计算 → 整体错位、右侧被裁切（实测）。
        // 因此回到 showCustomDialog；自带按钮改由 customDialogConfirm() 实现为可用。
        try {
            dialog.showCustomDialog(w, h, new FrontendDialogDelegate(panel));
            panel.markMountedInDialog();
        } catch (Throwable t) {
            try {
                Global.getLogger(FrontendDialogPlugin.class).warn(
                        "[ConsoleFrontend] showCustomDialog 失败: " + t, t);
            } catch (Throwable ignored) {
            }
        }
    }

    @Override
    public void optionSelected(String optionText, Object optionData) {
    }

    @Override
    public void optionMousedOver(String optionText, Object optionData) {
    }

    @Override
    public void advance(float amount) {
        if (panel != null && panel.isClosed()) {
            close();
        }
    }

    private void close() {
        try {
            if (dialog != null) {
                dialog.dismiss();
            }
        } catch (Throwable ignored) {
        }
        dialog = null;
    }

    @Override
    public void backFromEngagement(EngagementResultAPI battleResult) {
    }

    @Override
    public Object getContext() {
        return null;
    }

    @Override
    public Map<String, MemoryAPI> getMemoryMap() {
        return new HashMap<String, MemoryAPI>();
    }

    /** 用公共 API 打开前端面板对话框。 */
    public static boolean openDialog() {
        try {
            CampaignUIAPI ui = Global.getSector().getCampaignUI();
            if (ui == null) {
                return false;
            }
            ui.showInteractionDialog(new FrontendDialogPlugin(), null);
            return true;
        } catch (Throwable t) {
            try {
                Global.getLogger(FrontendDialogPlugin.class).warn(
                        "[ConsoleFrontend] 打开对话框失败: " + t, t);
            } catch (Throwable ignored) {
            }
            return false;
        }
    }
}
