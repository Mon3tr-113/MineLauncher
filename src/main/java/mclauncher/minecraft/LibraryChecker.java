package mclauncher.minecraft;

import mclauncher.config.Config;
import mclauncher.net.DownloadManager;
import mclauncher.net.Http;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * 依赖库（libraries）完整性校验：大小 / SHA-1 双重核对，缺的、坏的自动补下载。
 *
 * <p>为什么需要它：版本安装到一半断网，或者镜像返回了错误内容（比如把主 jar 当成
 * natives 分类器 jar 存下来），都会在库里留下「存在但内容不对」的文件。表现是
 * {@code UnsatisfiedLinkError: no lwjgl in java.library.path}、或某个 mod 加载器抛
 * {@code ClassNotFoundException / ZipException: invalid CEN header}，光看日志很难定位到是哪个 jar。
 *
 * <p>策略：
 * <ul>
 *   <li>JSON 给了 sha1 → 按 sha1 判定（最可靠，能抓出「大小对、内容错」）；</li>
 *   <li>只给了 size → 按大小判定（能抓出截断/半下载）；</li>
 *   <li>都没给（Fabric / NeoForge 的部分库）→ 只要求文件存在且非空，不误删；</li>
 *   <li>本地产物（安装器现场生成的 client-extra 等，没有下载地址）缺失时不自动补，直接提示重装。</li>
 * </ul>
 */
public final class LibraryChecker {

    /** 校验结果。 */
    public static final class Report {
        public final int total;
        /** 缺下载地址、只能靠重装解决的本地产物。 */
        public final List<Path> localMissing = new ArrayList<>();
        /** 缺失或损坏、可以从网络补回来的条目。 */
        public final List<LibraryResolver.Item> broken = new ArrayList<>();
        /** 本次实际补下载成功的文件数。 */
        public int repaired;
        /** 补下载后仍然有问题（比如镜像里也没有这个文件）的条目。 */
        public final List<LibraryResolver.Item> stillBroken = new ArrayList<>();

        Report(int total) {
            this.total = total;
        }

        /** 校验开始前是否本来就是完整的。 */
        public boolean wasComplete() {
            return broken.isEmpty() && localMissing.isEmpty();
        }

        public boolean isComplete() {
            return stillBroken.isEmpty() && localMissing.isEmpty();
        }

        /** 是否有 natives 分类器库参与修复（这类库坏了必然是启动即崩）。 */
        public boolean touchesNatives() {
            for (LibraryResolver.Item i : broken) {
                if (i.nativeLib) return true;
            }
            return false;
        }
    }

    private LibraryChecker() {
    }

    /** 只校验、不下载、不联网。 */
    public static Report inspect(GameVersion v, Config cfg) {
        List<LibraryResolver.Item> items = allItems(v, cfg);
        Report r = new Report(items.size());
        for (LibraryResolver.Item item : items) {
            if (!item.isDownloadable()) {
                if (!Files.isRegularFile(item.path) || sizeOf(item.path) <= 0) {
                    r.localMissing.add(item.path);
                }
                continue;
            }
            if (!ok(item)) r.broken.add(item);
        }
        return r;
    }

    /** 版本 JSON 声明的库 + FML 自己按 maven 路径找的本体 jar（按落盘路径去重）。 */
    public static List<LibraryResolver.Item> allItems(GameVersion v, Config cfg) {
        java.util.Map<Path, LibraryResolver.Item> byPath = new java.util.LinkedHashMap<>();
        for (LibraryResolver.Item i : LibraryResolver.resolveItems(v, cfg)) {
            byPath.putIfAbsent(i.path, i);
        }
        for (LibraryResolver.Item i : fmlSelfJars(v, cfg)) {
            byPath.putIfAbsent(i.path, i);
        }
        return new ArrayList<>(byPath.values());
    }

