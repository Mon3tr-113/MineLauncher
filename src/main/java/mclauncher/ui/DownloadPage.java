package mclauncher.ui;

import mclauncher.config.Config;
import mclauncher.loader.LoaderInstaller;
import mclauncher.minecraft.VersionInstaller;
import mclauncher.minecraft.VersionManager;
import mclauncher.minecraft.VersionManifest;
import mclauncher.modpack.ModpackFile;
import mclauncher.modpack.ModpackInstaller;
import mclauncher.modrinth.ModNames;
import mclauncher.modrinth.ModrinthApi;

import javax.swing.*;
import java.awt.*;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Supplier;

/** 下载页：版本 / Mod / 光影 / 材质 / 整合包。 */
public class DownloadPage extends JPanel {

    private final Config cfg;
    private final MainFrame frame;
    private final VersionManager vm;
    private final JComboBox<String> targetVersionBox = new JComboBox<>();
    private final JTabbedPane tabs = new JTabbedPane();
    private final ModrinthTab modTab;
    private final ModrinthTab shaderTab;
    private final ModrinthTab resourceTab;
    private final ModpackTab modpackTab;

    public DownloadPage(Config cfg, MainFrame frame) {
        this.cfg = cfg;
        this.frame = frame;
        this.vm = new VersionManager(cfg);
        setOpaque(false);
        setLayout(new BorderLayout());
        add(buildTargetBar(), BorderLayout.NORTH);

        tabs.setBackground(Ui.CARD);
        tabs.setForeground(Ui.TEXT);
        tabs.addTab("版本", new VersionTab());
        modTab = new ModrinthTab(ModrinthApi.TYPE_MOD, "mods", () -> targetDirFor("mods"));
        shaderTab = new ModrinthTab(ModrinthApi.TYPE_SHADER, "shaderpacks", () -> targetDirFor("shaderpacks"));
        resourceTab = new ModrinthTab(ModrinthApi.TYPE_RESOURCEPACK, "resourcepacks", () -> targetDirFor("resourcepacks"));
        tabs.addTab("Mod", modTab);
        tabs.addTab("光影", shaderTab);
        tabs.addTab("材质", resourceTab);
        modpackTab = new ModpackTab();
        tabs.addTab("整合包", modpackTab);
        add(tabs, BorderLayout.CENTER);

        tabs.addChangeListener(e -> {
            java.awt.Component sel = tabs.getSelectedComponent();
            if (sel instanceof ModrinthTab mt) {
                mt.ensureLoaded();
            } else if (sel == modpackTab) {
                modpackTab.ensureLoaded();
                modpackTab.refreshInstalled();
            }
        });
        refreshTargetVersions();
    }

    /** 外部整合包（拖拽 / 文件选择）装进来后统一走这里：跳到整合包页并开始安装。 */
    public void installModpackFile(Path file) {
        selectTab(4);
        modpackTab.installFile(file);
    }

    /** 自检用：跳过确认对话框直接装。 */
    public void installModpackFileNoConfirm(Path file) {
        selectTab(4);
        modpackTab.installFileNoConfirm(file);
    }

    private JPanel buildTargetBar() {
        JPanel bar = Ui.glass(Ui.CARD);
        bar.setLayout(new FlowLayout(FlowLayout.LEFT, 8, 6));
        bar.add(Ui.muted("安装到版本："));
        targetVersionBox.setPreferredSize(new Dimension(220, 24));
        bar.add(targetVersionBox);
        JLabel hint = Ui.muted("下载时会弹窗选择保存位置（按版本 / 共享目录 / 自定义目录）");
        bar.add(hint);
        return bar;
    }

