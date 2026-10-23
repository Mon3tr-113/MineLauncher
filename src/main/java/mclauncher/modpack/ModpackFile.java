package mclauncher.modpack;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 外部整合包的「识别」：判断一个被拖进来 / 选中的文件到底是哪种整合包，以及它需要什么 MC 版本和加载器。
 *
 * <p>目前能认出这几种：
 * <ul>
 *   <li>{@link Kind#MODRINTH}：zip 里有 {@code modrinth.index.json}（Modrinth 的 .mrpack，无论从哪下的）；</li>
 *   <li>{@link Kind#CURSEFORGE}：zip 里有 {@code manifest.json}（CurseForge 导出的整合包）；</li>
 *   <li>{@link Kind#PLAIN}：普通 zip，里面有 {@code mods/}、{@code config/}、{@code options.txt} 之类的整合包内容；</li>
 *   <li>{@link Kind#INSTANCE_DIR}：已经解压好的整合包目录（Prism / MultiMC / HMCL 的实例目录也能读 {@code mmc-pack.json}）。</li>
 * </ul>
 * 认不出来的返回 {@link Kind#UNKNOWN}，由界面告诉用户支持哪些格式。
 */
public final class ModpackFile {

    public enum Kind {
        MODRINTH, CURSEFORGE, PLAIN, INSTANCE_DIR, UNKNOWN
    }

    public final Path path;
    public final Kind kind;
    /** 整合包名（识别不到就用文件名）。 */
    public final String name;
    /** 需要的 Minecraft 版本，可为 null（普通 zip 认不出来）。 */
    public final String mcVersion;
    /** vanilla / fabric / forge / neoforge / quilt。 */
    public final String loader;
    /** 加载器版本，可为 null。 */
    public final String loaderVersion;
    /** 清单里声明要下载的文件数（.mrpack / CurseForge）。 */
    public final int declaredFiles;
    /** 需要在界面上额外提醒用户的一句话，可为 null。 */
    public final String note;
    /**
     * 压缩包里「又多套了一层目录」时的公共前缀（例如 {@code MyPack/}），解压时要剥掉。
     * 空串表示没有多余的一层。
     */
    public final String rootPrefix;

    private ModpackFile(Path path, Kind kind, String name, String mcVersion, String loader,
                        String loaderVersion, int declaredFiles, String note) {
        this(path, kind, name, mcVersion, loader, loaderVersion, declaredFiles, note, "");
    }

    private ModpackFile(Path path, Kind kind, String name, String mcVersion, String loader,
                        String loaderVersion, int declaredFiles, String note, String rootPrefix) {
        this.path = path;
        this.kind = kind;
        this.name = name;
        this.mcVersion = mcVersion;
        this.loader = loader == null ? "vanilla" : loader;
        this.loaderVersion = loaderVersion;
        this.declaredFiles = declaredFiles;
        this.note = note;
        this.rootPrefix = rootPrefix == null ? "" : rootPrefix;
    }

    /** 是否能给出「装哪个 MC + 哪个加载器」，即装完后可以直接启动。 */
    public boolean launchable() {
        return mcVersion != null && !mcVersion.isBlank()
                && (kind == Kind.MODRINTH || kind == Kind.CURSEFORGE || kind == Kind.INSTANCE_DIR);
    }

    public String summary() {
        StringBuilder sb = new StringBuilder();
        sb.append(name).append("  [").append(kindLabel()).append("]");
        if (mcVersion != null) {
            sb.append("  Minecraft ").append(mcVersion);
        }
        if (!"vanilla".equals(loader)) {
            sb.append(" / ").append(loader);
            if (loaderVersion != null && !loaderVersion.isBlank()) sb.append(" ").append(loaderVersion);
        }
        if (declaredFiles > 0) sb.append("  ·  ").append(declaredFiles).append(" 个文件");
        return sb.toString();
    }

    public String kindLabel() {
        return switch (kind) {
            case MODRINTH -> "Modrinth .mrpack";
            case CURSEFORGE -> "CurseForge";
            case PLAIN -> "普通整合包压缩包";
            case INSTANCE_DIR -> "已解压的实例目录";
            case UNKNOWN -> "未识别";
        };
    }

    /** 识别一个文件 / 目录；认不出来时返回 kind = UNKNOWN 而不是抛异常。 */
    public static ModpackFile detect(Path p) {
        String baseName = p.getFileName() == null ? "modpack" : p.getFileName().toString();
        try {
            if (Files.isDirectory(p)) {
                return detectDirectory(p, baseName);
            }
            if (!Files.isRegularFile(p)) {
                return unknown(p, baseName, "文件不存在");
            }
            return detectZip(p, baseName);
        } catch (Exception e) {
            return unknown(p, baseName, e.getMessage());
        }
    }

    /** 是否是「可能装着整合包」的文件（给拖拽用，不读内容，只看扩展名）。 */
    public static boolean maybeModpackFile(Path p) {
        if (Files.isDirectory(p)) return true;
        String n = p.getFileName() == null ? "" : p.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
        return n.endsWith(".mrpack") || n.endsWith(".zip") || n.endsWith(".mcpack")
                || n.endsWith(".minecraft") || n.endsWith(".jar");
    }

    // ---------- 目录 ----------

    private static ModpackFile detectDirectory(Path dir, String baseName) throws Exception {
        String name = baseName;
        // Prism / MultiMC 实例：mmc-pack.json + .minecraft/
        Path mmc = dir.resolve("mmc-pack.json");
        if (Files.isRegularFile(mmc)) {
            String[] fromMmc = parseMmcPack(Files.readString(mmc, StandardCharsets.UTF_8));
            return new ModpackFile(dir, Kind.INSTANCE_DIR, name, fromMmc[0], fromMmc[1], fromMmc[2], 0,
                    "Prism/MultiMC 实例目录");
        }
        // 已经解压开的 Modrinth / CurseForge 包
        if (Files.isRegularFile(dir.resolve("modrinth.index.json"))) {
            Info mi = parseModrinthIndex(Files.readString(dir.resolve("modrinth.index.json"), StandardCharsets.UTF_8));
            return new ModpackFile(dir, Kind.MODRINTH, mi.name != null ? mi.name : name,
                    mi.mcVersion, mi.loader, mi.loaderVersion, mi.fileCount, null);
        }
        if (Files.isRegularFile(dir.resolve("manifest.json"))) {
            Info mi = parseCurseManifest(Files.readString(dir.resolve("manifest.json"), StandardCharsets.UTF_8), name);
            return new ModpackFile(dir, Kind.CURSEFORGE, mi.name != null ? mi.name : name,
                    mi.mcVersion, mi.loader, mi.loaderVersion, mi.fileCount, cfNote(mi.fileCount));
        }
        // 普通已解压目录：有 mods/config/options.txt 之类就算
        if (Files.isDirectory(dir.resolve("mods")) || Files.isDirectory(dir.resolve("config"))
                || Files.isRegularFile(dir.resolve("options.txt"))) {
            return new ModpackFile(dir, Kind.PLAIN, name, null, "vanilla", null, 0,
                    "认不出 MC / 加载器版本，只能把内容解压到实例目录");
        }
        return unknown(dir, name, "目录里没有整合包特征文件");
    }

    // ---------- zip ----------

    private static ModpackFile detectZip(Path zip, String baseName) throws Exception {
        String name = stripExt(baseName);
        try (ZipFile zf = new ZipFile(zip.toFile())) {
            // 很多人会把整合包「再压一层目录」（MyPack/xxx/mods/…），先算出这层公共前缀
            String prefix = commonRootPrefix(zf);
            boolean hasMods = false;
            boolean hasPlainContent = false;
            for (Enumeration<? extends ZipEntry> en = zf.entries(); en.hasMoreElements(); ) {
                ZipEntry e = en.nextElement();
                String n = e.getName();
                String rel = stripPrefix(n, prefix);
                if (rel.equals("modrinth.index.json") || rel.equals("overrides/modrinth.index.json")) {
                    Info mi = parseModrinthIndex(read(zf, e));
                    return new ModpackFile(zip, Kind.MODRINTH, mi.name != null ? mi.name : name,
                            mi.mcVersion, mi.loader, mi.loaderVersion, mi.fileCount, null, prefix);
                }
            }
            ZipEntry manifest = zf.getEntry(prefix + "manifest.json");
            if (manifest != null) {
                Info mi = parseCurseManifest(read(zf, manifest), name);
                return new ModpackFile(zip, Kind.CURSEFORGE, mi.name != null ? mi.name : name,
                        mi.mcVersion, mi.loader, mi.loaderVersion, mi.fileCount,
                        cfNote(mi.fileCount), prefix);
            }
            for (Enumeration<? extends ZipEntry> en = zf.entries(); en.hasMoreElements(); ) {
                ZipEntry e = en.nextElement();
                String n = stripPrefix(e.getName(), prefix);
                if (n.startsWith("mods/") || n.startsWith("overrides/mods/")) hasMods = true;
                if (n.equals("options.txt") || n.startsWith("config/") || n.startsWith("overrides/config/")
                        || n.equals("instance.cfg") || n.startsWith(".minecraft/")) {
                    hasPlainContent = true;
                }
            }
            if (hasMods || hasPlainContent) {
                return new ModpackFile(zip, Kind.PLAIN, name, null, "vanilla", null, 0,
                        "认不出 MC / 加载器版本，只能把内容解压到实例目录", prefix);
            }
            return unknown(zip, name, "压缩包里没有 modrinth.index.json / manifest.json / mods 目录");
        } catch (java.util.zip.ZipException ze) {
            return unknown(zip, name, "不是有效的 zip（整合包需要是 .mrpack 或 zip 压缩包）");
        }
    }

    /**
     * 压缩包内所有文件都套在同一个目录下时返回该前缀（带结尾的 {@code /}），否则返回空串。
     * 支持多层（解压再压缩有时会套两层），最多剥 4 层。
     */
    static String commonRootPrefix(ZipFile zf) {
        List<String> names = new ArrayList<>();
        for (Enumeration<? extends ZipEntry> en = zf.entries(); en.hasMoreElements(); ) {
            String n = en.nextElement().getName().replace('\\', '/');
            if (!n.endsWith("/") && !n.isEmpty()) names.add(n);
        }
        if (names.isEmpty()) return "";
        String prefix = "";
        for (int depth = 0; depth < 4; depth++) {
            String root = null;
            boolean same = true;
            for (String n : names) {
                if (!n.startsWith(prefix)) {
                    same = false;
                    break;
                }
                String rest = n.substring(prefix.length());
                int slash = rest.indexOf('/');
                if (slash < 0) {          // 这一层直接有文件 → 不能再剥
                    same = false;
                    break;
                }
                String top = rest.substring(0, slash);
                if (root == null) root = top;
                else if (!root.equals(top)) {
                    same = false;
                    break;
                }
            }
            if (!same || root == null) break;
            prefix = prefix + root + "/";
        }
        return prefix;
    }

    /** 去掉前缀（前缀为空时原样返回）。 */
    static String stripPrefix(String name, String prefix) {
        String n = name.replace('\\', '/');
        if (prefix == null || prefix.isEmpty()) return n;
        return n.startsWith(prefix) ? n.substring(prefix.length()) : n;
    }

    /**
     * 按「文件名」找 zip 条目：不管它套了几层目录、条目名用的是 {@code /} 还是 {@code \}。
     * 以前用 {@code getEntry(前缀 + 名字)} 拼路径查，遇到反斜杠条目或多层目录就查不到，
     * 报错是「整合包缺少 modrinth.index.json」这种让人摸不着头脑的话。
     */
    static ZipEntry findEntry(ZipFile zf, String fileName) {
        ZipEntry loose = null;
        for (Enumeration<? extends ZipEntry> en = zf.entries(); en.hasMoreElements(); ) {
            ZipEntry e = en.nextElement();
            if (e.isDirectory()) continue;
            String n = e.getName().replace('\\', '/');
            if (n.equals(fileName)) return e;                       // 根目录优先级最高
            if (n.endsWith("/" + fileName) && loose == null) loose = e;
        }
        return loose;
    }

    // ---------- 解析 ----------

    /** 内部临时结构。 */
    static final class Info {
        String name;
        String mcVersion;
        String loader;
        String loaderVersion;
        int fileCount;
    }

    static Info parseModrinthIndex(String json) {
        Info info = new Info();
        JsonObject index = JsonParser.parseString(json).getAsJsonObject();
        info.name = index.has("name") ? index.get("name").getAsString() : null;
        JsonObject deps = index.has("dependencies") ? index.getAsJsonObject("dependencies") : null;
        if (deps != null) {
            if (deps.has("minecraft")) info.mcVersion = deps.get("minecraft").getAsString();
            if (deps.has("fabric-loader")) {
                info.loader = "fabric";
                info.loaderVersion = deps.get("fabric-loader").getAsString();
            } else if (deps.has("neoforge")) {
                info.loader = "neoforge";
                info.loaderVersion = deps.get("neoforge").getAsString();
            } else if (deps.has("forge")) {
                info.loader = "forge";
                info.loaderVersion = deps.get("forge").getAsString();
            } else if (deps.has("quilt-loader")) {
                info.loader = "quilt";
                info.loaderVersion = deps.get("quilt-loader").getAsString();
            }
        }
        if (info.loader == null) info.loader = "vanilla";
        if (index.has("files") && index.get("files").isJsonArray()) {
            info.fileCount = index.getAsJsonArray("files").size();
        }
        return info;
    }

    private static Info parseCurseManifest(String json, String fallbackName) {
        Info info = new Info();
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        info.name = root.has("name") ? root.get("name").getAsString() : fallbackName;
        if (root.has("minecraft")) {
            JsonObject mc = root.getAsJsonObject("minecraft");
            if (mc.has("version")) info.mcVersion = mc.get("version").getAsString();
            if (mc.has("modLoaders")) {
                JsonArray arr = mc.getAsJsonArray("modLoaders");
                for (JsonElement le : arr) {
                    JsonObject lo = le.getAsJsonObject();
                    if (!lo.has("id")) continue;
                    String id = lo.get("id").getAsString();
                    boolean primary = !lo.has("primary") || lo.get("primary").getAsBoolean();
                    if (!primary) continue;
                    parseLoaderId(id, info);
                    break;
                }
                if (info.loader == null && !arr.isEmpty()) {
                    parseLoaderId(arr.get(0).getAsJsonObject().get("id").getAsString(), info);
                }
            }
        }
        if (info.loader == null) info.loader = "vanilla";
        if (root.has("files") && root.get("files").isJsonArray()) {
            info.fileCount = root.getAsJsonArray("files").size();
        }
        return info;
    }

    /** CurseForge 的 loader id 形如 forge-47.2.0 / fabric-0.15.11 / neoforge-21.1.264 / quilt-0.23.1。 */
    private static void parseLoaderId(String id, Info info) {
        if (id == null) return;
        String lower = id.toLowerCase(java.util.Locale.ROOT);
        int dash = lower.indexOf('-');
        if (dash <= 0) return;
        String kind = lower.substring(0, dash);
        String ver = id.substring(dash + 1);
        switch (kind) {
            case "forge" -> {
                info.loader = "forge";
                info.loaderVersion = ver;
            }
            case "neoforge" -> {
                info.loader = "neoforge";
                info.loaderVersion = ver;
            }
            case "fabric" -> {
                info.loader = "fabric";
                info.loaderVersion = ver;
            }
            case "quilt" -> {
                info.loader = "quilt";
                info.loaderVersion = ver;
            }
            default -> {
            }
        }
    }

    /** Prism / MultiMC 的 mmc-pack.json。 */
    private static String[] parseMmcPack(String json) {
        String mc = null;
        String loader = "vanilla";
        String loaderVer = null;
        try {
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            if (root.has("components")) {
                for (JsonElement e : root.getAsJsonArray("components")) {
                    JsonObject c = e.getAsJsonObject();
                    String uid = c.has("uid") ? c.get("uid").getAsString() : "";
                    String ver = c.has("version") ? c.get("version").getAsString() : null;
                    String cached = c.has("cachedVersion") ? c.get("cachedVersion").getAsString() : null;
                    String v = ver != null ? ver : cached;
                    switch (uid) {
                        case "net.minecraft" -> mc = v;
                        case "net.fabricmc.fabric-loader" -> {
                            loader = "fabric";
                            loaderVer = v;
                        }
                        case "org.quiltmc.quilt-loader" -> {
                            loader = "quilt";
                            loaderVer = v;
                        }
                        case "net.minecraftforge" -> {
                            loader = "forge";
                            loaderVer = v;
                        }
                        case "net.neoforged" -> {
                            loader = "neoforge";
                            loaderVer = v;
                        }
                        default -> {
                        }
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return new String[]{mc, loader, loaderVer};
    }

    static String cfNote(int fileCount) {
        if (fileCount <= 0) return null;
        return "CurseForge 整合包清单里的 " + fileCount + " 个 mod 会通过国内镜像逐个下载到 mods/；"
                + "个别作者关闭了第三方分发或镜像查不到的文件会列在安装日志里，可手动下载后放进 mods/";
    }

    private static String read(ZipFile zf, ZipEntry e) throws IOException {
        try (InputStream in = zf.getInputStream(e)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    static List<String> listNames(ZipFile zf) {
        List<String> out = new ArrayList<>();
        for (Enumeration<? extends ZipEntry> en = zf.entries(); en.hasMoreElements(); ) {
            out.add(en.nextElement().getName());
        }
        return out;
    }

    private static String stripExt(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private static ModpackFile unknown(Path p, String name, String why) {
        return new ModpackFile(p, Kind.UNKNOWN, stripExt(name), null, "vanilla", null, 0, why);
    }
}
