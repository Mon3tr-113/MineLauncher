package mclauncher.launch;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * 启动前修正游戏内的 {@code options.txt}（目前只处理语言）。
 *
 * <p>Minecraft 把界面语言存在 options.txt 的 {@code lang:} 一行里，
 * 所以「默认中文」就是在启动前把这一行写成 {@code lang:zh_cn}。
 */
public final class GameOptions {

    /** 「跟随游戏」：不修改 options.txt。 */
    public static final String KEEP = "keep";

    private GameOptions() {
    }

    /**
     * 让游戏以指定语言启动。
     *
     * @param gameDir 实际游戏目录（版本隔离时是实例目录）
     * @param lang    语言代码，如 {@code zh_cn} / {@code en_us}；为 null、空或 {@link #KEEP} 时不改动
     */
    public static void applyLanguage(Path gameDir, String lang, Consumer<String> log) throws IOException {
        if (gameDir == null || lang == null || lang.isBlank() || KEEP.equalsIgnoreCase(lang)) {
            return;
        }
        Path file = gameDir.resolve("options.txt");
        List<String> lines = new ArrayList<>();
        if (Files.exists(file)) {
            lines.addAll(Files.readAllLines(file, StandardCharsets.UTF_8));
        }

        String want = "lang:" + lang;
        int idx = -1;
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).startsWith("lang:")) {
                idx = i;
                break;
            }
        }

        boolean changed;
        if (idx >= 0) {
            changed = !lines.get(idx).equals(want);
            if (changed) lines.set(idx, want);
        } else {
            lines.add(want);
            changed = true;
        }

        if (!changed) {
            if (log != null) log.accept("[语言] 游戏语言已经是 " + lang + "，无需修改");
            return;
        }
        Files.createDirectories(gameDir);
        Files.write(file, lines, StandardCharsets.UTF_8);
        if (log != null) log.accept("[语言] 已把游戏语言设为 " + lang + "（options.txt）");
    }

    /** 读出当前语言设置，没有则返回 null（供自检/调试）。 */
    public static String currentLanguage(Path gameDir) throws IOException {
        Path file = gameDir.resolve("options.txt");
        if (!Files.exists(file)) return null;
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            if (line.startsWith("lang:")) return line.substring("lang:".length()).trim();
        }
        return null;
    }
}
