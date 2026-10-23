package mclauncher.modpack;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import mclauncher.config.Config;
import mclauncher.loader.LoaderInstaller;
import mclauncher.minecraft.VersionInstaller;
import mclauncher.net.Http;
import mclauncher.net.Mirror;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 安装整合包：支持 Modrinth {@code .mrpack}、CurseForge 导出的 zip、普通整合包 zip，
 * 以及已经解压好的实例目录（Prism / MultiMC / HMCL 那种）。
 *
 * <p>关键点（以前踩过的坑）：
 * <ul>
 *   <li>.mrpack / CurseForge 的配置文件放在 {@code overrides/}（另有 {@code client-overrides/}）下，
 *       解压时必须把这一层前缀去掉，否则 mod 会被放成 {@code instances/<包名>/overrides/mods/xxx.jar}，
 *       游戏里一个 mod 都加载不到；</li>
 *   <li>NeoForge 整合包要真的装 NeoForge 加载器（以前落到 default 分支只装了原版）；</li>
 *   <li>装完在实例目录写一份 {@code .mclauncher-modpack.json} 标记，
 *       这样即使 config.json 丢了/被重置，整合包也能被重新认出来。</li>
 * </ul>
 */
public final class ModpackInstaller {

    /** 实例目录里的整合包标记文件。 */
    public static final String MARKER = ".mclauncher-modpack.json";

    /** 整合包元信息（用于决定要装哪个加载器）。 */
    public static class Info {
        public String name;
        public String mcVersion;
        public String loader;        // vanilla / fabric / forge / neoforge / quilt
        public String loaderVersion;
    }

    private ModpackInstaller() {
    }

    /** 读取 Modrinth 整合包的元信息（保留给旧调用方；新代码用 {@link ModpackFile#detect}）。 */
    public static Info readInfo(Path mrpackFile) throws Exception {
        ModpackFile mp = ModpackFile.detect(mrpackFile);
        if (mp.kind == ModpackFile.Kind.UNKNOWN) {
            throw new IOException("无法识别整合包：" + (mp.note == null ? mrpackFile : mp.note));
        }
        Info info = new Info();
        info.name = mp.name;
        info.mcVersion = mp.mcVersion;
        info.loader = mp.loader;
        info.loaderVersion = mp.loaderVersion;
        return info;
    }

    /** 只安装整合包内容（不装 MC / 加载器）。 */
    public static void install(Path pack, Path instanceDir, Consumer<String> log) throws Exception {
        ModpackFile mp = ModpackFile.detect(pack);
        Files.createDirectories(instanceDir);
        log.accept("[整合包] " + mp.summary());
        if (mp.note != null) log.accept("[整合包] 注意：" + mp.note);
        switch (mp.kind) {
            case MODRINTH -> installModrinth(mp, instanceDir, log);
            case CURSEFORGE -> installCurseForge(mp, instanceDir, log);
            case PLAIN -> {
                if (Files.isDirectory(mp.path)) {
                    copyTree(mp.path, instanceDir, log);
                } else {
                    extractZip(mp, instanceDir, log);
                }
            }
            case INSTANCE_DIR -> copyTree(mp.path, instanceDir, log);
            default -> throw new IOException("无法识别的整合包文件：" + mp.path
                    + (mp.note == null ? "" : "（" + mp.note + "）"));
        }
    }

