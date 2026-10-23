package mclauncher.modrinth;

import com.google.gson.Gson;
import com.google.gson.annotations.SerializedName;
import mclauncher.net.Http;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** Modrinth API 封装（无需 API key）。 */
public final class ModrinthApi {

    public static final String BASE = "https://api.modrinth.com/v2";

    public static final String TYPE_MOD = "mod";
    public static final String TYPE_MODPACK = "modpack";
    public static final String TYPE_SHADER = "shader";
    public static final String TYPE_RESOURCEPACK = "resourcepack";

    private static final Gson GSON = new Gson();

    public static class Hit {
        @SerializedName("project_id") public String projectId;
        public String slug;
        public String title;
        public String description;
        @SerializedName("project_type") public String projectType;
        public long downloads;
        @SerializedName("icon_url") public String iconUrl;
        public String author;
        public List<String> categories;
    }

    public static class Version {
        public String id;
        public String name;
        @SerializedName("version_number") public String versionNumber;
        @SerializedName("game_versions") public List<String> gameVersions;
        public List<String> loaders;
        public List<ModFile> files;

        @Override
        public String toString() {
            String base = versionNumber != null ? versionNumber : (name != null ? name : id);
            if (loaders != null && !loaders.isEmpty()) {
                return base + "  [" + String.join("/", loaders) + "]";
            }
            return base;
        }
    }

    public static class ModFile {
        public String url;
        public String filename;
        public boolean primary;
        public long size;
    }

    private static class SearchResponse {
        public List<Hit> hits;
    }

    private ModrinthApi() {
    }

    public static List<Hit> search(String query, String projectType) throws Exception {
        String facets = "[[\"project_type:" + projectType + "\"]]";
        String url = BASE + "/search?query=" + enc(query)
                + "&facets=" + enc(facets)
                + "&limit=50&index=relevance";
        String resp = Http.getString(url);
        return GSON.fromJson(resp, SearchResponse.class).hits;
    }

    /** 常用/热门列表：空查询 + 按下载量排序（打开标签页时无需搜索就有内容）。 */
    public static List<Hit> popular(String projectType) throws Exception {
        String facets = "[[\"project_type:" + projectType + "\"]]";
        String url = BASE + "/search?query=&facets=" + enc(facets) + "&limit=50&index=downloads";
        String resp = Http.getString(url);
        return GSON.fromJson(resp, SearchResponse.class).hits;
    }

    public static List<Version> versions(String projectId) throws Exception {
        String url = BASE + "/project/" + projectId + "/version";
        String resp = Http.getString(url);
        List<Version> list = GSON.fromJson(resp, new com.google.gson.reflect.TypeToken<List<Version>>() {}.getType());
        return list == null ? List.of() : list;
    }

    /**
     * 智能搜索：英文关键词直接搜 Modrinth；<b>含中文时先用 MC 百科把中文名翻成英文名，再按英文名搜 Modrinth</b>
     * （MC 百科没开放搜索 API，这里查的是它的搜索页，见 {@link McmodApi}）。
     *
     * <p>结果排序：英文名精确对得上的排前面，其余全文命中的排后面。
     */
    public static List<Hit> searchSmart(String query, String projectType,
                                        java.util.function.Consumer<String> progress) {
        java.util.LinkedHashMap<String, Hit> strong = new java.util.LinkedHashMap<>();
        java.util.LinkedHashMap<String, Hit> weak = new java.util.LinkedHashMap<>();
        if (query == null || query.isBlank()) return List.of();

        if (!containsChinese(query)) {
            try {
                for (Hit h : search(query, projectType)) weak.putIfAbsent(h.projectId, h);
            } catch (Exception e) {
                if (progress != null) progress.accept("搜索失败：" + e.getMessage());
            }
            return new java.util.ArrayList<>(weak.values());
        }

        // 1) 先拿中文直接问 Modrinth（个别 mod 简介里带中文）
        try {
            for (Hit h : search(query, projectType)) weak.putIfAbsent(h.projectId, h);
        } catch (Exception ignored) {
        }

        // 2) MC 百科：中文 → 英文名 → 按英文名搜 Modrinth
        if (progress != null) progress.accept("正在 MC 百科查「" + query + "」的英文名...");
        List<String> englishNames = McmodApi.englishNamesFor(query, 6);
        for (String en : englishNames) {
            if (progress != null) progress.accept("MC 百科：「" + query + "」→ " + en + "，正在搜索...");
            String want = en.toLowerCase(java.util.Locale.ROOT).replace(' ', '-');
            try {
                List<Hit> hits = search(en, projectType);
                boolean picked = false;
                for (Hit h : hits) {
                    String key = h.slug == null ? "" : h.slug.toLowerCase(java.util.Locale.ROOT);
                    String title = h.title == null ? ""
                            : h.title.toLowerCase(java.util.Locale.ROOT).replace(' ', '-');
                    if (key.equals(want) || title.equals(want) || key.contains(want) || title.contains(want)) {
                        strong.putIfAbsent(h.projectId, h);
                        picked = true;
                    }
                }
                if (!picked && !hits.isEmpty()) weak.putIfAbsent(hits.get(0).projectId, hits.get(0));
            } catch (Exception ignored) {
            }
        }
        java.util.List<Hit> out = new java.util.ArrayList<>(strong.values());
        for (Hit h : weak.values()) {
            if (h.projectId != null && strong.containsKey(h.projectId)) continue;
            out.add(h);
        }
        return out;
    }

    /** 关键词里是否有汉字。 */
    public static boolean containsChinese(String s) {
        if (s == null) return false;
        for (int i = 0; i < s.length(); i++) {
            if (Character.UnicodeScript.of(s.charAt(i)) == Character.UnicodeScript.HAN) return true;
        }
        return false;
    }

    public static ModFile primaryFile(List<Version> versions, String gameVersion, String loader) {
        for (Version v : versions) {
            if (gameVersion != null && !gameVersion.isBlank()
                    && v.gameVersions != null && !v.gameVersions.contains(gameVersion)) {
                continue;
            }
            if (loader != null && !loader.isBlank() && v.loaders != null && !v.loaders.contains(loader)) {
                continue;
            }
            for (ModFile f : v.files) {
                if (f.primary && f.url != null) return f;
            }
            // 没有标记 primary 就取第一个有 url 的
            for (ModFile f : v.files) {
                if (f.url != null) return f;
            }
        }
        return null;
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
