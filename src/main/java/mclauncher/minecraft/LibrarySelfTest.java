package mclauncher.minecraft;

import mclauncher.config.Config;
import mclauncher.net.Http;
import mclauncher.util.Platform;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * 依赖解析 / 完整性校验的无网络自检（对应 {@code --libtest}）。
 *
 * <p>覆盖两个曾经踩过的坑：
 * <ul>
 *   <li><b>分类器库下载</b>：natives 分类器写在 name 里、且版本 JSON 没有 downloads 段时，
 *       以前会按「主 artifact」推导地址（lwjgl-3.3.3.jar），存成 natives jar 的文件名，
 *       结果 natives 解压不出 .dll，进游戏直接 UnsatisfiedLinkError；</li>
 *   <li><b>依赖库完整性</b>：大小 / SHA-1 判定，大小对但内容错的文件也要能被抓出来。</li>
 * </ul>
 */
public final class LibrarySelfTest {

    private static int failures;
    private static final String BASE = "https://repo.example.invalid/";
    /** 解析用的临时 gameDir（自检结束会删掉）。 */
    private static Path resolveRoot;

    private LibrarySelfTest() {
    }

    /** @return 失败断言数（0 表示全部通过） */
    public static int run() {
        String os = Platform.osName();
        String nat = Platform.nativesClassifier();
        String bits = Platform.archBits();
        failures = 0;

        try {
            // ---- 1) 新格式 natives：classifier 在 name 里，downloads.artifact 就是 natives jar ----
            String jar1 = "lwjgl-3.3.3-" + nat + ".jar";
            List<LibraryResolver.Item> items1 = resolve("""
                    {"id":"t","mainClass":"a.B","libraries":[
                      {"name":"org.lwjgl:lwjgl:3.3.3:%s",
                       "downloads":{"artifact":{"path":"org/lwjgl/lwjgl/3.3.3/%s",
                         "sha1":"1111111111111111111111111111111111111111","size":1000,
                         "url":"%sorg/lwjgl/lwjgl/3.3.3/%s"}},
                       "rules":[{"action":"allow","os":{"name":"%s"}}]}
                    ]}""".formatted(nat, jar1, BASE, jar1, os));
            LibraryResolver.Item i1 = single(items1, "新格式 natives");
            if (i1 != null) {
                eq("新格式 natives nativeLib", true, i1.nativeLib);
                eq("新格式 natives url", BASE + "org/lwjgl/lwjgl/3.3.3/" + jar1, i1.url);
                eq("新格式 natives path 文件名", jar1, i1.path.getFileName().toString());
                eq("新格式 natives sha1", "1111111111111111111111111111111111111111", i1.sha1);
                eq("新格式 natives size", 1000L, i1.size);
            }

            // ---- 2) 新格式 natives 但没有 downloads（加载器生成的 JSON 很常见）：必须带分类器推导 ----
            List<LibraryResolver.Item> items2 = resolve("""
                    {"id":"t","mainClass":"a.B","libraries":[
                      {"name":"org.lwjgl:lwjgl:3.3.3:%s","url":"%s"%s}
                    ]}""".formatted(nat, BASE, rules(os)));
            LibraryResolver.Item i2 = single(items2, "无 downloads 的 natives");
            if (i2 != null) {
                eq("无 downloads 推导 url", BASE + "org/lwjgl/lwjgl/3.3.3/lwjgl-3.3.3-" + nat + ".jar", i2.url);
                eq("无 downloads 推导 path 文件名", "lwjgl-3.3.3-" + nat + ".jar",
                        i2.path.getFileName().toString());
                eq("无 downloads 仍标记为 natives", true, i2.nativeLib);
            }

            // ---- 3) 老格式 natives：downloads.classifiers + natives 映射 ----
            String legacyRel = "org/lwjgl/lwjgl/lwjgl-platform/2.9.4-nightly-20150209/";
            List<LibraryResolver.Item> items3 = resolve("""
                    {"id":"t","mainClass":"a.B","libraries":[
                      {"name":"org.lwjgl.lwjgl:lwjgl-platform:2.9.4-nightly-20150209",
                       "natives":{"%s":"natives-legacy"},
                       "downloads":{
                         "artifact":{"path":"%slwjgl-platform-2.9.4-nightly-20150209.jar",
                           "sha1":"2222222222222222222222222222222222222222","size":10,
                           "url":"%s%slwjgl-platform-2.9.4-nightly-20150209.jar"},
                         "classifiers":{"natives-legacy":{
                           "path":"%slwjgl-platform-2.9.4-nightly-20150209-natives-legacy.jar",
                           "sha1":"3333333333333333333333333333333333333333","size":20,
                           "url":"%s%slwjgl-platform-2.9.4-nightly-20150209-natives-legacy.jar"}}}}
                    ]}""".formatted(os, legacyRel, BASE, legacyRel, legacyRel, BASE, legacyRel));
            eq("老格式 natives 条目数（主 artifact + natives）", 2, items3.size());
            LibraryResolver.Item main3 = byFile(items3, "lwjgl-platform-2.9.4-nightly-20150209.jar");
            LibraryResolver.Item native3 = byFile(items3, "lwjgl-platform-2.9.4-nightly-20150209-natives-legacy.jar");
            if (main3 != null) {
                eq("老格式 主 artifact nativeLib", false, main3.nativeLib);
                eq("老格式 主 artifact sha1", "2222222222222222222222222222222222222222", main3.sha1);
                eq("老格式 主 artifact size", 10L, main3.size);
            } else {
                fail("老格式 主 artifact 缺失");
            }
            if (native3 != null) {
                eq("老格式 natives nativeLib", true, native3.nativeLib);
                eq("老格式 natives url", BASE + legacyRel
                        + "lwjgl-platform-2.9.4-nightly-20150209-natives-legacy.jar", native3.url);
                eq("老格式 natives sha1", "3333333333333333333333333333333333333333", native3.sha1);
            } else {
                fail("老格式 natives 条目缺失");
            }

            // ---- 4) ${arch} 占位符：64 位机器要替换成 64，不能直接删成 "natives-windows-" ----
            String legacyCls = "natives-" + os + "-" + bits;
            List<LibraryResolver.Item> items4 = resolve("""
                    {"id":"t","mainClass":"a.B","libraries":[
                      {"name":"g:a:1.0","url":"%s","natives":{"%s":"natives-%s-${arch}"}}
                    ]}""".formatted(BASE, os, os));
            LibraryResolver.Item native4 = byFile(items4, "a-1.0-" + legacyCls + ".jar");
            if (native4 == null) {
                fail("${arch} 未替换成 " + bits + "（期望文件 a-1.0-" + legacyCls + ".jar）");
            } else {
                eq("${arch} natives url", BASE + "g/a/1.0/a-1.0-" + legacyCls + ".jar", native4.url);
            }

            // ---- 5) 非 natives 的分类器库（Forge 的 :client / :universal）：当普通库，但地址要带分类器 ----
            List<LibraryResolver.Item> items5 = resolve("""
                    {"id":"t","mainClass":"a.B","libraries":[
                      {"name":"net.minecraftforge:forge:1.20.1-47.2.0:client","url":"%s"}
                    ]}""".formatted(BASE));
            LibraryResolver.Item i5 = single(items5, "Forge client 分类器库");
            if (i5 != null) {
                eq("Forge client url", BASE
                        + "net/minecraftforge/forge/1.20.1-47.2.0/forge-1.20.1-47.2.0-client.jar", i5.url);
                eq("Forge client nativeLib", false, i5.nativeLib);
            }

            // ---- 6) sha1 取值：Fabric 顶层 sha1 / 分类器库不能沿用顶层 sha1 ----
            List<LibraryResolver.Item> items6 = resolve("""
                    {"id":"t","mainClass":"a.B","libraries":[
                      {"name":"g:plain:1.0","url":"%s","sha1":"4444444444444444444444444444444444444444","size":7},
                      {"name":"g:cl:1.0:%s","url":"%s","sha1":"5555555555555555555555555555555555555555"%s}
                    ]}""".formatted(BASE, nat, BASE, rules(os)));
            LibraryResolver.Item plain6 = byFile(items6, "plain-1.0.jar");
            if (plain6 == null) {
                fail("顶层 sha1 库缺失");
            } else {
                eq("Fabric 顶层 sha1", "4444444444444444444444444444444444444444", plain6.sha1);
                eq("Fabric 顶层 size", 7L, plain6.size);
            }
            LibraryResolver.Item cl6 = byFile(items6, "cl-1.0-" + nat + ".jar");
            if (cl6 == null) {
                fail("分类器库缺失");
            } else {
                eq("分类器库不沿用顶层 sha1", null, cl6.sha1);
            }

            // ---- 7) 本地产物（url 为空串）：进 classpath，但不参与下载 ----
            List<LibraryResolver.Item> items7 = resolve("""
                    {"id":"t","mainClass":"a.B","libraries":[
                      {"name":"net.neoforged:neoforge:1.0:extra",
                       "downloads":{"artifact":{"path":"net/neoforged/neoforge/1.0/neoforge-1.0-extra.jar","url":""}}}
                    ]}""");
            LibraryResolver.Item i7 = single(items7, "本地产物");
            if (i7 != null) {
                eq("本地产物 不可下载", false, i7.isDownloadable());
                eq("本地产物 在 classpath", 1, LibraryResolver.resolve(version("""
                        {"id":"t","mainClass":"a.B","libraries":[
                          {"name":"net.neoforged:neoforge:1.0:extra",
                           "downloads":{"artifact":{"path":"net/neoforged/neoforge/1.0/neoforge-1.0-extra.jar","url":""}}}
                        ]}"""), cfg()).classpath.size());
            }

            // ---- 8) 去重：父子版本重复声明的库只下载一次 ----
            List<LibraryResolver.Item> items8 = resolve("""
                    {"id":"t","mainClass":"a.B","libraries":[
                      {"name":"g:dup:1.0","url":"%s"},
                      {"name":"g:dup:1.0","url":"%s"}
                    ]}""".formatted(BASE, BASE));
            eq("重复声明的库去重后条目数", 1, items8.size());

            // ---- 9) os.arch 规则：64 位机器不能再选中 -x86 的 32 位 natives ----
            List<LibraryResolver.Item> items9 = resolve("""
                    {"id":"t","mainClass":"a.B","libraries":[
                      {"name":"org.lwjgl:lwjgl:3.3.3:natives-%s-x86","url":"%s",
                       "rules":[{"action":"allow","os":{"name":"%s","arch":"x86"}}]}
                    ]}""".formatted(os, BASE, os));
            int expectX86 = Platform.is64Bit() || Platform.isArm64() ? 0 : 1;
            eq("os.arch=x86 规则条目数", expectX86, items9.size());

            // ---- 10) 完整性校验：大小不符 / SHA-1 不符 / 本地产物缺失 ----
            integrityCases();

        } catch (Exception e) {
            fail("自检抛异常: " + e);
            e.printStackTrace();
        } finally {
            cleanupResolveRoot();
        }

        System.out.println(failures == 0
                ? "[libtest] 全部通过"
                : "[libtest] 失败 " + failures + " 项");
        return failures;
    }

