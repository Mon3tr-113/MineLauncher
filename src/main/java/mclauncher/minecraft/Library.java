package mclauncher.minecraft;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import mclauncher.util.Platform;

/**
 * 版本 JSON 里的一个 library 条目。
 * name 支持：g:a:v、g:a:v:classifier、g:a:v@ext、g:a:v:classifier@ext。
 *
 * <p>下载地址/本地路径的取值规则（分类器库 bug 的根源就在这里）：
 * <ol>
 *   <li>有 {@code downloads.artifact} 且它的 path/url 文件名正好是「带分类器的 jar 名」→ 用它
 *       （1.19.3+ 的 natives 就是这种：分类器写在 name 里，natives jar 就是 artifact 本身）；</li>
 *   <li>有 {@code downloads.classifiers.&lt;classifier&gt;} → 用它（1.18 及更早的老格式 natives）；</li>
 *   <li>都没有 → 按 maven 约定 {@code basePath + artifact-version-classifier.jar} 推导。
 *       <b>推导时必须带分类器</b>，否则会去下 {@code lwjgl-3.3.3.jar} 这种主 jar，
 *       存成 {@code lwjgl-3.3.3-natives-windows.jar}，解压 natives 时找不到 .dll → 游戏起不来。</li>
 * </ol>
 */
public class Library {
    public final String name;
    public final String url;
    public final JsonObject raw;

    private final String grp;
    private final String art;
    private final String ver;
    private final String cls;
    private final String ext;

    public Library(String name, String url, JsonObject raw) {
        this.name = name;
        this.url = url;
        this.raw = raw;
        String n = name;
        int at = n.indexOf('@');
        if (at >= 0) {
            this.ext = n.substring(at + 1);
            n = n.substring(0, at);
        } else {
            this.ext = "jar";
        }
        String[] p = n.split(":");
        if (p.length < 3) {
            throw new IllegalArgumentException("非法的 library 名称: " + name);
        }
        this.grp = p[0];
        this.art = p[1];
        this.ver = p[2];
        this.cls = p.length > 3 ? p[3] : null;
    }

    public String group() {
        return grp.replace('.', '/');
    }

    public String artifact() {
        return art;
    }

    public String version() {
        return ver;
    }

    public String extension() {
        return ext;
    }

    /** 名字里的 classifier（第 4 段），无则 null。 */
    public String classifierInName() {
        return cls;
    }

    public String basePath() {
        return group() + "/" + art + "/" + ver + "/";
    }

    /** 带分类器的 jar 文件名；classifier 为 null 时是主 artifact 的文件名。 */
    public String jarName(String classifier) {
        return art + "-" + ver + (classifier == null ? "" : "-" + classifier) + "." + ext;
    }

    /** 老格式（1.18 及更早）：natives 键里对应当前 OS 的 classifier；无则 null。 */
    public String legacyNativeClassifier(String osName) {
        if (!raw.has("natives")) return null;
        JsonObject natives = raw.getAsJsonObject("natives");
        JsonElement e = natives.get(osName);
        if (e == null || e.isJsonNull()) return null;
        // 少数老库写成 natives-windows-${arch}：64 位要替换成 64，直接删掉会变成
        // "natives-windows-" 这种不存在的分类器，下载必然 404
        return e.getAsString().replace("${arch}", Platform.archBits());
    }

    /** 规则是否放行当前操作系统/架构。 */
    public boolean appliesTo(String osName, String archName) {
        if (!raw.has("rules")) return true;
        JsonArray rules = raw.getAsJsonArray("rules");
        boolean allow = false;
        for (JsonElement re : rules) {
            JsonObject rule = re.getAsJsonObject();
            boolean action = "allow".equals(rule.get("action").getAsString());
            if (rule.has("os")) {
                JsonObject os = rule.getAsJsonObject("os");
                boolean nameOk = !os.has("name") || os.get("name").getAsString().equals(osName);
                boolean archOk = !os.has("arch") || archMatches(os.get("arch").getAsString(), archName);
                if (nameOk && archOk) allow = action;
            } else if (rule.has("features")) {
                return false;
            } else {
                allow = action;
            }
        }
        return allow;
    }

    /**
     * os.arch 规则匹配。Mojang 的规则里 "x86" 专指 32 位（64 位机器靠「没有 arch 的通用条目」命中），
     * 所以这里不能用「x86 家族」去匹配：否则 64 位机器会同时选中 32 位 natives 条目。
     */
    private static boolean archMatches(String ruleArch, String platformArch) {
        switch (ruleArch) {
            case "x86":
                return !Platform.is64Bit() && !Platform.isArm64();
            case "arm64":
                return Platform.isArm64();
            case "x86_64":
            case "amd64":
                return Platform.is64Bit() && !Platform.isArm64();
            default:
                return ruleArch.equals(platformArch);
        }
    }

