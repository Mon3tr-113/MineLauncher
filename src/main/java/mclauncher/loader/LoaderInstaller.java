package mclauncher.loader;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import mclauncher.config.Config;
import mclauncher.minecraft.Library;
import mclauncher.minecraft.VersionInstaller;
import mclauncher.minecraft.VersionManifest;
import mclauncher.net.DownloadManager;
import mclauncher.net.Http;
import mclauncher.net.Mirror;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 自动安装 MOD 加载器：Fabric（meta API）、Forge（安装器子进程）。 */
public final class LoaderInstaller {

    public static final String FABRIC_META = "https://meta.fabricmc.net/v2";
    private static final String FORGE_MAVEN_METADATA =
            "https://maven.minecraftforge.net/net/minecraftforge/forge/maven-metadata.xml";
    private static final String NEOFORGE_MAVEN_METADATA =
            "https://maven.neoforged.net/releases/net/neoforged/neoforge/maven-metadata.xml";
    private static final String BMCLAPI_MAVEN = "https://bmclapi2.bangbang93.com/maven/";

    private LoaderInstaller() {
    }

    // ---------- Fabric ----------

    public static List<String> fabricLoaderVersions() throws Exception {
        String json = Http.getString(FABRIC_META + "/versions/loader");
        return parseVersionList(json, false);
    }

    public static List<String> fabricGameVersions() throws Exception {
        String json = Http.getString(FABRIC_META + "/versions/game");
        return parseVersionList(json, true);
    }

    private static List<String> parseVersionList(String json, boolean stableOnly) {
        List<String> out = new ArrayList<>();
        JsonArray arr = JsonParser.parseString(json).getAsJsonArray();
        for (JsonElement e : arr) {
            JsonObject o = e.getAsJsonObject();
            boolean stable = o.has("stable") && o.get("stable").getAsBoolean();
            if (stableOnly && !stable) continue;
            out.add(o.get("version").getAsString());
        }
        return out;
    }

    /** 安装 Fabric：拉取 loader profile JSON，当作一个继承原版的版本安装。 */
    public static String installFabric(String mcVersion, String loaderVersion, Config cfg, VersionInstaller.Progress progress) throws Exception {
        String url = FABRIC_META + "/versions/loader/" + mcVersion + "/" + loaderVersion + "/profile/json";
        String json = Http.getString(url);
        JsonObject o = JsonParser.parseString(json).getAsJsonObject();
        String id = o.get("id").getAsString();

        if (progress != null) progress.on("正在准备 Fabric " + loaderVersion + " ...", 0, 0);
        VersionManifest manifest = VersionManifest.fetch();
        new VersionInstaller(cfg).installFromJson(id, json, manifest, progress);
        mclauncher.minecraft.LibraryChecker.verifyAfterInstall(id, cfg, msg -> {
            if (progress != null) progress.on(msg, 0, 0);
        });
        return id;
    }

    // ---------- Forge ----------

    private static volatile String cachedForgeXml;
    private static volatile List<String> cachedMcIds;

    private static String forgeXml() throws Exception {
        if (cachedForgeXml == null) {
            cachedForgeXml = Http.getString(FORGE_MAVEN_METADATA);
        }
        return cachedForgeXml;
    }

    private static List<String> mcIds() throws Exception {
        if (cachedMcIds == null) {
            List<String> ids = new ArrayList<>();
            for (VersionManifest.VersionInfo v : VersionManifest.fetch().getVersions()) {
                ids.add(v.id);
            }
            cachedMcIds = ids;
        }
        return cachedMcIds;
    }

    /** 返回有 Forge 构建的 MC 版本列表（倒序）。 */
    public static List<String> forgeMcVersions() throws Exception {
        String xml = forgeXml();
        List<String> ids = mcIds();
        java.util.Set<String> prefixes = new java.util.TreeSet<>();
        Matcher m = Pattern.compile("<version>([^<]+)</version>").matcher(xml);
        while (m.find()) {
            String mc = matchMcVersion(m.group(1), ids);
            if (mc != null) prefixes.add(mc);
        }
        List<String> out = new ArrayList<>(prefixes);
        out.sort((a, b) -> compareVersions(b, a));
        return out;
    }

