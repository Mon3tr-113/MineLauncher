package mclauncher.modpack;

import mclauncher.config.Config;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 整合包识别 / 安装的无网络自检（对应 {@code --packtest}）。
 *
 * <p>重点验证两件过去会出错的事：
 * <ol>
 *   <li><b>识别</b>：能认出 .mrpack、CurseForge zip、普通 zip、已解压实例目录，并读出 MC 版本与加载器；</li>
 *   <li><b>overrides 前缀</b>：{@code overrides/mods/x.jar} 必须落到 {@code instances/<包>/mods/x.jar}，
 *       否则整合包装完游戏里一个 mod 都没有。</li>
 * </ol>
 */
public final class ModpackSelfTest {

    private static int failures;

    private ModpackSelfTest() {
    }

    /** @return 失败断言数（0 表示全部通过） */
    public static int run() {
        failures = 0;
        Path tmp = null;
        try {
            tmp = Files.createTempDirectory("mclauncher-packtest");
            Config cfg = new Config();
            cfg.gameDir = tmp.toString();
            // 关键：把保存路径也指到临时目录，否则 syncFromDisk 里的 cfg.save() 会把测试用的
            // gameDir 写进用户真实的 config.json（踩过这个坑）
            cfg.savePath = tmp.resolve("config.json");
            Files.createDirectories(cfg.instancesDir());

            detectCases(tmp);
            installCases(tmp, cfg);
            syncCase(tmp, cfg);

        } catch (Exception e) {
            fail("自检抛异常: " + e);
            e.printStackTrace();
        } finally {
            if (tmp != null) {
                try (var walk = Files.walk(tmp)) {
                    walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
                } catch (Exception ignored) {
                }
            }
        }
        System.out.println(failures == 0 ? "[packtest] 全部通过" : "[packtest] 失败 " + failures + " 项");
        return failures;
    }

    // ---------- 识别 ----------

