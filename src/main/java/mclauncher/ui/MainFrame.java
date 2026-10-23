package mclauncher.ui;

import mclauncher.config.Config;
import mclauncher.net.DownloadManager;

import javax.swing.*;
import java.awt.*;

/** 主窗口：左侧导航 + 内容区 + 底部状态栏。 */
public class MainFrame extends JFrame {

    private final Config config;
    private final CardLayout cards = new CardLayout();
    private final JPanel content = new JPanel(cards);
    private final BackgroundPanel root = new BackgroundPanel(new BorderLayout());
    private final JLabel statusLabel = new JLabel("就绪");
    private final JLabel musicTrackLabel = new JLabel("♪ 未播放");
    private final JLabel musicTimeLabel = new JLabel("0:00 / 0:00");
    private final JProgressBar musicProgress = new JProgressBar();
    private JButton musicToggleButton;
    private final JLabel speedLabel = new JLabel("");

    private final LaunchPage launchPage;
    private final DownloadPage downloadPage;
    private final DownloadCenterPage downloadCenterPage;
    private final InstallPage installPage;
    private final ResourceDownloadPage resourcePage;
    private final PersonalizationPage personalizationPage;
    private final SettingsPage settingsPage;

    public MainFrame(Config config) {
        this.config = config;
        setTitle("方块启动器 MineLauncher v0.1.0");
        setSize(1280, 840);
        setMinimumSize(new Dimension(1000, 680));
        setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
        setLocationRelativeTo(null);

        // 磁盘上已有、但 config.json 里没登记的整合包补回来（config 被重置 / 手动拷入实例目录）
        try {
            mclauncher.modpack.ModpackInstaller.syncFromDisk(config);
        } catch (Exception ignored) {
        }

        setLayout(new BorderLayout());

        content.setOpaque(false);
        launchPage = new LaunchPage(config, this);
        downloadPage = new DownloadPage(config, this);
        downloadCenterPage = new DownloadCenterPage();
        installPage = new InstallPage(config, this);
        resourcePage = new ResourceDownloadPage(config, this);
        personalizationPage = new PersonalizationPage(config, this);
        settingsPage = new SettingsPage(config, this);
        content.add(launchPage, "launch");
        content.add(downloadPage, "download");
        content.add(downloadCenterPage, "downloadcenter");
        content.add(installPage, "install");
        content.add(resourcePage, "resource");
        content.add(personalizationPage, "personalization");
        content.add(settingsPage, "settings");

        root.add(buildSidebar(), BorderLayout.WEST);
        root.add(content, BorderLayout.CENTER);

        // 自定义背景图（铺满整个窗口，包括侧栏和状态栏）
        if (config.backgroundImage != null && !config.backgroundImage.isBlank()) {
            java.awt.Image bg = Ui.loadBackgroundImage(config.backgroundImage);
            if (bg != null) {
                root.setImage(bg);
            }
        }

        JPanel statusBar = Ui.glass(Ui.SIDEBAR);
        statusBar.setLayout(new BorderLayout());
        statusLabel.setForeground(Ui.MUTED);
        statusLabel.setBorder(BorderFactory.createEmptyBorder(6, 12, 6, 12));
        speedLabel.setForeground(Ui.ACCENT);
        speedLabel.setBorder(BorderFactory.createEmptyBorder(6, 12, 6, 12));
        statusBar.add(statusLabel, BorderLayout.WEST);
        statusBar.add(speedLabel, BorderLayout.EAST);

        JPanel south = new JPanel(new BorderLayout());
        south.setOpaque(false);
        south.add(buildMusicBar(), BorderLayout.NORTH);
        south.add(statusBar, BorderLayout.SOUTH);
        root.add(south, BorderLayout.SOUTH);

        add(root);

        // 外部整合包拖拽：整个窗口都能接住（.mrpack / 整合包 zip / 实例目录）
        ModpackDropHandler.install(this, root, content, launchPage, downloadPage);

        // 每 500ms 刷新底部下载网速
        Timer speedTimer = new Timer(500, e -> {
            DownloadManager.Task t = DownloadManager.get().activeTask();
            if (t != null) {
                String files = t.filesTotal > 0 && t.filesRemaining() > 0
                        ? "  剩 " + t.filesRemaining() + " 个文件" : "";
                speedLabel.setText("下载中：" + t.name + "  " + DownloadManager.formatSpeed(t.speed) + files);
            } else {
                speedLabel.setText("");
            }
        });
        speedTimer.start();

        showPage("launch");
    }

    private JPanel buildSidebar() {
        JPanel sidebar = Ui.glass(Ui.SIDEBAR);
        sidebar.setLayout(new BoxLayout(sidebar, BoxLayout.Y_AXIS));
        sidebar.setPreferredSize(new Dimension(150, 0));
        sidebar.setBorder(BorderFactory.createEmptyBorder(18, 0, 18, 0));

        JLabel logo = new JLabel("方块启动器", SwingConstants.CENTER);
        logo.setForeground(Ui.ACCENT);
        logo.setFont(logo.getFont().deriveFont(Font.BOLD, 16f));
        logo.setAlignmentX(Component.CENTER_ALIGNMENT);
        logo.setMaximumSize(new Dimension(150, 40));
        sidebar.add(logo);
        sidebar.add(Box.createVerticalStrut(16));

        sidebar.add(navButton("启动游戏", () -> showPage("launch")));
        sidebar.add(navButton("下载资源", () -> showPage("download")));
        sidebar.add(navButton("下载中心", () -> showPage("downloadcenter")));
        sidebar.add(navButton("个性化", () -> showPage("personalization")));
        sidebar.add(navButton("设置", () -> showPage("settings")));
        sidebar.add(Box.createVerticalGlue());
        return sidebar;
    }

