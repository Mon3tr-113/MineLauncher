package mclauncher.ui;

import mclauncher.config.Config;
import mclauncher.modpack.ModpackInstaller;
import mclauncher.modrinth.ModNames;
import mclauncher.modrinth.ModrinthApi;

import javax.swing.*;
import java.awt.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

/** 资源下载页：选中一个 Mod/光影/材质/整合包后，选 MC 版本与具体版本，再下载/安装。 */
public class ResourceDownloadPage extends JPanel {

    private final Config cfg;
    private final MainFrame frame;

    private ModrinthApi.Hit hit;
    private String type;
    private Supplier<Path> targetDir;

    private final JLabel iconLabel = new JLabel();
    private final JLabel titleLabel = new JLabel();
    private final JLabel descLabel = new JLabel();
    private final JComboBox<String> mcBox = new JComboBox<>();
    private final JComboBox<ModrinthApi.Version> versionBox = new JComboBox<>();
    private final JButton downloadBtn = Ui.button("下载");
    private final JButton backBtn = Ui.button("返回列表");
    private final JProgressBar progressBar = new JProgressBar();
    private final JLabel status = Ui.muted("");
    private final JTextArea logArea = new JTextArea();

    private final List<ModrinthApi.Version> allVersions = new ArrayList<>();
    private boolean loading = false;
    private boolean busy = false;

    public ResourceDownloadPage(Config cfg, MainFrame frame) {
        this.cfg = cfg;
        this.frame = frame;
        setOpaque(false);
        setLayout(new BorderLayout(0, 10));
        setBorder(BorderFactory.createEmptyBorder(16, 16, 16, 16));
        add(buildTop(), BorderLayout.NORTH);
        add(buildLog(), BorderLayout.CENTER);
        add(buildBottom(), BorderLayout.SOUTH);
        mcBox.addActionListener(e -> {
            if (!loading) populateVersions();
        });
    }

    private JPanel buildTop() {
        JPanel panel = Ui.glass(Ui.CARD);
        panel.setLayout(new BorderLayout(0, 10));
        panel.setBorder(BorderFactory.createEmptyBorder(14, 14, 14, 14));

        JPanel head = new JPanel(new BorderLayout(10, 0));
        head.setOpaque(false);
        iconLabel.setPreferredSize(new Dimension(44, 44));
        head.add(iconLabel, BorderLayout.WEST);
        JPanel titleBox = new JPanel();
        titleBox.setOpaque(false);
        titleBox.setLayout(new BoxLayout(titleBox, BoxLayout.Y_AXIS));
        titleLabel.setFont(titleLabel.getFont().deriveFont(Font.BOLD, 18f));
        titleLabel.setForeground(Ui.TEXT);
        descLabel.setForeground(Ui.MUTED);
        titleBox.add(titleLabel);
        titleBox.add(descLabel);
        head.add(titleBox, BorderLayout.CENTER);
        panel.add(head, BorderLayout.NORTH);

        JPanel form = new JPanel(new GridBagLayout());
        form.setOpaque(false);
        GridBagConstraints g = new GridBagConstraints();
        g.insets = new Insets(6, 8, 6, 8);
        g.anchor = GridBagConstraints.WEST;
        g.fill = GridBagConstraints.HORIZONTAL;

        g.gridx = 0; g.gridy = 0; g.weightx = 0;
        form.add(Ui.muted("Minecraft 版本"), g);
        g.gridx = 1; g.gridy = 0; g.weightx = 1;
        form.add(mcBox, g);

        g.gridx = 0; g.gridy = 1; g.weightx = 0;
        form.add(Ui.muted("资源版本"), g);
        g.gridx = 1; g.gridy = 1; g.weightx = 1;
        form.add(versionBox, g);

        g.gridx = 0; g.gridy = 2; g.weightx = 1; g.gridwidth = 2;
        JPanel btns = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        btns.setOpaque(false);
        downloadBtn.setFont(downloadBtn.getFont().deriveFont(Font.BOLD, 14f));
        downloadBtn.addActionListener(e -> download());
        backBtn.addActionListener(e -> frame.showDownloadVersions());
        btns.add(downloadBtn);
        btns.add(backBtn);
        form.add(btns, g);

        panel.add(form, BorderLayout.CENTER);
        return panel;
    }

