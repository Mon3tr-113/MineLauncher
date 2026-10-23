package mclauncher.minecraft;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import mclauncher.config.Config;
import mclauncher.net.DownloadManager;
import mclauncher.net.Http;
import mclauncher.net.Mirror;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 启动前校验并把缺失的资源文件（assets）补齐。
 *
 * <p>资源文件决定游戏内的**语言文件**与**主菜单全景背景**：一旦缺文件，
 * 表现就是界面全英文、语言列表空白、背景变成纯色。安装时如果中途网络失败就会留下这种半装状态，
 * 所以每次启动前做一次轻量核对，只补下载缺失/大小不符的对象。
 */
public final class AssetChecker {

    private AssetChecker() {
    }

    /** @return 本次补下载的文件数量（0 表示本来就完整） */
    public static int ensure(GameVersion v, Config cfg, Consumer<String> log) throws Exception {
        if (v == null || v.assetIndexObj == null) return 0;
        String indexId = v.assetIndexObj.get("id").getAsString();
        Path indexPath = cfg.assetsDir().resolve("indexes").resolve(indexId + ".json");

        if (!Files.exists(indexPath)) {
            if (log != null) log.accept("[资源] 资源索引缺失，先下载索引 " + indexId);
            long size = v.assetIndexObj.has("size") ? v.assetIndexObj.get("size").getAsLong() : -1;
            Http.download(Mirror.map(v.assetIndexObj.get("url").getAsString()), indexPath, null, size);
        }

        JsonObject index = JsonParser.parseString(Files.readString(indexPath)).getAsJsonObject();
        if (!index.has("objects")) return 0;
        JsonObject objects = index.getAsJsonObject("objects");

        List<DownloadManager.FileSpec> missing = new ArrayList<>();
        for (Map.Entry<String, JsonElement> e : objects.entrySet()) {
            JsonObject obj = e.getValue().getAsJsonObject();
            String hash = obj.get("hash").getAsString();
            long size = obj.has("size") ? obj.get("size").getAsLong() : -1;
            String rel = hash.substring(0, 2) + "/" + hash;
            Path target = cfg.assetsDir().resolve("objects").resolve(rel);
            boolean ok = false;
            try {
                ok = Files.exists(target) && (size <= 0 || Files.size(target) == size);
            } catch (Exception ignored) {
            }
            if (!ok) {
                // 资源文件名就是内容 sha1：新下载的一定校验，已存在的只按大小核对（上千个文件全量哈希太慢）
                missing.add(new DownloadManager.FileSpec(
                        Mirror.map("https://resources.download.minecraft.net/" + rel), target, size, hash, false));
            }
        }

        if (missing.isEmpty()) {
            if (log != null) log.accept("[资源] 资源文件完整（" + objects.size() + " 个）");
            return 0;
        }
        if (log != null) {
            log.accept("[资源] 缺少 " + missing.size() + " / " + objects.size()
                    + " 个资源文件（这会导致没有中文、主菜单无背景），开始自动补全...");
        }
        // 资源文件都是小文件，吞吐几乎完全取决于并发数（实测 16 并发约为 4 并发的 2 倍）
        int threads = Math.min(64, Math.max(32, cfg.downloadThreads * 4));
        if (log != null) log.accept("[资源] 使用 " + threads + " 并发下载");

        long[] lastLog = {0};
        DownloadManager.get().downloadAll("补全资源 (" + indexId + ")", missing, threads,
                (done, total) -> {
                    if (log == null || total <= 0) return;
                    long now = System.currentTimeMillis();
                    if (now - lastLog[0] < 2000) return; // 日志节流，避免刷屏拖慢下载
                    lastLog[0] = now;
                    log.accept("[资源] " + DownloadManager.formatSize(done) + " / "
                            + DownloadManager.formatSize(total));
                });
        if (log != null) log.accept("[资源] 补全完成");
        return missing.size();
    }
}
