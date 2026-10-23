package mclauncher.util;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import mclauncher.config.Config;
import mclauncher.net.Http;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;

/** Java 自动识别与自动下载（缺失时通过 Adoptium 下载 JRE）。 */
public final class JavaManager {

    public static class JavaInfo {
        public final String path;
        public final int major;

        public JavaInfo(String path, int major) {
            this.path = path;
            this.major = major;
        }
    }

    private static final String ADPTIUM_API =
            "https://api.adoptium.net/v3/assets/latest/%d/hotspot?os=windows&architecture=x64&image_type=jre";

    private JavaManager() {
    }

    /** 扫描本机常见位置的 Java，返回可用的列表（按 major 降序）。 */
    public static List<JavaInfo> detect() {
        List<String> candidates = new ArrayList<>();
        String javaHome = System.getenv("JAVA_HOME");
        if (javaHome != null && !javaHome.isBlank()) {
            candidates.add(javaHome + "/bin/java.exe");
        }
        addGlob(candidates, "C:\\Program Files\\Java\\*\\bin\\java.exe");
        addGlob(candidates, "C:\\Program Files\\Eclipse Adoptium\\*\\bin\\java.exe");
        addGlob(candidates, "C:\\Program Files\\Zulu\\*\\bin\\java.exe");
        addGlob(candidates, "C:\\Program Files\\BellSoft\\*\\bin\\java.exe");
        String local = System.getenv("LOCALAPPDATA");
        if (local != null) {
            addGlob(candidates, local + "\\Programs\\Eclipse Adoptium\\*\\bin\\java.exe");
        }
        // 启动器自己下载的 Java
        addGlob(candidates, Config.launcherHome().resolve("java").toString() + "\\*\\bin\\java.exe");
        addGlob(candidates, Config.launcherHome().resolve("java").toString() + "\\*\\*\\bin\\java.exe");
        // 安装包内自带的 Java（java/8、java/17 …）
        Path bundled = bundledJavaRoot();
        if (bundled != null) {
            addGlob(candidates, bundled.toString() + "\\*\\bin\\java.exe");
            addGlob(candidates, bundled.toString() + "\\*\\*\\bin\\java.exe");
        }
        // PATH 里的 java
        candidates.add("java");

        List<JavaInfo> result = new ArrayList<>();
        java.util.Set<String> seen = new java.util.LinkedHashSet<>();
        for (String c : candidates) {
            int major = majorOf(c);
            if (major > 0 && seen.add(c)) {
                result.add(new JavaInfo(c, major));
            }
        }
        result.sort(Comparator.comparingInt((JavaInfo j) -> j.major).reversed());
        return result;
    }

