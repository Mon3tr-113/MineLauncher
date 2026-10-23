package mclauncher.auth;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import mclauncher.net.Http;

import java.awt.Desktop;
import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;

/**
 * 微软正版登录。
 *
 * <p>为保证「正版登录始终可用」，这里同时实现两条通道，按顺序自动降级：
 * <ol>
 *   <li><b>设备代码流</b>（Azure AD，体验最好，无需粘贴）：请求设备码 → 浏览器输入代码 → 轮询取令牌。</li>
 *   <li><b>浏览器授权码流</b>（login.live.com，兼容性最好）：打开浏览器登录 → 回落到
 *       {@code oauth20_desktop.srf} → 把地址栏整条链接粘回来 → 换取令牌。</li>
 * </ol>
 * 两条通道拿到微软令牌后都走同一条链：Xbox Live → XSTS → Minecraft Services → 玩家档案，
 * 并保存 refresh_token 与过期时间，之后启动游戏前会自动刷新，不会因为令牌过期而登录失效。
 */
public final class MicrosoftAuth {

    /** 启动器使用的公开客户端 ID（微软账号应用，原生/公共客户端，无密钥）。 */
    private static final String CLIENT_ID = "00000000402b5328";

    // ---- 通道一：Azure AD 设备代码流 ----
    private static final String DEVICE_URL = "https://login.microsoftonline.com/consumers/oauth2/v2.0/devicecode";
    private static final String TOKEN_URL = "https://login.microsoftonline.com/consumers/oauth2/v2.0/token";

    // ---- 通道二：login.live.com 浏览器授权码流 ----
    private static final String LIVE_AUTHORIZE = "https://login.live.com/oauth20_authorize.srf";
    private static final String LIVE_TOKEN = "https://login.live.com/oauth20_token.srf";
    private static final String LIVE_REDIRECT = "https://login.live.com/oauth20_desktop.srf";

    private static final String SCOPE = "XboxLive.signin offline_access";

    private MicrosoftAuth() {
    }

    /** 设备代码流在当前网络/租户下不可用时抛出，调用方据此降级到浏览器流。 */
    public static class DeviceCodeUnavailable extends IOException {
        public DeviceCodeUnavailable(String message) {
            super(message);
        }
    }

    // ==================================================================== 通道一

