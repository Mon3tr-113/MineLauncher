package mclauncher.ui;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;

/** UI 配色与小工具。默认深色 + PCL 同款蓝色调。 */
public final class Ui {

    public static Color BG = new Color(0x1E1E1E);
    public static Color SIDEBAR = new Color(0x121212);
    public static Color CARD = new Color(0x2C2C2C);
    public static Color TEXT = new Color(0xE6E6E6);
    public static Color MUTED = new Color(0x828282);

    /** 主题色（可动态更改），默认 PCL 同款蓝色。 */
    public static Color ACCENT = new Color(0x4A90D9);

    @FunctionalInterface
    public interface ThrowingRunnable {
        void run() throws Exception;
    }

    private Ui() {
    }

    /** 依次应用主题色、背景色、文字色（无效则回退默认值），并派生侧栏/卡片/次要文字色。
     *  panelAlpha 为面板不透明度（1.0=不透明；启用背景图时可设为 0.8 让图片透出）。 */
    public static void applyTheme(String accentHex, String bgHex, String textHex, double panelAlpha) {
        applyTheme(accentHex, bgHex, textHex, panelAlpha, 1.0);
    }

    /** 同上，textAlpha 控制文字（含次要文字）的不透明度，1.0=完全不透明。 */
    public static void applyTheme(String accentHex, String bgHex, String textHex,
                                  double panelAlpha, double textAlpha) {
        ACCENT = parseHex(accentHex);
        if (ACCENT == null) ACCENT = new Color(0x4A90D9);
        Color bgBase = parseHex(bgHex);
        if (bgBase == null) bgBase = new Color(0x1E1E1E);
        Color textBase = parseHex(textHex);
        if (textBase == null) textBase = new Color(0xE6E6E6);
        double ta = Math.max(0, Math.min(1, textAlpha));
        TEXT = withAlpha(textBase, ta);
        BG = withAlpha(bgBase, panelAlpha);
        SIDEBAR = withAlpha(shift(bgBase, -0x0C), panelAlpha);
        CARD = withAlpha(shift(bgBase, 0x0E), panelAlpha);
        MUTED = withAlpha(mix(textBase, bgBase, 0.5f), ta);
    }

    /** 不透明的 3 参版本。 */
    public static void applyTheme(String accentHex, String bgHex, String textHex) {
        applyTheme(accentHex, bgHex, textHex, 1.0);
    }

    private static Color withAlpha(Color c, double alpha) {
        int a = (int) Math.round(Math.max(0, Math.min(1, alpha)) * 255);
        return new Color(c.getRed(), c.getGreen(), c.getBlue(), a);
    }

    private static Color shift(Color c, int delta) {
        return new Color(clamp(c.getRed() + delta), clamp(c.getGreen() + delta), clamp(c.getBlue() + delta));
    }

    private static int clamp(int v) {
        return Math.max(0, Math.min(255, v));
    }

    private static Color mix(Color a, Color b, float t) {
        return new Color(
                (int) (a.getRed() * (1 - t) + b.getRed() * t),
                (int) (a.getGreen() * (1 - t) + b.getGreen() * t),
                (int) (a.getBlue() * (1 - t) + b.getBlue() * t));
    }

    public static Color parseHex(String hex) {
        if (hex == null || hex.isBlank()) return null;
        String h = hex.trim();
        if (h.startsWith("#")) h = h.substring(1);
        if (h.length() == 6) {
            try {
                return new Color(Integer.parseInt(h, 16));
            } catch (NumberFormatException ignored) {
            }
        }
        return null;
    }

    public static String toHex(Color c) {
        return String.format("#%02X%02X%02X", c.getRed(), c.getGreen(), c.getBlue());
    }

    /** 主题色的浅色/深色变体（用于按钮悬停等）。 */
    public static Color accentBright() {
        return ACCENT.brighter();
    }

    /** 让滚动容器整体透明（含 viewport 与里面的组件），半透明面板与背景图才能透出来。 */
    public static JScrollPane transparentScroll(JComponent view) {
        view.setOpaque(false);
        JScrollPane sp = new JScrollPane(view);
        sp.setOpaque(false);
        sp.getViewport().setOpaque(false);
        sp.setBorder(new EmptyBorder(0, 0, 0, 0));
        return sp;
    }

    /** 读取打包进 jar 的启动器图标（可能为 null）。 */
    public static java.awt.Image appIcon() {
        try (var in = Ui.class.getResourceAsStream("/icon.png")) {
            if (in != null) return javax.imageio.ImageIO.read(in);
        } catch (Exception ignored) {
        }
        return null;
    }

