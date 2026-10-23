package mclauncher.net;

import mclauncher.config.Config;

/** BMCLAPI 国内镜像映射。把 Mojang 官方下载地址替换为国内镜像，加速下载。 */
public final class Mirror {

    private static final String BMCLAPI = "https://bmclapi2.bangbang93.com/";

    private static final String[][] MAP = {
            {"https://launchermeta.mojang.com/", BMCLAPI},
            {"https://piston-meta.mojang.com/", BMCLAPI},
            {"https://piston-data.mojang.com/", BMCLAPI},
            {"https://launcher.mojang.com/", BMCLAPI},
            {"https://libraries.minecraft.net/", BMCLAPI + "maven/"},
            {"https://resources.download.minecraft.net/", BMCLAPI + "assets/"},
            // 第三方 Maven 仓库统一走 BMCLAPI 的 /maven/ 代理（会 302 到实际 CDN）
            {"https://maven.fabricmc.net/", BMCLAPI + "maven/"},
            {"https://maven.minecraftforge.net/", BMCLAPI + "maven/"},
            {"https://maven.neoforged.net/releases/", BMCLAPI + "maven/"},
    };

    private Mirror() {
    }

    public static String map(String url) {
        if (url == null || url.isBlank()) return url;
        if (!Config.get().useMirror) return url;
        for (String[] m : MAP) {
            if (url.startsWith(m[0])) {
                return m[1] + url.substring(m[0].length());
            }
        }
        return url;
    }

    /**
     * 「另一个源」：镜像开着就返回官方原地址，镜像关着就返回镜像地址；两种都拿不到时返回 null。
     *
     * <p>下载卡住/失败时用它换源重试 —— 国内镜像偶尔会有单个文件巨慢、5xx，
     * 或者干脆不代理某类文件（例如 {@code /v1/objects/xxx/client-1.12.xml} 在镜像上是 404，
     * 官方 piston-data 却有），这时换回官方地址往往立刻就通。
     */
    public static String alternate(String url) {
        if (url == null || url.isBlank()) return null;
        return Config.get().useMirror ? toOfficial(url) : mapForced(url);
    }

    /** 不看开关，强制映射到镜像（换源重试用）。 */
    private static String mapForced(String url) {
        for (String[] m : MAP) {
            if (url.startsWith(m[0])) {
                return m[1] + url.substring(m[0].length());
            }
        }
        return null;
    }

    /**
     * 镜像地址 → 官方地址。
     *
     * <p>反向映射是不唯一的（多个官方源都映射到同一个镜像前缀），所以按路径特征判断：
     * {@code /assets/} 是资源、{@code /v1/objects/} 是 piston-data、{@code /maven/} 再按 groupId 猜仓库。
     */
    private static String toOfficial(String url) {
        if (!url.startsWith(BMCLAPI)) return null;
        String rest = url.substring(BMCLAPI.length());
        if (rest.startsWith("assets/")) {
            return "https://resources.download.minecraft.net/" + rest.substring("assets/".length());
        }
        if (rest.startsWith("v1/objects/")) {
            // 版本 JSON 里 client.jar / 日志配置等对象都挂在这个前缀下
            return "https://piston-data.mojang.com/" + rest;
        }
        if (rest.startsWith("mc/game/version_manifest")) {
            return "https://launchermeta.mojang.com/" + rest;
        }
        if (rest.startsWith("versions/")) {
            return "https://piston-meta.mojang.com/" + rest;
        }
        if (rest.startsWith("maven/")) {
            String path = rest.substring("maven/".length());
            if (path.startsWith("net/fabricmc/")) return "https://maven.fabricmc.net/" + path;
            if (path.startsWith("net/minecraftforge/")) return "https://maven.minecraftforge.net/" + path;
            if (path.startsWith("net/neoforged/")) return "https://maven.neoforged.net/releases/" + path;
            // cpw/mods、org/ow2/asm、com/mojang… 都来自 Mojang 官方库仓库
            return "https://libraries.minecraft.net/" + path;
        }
        return null;
    }
}