    private static void detectCases(Path tmp) throws Exception {
        // 1) Modrinth .mrpack
        Path mrpack = tmp.resolve("Fabulous Test.mrpack");
        writeZip(mrpack, List.of(
                entry("modrinth.index.json", """
                        {"formatVersion":1,"game":"minecraft","name":"Fabulous Test",
                         "dependencies":{"minecraft":"1.20.1","forge":"47.2.0"},
                         "files":[
                           {"path":"mods/a.jar","hashes":{"sha1":"0000000000000000000000000000000000000000"},
                            "downloads":["https://example.invalid/a.jar"],"fileSize":10},
                           {"path":"mods/server-only.jar","hashes":{},"downloads":[],
                            "env":{"client":"unsupported"}}
                         ]}"""),
                entry("overrides/config/test.cfg", "x=1"),
                entry("client-overrides/options.txt", "lang:zh_cn"),
                entry("server-overrides/should-not-copy.txt", "nope")));
        ModpackFile mp = ModpackFile.detect(mrpack);
        eq("mrpack kind", ModpackFile.Kind.MODRINTH, mp.kind);
        eq("mrpack name", "Fabulous Test", mp.name);
        eq("mrpack mc", "1.20.1", mp.mcVersion);
        eq("mrpack loader", "forge", mp.loader);
        eq("mrpack loaderVersion", "47.2.0", mp.loaderVersion);
        eq("mrpack files", 2, mp.declaredFiles);
        eq("mrpack 可启动", true, mp.launchable());

        // 2) CurseForge zip
        Path cf = tmp.resolve("Curse Pack.zip");
        writeZip(cf, List.of(
                entry("manifest.json", """
                        {"minecraft":{"version":"1.21.1",
                          "modLoaders":[{"id":"neoforge-21.1.264","primary":true}]},
                         "name":"Curse Pack",
                         "files":[{"projectID":1,"fileID":2},{"projectID":3,"fileID":4}]}"""),
                entry("overrides/config/x.cfg", "y=2")));
        ModpackFile cfp = ModpackFile.detect(cf);
        eq("cf kind", ModpackFile.Kind.CURSEFORGE, cfp.kind);
        eq("cf mc", "1.21.1", cfp.mcVersion);
        eq("cf loader", "neoforge", cfp.loader);
        eq("cf loaderVersion", "21.1.264", cfp.loaderVersion);
        eq("cf files", 2, cfp.declaredFiles);
        eq("cf 有提醒", true, cfp.note != null);

        // 3) 普通 zip
        Path plain = tmp.resolve("plain.zip");
        writeZip(plain, List.of(
                entry("mods/b.jar", "x"),
                entry("options.txt", "lang:en_us")));
        ModpackFile pp = ModpackFile.detect(plain);
        eq("plain kind", ModpackFile.Kind.PLAIN, pp.kind);
        eq("plain 不可启动（认不出 MC）", false, pp.launchable());

        // 4) 已解压的 Prism 实例目录（mmc-pack.json）
        Path inst = tmp.resolve("PrismInstance");
        Files.createDirectories(inst.resolve("mods"));
        Files.writeString(inst.resolve("mmc-pack.json"), """
                {"formatVersion":1,"components":[
                  {"uid":"net.minecraft","version":"1.20.1"},
                  {"uid":"net.fabricmc.fabric-loader","version":"0.15.11"}]}""", StandardCharsets.UTF_8);
        ModpackFile ip = ModpackFile.detect(inst);
        eq("instance kind", ModpackFile.Kind.INSTANCE_DIR, ip.kind);
        eq("instance mc", "1.20.1", ip.mcVersion);
        eq("instance loader", "fabric", ip.loader);
        eq("instance loaderVersion", "0.15.11", ip.loaderVersion);

        // 5) 被「再压了一层文件夹」的包（很多人解压再压缩会变成这样）
        Path wrapped = tmp.resolve("Wrapped Pack.zip");
        writeZip(wrapped, List.of(
                entry("Wrapped/mods/dummy.jar", "x"),
                entry("Wrapped/config/w.cfg", "y"),
                entry("Wrapped/manifest.json", """
                        {"minecraft":{"version":"1.20.1",
                          "modLoaders":[{"id":"fabric-0.15.11","primary":true}]},
                         "name":"Wrapped Pack","files":[]}""")));
        ModpackFile wp = ModpackFile.detect(wrapped);
        eq("套了一层目录也能认出 CF", ModpackFile.Kind.CURSEFORGE, wp.kind);
        eq("套了一层目录的 mc", "1.20.1", wp.mcVersion);
        eq("套了一层目录的前缀", "Wrapped/", wp.rootPrefix);

        // 6) 不是整合包
        Path junk = tmp.resolve("junk.zip");
        writeZip(junk, List.of(entry("readme.txt", "hi")));
        eq("junk kind", ModpackFile.Kind.UNKNOWN, ModpackFile.detect(junk).kind);
        eq("junk 不可启动", false, ModpackFile.detect(junk).launchable());
    }

    // ---------- 安装（只装内容，不联网、不装 MC） ----------

