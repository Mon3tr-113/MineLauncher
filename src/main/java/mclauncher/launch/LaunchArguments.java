package mclauncher.launch;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import mclauncher.auth.Account;
import mclauncher.config.Config;
import mclauncher.minecraft.GameVersion;
import mclauncher.util.Platform;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** 构建 JVM 参数与游戏参数，并替换 ${...} 占位符。 */
public final class LaunchArguments {

    public static class LaunchContext {
        public Account account;
        public Config cfg;
        public GameVersion version;
        public Path nativesDir;
        public String classpath;
        public Path gameDir;   // 实际游戏目录（版本隔离时为实例目录）
        public int width = 854;
        public int height = 480;
        public String logConfigPath;
        /** 外置登录（Yggdrasil）需要注入 authlib-injector 的 jar 路径与 API 根地址 */
        public String authlibInjectorJar;
        public String yggdrasilServer;
    }

    private static final String LAUNCHER_NAME = "MineLauncher";
    private static final String LAUNCHER_VERSION = "0.1.0";

    private LaunchArguments() {
    }

    public static List<String> buildJvmArgs(LaunchContext ctx) {
        List<String> out = new ArrayList<>();
        Config cfg = ctx.cfg;

        // 外置登录：注入 authlib-injector（作为 java agent）
        if (ctx.authlibInjectorJar != null && !ctx.authlibInjectorJar.isBlank()) {
            out.add("-javaagent:" + ctx.authlibInjectorJar
                    + (ctx.yggdrasilServer == null ? "" : "=" + ctx.yggdrasilServer));
        }

        out.add("-Xmx" + cfg.maxMemoryMb + "M");
        out.add("-Xms" + Math.min(512, cfg.maxMemoryMb) + "M");
        out.add("-XX:+UseG1GC");
        out.add("-XX:+UnlockExperimentalVMOptions");
        out.add("-XX:+UseStringDeduplication");

        ctx.logConfigPath = resolveLogConfig(ctx);
        String os = Platform.osName();
        if (ctx.version.jvmArgs != null) {
            for (String a : flatten(ctx.version.jvmArgs, os)) {
                out.add(subst(a, ctx));
            }
        } else {
            out.add("-Djava.library.path=" + ctx.nativesDir);
            out.add("-cp");
            out.add(ctx.classpath);
        }

        // 日志配置参数（logging.client.argument）
        if (ctx.version.loggingObj != null && ctx.version.loggingObj.has("client")) {
            JsonObject client = ctx.version.loggingObj.getAsJsonObject("client");
            if (client.has("argument")) {
                out.add(subst(client.get("argument").getAsString(), ctx));
            }
        }

        out.add(ctx.version.mainClass);
        return out;
    }

    public static List<String> buildGameArgs(LaunchContext ctx) {
        List<String> out = new ArrayList<>();
        String os = Platform.osName();
        List<String> raw;
        if (ctx.version.gameArgs != null && ctx.version.gameArgs.size() > 0) {
            raw = flatten(ctx.version.gameArgs, os);
        } else if (ctx.version.minecraftArguments != null) {
            raw = tokenize(ctx.version.minecraftArguments);
        } else {
            raw = List.of();
        }
        for (String r : raw) {
            String v = subst(r, ctx);
            // 值被替换成空（比如离线账号没有 xuid）时，把前面的 --flag 一起丢掉，
            // 免得给游戏传一个空的参数值
            if (v.isBlank() && !out.isEmpty() && out.get(out.size() - 1).startsWith("--")) {
                out.remove(out.size() - 1);
                continue;
            }
            out.add(v);
        }
        return out;
    }

    private static String resolveLogConfig(LaunchContext ctx) {
        if (ctx.version.loggingObj == null || !ctx.version.loggingObj.has("client")) return null;
        JsonObject client = ctx.version.loggingObj.getAsJsonObject("client");
        if (!client.has("file")) return null;
        String id = client.getAsJsonObject("file").get("id").getAsString();
        return ctx.cfg.assetsDir().resolve("log_configs").resolve(id).toString();
    }