    /** 安装包内自带的 Java 目录（jpackage app-image 下与 MineLauncher.exe 同级）。 */
    public static Path bundledJavaRoot() {
        try {
            String appPath = System.getProperty("jpackage.app-path");
            if (appPath != null && !appPath.isBlank()) {
                Path p = Paths.get(appPath).getParent();
                if (p != null) return p.resolve("java");
            }
            // 回退：从 classpath 里的 jar 位置推断 <app>/app/mclauncher.jar
            String cp = System.getProperty("java.class.path");
            if (cp != null && cp.endsWith(".jar")) {
                Path jar = Paths.get(cp);
                if (jar.getParent() != null && jar.getParent().getParent() != null) {
                    return jar.getParent().getParent().resolve("java");
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private static void addGlob(List<String> out, String pattern) {
        int star = pattern.indexOf('*');
        if (star < 0) return;
        String baseStr = pattern.substring(0, star);
        File base = new File(baseStr);
        if (!base.isDirectory()) return;
        for (File d : listDirs(base)) {
            File j = new File(d, "bin/java.exe");
            if (j.isFile()) {
                out.add(j.getAbsolutePath());
                continue;
            }
            for (File dd : listDirs(d)) {
                File j2 = new File(dd, "bin/java.exe");
                if (j2.isFile()) out.add(j2.getAbsolutePath());
            }
        }
    }

    private static File[] listDirs(File dir) {
        File[] fs = dir.listFiles(File::isDirectory);
        return fs == null ? new File[0] : fs;
    }

    /** 读取某个 java 可执行文件的 major 版本（优先读 release 文件，回退到 java -version）。 */
    public static int majorOf(String javaPath) {
        if (javaPath == null || javaPath.isBlank()) return -1;
        try {
            File exe = new File(javaPath);
            File home = exe.getParentFile() != null ? exe.getParentFile().getParentFile() : null;
            if (home != null) {
                File release = new File(home, "release");
                if (release.isFile()) {
                    for (String line : Files.readAllLines(release.toPath())) {
                        if (line.startsWith("JAVA_VERSION=")) {
                            String v = line.substring("JAVA_VERSION=".length()).replace("\"", "").trim();
                            return parseMajor(v);
                        }
                    }
                }
            }
            // 回退：运行 java -version
            Process p = new ProcessBuilder(javaPath, "-version")
                    .redirectErrorStream(true).start();
            String out;
            try (var in = p.getInputStream()) {
                out = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            }
            p.waitFor();
            int idx = out.indexOf("version");
            if (idx >= 0) {
                int q1 = out.indexOf('"', idx);
                int q2 = out.indexOf('"', q1 + 1);
                if (q1 >= 0 && q2 > q1) return parseMajor(out.substring(q1 + 1, q2));
            }
        } catch (Exception ignored) {
        }
        return -1;
    }

    private static int parseMajor(String v) {
        if (v.startsWith("1.")) {
            int dot = v.indexOf('.', 2);
            return Integer.parseInt(v.substring(2, dot > 0 ? dot : 3));
        }
        int dot = v.indexOf('.');
        try {
            return Integer.parseInt(dot > 0 ? v.substring(0, dot) : v);
        } catch (Exception e) {
            return -1;
        }
    }

    /** 选一个 major >= required 的 Java（优先最接近 required 的）；没有则返回 null。 */
    public static JavaInfo bestFor(int required) {
        List<JavaInfo> all = detect();
        JavaInfo best = null;
        for (JavaInfo j : all) {
            if (j.major >= required) {
                if (best == null || j.major < best.major) best = j;
            }
        }
        if (best == null && !all.isEmpty()) {
            // 没有满足的，退回最新的（至少能跑起来，后续会提示版本过低）
            best = all.get(0);
        }
        return best;
    }

    /**
     * 确保有一个可用的 Java：优先用 preferredPath（若版本够），否则检测，再否则自动下载。
     */
    public static String ensure(int requiredMajor, String preferredPath, Config cfg, Consumer<String> log) throws Exception {
        if (preferredPath != null && !preferredPath.isBlank() && !"java".equals(preferredPath)) {
            File f = new File(preferredPath);
            if (f.isFile()) {
                int m = majorOf(preferredPath);
                if (m >= requiredMajor) {
                    if (log != null) log.accept("[Java] 使用设置的 Java " + m + "：" + preferredPath);
                    return preferredPath;
                }
                if (log != null) log.accept("[Java] " + preferredPath + " 是 Java " + m
                        + "，低于该版本要求的 Java " + requiredMajor);
            }
        }
        JavaInfo best = bestFor(requiredMajor);
        if (best != null && best.major >= requiredMajor && !"java".equals(best.path)) {
            if (log != null) log.accept("[Java] 自动识别到 Java " + best.major + "：" + best.path);
            return best.path;
        }
        if (log != null) log.accept("[Java] 未找到满足 Java " + requiredMajor + " 的运行环境，开始自动下载...");
        return download(requiredMajor, cfg, log);
    }

    /** 按顺序尝试多个下载源，任一成功即可；全失败时给出可操作的错误。 */
    public static String download(int major, Config cfg, Consumer<String> log) throws Exception {
        Path base = Config.launcherHome().resolve("java");
        Files.createDirectories(base);
        Path zip = base.resolve("jre-" + major + ".zip");

        List<String[]> sources = new ArrayList<>(); // 每项 {名称, URL}

        // 源一：Azul Zulu。全球 CDN，国内一般可达，且元数据与下载站分离，最稳。
        try {
            String azul = azulJreUrl(major);
            if (azul != null) {
                sources.add(new String[]{"Azul Zulu CDN", azul});
            }
        } catch (Exception e) {
            if (log != null) log.accept("[Java] Azul 元数据获取失败：" + shortError(e));
        }

        // 源二/三：Adoptium 官方（GitHub release），外加清华镜像加速。
        try {
            String gh = adoptiumJreLink(major);
            String fileName = gh.substring(gh.lastIndexOf('/') + 1);
            String tuna = "https://mirrors.tuna.tsinghua.edu.cn/Adoptium/" + major
                    + "/jre/x64/windows/" + fileName;
            sources.add(new String[]{"清华镜像", tuna});
            sources.add(new String[]{"Adoptium GitHub", gh});
        } catch (Exception e) {
            if (log != null) log.accept("[Java] Adoptium 元数据获取失败：" + shortError(e));
        }

        if (sources.isEmpty()) {
            throw new Exception("获取不到 Java " + major + " 的下载地址（网络不通）。\n"
                    + "可以到 https://adoptium.net 手动下载 JRE，再在「设置 → Java 路径」里指定它的 bin\\java.exe");
        }

        String lastErr = null;
        boolean ok = false;
        for (String[] s : sources) {
            try {
                if (log != null) log.accept("[Java] 尝试下载源：" + s[0]);
                Http.download(s[1], zip, null);
                ok = true;
                if (log != null) log.accept("[Java] 下载完成（" + s[0] + "）");
                break;
            } catch (Exception e) {
                lastErr = s[0] + " → " + shortError(e);
                if (log != null) log.accept("[Java] 失败：" + lastErr);
                try {
                    Files.deleteIfExists(zip);
                } catch (Exception ignored) {
                }
            }
        }
        if (!ok) {
            throw new Exception("Java " + major + " 自动下载失败。\n最后一次错误：" + lastErr
                    + "\n可在「设置 → Java 路径」里手动指定本机已有的 java.exe，"
                    + "或到 https://adoptium.net 手动下载 JRE。");
        }

        Path outDir = base.resolve(String.valueOf(major));
        if (log != null) log.accept("[Java] 解压到 " + outDir);
        extractZip(zip, outDir);

        Path exe = findJavaExe(outDir);
        if (exe == null) throw new Exception("解压后未找到 bin/java.exe，压缩包可能不完整");
        try {
            Files.deleteIfExists(zip);
        } catch (Exception ignored) {
        }
        if (log != null) log.accept("[Java] 已就绪：" + exe);
        return exe.toString();
    }

    /** 从 Azul API 取 Windows x64 JRE 的直链。 */
    private static String azulJreUrl(int major) throws Exception {
        String api = "https://api.azul.com/metadata/v1/zulu/packages/"
                + "?java_version=" + major
                + "&os=windows&arch=x86_64&archive_type=zip&java_package_type=jre"
                + "&javafx_bundled=false&release_status=ga&latest=true";
        JsonArray arr = JsonParser.parseString(Http.getString(api)).getAsJsonArray();
        if (arr.isEmpty()) return null;
        JsonObject o = arr.get(0).getAsJsonObject();
        return o.has("download_url") ? o.get("download_url").getAsString() : null;
    }

    /** 从 Adoptium API 取 Windows x64 JRE 的压缩包直链。 */
    private static String adoptiumJreLink(int major) throws Exception {
        String api = String.format(ADPTIUM_API, major);
        JsonArray arr = JsonParser.parseString(Http.getString(api)).getAsJsonArray();
        if (arr.isEmpty()) throw new Exception("Adoptium 没有 Java " + major + " 的 Windows JRE");
        JsonObject rel = arr.get(0).getAsJsonObject();
        return rel.getAsJsonObject("binary").getAsJsonObject("package").get("link").getAsString();
    }

    /** 把异常压成一行可读信息（ConnectException 自带 message 为空，需要补上类型）。 */
    private static String shortError(Throwable t) {
        StringBuilder sb = new StringBuilder();
        while (t != null) {
            String m = t.getMessage();
            String cls = t.getClass().getSimpleName();
            if (sb.length() > 0) sb.append(" ← ");
            sb.append(m == null || m.isBlank() ? cls : cls + ": " + m);
            t = t.getCause();
        }
        return sb.toString();
    }

    private static void extractZip(Path zip, Path target) throws Exception {
        Files.createDirectories(target);
        try (var zin = new java.util.zip.ZipInputStream(Files.newInputStream(zip))) {
            java.util.zip.ZipEntry e;
            while ((e = zin.getNextEntry()) != null) {
                if (e.isDirectory()) continue;
                Path out = target.resolve(e.getName()).normalize();
                if (!out.startsWith(target)) continue;
                Files.createDirectories(out.getParent());
                Files.copy(zin, out, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    private static Path findJavaExe(Path root) {
        try (var walk = Files.walk(root)) {
            return walk.filter(p -> p.getFileName().toString().equals("java.exe")
                            && p.getParent() != null && p.getParent().getFileName().toString().equals("bin"))
                    .findFirst().orElse(null);
        } catch (Exception e) {
            return null;
        }
    }
}
