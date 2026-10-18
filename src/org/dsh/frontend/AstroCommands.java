package org.dsh.frontend;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CustomEntitySpecAPI;
import com.fs.starfarer.api.campaign.LocationAPI;
import com.fs.starfarer.api.campaign.PlanetSpecAPI;
import com.fs.starfarer.api.campaign.StarSystemAPI;
import org.lazywizard.console.BaseCommand;
import org.lazywizard.console.Console;

/**
 * 「加 / 删天体、星云、空间站」面板命令族。
 *
 * <p>每条命令都不直接调用游戏 API 建对象，而是把参数拼成一段 Java 源码后交给
 * Console Commands 的 runcode 执行；这样玩家在日志区能看到完整的实际代码，
 * 也方便直接复制到控制台里改成别的用法。</p>
 */
public final class AstroCommands {

    private AstroCommands() {
    }

    /** 公共基类：负责上下文检查、参数切分与 runcode 投递。 */
    public abstract static class Astro implements BaseCommand {

        /** 见 /用法。 */
        protected abstract String usage();

        /** 子类实现：把参数变成 Java 源码；返回 null 表示要展示用法。 */
        protected abstract String buildCode(String[] args);

        /** 是否必须在星系内执行（删除类命令在超空间也能用）。 */
        protected boolean requiresStarSystem() {
            return true;
        }

        @Override
        public CommandResult runCommand(String args, CommandContext context) {
            if (context == null || !context.isInCampaign()) {
                Console.showMessage("错误：该命令只能在战役（非战斗）中使用。");
                return CommandResult.WRONG_CONTEXT;
            }
            LocationAPI loc = currentLocation();
            if (loc == null) {
                Console.showMessage("错误：当前不在任何星系 / 空间内。");
                return CommandResult.ERROR;
            }
            if (requiresStarSystem() && !(loc instanceof StarSystemAPI)) {
                Console.showMessage("错误：只能在星系内执行（超空间中不行）。");
                return CommandResult.WRONG_CONTEXT;
            }
            String[] parts = split(args);
            String code = buildCode(parts);
            if (code == null || code.isEmpty()) {
                Console.showMessage(usage());
                return CommandResult.BAD_SYNTAX;
            }
            Console.showMessage("执行 runcode: " + code);
            try {
                Console.parseInput("runcode " + code, context);
            } catch (Throwable t) {
                Console.showException("天体命令执行失败", t);
                return CommandResult.ERROR;
            }
            return CommandResult.SUCCESS;
        }
    }

    static LocationAPI currentLocation() {
        try {
            if (Global.getSector() == null || Global.getSector().getPlayerFleet() == null) {
                return null;
            }
            return Global.getSector().getPlayerFleet().getContainingLocation();
        } catch (Throwable t) {
            return null;
        }
    }

    static String[] split(String args) {
        String s = args == null ? "" : args.trim();
        if (s.isEmpty()) {
            return new String[0];
        }
        return s.split("\\s+");
    }

    static String at(String[] a, int i) {
        return i < a.length && a[i] != null ? a[i].trim() : "";
    }

    static float num(String[] a, int i, float def) {
        String s = at(a, i);
        if (s.isEmpty()) {
            return def;
        }
        try {
            return Float.parseFloat(s);
        } catch (Throwable t) {
            return def;
        }
    }

    static int integer(String[] a, int i, int def) {
        String s = at(a, i);
        if (s.isEmpty()) {
            return def;
        }
        try {
            return (int) Float.parseFloat(s);
        } catch (Throwable t) {
            return def;
        }
    }