    /** 展平参数数组：处理字符串与带 rules 的 {value: ...} 对象。 */
    private static List<String> flatten(JsonArray arr, String os) {
        List<String> out = new ArrayList<>();
        for (JsonElement e : arr) {
            if (e.isJsonPrimitive()) {
                out.add(e.getAsString());
                continue;
            }
            if (e.isJsonObject()) {
                JsonObject o = e.getAsJsonObject();
                if (!rulesAllow(o.has("rules") ? o.getAsJsonArray("rules") : null, os)) continue;
                JsonElement value = o.get("value");
                if (value == null) continue;
                if (value.isJsonArray()) {
                    for (JsonElement v : value.getAsJsonArray()) out.add(v.getAsString());
                } else {
                    out.add(value.getAsString());
                }
            }
        }
        return out;
    }

    private static boolean rulesAllow(JsonArray rules, String os) {
        if (rules == null || rules.size() == 0) return true;
        String arch = Platform.archName();
        boolean allow = false;
        for (JsonElement re : rules) {
            JsonObject rule = re.getAsJsonObject();
            boolean action = "allow".equals(rule.get("action").getAsString());
            if (rule.has("os")) {
                JsonObject osObj = rule.getAsJsonObject("os");
                boolean nameOk = !osObj.has("name") || osObj.get("name").getAsString().equals(os);
                boolean archOk = !osObj.has("arch") || osObj.get("arch").getAsString().equals(arch);
                if (nameOk && archOk) allow = action;
            } else if (rule.has("features")) {
                // 特性条件（如 has_custom_resolution / is_demo_user）MVP 不做，跳过该规则
                return false;
            } else {
                allow = action;
            }
        }
        return allow;
    }

    /** 简单的带引号分词，用于旧版 minecraftArguments。 */
    private static List<String> tokenize(String s) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inQuote = false;
        char quote = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (inQuote) {
                if (c == quote) {
                    inQuote = false;
                } else {
                    cur.append(c);
                }
            } else if (c == '"' || c == '\'') {
                inQuote = true;
                quote = c;
            } else if (Character.isWhitespace(c)) {
                if (cur.length() > 0) {
                    out.add(cur.toString());
                    cur.setLength(0);
                }
            } else {
                cur.append(c);
            }
        }
        if (cur.length() > 0) out.add(cur.toString());
        return out;
    }

    private static String subst(String arg, LaunchContext ctx) {
        if (arg == null) return "";
        Account a = ctx.account;
        Config cfg = ctx.cfg;
        String userType = a.isMicrosoft() ? "msa" : (a.isYggdrasil() ? "mojang" : "legacy");
        String gameDir = ctx.gameDir != null ? ctx.gameDir.toString() : cfg.gamePath().toString();
        return arg
                .replace("${auth_player_name}", a.name)
                .replace("${version_name}", ctx.version.id)
                .replace("${game_directory}", gameDir)
                .replace("${assets_root}", cfg.assetsDir().toString())
                .replace("${game_assets}", cfg.assetsDir().toString())
                .replace("${assets_index_name}", ctx.version.assetsIndex == null ? "" : ctx.version.assetsIndex)
                .replace("${auth_uuid}", a.uuid)
                .replace("${auth_access_token}", a.accessToken == null ? "0" : a.accessToken)
                .replace("${user_type}", userType)
                .replace("${version_type}", ctx.version.type)
                .replace("${user_properties}", "{}")
                .replace("${natives_directory}", ctx.nativesDir.toString())
                .replace("${library_directory}", cfg.librariesDir().toString())
                .replace("${classpath}", ctx.classpath)
                .replace("${classpath_separator}", Platform.pathSeparator())
                .replace("${launcher_name}", LAUNCHER_NAME)
                .replace("${launcher_version}", LAUNCHER_VERSION)
                .replace("${resolution_width}", String.valueOf(ctx.width))
                .replace("${resolution_height}", String.valueOf(ctx.height))
                // NeoForge 的 version.json 里带 --clientId ${clientid} / --xuid ${auth_xuid}，
                // 这俩只有官方启动器才会填；不处理会把字面量 "${clientid}" 传给游戏
                .replace("${clientid}", a.uuid == null ? "" : a.uuid)
                .replace("${auth_xuid}", "")
                .replace("${xuid}", "")
                .replace("${path}", ctx.logConfigPath == null ? "" : ctx.logConfigPath);
    }
}