    private static void installCases(Path tmp, Config cfg) throws Exception {
        Path mrpack = tmp.resolve("Fabulous Test.mrpack");
        ModpackFile mp = ModpackFile.detect(mrpack);
        Path instanceDir = cfg.instanceDir(mp.name);
        ModpackInstaller.install(mrpack, instanceDir, line -> {
        });

        eq("overrides 前缀已去掉（config）", true,
                Files.isRegularFile(instanceDir.resolve("config/test.cfg")));
        eq("client-overrides 也铺平", true,
                Files.isRegularFile(instanceDir.resolve("options.txt")));
        eq("server-overrides 不拷", false,
                Files.exists(instanceDir.resolve("should-not-copy.txt")));
        eq("没有残留 overrides 目录", false, Files.exists(instanceDir.resolve("overrides")));

        // CurseForge：解压 overrides（清单里没有文件 → 不去联网下 mod）
        Path cf = tmp.resolve("Curse Pack Empty.zip");
        writeZip(cf, List.of(
                entry("manifest.json", """
                        {"minecraft":{"version":"1.20.1",
                          "modLoaders":[{"id":"forge-47.4.23","primary":true}]},
                         "name":"Curse Empty","files":[]}"""),
                entry("overrides/config/x.cfg", "y=2")));
        ModpackFile cfp2 = ModpackFile.detect(cf);
        eq("cf(空清单) kind", ModpackFile.Kind.CURSEFORGE, cfp2.kind);
        eq("cf(空清单) 无提醒", null, cfp2.note);
        Path cfDir = cfg.instanceDir("curse-test");
        ModpackInstaller.install(cf, cfDir, line -> {
        });
        eq("cf overrides 铺平", true, Files.isRegularFile(cfDir.resolve("config/x.cfg")));

        // 套了一层目录的包：前缀要被剥掉
        Path wrapped = tmp.resolve("Wrapped Pack.zip");
        Path wrappedDir = cfg.instanceDir("wrapped-test");
        ModpackInstaller.install(wrapped, wrappedDir, line -> {
        });
        eq("套目录包：overrides 铺平", true, Files.isRegularFile(wrappedDir.resolve("config/w.cfg")));
        eq("套目录包：没有残留 Wrapped 目录", false, Files.exists(wrappedDir.resolve("Wrapped")));

        // 普通 zip：保持包内结构
        Path plain = tmp.resolve("plain.zip");
        Path plainDir = cfg.instanceDir("plain-test");
        ModpackInstaller.install(plain, plainDir, line -> {
        });
        eq("plain 保持结构", true, Files.isRegularFile(plainDir.resolve("mods/b.jar")));

        // 实例目录：整棵拷贝
        Path instSrc = tmp.resolve("PrismInstance");
        Path instDst = cfg.instanceDir("inst-test");
        ModpackInstaller.install(instSrc, instDst, line -> {
        });
        eq("实例目录已拷贝", true, Files.isRegularFile(instDst.resolve("mmc-pack.json")));
    }

    // ---------- config 丢失后靠标记文件找回 ----------

    private static void syncCase(Path tmp, Config cfg) throws Exception {
        ModpackInstaller.writeMarker(cfg, "标记测试", "1.20.1-forge-47.2.0", "forge", "1.20.1");
        eq("标记文件已写入", true,
                Files.isRegularFile(cfg.instanceDir("标记测试").resolve(ModpackInstaller.MARKER)));
        // 模拟 config 里没登记
        cfg.modpacks.removeIf(m -> "标记测试".equals(m.name));
        int added = ModpackInstaller.syncFromDisk(cfg);
        eq("syncFromDisk 补登记数量", 1, added);
        eq("syncFromDisk 找回整合包", true, cfg.findModpack("标记测试") != null);
        eq("重复 sync 不再重复登记", 0, ModpackInstaller.syncFromDisk(cfg));
    }

    // ---------- 工具 ----------

    private record ZipEntrySpec(String name, String content) {
    }

    private static ZipEntrySpec entry(String name, String content) {
        return new ZipEntrySpec(name, content);
    }

    private static void writeZip(Path zip, List<ZipEntrySpec> entries) throws IOException {
        Files.createDirectories(zip.getParent());
        try (OutputStream out = Files.newOutputStream(zip); ZipOutputStream zos = new ZipOutputStream(out)) {
            for (ZipEntrySpec e : entries) {
                zos.putNextEntry(new ZipEntry(e.name()));
                zos.write(e.content().getBytes(StandardCharsets.UTF_8));
                zos.closeEntry();
            }
        }
    }

    private static void eq(String label, Object expected, Object actual) {
        boolean ok = expected == null ? actual == null : expected.equals(actual);
        if (!ok) fail(label + "：期望 " + expected + "，实际 " + actual);
    }

    private static void fail(String msg) {
        failures++;
        System.out.println("[packtest] 失败 -> " + msg);
    }
}