    private JButton navButton(String text, Runnable action) {
        JButton b = new JButton(text) {
            @Override
            protected void paintComponent(Graphics g) {
                if (getModel().isRollover() || getModel().isPressed()) {
                    g.setColor(Ui.CARD);
                    g.fillRect(0, 0, getWidth(), getHeight());
                }
                super.paintComponent(g);
            }
        };
        b.setAlignmentX(Component.CENTER_ALIGNMENT);
        b.setMaximumSize(new Dimension(150, 44));
        b.setForeground(Ui.TEXT);
        b.setFocusPainted(false);
        b.setContentAreaFilled(false);
        b.setBorderPainted(false);
        b.setOpaque(false);
        b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        b.addActionListener(e -> action.run());
        return b;
    }

    public void showPage(String name) {
        cards.show(content, name);
    }

    public void showDownloadVersions() {
        downloadPage.selectTab(0);
        showPage("download");
    }

    public void openInstallPage(String versionId) {
        installPage.openFor(versionId);
        showPage("install");
    }

    public void openResourcePage(mclauncher.modrinth.ModrinthApi.Hit hit, String type,
                                 java.util.function.Supplier<java.nio.file.Path> targetDir) {
        resourcePage.openFor(hit, type, targetDir);
        showPage("resource");
    }

    /** 安装外部整合包文件（拖拽 / 文件选择）：跳到整合包页并开始装。 */
    public void installModpackFile(java.nio.file.Path file) {
        showPage("download");
        downloadPage.installModpackFile(file);
    }

    /** 自检用：跳过确认对话框直接装（走的是和界面完全一样的安装路径）。 */
    public void installModpackFileNoConfirm(java.nio.file.Path file) {
        showPage("download");
        downloadPage.installModpackFileNoConfirm(file);
    }

    /** 应用主题色/背景/音乐后重建窗口（Swing 无法可靠地局部换肤，直接重建最稳）。 */
    public void applyThemeAndRebuild() {
        SwingUtilities.invokeLater(() -> {
            dispose();
            new MainFrame(config).setVisible(true);
        });
    }

    public void refreshLaunch() {
        launchPage.refreshVersions();
        launchPage.refreshAccounts();
        downloadPage.refreshTargetVersions();
        downloadPage.refreshModpacks();
    }

    /** 底部音乐条：曲名 + 进度 + 已播放/总时长 + 上一首/播放停止/下一首。 */
    private JPanel buildMusicBar() {
        JPanel bar = Ui.glass(Ui.SIDEBAR);
        bar.setLayout(new BorderLayout(10, 0));
        bar.setBorder(BorderFactory.createEmptyBorder(4, 12, 4, 12));

        musicTrackLabel.setForeground(Ui.MUTED);
        musicTrackLabel.setPreferredSize(new Dimension(220, 20));

        musicProgress.setMinimum(0);
        musicProgress.setMaximum(1000);
        musicProgress.setBorderPainted(false);
        musicProgress.setForeground(Ui.ACCENT);

        musicTimeLabel.setForeground(Ui.MUTED);
        musicTimeLabel.setPreferredSize(new Dimension(96, 20));

        JButton prev = Ui.button("上一首");
        JButton toggle = Ui.button("播放");
        JButton next = Ui.button("下一首");
        prev.addActionListener(e -> mclauncher.audio.MusicPlayer.previous());
        next.addActionListener(e -> mclauncher.audio.MusicPlayer.next());
        toggle.addActionListener(e -> {
            if (mclauncher.audio.MusicPlayer.isPlaying()) {
                mclauncher.audio.MusicPlayer.stop();
            } else {
                java.util.List<String> pl = mclauncher.audio.MusicPlayer.getPlaylist();
                if (!pl.isEmpty()) {
                    mclauncher.audio.MusicPlayer.setPlaylist(pl,
                            Math.max(0, mclauncher.audio.MusicPlayer.currentIndex()));
                }
            }
        });
        musicToggleButton = toggle;

        JPanel left = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 6, 0));
        left.setOpaque(false);
        left.add(musicTrackLabel);

        JPanel right = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.RIGHT, 6, 0));
        right.setOpaque(false);
        right.add(musicTimeLabel);
        right.add(prev);
        right.add(toggle);
        right.add(next);

        JPanel center = new JPanel(new BorderLayout(10, 0));
        center.setOpaque(false);
        center.add(musicProgress, BorderLayout.CENTER);
        center.add(right, BorderLayout.EAST);

        bar.add(left, BorderLayout.WEST);
        bar.add(center, BorderLayout.CENTER);

        new javax.swing.Timer(500, e -> {
            musicTrackLabel.setText("♪ " + mclauncher.audio.MusicPlayer.currentName());
            musicProgress.setValue((int) Math.round(mclauncher.audio.MusicPlayer.progress() * 1000));
            musicTimeLabel.setText(mclauncher.audio.MusicPlayer.formatTime(mclauncher.audio.MusicPlayer.elapsedMs())
                    + " / " + mclauncher.audio.MusicPlayer.formatTime(mclauncher.audio.MusicPlayer.totalMs()));
            if (musicToggleButton != null) {
                musicToggleButton.setText(mclauncher.audio.MusicPlayer.isPlaying() ? "停止" : "播放");
            }
        }).start();
        return bar;
    }

    public void setStatus(String s) {        statusLabel.setText(s);
    }

    @Override
    public void setVisible(boolean b) {
        super.setVisible(b);
        if (b) {
            refreshLaunch();
        }
    }
}
