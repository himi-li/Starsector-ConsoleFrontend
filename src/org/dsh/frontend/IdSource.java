package org.dsh.frontend;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.ModSpecAPI;
import com.fs.starfarer.api.campaign.FactionAPI;
import com.fs.starfarer.api.campaign.SectorAPI;
import com.fs.starfarer.api.campaign.SpecialItemSpecAPI;
import com.fs.starfarer.api.campaign.econ.CommoditySpecAPI;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.campaign.econ.SubmarketSpecAPI;
import com.fs.starfarer.api.characters.MarketConditionSpecAPI;
import com.fs.starfarer.api.characters.OfficerDataAPI;
import com.fs.starfarer.api.combat.ShipHullSpecAPI;
import com.fs.starfarer.api.combat.ShipSystemSpecAPI;
import com.fs.starfarer.api.combat.ShipVariantAPI;
import com.fs.starfarer.api.loading.FighterWingSpecAPI;
import com.fs.starfarer.api.loading.HullModSpecAPI;
import com.fs.starfarer.api.loading.IndustrySpecAPI;
import com.fs.starfarer.api.loading.WeaponSpecAPI;
import org.lazywizard.console.commands.AddSpecial;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * ID 选择器的数据源：把游戏内各种 spec 的【显示名 + ID】收集成可搜索列表。
 * 名称优先显示，ID 作为注释保留。
 */
public final class IdSource {

    public static final String COMMODITY = "commodity";
    public static final String SPECIAL = "special";
    public static final String COMMODITY_SPECIAL = "commodity+special";
    public static final String WEAPON = "weapon";
    public static final String WING = "wing";
    public static final String HULLMOD = "hullmod";
    public static final String SHIP = "ship";
    public static final String SHIP_VARIANT = "ship+variant";
    public static final String VARIANT = "variant";
    public static final String FACTION = "faction";
    public static final String MARKET = "market";
    public static final String MARKET_SYSTEM = "market+system";
    public static final String SYSTEM = "system";
    public static final String CONDITION = "condition";
    public static final String INDUSTRY = "industry";
    public static final String SUBMARKET = "submarket";
    public static final String SHIP_SYSTEM = "shipSystem";
    public static final String OFFICER = "officer";
    public static final String PERSONALITY = "personality";

    /** 来源 Mod 反查表：id -> Mod 名（尽力而为，失败返回 null）。 */
    private static Map<String, String> modNames;

    private IdSource() {
    }

    public static List<String> knownSources() {
        List<String> l = new ArrayList<String>();
        Collections.addAll(l, COMMODITY, SPECIAL, COMMODITY_SPECIAL, WEAPON, WING, HULLMOD,
                SHIP, VARIANT, SHIP_VARIANT, FACTION, MARKET, SYSTEM, MARKET_SYSTEM,
                CONDITION, INDUSTRY, SUBMARKET, SHIP_SYSTEM, OFFICER, PERSONALITY);
        return l;
    }

    public static String displayNameOf(String source) {
        if (source == null) {
            return "项目";
        }
        switch (source) {
            case COMMODITY: return "商品";
            case SPECIAL: return "特殊物品";
            case COMMODITY_SPECIAL: return "物品";
            case WEAPON: return "武器";
            case WING: return "战机";
            case HULLMOD: return "船插";
            case SHIP: return "舰船";
            case VARIANT: return "变体";
            case SHIP_VARIANT: return "舰船 / 变体";
            case FACTION: return "派系";
            case MARKET: return "市场";
            case SYSTEM: return "星系";
            case MARKET_SYSTEM: return "市场 / 星系";
            case CONDITION: return "市场条件";
            case INDUSTRY: return "工业";
            case SUBMARKET: return "子市场";
            case SHIP_SYSTEM: return "舰船系统";
            case OFFICER: return "军官";
            case PERSONALITY: return "性格";
            default: return "项目";
        }
    }