    private JPanel buildLog() {
        JPanel panel = new JPanel(new BorderLayout(0, 6));
        panel.setOpaque(false);
        panel.add(Ui.muted("下载日志"), BorderLayout.NORTH);
        logArea.setEditable(false);
        logArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        logArea.setBackground(new Color(0x14151c));
        logArea.setForeground(new Color(0xd0d0d0));
        JScrollPane sp = new JScrollPane(logArea);
        sp.setBorder(BorderFactory.createEmptyBorder());
        panel.add(sp, BorderLayout.CENTER);
        return panel;
    }

    private JPanel buildBottom() {
        JPanel panel = new JPanel(new BorderLayout(8, 0));
        panel.setOpaque(false);
        panel.add(progressBar, BorderLayout.CENTER);
        panel.add(status, BorderLayout.SOUTH);
        return panel;
    }

    /** 从列表跳转进来：设置资源、类型、目标目录。 */
    public void openFor(ModrinthApi.Hit hit, String type, Supplier<Path> targetDir) {
        this.hit = hit;
        this.type = type;
        this.targetDir = targetDir;
        this.busy = false;

        String cn = ModNames.chinese(hit.slug);
        String title = cn != null ? cn + "  (" + hit.title + ")" : hit.title;
        titleLabel.setText(title);
        descLabel.setText(hit.description == null ? "" : hit.description);
        iconLabel.setIcon(IconCache.get(hit.iconUrl, () -> iconLabel.repaint()));

        logArea.setText("");
        progressBar.setValue(0);
        progressBar.setIndeterminate(false);
        status.setText("正在获取版本列表...");
        downloadBtn.setEnabled(false);
        loading = true;
        mcBox.removeAllItems();
        versionBox.removeAllItems();
        loading = false;

        Ui.runAsync(this, () -> {
            allVersions.clear();
            allVersions.addAll(ModrinthApi.versions(hit.projectId));
            Ui.onEdt(() -> {
                Set<String> mcSet = new LinkedHashSet<>();
                for (ModrinthApi.Version v : allVersions) {
                    if (v.gameVersions != null) mcSet.addAll(v.gameVersions);
                }
                List<String> mcList = new ArrayList<>(mcSet);
                mcList.sort(ResourceDownloadPage::compareVersion);
                java.util.Collections.reverse(mcList);
                loading = true;
                mcBox.removeAllItems();
                for (String mc : mcList) mcBox.addItem(mc);
                loading = false;
                populateVersions();
                downloadBtn.setEnabled(!allVersions.isEmpty());
            });
        });
    }

    private void populateVersions() {
        String mc = (String) mcBox.getSelectedItem();
        List<ModrinthApi.Version> filtered = new ArrayList<>();
        for (ModrinthApi.Version v : allVersions) {
            if (mc == null || (v.gameVersions != null && v.gameVersions.contains(mc))) {
                filtered.add(v);
            }
        }
        loading = true;
        versionBox.removeAllItems();
        for (ModrinthApi.Version v : filtered) versionBox.addItem(v);
        loading = false;
        status.setText("共 " + filtered.size() + " 个匹配版本");
    }

    private void appendLog(String line) {
        Ui.onEdt(() -> {
            logArea.append(line + "\n");
            logArea.setCaretPosition(logArea.getDocument().getLength());
        });
    }

