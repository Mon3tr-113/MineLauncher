package mclauncher.modrinth;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import mclauncher.config.Config;
import mclauncher.net.Http;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * MC 百科（mcmod.cn）中文名 / 中文搜索。
 *
 * <p>Modrinth 上的 Mod 只有英文名，启动器里靠一张小映射表显然不够。这里直接查 MC 百科：
 * <ul>
 *   <li><b>中文名</b>：用英文标题去 MC 百科搜，命中条目的标题形如「钠 (Sodium)」，取括号前的部分；</li>
 *   <li><b>中文搜索</b>：用中文关键词搜 MC 百科，拿到对应条目的英文名，再去 Modrinth 按英文名搜，
 *       这样「机械动力」「钠」「应用能源」这些都能搜到；</li>
 *   <li>结果缓存到 {@code ~/.mclauncher/mcmod-cache.json}，第二次打开列表就不用再联网。</li>
 * </ul>
 */
public final class McmodApi {

    private static final String SEARCH = "https://search.mcmod.cn/s?key=";
    /** 结果条目：{classId, 标题原文}。 */
    private static final Pattern ITEM = Pattern.compile(
            "<a[^>]+href=\"(?:https?:)?//(?:www\\.)?mcmod\\.cn/class/(\\d+)\\.html\"[^>]*>(.*?)</a>",
            Pattern.DOTALL);
    private static final Pattern TAG = Pattern.compile("<[^>]+>");
    /** 两次请求的最小间隔，别把人家站点打挂。 */
    private static final long MIN_GAP_MS = 450;

    /** 一条 MC 百科条目。 */
    public static final class Entry {
        public final String classId;
        /** 中文名（没有中文时等于英文名）。 */
        public final String cnName;
        /** 英文名，可能为 null。 */
        public final String enName;

        Entry(String classId, String cnName, String enName) {
            this.classId = classId;
            this.cnName = cnName;
            this.enName = enName;
        }

        @Override
        public String toString() {
            return cnName + (enName == null ? "" : " (" + enName + ")");
        }
    }

