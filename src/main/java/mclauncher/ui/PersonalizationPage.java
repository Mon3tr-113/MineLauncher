package mclauncher.ui;

import mclauncher.audio.MusicPlayer;
import mclauncher.config.Config;

import javax.swing.*;
import java.awt.*;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

/** 个性化页：主题色 / 背景色 / 文字色 / 自定义背景图 / 自定义音乐。 */
public class PersonalizationPage extends JPanel {

    private static final String[] ACCENT_COLORS = {
            "#4A90D9", "#7289DA", "#00BCD4", "#26C6DA", "#4CAF50", "#8BC34A",
            "#FF9800", "#FF5722", "#E91E63", "#9C27B0", "#673AB7", "#3F51B5"
    };
    private static final String[] BG_COLORS = {
            "#1E1E1E", "#121212", "#2B2B2B", "#1A1A2E", "#0F1B2D", "#1E2A1E",
            "#2A1E2E", "#252020", "#FFFFFF", "#F0F0F0", "#E8E8E8", "#D8D8D8"
    };
    private static final String[] TEXT_COLORS = {
            "#E6E6E6", "#FFFFFF", "#000000", "#1E1E1E", "#CCCCCC", "#AAAAAA",
            "#B0B0B0", "#D0D0D0", "#FFD54F", "#81D4FA", "#A5D6A7", "#F48FB1"
    };

    private final Config cfg;
    private final MainFrame frame;

    private Color selectedAccent;
    private Color selectedBg;
    private Color selectedText;
    private String backgroundImage;
    private String musicFile;

    private final List<JButton> accentSwatches = new ArrayList<>();
    private final List<JButton> bgSwatches = new ArrayList<>();
    private final List<JButton> textSwatches = new ArrayList<>();
    private final List<JComponent> bgColorControls = new ArrayList<>();
    private final JLabel bgColorNote = new JLabel();
    /** 底部常驻栏里的「当前背景」提示。 */
    private final JLabel bgStatusLabel = new JLabel("当前背景：未设置");
    /** 内置预设按钮，用来给当前选中的那个描边，方便确认选到了哪张。 */
    private final List<JButton> bgPresetButtons = new ArrayList<>();
    private final JSlider transparencySlider = new JSlider(0, 90, 20);
    private final JSlider textAlphaSlider = new JSlider(0, 90, 0);
    private final JSlider volumeSlider = new JSlider(0, 200, 100);
    private final List<String> playlist = new ArrayList<>();

    public PersonalizationPage(Config cfg, MainFrame frame) {
        this.cfg = cfg;
        this.frame = frame;
        this.selectedAccent = or(Ui.parseHex(cfg.themeColor), new Color(0x4A90D9));
        this.selectedBg = or(Ui.parseHex(cfg.backgroundColor), new Color(0x1E1E1E));
        this.selectedText = or(Ui.parseHex(cfg.textColor), new Color(0xE6E6E6));
        this.backgroundImage = cfg.backgroundImage;
        this.musicFile = cfg.musicFile;
        this.transparencySlider.setValue(Math.max(0, Math.min(90, cfg.uiTransparency)));
        this.transparencySlider.setOpaque(false);
        this.textAlphaSlider.setValue(Math.max(0, Math.min(90, cfg.textTransparency)));
        this.textAlphaSlider.setOpaque(false);
        this.volumeSlider.setValue(Math.max(0, Math.min(200, cfg.musicVolume)));
        this.volumeSlider.setOpaque(false);
        if (cfg.playlist != null) this.playlist.addAll(cfg.playlist);
        if (this.playlist.isEmpty() && cfg.musicFile != null && !cfg.musicFile.isBlank()) {
            this.playlist.add(cfg.musicFile);
        }

        setOpaque(false);
        setLayout(new BorderLayout());
        // 内容比窗口高时（预设多了很容易超），必须能滚动，否则下面的「应用设置」被裁掉、点不到
        JScrollPane scroll = Ui.transparentScroll(buildPanel());
        scroll.getVerticalScrollBar().setUnitIncrement(24);
        add(scroll, BorderLayout.CENTER);
        // 「应用设置」固定在底部，永远可见
        add(buildApplyBar(), BorderLayout.SOUTH);
        refreshStates();
    }

