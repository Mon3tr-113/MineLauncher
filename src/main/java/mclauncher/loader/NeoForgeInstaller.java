package mclauncher.loader;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import mclauncher.config.Config;
import mclauncher.minecraft.Library;
import mclauncher.minecraft.VersionInstaller;
import mclauncher.net.DownloadManager;
import mclauncher.net.Http;
import mclauncher.net.Mirror;
import mclauncher.util.Platform;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

/**
 * 手写实现 NeoForge 客户端安装（不依赖官方安装器）：
 * 读取安装器里的 version.json + install_profile.json，预下载依赖库（经 BMCLAPI），
 * 然后按 processors 声明逐步执行（installertools / jarsplitter / AutoRenamingTool / binarypatcher），
 * 产出打补丁的客户端 jar 与最终 version.json。
 */
public final class NeoForgeInstaller {

    private static final String NEOFORGE_MAVEN = "https://maven.neoforged.net/releases/";
    private static final String SIDE = "client";

    private NeoForgeInstaller() {
    }

    public static String install(String mcVersion, String neoforgeVersion, Config cfg,
                                 VersionInstaller.Progress progress) throws Exception {
        // 1) 下载安装器
        String instRel = "net/neoforged/neoforge/" + neoforgeVersion
                + "/neoforge-" + neoforgeVersion + "-installer.jar";
        Path installer = cfg.librariesDir().resolve("..").normalize()
                .resolve("installers").resolve("neoforge-" + neoforgeVersion + "-installer.jar");
        Files.createDirectories(installer.getParent());
        if (progress != null) progress.on("正在下载 NeoForge 安装器", 0, 0);
        Http.download(Mirror.map(NEOFORGE_MAVEN + instRel), installer, null);

        // 2) 读安装器内 version.json + install_profile.json
        JsonObject versionTemplate = readJsonFromJar(installer, "version.json");
        JsonObject profile = readJsonFromJar(installer, "install_profile.json");
        String versionId = versionTemplate.get("id").getAsString();
        String realMc = profile.has("minecraft") ? profile.get("minecraft").getAsString() : mcVersion;

        // 3) 确保原版已安装
        Path baseJson = cfg.versionsDir().resolve(realMc).resolve(realMc + ".json");
        if (!Files.exists(baseJson)) {
            if (progress != null) progress.on("先安装原版 " + realMc, 0, 0);
            new VersionInstaller(cfg).install(realMc, progress);
        }
        Path mcJar = cfg.versionsDir().resolve(realMc).resolve(realMc + ".jar");

        // 4) 预下载依赖库（install_profile + version.json）
        prefetch(profile, cfg, progress);
        prefetch(versionTemplate, cfg, progress);

        // 5) 准备临时工作目录
        Path work = cfg.gamePath().resolve(".neoforge-install").resolve(neoforgeVersion);
        deleteRecursively(work);
        Files.createDirectories(work);

        // 6) 解析 data
        Map<String, String> data = resolveData(profile, installer, work, cfg);

        // 7) 执行 client 侧 processors
        runProcessors(profile, installer, work, cfg, realMc, mcJar, data, progress);

        // 8) 把打补丁后的 client jar 放到版本目录，作为版本 jar
        Path patched = cfg.librariesDir().resolve(
                mavenRel("net.neoforged:neoforge:" + neoforgeVersion + ":client"));
        if (!Files.exists(patched)) {
            throw new IOException("binarypatcher 未产出打补丁客户端 jar：" + patched);
        }
        // 8.5) fml 本体 jar（universal）按 maven 路径直接找，不在版本 JSON 里：
        //      libraries/net/neoforged/neoforge/<ver>/neoforge-<ver>-universal.jar
        //      缺了它会在启动时报 "Mod ID: 'neoforge' ... [MISSING]" + DisplayWindow NoSuchElementException
        String universalCoord = "net.neoforged:neoforge:" + neoforgeVersion + ":universal";
        ensureLib(universalCoord, cfg.librariesDir().resolve(mavenRel(universalCoord)));
        Path versionDir = cfg.versionsDir().resolve(versionId);
        Files.createDirectories(versionDir);
        Files.copy(patched, versionDir.resolve(versionId + ".jar"), StandardCopyOption.REPLACE_EXISTING);

        // 把 jarsplitter 产出的 client-extra 也加入 libraries（客户端资源需要）
        addClientExtra(versionTemplate, profile, cfg);

        // 9) 写出最终 version.json
        Files.writeString(versionDir.resolve(versionId + ".json"),
                new GsonBuilder().setPrettyPrinting().create().toJson(versionTemplate),
                StandardCharsets.UTF_8);

        if (progress != null) progress.on("NeoForge " + neoforgeVersion + " 安装完成", 0, 0);
        return versionId;
    }

    // ---------- 预下载依赖库 ----------