    /** 设备代码流登录。应用未在该租户注册时抛 {@link DeviceCodeUnavailable}。 */
    public static Account login(Consumer<String> status) throws Exception {
        status.accept("正在请求设备代码...");
        String form = "client_id=" + CLIENT_ID
                + "&scope=" + encode(SCOPE);
        String resp = Http.postForm(DEVICE_URL, form);
        JsonObject device = JsonParser.parseString(resp).getAsJsonObject();
        if (device.has("error")) {
            String err = device.get("error").getAsString();
            String desc = device.has("error_description") ? device.get("error_description").getAsString() : "";
            // 该应用注册在微软账号(live)体系而非 Azure AD 租户，设备码流天然不可用 → 降级
            if ("unauthorized_client".equals(err) || "invalid_client".equals(err)
                    || desc.contains("AADSTS700016") || desc.contains("AADSTS700027")) {
                throw new DeviceCodeUnavailable(desc);
            }
            throw new IOException("设备码请求失败: " + err + (desc.isEmpty() ? "" : "（" + desc + "）"));
        }
        String userCode = device.get("user_code").getAsString();
        String uri = device.has("verification_uri") ? device.get("verification_uri").getAsString()
                : device.get("verification_url").getAsString();
        String deviceCode = device.get("device_code").getAsString();
        int interval = device.has("interval") ? device.get("interval").getAsInt() : 5;

        status.accept("请在浏览器打开 " + uri + "\n并输入代码：" + userCode);
        openBrowser(uri);

        String msAccessToken = null;
        String refreshToken = null;
        long expiresIn = 3600;
        long deadline = System.currentTimeMillis()
                + (device.has("expires_in") ? device.get("expires_in").getAsLong() * 1000L : 15 * 60 * 1000L);
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(interval * 1000L);
            String tf = "grant_type=" + encode("urn:ietf:params:oauth:grant-type:device_code")
                    + "&client_id=" + CLIENT_ID
                    + "&device_code=" + encode(deviceCode);
            JsonObject tok = JsonParser.parseString(Http.postForm(TOKEN_URL, tf)).getAsJsonObject();
            if (tok.has("access_token")) {
                msAccessToken = tok.get("access_token").getAsString();
                refreshToken = tok.has("refresh_token") ? tok.get("refresh_token").getAsString() : null;
                expiresIn = tok.has("expires_in") ? tok.get("expires_in").getAsLong() : 3600;
                break;
            }
            String err = tok.has("error") ? tok.get("error").getAsString() : "";
            switch (err) {
                case "authorization_pending" -> { /* 用户还没完成，继续等 */ }
                case "slow_down" -> interval += 5;
                case "expired", "expired_token", "code_expired" -> throw new IOException("登录已过期，请重试");
                case "access_denied", "authorization_declined" -> throw new IOException("用户取消了登录授权");
                default -> throw new IOException("登录失败: " + err);
            }
        }
        if (msAccessToken == null) {
            throw new IOException("登录超时，未完成验证");
        }
        return finish(msAccessToken, refreshToken, expiresIn, "aad", status);
    }

    // ==================================================================== 通道二

    /** 隐式流（推荐）：令牌直接出现在回调链接的 # 片段里，不需要再换票。 */
    public static final String MODE_TOKEN = "token";
    /** 授权码流：回调里给 code，需要再向令牌端点兑换。 */
    public static final String MODE_CODE = "code";

    /** 浏览器登录页地址（默认隐式流）。 */
    public static String browserAuthUrl() {
        return browserAuthUrl(MODE_TOKEN);
    }

    /** 浏览器登录页地址。responseType 取 {@link #MODE_TOKEN} 或 {@link #MODE_CODE}。 */
    public static String browserAuthUrl(String responseType) {
        return LIVE_AUTHORIZE
                + "?client_id=" + CLIENT_ID
                + "&response_type=" + responseType
                + "&redirect_uri=" + encode(LIVE_REDIRECT)
                + "&scope=" + encode(SCOPE)
                + "&display=touch"
                + "&prompt=select_account";
    }

    /** 打开系统默认浏览器（失败不抛异常，由用户自己复制链接）。 */
    public static void openBrowser(String url) {
        try {
            Desktop.getDesktop().browse(URI.create(url));
        } catch (Exception ignored) {
            // 无桌面环境时用户可手动复制
        }
    }

    /**
     * 浏览器登录第二步：用户把登录后浏览器的完整链接粘回来。
     *
     * <p>隐式流会在链接里直接带回 {@code access_token} / {@code refresh_token}，直接使用；
     * 若链接里只有 {@code code}（授权码流），再去令牌端点兑换。
     *
     * @param pasted 完整回调链接、query 串、片段串或纯 code
     */
    public static Account finishBrowser(String pasted, Consumer<String> status) throws Exception {
        Callback cb = parseCallback(pasted);
        if (cb.accessToken != null && !cb.accessToken.isBlank()) {
            status.accept("已获取登录令牌...");
            return finish(cb.accessToken, cb.refreshToken, cb.expiresIn, "live", status);
        }
        if (cb.code != null && !cb.code.isBlank()) {
            return exchangeCode(cb, status);
        }
        if (cb.error != null) {
            throw new IOException("微软返回错误: " + cb.error
                    + (cb.errorDescription == null || cb.errorDescription.isBlank()
                        ? "" : "（" + cb.errorDescription + "）")
                    + "\n请点「重新打开登录页」重新登录。");
        }
        throw new IOException("粘贴的链接里没有找到 access_token 或 code。\n"
                + "请确认复制的是登录完成后浏览器地址栏里的整条链接；"
                + "若浏览器显示的是错误页，请把上方登录方式换成另一种再试。");
    }

    /** 授权码兑换令牌。授权码只能用一次且很快过期，这里把解码前后两种写法都试一遍。 */
    private static Account exchangeCode(Callback cb, Consumer<String> status) throws Exception {
        status.accept("正在换取登录令牌...");
        java.util.LinkedHashSet<String> candidates = new java.util.LinkedHashSet<>();
        if (cb.code != null) candidates.add(cb.code);
        if (cb.codeRaw != null) candidates.add(cb.codeRaw);
        String lastErr = "";
        for (String code : candidates) {
            String form = "client_id=" + CLIENT_ID
                    + "&code=" + encode(code)
                    + "&grant_type=authorization_code"
                    + "&redirect_uri=" + encode(LIVE_REDIRECT);
            JsonObject tok = JsonParser.parseString(Http.postForm(LIVE_TOKEN, form)).getAsJsonObject();
            if (tok.has("access_token")) {
                return finish(tok.get("access_token").getAsString(),
                        tok.has("refresh_token") ? tok.get("refresh_token").getAsString() : null,
                        tok.has("expires_in") ? tok.get("expires_in").getAsLong() : 3600,
                        "live", status);
            }
            lastErr = tok.has("error") ? tok.get("error").getAsString() : "unknown";
            if (tok.has("error_description")) {
                lastErr += "（" + tok.get("error_description").getAsString() + "）";
            }
        }
        throw new IOException("换取令牌失败: " + lastErr
                + "\n授权码只能用一次、几分钟内有效。请点「重新打开登录页」重新登录后立刻粘贴；"
                + "或把登录方式改成「隐式登录」再试。");
    }

    /** 解析回调链接里带回来的参数。 */
    public static final class Callback {
        public String accessToken;
        public String refreshToken;
        /** 解码后的 code */
        public String code;
        /** 原样（未解码）的 code */
        public String codeRaw;
        public String error;
        public String errorDescription;
        public long expiresIn = 3600;
    }

    /** 同时解析 query 和 # 片段，兼容隐式流与授权码流。 */
    public static Callback parseCallback(String pasted) throws IOException {
        String s = pasted == null ? "" : pasted.trim();
        if (s.isEmpty()) {
            throw new IOException("没有收到登录结果，请把浏览器地址栏里的完整链接粘贴进来");
        }
        Callback cb = new Callback();
        if (!s.contains("=")) {
            // 用户直接粘贴了 code 本体
            cb.code = s;
            cb.codeRaw = s;
            return cb;
        }
        int q = s.indexOf('?');
        String params = q >= 0 ? s.substring(q + 1) : s;
        int hash = params.indexOf('#');
        if (hash >= 0) {
            // 隐式流的令牌在 # 之后，把它接到参数串里一起解析
            params = params.substring(0, hash) + "&" + params.substring(hash + 1);
        }
        for (String kv : params.split("&")) {
            int i = kv.indexOf('=');
            if (i <= 0) continue;
            String k = decode(kv.substring(0, i));
            String raw = kv.substring(i + 1);
            switch (k) {
                case "access_token" -> cb.accessToken = decode(raw);
                case "refresh_token" -> cb.refreshToken = decode(raw);
                case "code" -> {
                    cb.code = decode(raw);
                    cb.codeRaw = raw;
                }
                case "expires_in" -> {
                    try {
                        cb.expiresIn = Long.parseLong(decode(raw).trim());
                    } catch (Exception ignored) {
                    }
                }
                case "error" -> cb.error = decode(raw);
                case "error_description" -> cb.errorDescription = decode(raw);
                default -> {
                }
            }
        }
        return cb;
    }

    /** 仅取授权码（保留给自检与旧调用方）。 */
    public static String extractCode(String pasted) throws IOException {
        Callback cb = parseCallback(pasted);
        if (cb.code != null && !cb.code.isBlank()) return cb.code;
        if (cb.error != null) {
            throw new IOException("微软返回错误: " + cb.error
                    + (cb.errorDescription == null || cb.errorDescription.isBlank()
                        ? "" : "（" + cb.errorDescription + "）"));
        }
        if (cb.accessToken != null) {
            throw new IOException("该链接是隐式流返回的令牌，不含 code");
        }
        throw new IOException("链接里没有找到 code");
    }

    /** 仅探测设备码通道是否可用（不进入轮询），用于自检。 */
    public static String probeDeviceCode() {
        try {
            String resp = Http.postForm(DEVICE_URL, "client_id=" + CLIENT_ID + "&scope=" + encode(SCOPE));
            JsonObject o = JsonParser.parseString(resp).getAsJsonObject();
            if (o.has("error")) {
                return "不可用: " + o.get("error").getAsString();
            }
            return "可用（user_code=" + (o.has("user_code") ? o.get("user_code").getAsString() : "?") + "）";
        } catch (Exception e) {
            return "请求失败: " + e.getMessage();
        }
    }

    // ==================================================================== 刷新

    /** 两条通道的刷新令牌都支持，按登录时记录的通道选择端点。 */
    public static Account refresh(Account account, Consumer<String> status) throws Exception {
        if (account.refreshToken == null || account.refreshToken.isBlank()) {
            throw new IOException("该账号没有刷新令牌，请重新登录");
        }
        if (status != null) status.accept("正在刷新正版登录...");
        // 旧版本存档没有 msFlow，按浏览器授权码流处理（设备码通道在多数网络下本就不可用）
        String flow = account.msFlow == null ? "live" : account.msFlow;
        boolean live = "live".equals(flow);
        String tokenUrl = live ? LIVE_TOKEN : TOKEN_URL;

        JsonObject tok = null;
        String lastErr = "";
        String[] forms = live
                ? new String[]{
                    "grant_type=refresh_token&client_id=" + CLIENT_ID
                            + "&refresh_token=" + encode(account.refreshToken)
                            + "&scope=" + encode(SCOPE),
                    "grant_type=refresh_token&client_id=" + CLIENT_ID
                            + "&refresh_token=" + encode(account.refreshToken)}
                : new String[]{
                    "grant_type=refresh_token&client_id=" + CLIENT_ID
                            + "&refresh_token=" + encode(account.refreshToken)
                            + "&scope=" + encode(SCOPE)};
        for (String form : forms) {
            JsonObject o = JsonParser.parseString(Http.postForm(tokenUrl, form)).getAsJsonObject();
            if (o.has("access_token")) {
                tok = o;
                break;
            }
            lastErr = o.has("error") ? o.get("error").getAsString() : "unknown";
        }
        if (tok == null) {
            throw new IOException("刷新失败: " + lastErr);
        }
        String msAccessToken = tok.get("access_token").getAsString();
        String newRefresh = tok.has("refresh_token") ? tok.get("refresh_token").getAsString() : account.refreshToken;
        long expiresIn = tok.has("expires_in") ? tok.get("expires_in").getAsLong() : 3600;
        return finish(msAccessToken, newRefresh, expiresIn, account.msFlow == null ? "live" : account.msFlow, status);
    }

    /** 若 accessToken 即将过期（或已过期）则刷新，否则原样返回。 */
    public static Account refreshIfNeeded(Account account, Consumer<String> status) {
        if (!account.isMicrosoft()) return account;
        if (account.refreshToken == null || account.refreshToken.isBlank()) return account;
        if (System.currentTimeMillis() < account.expiresAt - 5 * 60 * 1000L) {
            return account; // 还有 5 分钟以上有效期
        }
        try {
            return refresh(account, status);
        } catch (Exception e) {
            if (status != null) status.accept("[警告] 刷新正版登录失败，将沿用旧令牌：" + e.getMessage());
            return account;
        }
    }

    // ==================================================================== 公共链条

    private static Account finish(String msAccessToken, String refreshToken, long expiresIn,
                                  String flow, Consumer<String> status) throws Exception {
        status.accept("正在通过 Xbox Live 验证...");
        // 设备码流(微软账号体系)的票据需要 "d=" 前缀，Azure AD 令牌则用裸令牌；
        // 两种都试一次，任一成功即可，避免因票据格式差异导致登录失败。
        String[] tickets = "live".equals(flow)
                ? new String[]{"d=" + msAccessToken, msAccessToken}
                : new String[]{msAccessToken, "d=" + msAccessToken};
        JsonObject xbl = null;
        String xblErr = "";
        for (String ticket : tickets) {
            String xblResp = Http.postJson("https://user.auth.xboxlive.com/user/authenticate",
                    "{\"Properties\":{\"AuthMethod\":\"RPS\",\"SiteName\":\"user.auth.xboxlive.com\","
                            + "\"RpsTicket\":\"" + ticket + "\"},"
                            + "\"RelyingParty\":\"http://auth.xboxlive.com\",\"TokenType\":\"JWT\"}");
            JsonObject o = JsonParser.parseString(xblResp).getAsJsonObject();
            if (o.has("Token")) {
                xbl = o;
                break;
            }
            xblErr = xblResp;
        }
        if (xbl == null) {
            throw new IOException("Xbox Live 验证失败: " + xblErr);
        }
        String xblToken = xbl.get("Token").getAsString();

        String xstsResp = Http.postJson("https://xsts.auth.xboxlive.com/xsts/authorize",
                "{\"Properties\":{\"SandboxId\":\"RETAIL\",\"UserTokens\":[\"" + xblToken + "\"]},"
                        + "\"RelyingParty\":\"rp://api.minecraftservices.com/\",\"TokenType\":\"JWT\"}");
        JsonObject xsts = JsonParser.parseString(xstsResp).getAsJsonObject();
        if (xsts.has("XErr") || xsts.has("error")) {
            long xerr = xsts.has("XErr") ? xsts.get("XErr").getAsLong() : -1;
            throw new IOException("XSTS 验证失败 (错误码 " + xerr + ")：" + xstsMessage(xerr));
        }
        String xstsToken = xsts.get("Token").getAsString();
        String uhs2 = xsts.getAsJsonObject("DisplayClaims").getAsJsonArray("xui")
                .get(0).getAsJsonObject().get("uhs").getAsString();

        status.accept("正在登录 Minecraft...");
        String mcResp = Http.postJson("https://api.minecraftservices.com/authentication/login_with_xbox",
                "{\"identityToken\":\"XBL3.0 x=" + uhs2 + ";" + xstsToken + "\"}");
        JsonObject mc = JsonParser.parseString(mcResp).getAsJsonObject();
        if (!mc.has("access_token")) {
            throw new IOException("Minecraft 登录失败（该账号可能没有购买 Minecraft）: " + mcResp);
        }
        String mcToken = mc.get("access_token").getAsString();

        status.accept("正在获取玩家档案...");
        String profileResp = Http.getString("https://api.minecraftservices.com/minecraft/profile",
                "Authorization", "Bearer " + mcToken);
        JsonObject profile = JsonParser.parseString(profileResp).getAsJsonObject();
        String uuid = profile.get("id").getAsString();
        String name = profile.get("name").getAsString();
        String dashedUuid = uuid.length() == 32 ? addDashes(uuid) : uuid;

        Account account = new Account(name, dashedUuid, mcToken, "microsoft");
        account.refreshToken = refreshToken;
        account.expiresAt = System.currentTimeMillis() + expiresIn * 1000L;
        account.msFlow = flow;
        status.accept("登录成功：" + name);
        return account;
    }

    private static String encode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String decode(String s) {
        try {
            return URLDecoder.decode(s, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return s;
        }
    }

    private static String addDashes(String s) {
        return s.substring(0, 8) + "-" + s.substring(8, 12) + "-" + s.substring(12, 16) + "-"
                + s.substring(16, 20) + "-" + s.substring(20);
    }

    private static String xstsMessage(long xerr) {
        if (xerr == 2148916233L) return "该账号没有 Xbox 档案，请先到 xbox.com 创建";
        if (xerr == 2148916235L) return "Xbox Live 在你所在地区不可用";
        if (xerr == 2148916236L || xerr == 2148916237L) return "该账号需要家长同意/成年验证";
        if (xerr == 2148916238L) return "该账号是儿童账号，需要添加到家庭组";
        return "未知错误（" + xerr + "）";
    }
}