    /**
     * 任务栏/标题栏用的多尺寸图标。
     * Windows 会自己挑最合适的尺寸：只给一张 256 的图，任务栏 32×32 上会被糊得很惨。
     */
    public static java.util.List<java.awt.Image> appIcons() {
        java.util.List<java.awt.Image> out = new java.util.ArrayList<>();
        java.awt.Image base = appIcon();
        if (base == null) return out;
        out.add(base);
        for (int size : new int[]{16, 24, 32, 48, 64, 128}) {
            out.add(base.getScaledInstance(size, size, java.awt.Image.SCALE_SMOOTH));
        }
        return out;
    }

    /** 内置背景图预设：{显示名, 配置里存的值}。图片打包在 jar 内 /presets/ 下。 */
    public static final String[][] BACKGROUND_PRESETS = {
            {"预设·暮色双影", "preset:1"},
            {"预设·黄昏剪影", "preset:2"},
            {"预设·云下守望", "preset:3"},
            {"预设·终末雪原", "preset:4"},
            {"预设·雾海水城", "preset:5"},
            {"预设·午后工坊", "preset:6"},
            {"预设·午夜窗景", "preset:7"},
    };

    /** 按配置值加载背景图：preset:N 读 jar 内资源，其余按文件路径读。加载失败返回 null。 */
    public static java.awt.Image loadBackgroundImage(String spec) {
        if (spec == null || spec.isBlank()) return null;
        if (spec.startsWith("preset:")) {
            String name = "/presets/bg" + spec.substring("preset:".length()) + ".jpg";
            try (var in = Ui.class.getResourceAsStream(name)) {
                if (in != null) return javax.imageio.ImageIO.read(in);
            } catch (Exception ignored) {
            }
            return null;
        }
        try {
            java.io.File f = new java.io.File(spec);
            if (f.isFile()) return new ImageIcon(spec).getImage();
        } catch (Exception ignored) {
        }
        return null;
    }

    /**
     * 内置音乐预设：{显示名, 音频文件路径}。
     * 默认留空 —— 启动器要分发给别人，不能带开发机上的本地路径（用户自己「选择音乐」即可）。
     */
    public static final String[][] MUSIC_PRESETS = {
    };

    /** 背景图配置值的可读名称（预设显示中文名，文件显示路径）。 */
    public static String describeBackground(String spec) {
        if (spec == null || spec.isBlank()) return "未设置";
        for (String[] p : BACKGROUND_PRESETS) {
            if (p[1].equals(spec)) return p[0];
        }
        return spec;
    }

    public static void onEdt(Runnable r) {
        if (SwingUtilities.isEventDispatchThread()) {
            r.run();
        } else {
            SwingUtilities.invokeLater(r);
        }
    }

    /** 在后台线程执行任务，异常弹窗提示。 */
    public static void runAsync(Component parent, ThrowingRunnable task) {
        Thread t = new Thread(() -> {
            try {
                task.run();
            } catch (Exception ex) {
                ex.printStackTrace();
                String raw = ex.getMessage() == null ? ex.toString() : ex.getMessage();
                // 多行错误（如下载失败的多源汇总）走 HTML，否则 JLabel 会把换行吃掉
                final String msg = raw.contains("\n")
                        ? "<html><div style='width:440px;'>" + raw.replace("\n", "<br>") + "</div></html>"
                        : raw;
                onEdt(() -> JOptionPane.showMessageDialog(parent, msg, "错误", JOptionPane.ERROR_MESSAGE));
            }
        }, "bg-task");
        t.setDaemon(true);
        t.start();
    }

    public static JButton button(String text) {
        JButton b = new JButton(text);
        b.setFocusPainted(false);
        return b;
    }

    /** 返回一个「玻璃面板」：非 opaque，但在 paintComponent 里手动填充半透明底色，
     *  避免 Swing 对「opaque + 半透明背景」的重绘叠加问题（透明度越高残影越严重）。 */
    public static JPanel glass(Color fill) {
        JPanel p = new JPanel() {
            @Override
            protected void paintComponent(Graphics g) {
                Color c = fill != null ? fill : getBackground();
                if (c != null) {
                    g.setColor(c);
                    g.fillRect(0, 0, getWidth(), getHeight());
                }
                super.paintComponent(g);
            }
        };
        p.setOpaque(false);
        if (fill != null) p.setBackground(fill);
        return p;
    }

    /** 去除 alpha 的不透明版本（用于列表等需要避免叠加的地方）。 */
    public static Color opaque(Color c) {
        return new Color(c.getRed(), c.getGreen(), c.getBlue());
    }

    public static JLabel muted(String text) {
        JLabel l = new JLabel(text);
        l.setForeground(MUTED);
        return l;
    }

    public static void pad(JComponent c, int top, int left, int bottom, int right) {
        c.setBorder(new EmptyBorder(top, left, bottom, right));
    }

    public static JPanel wrap(JComponent c) {
        JPanel p = new JPanel(new BorderLayout());
        p.setOpaque(false);
        p.add(c, BorderLayout.CENTER);
        return p;
    }
}