    /**
     * 完整安装一个整合包：读取元信息 → 装好对应 MC+加载器 → 下载文件 + 解压 overrides → 注册实例。
     *
     * @return 整合包实例名
     */
    public static String installFull(Path pack, Config cfg, VersionInstaller.Progress progress,
                                     Consumer<String> log) throws Exception {
        ModpackFile mp = ModpackFile.detect(pack);
        String name = safeName(mp.name);
        Path instanceDir = cfg.instanceDir(name);
        if (mp.kind == ModpackFile.Kind.UNKNOWN) {
            throw new IOException("无法识别整合包：" + (mp.note == null ? pack.toString() : mp.note));
        }
        if (mp.mcVersion == null || mp.mcVersion.isBlank()) {
            throw new IOException("这个整合包没写 Minecraft 版本（" + mp.kindLabel() + "），"
                    + "请换成 .mrpack / CurseForge 整合包，或把内容解压到已装版本的实例目录里");
        }
        String versionId = ensureLoader(mp, cfg, progress, log);
        install(pack, instanceDir, log);
        // 装完立刻核对：版本 jar（FML 的补丁客户端）+ 依赖库，问题在安装阶段就暴露
        mclauncher.minecraft.LibraryChecker.verifyAfterInstall(versionId, cfg, log);
        cfg.upsertModpack(name, versionId, mp.loader);
        writeMarker(cfg, name, versionId, mp.loader, mp.mcVersion);
        return name;
    }

    /** 按整合包要求装好 Minecraft + 加载器，返回可用于启动的版本 id。 */
    public static String ensureLoader(ModpackFile mp, Config cfg, VersionInstaller.Progress progress,
                                      Consumer<String> log) throws Exception {
        String mc = mp.mcVersion;
        String loader = mp.loader == null ? "vanilla" : mp.loader;
        String lv = mp.loaderVersion;
        if (log != null && !"vanilla".equals(loader)) {
            log.accept("[整合包] 需要加载器：" + loader + (lv == null ? "（自动选最新）" : " " + lv));
        }
        switch (loader) {
            case "fabric": {
                if (lv == null || lv.isBlank()) lv = firstOrNull(LoaderInstaller.fabricLoaderVersions());
                requireVersion(lv, "Fabric", mc);
                return LoaderInstaller.installFabric(mc, lv, cfg, progress);
            }
            case "quilt": {
                if (lv == null || lv.isBlank()) lv = firstOrNull(LoaderInstaller.quiltLoaderVersions());
                requireVersion(lv, "Quilt", mc);
                return LoaderInstaller.installQuilt(mc, lv, cfg, progress);
            }
            case "forge": {
                if (lv == null || lv.isBlank()) {
                    List<String> versions = LoaderInstaller.forgeVersions(mc);
                    lv = versions.isEmpty() ? null : versions.get(0);
                }
                requireVersion(lv, "Forge", mc);
                return LoaderInstaller.installForge(mc, lv, cfg, progress);
            }
            case "neoforge": {
                if (lv == null || lv.isBlank()) {
                    List<String> versions = LoaderInstaller.neoForgeVersions(mc);
                    lv = versions.isEmpty() ? null : versions.get(0);
                }
                requireVersion(lv, "NeoForge", mc);
                return LoaderInstaller.installNeoForge(mc, lv, cfg, progress);
            }
            default: {
                new VersionInstaller(cfg).install(mc, progress);
                return mc;
            }
        }
    }

    private static void requireVersion(String loaderVersion, String loaderName, String mcVersion) throws IOException {
        if (loaderVersion == null || loaderVersion.isBlank()) {
            throw new IOException("这个整合包要求的 " + loaderName + " 加载器在 Minecraft " + mcVersion
                    + " 上没有可用版本（整合包没有写加载器版本，列表里也找不到）。"
                    + "可以先在内置的「下载资源 → 版本」里手动装好该加载器，再回到整合包页导入内容。");
        }
    }

    // ---------- 各格式的安装 ----------

