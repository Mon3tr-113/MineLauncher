package mclauncher.ui;

import mclauncher.config.Config;
import mclauncher.loader.LoaderInstaller;
import mclauncher.minecraft.VersionInstaller;

import javax.swing.*;
import java.awt.*;
import java.util.List;

/** 安装版本页：选完版本后跳到这页选择加载器（原版 / Fabric / Forge），再开始安装。 */
public class InstallPage extends JPanel {

    private final Config cfg;
    private final MainFrame frame;

    private final JLabel versionLabel = new JLabel("");
    private final JComboBox<String> loaderBox = new JComboBox<>(new String[]{"原版", "Fabric", "Quilt", "Forge", "NeoForge", "OptiFine"});
    private final JComboBox<Object> loaderVersionBox = new JComboBox<>();
    private final JButton installButton = Ui.button("开始安装");
    private final JButton backButton = Ui.button("返回");
    private final JProgressBar progressBar = new JProgressBar();
    private final JLabel status = Ui.muted("选择加载器后点击“开始安装”");
    private final JTextArea logArea = new JTextArea();

    private String targetVersion;
    private boolean loading = false;
    private volatile boolean installing = false;

    public InstallPage(Config cfg, MainFrame frame) {
        this.cfg = cfg;
        this.frame = frame;

        setOpaque(false);
        setLayout(new BorderLayout(0, 10));
        setBorder(BorderFactory.createEmptyBorder(16, 16, 16, 16));

        add(buildTop(), BorderLayout.NORTH);
        add(buildLog(), BorderLayout.CENTER);
        add(buildBottom(), BorderLayout.SOUTH);
    }