    /** 是否声明了主 artifact（无 downloads 段视为按 maven 约定推导，也算有）。 */
    public boolean hasArtifact() {
        JsonObject dl = downloads();
        if (dl == null) return true;
        return dl.has("artifact");
    }

    /**
     * 该 classifier 的下载地址；返回 null 表示「本地产物、没有可下载地址」
     * （例如 NeoForge 安装器 jarsplitter 现场生成的 client-extra jar，downloads 里 url 是空串）。
     */
    public String downloadUrlFor(String classifier) {
        JsonObject dl = downloads();
        if (dl == null) {
            // 没有 downloads 段（Fabric / NeoForge 的 version.json 常见）：按 maven 约定推导，必须带分类器
            return baseUrl() + basePath() + jarName(classifier);
        }
        JsonObject entry = downloadEntry(classifier);
        if (entry == null) {
            if (classifier == null) return null;
            return baseUrl() + basePath() + jarName(classifier);
        }
        JsonElement u = entry.get("url");
        if (u == null || u.isJsonNull()) return null;
        String s = u.getAsString();
        return s.isBlank() ? null : s;
    }

    /** 该 classifier 产物在 libraries 目录下的相对路径（优先用 JSON 里声明的 path）。 */
    public String downloadPathFor(String classifier) {
        JsonObject entry = downloadEntry(classifier);
        if (entry != null && entry.has("path")) {
            String p = entry.get("path").getAsString();
            if (p != null && !p.isBlank()) return p.replace('\\', '/');
        }
        return basePath() + jarName(classifier);
    }

    /** 该 classifier 产物的期望大小（-1 表示未知）。兼容 Fabric 的顶层 size 字段。 */
    public long downloadSizeFor(String classifier) {
        JsonObject entry = downloadEntry(classifier);
        if (entry != null && entry.has("size")) return entry.get("size").getAsLong();
        if (classifier == null && raw.has("size")) return raw.get("size").getAsLong();
        return -1;
    }

    /** 该 classifier 产物的期望 SHA-1（null 表示 JSON 没给）。 */
    public String downloadSha1For(String classifier) {
        JsonObject entry = downloadEntry(classifier);
        String sha1 = sha1Of(entry);
        if (sha1 != null) return sha1;
        // 少数库把 sha1 写在 library 顶层（主 artifact 用），分类器库不能沿用
        if (classifier == null) return sha1Of(raw);
        return null;
    }

    private static String sha1Of(JsonObject o) {
        if (o == null || !o.has("sha1")) return null;
        JsonElement e = o.get("sha1");
        if (e == null || e.isJsonNull()) return null;
        String s = e.getAsString();
        return s == null || s.isBlank() ? null : s.toLowerCase(java.util.Locale.ROOT);
    }

    /**
     * 找到与 classifier 对应的 downloads 子对象：
     * 先看 artifact（当它的 path / url 文件名就是该分类器的 jar），再看 classifiers[classifier]。
     */
    private JsonObject downloadEntry(String classifier) {
        JsonObject dl = downloads();
        if (dl == null) return null;
        if (dl.has("artifact")) {
            JsonObject a = dl.getAsJsonObject("artifact");
            if (classifier == null) return a;
            if (matchesJarName(a, jarName(classifier))) return a;
        }
        if (classifier != null && dl.has("classifiers")) {
            JsonObject c = dl.getAsJsonObject("classifiers");
            if (c.has(classifier)) return c.getAsJsonObject(classifier);
        }
        return null;
    }

    private static boolean matchesJarName(JsonObject entry, String jarName) {
        for (String key : new String[]{"path", "url"}) {
            if (!entry.has(key)) continue;
            JsonElement e = entry.get(key);
            if (e == null || e.isJsonNull()) continue;
            String v = e.getAsString().replace('\\', '/');
            if (v.isBlank()) continue;
            if (v.equals(jarName) || v.endsWith("/" + jarName)) return true;
        }
        return false;
    }

    private JsonObject downloads() {
        if (!raw.has("downloads")) return null;
        JsonElement e = raw.get("downloads");
        return e != null && e.isJsonObject() ? e.getAsJsonObject() : null;
    }

    private String baseUrl() {
        if (url != null && !url.isBlank()) {
            return url.endsWith("/") ? url : url + "/";
        }
        return "https://libraries.minecraft.net/";
    }
}
