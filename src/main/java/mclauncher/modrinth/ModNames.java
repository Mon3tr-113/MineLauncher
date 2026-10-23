package mclauncher.modrinth;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 常见 Mod 的中文名。
 *
 * <p>这里只放「内置兜底表」（离线可用、打开就能看到中文）；其余由 {@link McmodApi}
 * 去 MC 百科查，查到后写进 {@code ~/.mclauncher/mcmod-cache.json}。
 * 查不到才回退显示 Modrinth 的英文原名。
 */
public final class ModNames {

    private static final Map<String, String> CN;

    static {
        Map<String, String> m = new LinkedHashMap<>();
        // ---- 性能 / 优化 ----
        m.put("sodium", "钠");
        m.put("lithium", "锂");
        m.put("phosphor", "磷");
        m.put("iris", "虹彩光影");
        m.put("indium", "铟");
        m.put("krypton", "氪");
        m.put("spark", "火花");
        m.put("jade", "玉");
        m.put("modernfix", "现代修复");
        m.put("ferritecore", "铁氧体核心");
        m.put("entityculling", "实体剔除");
        m.put("moreculling", "更多剔除");
        m.put("immediatelyfast", "即时快速");
        m.put("memoryleakfix", "内存泄漏修复");
        m.put("dynamic-fps", "动态帧率");
        m.put("badoptimizations", "劣化优化");
        m.put("c2me-fabric", "C2ME");
        m.put("vmp-fabric", "VMP");
        m.put("threadtweak", "线程优化");
        m.put("servercore", "服务器核心");
        m.put("lazy-dfu", "懒加载 DFU");
        m.put("sodium-extra", "钠·扩展");
        m.put("reeses-sodium-options", "钠·选项优化");
        m.put("no-chat-reports", "无聊天报告");
        m.put("betterf3", "更好的 F3");
        m.put("toastcontrol", "提示控制");
        m.put("fastload", "快速加载");
        m.put("modelfix", "模型修复");
        m.put("entity-model-features", "实体模型特性");
        m.put("debugify", "Mojang 修复");
        m.put("blanket", "毯子");
        m.put("krypton", "氪");
        m.put("fastanim", "快速动画");
        m.put("clumps", "经验团");
        m.put("fix-gpu-memory-leak", "显存泄漏修复");
        m.put("exordium", "智能渲染");
        m.put("nvidium", "Nvidium");
        m.put("sodium-shadowy-path-blocks", "钠·阴影方块");
        m.put("iris-flywheel-compat", "虹彩×Flywheel 兼容");
        m.put("lambdabettergrass", "Lambda 更好的草");
        m.put("fabric-seeds", "Fabric 种子");

        // ---- 大型内容 Mod ----
        m.put("create", "机械动力");
        m.put("createaddition", "机械动力：附加");
        m.put("create-central-kitchen", "机械动力：中央厨房");
        m.put("ae2", "应用能源2");
        m.put("applied-energistics-2", "应用能源2");
        m.put("tconstruct", "匠魂");
        m.put("botania", "植物魔法");
        m.put("twilightforest", "暮色森林");
        m.put("thermal-foundation", "热力基础");
        m.put("immersiveengineering", "沉浸工程");
        m.put("mekanism", "通用机械");
        m.put("bloodmagic", "血魔法");
        m.put("ars-nouveau", "新生魔艺");
        m.put("occultism", "神秘学");
        m.put("farmers-delight", "农夫乐事");
        m.put("croptopia", "作物盛景");
        m.put("pams-harvestcraft", "潘马斯农场");
        m.put("alexs-mobs", "Alex 的生物");
        m.put("alexs-caves", "Alex 的洞穴");
        m.put("alexs-delight", "Alex 的乐事");
        m.put("cobblemon", "方块宝可梦");
        m.put("create-astral", "机械动力：星辉");
        m.put("ice-and-fire", "冰与火之歌");
        m.put("epicfight", "史诗战斗");
        m.put("epic-fight-mod", "史诗战斗");
        m.put("the-aether", "天境");
        m.put("aether", "天境");
        m.put("rats", "老鼠");
        m.put("quark", "夸克");
        m.put("yungs-better-dungeons", "YUNG 的更好的地牢");
        m.put("when-dungeons-arise", "地牢浮现之时");
        m.put("dungeons-and-taverns", "地牢与酒馆");
        m.put("minecolonies", "我的殖民地");
        m.put("mca", "Minecraft Comes Alive");
        m.put("sophisticatedbackpacks", "精妙背包");
        m.put("sophisticated-backpacks", "精妙背包");
        m.put("sophisticatedcore", "精妙核心");
        m.put("toms-storage", "Tom 的简易存储");
        m.put("refinedstorage", "精致存储");
        m.put("storage-drawers", "储物抽屉");
        m.put("functionalstorage", "功能性存储");
        m.put("waystones", "指路石");
        m.put("journeymap", "旅行地图");
        m.put("xaeros-minimap", "Xaero 的小地图");
        m.put("xaeros-world-map", "Xaero 的世界地图");
        m.put("curios", "饰品栏");
        m.put("trinkets", "饰品");
        m.put("geckolib", "GeckoLib");
        m.put("patchouli", "帕秋莉手册");
        m.put("appleskin", "苹果皮");
        m.put("serene-seasons", "静谧四季");
        m.put("waystones-reforged", "指路石");
        m.put("apotheosis", "神化");
        m.put("placebo", "安慰剂");
        m.put("mob-grinding-utils", "怪物磨床工具");
        m.put("shetiphiancore", "ShetiPhianCore");
        m.put("ender-storage", "末影存储");
        m.put("iron-chest", "铁箱");
        m.put("ironchest", "铁箱");
        m.put("iron-furnaces", "铁熔炉");
        m.put("comforts", "舒适用品");
        m.put("corpse", "尸体");

        // ---- 前置 / API ----
        m.put("fabric-api", "Fabric API");
        m.put("forge", "Forge");
        m.put("neoforge", "NeoForge");
        m.put("quilted-fabric-api", "Quilted Fabric API");
        m.put("cloth-config", "Cloth Config API");
        m.put("architectury", "Architectury API");
        m.put("architectury-api", "Architectury API");
        m.put("kotlinforforge", "Kotlin for Forge");
        m.put("fabric-language-kotlin", "Fabric 语言 Kotlin");
        m.put("balm", "Balm");
        m.put("forge-config-api-port", "Forge Config API Port");
        m.put("obsidianui", "ObsidianUI");
        m.put("owo-lib", "oωo lib");
        m.put("libz", "LibZ");
        m.put("midnightlib", "MidnightLib");
        m.put("yacl", "YACL");
        m.put("yet-another-config-lib", "YACL");
        m.put("puzzles-lib", "Puzzles Lib");
        m.put("collective", "Collective");
        m.put("moonlight", "Moonlight Lib");
        m.put("resourceful-lib", "Resourceful Lib");
        m.put("fzzy-config", "Fzzy Config");

        // ---- 界面 / 信息 ----
        m.put("jei", "JEI 物品管理器");
        m.put("rei", "REI 物品管理器");
        m.put("emi", "EMI 物品管理器");
        m.put("wthit", "WTHIT");
        m.put("modmenu", "模组菜单");
        m.put("catalogue", "目录");
        m.put("controlling", "Controlling");
        m.put("appleskin-reforged", "苹果皮");
        m.put("inventory-profiles-next", "物品栏配置");
        m.put("mouse-tweaks", "鼠标微调");
        m.put("zoomify", "缩放");
        m.put("ok-zoomer", "OK Zoomer");
        m.put("no-telemetry", "无遥测");
        m.put("better-advancements", "更好的进度");
        m.put("advancementinfo", "进度信息");
        m.put("legendary-tooltips", "传说提示");
        m.put("tooltipfix", "提示框修复");
        m.put("trashslot", "垃圾桶槽");
        m.put("inventory-sorter", "物品栏整理");
        m.put("inventoryprofilesnext", "物品栏配置");

        CN = Collections.unmodifiableMap(m);
    }

    private ModNames() {
    }

    /** 内置表（给 {@link McmodApi} 初始化缓存用）。 */
    static Map<String, String> builtinEntries() {
        return CN;
    }

    /**
     * 返回中文名：先查内置表，再查 MC 百科缓存；都没有返回 null。
     * 想触发联网查询用 {@link McmodApi#resolveChineseAsync}。
     */
    public static String chinese(String slugOrTitle) {
        if (slugOrTitle == null || slugOrTitle.isBlank()) return null;
        String key = slugOrTitle.trim();
        String builtin = CN.get(key.toLowerCase(java.util.Locale.ROOT));
        if (builtin != null) return builtin;
        return McmodApi.cachedChinese(key);
    }
}
