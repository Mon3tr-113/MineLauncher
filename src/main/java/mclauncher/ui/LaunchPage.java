package mclauncher.ui;

import mclauncher.auth.Account;
import mclauncher.auth.OfflineAuth;
import mclauncher.config.Config;
import mclauncher.launch.GameLauncher;
import mclauncher.minecraft.GameVersion;
import mclauncher.minecraft.VersionManager;

import javax.swing.*;
import javax.swing.text.DefaultCaret;
import java.awt.*;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** 启动页：选择版本/账号、配置内存、启动游戏、查看日志。 */
public class LaunchPage extends JPanel {

    /** 删除版本 / 整合包时必须手动输入这两个字。 */
    private static final String CONFIRM_WORD = "确认";

    /** 一个可启动目标：普通版本或整合包实例。 */
    private static class Target {
        final String label;
        final String versionId;
        final Path gameDir;
        /** 是否为「[整合包] xxx」条目（不要用 label 判断，label 可能是用户自定义名）。 */
        final boolean isPack;
        /** 选中项的真实版本信息（原版 / 带加载器），用于「重命名」里展示提示。 */
        final VersionManager.Summary summary;

        Target(String label, String versionId, Path gameDir) {
            this(label, versionId, gameDir, false, null);
        }

        Target(String label, String versionId, Path gameDir, boolean isPack,
               VersionManager.Summary summary) {
            this.label = label;
            this.versionId = versionId;
            this.gameDir = gameDir;
            this.isPack = isPack;
            this.summary = summary;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    private final Config cfg;
    private final MainFrame frame;
    private final VersionManager vm;

    private final JComboBox<Target> versionBox = new JComboBox<>();
    private final JComboBox<Account> accountBox = new JComboBox<>();
    private final JCheckBox showParentsBox = new JCheckBox("同时列出被加载器依赖的原版版本");
    private final JTextField javaField;
    private final JSpinner memorySpinner;
    private final JButton launchButton = Ui.button("启动游戏");
    private final JButton stopButton = Ui.button("结束游戏");
    private final JTextArea logArea = new JTextArea();

    private volatile Process runningProcess;
    private final StringBuffer launchLogBuf = new StringBuffer();

    public LaunchPage(Config cfg, MainFrame frame) {
        this.cfg = cfg;
        this.frame = frame;
        this.vm = new VersionManager(cfg);
        this.javaField = new JTextField(cfg.javaPath, 20);
        this.memorySpinner = new JSpinner(new SpinnerNumberModel(cfg.maxMemoryMb, 512, 32768, 512));

        setOpaque(false);
        setLayout(new BorderLayout());
        add(buildControls(), BorderLayout.NORTH);
        add(buildLogPanel(), BorderLayout.CENTER);
    }

    private JPanel buildControls() {
        JPanel panel = Ui.glass(Ui.CARD);
        panel.setLayout(new BorderLayout(0, 10));
        panel.setBorder(BorderFactory.createEmptyBorder(16, 16, 16, 16));

        JLabel title = new JLabel("启动游戏");
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
        form.add(versionBox, g);

        g.gridx = 2; g.gridy = 0; g.weightx = 0;
        JPanel versionActions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        versionActions.setOpaque(false);
        JButton installBtn = Ui.button("安装新版本");
        installBtn.addActionListener(e -> frame.showDownloadVersions());
        versionActions.add(installBtn);
        JButton importBtn = Ui.button("导入版本文件夹");
        importBtn.setToolTipText("把已有的版本文件夹（含 <版本名>.json）复制进来，支持一次导入多个版本或整个 .minecraft/versions");
        importBtn.addActionListener(e -> importVersionFolder());
        versionActions.add(importBtn);
        JButton renameBtn = Ui.button("重命名");
        renameBtn.setToolTipText("给所选版本起个显示名（只改显示，不动文件夹名）");
        renameBtn.addActionListener(e -> renameVersion());
        versionActions.add(renameBtn);
        JButton delBtn = Ui.button("删除版本");
        delBtn.setToolTipText("删除版本文件夹（可选是否连实例目录一起删），需要手动输入「确认」");
        delBtn.addActionListener(e -> deleteVersion());
        versionActions.add(delBtn);
        form.add(versionActions, g);

        // 加载器版本已经把「游戏版本 + 加载器」合成一条了，原版父版本默认不再单列
        g.gridx = 1; g.gridy = 1; g.weightx = 1;
        g.gridwidth = 2;
        showParentsBox.setSelected(cfg.showParentVersions);
        showParentsBox.setOpaque(false);
        showParentsBox.setForeground(Ui.MUTED);
        showParentsBox.setToolTipText("开启后，被 Fabric/Forge/NeoForge 依赖的原版版本也会出现在下拉里（默认合并显示）");
        showParentsBox.addActionListener(e -> {
            cfg.showParentVersions = showParentsBox.isSelected();
            cfg.save();
            refreshVersions();
        });
        form.add(showParentsBox, g);
        g.gridwidth = 1;

        g.gridx = 0; g.gridy = 2; g.weightx = 0;
        form.add(Ui.muted("登录账号"), g);
        g.gridx = 1; g.gridy = 2; g.weightx = 1;
        form.add(accountBox, g);

        g.gridx = 2; g.gridy = 2; g.weightx = 0;
        JButton addAccount = Ui.button("添加账号");
        addAccount.addActionListener(e -> {
            Account a = Dialogs.addAccount(this, cfg);
            if (a != null) {
                cfg.accounts.add(a);
                cfg.save();
                refreshAccounts();
            }
        });
        form.add(addAccount, g);

        g.gridx = 0; g.gridy = 3; g.weightx = 0;
        form.add(Ui.muted("Java 路径"), g);
        g.gridx = 1; g.gridy = 3; g.weightx = 1;
        form.add(javaField, g);

        g.gridx = 2; g.gridy = 3; g.weightx = 0;
        JButton browseJava = Ui.button("浏览");
        browseJava.addActionListener(e -> {
            File f = Dialogs.chooseFile(this, "选择 java 可执行文件");
            if (f != null) javaField.setText(f.getAbsolutePath());
        });
        form.add(browseJava, g);

        g.gridx = 0; g.gridy = 4; g.weightx = 0;
        form.add(Ui.muted("分配内存(MB)"), g);
        g.gridx = 1; g.gridy = 4; g.weightx = 1;
        form.add(memorySpinner, g);

        g.gridx = 2; g.gridy = 4; g.weightx = 0;
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        buttons.setOpaque(false);
        launchButton.setFont(launchButton.getFont().deriveFont(Font.BOLD, 14f));
        launchButton.addActionListener(e -> launch());
        stopButton.setEnabled(false);
        stopButton.addActionListener(e -> stopGame());
        buttons.add(launchButton);
        buttons.add(stopButton);
        form.add(buttons, g);

        panel.add(form, BorderLayout.CENTER);
        return panel;
    }

    private JPanel buildLogPanel() {
        JPanel panel = new JPanel(new BorderLayout(0, 6));
        panel.setBackground(Ui.BG);
        panel.setBorder(BorderFactory.createEmptyBorder(0, 12, 12, 12));

        JPanel header = new JPanel(new BorderLayout());
        header.setOpaque(false);
        header.add(Ui.muted("游戏日志"), BorderLayout.WEST);
        JPanel actions = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.RIGHT, 8, 0));
        actions.setOpaque(false);
        JButton openMods = Ui.button("打开本版本 mods");
        openMods.setToolTipText("打开当前所选版本实际使用的 mods 目录（版本隔离时在 instances/<版本>/mods）");
        openMods.addActionListener(e -> openVersionSub("mods"));
        actions.add(openMods);
        JButton checkLibs = Ui.button("校验依赖库");
        checkLibs.setToolTipText("核对当前版本所有依赖库（含 natives 分类器库）的大小与 SHA-1，缺失/损坏的自动补下载");
        checkLibs.addActionListener(e -> checkLibraries(checkLibs));
        actions.add(checkLibs);
        JButton openDir = Ui.button("打开游戏目录");
        openDir.addActionListener(e -> {
            try {
                Target t = (Target) versionBox.getSelectedItem();
                java.nio.file.Path dir = t != null ? t.gameDir : cfg.gamePath();
                java.nio.file.Files.createDirectories(dir);
                Dialogs.openInDesktop(dir);
            } catch (Exception ex) {
                ex.printStackTrace();
            }
        });
        actions.add(openDir);
        header.add(actions, BorderLayout.EAST);
        panel.add(header, BorderLayout.NORTH);