    /** 下载前弹窗选择目标位置：某个版本（版本隔离时）/ 共享目录 / 自定义目录。取消返回 null。 */
    private Path targetDirFor(String sub) {
        final Path[] out = {null};
        try {
            // 由后台下载线程调用，弹窗必须切回 EDT
            javax.swing.SwingUtilities.invokeAndWait(() -> out[0] = askTargetDir(sub));
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
        return out[0];
    }

    /** 可选目标：某个版本的实例目录 / 共享目录 / 自定义目录（path 为 null 表示需要再选目录）。 */
    private record TargetChoice(String label, Path path) {
        @Override
        public String toString() {
            return label;
        }
    }

    private Path askTargetDir(String sub) {
        JComboBox<TargetChoice> box = new JComboBox<>();
        if (cfg.versionIsolation) {
            for (String v : vm.listInstalled()) {
                box.addItem(new TargetChoice("版本 " + v + "  →  instances/" + v + "/" + sub,
                        cfg.instanceDir(v).resolve(sub)));
            }
        }
        box.addItem(new TargetChoice("共享目录  →  " + cfg.gamePath().resolve(sub),
                cfg.gamePath().resolve(sub)));
        box.addItem(new TargetChoice("自定义目录…", null));
        box.setSelectedIndex(0);

        JLabel pathLabel = Ui.muted("");
        Runnable syncLabel = () -> {
            Object sel = box.getSelectedItem();
            if (sel instanceof TargetChoice tc) {
                pathLabel.setText(tc.path == null ? "点击「确定」后会弹出目录选择框" : tc.path.toString());
            }
        };
        box.addActionListener(e -> syncLabel.run());
        syncLabel.run();

        JPanel panel = new JPanel(new GridBagLayout());
        panel.setOpaque(false);
        GridBagConstraints gc = new GridBagConstraints();
        gc.insets = new Insets(4, 4, 4, 4);
        gc.anchor = GridBagConstraints.WEST;
        gc.fill = GridBagConstraints.HORIZONTAL;
        gc.gridx = 0;
        gc.weightx = 1;
        gc.gridy = 0;
        panel.add(Ui.muted(cfg.versionIsolation
                ? "版本隔离已开启，请选择要把资源放进哪个版本：" : "选择下载位置："), gc);
        gc.gridy = 1;
        panel.add(box, gc);
        gc.gridy = 2;
        panel.add(pathLabel, gc);

        while (true) {
            int r = JOptionPane.showConfirmDialog(this, panel, "选择下载位置",
                    JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
            if (r != JOptionPane.OK_OPTION) return null;
            Object sel = box.getSelectedItem();
            if (!(sel instanceof TargetChoice tc)) return null;
            if (tc.path() != null) return tc.path();
            java.io.File f = Dialogs.chooseDir(this, "选择下载目录");
            if (f != null) return f.toPath();
        }
    }

    public void refreshTargetVersions() {
        String prev = (String) targetVersionBox.getSelectedItem();
        targetVersionBox.removeAllItems();
        for (String id : vm.listInstalled()) {
            targetVersionBox.addItem(id);
        }
        if (prev != null) {
            targetVersionBox.setSelectedItem(prev);
        }
    }

    public void selectTab(int index) {
        tabs.setSelectedIndex(index);
    }

    /** 刷新整合包页的「已安装」提示（安装完 / 删除登记后调用）。 */
    public void refreshModpacks() {
        if (modpackTab != null) modpackTab.refreshInstalled();
    }

    private static void setProgressBar(JProgressBar bar, long done, long total) {
        if (total <= 0) {
            bar.setIndeterminate(true);
        } else {
            bar.setIndeterminate(false);
            bar.setMaximum(1000);
            bar.setValue((int) Math.min(1000, done * 1000 / total));
        }
    }

    // ---------- 版本下载（选版本 -> 跳到安装页选加载器） ----------

    private class VersionTab extends JPanel {
        private final JTextField searchField = new JTextField();
        private final JCheckBox snapshotBox = new JCheckBox("包含快照");
        private final DefaultListModel<VersionManifest.VersionInfo> model = new DefaultListModel<>();
        private final JList<VersionManifest.VersionInfo> list = new JList<>(model);
        private final JLabel status = Ui.muted("输入版本号搜索，或点击“获取版本列表”");
        private volatile VersionManifest manifest;

        VersionTab() {
            setOpaque(false);
            setLayout(new BorderLayout(0, 8));
            setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));

            JPanel top = new JPanel(new BorderLayout(8, 0));
            top.setOpaque(false);
            top.add(searchField, BorderLayout.CENTER);
            JButton fetchBtn = Ui.button("获取版本列表");
            fetchBtn.addActionListener(e -> refresh());
            top.add(fetchBtn, BorderLayout.EAST);

            JPanel top2 = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
            top2.setOpaque(false);
            snapshotBox.setOpaque(false);
            snapshotBox.setForeground(Ui.TEXT);
            top2.add(snapshotBox);
            JButton installBtn = Ui.button("选择此版本并安装");
            installBtn.addActionListener(e -> openInstall());
            top2.add(installBtn);

            JPanel north = new JPanel(new BorderLayout(0, 6));
            north.setOpaque(false);
            north.add(top, BorderLayout.NORTH);
            north.add(top2, BorderLayout.SOUTH);
            add(north, BorderLayout.NORTH);

            searchField.addActionListener(e -> refresh());

            list.setBackground(Ui.CARD);
            list.setForeground(Ui.TEXT);
            list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
            list.addMouseListener(new java.awt.event.MouseAdapter() {
                @Override
                public void mouseClicked(java.awt.event.MouseEvent e) {
                    if (e.getClickCount() == 2) openInstall();
                }
            });
            add(Ui.transparentScroll(list), BorderLayout.CENTER);

            JPanel bottom = new JPanel(new BorderLayout(8, 0));
            bottom.setOpaque(false);
            bottom.add(status, BorderLayout.SOUTH);
            add(bottom, BorderLayout.SOUTH);
        }

        private void refresh() {
            String q = searchField.getText().trim();
            boolean snap = snapshotBox.isSelected();
            Ui.runAsync(this, () -> {
                if (manifest == null) {
                    status.setText("正在获取版本清单...");
                    manifest = VersionManifest.fetch();
                }
                List<VersionManifest.VersionInfo> result = manifest.filter(q, snap);
                Ui.onEdt(() -> {
                    model.clear();
                    for (VersionManifest.VersionInfo v : result) model.addElement(v);
                    status.setText("共 " + result.size() + " 个版本，选中后点“选择此版本并安装”");
                });
            });
        }

        private void openInstall() {
            VersionManifest.VersionInfo info = list.getSelectedValue();
            if (info == null) {
                JOptionPane.showMessageDialog(this, "请先选择一个版本", "提示", JOptionPane.INFORMATION_MESSAGE);
                return;
            }
            frame.openInstallPage(info.id);
        }
    }

