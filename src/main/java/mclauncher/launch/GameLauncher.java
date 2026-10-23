package mclauncher.launch;

import mclauncher.auth.Account;
import mclauncher.config.Config;
import mclauncher.minecraft.GameVersion;
import mclauncher.minecraft.LibraryResolver;
import mclauncher.util.Platform;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** 组装命令并拉起 java 进程。 */
public final class GameLauncher {

    private GameLauncher() {
    }

    /** 启动游戏；gameDir 为实际游戏目录（版本隔离时为实例目录）。 */
    public static Process launch(GameVersion version, Account account, Config cfg, Path gameDir, Consumer<String> log) throws Exception {
        Files.createDirectories(gameDir);
        for (String sub : List.of("mods", "saves", "config", "shaderpacks", "resourcepacks", "logs")) {
            Files.createDirectories(gameDir.resolve(sub));
        }

        // 默认中文：启动前把 options.txt 的 lang 写对
        mclauncher.launch.GameOptions.applyLanguage(gameDir, cfg.gameLang, log);

        LibraryResolver.Resolved resolved = LibraryResolver.resolve(version, cfg);

        // 解压 natives
        Path nativesDir = gameDir.resolve("natives-" + version.id);
        deleteRecursively(nativesDir);
        Files.createDirectories(nativesDir);
        for (Path np : resolved.natives) {
            if (!Files.exists(np)) {
                log.accept("[警告] natives 库缺失（缺 .dll 会直接 UnsatisfiedLinkError）：" + np.getFileName()
                        + "；可在启动页点「校验依赖库」自动补下，或重新安装该版本");
                continue;
            }
            try {
                extractZip(np, nativesDir);
            } catch (IOException e) {
                // 分类器库被下成了主 jar / 被截断时，这里会炸 ZipException：别静默跳过，直接说清楚
                log.accept("[警告] natives 库无法解压（文件可能损坏，建议点「校验依赖库」修复）："
                        + np.getFileName() + " —— " + e.getMessage());
            }
        }

        // classpath（必须去重：加载器版本的 JSON 会重复声明父版本已有的库，
        // 比如 NeoForge 和 1.21.1 都声明 gson，重复项会让 securejarhandler 抛 Duplicate key）
        List<Path> cp = new ArrayList<>(new java.util.LinkedHashSet<>(resolved.classpath));
        List<String> missLibs = new ArrayList<>();
        for (Path p : cp) {
            if (!Files.exists(p)) missLibs.add(p.getFileName().toString());
        }
        if (!missLibs.isEmpty()) {
            log.accept("[警告] 缺少 " + missLibs.size() + " 个依赖库，启动大概率失败："
                    + String.join(", ", missLibs.subList(0, Math.min(6, missLibs.size())))
                    + (missLibs.size() > 6 ? " ..." : "")
                    + "；可在启动页点「校验依赖库」自动补下");
        }

        // 版本 jar：FML 系（Forge 1.17+ / NeoForge）用的是安装器打的补丁客户端，
        // 缺了它绝不能拿原版 jar 顶上 —— 那会在模块解析阶段抛
        // "Module minecraft contains package net.minecraft.server, module _1._20._1 exports …"
        Path clientJar = cfg.versionsDir().resolve(version.id).resolve(version.id + ".jar");
        boolean fml = mclauncher.minecraft.LibraryChecker.isFmlVersion(version);
        if (!Files.isRegularFile(clientJar) && mclauncher.minecraft.LibraryChecker.ensureClientJar(version, cfg, log)) {
            log.accept("[依赖库] 已补齐版本 jar：" + clientJar.getFileName());
        }
        if (Files.isRegularFile(clientJar)) {
            cp.add(clientJar);
        } else if (!fml) {
            // Fabric / Quilt / Forge 1.12 这类：客户端 jar 来自父版本（或原版）
            Path parentJar = null;
            if (version.inheritsFrom != null) {
                parentJar = cfg.versionsDir().resolve(version.inheritsFrom)
                        .resolve(version.inheritsFrom + ".jar");
            }
            if (parentJar != null && Files.isRegularFile(parentJar)) {
                clientJar = parentJar;
                cp.add(clientJar);
            } else {
                log.accept("[警告] 找不到版本 jar 也没有父版本 jar，启动会失败：" + clientJar);
            }
        } else {
            log.accept("[警告] FML 版本缺少 versions/" + version.id + "/" + version.id + ".jar，"
                    + "自动补齐也失败；请重新安装该版本（否则会在模块解析阶段崩溃）");
        }
        String classpath = String.join(Platform.pathSeparator(), cp.stream().map(Path::toString).toList());

        LaunchArguments.LaunchContext ctx = new LaunchArguments.LaunchContext();
        ctx.account = account;
        ctx.cfg = cfg;
        ctx.version = version;
        ctx.nativesDir = nativesDir;
        ctx.classpath = classpath;
        ctx.gameDir = gameDir;

        // 外置登录（LittleSkin 等）：注入 authlib-injector
        if (account.isYggdrasil()) {
            ctx.authlibInjectorJar = mclauncher.auth.AuthlibInjector.ensure(cfg, log);
            ctx.yggdrasilServer = account.server;
            log.accept("[外置登录] 已注入 authlib-injector，服务器：" + account.server);
        }

        List<String> jvmArgs = LaunchArguments.buildJvmArgs(ctx);
        List<String> gameArgs = LaunchArguments.buildGameArgs(ctx);

        List<String> cmd = new ArrayList<>();
        cmd.add(cfg.javaPath);
        cmd.addAll(jvmArgs);
        cmd.addAll(gameArgs);

        log.accept("[启动] " + String.join(" ", cmd));

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(gameDir.toFile());
        pb.redirectErrorStream(true);
        Process process = pb.start();

        Thread pump = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    log.accept(line);
                }
            } catch (IOException ignored) {
            }
        }, "game-log-pump");
        pump.setDaemon(true);
        pump.start();

        return process;
    }

    private static void extractZip(Path zipFile, Path destDir) throws IOException {
        try (ZipInputStream zin = new ZipInputStream(Files.newInputStream(zipFile))) {
            ZipEntry entry;
            while ((entry = zin.getNextEntry()) != null) {
                if (entry.isDirectory()) continue;
                Path out = destDir.resolve(entry.getName()).normalize();
                if (!out.startsWith(destDir)) continue; // 防 zip slip
                Files.createDirectories(out.getParent());
                Files.copy(zin, out, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    private static void deleteRecursively(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                }
            });
        }
    }
}