    private static String matchMcVersion(String forgeVersion, List<String> mcIds) {
        String best = null;
        for (String mc : mcIds) {
            if (forgeVersion.startsWith(mc + "-")) {
                if (best == null || mc.length() > best.length()) best = mc;
            }
        }
        return best;
    }

    /** 返回某 MC 版本可用的 Forge 版本号（不含 MC 前缀，倒序）。 */
    public static List<String> forgeVersions(String mcVersion) throws Exception {
        String xml = forgeXml();
        List<String> out = new ArrayList<>();
        Matcher m = Pattern.compile("<version>([^<]+)</version>").matcher(xml);
        String prefix = mcVersion + "-";
        while (m.find()) {
            String v = m.group(1);
            if (v.startsWith(prefix)) {
                out.add(v.substring(prefix.length()));
            }
        }
        out.sort((a, b) -> compareVersions(b, a)); // 倒序
        return out;
    }

    /** 安装 Forge：确保原版已装 -> 下载安装器 -> 运行 --installClient。 */
    public static String installForge(String mcVersion, String forgeVersion, Config cfg, VersionInstaller.Progress progress) throws Exception {
        // 1) 确保原版已安装
        Path baseJson = cfg.versionsDir().resolve(mcVersion).resolve(mcVersion + ".json");
        if (!Files.exists(baseJson)) {
            if (progress != null) progress.on("先安装原版 " + mcVersion, 0, 0);
            new VersionInstaller(cfg).install(mcVersion, progress);
        }

        // 2) 下载安装器
        String artifact = mcVersion + "-" + forgeVersion;
        String path = "net/minecraftforge/forge/" + artifact + "/forge-" + artifact + "-installer.jar";
        String installerUrl = Mirror.map("https://maven.minecraftforge.net/" + path);
        Path installer = cfg.librariesDir().resolve("..").normalize()
                .resolve("installers").resolve("forge-" + artifact + "-installer.jar");
        Files.createDirectories(installer.getParent());
        if (progress != null) progress.on("正在下载 Forge 安装器", 0, 0);
        Http.download(installerUrl, installer, null);

        // 3) 运行安装器
        ensureLauncherProfiles(cfg);
        if (progress != null) progress.on("正在运行 Forge 安装器（需要一点时间）", 0, 0);
        List<String> cmd = new ArrayList<>();
        cmd.add(cfg.javaPath);
        cmd.add("-jar");
        cmd.add(installer.toString());
        cmd.add("--installClient");
        cmd.add(cfg.gamePath().toString());
        if (cfg.useMirror) {
            cmd.add("--mirror");
            cmd.add(BMCLAPI_MAVEN);
        }
        runProcess(cmd, cfg.gamePath().toFile(), line -> {
            if (progress != null) progress.on(line.length() > 120 ? line.substring(0, 120) : line, 0, 0);
        });
        String id = mcVersion + "-forge-" + forgeVersion;
        // 安装器只把打补丁的客户端放在 libraries 里，versions/<id>/<id>.jar 得我们自己补，
        // 否则启动会在模块解析阶段崩（见 LibraryChecker.ensureClientJar 的说明）
        mclauncher.minecraft.LibraryChecker.verifyAfterInstall(id, cfg,
                msg -> {
                    if (progress != null) progress.on(msg, 0, 0);
                });
        return id;
    }

    // ---------- NeoForge ----------

    private static volatile String cachedNeoForgeXml;

    private static String neoForgeXml() throws Exception {
        if (cachedNeoForgeXml == null) {
            // 直连 maven.neoforged.net 在国内/本环境常超时，优先走 BMCLAPI 镜像
            cachedNeoForgeXml = Http.getString(Mirror.map(NEOFORGE_MAVEN_METADATA));
        }
        return cachedNeoForgeXml;
    }