    // ---------- Modrinth 资源下载（Mod / 光影 / 材质） ----------

    private class ModrinthTab extends JPanel {
        private final String type;
        private final String folderName;
        private final Supplier<Path> targetDirSupplier;
        private final JTextField searchField = new JTextField();
        private final DefaultListModel<ModrinthApi.Hit> model = new DefaultListModel<>();
        private final JList<ModrinthApi.Hit> list = new JList<>(model);
        private final JProgressBar progressBar = new JProgressBar();
        private final JLabel status = Ui.muted("搜索 Modrinth 上的资源并下载");
        private boolean loaded = false;

        ModrinthTab(String type, String folderName, Supplier<Path> targetDirSupplier) {
            this.type = type;
            this.folderName = folderName;
            this.targetDirSupplier = targetDirSupplier;
            setOpaque(false);
            setLayout(new BorderLayout(0, 8));
            setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));

            JPanel top = new JPanel(new BorderLayout(8, 0));
            top.setOpaque(false);
            top.add(searchField, BorderLayout.CENTER);
            JButton searchBtn = Ui.button("搜索");
            searchBtn.addActionListener(e -> search());
            top.add(searchBtn, BorderLayout.EAST);
            JPanel north = new JPanel(new BorderLayout(0, 6));
            north.setOpaque(false);
            north.add(top, BorderLayout.NORTH);
            JButton downloadBtn = Ui.button("选择并下载 -> " + folderName + "/");
            downloadBtn.addActionListener(e -> {
                ModrinthApi.Hit h = list.getSelectedValue();
                if (h == null) {
                    JOptionPane.showMessageDialog(this, "请先选择一项资源", "提示", JOptionPane.INFORMATION_MESSAGE);
                    return;
                }
                frame.openResourcePage(h, type, targetDirSupplier);
            });
            north.add(downloadBtn, BorderLayout.SOUTH);
            add(north, BorderLayout.NORTH);

            searchField.addActionListener(e -> search());

            list.setBackground(Ui.CARD);
            list.setForeground(Ui.TEXT);
            list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
            list.setCellRenderer(new HitRenderer());
            installWebMenu(list, model);
            add(Ui.transparentScroll(list), BorderLayout.CENTER);