    private void download() {
        if (busy) return;
        ModrinthApi.Version v = (ModrinthApi.Version) versionBox.getSelectedItem();
        if (v == null) {
            JOptionPane.showMessageDialog(this, "请先选择资源版本", "提示", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        ModrinthApi.ModFile file = primaryFile(v);
        if (file == null) {
            JOptionPane.showMessageDialog(this, "该版本没有可下载的文件", "提示", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        busy = true;
        downloadBtn.setEnabled(false);
        progressBar.setIndeterminate(true);
        appendLog("开始下载：" + v + " -> " + file.filename);

        Ui.runAsync(this, () -> {
            try {
                VersionInstallerProgress prog = new VersionInstallerProgress();
                if ("modpack".equals(type)) {
                    Path tmp = Files.createTempFile("modpack", ".mrpack");
                    mclauncher.net.Http.download(file.url, tmp, (d, t) -> prog.update("下载整合包", d, t), file.size);
                    String name = ModpackInstaller.installFull(tmp, cfg, prog, this::appendLog);
                    appendLog("整合包安装完成：" + name);
                    Ui.onEdt(() -> {
                        busy = false;
                        downloadBtn.setEnabled(true);
                        progressBar.setIndeterminate(false);
                        progressBar.setValue(1000);
                        status.setText("整合包安装完成：" + name);
                        frame.refreshLaunch();
                        JOptionPane.showMessageDialog(this, "整合包已安装，可在「启动游戏」中选择：\n[整合包] " + name,
                                "完成", JOptionPane.INFORMATION_MESSAGE);
                    });
                } else {
                    Path dir = targetDir.get();
                    if (dir == null) {
                        appendLog("已取消下载（没有选择目标位置）");
                        Ui.onEdt(() -> {
                            busy = false;
                            downloadBtn.setEnabled(true);
                            progressBar.setIndeterminate(false);
                            status.setText("已取消下载");
                        });
                        return;
                    }
                    Files.createDirectories(dir);
                    Path target = dir.resolve(file.filename);
                    mclauncher.net.Http.download(file.url, target, (d, t) -> prog.update("下载", d, t), file.size);
                    appendLog("已下载到 " + target);
                    Ui.onEdt(() -> {
                        busy = false;
                        downloadBtn.setEnabled(true);
                        progressBar.setIndeterminate(false);
                        progressBar.setValue(1000);
                        status.setText("已下载到 " + dir);
                    });
                }
            } catch (Exception ex) {
                appendLog("下载失败：" + ex.getMessage());
                Ui.onEdt(() -> {
                    busy = false;
                    downloadBtn.setEnabled(true);
                    progressBar.setIndeterminate(false);
                    status.setText("下载失败：" + ex.getMessage());
                });
                throw ex;
            }
        });
    }

    private ModrinthApi.ModFile primaryFile(ModrinthApi.Version v) {
        if (v.files == null) return null;
        for (ModrinthApi.ModFile f : v.files) {
            if (f.primary && f.url != null) return f;
        }
        for (ModrinthApi.ModFile f : v.files) {
            if (f.url != null) return f;
        }
        return null;
    }

    /** 包装进度回调，用于整合包安装。 */
    private class VersionInstallerProgress implements mclauncher.minecraft.VersionInstaller.Progress {
        @Override
        public void on(String stage, long done, long total) {
            Ui.onEdt(() -> {
                String pct = total > 0 ? "  " + (done * 100 / total) + "%" : "";
                status.setText(stage + pct);
                if (total > 0) {
                    progressBar.setIndeterminate(false);
                    progressBar.setValue((int) Math.min(1000, done * 1000 / total));
                }
            });
        }

        void update(String stage, long done, long total) {
            on(stage, done, total);
        }
    }

    private static int compareVersion(String a, String b) {
        String[] pa = a.split("[.\\-]");
        String[] pb = b.split("[.\\-]");
        int n = Math.max(pa.length, pb.length);
        for (int i = 0; i < n; i++) {
            int x = i < pa.length ? num(pa[i]) : 0;
            int y = i < pb.length ? num(pb[i]) : 0;
            if (x != y) return Integer.compare(x, y);
        }
        return 0;
    }

    private static int num(String s) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
