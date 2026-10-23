package mclauncher.minecraft;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import mclauncher.net.Http;
import mclauncher.net.Mirror;

import java.util.ArrayList;
import java.util.List;

/** Mojang 版本清单（version_manifest_v2.json）。 */
public class VersionManifest {

    public static final String MANIFEST_URL = "https://launchermeta.mojang.com/mc/game/version_manifest_v2.json";

    public static class VersionInfo {
        public final String id;
        public final String type;
        public final String url;
        public final String releaseTime;
        /** 版本 JSON 的 SHA-1（manifest v1 没有这个字段时为 null）。 */
        public final String sha1;

        public VersionInfo(String id, String type, String url, String releaseTime) {
            this(id, type, url, releaseTime, null);
        }

        public VersionInfo(String id, String type, String url, String releaseTime, String sha1) {
            this.id = id;
            this.type = type;
            this.url = url;
            this.releaseTime = releaseTime;
            this.sha1 = sha1;
        }

        @Override
        public String toString() {
            return id + "  (" + type + ")";
        }
    }

    private final List<VersionInfo> versions = new ArrayList<>();

    /** 下载并校验某个版本的 JSON（manifest 给了 sha1 就校验，镜像返回坏内容能立刻发现）。 */
    public static String fetchJson(VersionInfo info) throws Exception {
        byte[] data = Http.getBytes(Mirror.map(info.url));
        if (info.sha1 != null && !info.sha1.isBlank()) {
            String actual = Http.sha1Of(data);
            if (!info.sha1.equalsIgnoreCase(actual)) {
                throw new java.io.IOException("版本 JSON 校验失败：" + info.id
                        + "（期望 sha1 " + info.sha1 + "，实际 " + actual + "）");
            }
        }
        return new String(data, java.nio.charset.StandardCharsets.UTF_8);
    }

    public static VersionManifest fetch() throws Exception {
        String json = Http.getString(Mirror.map(MANIFEST_URL));
        VersionManifest m = new VersionManifest();
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        for (JsonElement e : root.getAsJsonArray("versions")) {
            JsonObject o = e.getAsJsonObject();
            m.versions.add(new VersionInfo(
                    o.get("id").getAsString(),
                    o.get("type").getAsString(),
                    o.get("url").getAsString(),
                    o.has("releaseTime") ? o.get("releaseTime").getAsString() : "",
                    o.has("sha1") ? o.get("sha1").getAsString() : null));
        }
        return m;
    }

    public List<VersionInfo> getVersions() {
        return versions;
    }

    public List<VersionInfo> filter(String q, boolean includeSnapshots) {
        List<VersionInfo> r = new ArrayList<>();
        String key = q == null ? "" : q.trim().toLowerCase();
        for (VersionInfo v : versions) {
            if (!includeSnapshots && "snapshot".equals(v.type)) continue;
            if (key.isEmpty() || v.id.toLowerCase().contains(key)) r.add(v);
        }
        return r;
    }

    public VersionInfo find(String id) {
        for (VersionInfo v : versions) {
            if (v.id.equals(id)) return v;
        }
        return null;
    }
}