    /**
     * FML（NeoForge、Forge 1.17+）**不通过版本 JSON 的 libraries 加载自己**，而是直接按 maven 约定
     * 去 {@code -DlibraryDirectory} 里找 {@code <group>/<artifact>/<version>/<artifact>-<version>-<classifier>.jar}。
     *
     * <p>以 NeoForge 21.1.264 为例，它找的是
     * {@code libraries/net/neoforged/neoforge/21.1.264/neoforge-21.1.264-universal.jar}。
     * 这个文件缺失时游戏不会说「少了某个文件」，而是：
     * <pre>
     *   Missing or unsupported mandatory dependencies: Mod ID: 'neoforge' … Actual version: '[MISSING]'
     *   java.util.NoSuchElementException: No value present  (DisplayWindow.updateModuleReads)
     * </pre>
     * 所以这里单独按 fml 版本参数把它补出来（顺带兼容历史上被下成 {@code neoforge-<ver>.jar} 的错名文件）。
     */
    public static List<LibraryResolver.Item> fmlSelfJars(GameVersion v, Config cfg) {
        List<LibraryResolver.Item> out = new ArrayList<>();
        String neoVer = argValue(v, "--fml.neoForgeVersion");
        if (neoVer != null && !neoVer.isBlank()) {
            out.add(selfJar(cfg, "net.neoforged", "neoforge", neoVer, "universal",
                    "https://maven.neoforged.net/releases/"));
            return out;
        }
        String forgeVer = argValue(v, "--fml.forgeVersion");
        String mcVer = argValue(v, "--fml.mcVersion");
        if (forgeVer != null && !forgeVer.isBlank()) {
            String ver = (mcVer == null || mcVer.isBlank()) ? forgeVer : mcVer + "-" + forgeVer;
            out.add(selfJar(cfg, "net.minecraftforge", "forge", ver, "universal",
                    "https://maven.minecraftforge.net/"));
        }
        return out;
    }

    private static LibraryResolver.Item selfJar(Config cfg, String group, String artifact,
                                                String version, String classifier, String mavenBase) {
        String rel = group.replace('.', '/') + "/" + artifact + "/" + version + "/"
                + artifact + "-" + version + "-" + classifier + ".jar";
        return new LibraryResolver.Item(mclauncher.net.Mirror.map(mavenBase + rel),
                cfg.librariesDir().resolve(rel), false, -1, null);
    }

    /** 是否是 ModLauncher/FML 系（Forge 1.17+ / NeoForge）：靠 {@code --launchTarget} 判断。 */
    public static boolean isFmlVersion(GameVersion v) {
        return argValue(v, "--launchTarget") != null;
    }