        logArea.setEditable(false);
        logArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        logArea.setBackground(new Color(0x14151c));
        logArea.setForeground(new Color(0xd0d0d0));
        DefaultCaret caret = (DefaultCaret) logArea.getCaret();
        caret.setUpdatePolicy(DefaultCaret.ALWAYS_UPDATE);
        JScrollPane sp = new JScrollPane(logArea);
        sp.setBorder(BorderFactory.createEmptyBorder());
        panel.add(sp, BorderLayout.CENTER);
        return panel;
    }

    /** 删除版本：必须手动输入「确认」两个字才删，防止误触。 */
    private void deleteVersion() {
        Target t = (Target) versionBox.getSelectedItem();
        if (t == null) {
            JOptionPane.showMessageDialog(this, "请先在下拉框里选择一个要删除的版本", "提示",
                    JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        String id = t.versionId;
        boolean isPack = t.isPack;
        JTextField input = new JTextField(10);
        JRadioButton both = new JRadioButton("版本文件夹 + 实例目录（存档 / mods / 配置一起删）", true);
        JRadioButton folderOnly = new JRadioButton("只删版本文件夹（保留 instances/" + id + " 里的存档和 mod）");
        both.setOpaque(false);
        folderOnly.setOpaque(false);
        both.setForeground(Ui.TEXT);
        folderOnly.setForeground(Ui.TEXT);
        javax.swing.ButtonGroup group = new javax.swing.ButtonGroup();
        group.add(both);
        group.add(folderOnly);

        JPanel panel = new JPanel(new java.awt.GridBagLayout());
        panel.setOpaque(false);
        java.awt.GridBagConstraints gc = new java.awt.GridBagConstraints();
        gc.insets = new java.awt.Insets(5, 5, 5, 5);
        gc.anchor = java.awt.GridBagConstraints.WEST;
        gc.fill = java.awt.GridBagConstraints.HORIZONTAL;
        gc.gridx = 0; gc.gridy = 0; gc.weightx = 1;
        String what = isPack
                ? "整合包实例目录：" + t.gameDir
                : "versions/" + id + "<br>&nbsp;&nbsp;instances/" + id + "（含存档、mods、配置）";
        panel.add(new JLabel("<html><div style='width:360px;'>确定要删除 <b>" + id + "</b> 吗？<br>"
                + "涉及的目录：<br>&nbsp;&nbsp;" + what + "<br><br>"
                + "此操作不可恢复。请输入 <b>确认</b> 两个字以继续：</div></html>"), gc);
        gc.gridy = 1;
        panel.add(both, gc);
        gc.gridy = 2;
        if (!isPack) panel.add(folderOnly, gc);
        gc.gridy = 3;
        panel.add(input, gc);

        int r = JOptionPane.showConfirmDialog(this, panel, "删除版本",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);
        if (r != JOptionPane.OK_OPTION) return;
        if (!CONFIRM_WORD.equals(input.getText().trim())) {
            JOptionPane.showMessageDialog(this, "没有输入「" + CONFIRM_WORD + "」，已取消删除。",
                    "已取消", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        boolean deleteInstance = isPack || both.isSelected();
        try {
            if (isPack) {
                deleteTree(t.gameDir);
                cfg.modpacks.removeIf(m -> m.name != null && t.label.endsWith(m.name));
            } else {
                deleteTree(cfg.versionsDir().resolve(id));
                if (deleteInstance) deleteTree(cfg.instanceDir(id));
                cfg.modpacks.removeIf(m -> id.equals(m.versionId));
            }
            cfg.save();
            refreshVersions();
            frame.setStatus(deleteInstance ? "已删除版本 " + id + "（含实例目录）" : "已删除版本文件夹 " + id + "（实例目录已保留）");
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, "删除失败：" + ex.getMessage(), "错误",
                    JOptionPane.ERROR_MESSAGE);
        }
    }

    private static void deleteTree(java.nio.file.Path dir) throws java.io.IOException {
        if (dir == null || !java.nio.file.Files.exists(dir)) return;
        try (var walk = java.nio.file.Files.walk(dir)) {
            for (java.nio.file.Path p : walk.sorted(java.util.Comparator.reverseOrder()).toList()) {
                java.nio.file.Files.deleteIfExists(p);
            }
        }
    }

    /** 手动触发依赖库校验：检查 + 自动补下载，结果写进日志并弹窗提示。 */
    private void checkLibraries(JButton trigger) {
        Target t = (Target) versionBox.getSelectedItem();
        if (t == null) {
            JOptionPane.showMessageDialog(this, "请先选择一个版本", "提示", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        trigger.setEnabled(false);
        appendLog("[依赖库] 开始校验 " + t.versionId + " 的依赖库...");
        Ui.runAsync(this, () -> {
            String summary;
            try {
                GameVersion version = vm.load(t.versionId);
                mclauncher.minecraft.LibraryChecker.Report r =
                        mclauncher.minecraft.LibraryChecker.ensure(version, cfg, this::appendLog);
                if (r.wasComplete()) {
                    summary = "依赖库完整（共 " + r.total + " 个文件）。";
                } else if (r.isComplete()) {
                    summary = "已修复：补下载 " + r.repaired + " 个依赖库"
                            + (r.touchesNatives() ? "（其中含 natives 库）" : "") + "。";
                } else {
                    summary = "仍有 " + (r.stillBroken.size() + r.localMissing.size()) + " 个依赖库无法补齐。\n"
                            + "如果是本地产物缺失，请重新安装该版本；如果是镜像缺文件，"
                            + "可在「设置」里关闭国内镜像后重试。";
                }
            } catch (Exception e) {
                summary = "校验失败：" + e.getMessage();
            }
            final String msg = summary;
            Ui.onEdt(() -> {
                trigger.setEnabled(true);
                JOptionPane.showMessageDialog(this, msg, "依赖库校验", JOptionPane.INFORMATION_MESSAGE);
            });
        });
    }

    /**
     * 导入版本文件夹：支持
     * <ul>
     *   <li>单个版本目录（里面有 {@code <名字>.json}）；</li>
     *   <li>一个装着多个版本目录的父目录；</li>
     *   <li>整个 {@code .minecraft}（会自动找里面的 {@code versions/}）。</li>
     * </ul>
     * 只复制 {@code versions/<id>/} 里的 JSON 和 jar，缺的库由「校验依赖库」按 JSON 里的地址补。
     */
    private void importVersionFolder() {
        File dir = Dialogs.chooseDir(this, "选择要导入的版本文件夹（含 <版本名>.json 的目录）");
        if (dir == null) return;
        Path src = dir.toPath();
        List<Path> versionDirs = new ArrayList<>();
        collectVersionDirs(src, versionDirs);
        if (versionDirs.isEmpty()) {
            JOptionPane.showMessageDialog(this,
                    "这个目录里没找到版本（需要存在 <版本名>.json 文件）：\n" + src
                            + "\n\n可以选单个版本目录，或装着多个版本的父目录，或整个 .minecraft",
                    "没有可导入的版本", JOptionPane.WARNING_MESSAGE);
            return;
        }
        StringBuilder sb = new StringBuilder("将要导入 " + versionDirs.size() + " 个版本：\n\n");
        for (int i = 0; i < Math.min(12, versionDirs.size()); i++) {
            sb.append("  ").append(versionDirs.get(i).getFileName()).append('\n');
        }
        if (versionDirs.size() > 12) sb.append("  ... 另有 ").append(versionDirs.size() - 12).append(" 个\n");
        sb.append("\n导入后会自动核对依赖库（缺的会自动下载）。继续？");
        if (JOptionPane.showConfirmDialog(this, sb.toString(), "导入版本文件夹",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE) != JOptionPane.OK_OPTION) {
            return;
        }
        Ui.runAsync(this, () -> {
            int ok = 0;
            List<String> failed = new ArrayList<>();
            List<String> imported = new ArrayList<>();
            for (Path vd : versionDirs) {
                String id = vd.getFileName().toString();
                try {
                    Path dst = cfg.versionsDir().resolve(id);
                    Files.createDirectories(dst);
                    Files.walk(vd).forEach(p -> {
                        try {
                            Path out = dst.resolve(vd.relativize(p).toString());
                            if (Files.isDirectory(p)) {
                                Files.createDirectories(out);
                            } else {
                                Files.createDirectories(out.getParent());
                                Files.copy(p, out, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                            }
                        } catch (Exception ex) {
                            throw new RuntimeException(ex);
                        }
                    });
                    ok++;
                    imported.add(id);
                } catch (Exception e) {
                    failed.add(id + "：" + e.getMessage());
                }
            }
            // 导入完顺手核一遍依赖库/版本 jar，缺什么补什么
            for (String id : imported) {
                try {
                    mclauncher.minecraft.LibraryChecker.verifyAfterInstall(id, cfg, line -> appendLog(line));
                } catch (Exception ignored) {
                }
            }
            final int okN = ok;
            Ui.onEdt(() -> {
                refreshVersions();
                frame.refreshLaunch();
                StringBuilder msg = new StringBuilder("已导入 " + okN + " 个版本");
                if (!failed.isEmpty()) {
                    msg.append("，失败 ").append(failed.size()).append(" 个：\n");
                    for (String f : failed) msg.append("  ").append(f).append('\n');
                }
                msg.append("\n\n可在下拉框里选择并启动；若缺依赖库，启动前会自动补。");
                JOptionPane.showMessageDialog(this, msg.toString(), "导入完成",
                        JOptionPane.INFORMATION_MESSAGE);
            });
        });
    }

    /** 递归找出「含 &lt;名字&gt;.json 的目录」；遇到 .minecraft 先看 versions/。 */
    private static void collectVersionDirs(Path dir, List<Path> out) {
        if (!Files.isDirectory(dir)) return;
        Path versionsSub = dir.resolve("versions");
        if (Files.isDirectory(versionsSub)) {
            collectVersionDirs(versionsSub, out);
            return;
        }
        String name = dir.getFileName() == null ? "" : dir.getFileName().toString();
        if (!name.isBlank() && Files.isRegularFile(dir.resolve(name + ".json"))) {
            out.add(dir);
            return;
        }
        // 父目录：看一级子目录
        try (var list = Files.list(dir)) {
            for (Path child : list.toList()) {
                if (!Files.isDirectory(child)) continue;
                String cn = child.getFileName().toString();
                if (Files.isRegularFile(child.resolve(cn + ".json"))) out.add(child);
            }
        } catch (Exception ignored) {
        }
    }

    /** 打开当前所选版本的子目录（mods 等）；版本隔离时指向该版本的实例目录。 */
    private void openVersionSub(String sub) {
        try {
            Target t = (Target) versionBox.getSelectedItem();
            java.nio.file.Path dir = (t != null ? t.gameDir : cfg.gamePath()).resolve(sub);
            java.nio.file.Files.createDirectories(dir);
            Dialogs.openInDesktop(dir);
        } catch (Exception ex) {
            ex.printStackTrace();
        }
    }

    public void refreshVersions() {
        Object prev = versionBox.getSelectedItem();
        String prevId = prev instanceof Target t ? t.versionId : null;
        versionBox.removeAllItems();
        List<VersionManager.Summary> all = vm.listSummaries();

        // 被某个加载器版本当作父版本的原版，默认不重复显示（下拉里只留「游戏版本 + 加载器」）
        java.util.Set<String> parents = new java.util.HashSet<>();
        for (VersionManager.Summary s : all) {
            if (s.loaderBased()) parents.add(s.mcVersion);
        }
        for (VersionManager.Summary s : all) {
            boolean isVanillaParent = !s.loaderBased() && parents.contains(s.id);
            if (isVanillaParent && !cfg.showParentVersions) continue;
            String custom = cfg.versionName(s.id);
            String label = custom != null ? custom : s.autoLabel();
            if (isVanillaParent) label += "（原版，被加载器依赖）";
            Path gd = cfg.versionIsolation ? cfg.instanceDir(s.id) : cfg.gamePath();
            versionBox.addItem(new Target(label, s.id, gd, false, s));
        }
        for (Config.Modpack m : cfg.modpacks) {
            versionBox.addItem(new Target("[整合包] " + m.name, m.versionId, cfg.instanceDir(m.name), true, null));
        }
        // 刷新后尽量保持原来的选择
        if (prevId != null) {
            for (int i = 0; i < versionBox.getItemCount(); i++) {
                Target t = versionBox.getItemAt(i);
                if (prevId.equals(t.versionId)) {
                    versionBox.setSelectedIndex(i);
                    break;
                }
            }
        }
    }

    /** 给当前选中的版本起一个显示名（自定义名，留空恢复自动名）。 */
    private void renameVersion() {
        Target t = (Target) versionBox.getSelectedItem();
        if (t == null) {
            JOptionPane.showMessageDialog(this, "请先选择一个版本", "提示", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        if (t.isPack) {
            JOptionPane.showMessageDialog(this,
                    "整合包的名字就是它的实例名，暂不支持在这里改；\n可以在「下载资源 → 整合包 → 已安装整合包…」里处理。",
                    "提示", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        String auto = t.summary == null ? t.versionId : t.summary.autoLabel();
        String current = cfg.versionName(t.versionId);
        String input = JOptionPane.showInputDialog(this,
                "给「" + t.versionId + "」起个显示名：\n\n"
                        + "  自动名：" + auto + "\n"
                        + "  留空则恢复自动名\n\n"
                        + "（只改显示，不动文件夹名，不影响启动）",
                current == null ? auto : current);
        if (input == null) return;
        cfg.setVersionName(t.versionId, input);
        refreshVersions();
        frame.setStatus(input.isBlank() ? "已恢复自动名：" + auto : "已重命名为：" + input.trim());
    }

    public void refreshAccounts() {
        if (cfg.accounts.isEmpty()) {
            cfg.accounts.add(OfflineAuth.login("Steve"));
            cfg.save();
        }
        accountBox.removeAllItems();
        for (Account a : cfg.accounts) accountBox.addItem(a);
    }

    private void appendLog(String line) {
        // 记录完整启动日志，供崩溃时一并导出（限制 4MB）
        if (launchLogBuf.length() < 4_000_000) {
            launchLogBuf.append(line).append('\n');
        }
        Ui.onEdt(() -> {
            logArea.append(line + "\n");
            // 限制日志长度，避免内存膨胀
            if (logArea.getLineCount() > 20000) {
                logArea.setText("");
            }
        });
    }

    private void launch() {
        Target target = (Target) versionBox.getSelectedItem();
        Account account = (Account) accountBox.getSelectedItem();
        if (target == null) {
            JOptionPane.showMessageDialog(this, "请先安装并选择一个游戏版本", "提示", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        if (account == null) {
            JOptionPane.showMessageDialog(this, "请先添加并选择一个账号", "提示", JOptionPane.INFORMATION_MESSAGE);
            return;
        }

        cfg.maxMemoryMb = (Integer) memorySpinner.getValue();
        cfg.save();

        launchButton.setEnabled(false);
        stopButton.setEnabled(true);
        logArea.setText("");
        launchLogBuf.setLength(0);

        Ui.runAsync(this, () -> {
            GameVersion version = vm.load(target.versionId);

            // 校验依赖库：缺 natives 库直接 UnsatisfiedLinkError，jar 损坏则是莫名其妙的 ZipException
            if (cfg.verifyLibraries) {
                try {
                    mclauncher.minecraft.LibraryChecker.ensure(version, cfg, this::appendLog);
                } catch (Exception e) {
                    // 补下载失败不该阻止启动：把原因写进日志，真正崩了也有据可查
                    appendLog("[警告] 依赖库校验未通过：" + e.getMessage());
                }
            } else {
                appendLog("[依赖库] 已在设置里关闭启动前校验，跳过");
            }

            // 校验资源文件：缺文件会导致没有中文、主菜单无全景背景
            mclauncher.minecraft.AssetChecker.ensure(version, cfg, this::appendLog);

            // 刷新登录令牌（正版 / 外置登录）
            Account acc = refreshAccount(account);

            // 1) 自动识别 / 自动下载 Java
            String javaPath = mclauncher.util.JavaManager.ensure(
                    version.javaMajor, javaField.getText().trim(), cfg, this::appendLog);
            cfg.javaPath = javaPath;

            // 2) 启动
            long startTime = System.currentTimeMillis();
            Process p = GameLauncher.launch(version, acc, cfg, target.gameDir, this::appendLog);
            runningProcess = p;
            appendLog("[提示] 游戏已启动，PID=" + p.pid());
            int code = p.waitFor();
            runningProcess = null;
            appendLog("[提示] 游戏已退出，退出码 " + code);

            // 3) 崩溃自动导出错误报告
            exportCrashReport(target.gameDir, version.id, startTime, code);

            Ui.onEdt(() -> {
                launchButton.setEnabled(true);
                stopButton.setEnabled(false);
            });
        });
    }

    /** 启动前刷新登录令牌：正版用 refresh_token，外置登录用 Yggdrasil refresh。 */
    private Account refreshAccount(Account acc) {
        if (acc.isMicrosoft()) {
            Account refreshed = mclauncher.auth.MicrosoftAuth.refreshIfNeeded(acc, this::appendLog);
            if (refreshed != acc) {
                replaceAccount(acc, refreshed);
            }
            return refreshed;
        }
        if (acc.isYggdrasil()) {
            try {
                Account refreshed = mclauncher.auth.LittleSkinAuth.refresh(acc, this::appendLog);
                replaceAccount(acc, refreshed);
                return refreshed;
            } catch (Exception e) {
                appendLog("[警告] LittleSkin 令牌刷新失败，沿用旧令牌：" + e.getMessage());
            }
        }
        return acc;
    }

    private void replaceAccount(Account oldAcc, Account newAcc) {
        int idx = cfg.accounts.indexOf(oldAcc);
        if (idx >= 0) {
            cfg.accounts.set(idx, newAcc);
            cfg.save();
        }
    }

    /** 扫描游戏目录的 crash-reports，把新产生的报告复制到启动器目录并弹窗提示。 */
    private void exportCrashReport(java.nio.file.Path gameDir, String versionId, long startTime, int exitCode) {
        java.nio.file.Path crashDir = gameDir.resolve("crash-reports");
        java.util.List<java.nio.file.Path> fresh = new java.util.ArrayList<>();
        if (java.nio.file.Files.isDirectory(crashDir)) {
            try (var s = java.nio.file.Files.list(crashDir)) {
                s.filter(f -> {
                            try {
                                return f.toString().endsWith(".txt")
                                        && java.nio.file.Files.isRegularFile(f)
                                        && java.nio.file.Files.getLastModifiedTime(f).toMillis() > startTime;
                            } catch (Exception e) {
                                return false;
                            }
                        })
                        .forEach(fresh::add);
            } catch (Exception ignored) {
            }
        }
        if (fresh.isEmpty() && exitCode == 0) {
            return; // 正常退出且无崩溃报告
        }

        java.nio.file.Path exportDir = mclauncher.config.Config.launcherHome().resolve("crash-reports");
        java.nio.file.Path folder;
        try {
            long ts = System.currentTimeMillis();
            folder = exportDir.resolve(ts + "-" + versionId);
            java.nio.file.Files.createDirectories(folder);
            // 1) 复制 crash-reports 里的崩溃报告
            for (java.nio.file.Path f : fresh) {
                java.nio.file.Files.copy(f, folder.resolve(f.getFileName()),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            // 2) 写入本次完整启动日志
            java.nio.file.Files.writeString(folder.resolve("launch.log"),
                    launchLogBuf.toString(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            appendLog("[崩溃] 导出失败：" + e.getMessage());
            return;
        }

        if (fresh.isEmpty()) {
            appendLog("[崩溃] 游戏异常退出（退出码 " + exitCode + "），已导出启动日志到 " + folder);
        } else {
            appendLog("[崩溃] 已自动导出崩溃报告与启动日志到 " + folder);
        }
        final java.nio.file.Path outFolder = folder;
        Ui.onEdt(() -> {
            int r = JOptionPane.showConfirmDialog(this,
                    "游戏崩溃了，已自动导出一份完整报告（崩溃信息 + 本次启动日志）：\n" + outFolder
                            + "\n\n是否打开所在文件夹？", "游戏崩溃", JOptionPane.YES_NO_OPTION);
            if (r == JOptionPane.YES_OPTION) {
                Dialogs.openInDesktop(outFolder);
            }
        });
    }

    private void stopGame() {
        Process p = runningProcess;
        if (p != null && p.isAlive()) {
            p.destroy();
            appendLog("[提示] 正在结束游戏...");
        }
    }
}
