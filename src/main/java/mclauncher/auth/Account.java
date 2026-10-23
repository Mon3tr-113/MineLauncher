package mclauncher.auth;

/** 登录账号。type: offline / microsoft / littleskin(外置登录/Yggdrasil)。 */
public class Account {
    public String name;
    public String uuid;
    public String accessToken;
    public String type;

    /** 微软登录：刷新令牌 */
    public String refreshToken;
    /** 微软登录：accessToken 过期时间（毫秒时间戳） */
    public long expiresAt;
    /** 微软登录：获取令牌的通道，live=login.live.com 授权码流，aad=Azure AD 设备码流 */
    public String msFlow;
    /** 外置登录（Yggdrasil）：clientToken */
    public String clientToken;
    /** 外置登录（Yggdrasil）：API 根地址，如 https://littleskin.cn/api/yggdrasil */
    public String server;

    public Account() {
    }

    public Account(String name, String uuid, String accessToken, String type) {
        this.name = name;
        this.uuid = uuid;
        this.accessToken = accessToken;
        this.type = type;
    }

    public boolean isMicrosoft() {
        return "microsoft".equals(type);
    }

    public boolean isYggdrasil() {
        return "littleskin".equals(type) || "yggdrasil".equals(type);
    }

    public String display() {
        if (isMicrosoft()) return name + "（正版）";
        if (isYggdrasil()) return name + "（外置登录）";
        return name + "（离线）";
    }

    @Override
    public String toString() {
        return display();
    }
}
