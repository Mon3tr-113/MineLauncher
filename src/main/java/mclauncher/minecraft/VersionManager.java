package mclauncher.minecraft;

import mclauncher.config.Config;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** 管理已安装的版本：列出 / 读取并合并继承。 */
public class VersionManager {

    /** 已安装版本的一句话摘要（用于版本下拉里显示「1.21.1 + Fabric 0.20.1」这种）。 */
    public static class Summary {
        public final String id;
        /** 对应的 Minecraft 版本（原版就是自己）。 */
        public final String mcVersion;
        /** vanilla / fabric / forge / neoforge / quilt / optifine。 */
        public final String loader;
        public final String loaderVersion;

        Summary(String id, String mcVersion, String loader, String loaderVersion) {
            this.id = id;
            this.mcVersion = mcVersion;
            this.loader = loader;
            this.loaderVersion = loaderVersion;
        }

        /** 是不是「游戏版本 + 加载器」这种派生版本。 */
        public boolean loaderBased() {
            return loader != null && !"vanilla".equals(loader);
        }

        /** 加载器的中文显示名。 */
        public String loaderName() {
            return switch (loader == null ? "vanilla" : loader) {
                case "fabric" -> "Fabric";
                case "forge" -> "Forge";
                case "neoforge" -> "NeoForge";
                case "quilt" -> "Quilt";
                case "optifine" -> "OptiFine";
                default -> "原版";
            };
        }

        /** 自动生成的显示名：`1.21.1  +  Fabric 0.20.1` / `1.21.1`。 */
        public String autoLabel() {
            if (!loaderBased()) return mcVersion;
            String lv = (loaderVersion == null || loaderVersion.isBlank()) ? "" : " " + loaderVersion;
            return mcVersion + "  +  " + loaderName() + lv;
        }
    }

    private final Config cfg;

    public VersionManager(Config cfg) {
        this.cfg = cfg;
    }

