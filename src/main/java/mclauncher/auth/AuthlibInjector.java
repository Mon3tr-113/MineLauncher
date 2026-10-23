package mclauncher.auth;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import mclauncher.config.Config;
import mclauncher.net.Http;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;

/** authlib-injector 下载管理：LittleSkin 等外置登录启动时作为 java agent 注入。 */
public final class AuthlibInjector {

    private static final String LATEST_URL = "https://authlib-injector.yushi.moe/artifact/latest.json";

    private AuthlibInjector() {
    }

    /** 确保 authlib-injector.jar 存在并返回其路径。 */
    public static String ensure(Config cfg, Consumer<String> log) throws Exception {
        Path jar = Config.launcherHome().resolve("authlib-injector.jar");
        if (Files.exists(jar) && Files.size(jar) > 100_000) {
            return jar.toString();
        }
        if (log != null) log.accept("[外置登录] 获取 authlib-injector 版本信息...");
        String meta = Http.getString(LATEST_URL);
        JsonObject o = JsonParser.parseString(meta).getAsJsonObject();
        String url = o.get("download_url").getAsString();
        if (log != null) log.accept("[外置登录] 下载 authlib-injector：" + url);
        Http.download(url, jar, null);
        if (!Files.exists(jar) || Files.size(jar) < 10_000) {
            throw new java.io.IOException("authlib-injector 下载失败");
        }
        return jar.toString();
    }
}