    /** 完整性判定：内容对、大小对但 sha1 错、被截断、本地产物缺失。 */
    private static void integrityCases() throws Exception {
        Path tmp = Files.createTempDirectory("mclauncher-libtest");
        Config cfg = new Config();
        cfg.gameDir = tmp.toString();
        // 保存路径也指到临时目录，绝不能让自检碰到用户真实配置
        cfg.savePath = tmp.resolve("config.json");

        // 已知内容的 sha1（"hello"）
        String hello = "aaf4c61ddcc5e8a2dabede0f3b482cd9aea9434d";
        eq("sha1(byte[])", hello, Http.sha1Of("hello".getBytes(StandardCharsets.UTF_8)));

        Path libDir = tmp.resolve("libraries/g/a/1.0");
        Files.createDirectories(libDir);
        Path jar = libDir.resolve("a-1.0.jar");
        Files.write(jar, "hello".getBytes(StandardCharsets.UTF_8));

        String url = BASE + "g/a/1.0/a-1.0.jar";
        String json = """
                {"id":"t","mainClass":"a.B","libraries":[
                  {"name":"g:a:1.0",
                   "downloads":{"artifact":{"path":"g/a/1.0/a-1.0.jar","sha1":"%s","size":5,"url":"%s"}}}
                ]}""".formatted(hello, url);
        LibraryChecker.Report okReport = LibraryChecker.inspect(version(json), cfg);
        eq("内容正确的库不报错", 0, okReport.broken.size());
        eq("内容正确的库 wasComplete", true, okReport.wasComplete());

        // 大小对、内容错 → 必须被 sha1 抓出来
        Files.write(jar, "HELLO".getBytes(StandardCharsets.UTF_8));
        LibraryChecker.Report badHash = LibraryChecker.inspect(version(json), cfg);
        eq("内容错的库被识别", 1, badHash.broken.size());

        // 截断（大小不符）
        Files.write(jar, "he".getBytes(StandardCharsets.UTF_8));
        LibraryChecker.Report badSize = LibraryChecker.inspect(version(json), cfg);
        eq("被截断的库被识别", 1, badSize.broken.size());

        // 本地产物缺失（url 为空串，文件也不存在）
        LibraryChecker.Report localMissing = LibraryChecker.inspect(version("""
                {"id":"t","mainClass":"a.B","libraries":[
                  {"name":"net.neoforged:neoforge:1.0:extra",
                   "downloads":{"artifact":{"path":"net/neoforged/neoforge/1.0/neoforge-1.0-extra.jar","url":""}}}
                ]}"""), cfg);
        eq("本地产物缺失被识别", 1, localMissing.localMissing.size());

        // 收尾
        try (var walk = Files.walk(tmp)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        }
    }