    public List<String> listInstalled() {
        List<String> result = new ArrayList<>();
        Path versionsDir = cfg.versionsDir();
        if (!Files.isDirectory(versionsDir)) return result;
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(versionsDir)) {
            for (Path p : ds) {
                if (!Files.isDirectory(p)) continue;
                String id = p.getFileName().toString();
                if (Files.exists(p.resolve(id + ".json"))) result.add(id);
            }
        } catch (IOException ignored) {
        }
        result.sort(Comparator.naturalOrder());
        return result;
    }

    /** 已安装版本的摘要列表（按 Minecraft 版本 + 加载器排序，解析失败的条目跳过）。 */
    public List<Summary> listSummaries() {
        List<Summary> out = new ArrayList<>();
        for (String id : listInstalled()) {
            Summary s = summarize(cfg.versionsDir().resolve(id).resolve(id + ".json"), id);
            if (s != null) out.add(s);
        }
        out.sort((a, b) -> {
            int c = compareMc(b.mcVersion, a.mcVersion);       // 新版本在前
            if (c != 0) return c;
            boolean ab = a.loaderBased(), bb = b.loaderBased();
            if (ab != bb) return ab ? -1 : 1;                  // 带加载器的排前面
            return a.id.compareTo(b.id);
        });
        return out;
    }

    /** 读一个版本 JSON，判断它是原版还是「原版 + 加载器」，以及加载器版本。 */
    public static Summary summarize(Path jsonFile, String id) {
        try {
            var root = com.google.gson.JsonParser.parseString(Files.readString(jsonFile)).getAsJsonObject();
            String mainClass = root.has("mainClass") ? root.get("mainClass").getAsString() : "";
            String inheritsFrom = root.has("inheritsFrom") ? root.get("inheritsFrom").getAsString() : null;
            String mc = inheritsFrom;
            String loader = "vanilla";
            String loaderVersion = null;

            // FML 系（Forge 1.17+ / NeoForge）：靠启动参数里的版本号
            String neo = argOf(root, "--fml.neoForgeVersion");
            String forge = argOf(root, "--fml.forgeVersion");
            String forgeMc = argOf(root, "--fml.mcVersion");
            if (forgeMc != null && !forgeMc.isBlank()) mc = forgeMc;
            if (neo != null && !neo.isBlank()) {
                loader = "neoforge";
                loaderVersion = neo;
            } else if (forge != null && !forge.isBlank()) {
                loader = "forge";
                loaderVersion = forge;
            } else if (mainClass != null && mainClass.toLowerCase().contains("quilt")) {
                // Quilt 的主类和 Fabric 一样是 knot.KnotClient，但包名是 org.quiltmc，必须先判
                loader = "quilt";
                loaderVersion = libraryVersion(root, "org.quiltmc:quilt-loader");
            } else if (mainClass != null && mainClass.contains("KnotClient")) {
                loader = "fabric";
                loaderVersion = libraryVersion(root, "net.fabricmc:fabric-loader");
            } else if (mainClass != null && mainClass.contains("launchwrapper")) {
                // Forge 1.12 及更早：靠 id 里的 1.x.x-forge-<ver>
                String v = id == null ? null : id;
                if (v != null && v.contains("-forge-")) {
                    loader = "forge";
                    int i = v.indexOf("-forge-");
                    loaderVersion = v.substring(i + "-forge-".length());
                    if (mc == null) mc = v.substring(0, i);
                }
            }
            if (mc == null || mc.isBlank()) mc = id;
            return new Summary(id, mc, loader, loaderVersion);
        } catch (Exception e) {
            return null;
        }
    }

    /** 从 arguments.game 里取 `--flag value`。 */
    private static String argOf(com.google.gson.JsonObject root, String flag) {
        if (!root.has("arguments")) return null;
        var args = root.getAsJsonObject("arguments");
        if (args == null || !args.has("game")) return null;
        var arr = args.getAsJsonArray("game");
        for (int i = 0; i + 1 < arr.size(); i++) {
            var a = arr.get(i);
            if (a.isJsonPrimitive() && flag.equals(a.getAsString())) {
                var b = arr.get(i + 1);
                return b.isJsonPrimitive() ? b.getAsString() : null;
            }
        }
        return null;
    }

    /** 从 libraries 里取形如 `g:a:v` 的库版本号。 */
    private static String libraryVersion(com.google.gson.JsonObject root, String ga) {
        if (!root.has("libraries")) return null;
        for (var le : root.getAsJsonArray("libraries")) {
            if (!le.isJsonObject()) continue;
            var o = le.getAsJsonObject();
            if (!o.has("name")) continue;
            String[] p = o.get("name").getAsString().split(":");
            if (p.length >= 3 && (p[0] + ":" + p[1]).equals(ga)) return p[2];
        }
        return null;
    }

    /** 粗粒度版本比较（1.21.1 > 1.20.5 > 1.7.10）。 */
    private static int compareMc(String a, String b) {
        return compareNums(a == null ? "" : a, b == null ? "" : b);
    }

    private static int compareNums(String a, String b) {
        String[] x = a.split("[^0-9]+");
        String[] y = b.split("[^0-9]+");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int xi = i < x.length && !x[i].isBlank() ? parseInt(x[i]) : 0;
            int yi = i < y.length && !y[i].isBlank() ? parseInt(y[i]) : 0;
            if (xi != yi) return Integer.compare(xi, yi);
        }
        return a.compareTo(b);
    }

    private static int parseInt(String s) {
        try {
            return Integer.parseInt(s);
        } catch (Exception e) {
            return 0;
        }
    }

    public GameVersion load(String id) throws Exception {
        Path json = cfg.versionsDir().resolve(id).resolve(id + ".json");
        String raw = Files.readString(json);
        GameVersion v = GameVersion.loadFromJson(id, raw);
        if (v.inheritsFrom != null && !v.inheritsFrom.equals(id)) {
            GameVersion parent = load(v.inheritsFrom);
            v.mergeFrom(parent);
        }
        return v;
    }
}