    private JPanel buildTop() {
        JPanel panel = Ui.glass(Ui.CARD);
        panel.setLayout(new BorderLayout(0, 10));
        panel.setBorder(BorderFactory.createEmptyBorder(14, 14, 14, 14));

        JLabel title = new JLabel("安装版本");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 18f));
        title.setForeground(Ui.TEXT);
        panel.add(title, BorderLayout.NORTH);

        JPanel form = new JPanel(new GridBagLayout());
        form.setOpaque(false);
        GridBagConstraints g = new GridBagConstraints();
        g.insets = new Insets(6, 8, 6, 8);
        g.anchor = GridBagConstraints.WEST;
        g.fill = GridBagConstraints.HORIZONTAL;

        g.gridx = 0; g.gridy = 0; g.weightx = 0;
        form.add(Ui.muted("游戏版本"), g);
        g.gridx = 1; g.gridy = 0; g.weightx = 1;
        versionLabel.setForeground(Ui.TEXT);
        versionLabel.setFont(versionLabel.getFont().deriveFont(Font.BOLD, 14f));
        form.add(versionLabel, g);

        g.gridx = 0; g.gridy = 1; g.weightx = 0;
        form.add(Ui.muted("加载器"), g);
        g.gridx = 1; g.gridy = 1; g.weightx = 1;
        form.add(loaderBox, g);

        g.gridx = 0; g.gridy = 2; g.weightx = 0;
        form.add(Ui.muted("加载器版本"), g);
        g.gridx = 1; g.gridy = 2; g.weightx = 1;
        form.add(loaderVersionBox, g);

        g.gridx = 0; g.gridy = 3; g.weightx = 1; g.gridwidth = 2;
        JPanel btns = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        btns.setOpaque(false);
        installButton.setFont(installButton.getFont().deriveFont(Font.BOLD, 14f));
        installButton.addActionListener(e -> install());
        backButton.addActionListener(e -> frame.showDownloadVersions());
        btns.add(installButton);
        btns.add(backButton);
        form.add(btns, g);

        panel.add(form, BorderLayout.CENTER);

        loaderBox.addActionListener(e -> { if (!loading) onLoaderChanged(); });
        return panel;
    }

    private JPanel buildLog() {
        JPanel panel = new JPanel(new BorderLayout(0, 6));
        panel.setOpaque(false);
        panel.add(Ui.muted("安装日志"), BorderLayout.NORTH);
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

    /** 从版本列表跳转进来，设置目标版本。 */
    public void openFor(String versionId) {
        this.targetVersion = versionId;
        versionLabel.setText(versionId);
        logArea.setText("");
        progressBar.setValue(0);
        progressBar.setIndeterminate(false);
        installing = false;
        installButton.setEnabled(true);
        status.setText("选择加载器后点击“开始安装”");
        loaderBox.setSelectedItem("原版");
        loading = true;
        loaderVersionBox.removeAllItems();
        loading = false;
    }

    private void onLoaderChanged() {
        String loader = (String) loaderBox.getSelectedItem();
        loading = true;
        loaderVersionBox.removeAllItems();
        loading = false;
        if ("原版".equals(loader)) {
            loaderVersionBox.setEnabled(false);
            status.setText("将安装原版（无加载器）");
            return;
        }
        loaderVersionBox.setEnabled(true);
        status.setText("正在获取 " + loader + " 版本列表...");
        Ui.runAsync(this, () -> {
            java.util.List<?> list = switch (loader) {
                case "Fabric" -> LoaderInstaller.fabricLoaderVersions();
                case "Quilt" -> LoaderInstaller.quiltLoaderVersions();
                case "Forge" -> LoaderInstaller.forgeVersions(targetVersion);
                case "NeoForge" -> LoaderInstaller.neoForgeVersions(targetVersion);
                case "OptiFine" -> LoaderInstaller.optifineVersions(targetVersion);
                default -> List.of();
            };
            Ui.onEdt(() -> {
                loading = true;
                loaderVersionBox.removeAllItems();
                for (Object v : list) loaderVersionBox.addItem(v);
                loading = false;
                status.setText("共 " + list.size() + " 个版本");
            });
        });
    }

    private void appendLog(String line) {
        Ui.onEdt(() -> {
            logArea.append(line + "\n");
            logArea.setCaretPosition(logArea.getDocument().getLength());
        });
    }

    private void install() {
        if (installing) return;
        String loader = (String) loaderBox.getSelectedItem();
        Object sel = loaderVersionBox.getSelectedItem();
        if (!"原版".equals(loader) && sel == null) {
            JOptionPane.showMessageDialog(this, "请先选择加载器版本", "提示", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        String selText = sel == null ? "" : sel.toString();
        installing = true;
        installButton.setEnabled(false);
        progressBar.setIndeterminate(true);
        appendLog("开始安装 " + targetVersion + (loader.equals("原版") ? "（原版）" : "（" + loader + " " + selText + "）"));

        Ui.runAsync(this, () -> {
            try {
                VersionInstaller.Progress progress = (stage, done, total) -> Ui.onEdt(() -> {
                    String pct = total > 0 ? "  " + (done * 100 / total) + "%" : "";
                    status.setText(stage + pct);
                    if (total > 0) {
                        progressBar.setIndeterminate(false);
                        progressBar.setMaximum(1000);
                        progressBar.setValue((int) Math.min(1000, done * 1000 / total));
                    }
                });
                String result;
                switch (loader) {
                    case "Fabric" -> {
                        result = LoaderInstaller.installFabric(targetVersion, (String) sel, cfg, progress);
                        appendLog("Fabric 安装完成：" + result);
                    }
                    case "Quilt" -> {
                        result = LoaderInstaller.installQuilt(targetVersion, (String) sel, cfg, progress);
                        appendLog("Quilt 安装完成：" + result);
                    }
                    case "Forge" -> {
                        result = LoaderInstaller.installForge(targetVersion, (String) sel, cfg, progress);
                        appendLog("Forge 安装完成：" + result);
                    }
                    case "NeoForge" -> {
                        result = LoaderInstaller.installNeoForge(targetVersion, (String) sel, cfg, progress);
                        appendLog("NeoForge 安装完成：" + result);
                    }
                    case "OptiFine" -> {
                        result = LoaderInstaller.installOptiFine(targetVersion, (LoaderInstaller.OptiFineVersion) sel, cfg, progress);
                        appendLog("OptiFine 已下载到 mods/：" + result + "（需 Forge 加载器生效）");
                    }
                    default -> {
                        new VersionInstaller(cfg).install(targetVersion, progress);
                        appendLog("原版安装完成：" + targetVersion);
                    }
                }
                Ui.onEdt(() -> {
                    installing = false;
                    installButton.setEnabled(true);
                    progressBar.setIndeterminate(false);
                    progressBar.setValue(1000);
                    status.setText("安装完成，可在“启动游戏”中选择");
                    frame.refreshLaunch();
                    JOptionPane.showMessageDialog(this, "安装完成，已可在“启动游戏”中启动", "完成",
                            JOptionPane.INFORMATION_MESSAGE);
                });
            } catch (Exception ex) {
                appendLog("安装失败：" + ex.getMessage());
                Ui.onEdt(() -> {
                    installing = false;
                    installButton.setEnabled(true);
                    progressBar.setIndeterminate(false);
                    status.setText("安装失败：" + ex.getMessage());
                });
                throw ex;
            }
        });
    }
}