    /**
     * 确保「版本 jar」存在：{@code versions/<id>/<id>.jar}。
     *
     * <p>Forge / NeoForge 的官方安装器只把打补丁的客户端放进
     * {@code libraries/.../<forge|neoforge>-<版本>-client.jar}，<b>不会</b>生成 versions 下的那个 jar。
     * 少了它的后果非常迷惑：启动时 FML 只能拿原版 jar 凑合，于是出现
     * {@code module.ResolutionException: Module minecraft contains package net.minecraft.server,
     * module _1._20._1 exports package net.minecraft.server to minecraft}。
     *
     * @return true 表示版本 jar 现在可用
     */
    public static boolean ensureClientJar(GameVersion v, Config cfg, Consumer<String> log) {
        Path target = cfg.versionsDir().resolve(v.id).resolve(v.id + ".jar");
        if (Files.isRegularFile(target) && sizeOf(target) > 0) return true;
        try {
            Files.createDirectories(target.getParent());
        } catch (Exception ignored) {
        }

        // 1) FML 系优先用安装器产出的「打补丁客户端」——这才是 FML 期望的版本 jar
        //    （拿原版 jar 顶替虽然有时也能启动，但 mod 依赖的补丁行为可能缺失）
        if (isFmlVersion(v)) {
            String patched = patchedClientJarPath(v, cfg);
            if (patched != null) {
                Path src = cfg.librariesDir().resolve(patched);
                if (Files.isRegularFile(src) && sizeOf(src) > 0) {
                    try {
                        Files.copy(src, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                        if (log != null) {
                            log.accept("[依赖库] 已用 " + src.getFileName() + " 补齐版本 jar："
                                    + target.getFileName());
                        }
                        return true;
                    } catch (Exception e) {
                        if (log != null) log.accept("[依赖库] 复制版本 jar 失败：" + e.getMessage());
                    }
                } else if (log != null) {
                    log.accept("[依赖库] 缺少打补丁客户端：" + src);
                }
            }
        }

        // 2) 版本 JSON 自己声明了 downloads.client（原版 / Fabric 等）：直接下
        if (v.downloadsObj != null && v.downloadsObj.has("client")) {
            try {
                com.google.gson.JsonObject client = v.downloadsObj.getAsJsonObject("client");
                String url = mclauncher.net.Mirror.map(client.get("url").getAsString());
                long size = client.has("size") ? client.get("size").getAsLong() : -1;
                String sha1 = client.has("sha1") ? client.get("sha1").getAsString() : null;
                Http.download(url, target, null, size, sha1);
                if (log != null) log.accept("[依赖库] 已补下版本 jar：" + target.getFileName());
                return Files.isRegularFile(target) && sizeOf(target) > 0;
            } catch (Exception e) {
                if (log != null) log.accept("[依赖库] 下载版本 jar 失败：" + e.getMessage());
            }
        }
        return Files.isRegularFile(target) && sizeOf(target) > 0;
    }

    /** FML 打补丁客户端的 maven 相对路径（Forge / NeoForge）。 */
    public static String patchedClientJarPath(GameVersion v, Config cfg) {
        String neo = argValue(v, "--fml.neoForgeVersion");
        if (neo != null && !neo.isBlank()) {
            return "net/neoforged/neoforge/" + neo + "/neoforge-" + neo + "-client.jar";
        }
        String forge = argValue(v, "--fml.forgeVersion");
        if (forge != null && !forge.isBlank()) {
            String mc = argValue(v, "--fml.mcVersion");
            String ver = (mc == null || mc.isBlank()) ? forge : mc + "-" + forge;
            return "net/minecraftforge/forge/" + ver + "/forge-" + ver + "-client.jar";
        }
        return null;
    }

    /**
     * 安装完成后立即核对一次（缺的库补下、版本 jar 补齐），
     * 让问题在安装阶段就暴露，而不是等到启动时崩在半路。
     */
    public static void verifyAfterInstall(String versionId, Config cfg, Consumer<String> log) {
        try {
            GameVersion v = new VersionManager(cfg).load(versionId);
            ensureClientJar(v, cfg, log);
            ensure(v, cfg, log);
        } catch (Exception e) {
            if (log != null) log.accept("[依赖库] 安装后校验失败（可启动前再试）：" + e.getMessage());
        }
    }

    /** 从 arguments.game 里取 {@code --fml.xxx <value>} 的值（字符串或带 rules 的对象都展开）。 */
    private static String argValue(GameVersion v, String flag) {
        List<String> args = new ArrayList<>();
        if (v.gameArgs != null) {
            for (com.google.gson.JsonElement e : v.gameArgs) {
                if (e == null || e.isJsonNull()) continue;
                if (e.isJsonPrimitive()) {
                    args.add(e.getAsString());
                } else if (e.isJsonObject() && e.getAsJsonObject().has("value")) {
                    com.google.gson.JsonElement val = e.getAsJsonObject().get("value");
                    if (val.isJsonArray()) {
                        for (com.google.gson.JsonElement x : val.getAsJsonArray()) args.add(x.getAsString());
                    } else {
                        args.add(val.getAsString());
                    }
                }
            }
        }
        for (int i = 0; i + 1 < args.size(); i++) {
            if (args.get(i).equals(flag)) return args.get(i + 1);
        }
        return null;
    }

    /**
     * 校验并自动补齐缺失/损坏的依赖库。
     *
     * @return 校验报告（repaired 为本次补下载的文件数）
     */
    public static Report ensure(GameVersion v, Config cfg, Consumer<String> log) throws Exception {
        Report r = inspect(v, cfg);
        if (r.wasComplete()) {
            if (log != null) log.accept("[依赖库] 依赖库完整（" + r.total + " 个）");
            return r;
        }

        if (!r.localMissing.isEmpty() && log != null) {
            log.accept("[依赖库] 有 " + r.localMissing.size()
                    + " 个本地产物缺失（由加载器安装器生成，无法下载），请重新安装该版本：");
            int shown = 0;
            for (Path p : r.localMissing) {
                if (shown++ >= 5) {
                    log.accept("[依赖库]   ... 另有 " + (r.localMissing.size() - 5) + " 个同类文件");
                    break;
                }
                log.accept("[依赖库]   " + p);
            }
        }

        if (r.broken.isEmpty()) return r;

        long natives = r.broken.stream().filter(i -> i.nativeLib).count();
        if (log != null) {
            log.accept("[依赖库] 发现 " + r.broken.size() + " 个依赖库缺失或损坏"
                    + (natives > 0 ? "（其中 natives 库 " + natives + " 个，缺了必然启动崩溃）" : "")
                    + "，开始自动补全...");
            int shown = 0;
            for (LibraryResolver.Item i : r.broken) {
                if (shown++ >= 8) {
                    log.accept("[依赖库]   ... 另有 " + (r.broken.size() - 8) + " 个");
                    break;
                }
                log.accept("[依赖库]   " + i.label() + "  [" + whyBroken(i) + "]");
            }
        }

        List<DownloadManager.FileSpec> specs = new ArrayList<>();
        for (LibraryResolver.Item i : r.broken) {
            // 历史上分类器库曾被下成「去掉分类器」的文件名（如 universal → neoforge-<ver>.jar）。
            // 本地已经有内容对的错名文件时直接顶替，省一次下载、也不依赖网络。
            if (!Files.isRegularFile(i.path) && repairFromSibling(i.path)) {
                if (log != null) log.accept("[依赖库] 用本地错名文件修复：" + i.path.getFileName());
                continue;
            }
            // 坏文件先删掉：避免「sha1 不符 → 下载 → 又被当成已存在文件复用」的死循环
            try {
                Files.deleteIfExists(i.path);
            } catch (Exception ignored) {
            }
            specs.add(new DownloadManager.FileSpec(i.url, i.path, i.size, i.sha1, true));
        }

        DownloadManager.get().downloadAll("依赖库 (" + v.id + ")",
                specs, Math.max(1, cfg.downloadThreads), null);
        r.repaired = specs.size();

        // 补完再核一遍：镜像里也可能缺文件，这种情况必须让用户知道，而不是进游戏再崩
        for (LibraryResolver.Item i : r.broken) {
            if (!ok(i)) r.stillBroken.add(i);
        }
        if (log != null) {
            if (r.stillBroken.isEmpty()) {
                log.accept("[依赖库] 依赖库已修复（" + r.repaired + " 个文件）");
            } else {
                log.accept("[依赖库] 仍有 " + r.stillBroken.size() + " 个依赖库无法补齐，"
                        + "多半是镜像缺文件：可在「设置」里关闭国内镜像后重试，或重新安装该版本");
                for (LibraryResolver.Item i : r.stillBroken) {
                    log.accept("[依赖库]   失败：" + i.label());
                }
            }
        }
        return r;
    }

    /** 单个条目是否可用：存在、非空、大小对（有的话）、sha1 对（有的话）。 */
    private static boolean ok(LibraryResolver.Item item) {        if (!Files.isRegularFile(item.path)) return false;
        long actual = sizeOf(item.path);
        if (actual <= 0) return false;
        if (item.size > 0 && actual != item.size) return false;
        return item.sha1 == null || Http.hashMatches(item.path, item.sha1);
    }

    /**
     * 用「少了分类器的错名文件」修好目标文件：{@code x-1.0-universal.jar} ← {@code x-1.0.jar}。
     * 只在错名文件确实是「模组 jar」（内含 *mods.toml）时才复制，避免把打补丁的客户端 jar 当成模组。
     */
    private static boolean repairFromSibling(Path target) {
        String name = target.getFileName().toString();
        int dash = name.lastIndexOf('-');
        if (dash < 0 || !name.endsWith(".jar")) return false;
        Path sibling = target.resolveSibling(name.substring(0, dash) + ".jar");
        if (sibling.equals(target)) return false;
        if (!Files.isRegularFile(sibling) || sizeOf(sibling) <= 0) return false;
        if (!hasModDescriptor(sibling)) return false;
        try {
            Files.createDirectories(target.getParent());
            Files.copy(sibling, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** jar 里是否带 FML 的模组描述文件（META-INF/*mods.toml）。 */
    private static boolean hasModDescriptor(Path jar) {
        try (java.util.zip.ZipFile zf = new java.util.zip.ZipFile(jar.toFile())) {
            return zf.stream().anyMatch(e -> {
                String n = e.getName();
                return n.startsWith("META-INF/") && n.endsWith("mods.toml");
            });
        } catch (Exception e) {
            return false;
        }
    }

    /** 说明这个条目为什么被判为坏，方便用户在日志里看懂。 */    private static String whyBroken(LibraryResolver.Item item) {
        if (!Files.isRegularFile(item.path)) return "缺失";
        long actual = sizeOf(item.path);
        if (actual <= 0) return "空文件";
        if (item.size > 0 && actual != item.size) {
            return "大小不符：期望 " + item.size + "，实际 " + actual;
        }
        if (item.sha1 != null && !Http.hashMatches(item.path, item.sha1)) return "SHA-1 不符";
        return "未知";
    }

    private static long sizeOf(Path p) {
        try {
            return Files.size(p);
        } catch (Exception e) {
            return -1;
        }
    }
}