    private static void installModrinth(ModpackFile mp, Path instanceDir, Consumer<String> log) throws Exception {
        Path pack = mp.path;
        if (Files.isDirectory(pack)) {
            // 已解压开的 mrpack：直接把内容拷过去（overrides 前缀同样要去掉）
            copyTree(pack, instanceDir, log);
            return;
        }
        // 1) 先解压 overrides / client-overrides（去掉前缀），并跳过清单文件
        try (ZipFile zf = new ZipFile(pack.toFile())) {
            int copied = 0;
            for (java.util.Enumeration<? extends ZipEntry> en = zf.entries(); en.hasMoreElements(); ) {
                ZipEntry e = en.nextElement();
                String n = e.getName();
                if (e.isDirectory() || n.endsWith("/")) continue;
                String rel0 = ModpackFile.stripPrefix(n, mp.rootPrefix);
                if (rel0.equals("modrinth.index.json") || rel0.endsWith("/modrinth.index.json")) continue;
                String rel = stripOverridesPrefix(rel0);
                if (rel == null) continue;
                if (extractEntry(zf, e, instanceDir, rel, log)) copied++;
            }
            if (copied > 0) log.accept("[整合包] 已解压 " + copied + " 个配置/资源文件");

            // 2) 按 files[] 下载模组（env.client == unsupported 的跳过）
            //    按「文件名后缀」找清单，别拼前缀去 getEntry —— zip 条目可能用反斜杠/多套一层
            ZipEntry indexEntry = ModpackFile.findEntry(zf, "modrinth.index.json");
            if (indexEntry == null) throw new IOException("整合包缺少 modrinth.index.json");
            String indexText = new String(zf.getInputStream(indexEntry).readAllBytes(), StandardCharsets.UTF_8);
            JsonObject index = JsonParser.parseString(indexText).getAsJsonObject();
            JsonArray files = index.has("files") ? index.getAsJsonArray("files") : new JsonArray();
            int ok = 0, skipped = 0, failed = 0;
            for (JsonElement fe : files) {
                JsonObject f = fe.getAsJsonObject();
                if (clientUnsupported(f)) {
                    skipped++;
                    continue;
                }
                if (!f.has("path")) continue;
                String rel = f.get("path").getAsString();
                Path target = instanceDir.resolve(rel).normalize();
                if (!target.startsWith(instanceDir)) continue;
                String sha1 = hashOf(f, "sha1");
                if (Files.isRegularFile(target) && (sha1 == null || Http.hashMatches(target, sha1))) {
                    ok++;
                    continue;
                }
                List<String> urls = new ArrayList<>();
                if (f.has("downloads")) {
                    for (JsonElement ue : f.getAsJsonArray("downloads")) {
                        urls.add(Mirror.map(ue.getAsString()));
                    }
                }
                if (urls.isEmpty()) {
                    log.accept("[跳过] " + rel + "（无下载地址）");
                    skipped++;
                    continue;
                }
                Files.createDirectories(target.getParent());
                Exception last = null;
                boolean done = false;
                for (String url : urls) {
                    try {
                        Http.download(url, target, null, f.has("fileSize") ? f.get("fileSize").getAsLong() : -1, sha1);
                        done = true;
                        break;
                    } catch (Exception ex) {
                        last = ex;
                    }
                }
                if (done) {
                    ok++;
                    log.accept("[整合包] 已下载 " + rel);
                } else {
                    failed++;
                    log.accept("[失败] " + rel + "：" + (last == null ? "未知原因" : last.getMessage()));
                }
            }
            log.accept("[整合包] 文件处理完成：下载/已存在 " + ok + "，跳过 " + skipped
                    + (failed > 0 ? "，失败 " + failed : "") + " -> " + instanceDir);
        }
    }

