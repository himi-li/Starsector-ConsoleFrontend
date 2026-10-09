package org.dsh.frontend;

import org.lazywizard.console.BaseCommand.CommandContext;
import org.lazywizard.console.CommandStore;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 命令目录：精选中文条目 + 自动枚举的其余全部命令。
 * 自动枚举保证【装任何 Mod 的新命令都会自动出现在面板上】。
 */
public final class CommandCatalog {

    private final List<CatalogEntry> entries = new ArrayList<CatalogEntry>();
    private final Map<String, CatalogEntry> byCommand = new LinkedHashMap<String, CatalogEntry>();
    private final Set<String> categoriesUsed = new LinkedHashSet<String>();

    public List<CatalogEntry> entries() {
        return entries;
    }

    public CatalogEntry byCommand(String command) {
        if (command == null) {
            return null;
        }
        return byCommand.get(command.toLowerCase());
    }

    public Set<String> categoriesUsed() {
        return categoriesUsed;
    }

    public void build(CommandContext context) {
        entries.clear();
        byCommand.clear();
        categoriesUsed.clear();

        Set<String> applicable = new LinkedHashSet<String>();
        try {
            List<String> list = CommandStore.getApplicableCommands(context);
            if (list != null) {
                for (String s : list) {
                    applicable.add(s.toLowerCase());
                }
            }
        } catch (Throwable ignored) {
        }

        List<String> loaded = new ArrayList<String>();
        try {
            List<String> list = CommandStore.getLoadedCommands();
            if (list != null) {
                loaded.addAll(list);
            }
        } catch (Throwable ignored) {
        }

        for (String rawName : loaded) {
            if (rawName == null || rawName.trim().isEmpty()) {
                continue;
            }
            CatalogEntry e = new CatalogEntry();
            e.displayName = rawName;
            e.command = rawName.toLowerCase();
            e.applicable = applicable.contains(e.command);

            try {
                CommandStore.StoredCommand sc = CommandStore.retrieveCommand(e.command);
                if (sc != null) {
                    e.syntax = sc.getSyntax() == null ? "" : sc.getSyntax();
                    e.help = sc.getHelp() == null ? "" : sc.getHelp();
                    e.source = sc.getSource() == null ? "" : sc.getSource();
                    if (sc.getTags() != null) {
                        e.tags = new ArrayList<String>(sc.getTags());
                    }
                }
            } catch (Throwable ignored) {
            }

            FrontendLabels.Curated cur = FrontendLabels.get(e.command);
            if (cur != null) {
                e.curated = true;
                e.label = cur.label;
                e.description = cur.description;
                e.category = cur.category;
                e.confirm = cur.confirm;
                e.params = cur.params;
                e.run = cur.run;
            } else {
                e.label = rawName;
                e.description = firstLine(e.help);
                e.category = categoryFromTags(e.tags);
                deriveParams(e);
            }

            if (e.category == null || e.category.isEmpty()) {
                e.category = "all";
            }
            entries.add(e);
            byCommand.put(e.command, e);
            categoriesUsed.add(e.category);
        }

        final List<String> ord = FrontendLabels.order();
        Collections.sort(entries, new Comparator<CatalogEntry>() {
            @Override
            public int compare(CatalogEntry a, CatalogEntry b) {
                int ia = ord.indexOf(a.command);
                int ib = ord.indexOf(b.command);
                if (ia < 0) {
                    ia = Integer.MAX_VALUE;
                }
                if (ib < 0) {
                    ib = Integer.MAX_VALUE;
                }
                if (ia != ib) {
                    return Integer.compare(ia, ib);
                }
                int ca = categoryOrder(a.category);
                int cb = categoryOrder(b.category);
                if (ca != cb) {
                    return Integer.compare(ca, cb);
                }
                return a.labelOrName().compareToIgnoreCase(b.labelOrName());
            }

            private int categoryOrder(String id) {
                FrontendLabels.Category c = FrontendLabels.categories().get(id);
                return c == null ? 500 : c.order;
            }
        });
    }

    public List<CatalogEntry> filter(String category, String query, boolean showUnavailable) {
        List<CatalogEntry> res = new ArrayList<CatalogEntry>();
        String q = query == null ? "" : query.trim().toLowerCase();
        for (CatalogEntry e : entries) {
            if (!showUnavailable && !e.applicable) {
                continue;
            }
            if (category != null && !"all".equals(category) && !category.equals(e.category)) {
                continue;
            }
            if (!q.isEmpty() && !e.searchBlob().contains(q)) {
                continue;
            }
            res.add(e);
        }
        return res;
    }

    private static String firstLine(String help) {
        if (help == null) {
            return "";
        }
        int nl = help.indexOf('\n');
        String s = nl < 0 ? help : help.substring(0, nl);
        if (s.length() > 120) {
            s = s.substring(0, 117) + "...";
        }
        return s.trim();
    }

    private static String categoryFromTags(List<String> tags) {
        if (tags == null || tags.isEmpty()) {
            return "all";
        }
        for (String t : tags) {
            if (t == null) {
                continue;
            }
            String s = t.toLowerCase();
            if ("console".equals(s)) {
                return "system";
            }
            if ("combat".equals(s)) {
                return "combat";
            }
            if ("market".equals(s)) {
                return "market";
            }
            if ("campaign".equals(s)) {
                return "map";
            }
        }
        return "all";
    }

    /**
     * 未精选命令的参数兜底：从 commands.csv 的 syntax 粗略解析出参数槽。
     * 例 "additem <itemID> [optionalAmount]" -> [itemID: text, optionalAmount: int?]
     */
    private static void deriveParams(CatalogEntry e) {
        String syntax = e.syntax;
        if (syntax == null || syntax.trim().isEmpty()) {
            return;
        }
        int sp = syntax.indexOf(' ');
        if (sp < 0) {
            return;
        }
        String rest = syntax.substring(sp + 1).trim();
        if (rest.isEmpty()) {
            return;
        }
        List<ParamSpec> params = new ArrayList<ParamSpec>();
        for (String token : rest.split("\\s+")) {
            String t = token.trim();
            if (t.isEmpty()) {
                continue;
            }
            boolean optional = t.startsWith("[");
            String inner = t.replace("[", "").replace("]", "").replace("<", "").replace(">", "").trim();
            if (inner.isEmpty() || inner.equalsIgnoreCase("no") || inner.equalsIgnoreCase("arguments")) {
                continue;
            }
            ParamSpec p = new ParamSpec();
            p.key = inner;
            p.label = inner;
            p.type = guessType(inner);
            p.hint = optional ? "可选" : "";
            params.add(p);
        }
        if (!params.isEmpty()) {
            e.params = params;
        }
    }

    private static String guessType(String name) {
        String n = name.toLowerCase();
        if (n.contains("amount") || n.contains("count") || n.contains("level") || n.contains("number")
                || n.contains("size") || n.contains("points") || n.contains("xp") || n.contains("stability")) {
            return ParamSpec.TYPE_INT;
        }
        if (n.contains("id") || n.contains("name") || n.contains("faction") || n.contains("market")
                || n.contains("system") || n.contains("hull") || n.contains("weapon") || n.contains("variant")) {
            return ParamSpec.TYPE_ID;
        }
        if (n.contains("onoroff") || n.startsWith("optionalon")) {
            return ParamSpec.TYPE_ENUM;
        }
        return ParamSpec.TYPE_TEXT;
    }
}
