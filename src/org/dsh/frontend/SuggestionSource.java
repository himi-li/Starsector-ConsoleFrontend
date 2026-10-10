package org.dsh.frontend;

import org.lazywizard.console.BaseCommand;
import org.lazywizard.console.BaseCommandWithSuggestion;
import org.lazywizard.console.CommandListener;
import org.lazywizard.console.CommandListenerWithSuggestion;
import org.lazywizard.console.CommandStore;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 从命令自身的自动补全接口取候选 ID。
 *
 * <p>Console Commands 有两套建议 API（源码 org/lazywizard/console/ 下）：
 * <ul>
 *   <li>{@link BaseCommandWithSuggestion#getSuggestions(int, List, BaseCommand.CommandContext)}
 *       —— 命令类自己实现，parameter 从 0 起，previous 是已输入的前序参数（小写）；</li>
 *   <li>{@link CommandListenerWithSuggestion#getSuggestions(String, int, List, BaseCommand.CommandContext)}
 *       —— 监听器按命令名分派，上游自己的自动补全弹窗就是把两者的结果合并。</li>
 * </ul>
 *
 * <p>上游的用法见 ConsoleOverlayPanel.kt:627-644：先 newInstance() 命令类判断实例类型，
 * 再遍历 {@link CommandStore#getListeners()}。这里照抄同一套语义，第三方 mod 的命令
 * 可能抛任何异常，因此整体 try/catch，最坏情况返回空表、由调用方回落到全量列表。
 */
public final class SuggestionSource {

    private SuggestionSource() {
    }

    /**
     * 取一条命令在某个参数位置上的建议值。
     *
     * @param command   命令名（CSV 原始大小写均可，底层 toLowerCase 查表）
     * @param parameter 参数下标，从 0 起
     * @param previous  已输入的前序参数值（小写，与上游约定一致）
     * @param context   当前 {@link BaseCommand.CommandContext}
     * @return 候选 ID 列表；没有建议时为空表（绝不是 null）
     */
    public static List<IdOption> forCommand(String command, int parameter,
                                            List<String> previous, BaseCommand.CommandContext context) {
        List<IdOption> out = new ArrayList<IdOption>();
        if (command == null || command.trim().isEmpty() || parameter < 0) {
            return out;
        }
        List<String> prev = previous == null ? new ArrayList<String>() : previous;
        Set<String> seen = new LinkedHashSet<String>();
        try {
            CommandStore.StoredCommand sc = CommandStore.retrieveCommand(command);
            if (sc == null) {
                return out;
            }
            // 命令类自身：上游是 commandClass.newInstance()，这里同样兜住异常
            try {
                BaseCommand inst = sc.getCommandClass().getDeclaredConstructor().newInstance();
                if (inst instanceof BaseCommandWithSuggestion) {
                    collect(out, seen, ((BaseCommandWithSuggestion) inst).getSuggestions(parameter, prev, context));
                }
            } catch (Throwable ignored) {
            }
            // 监听器：按命令名分派（AddSpecialSuggestionsListener 这类跨参数建议）
            try {
                for (CommandListener l : CommandStore.getListeners()) {
                    if (l instanceof CommandListenerWithSuggestion) {
                        collect(out, seen, ((CommandListenerWithSuggestion) l)
                                .getSuggestions(sc.getName(), parameter, prev, context));
                    }
                }
            } catch (Throwable ignored) {
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    private static void collect(List<IdOption> out, Set<String> seen, List<String> values) {
        if (values == null || values.isEmpty()) {
            return;
        }
        for (String v : values) {
            if (v == null || v.trim().isEmpty()) {
                continue;
            }
            String id = v.trim();
            if (!seen.add(id.toLowerCase())) {
                continue;
            }
            out.add(new IdOption(id, id, "", ""));
        }
    }
}
