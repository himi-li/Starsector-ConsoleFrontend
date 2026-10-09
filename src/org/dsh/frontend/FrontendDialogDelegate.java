package org.dsh.frontend;

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

    @Override
    public void createCustomDialog(CustomPanelAPI host, CustomDialogCallback callback) {
        if (host == null) {
            return;
        }
        // 把宿主面板交给 FrontendPanel，由它在其上构建全部控件
        panel.attachToDialog(host);
    }

    /** 我们自带了【关闭】按钮，不需要对话框的取消按钮。 */
    @Override
    public boolean hasCancelButton() {
        return false;
    }

    /**
     * 不要对话框自带的确认按钮。
     *
     * <p>实测：返回 null 时游戏会渲染一个默认的【确认 [G]】按钮，但它点击后走的是
     * {@code customDialogConfirm()}，与我们的面板逻辑无关，表现为【按钮无效】。
     * 返回空串则不渲染该按钮 —— 关闭动作由面板自己的【关闭 (ESC)】按钮负责。
     */
    @Override
    public String getConfirmText() {
        return "";
    }

    @Override
    public String getCancelText() {
        return "";
    }

    @Override
    public void customDialogConfirm() {
        if (panel != null) {
            panel.close();
        }
    }

    @Override
    public void customDialogCancel() {
        if (panel != null) {
            panel.close();
        }
    }

    @Override
    public CustomUIPanelPlugin getCustomPanelPlugin() {
        return panel;
    }
}