            JPanel bottom = new JPanel(new BorderLayout(8, 0));
            bottom.setOpaque(false);
            bottom.add(progressBar, BorderLayout.CENTER);
            bottom.add(status, BorderLayout.SOUTH);
            add(bottom, BorderLayout.SOUTH);
        }

        /** 首次显示该标签页时自动加载“常用/热门”列表（无需搜索）。 */
        void ensureLoaded() {
            if (loaded) return;
            loaded = true;
            loadPopular();
        }

        /** 拉「按下载量排序」的常用列表（首次打开 / 清空搜索框时用）。 */
        private void loadPopular() {
            status.setText("正在加载常用资源列表...");
            Ui.runAsync(this, () -> {
                List<ModrinthApi.Hit> hits = ModrinthApi.popular(type);
                Ui.onEdt(() -> {
                    model.clear();
                    for (ModrinthApi.Hit h : hits) model.addElement(h);
                    status.setText("常用资源（按下载量排序），也可在上方搜索（支持中文，如「钠」「机械动力」）");
                });
            });
        }

        private void search() {
            String q = searchField.getText().trim();
            if (q.isBlank()) {
                loadPopular();
                return;
            }
            Ui.runAsync(this, () -> {
                status.setText("正在搜索 " + q + "...");
                List<ModrinthApi.Hit> hits = ModrinthApi.searchSmart(q, type, msg -> status.setText(msg));
                Ui.onEdt(() -> {
                    model.clear();
                    for (ModrinthApi.Hit h : hits) model.addElement(h);
                    status.setText(hits.isEmpty()
                            ? "没搜到「" + q + "」，换个关键词试试（中文名来自 MC 百科）"
                            : "命中 " + hits.size() + " 条结果");
                });
            });
        }

        private void download() {
            ModrinthApi.Hit hit = list.getSelectedValue();
            if (hit == null) {
                JOptionPane.showMessageDialog(this, "请先选择一项资源", "提示", JOptionPane.INFORMATION_MESSAGE);
                return;
            }
            progressBar.setIndeterminate(true);
            status.setText("正在获取 " + hit.title + " 的版本...");
            Ui.runAsync(this, () -> {
                List<ModrinthApi.Version> versions = ModrinthApi.versions(hit.projectId);
                ModrinthApi.ModFile file = ModrinthApi.primaryFile(versions, null, null);
                if (file == null) {
                    throw new Exception("未找到可下载的文件");
                }
                Path targetDir = targetDirSupplier.get();
                Files.createDirectories(targetDir);
                Path target = targetDir.resolve(file.filename);
                Ui.onEdt(() -> status.setText("正在下载 " + file.filename + "..."));
                mclauncher.net.Http.download(file.url, target, (done, total) -> Ui.onEdt(() -> {
                    status.setText("下载 " + file.filename + "  " + (total > 0 ? (done * 100 / total) + "%" : ""));
                    setProgressBar(progressBar, done, total);
                }), file.size);
                Ui.onEdt(() -> {
                    progressBar.setIndeterminate(false);
                    progressBar.setValue(1000);
                    status.setText("已下载到 " + targetDir);
                });
            });
        }
    }

    /** 右键菜单：跳转到该资源在 MC 百科 / CurseForge / Modrinth 的页面。 */
    private void installWebMenu(JList<ModrinthApi.Hit> list, DefaultListModel<ModrinthApi.Hit> model) {
        list.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mousePressed(java.awt.event.MouseEvent e) {
                maybePopup(e);
            }

            @Override
            public void mouseReleased(java.awt.event.MouseEvent e) {
                maybePopup(e);
            }

            private void maybePopup(java.awt.event.MouseEvent e) {
                if (!e.isPopupTrigger()) return;
                int idx = list.locationToIndex(e.getPoint());
                if (idx < 0 || idx >= model.size()) return;
                list.setSelectedIndex(idx);
                ModrinthApi.Hit h = model.get(idx);
                String name = h.title == null || h.title.isBlank() ? h.slug : h.title;

                JPopupMenu menu = new JPopupMenu();
                JMenuItem mcmod = new JMenuItem("在 MC 百科中查找");
                mcmod.addActionListener(a -> Dialogs.openUrl(
                        "https://search.mcmod.cn/s?key=" + urlEnc(name)));
                JMenuItem cf = new JMenuItem("在 CurseForge 中查找");
                cf.addActionListener(a -> Dialogs.openUrl(
                        "https://www.curseforge.com/minecraft/search?search=" + urlEnc(name)));
                JMenuItem mr = new JMenuItem("在 Modrinth 打开项目页");
                mr.addActionListener(a -> Dialogs.openUrl("https://modrinth.com/project/" + h.slug));
                menu.add(new JLabel("  " + name));
                menu.addSeparator();
                menu.add(mcmod);
                menu.add(cf);
                menu.add(mr);
                menu.show(list, e.getX(), e.getY());
            }
        });
    }

    private static String urlEnc(String s) {
        return java.net.URLEncoder.encode(s == null ? "" : s, java.nio.charset.StandardCharsets.UTF_8);
    }

    // ---------- 整合包下载安装 ----------
    private class ModpackTab extends JPanel {
        private final JTextField searchField = new JTextField();
        private final DefaultListModel<ModrinthApi.Hit> model = new DefaultListModel<>();
        private final JList<ModrinthApi.Hit> list = new JList<>(model);
        private final JProgressBar progressBar = new JProgressBar();
        private final JLabel status = Ui.muted("正在加载常用整合包...");
        private final JLabel installedLabel = Ui.muted("");
        private final JTextArea logArea = new JTextArea(6, 60);
        private boolean loaded = false;
        private volatile boolean busy = false;
        /** 本次安装开始的时间戳，用来识别「状态卡死」。 */
        private volatile long busySince = 0;

        ModpackTab() {
            setOpaque(false);
            setLayout(new BorderLayout(0, 8));
            setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));

            JPanel top = new JPanel(new BorderLayout(8, 0));
            top.setOpaque(false);
            top.add(searchField, BorderLayout.CENTER);
            JButton searchBtn = Ui.button("搜索");
            searchBtn.addActionListener(e -> search());
            top.add(searchBtn, BorderLayout.EAST);

            JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
            actions.setOpaque(false);
            JButton refreshBtn = Ui.button("刷新列表");
            refreshBtn.setToolTipText("重新拉取 Modrinth 常用整合包列表");
            refreshBtn.addActionListener(e -> {
                loaded = true;
                loadPopular();
            });
            actions.add(refreshBtn);
            JButton importBtn = Ui.button("导入整合包文件…");
            importBtn.setToolTipText(".mrpack / CurseForge 整合包 zip / 普通整合包 zip（也可以直接把文件拖进窗口）");
            importBtn.addActionListener(e -> chooseFile());
            actions.add(importBtn);
            JButton installBtn = Ui.button("选择并安装整合包");
            installBtn.addActionListener(e -> {
                ModrinthApi.Hit h = list.getSelectedValue();
                if (h == null) {
                    JOptionPane.showMessageDialog(this, "请先选择一个整合包", "提示", JOptionPane.INFORMATION_MESSAGE);
                    return;
                }
                frame.openResourcePage(h, ModrinthApi.TYPE_MODPACK, () -> cfg.instancesDir());
            });
            actions.add(installBtn);
            JButton installedBtn = Ui.button("已安装整合包…");
            installedBtn.addActionListener(e -> showInstalled());
            actions.add(installedBtn);
            actions.add(installedLabel);

            JPanel north = new JPanel(new BorderLayout(0, 6));
            north.setOpaque(false);
            north.add(top, BorderLayout.NORTH);
            north.add(actions, BorderLayout.SOUTH);
            add(north, BorderLayout.NORTH);

            searchField.addActionListener(e -> search());

            list.setBackground(Ui.CARD);
            list.setForeground(Ui.TEXT);
            list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
            list.setCellRenderer(new HitRenderer());
            installWebMenu(list, model);
            add(Ui.transparentScroll(list), BorderLayout.CENTER);

            logArea.setEditable(false);
            logArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
            logArea.setBackground(new Color(0x14151c));
            logArea.setForeground(new Color(0xd0d0d0));
            JScrollPane logScroll = new JScrollPane(logArea);
            logScroll.setPreferredSize(new Dimension(0, 130));
            logScroll.setBorder(BorderFactory.createEmptyBorder());

            JPanel bottom = new JPanel(new BorderLayout(8, 4));
            bottom.setOpaque(false);
            bottom.add(progressBar, BorderLayout.NORTH);
            bottom.add(status, BorderLayout.CENTER);
            bottom.add(logScroll, BorderLayout.SOUTH);
            add(bottom, BorderLayout.SOUTH);

            refreshInstalled();
        }

        /** 首次显示该标签页时自动拉「常用整合包」，不再是一打开就空列表。 */
        void ensureLoaded() {
            if (loaded) return;
            loaded = true;
            loadPopular();
        }

        private void loadPopular() {
            status.setText("正在加载常用整合包列表...");
            Ui.runAsync(this, () -> {
                List<ModrinthApi.Hit> hits = ModrinthApi.popular(ModrinthApi.TYPE_MODPACK);
                Ui.onEdt(() -> {
                    model.clear();
                    for (ModrinthApi.Hit h : hits) model.addElement(h);
                    status.setText(hits.isEmpty()
                            ? "没取到整合包列表（网络可能不通），可以点「刷新列表」重试，或用「导入整合包文件…」"
                            : "常用整合包（按下载量排序，共 " + hits.size() + " 个），也可在上方搜索");
                });
            });
        }

        private void search() {
            String q = searchField.getText().trim();
            if (q.isBlank()) {
                loadPopular();
                return;
            }
            Ui.runAsync(this, () -> {
                status.setText("正在搜索整合包 " + q + "...");
                List<ModrinthApi.Hit> hits = ModrinthApi.searchSmart(q, ModrinthApi.TYPE_MODPACK,
                        msg -> status.setText(msg));
                Ui.onEdt(() -> {
                    model.clear();
                    for (ModrinthApi.Hit h : hits) model.addElement(h);
                    status.setText(hits.isEmpty()
                            ? "没搜到「" + q + "」相关的整合包"
                            : "命中 " + hits.size() + " 个整合包");
                });
            });
        }

        /** 更新「已安装 N 个整合包」提示（config 里登记的和磁盘上的标记会先同步一次）。 */
        void refreshInstalled() {
            ModpackInstaller.syncFromDisk(cfg);
            int n = cfg.modpacks.size();
            installedLabel.setText(n == 0
                    ? "还没安装整合包：把 .mrpack / 整合包 zip 直接拖进窗口也行"
                    : "已装 " + n + " 个整合包");
        }

        /** 列出已安装的整合包，可跳到启动页 / 打开目录 / 删除登记。 */
        private void showInstalled() {
            refreshInstalled();
            if (cfg.modpacks.isEmpty()) {
                JOptionPane.showMessageDialog(this,
                        "还没有安装任何整合包。\n\n安装方式：\n"
                                + "  1) 在这页搜索 Modrinth 整合包 →「选择并安装整合包」\n"
                                + "  2) 点「导入整合包文件…」选 .mrpack / zip\n"
                                + "  3) 直接把整合包文件拖进启动器窗口",
                        "已安装整合包", JOptionPane.INFORMATION_MESSAGE);
                return;
            }
            DefaultListModel<Config.Modpack> lm = new DefaultListModel<>();
            for (Config.Modpack m : cfg.modpacks) lm.addElement(m);
            JList<Config.Modpack> jl = new JList<>(lm);
            jl.setVisibleRowCount(Math.min(8, Math.max(3, lm.size())));
            jl.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
            jl.setBackground(Ui.CARD);
            jl.setForeground(Ui.TEXT);
            jl.setCellRenderer(new DefaultListCellRenderer() {
                @Override
                public Component getListCellRendererComponent(JList<?> l, Object v, int i, boolean sel, boolean focus) {
                    JLabel base = (JLabel) super.getListCellRendererComponent(l, "", i, sel, focus);
                    if (v instanceof Config.Modpack m) {
                        base.setText(m.name + "   →   " + m.versionId
                                + (m.loader == null ? "" : "  [" + m.loader + "]"));
                    }
                    return base;
                }
            });
            JScrollPane sp = new JScrollPane(jl);
            sp.setPreferredSize(new Dimension(460, Math.min(220, 28 * lm.size() + 20)));

            Object[] options = {"在启动页选择", "打开目录", "补齐缺失 mod", "删除登记", "关闭"};
            int r = JOptionPane.showOptionDialog(this, sp, "已安装的整合包（" + lm.size() + " 个）",
                    JOptionPane.DEFAULT_OPTION, JOptionPane.PLAIN_MESSAGE, null, options, options[4]);
            Config.Modpack sel = jl.getSelectedValue();
            if (r == 2 && sel != null) {
                fillMods(sel);
                return;
            }
            if (r == 0) {
                frame.refreshLaunch();
                frame.showPage("launch");
            } else if (r == 1 && sel != null) {
                try {
                    Path dir = cfg.instanceDir(sel.name);
                    Files.createDirectories(dir);
                    Dialogs.openInDesktop(dir);
                } catch (Exception ex) {
                    ex.printStackTrace();
                }
            } else if (r == 3 && sel != null) {
                int c = JOptionPane.showConfirmDialog(this,
                        "只从列表里移除「" + sel.name + "」的登记，磁盘上的实例目录不会删。继续？",
                        "删除登记", JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);
                if (c == JOptionPane.OK_OPTION) {
                    cfg.modpacks.remove(sel);
                    try {
                        Files.deleteIfExists(cfg.instanceDir(sel.name).resolve(ModpackInstaller.MARKER));
                    } catch (Exception ignored) {
                    }
                    cfg.save();
                    refreshInstalled();
                    frame.refreshLaunch();
                }
            }
        }

        /** 给 CurseForge 整合包补齐缺失的 mod（按实例里的 manifest.json）。 */
        private void fillMods(Config.Modpack pack) {
            if (busy) {
                JOptionPane.showMessageDialog(this, "正在安装/下载别的整合包，稍后再试", "提示",
                        JOptionPane.INFORMATION_MESSAGE);
                return;
            }
            Path dir = cfg.instanceDir(pack.name);
            if (!Files.isRegularFile(dir.resolve("manifest.json"))) {
                JOptionPane.showMessageDialog(this,
                        "「" + pack.name + "」实例里没有 manifest.json，不是 CurseForge 整合包，无法按清单补齐。\n"
                                + "Modrinth 的 .mrpack 请直接重新导入一次（会自动补文件）。",
                        "无法补齐", JOptionPane.WARNING_MESSAGE);
                return;
            }
            busy = true;
            logArea.setText("");
            status.setText("正在按清单补齐「" + pack.name + "」的 mod...");
            progressBar.setIndeterminate(true);
            Ui.runAsync(this, () -> {
                try {
                    int n = ModpackInstaller.syncCurseForgeMods(dir, this::appendLog);
                    Ui.onEdt(() -> {
                        busy = false;
                        progressBar.setIndeterminate(false);
                        status.setText("补齐完成，mods 目录现有 " + n + " 个文件");
                        JOptionPane.showMessageDialog(this,
                                "「" + pack.name + "」的 mod 已按清单补齐，mods 目录现有 " + n + " 个文件。\n"
                                        + "个别作者关闭第三方分发的 mod 会列在日志里，需要手动下载。",
                                "补齐完成", JOptionPane.INFORMATION_MESSAGE);
                    });
                } catch (Exception ex) {
                    appendLog("[失败] " + ex.getMessage());
                    Ui.onEdt(() -> {
                        busy = false;
                        progressBar.setIndeterminate(false);
                        status.setText("补齐失败：" + ex.getMessage());
                    });
                    throw ex;
                }
            });
        }

        private void chooseFile() {
            File f = Dialogs.chooseFile(this, "选择整合包文件（.mrpack / 整合包 zip）",
                    new String[]{"mrpack", "zip", "mcpack"});
            if (f == null) {
                appendLog("[整合包] 没有选择文件");
                return;
            }
            installFile(f.toPath());
        }

        /** 供命令行自检用：跳过「确认」对话框直接装。 */
        void installFileNoConfirm(Path file) {
            ModpackFile mp = ModpackFile.detect(file);
            if (mp.kind == ModpackFile.Kind.UNKNOWN) {
                System.out.println("[packui] 无法识别：" + (mp.note == null ? file.toString() : mp.note));
                return;
            }
            startInstall(file, mp);
        }

        /** 安装一个外部整合包文件（拖拽 / 文件选择都走这里）。 */
        void installFile(Path file) {
            if (busy && busySince > 0 && System.currentTimeMillis() - busySince > 20 * 60_000L) {
                // 上一次安装被强杀/异常打断，状态卡住了：自动复位，别让导入功能彻底不可用
                busy = false;
                appendLog("[整合包] 上一次安装状态已超时，已自动复位");
            }
            if (busy) {
                JOptionPane.showMessageDialog(this,
                        "正在安装另一个整合包（进度在下方日志里）。\n"
                                + "如果上一次安装已经中断（报错/关窗口），重启启动器即可恢复。",
                        "正在安装", JOptionPane.INFORMATION_MESSAGE);
                return;
            }
            ModpackFile mp = ModpackFile.detect(file);
            if (mp.kind == ModpackFile.Kind.UNKNOWN) {
                JOptionPane.showMessageDialog(this,
                        "认不出这个文件是整合包：\n" + file + "\n\n"
                                + (mp.note == null ? "" : "原因：" + mp.note + "\n\n")
                                + "支持：\n"
                                + "  • Modrinth 的 .mrpack\n"
                                + "  • CurseForge 导出的整合包 zip（含 manifest.json）\n"
                                + "  • 带 mods/ 的普通整合包 zip\n"
                                + "  • 已解压的实例目录（Prism / MultiMC / HMCL）",
                        "无法识别", JOptionPane.WARNING_MESSAGE);
                return;
            }
            String target = cfg.instanceDir(mp.name).toString();
            StringBuilder msg = new StringBuilder();
            msg.append("检测到整合包：\n\n");
            msg.append("  名称：").append(mp.name).append('\n');
            msg.append("  类型：").append(mp.kindLabel()).append('\n');
            msg.append("  Minecraft：").append(mp.mcVersion == null ? "未识别" : mp.mcVersion).append('\n');
            msg.append("  加载器：").append(mp.loader)
                    .append(mp.loaderVersion == null ? "" : " " + mp.loaderVersion).append('\n');
            if (mp.declaredFiles > 0) msg.append("  清单文件数：").append(mp.declaredFiles).append('\n');
            if (mp.note != null) msg.append("\n注意：").append(mp.note).append('\n');
            if (!mp.launchable()) {
                msg.append("\n这个包认不出 MC / 加载器版本，装完后需要在「下载资源 → 版本」里")
                        .append("安装对应版本，再把内容挂到实例目录。\n");
            }
            msg.append("\n实例目录：").append(target).append("\n\n开始安装？");
            int r = JOptionPane.showConfirmDialog(this, msg.toString(), "安装整合包",
                    JOptionPane.OK_CANCEL_OPTION, JOptionPane.INFORMATION_MESSAGE);
            if (r != JOptionPane.OK_OPTION) {
                appendLog("[整合包] 已取消安装 " + file.getFileName());
                return;
            }
            startInstall(file, mp);
        }

        /** 真正开始装：认不出 MC/加载器时只铺文件，否则装 MC+加载器+文件并注册实例。 */
        private void startInstall(Path file, ModpackFile mp) {
            busy = true;
            busySince = System.currentTimeMillis();
            progressBar.setIndeterminate(true);
            logArea.setText("");
            appendLog("[整合包] " + mp.summary());
            status.setText("正在安装整合包 " + mp.name + "...");
            if (!mp.launchable()) {
                // 认不出 MC/加载器：只把文件铺到实例目录里，不注册成可启动实例
                Ui.runAsync(this, () -> {
                    try {
                        ModpackInstaller.install(file, cfg.instanceDir(mp.name), this::appendLog);
                        Ui.onEdt(() -> {
                            busy = false;
                            progressBar.setIndeterminate(false);
                            status.setText("已解压到 " + cfg.instanceDir(mp.name) + "（未注册为可启动实例）");
                            JOptionPane.showMessageDialog(this,
                                    "内容已解压到：\n" + cfg.instanceDir(mp.name)
                                            + "\n\n但这个包认不出 Minecraft / 加载器版本，没有注册成可启动实例。",
                                    "完成", JOptionPane.INFORMATION_MESSAGE);
                        });
                    } catch (Exception ex) {
                        appendLog("[失败] " + ex.getMessage());
                        Ui.onEdt(() -> {
                            busy = false;
                            progressBar.setIndeterminate(false);
                            status.setText("安装失败：" + ex.getMessage());
                        });
                    }
                });
                return;
            }

            Ui.runAsync(this, () -> {
                try {
                    VersionInstaller.Progress progress = (stage, done, total) -> Ui.onEdt(() -> {
                        status.setText(stage + (total > 0 ? "  " + (done * 100 / total) + "%" : ""));
                        setProgressBar(progressBar, done, total);
                    });
                    String name = ModpackInstaller.installFull(file, cfg, progress, this::appendLog);
                    Ui.onEdt(() -> {
                        busy = false;
                        progressBar.setIndeterminate(false);
                        progressBar.setValue(1000);
                        status.setText("整合包「" + name + "」安装完成，可在「启动游戏」中选择 [整合包] " + name);
                        refreshInstalled();
                        frame.refreshLaunch();
                        JOptionPane.showMessageDialog(this,
                                "整合包「" + name + "」已安装完成。\n\n"
                                        + "回到「启动游戏」，在版本下拉里选「[整合包] " + name + "」即可启动。",
                                "完成", JOptionPane.INFORMATION_MESSAGE);
                    });
                } catch (Exception ex) {
                    // 失败一定要把 busy 复位，否则之后的导入都会被"正在安装另一个整合包"挡住，
                    // 表现出来就是「导入整合包功能失效了」
                    appendLog("[失败] " + ex.getMessage());
                    Ui.onEdt(() -> {
                        busy = false;
                        progressBar.setIndeterminate(false);
                        status.setText("安装失败：" + ex.getMessage());
                    });
                    throw ex;
                }
            });
        }

        private void appendLog(String line) {
            // 自检时把界面日志同时打到控制台（--packui），否则看不到安装过程
            if (Boolean.getBoolean("mclauncher.uiEcho")) System.out.println("[ui] " + line);
            Ui.onEdt(() -> {
                logArea.append(line + "\n");
                logArea.setCaretPosition(logArea.getDocument().getLength());
            });
        }
    }

    // ---------- Modrinth 列表渲染 ----------

    /** 已经向 MC 百科发过查询的条目，避免重复刷屏。 */
    private static final java.util.Set<String> CN_REQUESTED =
            java.util.concurrent.ConcurrentHashMap.newKeySet();
    /** MC 百科在线解析出来的中文名：slug → 中文名。 */
    private static final java.util.Map<String, String> CN_RESOLVED =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** 取中文名：内置表 / 缓存 → 立刻返回；都没有就后台查 MC 百科，查到后重绘。 */
    private static String chineseFor(ModrinthApi.Hit h, java.util.function.Consumer<Void> onResolved) {
        if (h == null) return null;
        String bySlug = mclauncher.modrinth.ModNames.chinese(h.slug);
        if (bySlug != null) return bySlug;
        String key = (h.slug == null || h.slug.isBlank()) ? h.title : h.slug;
        String resolved = CN_RESOLVED.get(key);
        if (resolved != null) return resolved;
        // 没命中就排一次 MC 百科查询（用英文标题查最准）
        String lookupKey = h.title != null && !h.title.isBlank() ? h.title : h.slug;
        if (lookupKey != null && CN_REQUESTED.add(lookupKey.toLowerCase(java.util.Locale.ROOT))) {
            mclauncher.modrinth.McmodApi.resolveChineseAsync(lookupKey, cn -> {
                if (cn == null) return;
                CN_RESOLVED.put(key, cn);
                if (h.slug != null) CN_RESOLVED.put(h.slug, cn);
                if (onResolved != null) onResolved.accept(null);
            });
        }
        return null;
    }

    private static class HitRenderer extends DefaultListCellRenderer {
        @Override
        public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                                                      boolean isSelected, boolean cellHasFocus) {
            JLabel base = (JLabel) super.getListCellRendererComponent(list, "", index, isSelected, cellHasFocus);
            base.setIcon(null);
            if (value instanceof ModrinthApi.Hit h) {
                // 中文名来自 MC 百科（内置表 → 本地缓存 → 在线查），查不到才显示英文原名
                String cn = chineseFor(h, ignored -> Ui.onEdt(list::repaint));
                String display = cn != null ? cn : (h.title == null ? "" : h.title);
                String sub = cn != null ? " <font color='#8f8fa8'>(" + escape(h.title) + ")</font>" : "";
                String meta = h.author == null ? "" : " · " + h.author;
                if (h.downloads > 0) meta += " · " + h.downloads + " 下载";
                base.setText("<html><b>" + escape(display) + "</b>" + sub
                        + " <font color='#9a9ab5'>" + meta + "</font><br>"
                        + "<font color='#9a9ab5'>" + escape(truncate(h.description, 80)) + "</font></html>");
                ImageIcon icon = IconCache.get(h.iconUrl, () -> list.repaint());
                base.setIcon(icon);
                base.setIconTextGap(8);
                base.setVerticalTextPosition(SwingConstants.CENTER);
                base.setVerticalAlignment(SwingConstants.CENTER);
            }
            base.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));
            return base;
        }

        private static String escape(String s) {
            if (s == null) return "";
            return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
        }

        private static String truncate(String s, int n) {
            if (s == null) return "";
            return s.length() <= n ? s : s.substring(0, n) + "...";
        }
    }
}