    /** MC 版本 -> NeoForge 版本前缀（1.20.2 -> 20.2，1.21 -> 21.0，26.3 -> 26.3）。 */
    private static String neoForgeMcPrefix(String mc) {
        if (mc == null || mc.isBlank()) return "";
        if (mc.startsWith("1.")) {
            String rest = mc.substring(2);
            return rest.indexOf('.') < 0 ? rest + ".0" : rest;
        }
        return mc;
    }

    private static boolean neoForgeStable(String v) {
        String l = v.toLowerCase();
        return !l.contains("alpha") && !l.contains("beta") && !l.contains("snapshot") && !l.contains("pre");
    }

    /** 返回某 MC 版本可用的 NeoForge 版本（倒序）。 */
    public static List<String> neoForgeVersions(String mcVersion) throws Exception {
        String xml = neoForgeXml();
        String prefix = neoForgeMcPrefix(mcVersion);
        List<String> out = new ArrayList<>();
        Matcher m = Pattern.compile("<version>([^<]+)</version>").matcher(xml);
        while (m.find()) {
            String v = m.group(1);
            if (!neoForgeStable(v)) continue;
            if (v.equals(prefix) || v.startsWith(prefix + ".")) out.add(v);
        }
        out.sort((a, b) -> compareVersions(b, a));
        return out;
    }

    private static class InstallerMeta {
        String versionId;
        String mcVersion;
        final java.util.List<JsonObject> libraries = new ArrayList<>();
    }

    /** 从安装器 jar 内读取 version.json 的 id / inheritsFrom、install_profile 的 minecraft，以及两处 libraries。 */
    private static InstallerMeta readInstallerMeta(Path installer) throws Exception {
        InstallerMeta meta = new InstallerMeta();
        try (java.util.zip.ZipInputStream zin = new java.util.zip.ZipInputStream(Files.newInputStream(installer))) {
            java.util.zip.ZipEntry e;
            while ((e = zin.getNextEntry()) != null) {
                String name = e.getName();
                if (!name.equals("version.json") && !name.equals("install_profile.json")) continue;
                String content = new String(zin.readAllBytes(), StandardCharsets.UTF_8);
                JsonObject o = JsonParser.parseString(content).getAsJsonObject();
                if (name.equals("version.json")) {
                    if (o.has("id")) meta.versionId = o.get("id").getAsString();
                    if (o.has("inheritsFrom")) meta.mcVersion = o.get("inheritsFrom").getAsString();
                } else {
                    if (o.has("minecraft")) meta.mcVersion = o.get("minecraft").getAsString();
                }
                if (o.has("libraries")) {
                    for (JsonElement le : o.getAsJsonArray("libraries")) {
                        meta.libraries.add(le.getAsJsonObject());
                    }
                }
            }
        }
        return meta;
    }

    /** 经 BMCLAPI 预下载安装器的依赖库，避免安装器直连 maven.neoforged.net 超时。 */
    private static void prefetchLibraries(java.util.List<JsonObject> libs, Config cfg,
                                          VersionInstaller.Progress progress) throws Exception {
        java.util.List<DownloadManager.FileSpec> specs = new ArrayList<>();
        java.util.Set<Path> seen = new java.util.LinkedHashSet<>();
        for (JsonObject o : libs) {
            if (!o.has("name")) continue;
            String name = o.get("name").getAsString();
            String url = o.has("url") ? o.get("url").getAsString() : null;
            Library lib;
            try {
                lib = new Library(name, url, o);
            } catch (IllegalArgumentException e) {
                continue;
            }
            // 名字里带分类器（:client / :universal / :extra）时，地址和落盘路径都得带分类器
            String cls = lib.classifierInName();
            String u = lib.downloadUrlFor(cls);
            if (u == null || u.isBlank()) continue;
            Path p = cfg.librariesDir().resolve(lib.downloadPathFor(cls));
            if (!seen.add(p)) continue;
            specs.add(new DownloadManager.FileSpec(Mirror.map(u), p,
                    lib.downloadSizeFor(cls), lib.downloadSha1For(cls)));
        }
        if (specs.isEmpty()) return;
        if (progress != null) progress.on("正在预下载 NeoForge 依赖库", 0, 0);
        try {
            DownloadManager.get().downloadAll("NeoForge 依赖库", specs, cfg.downloadThreads, (done, total) -> {
                if (progress != null) progress.on("正在预下载 NeoForge 依赖库", done, total);
            });
        } catch (Exception e) {
            // 预下载失败不致命：交给安装器自己再尝试下载缺失项
            if (progress != null) progress.on("预下载部分依赖失败（安装器会自行重试）", 0, 0);
        }
    }