    private static void installCurseForge(ModpackFile mp, Path instanceDir, Consumer<String> log) throws Exception {
        Path pack = mp.path;
        if (Files.isDirectory(pack)) {
            copyTree(pack, instanceDir, log);
            return;
        }
        List<long[]> modFiles = new ArrayList<>();
        int extracted = 0;
        try (ZipFile zf = new ZipFile(pack.toFile())) {
            for (java.util.Enumeration<? extends ZipEntry> en = zf.entries(); en.hasMoreElements(); ) {
                ZipEntry e = en.nextElement();
                String n = e.getName();
                if (e.isDirectory() || n.endsWith("/")) continue;
                // manifest.json 里列出了要下载的 mod（projectID / fileID）
                String rel0 = ModpackFile.stripPrefix(n, mp.rootPrefix);
                if (rel0.equals("manifest.json") || rel0.endsWith("/manifest.json")) {
                    try (InputStream in = zf.getInputStream(e)) {
                        modFiles = readCurseManifestFiles(new String(in.readAllBytes(), StandardCharsets.UTF_8));
                    }
                    continue;
                }
                String rel = stripOverridesPrefix(rel0);
                if (rel == null) continue;
                if (extractEntry(zf, e, instanceDir, rel, log)) extracted++;
            }
        }
        log.accept("[整合包] 已解压 " + extracted + " 个配置/资源文件（overrides 已去掉前缀）");
        downloadCurseForgeMods(modFiles, instanceDir, log);
    }

    /**
     * 解压单个 zip 条目到目标目录（不带任何前缀处理，调用方先算好相对路径）。
     *
     * <p>加了几层保护，避免一个奇怪条目就让整个整合包安装失败：
     * 条目本身是目录 → 跳过；目标位置已经是个目录（包里同路径是文件）→ 跳过；
     * 父路径被同名文件占住 → 记录后跳过；越界（zip slip）→ 跳过。
     *
     * @return true 表示真的写了一个文件
     */
    private static boolean extractEntry(ZipFile zf, ZipEntry e, Path dest, String rel,
                                        Consumer<String> log) {
        if (e == null || rel == null || rel.isBlank()) return false;
        Path out = dest.resolve(rel).normalize();
        if (!out.startsWith(dest)) return false;          // 防 zip slip
        try {
            if (Files.isDirectory(out)) return false;     // 目标是目录：不覆盖
            Files.createDirectories(out.getParent());
            try (InputStream in = zf.getInputStream(e)) {
                Files.copy(in, out, StandardCopyOption.REPLACE_EXISTING);
            }
            return true;
        } catch (Exception ex) {
            if (log != null) {
                log.accept("[跳过] " + rel + "（" + ex.getClass().getSimpleName()
                        + (ex.getMessage() == null ? "" : "：" + ex.getMessage()) + "）");
            }
            return false;
        }
    }

    /**
     * 给「已经装好、但 mod 没下全」的 CurseForge 整合包实例补 mod。
     * 实例目录里有 {@code manifest.json} 就能补（以前这类包只能解压配置、mod 得手动下）。
     *
     * @return 补下/已存在的 mod 数量；不是 CF 包返回 -1
     */
    public static int syncCurseForgeMods(Path instanceDir, Consumer<String> log) throws Exception {
        Path manifest = instanceDir.resolve("manifest.json");
        if (!Files.isRegularFile(manifest)) return -1;
        List<long[]> files = readCurseManifestFiles(Files.readString(manifest, StandardCharsets.UTF_8));
        if (files.isEmpty()) return 0;
        downloadCurseForgeMods(files, instanceDir, log);
        // 返回现在 mods 目录里的文件数，方便界面报个数
        Path mods = instanceDir.resolve("mods");
        if (!Files.isDirectory(mods)) return 0;
        try (Stream<Path> list = Files.list(mods)) {
            return (int) list.filter(Files::isRegularFile).count();
        }
    }

