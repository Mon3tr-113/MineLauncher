package mclauncher.net;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.concurrent.ConcurrentHashMap;

/**
 * CurseForge 的文件查询（走国内镜像，不需要 API Key）。
 *
 * <p>CurseForge 整合包的 {@code manifest.json} 里只写了 {@code projectID / fileID}，
 * 真正要下载的文件名和地址得靠 API 查。官方 API 需要 Key，这里用 MCIM 的镜像
 * （{@code mod.mcimirror.top}），国内可直连；镜像不可用时再按 CF CDN 的固定规则拼地址兜底。
 */
public final class CurseForgeApi {

    /** MCIM 镜像（CurseForge API 的国内代理）。 */
    private static final String MIRROR = "https://mod.mcimirror.top/curseforge/v1";
    /** CurseForge 官方 API（需要 Key，一般用不上，留着兜底尝试）。 */
    private static final String OFFICIAL = "https://api.curseforge.com/v1";
    /** CF 的 CDN 直链规则：files/<id/1000>/<id%1000>/<fileName>。 */
    private static final String CDN = "https://mediafilez.forgecdn.net/files/";

    /** 一个可直接下载的文件。 */
    public static class ModFile {
        public String fileName;
        public String downloadUrl;
        public String sha1;
        public long size = -1;
        /** 作者关闭了第三方分发时，官方 CDN 会 403，这里也直接跳过。 */
        public boolean distributable = true;
        public String error;
    }

    private static final java.util.Map<String, ModFile> CACHE = new ConcurrentHashMap<>();

    private CurseForgeApi() {
    }

    /**
     * 查某个 mod 的某个具体文件。
     *
     * @return 查到返回文件信息；查不到返回带 {@code error} 的对象（不抛异常，方便批量处理继续跑）
     */
    public static ModFile resolve(long modId, long fileId) {
        String key = modId + "/" + fileId;
        ModFile cached = CACHE.get(key);
        if (cached != null) return cached;

        ModFile out = new ModFile();
        try {
            String json = Http.getString(MIRROR + "/mods/" + modId + "/files/" + fileId);
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            JsonObject data = root.has("data") ? root.getAsJsonObject("data") : null;
            if (data == null) {
                out.error = "镜像没有返回文件信息";
            } else {
                fill(out, data);
            }
        } catch (Exception e) {
            out.error = "查询失败：" + shortMsg(e);
        }
        // 镜像查不到时，用 CDN 固定规则拼地址兜底（能拼出来就试一试）
        if (out.downloadUrl == null && out.fileName != null) {
            out.downloadUrl = CDN + (fileId / 1000) + "/" + (fileId % 1000) + "/" + out.fileName;
        }
        CACHE.put(key, out);
        return out;
    }

    /** 从 API 的 data 节点里取文件名 / 地址 / sha1 / 大小。 */
    private static void fill(ModFile out, JsonObject data) {
        if (data.has("fileName") && !data.get("fileName").isJsonNull()) {
            out.fileName = data.get("fileName").getAsString();
        }
        if (data.has("downloadUrl") && !data.get("downloadUrl").isJsonNull()) {
            String url = data.get("downloadUrl").getAsString();
            if (url != null && !url.isBlank()) out.downloadUrl = url;
        }
        if (data.has("fileLength") && !data.get("fileLength").isJsonNull()) {
            out.size = data.get("fileLength").getAsLong();
        }
        if (data.has("isAvailable") && !data.get("isAvailable").isJsonNull()) {
            out.distributable = data.get("isAvailable").getAsBoolean();
        }
        if (data.has("hashes") && data.get("hashes").isJsonArray()) {
            for (JsonElement he : data.getAsJsonArray("hashes")) {
                JsonObject h = he.getAsJsonObject();
                if (h.has("algo") && h.has("value")) {
                    String algo = h.get("algo").getAsString();
                    if ("sha1".equalsIgnoreCase(algo)) {
                        out.sha1 = h.get("value").getAsString().toLowerCase(java.util.Locale.ROOT);
                        break;
                    }
                }
            }
        }
    }

    /** 官方 API 的地址（保留给以后有 Key 时切用）。 */
    public static String officialBase() {
        return OFFICIAL;
    }

    private static String shortMsg(Exception e) {
        String m = e.getMessage();
        if (m == null) return e.getClass().getSimpleName();
        m = m.replace('\n', ' ');
        return m.length() > 120 ? m.substring(0, 120) + "…" : m;
    }
}