    /** 主标签页（选择器顶部的来源过滤按钮）。 */
    public static List<String> tabsFor(String source) {
        List<String> tabs = new ArrayList<String>();
        if (source == null) {
            return tabs;
        }
        switch (source) {
            case COMMODITY_SPECIAL:
            case COMMODITY:
            case SPECIAL:
                Collections.addAll(tabs, COMMODITY, SPECIAL);
                break;
            case SHIP_VARIANT:
            case SHIP:
            case VARIANT:
                Collections.addAll(tabs, SHIP, VARIANT);
                break;
            case MARKET_SYSTEM:
            case MARKET:
            case SYSTEM:
                Collections.addAll(tabs, MARKET, SYSTEM);
                break;
            default:
                tabs.add(source);
                break;
        }
        return tabs;
    }

    private static String modOf(String id) {
        if (id == null || id.isEmpty()) {
            return "";
        }
        Map<String, String> cache = modNames;
        if (cache == null) {
            cache = new LinkedHashMap<String, String>();
            try {
                for (ModSpecAPI spec : Global.getSettings().getModManager().getEnabledModsCopy()) {
                    cache.put(spec.getId(), spec.getName());
                }
            } catch (Throwable ignored) {
            }
            modNames = cache;
        }
        int cut = id.indexOf('_');
        if (cut > 0) {
            String head = id.substring(0, cut);
            String name = cache.get(head);
            if (name != null) {
                return name;
            }
        }
        return "";
    }

    public static void invalidate() {
        modNames = null;
    }

    private static String note(String id) {
        String m = modOf(id);
        if (m == null || m.isEmpty()) {
            return "";
        }
        return m;
    }

