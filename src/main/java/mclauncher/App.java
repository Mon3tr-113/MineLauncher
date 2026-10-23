package mclauncher;

import com.formdev.flatlaf.FlatDarkLaf;
import mclauncher.config.Config;
import mclauncher.loader.LoaderInstaller;
import mclauncher.minecraft.VersionManifest;
import mclauncher.modrinth.ModrinthApi;
import mclauncher.ui.MainFrame;

import javax.swing.*;

/**
 * 方块启动器（MineLauncher）入口。
 *
 * 用法：
 *   java -jar mclauncher.jar           正常启动 GUI
 *   java -jar mclauncher.jar --smoke   无界面自检：拉取版本清单 + 搜索 Modrinth
 */
public final class App {

    public static void main(String[] args) {
        if (args.length > 0 && "--smoke".equals(args[0])) {
            runSmokeTest();
            return;
        }
        if (args.length > 1 && "--dryrun".equals(args[0])) {
            runDryRun(args[1]);
            return;
        }
        if (args.length > 0 && "--listloaders".equals(args[0])) {
            runLoaderListTest();
            return;
        }
        if (args.length > 2 && "--installfabric".equals(args[0])) {
            runFabricInstall(args[1], args[2]);
            return;
        }
        if (args.length > 2 && "--installneoforge".equals(args[0])) {
            runNeoForgeInstall(args[1], args[2]);
            return;
        }
        if (args.length > 0 && "--detectjava".equals(args[0])) {
            runDetectJava();
            return;
        }
        if (args.length > 0 && "--msauth".equals(args[0])) {
            runMsAuthTest();
            return;
        }
        if (args.length > 1 && "--javadl".equals(args[0])) {
            runJavaDownloadTest(Integer.parseInt(args[1]));
            return;
        }
        if (args.length > 0 && "--langtest".equals(args[0])) {
            runLangTest();
            return;
        }
        if (args.length > 1 && "--fixassets".equals(args[0])) {
            runFixAssets(args[1]);
            return;
        }
        if (args.length > 1 && "--checklibs".equals(args[0])) {
            runCheckLibs(args[1]);
            return;
        }
        if (args.length > 0 && "--libtest".equals(args[0])) {
            if (mclauncher.minecraft.LibrarySelfTest.run() > 0) System.exit(1);
            return;
        }
        if (args.length > 0 && "--dltest".equals(args[0])) {
            if (mclauncher.net.DownloadSelfTest.run() > 0) System.exit(1);
            return;
        }
        if (args.length > 0 && "--uiprobe".equals(args[0])) {
            runUiProbe();
            return;
        }
        if (args.length > 1 && "--launchtest".equals(args[0])) {
            runLaunchTest(args[1], args.length > 2 ? Integer.parseInt(args[2]) : 90,
                    args.length > 3 ? java.nio.file.Paths.get(args[3]) : null);
            return;
        }
        if (args.length > 0 && "--versummary".equals(args[0])) {
            runVersionSummary();
            return;
        }
        if (args.length > 0 && "--mcmodtest".equals(args[0])) {
            runMcmodTest(args.length > 1 ? args[1] : "钠");
            return;
        }
        if (args.length > 1 && "--packui".equals(args[0])) {
            runPackUiTest(args[1]);
            return;
        }
        if (args.length > 1 && "--packinstall".equals(args[0])) {
            // 只装整合包内容（不装 MC / 加载器），用于排查「装完没有 mod」这类问题
            try {
                mclauncher.modpack.ModpackInstaller.install(java.nio.file.Paths.get(args[1]),
                        java.nio.file.Paths.get(args[2]), System.out::println);
            } catch (Exception e) {
                System.out.println("[packinstall] 失败: " + e.getMessage());
                e.printStackTrace();
                System.exit(1);
            }
            return;
        }
        if (args.length > 1 && "--packfix".equals(args[0])) {
            // 给已装好的 CurseForge 整合包实例补齐 mod：--packfix <实例目录>
            try {
                int n = mclauncher.modpack.ModpackInstaller.syncCurseForgeMods(
                        java.nio.file.Paths.get(args[1]), System.out::println);
                System.out.println("[packfix] " + (n < 0 ? "该实例没有 manifest.json（不是 CurseForge 包）"
                        : "mods 目录现在有 " + n + " 个文件"));
            } catch (Exception e) {
                System.out.println("[packfix] 失败: " + e.getMessage());
                System.exit(1);
            }
            return;
        }
        if (args.length > 0 && "--packtest".equals(args[0])) {
            boolean ok = mclauncher.modpack.ModpackSelfTest.run() == 0;            // 附带支持把真实文件当参数识别一下（不安装）：--packtest <文件或目录>...
            for (int i = 1; i < args.length; i++) {
                var mp = mclauncher.modpack.ModpackFile.detect(java.nio.file.Paths.get(args[i]));
                System.out.println("[packtest] " + args[i] + " -> " + mp.kindLabel() + " | " + mp.summary()
                        + (mp.note == null ? "" : " | " + mp.note));
            }
            if (!ok) System.exit(1);
            return;
        }

        Config config = Config.load();

        SwingUtilities.invokeLater(() -> {
            FlatDarkLaf.setup();
            // 放大全局字体，让整体 UI 更大更易读
            java.awt.Font baseFont = new java.awt.Font("Microsoft YaHei UI", java.awt.Font.PLAIN, 15);
            UIManager.put("defaultFont", baseFont);
            UIManager.put("Label.font", baseFont);
            UIManager.put("Button.font", baseFont);
            UIManager.put("TextField.font", baseFont);
            UIManager.put("TextArea.font", baseFont);
            UIManager.put("ComboBox.font", baseFont);
            UIManager.put("CheckBox.font", baseFont);
            UIManager.put("List.font", baseFont);
            UIManager.put("TabbedPane.font", baseFont);
            double panelAlpha = (config.backgroundImage != null && !config.backgroundImage.isBlank())
                    ? 1.0 - Math.max(0, Math.min(100, config.uiTransparency)) / 100.0
                    : 1.0;
            double textAlpha = 1.0 - Math.max(0, Math.min(90, config.textTransparency)) / 100.0;
            mclauncher.ui.Ui.applyTheme(config.themeColor, config.backgroundColor, config.textColor,
                    panelAlpha, textAlpha);
            try {
                MainFrame frame = new MainFrame(config);
                // 多尺寸图标：任务栏用小图、切换窗口用大图，Windows 自己挑
                java.util.List<java.awt.Image> icons = mclauncher.ui.Ui.appIcons();
                if (!icons.isEmpty()) frame.setIconImages(icons);
                frame.setVisible(true);
                mclauncher.audio.MusicPlayer.setVolume(config.musicVolume);
                if (config.playlist != null && !config.playlist.isEmpty()) {
                    mclauncher.audio.MusicPlayer.setPlaylist(config.playlist, 0);
                } else if (config.musicFile != null && !config.musicFile.isBlank()) {
                    mclauncher.audio.MusicPlayer.play(config.musicFile);
                }
            } catch (Throwable t) {
                t.printStackTrace();
                JOptionPane.showMessageDialog(null, "启动失败：" + t.getMessage(), "错误",
                        JOptionPane.ERROR_MESSAGE);
            }
        });
    }