    // ---------- 工具 ----------

    private static Config cfg() {
        if (resolveRoot == null) {
            try {
                resolveRoot = Files.createTempDirectory("mclauncher-libtest-resolve");
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }
        Config cfg = new Config();
        cfg.gameDir = resolveRoot.toString();
        cfg.savePath = resolveRoot.resolve("config.json");
        return cfg;
    }

    private static void cleanupResolveRoot() {
        if (resolveRoot == null) return;
        try (var walk = Files.walk(resolveRoot)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        } catch (Exception ignored) {
        }
        resolveRoot = null;
    }

    private static GameVersion version(String json) {
        return GameVersion.loadFromJson("t", json);
    }

    private static List<LibraryResolver.Item> resolve(String json) {
        return LibraryResolver.resolveItems(version(json), cfg());
    }

    private static String rules(String os) {
        return ",\"rules\":[{\"action\":\"allow\",\"os\":{\"name\":\"" + os + "\"}}]";
    }

    private static LibraryResolver.Item single(List<LibraryResolver.Item> items, String what) {
        if (items.size() != 1) {
            fail(what + " 条目数应为 1，实际 " + items.size());
            return items.isEmpty() ? null : items.get(0);
        }
        return items.get(0);
    }

    private static LibraryResolver.Item byFile(List<LibraryResolver.Item> items, String fileName) {
        for (LibraryResolver.Item i : items) {
            if (i.path.getFileName().toString().equals(fileName)) return i;
        }
        return null;
    }

    private static void eq(String label, Object expected, Object actual) {
        boolean ok = expected == null ? actual == null : expected.equals(actual);
        if (!ok) fail(label + "：期望 " + expected + "，实际 " + actual);
    }

    private static void fail(String msg) {
        failures++;
        System.out.println("[libtest] 失败 -> " + msg);
    }
}