    /**
     * 取「最后一个参数之后的所有内容」——名称允许带空格。
     */
    static String tail(String args, int skipTokens) {
        String s = args == null ? "" : args.trim();
        if (s.isEmpty()) {
            return "";
        }
        String[] parts = s.split("\\s+");
        if (skipTokens >= parts.length) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = skipTokens; i < parts.length; i++) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(parts[i]);
        }
        return sb.toString();
    }

    /** 从第 skip 个 token 起全部拼回一个字符串（用于允许空格的名称）。 */
    static String rest(String[] parts, int skip) {
        if (parts == null || skip >= parts.length) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = skip; i < parts.length; i++) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(parts[i]);
        }
        return sb.toString();
    }

    /**
     * 取 `key=value` 形式的命名参数；值可以带空格（会一直取到下一个 `key=` 或串尾）。
     * 没有该键返回 null。
     */
    static String named(String[] parts, String key) {
        if (parts == null) {
            return null;
        }
        String prefix = key + "=";
        for (int i = 0; i < parts.length; i++) {
            String t = parts[i];
            if (t == null || !t.startsWith(prefix)) {
                continue;
            }
            StringBuilder sb = new StringBuilder(t.substring(prefix.length()));
            for (int j = i + 1; j < parts.length; j++) {
                String n = parts[j];
                if (n != null && n.indexOf('=') > 0 && !n.startsWith("=")) {
                    break;
                }
                sb.append(' ').append(n == null ? "" : n);
            }
            return sb.toString().trim();
        }
        return null;
    }

    /** 去掉所有 `key=...` 命名参数后剩下的位置参数。 */
    static String[] positional(String[] parts) {
        if (parts == null) {
            return new String[0];
        }
        java.util.List<String> out = new java.util.ArrayList<>();
        for (String t : parts) {
            if (t != null && t.indexOf('=') > 0) {
                continue;
            }
            out.add(t == null ? "" : t);
        }
        return out.toArray(new String[0]);
    }

    /** 解析浮点；失败用默认值。 */
    static float fnum(String s, float def) {
        if (s == null || s.trim().isEmpty()) {
            return def;
        }
        try {
            return Float.parseFloat(s.trim());
        } catch (Throwable t) {
            return def;
        }
    }

    static int inum(String s, int def) {
        if (s == null || s.trim().isEmpty()) {
            return def;
        }
        try {
            return (int) Float.parseFloat(s.trim());
        } catch (Throwable t) {
            return def;
        }
    }

    /**
     * 同时支持两种写法：命名参数 `key=value` 与位置参数。
     * 命名参数优先；否则取第 pos 个位置参数；都空则用 def。
     */
    static String arg(String[] a, String key, int pos, String def) {
        String n = named(a, key);
        if (n != null) {
            return n.trim().isEmpty() ? def : n.trim();
        }
        String s = at(positional(a), pos);
        return s.isEmpty() ? def : s;
    }

    /**
     * 把 `a,b,c` 形式的行星条件串拆开；跳过空项与 none。
     */
    static java.util.List<String> splitConditions(String s) {
        java.util.List<String> out = new java.util.ArrayList<>();
        if (s == null) {
            return out;
        }
        for (String part : s.split(",")) {
            String p = part == null ? "" : part.trim();
            if (!p.isEmpty() && !"none".equalsIgnoreCase(p)) {
                out.add(p);
            }
        }
        return out;
    }

    /**
     * 把条件（市场属性）的中文名或 id 统一成 id；认不出来就原样返回。
     * 例：丰富的矿床 -> ore_rich / 宜居 -> habitable / rare_ore_rich -> rare_ore_rich
     */
    static String conditionId(String token) {
        if (token == null) {
            return "";
        }
        String t = token.trim();
        if (t.isEmpty()) {
            return "";
        }
        try {
            java.util.List<com.fs.starfarer.api.characters.MarketConditionSpecAPI> all =
                    Global.getSettings().getAllMarketConditionSpecs();
            for (com.fs.starfarer.api.characters.MarketConditionSpecAPI s : all) {
                if (t.equalsIgnoreCase(s.getId())) {
                    return s.getId();
                }
            }
            for (com.fs.starfarer.api.characters.MarketConditionSpecAPI s : all) {
                if (t.equals(s.getName())) {
                    return s.getId();
                }
            }
            // 只接受「唯一包含」的模糊匹配：面板选择器已经把中文名解析成 id，
            // 这里再无条件取第一个包含项会把拼错的条件悄悄换成别的条件。
            String lower = t.toLowerCase();
            String hit = null;
            for (com.fs.starfarer.api.characters.MarketConditionSpecAPI s : all) {
                String n = s.getName();
                if (n != null && n.toLowerCase().contains(lower)) {
                    if (hit != null) {
                        return t;
                    }
                    hit = s.getId();
                }
            }
            if (hit != null) {
                return hit;
            }
        } catch (Throwable ignored) {
        }
        return t;
    }

    static String lit(String s) {
        if (s == null) {
            return "\"\"";
        }
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"') {
                sb.append("\\\"");
            } else if (c == '\\') {
                sb.append("\\\\");
            } else if (c == '\n') {
                sb.append("\\n");
            } else if (c == '\r') {
                sb.append("\\r");
            } else if (c == '\t') {
                sb.append("\\t");
            } else {
                sb.append(c);
            }
        }
        return sb.append('"').toString();
    }

    private static int idSeq;

    static synchronized String randId(String prefix) {
        idSeq++;
        String a = Long.toHexString(System.nanoTime() & 0xFFFFFL);
        String b = Integer.toHexString(idSeq & 0xFFF);
        return prefix + a + "_" + b;
    }

    /** 按类型键取行星的中文名；找不到就回退类型键本身。 */
    static String planetTypeName(String typeId) {
        try {
            for (PlanetSpecAPI s : Global.getSettings().getAllPlanetSpecs()) {
                if (typeId.equals(s.getPlanetType())) {
                    String n = s.getName();
                    if (n != null && !n.trim().isEmpty()) {
                        return n.trim();
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return typeId;
    }

    static String customEntityName(String typeId) {
        try {
            CustomEntitySpecAPI spec = Global.getSettings().getCustomEntitySpec(typeId);
            if (spec != null) {
                String n = spec.getNameInText();
                if (n == null || n.trim().isEmpty()) {
                    n = spec.getDefaultName();
                }
                if (n != null && !n.trim().isEmpty()) {
                    return n.trim();
                }
            }
        } catch (Throwable ignored) {
        }
        return typeId;
    }

    /** 生成「取轨道中心」的前置代码；focus 为空时使用星系中心恒星。 */
    static String focusCode(String focusId) {
        StringBuilder sb = new StringBuilder();
        sb.append("com.fs.starfarer.api.campaign.LocationAPI loc = Global.getSector().getPlayerFleet().getContainingLocation();");
        if (focusId == null || focusId.isEmpty()) {
            sb.append("com.fs.starfarer.api.campaign.SectorEntityToken focus = ((com.fs.starfarer.api.campaign.StarSystemAPI) loc).getStar();");
        } else {
            sb.append("com.fs.starfarer.api.campaign.SectorEntityToken focus = loc.getEntityById(").append(lit(focusId)).append(");");
            sb.append("if (focus == null) { focus = ((com.fs.starfarer.api.campaign.StarSystemAPI) loc).getStar(); }");
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------
    // 1) 自然天体：行星 / 恒星 / 卫星
    // ------------------------------------------------------------------
    public static class AddPlanet extends Astro {
        @Override
        protected String usage() {
            return "用法: astroaddplanet type=<类型> radius=<半径> orbitRadius=<轨道半径> orbitDays=<天数> angle=<角度> focus=<轨道中心id> name=<名称> desc=<描述id> conditions=<条件id,条件id|none>";
        }

        @Override
        protected String buildCode(String[] a) {
            String type = arg(a, "type", 0, "");
            if (type.isEmpty()) {
                return null;
            }
            float radius = fnum(arg(a, "radius", 1, ""), 150f);
            float orbitRadius = fnum(arg(a, "orbitRadius", 2, ""), 3000f);
            float orbitDays = fnum(arg(a, "orbitDays", 3, ""), 100f);
            float angle = fnum(arg(a, "angle", 4, ""), 0f);
            String focus = arg(a, "focus", 5, "");
            String name = arg(a, "name", 6, "");
            if (name.isEmpty()) {
                name = planetTypeName(type);
            }
            String desc = arg(a, "desc", 7, "");
            String conditions = arg(a, "conditions", 8, "");
            boolean autoConditions = !"none".equalsIgnoreCase(conditions);
            String id = randId("cf_p_");
            StringBuilder sb = new StringBuilder();
            sb.append(focusCode(focus))
                    .append("com.fs.starfarer.api.campaign.PlanetAPI p = loc.addPlanet(")
                    .append(lit(id)).append(", focus, ").append(lit(name)).append(", ").append(lit(type))
                    .append(", ").append(angle).append("f, ").append(radius).append("f, ")
                    .append(orbitRadius).append("f, ").append(orbitDays).append("f);");
            // 描述：不填时沿用 PlanetSpec 自带的 descriptionId（多数类型本来就有描述）
            if (!desc.isEmpty()) {
                sb.append("try { p.setDescriptionIdOverride(").append(lit(desc)).append("); }")
                        .append(" catch (Throwable ignored) {}");
            }
            // 市场属性 / 行星条件：由游戏的 PlanetConditionGenerator 按类型自动生成
            // （它同时会给星球建一个「仅条件用」的市场，行星信息面板才有条件列表可看）
            if (autoConditions) {
                sb.append("try { com.fs.starfarer.api.impl.campaign.procgen.PlanetConditionGenerator")
                        .append(".generateConditionsForPlanet(p, ((com.fs.starfarer.api.campaign.StarSystemAPI) loc).getAge()); }")
                        .append(" catch (Throwable ignored) {}")
                        .append("try { if (p.getMarket() != null) p.getMarket().setSurveyLevel(")
                        .append("com.fs.starfarer.api.campaign.econ.MarketAPI.SurveyLevel.FULL); }")
                        .append(" catch (Throwable ignored) {}");
                for (String c : splitConditions(conditions)) {
                    sb.append("try { if (p.getMarket() != null) p.getMarket().addCondition(")
                            .append(lit(conditionId(c))).append("); } catch (Throwable ignored) {}");
                }
                // 手动 addCondition 之后重算一次，条件带来的产出/危险度才会立刻生效
                sb.append("try { if (p.getMarket() != null) p.getMarket().reapplyConditions(); }")
                        .append(" catch (Throwable ignored) {}");
            }
            sb.append("Console.showMessage(\"已添加天体: \" + p.getName() + \" (\" + p.getId() + \")\")")
                    .append(";");
            return sb.toString();
        }
    }

    // ------------------------------------------------------------------
    // 2) 人造设施：空间站 / 中继站 / 星门 等自定义实体
    // ------------------------------------------------------------------
    public static class AddStation extends Astro {
        @Override
        protected String usage() {
            return "用法: astroaddstation type=<实体类型> faction=<派系> orbitRadius=<轨道半径> orbitDays=<天数> angle=<角度> focus=<轨道中心id> name=<名称> desc=<描述id>";
        }

        @Override
        protected String buildCode(String[] a) {
            String type = arg(a, "type", 0, "");
            if (type.isEmpty()) {
                return null;
            }
            String faction = arg(a, "faction", 1, "player");
            if (faction.isEmpty()) {
                faction = "player";
            }
            float orbitRadius = fnum(arg(a, "orbitRadius", 2, ""), 1200f);
            float orbitDays = fnum(arg(a, "orbitDays", 3, ""), 60f);
            float angle = fnum(arg(a, "angle", 4, ""), 0f);
            String focus = arg(a, "focus", 5, "");
            String name = arg(a, "name", 6, "");
            if (name.isEmpty()) {
                name = customEntityName(type);
            }
            String desc = arg(a, "desc", 7, "");
            String id = randId("cf_s_");
            return new StringBuilder()
                    .append(focusCode(focus))
                    .append("com.fs.starfarer.api.campaign.SectorEntityToken s = loc.addCustomEntity(")
                    .append(lit(id)).append(", ").append(lit(type)).append(", ").append(lit(faction))
                    .append(", ").append(lit(name)).append(");")
                    .append("s.setCircularOrbitPointingDown(focus, ").append(angle).append("f, ")
                    .append(orbitRadius).append("f, ").append(orbitDays).append("f);")
                    // 描述：留空则沿用 custom_entities.json 里该类型自带的 customDescriptionId
                    .append(desc.isEmpty() ? ""
                            : "try { s.setCustomDescriptionId(" + lit(desc) + "); } catch (Throwable ignored) {}")
                    .append("Console.showMessage(\"已添加空间站: \" + s.getName() + \" (\" + s.getId() + \")\")")
                    .append(";")
                    .toString();
        }
    }

    // ------------------------------------------------------------------
    // 3) 星云（自然景观）
    // ------------------------------------------------------------------
    public static class AddNebula extends Astro {
        @Override
        protected String usage() {
            return "用法: astroaddnebula key=<贴图> w=<宽> h=<高> orbitRadius=<轨道半径> orbitDays=<天数> angle=<角度>";
        }

        @Override
        protected String buildCode(String[] a) {
            String key = arg(a, "key", 0, "nebula");
            if (key.isEmpty()) {
                key = "nebula";
            }
            int w = Math.max(1, Math.min(8, inum(arg(a, "w", 1, ""), 3)));
            int h = Math.max(1, Math.min(8, inum(arg(a, "h", 2, ""), 3)));
            float orbitRadius = fnum(arg(a, "orbitRadius", 3, ""), 6000f);
            float orbitDays = fnum(arg(a, "orbitDays", 4, ""), 300f);
            float angle = fnum(arg(a, "angle", 5, ""), 0f);
            StringBuilder tiles = new StringBuilder();
            for (int i = 0; i < w * h; i++) {
                tiles.append('x');
            }
            return new StringBuilder()
                    .append("com.fs.starfarer.api.campaign.LocationAPI loc = Global.getSector().getPlayerFleet().getContainingLocation();")
                    .append("com.fs.starfarer.api.campaign.CampaignTerrainAPI t = (com.fs.starfarer.api.campaign.CampaignTerrainAPI) loc.addTerrain(\"nebula\", new com.fs.starfarer.api.impl.campaign.terrain.BaseTiledTerrain.TileParams(")
                    .append(lit(tiles.toString())).append(", ").append(w).append(", ").append(h)
                    .append(", \"terrain\", ").append(lit(key)).append(", 4, 4, null));")
                    .append("t.setCircularOrbit(((com.fs.starfarer.api.campaign.StarSystemAPI) loc).getStar(), ")
                    .append(angle).append("f, ").append(orbitRadius).append("f, ").append(orbitDays).append("f);")
                    .append("Console.showMessage(\"已添加星云，实体 id: \" + t.getId());")
                    .toString();
        }
    }

    // ------------------------------------------------------------------
    // 4) 行星环（自然景观）
    // ------------------------------------------------------------------
    public static class AddRing extends Astro {
        @Override
        protected String usage() {
            return "用法: astroaddring key=<贴图> middleRadius=<中径> bandWidth=<环宽> orbitDays=<天数> focus=<轨道中心id>";
        }

        @Override
        protected String buildCode(String[] a) {
            String key = arg(a, "key", 0, "rings_dust0");
            if (key.isEmpty()) {
                key = "rings_dust0";
            }
            float middleRadius = fnum(arg(a, "middleRadius", 1, ""), 900f);
            float bandWidth = fnum(arg(a, "bandWidth", 2, ""), 256f);
            float orbitDays = fnum(arg(a, "orbitDays", 3, ""), 60f);
            String focus = arg(a, "focus", 4, "");
            return new StringBuilder()
                    .append(focusCode(focus))
                    .append("com.fs.starfarer.api.campaign.RingBandAPI r = loc.addRingBand(focus, \"misc\", ")
                    .append(lit(key))
                    .append(", 256f, 0, java.awt.Color.white, ").append(bandWidth).append("f, ")
                    .append(middleRadius).append("f, ").append(orbitDays).append("f);")
                    .append("Console.showMessage(\"已添加行星环，实体 id: \" + r.getId());")
                    .toString();
        }
    }

    // ------------------------------------------------------------------
    // 5) 小行星带（自然景观）
    // ------------------------------------------------------------------
    public static class AddBelt extends Astro {
        @Override
        protected String usage() {
            return "用法: astroaddbelt count=<数量> orbitRadius=<轨道半径> width=<带宽> minDays=<最短天数> maxDays=<最长天数> focus=<轨道中心id> name=<名称>";
        }

        @Override
        protected String buildCode(String[] a) {
            int count = Math.max(1, Math.min(600, inum(arg(a, "count", 0, ""), 80)));
            float orbitRadius = fnum(arg(a, "orbitRadius", 1, ""), 5000f);
            float width = fnum(arg(a, "width", 2, ""), 400f);
            float minDays = fnum(arg(a, "minDays", 3, ""), 150f);
            float maxDays = fnum(arg(a, "maxDays", 4, ""), 300f);
            String focus = arg(a, "focus", 5, "");
            String name = arg(a, "name", 6, "");
            String nameLit = name.isEmpty() ? "null" : lit(name);
            return new StringBuilder()
                    .append(focusCode(focus))
                    .append("com.fs.starfarer.api.campaign.SectorEntityToken b = loc.addAsteroidBelt(focus, ")
                    .append(count).append(", ").append(orbitRadius).append("f, ").append(width).append("f, ")
                    .append(minDays).append("f, ").append(maxDays).append("f, \"asteroid_belt\", ")
                    .append(nameLit).append(");")
                    .append("Console.showMessage(\"已添加小行星带，实体 id: \" + b.getId());")
                    .toString();
        }
    }

    // ------------------------------------------------------------------
    // 6) 小行星群（局部密集的小行星云，不是环形小行星带）
    // ------------------------------------------------------------------
    public static class AddField extends Astro {
        @Override
        protected String usage() {
            return "用法: astroaddfield minRadius=<内径> maxRadius=<外径> minAsteroids=<最少> maxAsteroids=<最多> minSize=<最小尺寸> maxSize=<最大尺寸> orbitRadius=<轨道半径> orbitDays=<天数> angle=<角度> focus=<轨道中心id> name=<名称>";
        }

        @Override
        protected String buildCode(String[] a) {
            float minRadius = fnum(arg(a, "minRadius", 0, ""), 200f);
            float maxRadius = fnum(arg(a, "maxRadius", 1, ""), 400f);
            if (maxRadius < minRadius) {
                float t = minRadius;
                minRadius = maxRadius;
                maxRadius = t;
            }
            int minAsteroids = Math.max(1, Math.min(400, inum(arg(a, "minAsteroids", 2, ""), 20)));
            int maxAsteroids = Math.max(minAsteroids, Math.min(400, inum(arg(a, "maxAsteroids", 3, ""), 30)));
            float minSize = fnum(arg(a, "minSize", 4, ""), 4f);
            float maxSize = fnum(arg(a, "maxSize", 5, ""), 12f);
            if (maxSize < minSize) {
                float t = minSize;
                minSize = maxSize;
                maxSize = t;
            }
            float orbitRadius = fnum(arg(a, "orbitRadius", 6, ""), 1100f);
            float orbitDays = fnum(arg(a, "orbitDays", 7, ""), 70f);
            float angle = fnum(arg(a, "angle", 8, ""), 0f);
            String focus = arg(a, "focus", 9, "");
            String name = arg(a, "name", 10, "");
            String nameLit = name.isEmpty() ? "null" : lit(name);
            return new StringBuilder()
                    .append(focusCode(focus))
                    .append("com.fs.starfarer.api.campaign.SectorEntityToken f = loc.addTerrain(\"asteroid_field\", ")
                    .append("new com.fs.starfarer.api.impl.campaign.terrain.AsteroidFieldTerrainPlugin.AsteroidFieldParams(")
                    .append(minRadius).append("f, ").append(maxRadius).append("f, ")
                    .append(minAsteroids).append(", ").append(maxAsteroids).append(", ")
                    .append(minSize).append("f, ").append(maxSize).append("f, ").append(nameLit).append("));")
                    .append("f.setCircularOrbitPointingDown(focus, ").append(angle).append("f, ")
                    .append(orbitRadius).append("f, ").append(orbitDays).append("f);")
                    .append("Console.showMessage(\"已添加小行星群，实体 id: \" + f.getId());")
                    .toString();
        }
    }

    // ------------------------------------------------------------------
    // 7) 删除实体（天体 / 空间站 / 星云 / 小行星带）
    // ------------------------------------------------------------------
    public static class RemoveEntity extends Astro {
        @Override
        protected boolean requiresStarSystem() {
            return false;
        }

        @Override
        protected String usage() {
            return "用法: astroremove id=<实体 id>（面板里用“目标实体”选择器挑，或控制台 planetlist 查看）";
        }

        @Override
        protected String buildCode(String[] a) {
            String id = arg(a, "id", 0, "");
            if (id.isEmpty()) {
                return null;
            }
            return new StringBuilder()
                    .append("com.fs.starfarer.api.campaign.LocationAPI loc = Global.getSector().getPlayerFleet().getContainingLocation();")
                    .append("com.fs.starfarer.api.campaign.SectorEntityToken e = loc.getEntityById(").append(lit(id)).append(");")
                    .append("if (e == null) { Console.showMessage(").append(lit("未找到实体: " + id)).append("); }")
                    .append(" else { String n = e.getName(); loc.removeEntity(e); Console.showMessage(\"已删除实体: \" + n); }")
                    .toString();
        }
    }
}
