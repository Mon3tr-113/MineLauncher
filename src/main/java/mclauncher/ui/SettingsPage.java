package mclauncher.ui;

import mclauncher.config.Config;
import mclauncher.util.JavaManager;

import javax.swing.*;
import java.awt.*;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

/** 设置页：游戏目录、Java、内存、镜像、线程、Java 列表等。 */
public class SettingsPage extends JPanel {

    private final Config cfg;
    private final MainFrame frame;

    private final JTextField gameDirField;
    private final JTextField javaField;
    private final JSpinner memorySpinner;
    private final JSpinner threadsSpinner;
    private final JCheckBox mirrorBox;
    private final JCheckBox isolationBox;
    private final JCheckBox verifyLibsBox;
    private final JComboBox<String> langBox;

    private final DefaultListModel<String> javaModel = new DefaultListModel<>();
    private final JList<String> javaList = new JList<>(javaModel);
    private final List<JavaManager.JavaInfo> scanned = new ArrayList<>();

    public SettingsPage(Config cfg, MainFrame frame) {
        this.cfg = cfg;
        this.frame = frame;
        this.gameDirField = new JTextField(cfg.gameDir);
        this.javaField = new JTextField(cfg.javaPath);
        this.memorySpinner = new JSpinner(new SpinnerNumberModel(cfg.maxMemoryMb, 512, 32768, 512));
        this.threadsSpinner = new JSpinner(new SpinnerNumberModel(cfg.downloadThreads, 1, 32, 1));
        this.mirrorBox = new JCheckBox("使用国内镜像（BMCLAPI）加速下载", cfg.useMirror);
        this.isolationBox = new JCheckBox("版本隔离（每个版本独立的 mods/存档/配置目录）", cfg.versionIsolation);
        this.verifyLibsBox = new JCheckBox("启动前校验依赖库完整性（大小 + SHA-1，缺失/损坏自动补下载）", cfg.verifyLibraries);
        this.langBox = new JComboBox<>(new String[]{"简体中文（默认）", "English (US)", "跟随游戏（不修改）"});
        this.langBox.setSelectedIndex(langIndex(cfg.gameLang));

        setOpaque(false);
        setLayout(new BorderLayout());
        add(buildPanel(), BorderLayout.NORTH);
        scanJava();
    }

