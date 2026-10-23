package mclauncher.minecraft;

import mclauncher.config.Config;
import mclauncher.net.Mirror;
import mclauncher.util.Platform;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** 根据操作系统/架构解析某个版本实际需要的 classpath 与 natives jar（含下载地址）。 */
public final class LibraryResolver {

    /** 一个需要下载的库条目。url 为 null 表示本地产物（安装器现场生成），只进 classpath 不下载。 */
    public static class Item {
        public final String url;
        public final Path path;
        public final boolean nativeLib;
        public final long size;
        /** 期望 SHA-1，null 表示版本 JSON 没提供。 */
        public final String sha1;

        public Item(String url, Path path, boolean nativeLib, long size) {
            this(url, path, nativeLib, size, null);
        }

        public Item(String url, Path path, boolean nativeLib, long size, String sha1) {
            this.url = url;
            this.path = path;
            this.nativeLib = nativeLib;
            this.size = size;
            this.sha1 = sha1;
        }

        /** 是否有可下载地址（本地产物为 false）。 */
        public boolean isDownloadable() {
            return url != null && !url.isBlank();
        }

        /** 供界面/日志展示的短名，能看出是 natives 分类器库还是普通库。 */
        public String label() {
            String file = path.getFileName().toString();
            return nativeLib ? file + "（natives）" : file;
        }
    }

    public static class Resolved {
        public final List<Path> classpath = new ArrayList<>();
        public final List<Path> natives = new ArrayList<>();
    }

    private LibraryResolver() {
    }

    /** 解析出全部需要下载的库（含 URL），供下载器与启动器共用，保证一致。 */
    public static List<Item> resolveItems(GameVersion version, Config cfg) {
        List<Item> items = new ArrayList<>();
        String os = Platform.osName();
        String arch = Platform.archName();
        String targetNative = Platform.nativesClassifier();
        // 去重：加载器版本的 JSON 会重复声明父版本已有的库（NeoForge/Forge 都会），
        // 重复条目会让多个线程同时写同一个 .part，最终把 jar 写坏
        Set<Path> seen = new LinkedHashSet<>();

        for (Library lib : version.libraries) {
            if (!lib.appliesTo(os, arch)) continue;

            String clsInName = lib.classifierInName();
            if (clsInName != null) {
                if (clsInName.startsWith("natives-")) {
                    // 新格式（1.19.3+）：natives classifier 写在名字里，按本机架构精确匹配
                    if (clsInName.equals(targetNative)) {
                        add(items, cfg, lib, clsInName, true, seen);
                    }
                    continue;
                }
                // 非 natives 的 classifier（如 NeoForge 打补丁后的 client jar）：当普通 artifact
                add(items, cfg, lib, clsInName, false, seen);
                continue;
            }

            if (lib.hasArtifact()) {
                add(items, cfg, lib, null, false, seen);
            }
            String legacyCls = lib.legacyNativeClassifier(os);
            if (legacyCls != null) {
                add(items, cfg, lib, legacyCls, true, seen);
            }
        }
        return items;
    }

    private static void add(List<Item> items, Config cfg, Library lib, String classifier,
                            boolean nativeLib, Set<Path> seen) {
        Path path = cfg.librariesDir().resolve(lib.downloadPathFor(classifier));
        if (!seen.add(path)) return;
        String url = lib.downloadUrlFor(classifier);
        items.add(new Item(url == null ? null : Mirror.map(url), path, nativeLib,
                lib.downloadSizeFor(classifier), lib.downloadSha1For(classifier)));
    }

    /** 只取本地路径（供启动时拼 classpath / 解压 natives）。 */
    public static Resolved resolve(GameVersion version, Config cfg) {
        Resolved r = new Resolved();
        for (Item item : resolveItems(version, cfg)) {
            if (item.nativeLib) r.natives.add(item.path);
            else r.classpath.add(item.path);
        }
        return r;
    }
}
