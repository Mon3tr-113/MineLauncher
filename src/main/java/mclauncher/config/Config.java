package mclauncher.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import mclauncher.auth.Account;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/** 持久化配置：游戏目录、Java 路径、内存、镜像开关、账号列表等。 */
public class Config {

    /** 一个可独立启动的整合包实例。 */
    public static class Modpack {
        public String name;
        public String versionId;   // 启动时加载的版本（如 fabric-loader-0.15.11-1.20.1）
        public String loader;       // vanilla / fabric / forge

        public Modpack() {
        }

        public Modpack(String name, String versionId, String loader) {
            this.name = name;
            this.versionId = versionId;
            this.loader = loader;
        }
    }

    public String gameDir;
    public String javaPath = "java";
    public int maxMemoryMb = 2048;
    public boolean useMirror = true;
    public int downloadThreads = 8;
    public boolean versionIsolation = true;
    public String themeColor = "#4A90D9";
    public String backgroundColor = "#1E1E1E";
    public String textColor = "#E6E6E6";
    public int uiTransparency = 20;
    /** 文字透明度（0~90，0=完全不透明）。 */
    public int textTransparency = 0;
    public String backgroundImage;
    public String musicFile;
    /** 背景音乐歌单（文件路径列表）。 */
    public List<String> playlist = new ArrayList<>();
    /** 背景音乐音量（0~200，100 为原始音量）。 */
    public int musicVolume = 100;
    /** 启动时写入 options.txt 的游戏语言：zh_cn / en_us / keep(跟随游戏)。 */
    public String gameLang = "zh_cn";
    /** 启动前校验依赖库完整性（大小 + SHA-1），缺失/损坏的自动补下载。 */
    public boolean verifyLibraries = true;
    public List<Account> accounts = new ArrayList<>();
    public List<Modpack> modpacks = new ArrayList<>();
    /** 用户自定义的版本显示名：版本 id → 显示名（不填就用「1.21.1 + Fabric 0.20.1」这种自动名）。 */
    public java.util.Map<String, String> versionNames = new java.util.LinkedHashMap<>();
    /** 版本下拉里是否也显示「只被加载器依赖的原版版本」。 */
    public boolean showParentVersions = false;

    private transient static Config instance;
    private transient static Path configFile;

    /**
     * 这个实例该往哪个文件保存。默认跟 {@link #configPath()} 一致；
     * 自检/测试用临时 Config 时必须显式改掉，否则会把测试用的 gameDir 写进用户真实配置
     * （踩过一次：`--packtest` 把 gameDir 写成了临时目录）。
     */
    public transient Path savePath;

    public static Path launcherHome() {
        String override = System.getProperty("mclauncher.home");
        if (override != null && !override.isBlank()) {
            return Paths.get(override);
        }
        return Paths.get(System.getProperty("user.home"), ".mclauncher");
    }

    public static Path configPath() {
        return launcherHome().resolve("config.json");
    }

    public static Config load() {
        configFile = configPath();
        try {
            if (Files.exists(configFile)) {
                instance = new Gson().fromJson(Files.readString(configFile, StandardCharsets.UTF_8), Config.class);
            }
        } catch (Exception ignored) {
            // 配置损坏时回退到默认值
        }
        if (instance == null) instance = new Config();
        if (instance.savePath == null) instance.savePath = configFile;
        if (instance.gameDir == null || instance.gameDir.isBlank()) {
            instance.gameDir = launcherHome().resolve("minecraft").toString();
        }
        if (instance.accounts == null) instance.accounts = new ArrayList<>();
        if (instance.modpacks == null) instance.modpacks = new ArrayList<>();
        if (instance.playlist == null) instance.playlist = new ArrayList<>();
        if (instance.downloadThreads <= 0) instance.downloadThreads = 8;
        if (instance.maxMemoryMb <= 0) instance.maxMemoryMb = 2048;
        // 老存档没有 gameLang 字段，默认用中文
        if (instance.gameLang == null || instance.gameLang.isBlank()) instance.gameLang = "zh_cn";
        if (instance.uiTransparency < 0 || instance.uiTransparency > 90) instance.uiTransparency = 20;
        if (instance.textTransparency < 0) instance.textTransparency = 0;
        if (instance.textTransparency > 90) instance.textTransparency = 90;
        if (instance.musicVolume <= 0) instance.musicVolume = 100;
        if (instance.musicVolume > 200) instance.musicVolume = 200;
        return instance;
    }

    public static Config get() {
        if (instance == null) return load();
        return instance;
    }

    public void save() {
        try {
            Path target = savePath != null ? savePath : configPath();
            if (target.getParent() != null) Files.createDirectories(target.getParent());
            Files.writeString(target,
                    new GsonBuilder().setPrettyPrinting().create().toJson(this),
                    StandardCharsets.UTF_8);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public Path gamePath() {
        return Paths.get(gameDir);
    }

    public Path versionsDir() {
        return gamePath().resolve("versions");
    }

    public Path librariesDir() {
        return gamePath().resolve("libraries");
    }

    public Path assetsDir() {
        return gamePath().resolve("assets");
    }

    public Path modsDir() {
        return gamePath().resolve("mods");
    }

    public Path shaderpacksDir() {
        return gamePath().resolve("shaderpacks");
    }

    public Path resourcepacksDir() {
        return gamePath().resolve("resourcepacks");
    }

    public Path instancesDir() {
        return gamePath().resolve("instances");
    }

    /** 某个版本被隔离时使用的独立游戏目录（mods / saves / config 等都放这里）。 */
    public Path instanceDir(String versionId) {
        return instancesDir().resolve(versionId);
    }

    /** 按名字查找整合包实例。 */
    public Modpack findModpack(String name) {
        for (Modpack m : modpacks) {
            if (m.name.equals(name)) return m;
        }
        return null;
    }

    /** 新增或覆盖一个整合包实例并保存。 */
    public void upsertModpack(String name, String versionId, String loader) {
        modpacks.removeIf(m -> m.name.equals(name));
        modpacks.add(new Modpack(name, versionId, loader));
        save();
    }

    /** 某个版本的自定义显示名（没设置返回 null）。 */
    public String versionName(String versionId) {
        if (versionId == null || versionNames == null) return null;
        String n = versionNames.get(versionId);
        return (n == null || n.isBlank()) ? null : n;
    }

    /** 设置/清除版本自定义显示名（传 null 或空串表示恢复自动名）。 */
    public void setVersionName(String versionId, String name) {
        if (versionId == null) return;
        if (versionNames == null) versionNames = new java.util.LinkedHashMap<>();
        if (name == null || name.isBlank()) versionNames.remove(versionId);
        else versionNames.put(versionId, name.trim());
        save();
    }
}
