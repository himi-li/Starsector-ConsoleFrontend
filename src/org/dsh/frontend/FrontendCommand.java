package org.dsh.frontend;

import org.lazywizard.console.BaseCommand;
import org.lazywizard.console.Console;

/** 控制台命令：consolefrontend [open|close|reload|resetparams|resetfont] */
public class FrontendCommand implements BaseCommand {

    @Override
    public CommandResult runCommand(String args, CommandContext context) {
        String a = args == null ? "" : args.trim().toLowerCase();
        if (a.isEmpty() || "open".equals(a)) {
            if (FrontendPanel.isOpen()) {
                Console.showMessage("控制台前端面板已经打开了。");
                return CommandResult.SUCCESS;
            }
            FrontendPanel.open(context);
            Console.showMessage("已打开控制台前端面板。默认热键: " + FrontendSettings.hotkeyText());
            return CommandResult.SUCCESS;
        }
        if ("close".equals(a)) {
            if (!FrontendPanel.isOpen()) {
                Console.showMessage("控制台前端面板当前没有打开。");
                return CommandResult.SUCCESS;
            }
            FrontendPanel.closeIfOpen();
            return CommandResult.SUCCESS;
        }
        if ("reload".equals(a)) {
            FrontendLabels.reload();
            IdSource.invalidate();
            Console.showMessage("控制台前端：标签与 ID 列表已重新加载。");
            return CommandResult.SUCCESS;
        }
        if ("resetparams".equals(a)) {
            ParamStore.resetAll();
            Console.showMessage("控制台前端：所有命令参数已恢复默认。");
            return CommandResult.SUCCESS;
        }
        if ("resetfont".equals(a)) {
            FontStore.select("");
            Console.showMessage("控制台前端：界面字体已恢复为游戏自带。");
            return CommandResult.SUCCESS;
        }
        return CommandResult.BAD_SYNTAX;
    }
}