    /** 把 install_profile.data.MC_EXTRA 指向的 client-extra jar 作为本地库加入 version.json。 */
    private static void addClientExtra(JsonObject versionTemplate, JsonObject profile, Config cfg) {
        if (!versionTemplate.has("libraries") || !profile.has("data")) return;
        JsonObject data = profile.getAsJsonObject("data");
        if (!data.has("MC_EXTRA")) return;
        JsonObject entry = data.getAsJsonObject("MC_EXTRA");
        if (!entry.has(SIDE)) return;
        String val = entry.get(SIDE).getAsString();
        if (!val.startsWith("[") || !val.endsWith("]")) return;
        String coord = val.substring(1, val.length() - 1);
        JsonObject lib = new JsonObject();
        lib.addProperty("name", coord);
        JsonObject dl = new JsonObject();
        JsonObject art = new JsonObject();
        art.addProperty("path", mavenRel(coord));
        art.addProperty("url", "");
        dl.add("artifact", art);
        lib.add("downloads", dl);
        versionTemplate.getAsJsonArray("libraries").add(lib);
    }

    private static void prefetch(JsonObject holder, Config cfg, VersionInstaller.Progress progress) throws Exception {
        if (!holder.has("libraries")) return;
        List<DownloadManager.FileSpec> specs = new ArrayList<>();
        java.util.Set<Path> seen = new java.util.LinkedHashSet<>();
        for (JsonElement le : holder.getAsJsonArray("libraries")) {
            JsonObject o = le.getAsJsonObject();
            if (!o.has("name")) continue;
            String name = o.get("name").getAsString();
            String url = o.has("url") ? o.get("url").getAsString() : null;
            Library lib;
            try {
                lib = new Library(name, url, o);
            } catch (IllegalArgumentException e) {
                continue;
            }
            // install_profile 里的库大量带分类器（:client / :universal / :extra），
            // 下载地址和本地路径都必须带分类器，否则会 404 或者把主 jar 存成分类器 jar
            String cls = lib.classifierInName();
            String u = lib.downloadUrlFor(cls);
            if (u == null || u.isBlank()) continue;
            Path p = cfg.librariesDir().resolve(lib.downloadPathFor(cls));
            if (!seen.add(p)) continue;
            specs.add(new DownloadManager.FileSpec(Mirror.map(u), p,
                    lib.downloadSizeFor(cls), lib.downloadSha1For(cls)));
        }
        if (specs.isEmpty()) return;
        try {
            DownloadManager.get().downloadAll("NeoForge 依赖库", specs, cfg.downloadThreads, (done, total) -> {
                if (progress != null) progress.on("正在预下载 NeoForge 依赖库", done, total);
            });
        } catch (Exception ignored) {
            // 个别库镜像缺失不致命，后面 processor 需要时再单独下载
        }
    }

    // ---------- data 解析 ----------

    private static Map<String, String> resolveData(JsonObject profile, Path installer, Path work, Config cfg) throws Exception {
        Map<String, String> map = new HashMap<>();
        if (!profile.has("data")) return map;
        JsonObject data = profile.getAsJsonObject("data");
        for (Map.Entry<String, JsonElement> e : data.entrySet()) {
            JsonObject entry = e.getValue().getAsJsonObject();
            if (!entry.has(SIDE)) continue;
            map.put(e.getKey(), resolveDataValue(entry.get(SIDE).getAsString(), installer, work, cfg));
        }
        return map;
    }

    private static String resolveDataValue(String val, Path installer, Path work, Config cfg) throws Exception {
        if (val == null) return "";
        if (val.length() >= 2 && ((val.startsWith("\"") && val.endsWith("\""))
                || (val.startsWith("'") && val.endsWith("'")))) {
            return val.substring(1, val.length() - 1);
        }
        if (val.startsWith("/")) {
            String entry = val.substring(1);
            Path out = work.resolve(entry);
            extractEntry(installer, entry, out);
            return out.toString();
        }
        if (val.startsWith("[")) {
            return cfg.librariesDir().resolve(mavenRel(val.substring(1, val.length() - 1))).toString();
        }
        return val;
    }

    // ---------- processors ----------

    private static void runProcessors(JsonObject profile, Path installer, Path work, Config cfg,
                                      String realMc, Path mcJar, Map<String, String> data,
                                      VersionInstaller.Progress progress) throws Exception {
        if (!profile.has("processors")) return;
        JsonArray procs = profile.getAsJsonArray("processors");
        for (JsonElement pe : procs) {
            JsonObject proc = pe.getAsJsonObject();
            if (proc.has("sides") && proc.getAsJsonArray("sides").size() > 0 && !containsClient(proc.getAsJsonArray("sides"))) {
                continue;
            }

            String jarCoord = proc.get("jar").getAsString();
            Path procJar = cfg.librariesDir().resolve(mavenRel(jarCoord));
            ensureLib(jarCoord, procJar);

            List<Path> cp = new ArrayList<>();
            cp.add(procJar);
            if (proc.has("classpath")) {
                for (JsonElement ce : proc.getAsJsonArray("classpath")) {
                    String cn = ce.getAsString();
                    Path p = cfg.librariesDir().resolve(mavenRel(cn));
                    ensureLib(cn, p);
                    cp.add(p);
                }
            }

            List<String> args = new ArrayList<>();
            for (JsonElement ae : proc.getAsJsonArray("args")) {
                args.add(substArg(ae.getAsString(), installer, cfg, realMc, mcJar, data, procJar));
            }

            String mainClass = readMainClass(procJar);
            List<String> cmd = new ArrayList<>();
            cmd.add(cfg.javaPath);
            cmd.add("-cp");
            cmd.add(String.join(Platform.pathSeparator(), cp.stream().map(Path::toString).toList()));
            cmd.add(mainClass);
            cmd.addAll(args);

            if (progress != null) progress.on("运行 processor：" + mainClass.split("\\.")[mainClass.split("\\.").length - 1], 0, 0);
            runProcess(cmd, cfg.gamePath().toFile(), line -> {
            });
        }
    }