    private JPanel buildPanel() {
        JPanel panel = Ui.glass(Ui.CARD);
        panel.setLayout(new BorderLayout(0, 14));
        panel.setBorder(BorderFactory.createEmptyBorder(18, 18, 18, 18));

        JLabel title = new JLabel("设置");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 18f));
        title.setForeground(Ui.TEXT);
        panel.add(title, BorderLayout.NORTH);

        JPanel center = new JPanel();
        center.setOpaque(false);
        center.setLayout(new BoxLayout(center, BoxLayout.Y_AXIS));
        center.add(buildForm());
        center.add(Box.createVerticalStrut(10));
        center.add(buildJavaSection());
        panel.add(center, BorderLayout.CENTER);

        JPanel bottom = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        bottom.setOpaque(false);
        JButton saveBtn = Ui.button("保存设置");
        saveBtn.addActionListener(e -> save());
        bottom.add(saveBtn);
        addFolderButton(bottom, "打开 mods（全局）", () -> cfg.modsDir());
        addFolderButton(bottom, "打开光影", () -> cfg.shaderpacksDir());
        addFolderButton(bottom, "打开材质", () -> cfg.resourcepacksDir());
        addFolderButton(bottom, "打开整合包", () -> cfg.instancesDir());

        panel.add(bottom, BorderLayout.SOUTH);
        return panel;
    }

    private JPanel buildForm() {
        JPanel form = new JPanel(new GridBagLayout());
        form.setOpaque(false);
        GridBagConstraints g = new GridBagConstraints();
        g.insets = new Insets(6, 8, 6, 8);
        g.anchor = GridBagConstraints.WEST;
        g.fill = GridBagConstraints.HORIZONTAL;

        g.gridx = 0; g.gridy = 0; g.weightx = 0;
        form.add(Ui.muted("游戏目录"), g);
        g.gridx = 1; g.gridy = 0; g.weightx = 1;
        form.add(gameDirField, g);
        g.gridx = 2; g.gridy = 0; g.weightx = 0;
        JButton browseGame = Ui.button("浏览");
        browseGame.addActionListener(e -> {
            File f = Dialogs.chooseDir(this, "选择游戏目录");
            if (f != null) gameDirField.setText(f.getAbsolutePath());
        });
        form.add(browseGame, g);

        g.gridx = 0; g.gridy = 1; g.weightx = 0;
        form.add(Ui.muted("Java 路径"), g);
        g.gridx = 1; g.gridy = 1; g.weightx = 1;
        form.add(javaField, g);
        g.gridx = 2; g.gridy = 1; g.weightx = 0;
        JButton browseJava = Ui.button("浏览");
        browseJava.addActionListener(e -> {
            File f = Dialogs.chooseFile(this, "选择 java 可执行文件");
            if (f != null) javaField.setText(f.getAbsolutePath());
        });
        form.add(browseJava, g);

        g.gridx = 0; g.gridy = 2; g.weightx = 0;
        form.add(Ui.muted("分配内存(MB)"), g);
        g.gridx = 1; g.gridy = 2; g.weightx = 1;
        form.add(memorySpinner, g);

        g.gridx = 0; g.gridy = 3; g.weightx = 0;
        form.add(Ui.muted("下载线程数"), g);
        g.gridx = 1; g.gridy = 3; g.weightx = 1;
        form.add(threadsSpinner, g);

        g.gridx = 0; g.gridy = 4; g.weightx = 1; g.gridwidth = 2;
        mirrorBox.setOpaque(false);
        mirrorBox.setForeground(Ui.TEXT);
        form.add(mirrorBox, g);

        g.gridx = 0; g.gridy = 5; g.weightx = 1; g.gridwidth = 2;
        isolationBox.setOpaque(false);
        isolationBox.setForeground(Ui.TEXT);
        form.add(isolationBox, g);

        g.gridx = 0; g.gridy = 6; g.weightx = 0; g.gridwidth = 1;
        form.add(Ui.muted("游戏语言"), g);
        g.gridx = 1; g.gridy = 6; g.weightx = 1;
        form.add(langBox, g);
        g.gridx = 2; g.gridy = 6; g.weightx = 0;
        form.add(Ui.muted("启动时写入 options.txt"), g);

        g.gridx = 0; g.gridy = 7; g.weightx = 1; g.gridwidth = 2;
        verifyLibsBox.setOpaque(false);
        verifyLibsBox.setForeground(Ui.TEXT);
        verifyLibsBox.setToolTipText("缺 natives 库会导致 UnsatisfiedLinkError；jar 被截断会导致 ZipException。"
                + "关掉后启动更快，但库坏了只能在启动日志里看出来");
        form.add(verifyLibsBox, g);
        return form;
    }

    private static int langIndex(String code) {
        if (code == null) return 0;
        if ("en_us".equalsIgnoreCase(code)) return 1;
        if (mclauncher.launch.GameOptions.KEEP.equalsIgnoreCase(code)) return 2;
        return 0;
    }

    private static String langCode(int index) {
        if (index == 1) return "en_us";
        if (index == 2) return mclauncher.launch.GameOptions.KEEP;
        return "zh_cn";
    }

    private JPanel buildJavaSection() {
        JPanel p = new JPanel(new BorderLayout(0, 6));
        p.setOpaque(false);

        JPanel header = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        header.setOpaque(false);
        JLabel label = new JLabel("已导入的 Java（双击一项填入 Java 路径）");
        label.setForeground(Ui.TEXT);
        header.add(label);
        JButton scan = Ui.button("扫描导入所有 Java");
        scan.addActionListener(e -> scanJava());
        header.add(scan);
        p.add(header, BorderLayout.NORTH);

        javaList.setBackground(Ui.opaque(Ui.CARD));
        javaList.setForeground(Ui.TEXT);
        javaList.setVisibleRowCount(5);
        javaList.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent e) {
                if (e.getClickCount() == 2) {
                    int idx = javaList.getSelectedIndex();
                    if (idx >= 0 && idx < scanned.size()) {
                        javaField.setText(scanned.get(idx).path);
                    }
                }
            }
        });
        JScrollPane sp = new JScrollPane(javaList);
        sp.setPreferredSize(new Dimension(600, 110));
        p.add(sp, BorderLayout.CENTER);

        JLabel note = Ui.muted("启动时会根据版本所需的 Java 版本自动选择一个最合适的 Java。");
        p.add(note, BorderLayout.SOUTH);
        return p;
    }

    private void scanJava() {
        javaModel.clear();
        scanned.clear();
        List<JavaManager.JavaInfo> list = JavaManager.detect();
        scanned.addAll(list);
        if (list.isEmpty()) {
            javaModel.addElement("（未检测到 Java，启动时会尝试自动下载）");
        } else {
            for (JavaManager.JavaInfo j : list) {
                javaModel.addElement("Java " + j.major + "    —    " + j.path);
            }
        }
        frame.setStatus("已导入 " + list.size() + " 个 Java");
    }

    private void addFolderButton(JPanel panel, String text, java.util.function.Supplier<java.nio.file.Path> dir) {
        JButton b = Ui.button(text);
        b.addActionListener(e -> {
            try {
                java.nio.file.Path p = dir.get();
                java.nio.file.Files.createDirectories(p);
                Dialogs.openInDesktop(p);
            } catch (Exception ex) {
                ex.printStackTrace();
            }
        });
        panel.add(b);
    }

    private void save() {
        cfg.gameDir = gameDirField.getText().trim();
        cfg.javaPath = javaField.getText().trim();
        cfg.maxMemoryMb = (Integer) memorySpinner.getValue();
        cfg.downloadThreads = (Integer) threadsSpinner.getValue();
        cfg.useMirror = mirrorBox.isSelected();
        cfg.versionIsolation = isolationBox.isSelected();
        cfg.verifyLibraries = verifyLibsBox.isSelected();
        cfg.gameLang = langCode(langBox.getSelectedIndex());
        cfg.save();
        frame.setStatus("设置已保存");
        frame.refreshLaunch();
        JOptionPane.showMessageDialog(this, "设置已保存", "提示", JOptionPane.INFORMATION_MESSAGE);
    }
}