    public static List<IdOption> build(String source) {
        List<IdOption> out = new ArrayList<IdOption>();
        if (source == null) {
            return out;
        }
        Set<String> seen = new LinkedHashSet<String>();
        try {
            switch (source) {
                case COMMODITY:
                    addCommodities(out, seen);
                    break;
                case SPECIAL:
                    addSpecials(out, seen);
                    break;
                case COMMODITY_SPECIAL:
                    addCommodities(out, seen);
                    addSpecials(out, seen);
                    break;
                case WEAPON:
                    for (WeaponSpecAPI s : Global.getSettings().getAllWeaponSpecs()) {
                        add(out, seen, s.getWeaponId(), s.getWeaponName());
                    }
                    break;
                case WING:
                    for (FighterWingSpecAPI s : Global.getSettings().getAllFighterWingSpecs()) {
                        add(out, seen, s.getId(), s.getWingName());
                    }
                    break;
                case HULLMOD:
                    for (HullModSpecAPI s : Global.getSettings().getAllHullModSpecs()) {
                        add(out, seen, s.getId(), s.getDisplayName());
                    }
                    break;
                case SHIP:
                    for (ShipHullSpecAPI s : Global.getSettings().getAllShipHullSpecs()) {
                        String id = s.getBaseHullId() == null || s.getBaseHullId().isEmpty() ? s.getHullId() : s.getBaseHullId();
                        add(out, seen, id, s.getHullNameWithDashClass());
                    }
                    break;
                case VARIANT:
                    for (String id : Global.getSettings().getAllVariantIds()) {
                        String name = id;
                        try {
                            ShipVariantAPI v = Global.getSettings().getVariant(id);
                            if (v != null && v.getFullDesignationWithHullName() != null) {
                                name = v.getFullDesignationWithHullName();
                            }
                        } catch (Throwable ignored) {
                        }
                        add(out, seen, id, name);
                    }
                    break;
                case SHIP_VARIANT:
                    for (ShipHullSpecAPI s : Global.getSettings().getAllShipHullSpecs()) {
                        String id = s.getBaseHullId() == null || s.getBaseHullId().isEmpty() ? s.getHullId() : s.getBaseHullId();
                        add(out, seen, id, s.getHullNameWithDashClass());
                    }
                    for (String id : Global.getSettings().getAllVariantIds()) {
                        String name = id;
                        try {
                            ShipVariantAPI v = Global.getSettings().getVariant(id);
                            if (v != null && v.getFullDesignationWithHullName() != null) {
                                name = v.getFullDesignationWithHullName();
                            }
                        } catch (Throwable ignored) {
                        }
                        add(out, seen, id, name);
                    }
                    break;
                case FACTION:
                    for (FactionAPI f : Global.getSector().getAllFactions()) {
                        add(out, seen, f.getId(), f.getDisplayName());
                    }
                    break;
                case MARKET:
                    for (MarketAPI m : Global.getSector().getEconomy().getMarketsCopy()) {
                        add(out, seen, m.getId(), m.getName());
                    }
                    break;
                case SYSTEM:
                    for (com.fs.starfarer.api.campaign.StarSystemAPI s : Global.getSector().getStarSystems()) {
                        add(out, seen, s.getId(), s.getBaseName());
                    }
                    break;
                case MARKET_SYSTEM:
                    for (MarketAPI m : Global.getSector().getEconomy().getMarketsCopy()) {
                        add(out, seen, m.getId(), m.getName());
                    }
                    for (com.fs.starfarer.api.campaign.StarSystemAPI s : Global.getSector().getStarSystems()) {
                        add(out, seen, s.getId(), s.getBaseName());
                    }
                    break;
                case CONDITION:
                    for (MarketConditionSpecAPI s : Global.getSettings().getAllMarketConditionSpecs()) {
                        add(out, seen, s.getId(), s.getName());
                    }
                    break;
                case INDUSTRY:
                    for (IndustrySpecAPI s : Global.getSettings().getAllIndustrySpecs()) {
                        add(out, seen, s.getId(), s.getName());
                    }
                    break;
                case SUBMARKET:
                    for (SubmarketSpecAPI s : Global.getSettings().getAllSubmarketSpecs()) {
                        add(out, seen, s.getId(), s.getName());
                    }
                    break;
                case SHIP_SYSTEM:
                    for (ShipSystemSpecAPI s : Global.getSettings().getAllShipSystemSpecs()) {
                        add(out, seen, s.getId(), s.getName());
                    }
                    break;
                case OFFICER:
                    try {
                        List<OfficerDataAPI> officers = Global.getSector().getPlayerFleet().getFleetData().getOfficersCopy();
                        for (int i = 0; i < officers.size(); i++) {
                            OfficerDataAPI o = officers.get(i);
                            String name = o.getPerson() == null ? ("军官 " + (i + 1)) : o.getPerson().getNameString();
                            add(out, seen, String.valueOf(i + 1), name);
                        }
                    } catch (Throwable ignored) {
                    }
                    break;
                case PERSONALITY:
                    addPersonalities(out);
                    break;
                default:
                    break;
            }
        } catch (Throwable t) {
            try {
                Global.getLogger(IdSource.class).warn("构建 ID 列表失败 (" + source + "): " + t);
            } catch (Throwable ignored) {
            }
        }
        Collections.sort(out, new Comparator<IdOption>() {
            @Override
            public int compare(IdOption a, IdOption b) {
                int c = a.name.compareToIgnoreCase(b.name);
                return c != 0 ? c : a.id.compareToIgnoreCase(b.id);
            }
        });
        return out;
    }

    private static void addCommodities(List<IdOption> out, Set<String> seen) {
        for (CommoditySpecAPI s : Global.getSettings().getAllCommoditySpecs()) {
            add(out, seen, s.getId(), s.getName());
        }
    }