    /** CurseForge 的 manifest.json 里 files[].projectID / fileID。 */
    private static List<long[]> readCurseManifestFiles(String json) {
        List<long[]> out = new ArrayList<>();
        try {
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            if (!root.has("files")) return out;
            for (JsonElement fe : root.getAsJsonArray("files")) {
                JsonObject f = fe.getAsJsonObject();
                if (!f.has("projectID") || !f.has("fileID")) continue;
                out.add(new long[]{f.get("projectID").getAsLong(), f.get("fileID").getAsLong()});
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    /**
     * 按 CurseForge 清单把 mod 一个个下下来（以前只能提示用户手动去 CurseForge 下，
     * 现在走国内镜像查文件名/地址，直接装进 mods/）。
     */
    private static void downloadCurseForgeMods(List<long[]> files, Path instanceDir, Consumer<String> log) {
        if (files.isEmpty()) return;
        Path modsDir = instanceDir.resolve("mods");
        int ok = 0, skip = 0, failed = 0;
        log.accept("[整合包] CurseForge 清单里有 " + files.size() + " 个 mod，开始通过镜像下载...");
        for (long[] mo : files) {
            long projectId = mo[0], fileId = mo[1];
            mclauncher.net.CurseForgeApi.ModFile mf = mclauncher.net.CurseForgeApi.resolve(projectId, fileId);
            if (mf.fileName == null || mf.downloadUrl == null) {
                failed++;
                log.accept("[失败] mod " + projectId + "/" + fileId + "：" + (mf.error == null ? "查不到文件" : mf.error));
                continue;
            }
            try {
                Files.createDirectories(modsDir);
                Path target = modsDir.resolve(mf.fileName);
                if (Files.isRegularFile(target) && (mf.sha1 == null || Http.hashMatches(target, mf.sha1))) {
                    ok++;
                    continue;
                }
                Http.download(mclauncher.net.Mirror.map(mf.downloadUrl), target, null, mf.size, mf.sha1);
                ok++;
                log.accept("[整合包] 已下载 mods/" + mf.fileName);
            } catch (Exception e) {
                failed++;
                log.accept("[失败] " + mf.fileName + "：" + e.getMessage());
            }
        }
        log.accept("[整合包] CurseForge mod 处理完成：成功/已存在 " + ok
                + (skip > 0 ? "，跳过 " + skip : "")
                + (failed > 0 ? "，失败 " + failed + "（可在 CurseForge 页面手动下载后放进 mods/）" : ""));
    }

    /** 普通 zip：整体解压到实例目录（保持包内结构，只剥掉「多套的那层目录」）。 */
    private static void extractZip(ModpackFile mp, Path dest, Consumer<String> log) throws IOException {
        Path zip = mp.path;
        int n = 0;
        try (ZipFile zf = new ZipFile(zip.toFile())) {
            for (java.util.Enumeration<? extends ZipEntry> en = zf.entries(); en.hasMoreElements(); ) {
                ZipEntry e = en.nextElement();
                String name = e.getName();
                if (e.isDirectory() || name.endsWith("/")) continue;
                String rel = ModpackFile.stripPrefix(name, mp.rootPrefix);
                if (rel.isBlank() || rel.equals(MARKER)) continue;
                if (extractEntry(zf, e, dest, rel, log)) n++;
            }
        }
        log.accept("[整合包] 已解压 " + n + " 个文件 -> " + dest);
    }

    /** 目录：整棵拷贝（跳过整合包标记文件）。 */
    private static void copyTree(Path src, Path dest, Consumer<String> log) throws IOException {
        int n = 0;
        try (Stream<Path> walk = Files.walk(src)) {
            for (Path p : walk.toList()) {
                if (Files.isDirectory(p)) continue;
                String rel = src.relativize(p).toString();
                if (rel.equals(MARKER)) continue;
                Path out = dest.resolve(rel).normalize();
                if (!out.startsWith(dest)) continue;
                if (Files.isDirectory(out)) continue;      // 目标位置是目录：跳过，别让安装整个失败
                try {
                    Files.createDirectories(out.getParent());
                    Files.copy(p, out, StandardCopyOption.REPLACE_EXISTING);
                    n++;
                } catch (Exception ex) {
                    if (log != null) log.accept("[跳过] " + rel + "（" + ex.getClass().getSimpleName() + "）");
                }
            }
        }
        log.accept("[整合包] 已拷贝 " + n + " 个文件 -> " + dest);
    }

    // ---------- 标记文件（让整合包在 config 丢失后也能被认出来） ----------

    public static void writeMarker(Config cfg, String name, String versionId, String loader, String mcVersion) {
        try {
            Path dir = cfg.instanceDir(name);
            Files.createDirectories(dir);
            JsonObject o = new JsonObject();
            o.addProperty("name", name);
            o.addProperty("versionId", versionId);
            o.addProperty("loader", loader);
            o.addProperty("mcVersion", mcVersion);
            o.addProperty("installedAt", System.currentTimeMillis());
            Files.writeString(dir.resolve(MARKER),
                    new GsonBuilder().setPrettyPrinting().create().toJson(o), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
        }
    }

    /**
     * 扫描 instances 目录，把磁盘上存在但 config 里没登记的整合包补回来
     * （config.json 被重置 / 手动拷入实例目录时，整合包列表不会「刷新不出来」）。
     *
     * @return 新补登记的数量
     */
    public static int syncFromDisk(Config cfg) {
        int added = 0;
        Path root = cfg.instancesDir();
        if (!Files.isDirectory(root)) return 0;
        try (Stream<Path> list = Files.list(root)) {
            for (Path dir : list.toList()) {
                Path marker = dir.resolve(MARKER);
                if (!Files.isRegularFile(marker)) continue;
                try {
                    JsonObject o = JsonParser.parseString(Files.readString(marker, StandardCharsets.UTF_8))
                            .getAsJsonObject();
                    String name = o.has("name") ? o.get("name").getAsString() : dir.getFileName().toString();
                    String versionId = o.has("versionId") ? o.get("versionId").getAsString() : null;
                    String loader = o.has("loader") ? o.get("loader").getAsString() : "vanilla";
                    if (versionId == null || versionId.isBlank()) continue;
                    if (cfg.findModpack(name) != null) continue;
                    cfg.modpacks.add(new Config.Modpack(name, versionId, loader));
                    added++;
                } catch (Exception ignored) {
                }
            }
        } catch (Exception ignored) {
        }
        if (added > 0) cfg.save();
        return added;
    }

    // ---------- 工具 ----------

    /** 去掉 overrides/ 与 client-overrides/ 前缀；清单/其他顶层文件返回 null 表示不拷。 */
    private static String stripOverridesPrefix(String name) {
        String n = name.replace('\\', '/');
        if (n.startsWith("overrides/")) return n.substring("overrides/".length());
        if (n.startsWith("client-overrides/")) return n.substring("client-overrides/".length());
        if (n.startsWith("server-overrides/")) return null;   // 仅服务端
        // 普通 zip / 已解压目录里的文件保持原路径
        return n;
    }

    /** Modrinth 的 env.client 只有 "unsupported" 表示「客户端不需要」（optional/required 都要装）。 */
    private static boolean clientUnsupported(JsonObject f) {
        if (!f.has("env") || !f.get("env").isJsonObject()) return false;
        JsonObject env = f.getAsJsonObject("env");
        if (!env.has("client") || env.get("client").isJsonNull()) return false;
        return "unsupported".equalsIgnoreCase(env.get("client").getAsString());
    }

    private static String hashOf(JsonObject f, String algo) {
        if (!f.has("hashes") || !f.get("hashes").isJsonObject()) return null;
        JsonObject hashes = f.getAsJsonObject("hashes");
        if (!hashes.has(algo)) return null;
        String v = hashes.get(algo).getAsString();
        return v == null || v.isBlank() ? null : v.toLowerCase(java.util.Locale.ROOT);
    }

    private static String firstOrNull(List<String> list) {
        return list == null || list.isEmpty() ? null : list.get(0);
    }

    private static String safeName(String name) {
        String n = (name == null || name.isBlank()) ? "modpack" : name;
        n = n.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        if (n.isEmpty()) n = "modpack";
        return n.length() > 64 ? n.substring(0, 64) : n;
    }
}
