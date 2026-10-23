package mclauncher.auth;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import mclauncher.net.Http;

import java.util.function.Consumer;

/** LittleSkin 外置登录（Yggdrasil 协议，需配合 authlib-injector 启动）。 */
public final class LittleSkinAuth {

    /** LittleSkin 的 Yggdrasil API 根地址。 */
    public static final String API_ROOT = "https://littleskin.cn/api/yggdrasil";

    private LittleSkinAuth() {
    }

    public static Account login(String username, String password, Consumer<String> status) throws Exception {
        if (status != null) status.accept("正在向 LittleSkin 验证账号...");
        String body = "{\"agent\":{\"name\":\"Minecraft\",\"version\":1},"
                + "\"username\":\"" + esc(username) + "\","
                + "\"password\":\"" + esc(password) + "\","
                + "\"requestUser\":true}";
        String resp = Http.postJson(API_ROOT + "/authserver/authenticate", body);
        JsonObject o = JsonParser.parseString(resp).getAsJsonObject();
        if (o.has("errorMessage")) {
            throw new java.io.IOException("LittleSkin 登录失败：" + o.get("errorMessage").getAsString());
        }
        if (!o.has("accessToken")) {
            throw new java.io.IOException("LittleSkin 登录失败：未返回 accessToken");
        }
        String accessToken = o.get("accessToken").getAsString();
        String clientToken = o.has("clientToken") ? o.get("clientToken").getAsString() : null;

        String id = null;
        String name = null;
        if (o.has("selectedProfile") && o.get("selectedProfile").isJsonObject()) {
            JsonObject sp = o.getAsJsonObject("selectedProfile");
            id = sp.get("id").getAsString();
            name = sp.get("name").getAsString();
        } else if (o.has("availableProfiles")) {
            JsonArray arr = o.getAsJsonArray("availableProfiles");
            if (arr.size() > 0) {
                JsonObject sp = arr.get(0).getAsJsonObject();
                id = sp.get("id").getAsString();
                name = sp.get("name").getAsString();
            }
        }
        if (name == null) {
            throw new java.io.IOException("LittleSkin 登录失败：该账号没有可用的角色");
        }
        String dashed = id.length() == 32 ? addDashes(id) : id;

        Account account = new Account(name, dashed, accessToken, "littleskin");
        account.clientToken = clientToken;
        account.server = API_ROOT;
        if (status != null) status.accept("LittleSkin 登录成功：" + name);
        return account;
    }

    /** 用已保存的令牌刷新（过期时）。 */
    public static Account refresh(Account account, Consumer<String> status) throws Exception {
        if (account.accessToken == null) throw new java.io.IOException("该账号没有令牌，请重新登录");
        String body = "{\"accessToken\":\"" + esc(account.accessToken) + "\","
                + "\"clientToken\":\"" + esc(account.clientToken == null ? "" : account.clientToken) + "\","
                + "\"requestUser\":true}";
        String resp = Http.postJson(API_ROOT + "/authserver/refresh", body);
        JsonObject o = JsonParser.parseString(resp).getAsJsonObject();
        if (o.has("accessToken")) {
            account.accessToken = o.get("accessToken").getAsString();
            if (o.has("clientToken")) account.clientToken = o.get("clientToken").getAsString();
        }
        return account;
    }

    /** 令牌是否有效（validate）。 */
    public static boolean validate(Account account) {
        try {
            String body = "{\"accessToken\":\"" + esc(account.accessToken == null ? "" : account.accessToken) + "\","
                    + "\"clientToken\":\"" + esc(account.clientToken == null ? "" : account.clientToken) + "\"}";
            String resp = Http.postJson(API_ROOT + "/authserver/validate", body);
            return "true".equalsIgnoreCase(resp.trim());
        } catch (Exception e) {
            return false;
        }
    }

    private static String addDashes(String s) {
        return s.substring(0, 8) + "-" + s.substring(8, 12) + "-" + s.substring(12, 16) + "-"
                + s.substring(16, 20) + "-" + s.substring(20);
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