    /** 英文名（小写）→ 中文名。 */
    private static final Map<String, String> CACHE = new ConcurrentHashMap<>();
    /** 中文名 → 英文名（中文搜索用）。 */
    private static final Map<String, String> CN_TO_EN = new ConcurrentHashMap<>();
    private static volatile boolean cacheLoaded = false;
    private static final AtomicLong lastRequest = new AtomicLong(0);
    private static final ExecutorService LOOKUP = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "mcmod-lookup");
        t.setDaemon(true);
        return t;
    });

    private McmodApi() {
    }

    // ---------- 缓存 ----------

    private static Path cacheFile() {
        return Config.launcherHome().resolve("mcmod-cache.json");
    }

    @SuppressWarnings("unchecked")
    private static synchronized void ensureCacheLoaded() {
        if (cacheLoaded) return;
        cacheLoaded = true;
        // 先塞内置表（离线可用），再叠用户缓存
        for (Map.Entry<String, String> e : ModNames.builtinEntries().entrySet()) {
            CACHE.putIfAbsent(normalize(e.getKey()), e.getValue());
        }
        try {
            Path f = cacheFile();
            if (Files.isRegularFile(f)) {
                Map<String, String> loaded = new Gson().fromJson(Files.readString(f, StandardCharsets.UTF_8),
                        new TypeToken<Map<String, String>>() {
                        }.getType());
                if (loaded != null) {
                    for (Map.Entry<String, String> e : loaded.entrySet()) {
                        if (e.getKey() == null || e.getValue() == null || e.getValue().isBlank()) continue;
                        CACHE.put(normalize(e.getKey()), e.getValue());
                        CN_TO_EN.putIfAbsent(e.getValue(), e.getKey());
                    }
                }
            }
        } catch (Exception ignored) {
        }
        // 内置表也登记一下「中文 → 英文」，中文搜索才好用
        for (Map.Entry<String, String> e : ModNames.builtinEntries().entrySet()) {
            CN_TO_EN.putIfAbsent(e.getValue(), e.getKey());
        }
    }

    private static void saveCache() {
        try {
            Path f = cacheFile();
            if (f.getParent() != null) Files.createDirectories(f.getParent());
            String json = new GsonBuilder().setPrettyPrinting().create().toJson(new LinkedHashMap<>(CACHE));
            Files.writeString(f, json, StandardCharsets.UTF_8);
        } catch (Exception ignored) {
        }
    }

    // ---------- 查询 ----------

    /** 直接查缓存（含内置表）：英文名/ slug → 中文名，没有返回 null。 */
    public static String cachedChinese(String english) {
        ensureCacheLoaded();
        if (english == null || english.isBlank()) return null;
        String hit = CACHE.get(normalize(english));
        return hit;
    }

    /** 缓存里的「中文 → 英文名」。 */
    public static String englishOfChinese(String cn) {
        ensureCacheLoaded();
        if (cn == null) return null;
        String hit = CN_TO_EN.get(cn.trim());
        if (hit != null) return hit;
        // 允许「模糊一点」：包含关系也算（如「机械动力」vs「机械动力 (Create)」）
        for (Map.Entry<String, String> e : CN_TO_EN.entrySet()) {
            if (e.getKey().contains(cn.trim()) || cn.trim().contains(e.getKey())) return e.getValue();
        }
        return null;
    }

    /**
     * 后台解析英文标题的中文名：命中缓存直接回调；否则排队查 MC 百科，查到后写缓存并回调。
     * 回调总是在后台线程里触发，界面自己切 EDT。
     */
    public static void resolveChineseAsync(String englishTitle, java.util.function.Consumer<String> onResult) {
        ensureCacheLoaded();
        if (englishTitle == null || englishTitle.isBlank()) return;
        String cached = CACHE.get(normalize(englishTitle));
        if (cached != null) {
            if (onResult != null) onResult.accept(cached);
            return;
        }
        LOOKUP.submit(() -> {
            try {
                String cn = queryChineseName(englishTitle);
                if (cn != null) {
                    CACHE.put(normalize(englishTitle), cn);
                    CN_TO_EN.putIfAbsent(cn, englishTitle);
                    saveCache();
                    if (onResult != null) onResult.accept(cn);
                }
            } catch (Exception ignored) {
            }
        });
    }

    /** 同步查一次 MC 百科拿中文名（内部限速）。只有真的含中文才返回。 */
    public static String queryChineseName(String englishTitle) {
        List<Entry> hits = search(englishTitle, 10);
        if (hits.isEmpty()) return null;
        String want = normalize(englishTitle);
        // 优先找英文名对得上的（且中文名里真有中文）
        for (Entry e : hits) {
            if (e.enName != null && normalize(e.enName).equals(want) && hasChinese(e.cnName)) return e.cnName;
        }
        for (Entry e : hits) {
            if (e.enName == null || !hasChinese(e.cnName)) continue;
            String n = normalize(e.enName);
            if (n.contains(want) || want.contains(n)) return e.cnName;
        }
        // 再退一步：第一条结果的中文名确实含中文
        Entry first = hits.get(0);
        return hasChinese(first.cnName) ? first.cnName : null;
    }

    /** 搜索 MC 百科，返回条目列表（可能为空）。带重试：站点偶发返回没有结果列表的页面。 */
    public static List<Entry> search(String keyword, int limit) {
        if (keyword == null || keyword.isBlank()) return new ArrayList<>();
        for (int attempt = 0; attempt < 3; attempt++) {
            List<Entry> list = searchOnce(keyword, limit, attempt);
            if (!list.isEmpty()) return list;
            try {
                Thread.sleep(500L * (attempt + 1));
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return new ArrayList<>();
    }

    private static List<Entry> searchOnce(String keyword, int limit, int attempt) {
        List<Entry> out = new ArrayList<>();
        String html;
        try {
            throttle();
            String url = SEARCH + java.net.URLEncoder.encode(keyword.trim(), StandardCharsets.UTF_8)
                    + (attempt == 0 ? "" : "&filter=0");   // 重试时走「全部」分类
            html = Http.getString(url);
        } catch (Exception e) {
            return out;
        }
        int idx = 0;
        while (out.size() < limit) {
            int start = html.indexOf("class=\"result-item\"", idx);
            if (start < 0) break;
            int end = html.indexOf("class=\"result-item\"", start + 10);
            String chunk = html.substring(start, end > 0 ? end : Math.min(html.length(), start + 4000));
            idx = start + 10;
            Matcher m = ITEM.matcher(chunk);
            if (!m.find()) continue;
            String classId = m.group(1);
            String title = TAG.matcher(m.group(2)).replaceAll("").trim();
            if (title.isEmpty()) continue;
            Entry e = parseTitle(classId, title);
            boolean dup = false;
            for (Entry x : out) {
                if (x.classId.equals(e.classId)) {
                    dup = true;
                    break;
                }
            }
            if (!dup) out.add(e);
        }
        return out;
    }

    /**
     * 中文关键词 → 对应条目的英文名（中文搜索用）。
     */
    public static List<String> englishNamesFor(String chineseKeyword, int limit) {
        List<String> out = new ArrayList<>();
        ensureCacheLoaded();
        // 先看缓存/内置表
        String cached = englishOfChinese(chineseKeyword);
        if (cached != null) out.add(cached);
        for (Entry e : search(chineseKeyword, limit)) {
            if (e.enName == null || !hasChinese(e.cnName)) continue;
            String cn = e.cnName;
            // 中文名要跟关键词有关（MC 百科是全文搜索，避免把描述里提了一嘴的条目带进来）
            if (!cn.contains(chineseKeyword.trim())) continue;
            boolean dup = false;
            for (String x : out) {
                if (x.equalsIgnoreCase(e.enName)) {
                    dup = true;
                    break;
                }
            }
            if (!dup) out.add(e.enName);
            CACHE.put(normalize(e.enName), e.cnName);
            CN_TO_EN.putIfAbsent(e.cnName, e.enName);
        }
        if (!out.isEmpty()) saveCache();
        return out;
    }

    /** 「钠 (Sodium)」/「[JEI] JEI物品管理器 (Just Enough Items)」→ cnName + enName。 */
    private static Entry parseTitle(String classId, String title) {
        String t = title;
        // 去掉 MC 百科标题前面的方括号标签（"[JEI] "、"[AE2] " 之类）
        while (t.startsWith("[")) {
            int close = t.indexOf(']');
            if (close <= 0) break;
            t = t.substring(close + 1).trim();
        }
        String cn = t;
        String en = null;
        int open = t.lastIndexOf('(');
        int openCn = t.lastIndexOf('（');
        int p = Math.max(open, openCn);
        if (p > 0) {
            int close = Math.max(t.lastIndexOf(')'), t.lastIndexOf('）'));
            if (close > p) {
                cn = t.substring(0, p).trim();
                en = t.substring(p + 1, close).trim();
            }
        }
        if (cn.isEmpty()) cn = t;
        if (en != null && en.isEmpty()) en = null;
        return new Entry(classId, cn, en);
    }

    /** 是否含中文：用来确认「这真的是个中文名」，纯英文的条目不采用。 */
    static boolean hasChinese(String s) {
        if (s == null) return false;
        for (int i = 0; i < s.length(); i++) {
            if (Character.UnicodeScript.of(s.charAt(i)) == Character.UnicodeScript.HAN) return true;
        }
        return false;
    }

    private static void throttle() {
        long now = System.currentTimeMillis();
        long last = lastRequest.get();
        long wait = MIN_GAP_MS - (now - last);
        if (wait > 0) {
            try {
                Thread.sleep(wait);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        lastRequest.set(System.currentTimeMillis());
    }

    private static String normalize(String s) {
        return s == null ? "" : s.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", "-");
    }
}