    private static boolean containsClient(JsonArray sides) {
        for (JsonElement s : sides) {
            if (SIDE.equals(s.getAsString())) return true;
        }
        return false;
    }

    private static String substArg(String arg, Path installer, Config cfg, String realMc,
                                   Path mcJar, Map<String, String> data, Path procJar) throws Exception {
        // [coord@ext] → 本地路径 + 下载（如 MCP_DATA 的 neoform zip 输入）
        if (arg.startsWith("[") && arg.endsWith("]")) {
            String coord = arg.substring(1, arg.length() - 1);
            Path p = cfg.librariesDir().resolve(mavenRel(coord));
            ensureLib(coord, p);
            return p.toString();
        }
        String r = arg;
        for (Map.Entry<String, String> e : data.entrySet()) {
            r = r.replace("{" + e.getKey() + "}", e.getValue());
        }
        r = r.replace("{MINECRAFT_JAR}", mcJar.toString());
        r = r.replace("{INSTALLER}", installer.toString());
        r = r.replace("{SIDE}", SIDE);
        r = r.replace("{ROOT}", cfg.gamePath().toString());
        r = r.replace("{LIBRARY_DIR}", cfg.librariesDir().toString());
        r = r.replace("{MINECRAFT_VERSION}", realMc);
        return r;
    }

    // ---------- 工具 ----------

    /** Maven 坐标 g:a:v[:cls][@ext] → libraries 下的相对路径。 */
    private static String mavenRel(String coord) {
        String ext = "jar";
        int at = coord.indexOf('@');
        if (at >= 0) {
            ext = coord.substring(at + 1);
            coord = coord.substring(0, at);
        }
        String[] p = coord.split(":");
        String g = p[0].replace('.', '/');
        String a = p[1];
        String v = p[2];
        String cls = p.length > 3 ? p[3] : null;
        return g + "/" + a + "/" + v + "/" + a + "-" + v + (cls == null ? "" : "-" + cls) + "." + ext;
    }

    /** 确保某 maven 坐标的产物本地存在，缺失则经 BMCLAPI 下载。 */
    private static void ensureLib(String coord, Path target) throws Exception {
        if (Files.exists(target) && Files.size(target) > 0) return;
        String rel = mavenRel(coord);
        Http.download(Mirror.map(NEOFORGE_MAVEN + rel), target, null);
    }

    private static JsonObject readJsonFromJar(Path jar, String entry) throws Exception {
        try (ZipInputStream zin = new ZipInputStream(Files.newInputStream(jar))) {
            ZipEntry e;
            while ((e = zin.getNextEntry()) != null) {
                if (e.getName().equals(entry)) {
                    return JsonParser.parseString(new String(zin.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
                }
            }
        }
        throw new IOException("安装器内缺少 " + entry);
    }

    private static String readMainClass(Path jar) throws Exception {
        try (ZipFile zf = new ZipFile(jar.toFile())) {
            ZipEntry e = zf.getEntry("META-INF/MANIFEST.MF");
            if (e == null) throw new IOException("处理器 jar 缺少 MANIFEST：" + jar.getFileName());
            Properties props = new Properties();
            try (var in = zf.getInputStream(e)) {
                props.load(in);
            }
            String mc = props.getProperty("Main-Class");
            if (mc == null) throw new IOException("处理器 jar 缺少 Main-Class：" + jar.getFileName());
            return mc;
        }
    }

    private static void extractEntry(Path jar, String entryName, Path out) throws Exception {
        Files.createDirectories(out.getParent());
        try (ZipInputStream zin = new ZipInputStream(Files.newInputStream(jar))) {
            ZipEntry e;
            while ((e = zin.getNextEntry()) != null) {
                if (e.getName().equals(entryName)) {
                    Files.copy(zin, out, StandardCopyOption.REPLACE_EXISTING);
                    return;
                }
            }
        }
        throw new IOException("安装器内缺少条目：" + entryName);
    }

    private static void runProcess(List<String> cmd, java.io.File workDir, Consumer<String> log) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(workDir);
        pb.redirectErrorStream(true);
        Process p = pb.start();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.isBlank()) log.accept(line);
            }
        }
        int code = p.waitFor();
        if (code != 0) {
            throw new IOException("processor 退出码 " + code + "：" + String.join(" ", cmd));
        }
    }

    private static void deleteRecursively(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        try (var walk = Files.walk(dir)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                }
            });
        }
    }
}
