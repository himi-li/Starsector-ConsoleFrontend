package org.dsh.frontend;

import com.fs.starfarer.api.BaseModPlugin;
import com.fs.starfarer.api.Global;

/** Mod 入口：注册监听器、加载标签与参数、接管 LunaLib 设置。 */
public class ConsoleFrontendModPlugin extends BaseModPlugin {

    @Override
    public void onApplicationLoad() throws Exception {
        try {
            ParamStore.load();
        } catch (Throwable t) {
            log("加载参数失败: " + t);
        }
        try {
            FrontendLabels.reload();
        } catch (Throwable t) {
            log("加载标签失败: " + t);
        }
        try {
            if (Global.getSettings().getModManager().isModEnabled("lunalib")) {
                FrontendLuna.install();
            }
        } catch (Throwable t) {
            log("接入 LunaLib 设置失败（将使用内置默认值）: " + t);
        }
    }

    @Override
    public void onGameLoad(boolean newGame) {
        try {
            Global.getSector().getListenerManager().addListener(new FrontendCampaignListener(), true);
        } catch (Throwable t) {
            log("注册战役输入监听器失败: " + t);
        }
        try {
            ParamStore.load();
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void onDevModeF8Reload() {
        try {
            FrontendLabels.reload();
            IdSource.invalidate();
            ParamStore.load();
        } catch (Throwable ignored) {
        }
    }

    private static void log(String msg) {
        try {
            Global.getLogger(ConsoleFrontendModPlugin.class).warn(msg);
        } catch (Throwable ignored) {
        }
    }
}
