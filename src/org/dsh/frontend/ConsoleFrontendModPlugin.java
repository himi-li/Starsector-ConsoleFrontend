package org.dsh.frontend;

import com.fs.starfarer.api.BaseModPlugin;
import com.fs.starfarer.api.Global;

/**
 * Mod 入口。
 *
 * <p><b>重要约束</b>：{@code onApplicationLoad()} 是在游戏的
 * {@code ResourceLoaderState.init} 期间被调用的，此时游戏正在遍历各 mod 读取任务描述等资源。
 * 在这个阶段调用 {@code SettingsAPI.loadText(...)} 之类的资源 API 会干扰游戏自身的资源查找
 * （实测会导致其它 mod 的任务文件被误报为 "resource, not found" 而启动失败）。
 *
 * <p>因此本类刻意<b>不在 onApplicationLoad 里做任何文件或资源访问</b>：
 * 标签、参数、LunaLib 设置全部推迟到 {@code onGameLoad} 或首次使用时按需加载。
 */
public class ConsoleFrontendModPlugin extends BaseModPlugin {

    @Override
    public void onApplicationLoad() throws Exception {
        // 刻意留空：此处处于游戏资源加载阶段，不做任何文件 / 资源访问。
    }

    @Override
    public void onGameLoad(boolean newGame) {
        // 标签与参数按需加载（惰性），这里只是提前热身，失败也不影响游戏
        try {
            FrontendLabels.reload();
        } catch (Throwable t) {
            log("加载中文标签失败（将使用自动枚举模式）: " + t);
        }
        try {
            ParamStore.load();
        } catch (Throwable t) {
            log("加载参数失败（将使用默认值）: " + t);
        }
        // 接入 LunaLib 设置（仅当已启用 lunalib 时才触碰它的类）
        try {
            if (Global.getSettings().getModManager().isModEnabled("lunalib")) {
                FrontendLuna.install();
            }
        } catch (Throwable t) {
            log("接入 LunaLib 设置失败（将使用内置默认值）: " + t);
        }
        // 注册战役热键监听
        try {
            Global.getSector().getListenerManager().addListener(new FrontendCampaignListener(), true);
        } catch (Throwable t) {
            log("注册战役输入监听器失败: " + t);
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
