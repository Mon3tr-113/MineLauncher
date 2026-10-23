package mclauncher.minecraft;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.List;

/** 单个 Minecraft 版本 JSON 的解析模型（含继承合并）。 */
public class GameVersion {
    public String id;
    public String type = "release";
    public String mainClass;
    public String assetsIndex;
    public String inheritsFrom;
    public int javaMajor = 8;

    public List<Library> libraries = new ArrayList<>();
    public JsonArray gameArgs;   // arguments.game（现代）；null 表示用 minecraftArguments
    public JsonArray jvmArgs;    // arguments.jvm；null 表示用默认
    public String minecraftArguments; // 旧版参数串

    public JsonObject assetIndexObj;
    public JsonObject downloadsObj;
    public JsonObject loggingObj;

    private static final java.util.regex.Pattern MC_VER =
            java.util.regex.Pattern.compile("(\\d+)\\.(\\d+)(?:\\.(\\d+))?");

    /** 按 Minecraft 版本号推断需要的 Java 大版本。 */
    static int guessJavaMajor(String ver) {
        if (ver == null) return 8;
        java.util.regex.Matcher m = MC_VER.matcher(ver);
        if (!m.find()) return 8;
        try {
            int a = Integer.parseInt(m.group(1));
            int b = Integer.parseInt(m.group(2));
            int c = m.group(3) == null ? 0 : Integer.parseInt(m.group(3));
            if (a == 1) {
                if (b > 20) return 21;                 // 1.21+
                if (b == 20) return c >= 5 ? 21 : 17;  // 1.20.5+ 改成 21
                if (b >= 18) return 17;
                if (b == 17) return 16;
                return 8;
            }
            return 25; // 新版本号方案（如 26.x）
        } catch (Exception e) {
            return 8;
        }
    }

    public static GameVersion loadFromJson(String id, String json) {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        GameVersion v = new GameVersion();
        v.id = root.get("id").getAsString();
        if (root.has("type")) v.type = root.get("type").getAsString();
        v.mainClass = root.get("mainClass").getAsString();
        if (root.has("inheritsFrom")) v.inheritsFrom = root.get("inheritsFrom").getAsString();
        if (root.has("assets")) v.assetsIndex = root.get("assets").getAsString();
        if (root.has("assetIndex")) v.assetIndexObj = root.getAsJsonObject("assetIndex");
        if (root.has("downloads")) v.downloadsObj = root.getAsJsonObject("downloads");
        if (root.has("logging")) v.loggingObj = root.getAsJsonObject("logging");
        if (root.has("javaVersion")) {
            JsonObject jv = root.getAsJsonObject("javaVersion");
            if (jv.has("majorVersion")) v.javaMajor = jv.get("majorVersion").getAsInt();
        } else {
            // 加载器版本（NeoForge/Forge/Fabric）的 JSON 没有 javaVersion，是靠 inheritsFrom 继承的；
            // 这里按继承的 MC 版本推断，否则会一路用默认的 Java 8，
            // 而 1.21.1 的 NeoForge 需要 Java 21（Java 8 不认识 -p 参数，直接崩）。
            v.javaMajor = guessJavaMajor(v.inheritsFrom != null ? v.inheritsFrom : v.id);
        }
        if (root.has("minecraftArguments")) v.minecraftArguments = root.get("minecraftArguments").getAsString();
        if (root.has("arguments")) {
            JsonObject a = root.getAsJsonObject("arguments");
            if (a.has("game")) v.gameArgs = a.getAsJsonArray("game");
            if (a.has("jvm")) v.jvmArgs = a.getAsJsonArray("jvm");
        }
        if (root.has("libraries")) {
            JsonArray libs = root.getAsJsonArray("libraries");
            for (JsonElement le : libs) {
                JsonObject lo = le.getAsJsonObject();
                String name = lo.get("name").getAsString();
                String url = lo.has("url") ? lo.get("url").getAsString() : null;
                v.libraries.add(new Library(name, url, lo));
            }
        }
        return v;
    }

    /** 把 parent 合并进 child（child.inheritsFrom == parent.id 时调用）。 */
    public void mergeFrom(GameVersion parent) {
        List<Library> merged = new ArrayList<>(parent.libraries);
        merged.addAll(libraries);
        libraries = merged;

        if (mainClass == null) mainClass = parent.mainClass;
        if (assetsIndex == null) assetsIndex = parent.assetsIndex;
        if (assetIndexObj == null) assetIndexObj = parent.assetIndexObj;
        if (downloadsObj == null) downloadsObj = parent.downloadsObj;
        if (loggingObj == null) loggingObj = parent.loggingObj;
        if (minecraftArguments == null) minecraftArguments = parent.minecraftArguments;

        JsonArray game = new JsonArray();
        if (parent.gameArgs != null) game.addAll(parent.gameArgs);
        if (gameArgs != null) game.addAll(gameArgs);
        gameArgs = game;

        JsonArray jvm = new JsonArray();
        if (parent.jvmArgs != null) jvm.addAll(parent.jvmArgs);
        if (jvmArgs != null) jvm.addAll(jvmArgs);
        jvmArgs = jvm;
    }
}
