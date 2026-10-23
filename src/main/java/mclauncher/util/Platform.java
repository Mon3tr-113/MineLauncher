package mclauncher.util;

import java.util.Locale;

/** 平台相关小工具。 */
public final class Platform {

    private Platform() {
    }

    /** 归一化操作系统名：windows / osx / linux（与版本 JSON 规则中的 os.name 一致）。 */
    public static String osName() {
        String os = System.getProperty("os.name").toLowerCase(Locale.ROOT);
        if (os.contains("win")) return "windows";
        if (os.contains("mac") || os.contains("darwin")) return "osx";
        return "linux";
    }

    /** 归一化 CPU 架构：x86（含 amd64/x86_64/x86）或 arm64，与版本 JSON 规则中的 os.arch 一致。 */
    public static String archName() {
        String arch = System.getProperty("os.arch").toLowerCase(Locale.ROOT);
        if (arch.equals("aarch64") || arch.equals("arm64")) return "arm64";
        return "x86";
    }

    /**
     * 新格式（1.19.3+）natives 库的 classifier 目标：
     * windows -> natives-windows，linux -> natives-linux，osx -> natives-macos；
     * arm64 追加 -arm64，32 位 x86 追加 -x86，64 位 x86 不带后缀。
     */
    public static String nativesClassifier() {
        String base = switch (osName()) {
            case "windows" -> "natives-windows";
            case "linux" -> "natives-linux";
            default -> "natives-macos";
        };
        if (isArm64()) return base + "-arm64";
        return is64Bit() ? base : base + "-x86";
    }

    /** 是否为 64 位 JVM（决定老格式 natives 里 ${arch} 占位符替换成 64 还是 32）。 */
    public static boolean is64Bit() {
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        if (arch.contains("64") || arch.equals("aarch64") || arch.equals("arm64")) return true;
        // os.arch 也可能是 amd64 以外的写法，用 JVM 数据模型兜底
        return "64".equals(System.getProperty("sun.arch.data.model"));
    }

    /** ${arch} 占位符的替换值：64 / 32。 */
    public static String archBits() {
        return is64Bit() ? "64" : "32";
    }

    public static boolean isArm64() {
        return "arm64".equals(archName());
    }

    public static String pathSeparator() {
        return System.getProperty("path.separator");
    }
}
