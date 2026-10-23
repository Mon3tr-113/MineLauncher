package mclauncher.minecraft;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import mclauncher.config.Config;
import mclauncher.net.DownloadManager;
import mclauncher.net.Http;
import mclauncher.net.Mirror;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** 下载并安装一个 Minecraft 版本（客户端 jar、libraries、assets、日志配置）。 */
public class VersionInstaller {

    public interface Progress {
        void on(String stage, long done, long total);
    }

    private final Config cfg;

    public VersionInstaller(Config cfg) {
        this.cfg = cfg;
    }

    /** 按版本号从 Mojang 清单安装。 */
    public GameVersion install(String versionId, Progress progress) throws Exception {
        VersionManifest manifest = VersionManifest.fetch();
        VersionManifest.VersionInfo info = manifest.find(versionId);
        if (info == null) {
            throw new IOException("未找到版本：" + versionId);
        }
        return installFromInfo(info, manifest, progress);
    }

    /**
     * 由一份版本 JSON 直接安装（供 Fabric 等加载器复用）。
     * 若继承父版本且父版本未安装，会先用 manifest 安装父版本。
     */
    public GameVersion installFromJson(String id, String json, VersionManifest manifest, Progress progress) throws Exception {
        Path versionDir = cfg.versionsDir().resolve(id);
        Files.createDirectories(versionDir);
        Files.writeString(versionDir.resolve(id + ".json"), json);

        GameVersion v = GameVersion.loadFromJson(id, json);
        if (v.inheritsFrom != null && !v.inheritsFrom.equals(id)) {
            Path parentJson = cfg.versionsDir().resolve(v.inheritsFrom).resolve(v.inheritsFrom + ".json");
            if (!Files.exists(parentJson)) {
                VersionManifest.VersionInfo parent = manifest.find(v.inheritsFrom);
                if (parent == null) {
                    throw new IOException("版本 " + id + " 依赖的父版本 " + v.inheritsFrom + " 不存在");
                }
                installFromInfo(parent, manifest, progress);
            }
        }

        downloadClient(v, versionDir, progress);
        downloadLibraries(v, progress);
        downloadAssets(v, progress);
        downloadLogConfig(v, progress);
        return v;
    }

    private GameVersion installFromInfo(VersionManifest.VersionInfo info, VersionManifest manifest, Progress progress) throws Exception {
        // 版本 JSON 也校验 sha1：镜像偶尔返回半截 JSON 或错误页，解析失败时要能一眼看出是内容不对
        String json = VersionManifest.fetchJson(info);
        return installFromJson(info.id, json, manifest, progress);
    }

    private void downloadClient(GameVersion v, Path versionDir, Progress progress) throws Exception {
        if (v.downloadsObj == null || !v.downloadsObj.has("client")) {
            return; // 加载器版本（Fabric 等）没有客户端下载信息，客户端 jar 来自父版本
        }
        JsonObject client = v.downloadsObj.getAsJsonObject("client");
        String url = Mirror.map(client.get("url").getAsString());
        long size = client.has("size") ? client.get("size").getAsLong() : -1;
        String sha1 = client.has("sha1") ? client.get("sha1").getAsString() : null;
        Path jar = versionDir.resolve(v.id + ".jar");
        if (Files.exists(jar) && Files.size(jar) == size && Http.hashMatches(jar, sha1)) {
            if (progress != null) progress.on("客户端 " + v.id + ".jar", size, size);
            return;
        }
        if (progress != null) progress.on("正在下载客户端 " + v.id + ".jar", 0, size);
        DownloadManager.get().download("客户端 " + v.id + ".jar", url, jar, size, sha1);
        if (progress != null) progress.on("客户端 " + v.id + ".jar", size, size);
    }