    /**
     * 安装 NeoForge：下载官方安装器并用 --installClient 安装（安装器内部即执行 processor 流程）。
     * 注意：并非所有 NeoForge 安装器都提供 --installClient，不支持的版本会给出清晰报错。
     */
    /**
     * 安装 NeoForge：优先手写执行 install_profile 的 processor 流程（全部经 BMCLAPI 下载，
     * 不依赖官方安装器），失败时回退到官方安装器 --installClient。
     */
    public static String installNeoForge(String mcVersion, String neoforgeVersion, Config cfg,
                                         VersionInstaller.Progress progress) throws Exception {
        try {
            return NeoForgeInstaller.install(mcVersion, neoforgeVersion, cfg, progress);
        } catch (Exception manualEx) {
            if (progress != null) {
                progress.on("手动 processor 安装失败，回退官方安装器：" + manualEx.getMessage(), 0, 0);
            }
            return installNeoForgeViaInstaller(mcVersion, neoforgeVersion, cfg, progress);
        }
    }

    /** 官方安装器 --installClient 方式（回退路径）。 */
    private static String installNeoForgeViaInstaller(String mcVersion, String neoforgeVersion, Config cfg,
                                                      VersionInstaller.Progress progress) throws Exception {
        String path = "net/neoforged/neoforge/" + neoforgeVersion + "/neoforge-" + neoforgeVersion + "-installer.jar";
        String installerUrl = Mirror.map("https://maven.neoforged.net/releases/" + path);
        Path installer = cfg.librariesDir().resolve("..").normalize()
                .resolve("installers").resolve("neoforge-" + neoforgeVersion + "-installer.jar");
        Files.createDirectories(installer.getParent());
        if (progress != null) progress.on("正在下载 NeoForge 安装器", 0, 0);
        Http.download(installerUrl, installer, null);

        InstallerMeta meta = readInstallerMeta(installer);
        String realMc = meta.mcVersion != null ? meta.mcVersion : mcVersion;
        Path baseJson = cfg.versionsDir().resolve(realMc).resolve(realMc + ".json");
        if (!Files.exists(baseJson)) {
            if (progress != null) progress.on("先安装原版 " + realMc, 0, 0);
            new VersionInstaller(cfg).install(realMc, progress);
        }

        prefetchLibraries(meta.libraries, cfg, progress);

        if (progress != null) progress.on("正在运行 NeoForge 安装器（需要一点时间）", 0, 0);
        ensureLauncherProfiles(cfg);
        List<String> cmd = new ArrayList<>();
        cmd.add(cfg.javaPath);
        cmd.add("-jar");
        cmd.add(installer.toString());
        cmd.add("--installClient");
        cmd.add(cfg.gamePath().toString());
        if (cfg.useMirror) {
            cmd.add("--mirror");
            cmd.add(BMCLAPI_MAVEN);
        }
        runProcess(cmd, cfg.gamePath().toFile(), line -> {
            if (progress != null) progress.on(line.length() > 120 ? line.substring(0, 120) : line, 0, 0);
        });
        return meta.versionId != null ? meta.versionId : "neoforge-" + neoforgeVersion;
    }

    /** 部分 Forge/NeoForge 安装器要求 .minecraft 里存在 launcher_profiles.json，否则拒绝安装。 */
    private static void ensureLauncherProfiles(Config cfg) throws IOException {
        Path dir = cfg.gamePath();
        Files.createDirectories(dir);
        Path f = dir.resolve("launcher_profiles.json");
        if (!Files.exists(f)) {
            Files.writeString(f, "{\"profiles\":{},\"settings\":{},\"version\":3}",
                    StandardCharsets.UTF_8);
        }
    }

    // ---------- Quilt ----------