    private static void addSpecials(List<IdOption> out, Set<String> seen) {
        for (SpecialItemSpecAPI s : Global.getSettings().getAllSpecialItemSpecs()) {
            add(out, seen, s.getId(), s.getName());
        }
        // 兜底：AddSpecial 自带的 ID 列表（防止个别 spec 未注册到 Settings）
        try {
            for (String id : AddSpecial.getSpecialItemIds()) {
                if (!seen.contains(id)) {
                    add(out, seen, id, null);
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private static void addPersonalities(List<IdOption> out) {
        add(out, new LinkedHashSet<String>(), "aggressive", "侵略");
        add(out, new LinkedHashSet<String>(), "steady", "稳健");
        add(out, new LinkedHashSet<String>(), "cautious", "谨慎");
        add(out, new LinkedHashSet<String>(), "timid", "胆小");
        add(out, new LinkedHashSet<String>(), "reckless", "鲁莽");
    }

    private static void add(List<IdOption> out, Set<String> seen, String id, String name) {
        if (id == null || id.isEmpty() || seen.contains(id)) {
            return;
        }
        seen.add(id);
        String note = FrontendSettings.idShowSourceMod ? note(id) : "";
        out.add(new IdOption(id, name, note, null));
    }

    /**
     * 把玩家输入的文本解析为真实 ID：精确 ID -> 精确名称 -> 忽略大小写名称 -> 唯一前缀 -> 唯一包含。
     * 无法唯一确定时返回原文本（交给 Console 报错并显示语法）。
     */
    public static String resolve(List<IdOption> options, String text) {
        if (text == null) {
            return "";
        }
        String q = text.trim();
        if (q.isEmpty()) {
            return "";
        }
        if (options == null || options.isEmpty()) {
            return q;
        }
        String lower = q.toLowerCase();
        for (IdOption o : options) {
            if (o.id.equalsIgnoreCase(q)) {
                return o.id;
            }
        }
        for (IdOption o : options) {
            if (o.name.equalsIgnoreCase(q)) {
                return o.id;
            }
        }
        for (IdOption o : options) {
            if (o.name.toLowerCase().equals(lower)) {
                return o.id;
            }
        }
        List<IdOption> prefix = new ArrayList<IdOption>();
        for (IdOption o : options) {
            if (o.name.toLowerCase().startsWith(lower)) {
                prefix.add(o);
            }
        }
        if (prefix.size() == 1) {
            return prefix.get(0).id;
        }
        List<IdOption> contains = new ArrayList<IdOption>();
        for (IdOption o : options) {
            if (o.name.toLowerCase().contains(lower) || o.id.toLowerCase().contains(lower)) {
                contains.add(o);
            }
        }
        if (contains.size() == 1) {
            return contains.get(0).id;
        }
        return q;
    }

    /** 多重匹配时返回候选（用于在日志区提示玩家）。 */
    public static List<IdOption> candidates(List<IdOption> options, String text, int max) {
        List<IdOption> res = new ArrayList<IdOption>();
        if (options == null || text == null || text.trim().isEmpty()) {
            return res;
        }
        String lower = text.trim().toLowerCase();
        for (IdOption o : options) {
            if (o.name.toLowerCase().contains(lower) || o.id.toLowerCase().contains(lower)) {
                res.add(o);
                if (res.size() >= max) {
                    break;
                }
            }
        }
        return res;
    }

    public static List<IdOption> filter(List<IdOption> options, String query) {
        if (options == null) {
            return new ArrayList<IdOption>();
        }
        if (query == null || query.trim().isEmpty()) {
            return new ArrayList<IdOption>(options);
        }
        String q = query.trim().toLowerCase();
        List<IdOption> res = new ArrayList<IdOption>();
        for (IdOption o : options) {
            if (o.searchBlob().contains(q)) {
                res.add(o);
            }
        }
        return res;
    }

    public static IdOption find(List<IdOption> options, String id) {
        if (options == null || id == null) {
            return null;
        }
        for (IdOption o : options) {
            if (o.id.equalsIgnoreCase(id)) {
                return o;
            }
        }
        return null;
    }
}