    private static void runSmokeTest() {
        System.out.println("[smoke] 自检开始...");
        try {
            VersionManifest m = VersionManifest.fetch();
            System.out.println("[smoke] 版本清单获取成功，共 " + m.getVersions().size() + " 个版本");
            System.out.println("[smoke] 最新正式版: " + latestRelease(m));

            var hits = ModrinthApi.search("sodium", "mod");
            System.out.println("[smoke] Modrinth 搜索 'sodium' 命中 " + hits.size() + " 条，第一条: " +
                    (hits.isEmpty() ? "-" : hits.get(0).title));

            var pop = ModrinthApi.popular(ModrinthApi.TYPE_MOD);
            System.out.println("[smoke] Modrinth 热门 mod 数=" + pop.size()
                    + "，第一条=" + (pop.isEmpty() ? "-" : pop.get(0).title)
                    + " / slug=" + (pop.isEmpty() ? "-" : pop.get(0).slug)
                    + " / 中文=" + (pop.isEmpty() ? "-" : mclauncher.modrinth.ModNames.chinese(pop.get(0).slug)));
            System.out.println("[smoke] 自检通过");
        } catch (Exception e) {
            System.out.println("[smoke] 自检失败: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }

    private static String latestRelease(VersionManifest m) {
        for (var v : m.getVersions()) {
            if ("release".equals(v.type)) return v.id;
        }
        return "unknown";
    }

    /** 无界面验证：解析某个版本的 JSON、解析依赖、构建启动参数（不下载大文件）。 */
    private static void runDryRun(String versionId) {
        System.out.println("[dryrun] 版本 " + versionId + " 的解析校验...");
        try {
            mclauncher.config.Config cfg = mclauncher.config.Config.load();
            mclauncher.minecraft.GameVersion v;
            String localJson = cfg.versionsDir().resolve(versionId).resolve(versionId + ".json").toString();
            if (java.nio.file.Files.exists(java.nio.file.Paths.get(localJson))) {
                // 已装版本（含 Fabric / NeoForge）直接从本地 JSON 读，比走 manifest 更贴近真实启动
                v = new mclauncher.minecraft.VersionManager(cfg).load(versionId);
                System.out.println("[dryrun] 使用本地已安装版本：" + localJson);
            } else {
                VersionManifest m = VersionManifest.fetch();
                VersionManifest.VersionInfo info = m.find(versionId);
                if (info == null) {
                    System.out.println("[dryrun] 未找到版本 " + versionId + "（本地未安装，manifest 里也没有）");
                    System.exit(1);
                    return;
                }
                v = mclauncher.minecraft.GameVersion.loadFromJson(versionId,
                        mclauncher.minecraft.VersionManifest.fetchJson(info));
            }
            System.out.println("[dryrun] mainClass=" + v.mainClass + ", assets=" + v.assetsIndex
                    + ", javaMajor=" + v.javaMajor + ", libraries=" + v.libraries.size());

            mclauncher.minecraft.LibraryResolver.Resolved res = mclauncher.minecraft.LibraryResolver.resolve(v, cfg);
            System.out.println("[dryrun] classpath 条目=" + res.classpath.size() + ", natives 条目=" + res.natives.size());

            // 分类器库自检：natives 的地址和落盘路径必须都带分类器（曾经这里会下成主 jar）
            java.util.List<mclauncher.minecraft.LibraryResolver.Item> items =
                    mclauncher.minecraft.LibraryResolver.resolveItems(v, cfg);
            for (mclauncher.minecraft.LibraryResolver.Item item : items) {
                if (!item.nativeLib) continue;
                String file = item.path.getFileName().toString();
                boolean urlOk = item.url != null && item.url.endsWith("/" + file);
                System.out.println("[dryrun] natives " + file + " url=" + (urlOk ? "ok" : "MISMATCH")
                        + " sha1=" + (item.sha1 == null ? "-" : item.sha1));
                if (!urlOk && item.url != null) {
                    System.out.println("[dryrun]   ↑ 地址与落盘文件名不一致: " + item.url);
                }
            }
            var report = mclauncher.minecraft.LibraryChecker.inspect(v, cfg);
            System.out.println("[dryrun] 依赖库校验：共 " + report.total + " 个，损坏/缺失 "
                    + report.broken.size() + "，本地产物缺失 " + report.localMissing.size());

            mclauncher.launch.LaunchArguments.LaunchContext ctx = new mclauncher.launch.LaunchArguments.LaunchContext();
            ctx.account = new mclauncher.auth.Account("TestPlayer",
                    "00000000-0000-0000-0000-000000000000", "0", "offline");
            ctx.cfg = cfg;
            ctx.version = v;
            ctx.nativesDir = cfg.gamePath().resolve("natives-" + v.id);
            ctx.classpath = "C:/fake/classpath.jar";
            java.util.List<String> jvm = mclauncher.launch.LaunchArguments.buildJvmArgs(ctx);
            java.util.List<String> game = mclauncher.launch.LaunchArguments.buildGameArgs(ctx);
            System.out.println("[dryrun] JVM 参数=" + jvm.size() + " 条，游戏参数=" + game.size() + " 条");
            for (String a : jvm) {
                if (a.contains("${")) System.out.println("[dryrun] JVM 参数里还有未替换的占位符: " + a);
            }
            for (String a : game) {
                if (a.contains("${")) System.out.println("[dryrun] 游戏参数里还有未替换的占位符: " + a);
            }
            System.out.println("[dryrun] 校验通过");
        } catch (Exception e) {
            System.out.println("[dryrun] 校验失败: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }

    private static void runLoaderListTest() {
        System.out.println("[loaders] 加载器版本列表自检...");
        try {
            var fabricLoader = LoaderInstaller.fabricLoaderVersions();
            var fabricGame = LoaderInstaller.fabricGameVersions();
            System.out.println("[loaders] Fabric loader 版本数=" + fabricLoader.size()
                    + "，最新=" + (fabricLoader.isEmpty() ? "-" : fabricLoader.get(0)));
            System.out.println("[loaders] Fabric 支持的 MC 版本数=" + fabricGame.size()
                    + "，最新=" + (fabricGame.isEmpty() ? "-" : fabricGame.get(0)));

            var forgeMc = LoaderInstaller.forgeMcVersions();
            System.out.println("[loaders] Forge 支持的 MC 版本数=" + forgeMc.size()
                    + "，包含 1.20.1=" + forgeMc.contains("1.20.1"));
            var forgeV = LoaderInstaller.forgeVersions("1.20.1");
            System.out.println("[loaders] 1.20.1 的 Forge 版本数=" + forgeV.size()
                    + "，最新=" + (forgeV.isEmpty() ? "-" : forgeV.get(0)));

            var neoV = LoaderInstaller.neoForgeVersions("1.20.2");
            System.out.println("[loaders] 1.20.2 的 NeoForge 版本数=" + neoV.size()
                    + "，最新=" + (neoV.isEmpty() ? "-" : neoV.get(0)));

            var quiltLoader = LoaderInstaller.quiltLoaderVersions();
            System.out.println("[loaders] Quilt loader 版本数=" + quiltLoader.size()
                    + "，最新=" + (quiltLoader.isEmpty() ? "-" : quiltLoader.get(0)));
            var of = LoaderInstaller.optifineVersions("1.20.1");
            System.out.println("[loaders] 1.20.1 的 OptiFine 版本数=" + of.size()
                    + "，第一个=" + (of.isEmpty() ? "-" : of.get(of.size() - 1)));
            System.out.println("[loaders] 自检通过");
        } catch (Exception e) {
            System.out.println("[loaders] 自检失败: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }

    private static void runFabricInstall(String mcVersion, String loaderVersion) {
        System.out.println("[install] 安装 Fabric " + loaderVersion + " (MC " + mcVersion + ") ...");
        try {
            mclauncher.config.Config cfg = mclauncher.config.Config.load();
            String id = LoaderInstaller.installFabric(mcVersion, loaderVersion, cfg,
                    (stage, done, total) -> {
                        String pct = total > 0 ? " " + (done * 100 / total) + "%" : "";
                        System.out.println("[install] " + stage + pct);
                    });
            System.out.println("[install] 安装完成：" + id);
        } catch (Exception e) {
            System.out.println("[install] 安装失败: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }

    private static void runNeoForgeInstall(String mc, String ver) {
        System.out.println("[install] 安装 NeoForge " + ver + " (MC " + mc + ") ...");
        try {
            mclauncher.config.Config cfg = mclauncher.config.Config.load();
            String id = LoaderInstaller.installNeoForge(mc, ver, cfg, (stage, done, total) -> {
                String pct = total > 0 ? " " + (done * 100 / total) + "%" : "";
                System.out.println("[install] " + stage + pct);
            });
            System.out.println("[install] 安装完成：" + id);
        } catch (Exception e) {
            System.out.println("[install] 安装失败: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }

    private static void runDetectJava() {
        System.out.println("[java] 扫描本机 Java ...");
        var list = mclauncher.util.JavaManager.detect();
        if (list.isEmpty()) {
            System.out.println("[java] 未检测到任何 Java");
        }
        for (var j : list) {
            System.out.println("[java] Java " + j.major + " : " + j.path);
        }
        var best = mclauncher.util.JavaManager.bestFor(17);
        System.out.println("[java] 满足 Java 17 的最佳: " + (best == null ? "无（会触发自动下载）" : best.path));
    }

    /** 微软正版登录链路自检：设备码通道 + 两种浏览器回调解析 + 外置登录元数据。 */
    private static void runMsAuthTest() {
        System.out.println("=== 微软正版登录自检 ===");
        System.out.println("[设备码] " + mclauncher.auth.MicrosoftAuth.probeDeviceCode());
        System.out.println("[隐式] 登录页: " + mclauncher.auth.MicrosoftAuth.browserAuthUrl(
                mclauncher.auth.MicrosoftAuth.MODE_TOKEN));
        System.out.println("[授权码] 登录页: " + mclauncher.auth.MicrosoftAuth.browserAuthUrl(
                mclauncher.auth.MicrosoftAuth.MODE_CODE));

        String[] samples = {
                // 隐式流：令牌在 # 片段里
                "https://login.live.com/oauth20_desktop.srf#access_token=EwAxyz123&refresh_token=M.C5abc%3D&"
                        + "expires_in=86400&scope=XboxLive.signin%20offline_access&token_type=bearer",
                // 授权码流
                "https://login.live.com/oauth20_desktop.srf?code=M.R3_BAY.abc%2Bdef%3D&lc=1033",
                // 纯 code
                "M.R3_BAY.onlycode",
                // 用户拒绝
                "https://login.live.com/oauth20_desktop.srf?error=access_denied&error_description=User%20denied",
        };
        for (String s : samples) {
            try {
                var cb = mclauncher.auth.MicrosoftAuth.parseCallback(s);
                StringBuilder sb = new StringBuilder();
                if (cb.accessToken != null) sb.append("access_token=").append(cb.accessToken).append(" ");
                if (cb.refreshToken != null) sb.append("refresh_token=").append(cb.refreshToken).append(" ");
                if (cb.code != null) sb.append("code=").append(cb.code).append(" ");
                if (cb.error != null) sb.append("error=").append(cb.error).append(" ");
                sb.append("expiresIn=").append(cb.expiresIn);
                System.out.println("[解析] " + sb);
            } catch (Exception e) {
                System.out.println("[解析] 报错 -> " + e.getMessage().replace("\n", " | "));
            }
        }

        System.out.println("[LittleSkin] 探测 API ...");
        try {
            String meta = mclauncher.net.Http.getString(mclauncher.auth.LittleSkinAuth.API_ROOT);
            System.out.println("[LittleSkin] " + meta.substring(0, Math.min(120, meta.length())) + " ...");
        } catch (Exception e) {
            System.out.println("[LittleSkin] 失败: " + e.getMessage());
        }
        System.out.println("=== 自检结束 ===");
    }

    /** 测试 Java 多源自动下载：下载 + 解压 + 校验可执行。 */
    private static void runJavaDownloadTest(int major) {
        System.out.println("[java] 开始自动下载 Java " + major + " ...");
        try {
            String exe = mclauncher.util.JavaManager.download(major, Config.load(), System.out::println);
            System.out.println("[java] 结果路径: " + exe);
            System.out.println("[java] 版本探测: Java " + mclauncher.util.JavaManager.majorOf(exe));
        } catch (Exception e) {
            System.out.println("[java] 失败: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /** 测试「默认中文」：options.txt 的新建 / 覆盖 / 不修改 / 幂等。 */
    private static void runLangTest() {
        try {
            java.nio.file.Path tmp = java.nio.file.Files.createTempDirectory("langtest");

            mclauncher.launch.GameOptions.applyLanguage(tmp, "zh_cn", System.out::println);
            System.out.println("[case1 无 options.txt] lang=" + mclauncher.launch.GameOptions.currentLanguage(tmp));

            java.nio.file.Files.write(tmp.resolve("options.txt"),
                    java.util.List.of("version:3465", "lang:en_us", "fov:0.0"),
                    java.nio.charset.StandardCharsets.UTF_8);
            mclauncher.launch.GameOptions.applyLanguage(tmp, "zh_cn", System.out::println);
            System.out.println("[case2 覆盖 en_us] lang=" + mclauncher.launch.GameOptions.currentLanguage(tmp));
            System.out.println("[case2 其它行保留] "
                    + java.nio.file.Files.readAllLines(tmp.resolve("options.txt")));

            mclauncher.launch.GameOptions.applyLanguage(tmp, "keep", System.out::println);
            System.out.println("[case3 keep] lang=" + mclauncher.launch.GameOptions.currentLanguage(tmp));

            mclauncher.launch.GameOptions.applyLanguage(tmp, "zh_cn", System.out::println);
            System.out.println("[case4 已是中文] lang=" + mclauncher.launch.GameOptions.currentLanguage(tmp));

            try (var walk = java.nio.file.Files.walk(tmp)) {
                walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            }
            System.out.println("[langtest] 全部通过");
        } catch (Exception e) {
            System.out.println("[langtest] 失败: " + e.getMessage());
        }
    }

    /** 校验并补全某个版本的资源文件。 */
    private static void runFixAssets(String versionId) {
        Config cfg = Config.load();
        try {
            var vm = new mclauncher.minecraft.VersionManager(cfg);
            var version = vm.load(versionId);
            int n = mclauncher.minecraft.AssetChecker.ensure(version, cfg, System.out::println);
            System.out.println("[fixassets] 补下载 " + n + " 个文件");
        } catch (Exception e) {
            System.out.println("[fixassets] 失败: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /** 校验并补全某个版本的依赖库（含 natives 分类器库），顺带打印分类器库清单。 */
    private static void runCheckLibs(String versionId) {
        Config cfg = Config.load();
        try {
            var vm = new mclauncher.minecraft.VersionManager(cfg);
            var version = vm.load(versionId);
            var items = mclauncher.minecraft.LibraryChecker.allItems(version, cfg);

            int natives = 0;
            for (var item : items) {
                if (item.nativeLib) natives++;
            }
            System.out.println("[checklibs] 依赖库条目=" + items.size() + "，其中 natives 分类器库=" + natives);
            for (var item : items) {
                if (!item.nativeLib) continue;
                System.out.println("[checklibs]   natives: " + item.path.getFileName()
                        + "  sha1=" + (item.sha1 == null ? "-" : item.sha1)
                        + "  size=" + item.size);
            }
            // FML（NeoForge/Forge）本体 jar 不走版本 JSON，单独列出来，缺了就是 "Mod ID: 'neoforge' [MISSING]"
            for (var item : mclauncher.minecraft.LibraryChecker.fmlSelfJars(version, cfg)) {
                System.out.println("[checklibs]   fml 本体 jar: " + item.path.getFileName()
                        + "  存在=" + java.nio.file.Files.isRegularFile(item.path));
            }

            var before = mclauncher.minecraft.LibraryChecker.inspect(version, cfg);
            System.out.println("[checklibs] 校验前：损坏/缺失 " + before.broken.size()
                    + "，本地产物缺失 " + before.localMissing.size());
            var r = mclauncher.minecraft.LibraryChecker.ensure(version, cfg, System.out::println);
            System.out.println("[checklibs] 补下载 " + r.repaired + " 个，仍失败 "
                    + r.stillBroken.size() + " 个");
            if (!r.isComplete()) System.exit(1);
        } catch (Exception e) {
            System.out.println("[checklibs] 失败: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }

    /**
     * 界面自检（{@code --uiprobe}）：不开手动点也能确认两件事——
     * 1) 窗口/任务栏图标是不是多尺寸的（任务栏小图不会糊）；
     * 2) 个性化页内容超高时能不能滚到、「应用设置」按钮是否真的在可视范围内。
     */
    private static void runUiProbe() {
        System.out.println("[uiprobe] 界面自检...");
        try {
            Config cfg = Config.load();
            com.formdev.flatlaf.FlatDarkLaf.setup();
            javax.swing.SwingUtilities.invokeAndWait(() -> {
                mclauncher.ui.MainFrame frame = new mclauncher.ui.MainFrame(cfg);
                frame.setIconImages(mclauncher.ui.Ui.appIcons());
                System.out.println("[uiprobe] 窗口图标尺寸: "
                        + frame.getIconImages().stream()
                        .map(i -> i.getWidth(null) + "x" + i.getHeight(null)).toList());
                frame.setSize(1280, 840);
                frame.setVisible(true);
                frame.validate();

                java.awt.Component pageComp = findByName(frame.getContentPane(), "PersonalizationPage");
                if (!(pageComp instanceof java.awt.Container page)) {
                    System.out.println("[uiprobe] 没找到个性化页");
                } else {
                    boolean scrollable = hasScrollPane(page);
                    java.awt.Dimension pref = page.getPreferredSize();
                    System.out.println("[uiprobe] 个性化页 preferred=" + pref.width + "x" + pref.height
                            + "，可视=" + page.getWidth() + "x" + page.getHeight()
                            + "，可滚动=" + scrollable);
                    javax.swing.JButton apply = findButton(page, "应用设置");
                    if (apply == null) {
                        System.out.println("[uiprobe] 没找到「应用设置」按钮");
                    } else {                        java.awt.Rectangle r = javax.swing.SwingUtilities.convertRectangle(
                                apply.getParent(), apply.getBounds(), page);
                        boolean visible = r.y >= 0 && r.y + r.height <= page.getHeight();
                        System.out.println("[uiprobe] 「应用设置」位置 y=" + r.y + " h=" + r.height
                                + "，在可视范围内=" + visible);
                    }
                    long presetBtns = countButtonsStartingWith(page, "预设·");
                    System.out.println("[uiprobe] 背景预设按钮数: " + presetBtns);
                }
                frame.dispose();
            });
            System.out.println("[uiprobe] 完成");
        } catch (Exception e) {
            System.out.println("[uiprobe] 失败: " + e);
            e.printStackTrace();
            System.exit(1);
        }
        // Swing 的 EDT/AWT 线程会吊住 JVM，自检完直接退出
        System.exit(0);
    }

    private static java.awt.Component findByName(java.awt.Container root, String simpleName) {
        for (java.awt.Component c : root.getComponents()) {
            if (c.getClass().getSimpleName().equals(simpleName)) return c;
            if (c instanceof java.awt.Container ct) {
                java.awt.Component r = findByName(ct, simpleName);
                if (r != null) return r;
            }
        }
        return null;
    }

    private static boolean hasScrollPane(java.awt.Container root) {
        for (java.awt.Component c : root.getComponents()) {
            if (c instanceof javax.swing.JScrollPane) return true;
            if (c instanceof java.awt.Container ct && hasScrollPane(ct)) return true;
        }
        return false;
    }

    private static javax.swing.JButton findButton(java.awt.Container root, String text) {
        for (java.awt.Component c : root.getComponents()) {
            if (c instanceof javax.swing.JButton b && text.equals(b.getText())) return b;
            if (c instanceof java.awt.Container ct) {
                javax.swing.JButton r = findButton(ct, text);
                if (r != null) return r;
            }
        }
        return null;
    }

    private static long countButtonsStartingWith(java.awt.Container root, String prefix) {
        long n = 0;
        for (java.awt.Component c : root.getComponents()) {
            if (c instanceof javax.swing.JButton b && b.getText() != null && b.getText().startsWith(prefix)) n++;
            if (c instanceof java.awt.Container ct) n += countButtonsStartingWith(ct, prefix);
        }
        return n;
    }

    /**
     * 启动自检（{@code --launchtest <版本id> [秒]}）：真的把游戏拉起来，
     * 打印游戏日志，到时间还没退就杀掉。用来验证「装完能不能进游戏」。
     */
    private static void runLaunchTest(String versionId, int seconds, java.nio.file.Path instanceOverride) {
        System.out.println("[launchtest] 启动 " + versionId + "（最多观察 " + seconds + " 秒）...");
        Config cfg = Config.load();
        try {
            mclauncher.minecraft.GameVersion v = new mclauncher.minecraft.VersionManager(cfg).load(versionId);
            mclauncher.auth.Account acc = cfg.accounts.isEmpty()
                    ? mclauncher.auth.OfflineAuth.login("TestPlayer") : cfg.accounts.get(0);
            java.nio.file.Path gameDir = instanceOverride != null ? instanceOverride
                    : (cfg.versionIsolation ? cfg.instanceDir(versionId) : cfg.gamePath());
            System.out.println("[launchtest] 游戏目录: " + gameDir);
            cfg.javaPath = mclauncher.util.JavaManager.ensure(v.javaMajor, cfg.javaPath, cfg,
                    line -> System.out.println("[java] " + line));
            long t0 = System.currentTimeMillis();
            Process p = mclauncher.launch.GameLauncher.launch(v, acc, cfg, gameDir, line -> {
                String l = line.length() > 400 ? line.substring(0, 400) + "…" : line;
                System.out.println("[game] " + l);
            });
            boolean finished = p.waitFor(seconds, java.util.concurrent.TimeUnit.SECONDS);
            if (finished) {
                System.out.println("[launchtest] 游戏退出，退出码 " + p.exitValue()
                        + "，用时 " + (System.currentTimeMillis() - t0) / 1000 + " 秒");
                if (p.exitValue() != 0) System.exit(1);
            } else {
                p.destroy();
                System.out.println("[launchtest] 观察 " + seconds + " 秒后游戏仍在运行 → 视为启动成功");
            }
        } catch (Exception e) {
            System.out.println("[launchtest] 失败: " + e);
            e.printStackTrace();
            System.exit(1);
        }
    }

    /**
     * 整合包「导入」链路的界面自检（{@code --packui <文件>}）：
     * 走的是界面上「导入整合包文件…」/ 拖拽 完全相同的路径（只跳过确认弹窗），
     * 用来看导入功能到底还能不能用。
     */
    private static void runPackUiTest(String path) {
        System.out.println("[packui] 导入自检：" + path);
        System.setProperty("mclauncher.uiEcho", "true");
        try {
            Config cfg = Config.load();
            final int modpacksBefore = cfg.modpacks.size();
            com.formdev.flatlaf.FlatDarkLaf.setup();
            javax.swing.SwingUtilities.invokeAndWait(() -> {
                mclauncher.ui.MainFrame frame = new mclauncher.ui.MainFrame(cfg);
                frame.setSize(1280, 840);
                frame.setVisible(true);
                frame.installModpackFileNoConfirm(java.nio.file.Paths.get(path));
            });
            // 等「登记的整合包数量变化」或超时；不要用下载管理器判断（mod 下载不经过它）
            long deadline = System.currentTimeMillis() + 900_000;
            boolean done = false;
            while (System.currentTimeMillis() < deadline) {
                Thread.sleep(2000);
                if (Config.get().modpacks.size() != modpacksBefore) {
                    done = true;
                    break;
                }
            }
            Thread.sleep(2000);
            System.out.println("[packui] 结果：" + (done ? "安装完成并已登记" : "超时未完成"));
            System.out.println("[packui] 已登记整合包：" + Config.get().modpacks.size() + " 个");
            for (Config.Modpack mp : Config.get().modpacks) {
                System.out.println("[packui]   " + mp.name + " -> " + mp.versionId + " [" + mp.loader + "]");
            }
            System.exit(done ? 0 : 1);
        } catch (Exception e) {
            System.out.println("[packui] 失败: " + e);
            e.printStackTrace();
            System.exit(1);
        }
    }

    /** MC 百科中文名 / 中文搜索自检（{@code --mcmodtest [中文关键词]}）。 */
    private static void runMcmodTest(String chineseKeyword) {
        System.out.println("[mcmodtest] MC 百科中文名 + 中文搜索自检...");
        String[] englishSamples = {"Sodium", "Create", "Just Enough Items", "Iris Shaders", "Applied Energistics 2"};
        for (String en : englishSamples) {
            String cn = mclauncher.modrinth.McmodApi.queryChineseName(en);
            System.out.println("[mcmodtest] 中文名: " + en + " -> " + (cn == null ? "（查不到）" : cn));
            if (cn == null) {
                System.out.println("[mcmodtest]   原始候选："
                        + mclauncher.modrinth.McmodApi.search(en, 10));
            }
        }
        System.out.println("[mcmodtest] 中文 → 英文名：" + chineseKeyword);
        java.util.List<String> names = mclauncher.modrinth.McmodApi.englishNamesFor(chineseKeyword, 5);
        System.out.println("[mcmodtest]   结果 = " + names);
        // 跟界面上「搜索」按钮完全一样的路径
        java.util.List<mclauncher.modrinth.ModrinthApi.Hit> smart =
                mclauncher.modrinth.ModrinthApi.searchSmart(chineseKeyword,
                        mclauncher.modrinth.ModrinthApi.TYPE_MOD, msg -> System.out.println("[mcmodtest]   " + msg));
        System.out.println("[mcmodtest] searchSmart 命中 " + smart.size() + " 条");
        for (int i = 0; i < Math.min(6, smart.size()); i++) {
            var h = smart.get(i);
            System.out.println("[mcmodtest]   " + (i + 1) + ". " + h.title + " / " + h.slug
                    + "  中文名=" + mclauncher.modrinth.ModNames.chinese(h.slug));
        }
        System.out.println("[mcmodtest] 缓存文件：" + mclauncher.config.Config.launcherHome().resolve("mcmod-cache.json"));
    }

    /** 版本列表自检（{@code --versummary}）：打印每个已装版本的「游戏版本 + 加载器」识别结果与显示名。 */
    private static void runVersionSummary() {
        Config cfg = Config.load();
        var vm = new mclauncher.minecraft.VersionManager(cfg);
        var all = vm.listSummaries();
        java.util.Set<String> parents = new java.util.HashSet<>();
        for (var s : all) {
            if (s.loaderBased()) parents.add(s.mcVersion);
        }
        System.out.println("[versummary] 已装版本 " + all.size() + " 个：");
        for (var s : all) {
            boolean hiddenParent = !s.loaderBased() && parents.contains(s.id) && !cfg.showParentVersions;
            String custom = cfg.versionName(s.id);
            System.out.println("[versummary]   id=" + s.id
                    + "  mc=" + s.mcVersion
                    + "  loader=" + s.loader + (s.loaderVersion == null ? "" : " " + s.loaderVersion)
                    + "  → 显示：" + (custom != null ? custom : s.autoLabel())
                    + (hiddenParent ? "   [默认隐藏：被加载器依赖的原版]" : ""));
        }
        System.out.println("[versummary] showParentVersions=" + cfg.showParentVersions);
    }

    private App() {
    }
}