    private void downloadLibraries(GameVersion v, Progress progress) throws Exception {
        List<LibraryResolver.Item> items = LibraryResolver.resolveItems(v, cfg);
        List<DownloadManager.FileSpec> specs = new ArrayList<>();
        for (LibraryResolver.Item item : items) {
            if (!item.isDownloadable()) continue; // 安装器现场生成的本地产物，没有下载地址
            // item.url 已在 LibraryResolver 里做过镜像映射（Fabric/Forge 的库直连国外 maven 常超时），别重复 map
            specs.add(new DownloadManager.FileSpec(item.url, item.path, item.size, item.sha1, true));
        }
        if (specs.isEmpty()) return;
        if (progress != null) progress.on("正在下载依赖库", 0, 0);
        DownloadManager.get().downloadAll("依赖库 (" + v.id + ")", specs, cfg.downloadThreads, (done, total) -> {
            // done/total 都是字节，别再混用文件数，否则会算出 >100%
            if (progress != null) progress.on("正在下载依赖库", done, total);
        });
    }

    private void downloadAssets(GameVersion v, Progress progress) throws Exception {
        if (v.assetIndexObj == null) return;
        String indexId = v.assetIndexObj.get("id").getAsString();
        String indexUrl = Mirror.map(v.assetIndexObj.get("url").getAsString());
        long indexSize = v.assetIndexObj.has("size") ? v.assetIndexObj.get("size").getAsLong() : -1;
        String indexSha1 = v.assetIndexObj.has("sha1") ? v.assetIndexObj.get("sha1").getAsString() : null;
        Path indexPath = cfg.assetsDir().resolve("indexes").resolve(indexId + ".json");
        Http.download(indexUrl, indexPath, null, indexSize, indexSha1);

        JsonObject index = JsonParser.parseString(Files.readString(indexPath)).getAsJsonObject();
        if (!index.has("objects")) return;
        JsonObject objects = index.getAsJsonObject("objects");
        List<DownloadManager.FileSpec> specs = new ArrayList<>();
        for (Map.Entry<String, JsonElement> e : objects.entrySet()) {
            JsonObject obj = e.getValue().getAsJsonObject();
            String hash = obj.get("hash").getAsString();
            long size = obj.has("size") ? obj.get("size").getAsLong() : -1;
            String rel = hash.substring(0, 2) + "/" + hash;
            Path target = cfg.assetsDir().resolve("objects").resolve(rel);
            // 资源文件的文件名就是内容 sha1：新下载的一定校验；已存在的只看大小
            // （每次启动都对上千个资源文件做哈希太慢，启动前的核对由 AssetChecker 按大小做）
            specs.add(new DownloadManager.FileSpec(
                    Mirror.map("https://resources.download.minecraft.net/" + rel), target, size, hash, false));
        }
        if (specs.isEmpty()) return;
        if (progress != null) progress.on("正在下载资源文件", 0, specs.size());
        // 资源文件都是小文件，吞吐几乎完全取决于并发数；默认给到 32 并发（镜像对小文件不敏感）
        int assetThreads = Math.min(64, Math.max(32, cfg.downloadThreads * 4));
        DownloadManager.get().downloadAll("资源文件 (" + indexId + ")", specs,
                assetThreads, (done, total) -> {
                    if (progress != null) progress.on("正在下载资源文件", done, total);
                });
    }

    private void downloadLogConfig(GameVersion v, Progress progress) throws Exception {
        if (v.loggingObj == null || !v.loggingObj.has("client")) return;
        JsonObject client = v.loggingObj.getAsJsonObject("client");
        if (!client.has("file")) return;
        JsonObject file = client.getAsJsonObject("file");
        String id = file.get("id").getAsString();
        String url = Mirror.map(file.get("url").getAsString());
        long size = file.has("size") ? file.get("size").getAsLong() : -1;
        String sha1 = file.has("sha1") ? file.get("sha1").getAsString() : null;
        Path target = cfg.assetsDir().resolve("log_configs").resolve(id);
        try {
            Http.download(url, target, null, size, sha1);
        } catch (Exception e) {
            // 这个 log4j 配置只是「日志格式」，缺了游戏照常能启动（会 fallback 到默认配置）。
            // 某些镜像不代理这类 piston-data 对象（会 404），不值得因此判整个安装失败。
            if (progress != null) {
                progress.on("日志配置下载失败（不影响启动）：" + e.getMessage(), 0, 0);
            }
        }
    }
}