    private static final String QUILT_META = "https://meta.quiltmc.org/v3";

    public static List<String> quiltLoaderVersions() throws Exception {
        String json = Http.getString(QUILT_META + "/versions/loader");
        List<String> out = new ArrayList<>();
        for (JsonElement e : JsonParser.parseString(json).getAsJsonArray()) {
            out.add(e.getAsJsonObject().get("version").getAsString());
        }
        return out;
    }

    public static String installQuilt(String mcVersion, String loaderVersion, Config cfg,
                                      VersionInstaller.Progress progress) throws Exception {
        String url = QUILT_META + "/versions/loader/" + mcVersion + "/" + loaderVersion + "/profile/json";
        String json = Http.getString(url);
        JsonObject o = JsonParser.parseString(json).getAsJsonObject();
        String id = o.get("id").getAsString();
        if (progress != null) progress.on("正在准备 Quilt " + loaderVersion + " ...", 0, 0);
        VersionManifest manifest = VersionManifest.fetch();
        new VersionInstaller(cfg).installFromJson(id, json, manifest, progress);
        mclauncher.minecraft.LibraryChecker.verifyAfterInstall(id, cfg, msg -> {
            if (progress != null) progress.on(msg, 0, 0);
        });
        return id;
    }

    // ---------- OptiFine ----------

    /** 一个 OptiFine 版本（type + patch）。 */
    public static class OptiFineVersion {
        public final String type;
        public final String patch;
        public final String filename;

        public OptiFineVersion(String type, String patch, String filename) {
            this.type = type;
            this.patch = patch;
            this.filename = filename;
        }

        @Override
        public String toString() {
            return type + " " + patch;
        }
    }

    public static List<OptiFineVersion> optifineVersions(String mcVersion) throws Exception {
        String url = "https://bmclapi2.bangbang93.com/optifine/" + mcVersion;
        String json = Http.getString(url);
        List<OptiFineVersion> out = new ArrayList<>();
        for (JsonElement e : JsonParser.parseString(json).getAsJsonArray()) {
            JsonObject o = e.getAsJsonObject();
            String filename = o.get("filename").getAsString();
            if (filename.toLowerCase().contains("preview")) continue; // 跳过预览版
            out.add(new OptiFineVersion(o.get("type").getAsString(), o.get("patch").getAsString(), filename));
        }
        return out;
    }

    /**
     * 下载 OptiFine（作为 mod）。OptiFine 需要 Forge 加载器才能生效；
     * 独立版 OptiFine 需要官方图形安装器，本方法只负责把 jar 放到 mods/。
     */
    public static String installOptiFine(String mcVersion, OptiFineVersion v, Config cfg,
                                         VersionInstaller.Progress progress) throws Exception {
        String url = "https://bmclapi2.bangbang93.com/optifine/" + mcVersion + "/" + v.type + "/" + v.patch;
        Path mods = cfg.modsDir();
        Files.createDirectories(mods);
        Path target = mods.resolve(v.filename);
        if (progress != null) progress.on("正在下载 " + v.filename, 0, 0);
        Http.download(url, target, null);
        if (progress != null) progress.on("OptiFine 已下载到 mods/（需 Forge 加载器生效）", 0, 0);
        return v.filename;
    }

    private static void runProcess(List<String> cmd, java.io.File workDir, Consumer<String> log) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(workDir);
        pb.redirectErrorStream(true);
        Process p = pb.start();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.isBlank()) log.accept(line);
            }
        }
        int code = p.waitFor();
        if (code != 0) {
            throw new IOException("安装器退出码 " + code + "，安装失败");
        }
    }

    private static int compareVersions(String a, String b) {
        String[] pa = a.split("[.\\-+]");
        String[] pb = b.split("[.\\-+]");
        int n = Math.max(pa.length, pb.length);
        for (int i = 0; i < n; i++) {
            int x = i < pa.length ? num(pa[i]) : 0;
            int y = i < pb.length ? num(pb[i]) : 0;
            if (x != y) return Integer.compare(x, y);
        }
        return 0;
    }

    private static int num(String s) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