    /** 底部常驻操作栏：左侧显示「当前背景」，右侧是「应用设置」。 */
    private JPanel buildApplyBar() {
        JPanel bar = Ui.glass(Ui.CARD);
        bar.setLayout(new BorderLayout(8, 0));
        bar.setBorder(BorderFactory.createEmptyBorder(8, 14, 8, 14));
        bgStatusLabel.setForeground(Ui.TEXT);
        bar.add(bgStatusLabel, BorderLayout.CENTER);
        JButton apply = Ui.button("应用设置");
        apply.setFont(apply.getFont().deriveFont(Font.BOLD, 15f));
        apply.setToolTipText("把上面的主题色 / 背景 / 音乐设置保存并立即生效");
        apply.addActionListener(e -> apply());
        bar.add(apply, BorderLayout.EAST);
        return bar;
    }

    private static Color or(Color c, Color def) {
        return c != null ? c : def;
    }

    private JPanel buildPanel() {
        JPanel panel = Ui.glass(Ui.CARD);
        panel.setLayout(new BorderLayout(0, 14));
        panel.setBorder(BorderFactory.createEmptyBorder(18, 18, 18, 18));

        JLabel title = new JLabel("个性化");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 20f));
        title.setForeground(Ui.TEXT);
        panel.add(title, BorderLayout.NORTH);

        JPanel body = new JPanel();
        body.setOpaque(false);
        body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));

        body.add(sectionLabel("主题色调"));
        body.add(swatchRow(ACCENT_COLORS, accentSwatches, c -> {
            selectedAccent = c;
            refreshStates();
        }, true, false));

        body.add(sectionLabel("背景颜色（选择了背景图后不生效）"));
        body.add(swatchRow(BG_COLORS, bgSwatches, c -> {
            selectedBg = c;
            refreshStates();
        }, false, true));
        bgColorNote.setForeground(Ui.MUTED);
        bgColorNote.setBorder(BorderFactory.createEmptyBorder(2, 0, 2, 0));
        body.add(bgColorNote);

        body.add(sectionLabel("文字颜色"));
        body.add(swatchRow(TEXT_COLORS, textSwatches, c -> {
            selectedText = c;
            refreshStates();
        }, false, false));

        body.add(sectionLabel("自定义背景图"));
        body.add(buildBackgroundRow());
        body.add(sectionLabel("背景图启用时的 UI 透明度（%）"));
        body.add(buildTransparencyRow());
        body.add(sectionLabel("文字透明度（%）"));
        body.add(buildTextAlphaRow());
        body.add(sectionLabel("自定义背景音乐（wav / mp3）"));
        body.add(buildMusicRow());
        body.add(sectionLabel("音乐音量（%）— 100 为原始音量，可放大到 200"));
        body.add(buildVolumeRow());

        JPanel applyRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 10));
        applyRow.setOpaque(false);
        JLabel hint = Ui.muted("改完点右下角的「应用设置」立即生效（背景图可以只预览，不应用就关窗口）");
        applyRow.add(hint);
        body.add(applyRow);

        panel.add(body, BorderLayout.CENTER);
        return panel;
    }

    private JPanel swatchRow(String[] colors, List<JButton> target, java.util.function.Consumer<Color> onPick,
                             boolean isAccent, boolean isBg) {
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 6));
        row.setOpaque(false);
        for (String hex : colors) {
            JButton b = new JButton();
            b.setPreferredSize(new Dimension(32, 32));
            b.setOpaque(true);
            b.setContentAreaFilled(true);
            b.setFocusPainted(false);
            b.setBackground(Ui.parseHex(hex));
            b.setBorder(BorderFactory.createLineBorder(Color.GRAY));
            b.setToolTipText(hex);
            b.addActionListener(e -> onPick.accept(Ui.parseHex(hex)));
            target.add(b);
            row.add(b);
            if (isBg) bgColorControls.add(b);
        }
        JButton custom = Ui.button(isAccent ? "自定义颜色" : "自定义");
        custom.addActionListener(e -> {
            Color init = isAccent ? selectedAccent : null;
            Color c = JColorChooser.showDialog(this, "选择颜色", init);
            if (c != null) onPick.accept(c);
        });
        if (isBg) bgColorControls.add(custom);
        row.add(custom);
        return row;
    }

    private JPanel buildBackgroundRow() {
        JPanel wrap = new JPanel();
        wrap.setOpaque(false);
        wrap.setLayout(new BoxLayout(wrap, BoxLayout.Y_AXIS));

        // 第一行：当前背景 + 选择图片 + 清除
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
        row.setOpaque(false);
        JLabel bgLabel = Ui.muted(Ui.describeBackground(backgroundImage));
        JButton choose = Ui.button("选择图片");
        choose.addActionListener(e -> {
            File f = Dialogs.chooseFile(this, "选择背景图片（png/jpg）");
            if (f != null) {
                backgroundImage = f.getAbsolutePath();
                bgLabel.setText(Ui.describeBackground(backgroundImage));
                refreshStates();
            }
        });
        JButton clear = Ui.button("清除");
        clear.addActionListener(e -> {
            backgroundImage = null;
            bgLabel.setText("未设置");
            refreshStates();
        });
        row.add(bgLabel);
        row.add(choose);
        row.add(clear);
        wrap.add(row);

        // 第二行：内置预设（单独一行 + 网格排布，预设多了也不会被挤掉）
        JPanel presetRow = new JPanel(new BorderLayout(0, 4));
        presetRow.setOpaque(false);
        presetRow.add(Ui.muted("内置预设（点一下直接套用，图片已打包在启动器里）："), BorderLayout.NORTH);
        JPanel grid = new JPanel(new GridLayout(0, 3, 6, 6));
        grid.setOpaque(false);
        for (String[] preset : Ui.BACKGROUND_PRESETS) {
            JButton pb = Ui.button(preset[0]);
            pb.setToolTipText(preset[1]);
            pb.putClientProperty("presetSpec", preset[1]);
            pb.addActionListener(e -> {
                backgroundImage = preset[1];
                bgLabel.setText(Ui.describeBackground(backgroundImage));
                refreshStates();
            });
            bgPresetButtons.add(pb);
            grid.add(pb);
        }
        presetRow.add(grid, BorderLayout.CENTER);
        wrap.add(presetRow);
        return wrap;
    }

    private JPanel buildTransparencyRow() {
        JPanel row = new JPanel(new BorderLayout(8, 0));
        row.setOpaque(false);
        JLabel val = Ui.muted(transparencySlider.getValue() + "%");
        transparencySlider.addChangeListener(e -> val.setText(transparencySlider.getValue() + "%"));
        row.add(transparencySlider, BorderLayout.CENTER);
        row.add(val, BorderLayout.EAST);
        return row;
    }

    private JPanel buildTextAlphaRow() {
        JPanel row = new JPanel(new BorderLayout(8, 0));
        row.setOpaque(false);
        JLabel val = Ui.muted(textAlphaSlider.getValue() + "%");
        textAlphaSlider.addChangeListener(e -> val.setText(textAlphaSlider.getValue() + "%"));
        row.add(textAlphaSlider, BorderLayout.CENTER);
        row.add(val, BorderLayout.EAST);
        return row;
    }

    private JPanel buildMusicRow() {
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
        row.setOpaque(false);
        JLabel musicLabel = Ui.muted(musicFile == null ? "未设置" : musicFile);
        JButton choose = Ui.button("选择音乐");
        choose.addActionListener(e -> {
            File f = Dialogs.chooseFile(this, "选择音乐（wav/mp3）");
            if (f != null) {
                musicFile = f.getAbsolutePath();
                musicLabel.setText(musicFile);
            }
        });
        JButton play = Ui.button("播放 / 停止");
        play.addActionListener(e -> {
            if (MusicPlayer.isPlaying()) {
                MusicPlayer.stop();
            } else if (musicFile != null && !musicFile.isBlank()) {
                MusicPlayer.play(musicFile);
            }
        });
        row.add(musicLabel);
        row.add(choose);
        row.add(play);
        // 歌单：多选文件，加入后按顺序循环播放
        final JLabel listLabel = Ui.muted("歌单：" + playlist.size() + " 首");
        JButton addList = Ui.button("添加歌单（可多选）");
        addList.addActionListener(e -> {
            File[] fs = Dialogs.chooseFiles(this, "选择音乐（可按住 Ctrl 多选）");
            for (File f : fs) {
                if (!playlist.contains(f.getAbsolutePath())) playlist.add(f.getAbsolutePath());
            }
            if (fs.length > 0) {
                musicFile = playlist.get(0);
                musicLabel.setText(musicFile);
            }
            listLabel.setText("歌单：" + playlist.size() + " 首");
        });
        JButton clearList = Ui.button("清空歌单");
        clearList.addActionListener(e -> {
            playlist.clear();
            listLabel.setText("歌单：0 首");
            MusicPlayer.stop();
        });
        row.add(addList);
        row.add(clearList);
        row.add(listLabel);
        // 音乐预设：一键套用，省得每次找文件
        for (String[] preset : Ui.MUSIC_PRESETS) {
            JButton pb = Ui.button(preset[0]);
            pb.addActionListener(e -> {
                File pf = new File(preset[1]);
                if (!pf.isFile()) {
                    JOptionPane.showMessageDialog(this,
                            "预设音乐文件不存在：\n" + preset[1] + "\n\n请改用「选择音乐」指定本机上的音频文件。",
                            "提示", JOptionPane.WARNING_MESSAGE);
                    return;
                }
                musicFile = preset[1];
                musicLabel.setText(musicFile);
                MusicPlayer.play(musicFile);
            });
            row.add(pb);
        }
        return row;
    }

    /** 音量滑条：拖动即时生效，不用点「应用设置」。 */
    private JPanel buildVolumeRow() {
        JPanel row = new JPanel(new BorderLayout(8, 0));
        row.setOpaque(false);
        JLabel val = Ui.muted(volumeSlider.getValue() + "%");
        volumeSlider.addChangeListener(e -> {
            int v = volumeSlider.getValue();
            val.setText(v + "%");
            MusicPlayer.setVolume(v);
        });
        row.add(volumeSlider, BorderLayout.CENTER);
        row.add(val, BorderLayout.EAST);
        return row;
    }

    private JLabel sectionLabel(String text) {
        JLabel l = new JLabel(text);
        l.setFont(l.getFont().deriveFont(Font.BOLD, 14f));
        l.setForeground(Ui.TEXT);
        l.setBorder(BorderFactory.createEmptyBorder(10, 0, 2, 0));
        return l;
    }

    private void refreshStates() {
        // 选中描边
        markSelected(accentSwatches, selectedAccent);
        markSelected(bgSwatches, selectedBg);
        markSelected(textSwatches, selectedText);

        // 预设按钮：把当前选中的那张描边加粗，点完能立刻看出选中了哪个
        for (JButton b : bgPresetButtons) {
            Object spec = b.getClientProperty("presetSpec");
            boolean sel = spec != null && spec.equals(backgroundImage);
            b.setBorder(sel
                    ? BorderFactory.createLineBorder(Ui.ACCENT, 2, true)
                    : BorderFactory.createEmptyBorder(2, 2, 2, 2));
            b.setFont(b.getFont().deriveFont(sel ? Font.BOLD : Font.PLAIN));
        }

        // 选了背景图 -> 禁用背景颜色设置
        boolean imageSet = backgroundImage != null && !backgroundImage.isBlank();
        for (JComponent c : bgColorControls) {
            c.setEnabled(!imageSet);
        }
        bgColorNote.setText(imageSet
                ? "已选择背景图，背景颜色暂不生效（清除图片后恢复）"
                : "未选择背景图时使用上面的背景颜色");

        // 底部常驻栏：显示当前背景（是不是已经应用过，也能一眼看出来）
        if (!imageSet) {
            bgStatusLabel.setText("当前背景：未设置（用下面的背景颜色）");
        } else {
            String name = Ui.describeBackground(backgroundImage);
            boolean applied = backgroundImage.equals(cfg.backgroundImage);
            bgStatusLabel.setText("当前背景：" + name + (applied ? "（已应用）" : "（已选中，点右侧「应用设置」生效）"));
        }
    }

    private void markSelected(List<JButton> buttons, Color selected) {
        for (JButton b : buttons) {
            boolean sel = b.getBackground().equals(selected);
            b.setBorder(BorderFactory.createLineBorder(sel ? Color.WHITE : Color.GRAY, sel ? 3 : 1));
        }
    }

    private void apply() {
        cfg.themeColor = Ui.toHex(selectedAccent);
        cfg.backgroundColor = Ui.toHex(selectedBg);
        cfg.textColor = Ui.toHex(selectedText);
        cfg.uiTransparency = transparencySlider.getValue();
        cfg.textTransparency = textAlphaSlider.getValue();
        cfg.backgroundImage = backgroundImage;
        cfg.musicFile = musicFile;
        cfg.musicVolume = volumeSlider.getValue();
        cfg.playlist = new ArrayList<>(playlist);
        cfg.save();
        double alpha = (backgroundImage != null && !backgroundImage.isBlank())
                ? 1.0 - cfg.uiTransparency / 100.0 : 1.0;
        double textAlpha = 1.0 - cfg.textTransparency / 100.0;
        Ui.applyTheme(cfg.themeColor, cfg.backgroundColor, cfg.textColor, alpha, textAlpha);
        MusicPlayer.setVolume(cfg.musicVolume);
        if (!playlist.isEmpty()) {
            MusicPlayer.setPlaylist(playlist, 0);
        } else if (musicFile != null && !musicFile.isBlank()) {
            MusicPlayer.play(musicFile);
        } else {
            MusicPlayer.stop();
        }
        frame.applyThemeAndRebuild();
    }
}
