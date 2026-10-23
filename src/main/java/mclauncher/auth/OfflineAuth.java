package mclauncher.auth;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** 离线登录：按 Mojang 规则用用户名生成稳定 UUID。 */
public final class OfflineAuth {

    private OfflineAuth() {
    }

    public static Account login(String username) {
        String uuid = UUID.nameUUIDFromBytes(("OfflinePlayer:" + username).getBytes(StandardCharsets.UTF_8)).toString();
        return new Account(username.trim(), uuid, "0", "offline");
    }
}
