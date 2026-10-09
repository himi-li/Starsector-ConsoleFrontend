package org.dsh.frontend;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.BaseCustomDialogDelegate;
import com.fs.starfarer.api.campaign.CustomDialogDelegate.CustomDialogCallback;
import com.fs.starfarer.api.campaign.CustomUIPanelPlugin;
import com.fs.starfarer.api.ui.CustomPanelAPI;

/**
 * 自定义对话框委托：为前端面板提供宿主 CustomPanelAPI。
 *
 * <p><b>为什么走对话框而不是反射挂 screenPanel</b>：实测游戏对 mod 脚本施加了
 * SecurityManager 限制，自建反射（含 MethodHandles）会被拒绝，报
 * {@code SecurityException: File access and reflection are not allowed to scripts}，
 * 连 {@code Class.getMethods()} 都返回空。而
 * {@code InteractionDialogAPI.showCustomDialog(w, h, delegate)} 是<b>纯公共 API</b>：
 * 它会把一个 {@link CustomPanelAPI} 交给我们，之后建按钮、文本框、滚动区全部走
 * 公开接口，完全不需要反射。Station Augments 等已装 Mod 正是这样做的。
 */
public class FrontendDialogDelegate extends BaseCustomDialogDelegate {

    private final FrontendPanel panel;

    public FrontendDialogDelegate(FrontendPanel panel) {
        this.panel = panel;
    }

    private void log(String msg) {
        try {
            Global.getLogger(FrontendDialogDelegate.class).info("[ConsoleFrontend] " + msg);
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void createCustomDialog(CustomPanelAPI host, CustomDialogCallback callback) {
        if (host == null) {
            return;
        }
        // 把宿主面板交给 FrontendPanel，由它在其上构建全部控件。
        panel.attachToDialog(host);
        // 注入回调：FrontendPanel.close() 用它走确定性关闭路径
        // （对话框自带的确认按钮只触发 fader 淡入，淡入完成前不会真正关闭）。
        panel.setDialogCallback(callback);
    }

    /** 我们自带了【关闭】按钮，不需要对话框的取消按钮。 */
    @Override
    public boolean hasCancelButton() {
        return false;
    }

    /**
     * 对话框自带的确认按钮。
     *
     * <p>实测：这个按钮无法通过返回空串去掉（返回 "" 只是文字为空，按钮仍在），
     * 所以改为<b>让它可用</b>：返回中文标签，点击后由 {@link #customDialogConfirm()}
     * 关闭面板。这样按 G 或点它都能关闭，不再是"无效按钮"。
     */
    @Override
    public String getConfirmText() {
        // 游戏会自动在按钮文字后追加 [G] 快捷键提示，
        // 这里只写动作名，否则会显示成「关闭 (G) [G]」（实测）。
        return "关闭";
    }

    @Override
    public String getCancelText() {
        return null;
    }

    @Override
    public void customDialogConfirm() {
        log("对话框确认回调 customDialogConfirm");
        if (panel != null) {
            panel.close();
        }
    }

    @Override
    public void customDialogCancel() {
        log("对话框取消回调 customDialogCancel");
        if (panel != null) {
            panel.close();
        }
    }

    @Override
    public CustomUIPanelPlugin getCustomPanelPlugin() {
        return panel;
    }
}
