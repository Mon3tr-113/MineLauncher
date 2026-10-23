package mclauncher.ui;

import mclauncher.auth.Account;
import mclauncher.auth.MicrosoftAuth;
import mclauncher.auth.OfflineAuth;
import mclauncher.config.Config;

import javax.swing.*;
import java.awt.*;
import java.io.File;

/** 账号相关对话框与文件选择小工具。 */
public final class Dialogs {

    private Dialogs() {
    }

    /** 弹出添加账号对话框，返回新账号或 null。 */
    public static Account addAccount(Component parent, Config config) {
        String[] options = {"离线登录", "微软正版登录", "LittleSkin 登录", "取消"};
        int choice = JOptionPane.showOptionDialog(parent,
                "选择登录方式：\n离线登录：输入任意用户名即可（仅单机）。\n微软登录：正版账号，可进入正版服务器。\n"
                        + "LittleSkin：外置登录（皮肤站），需要 LittleSkin 账号。",
                "添加账号", JOptionPane.DEFAULT_OPTION, JOptionPane.QUESTION_MESSAGE,
                null, options, options[0]);
        if (choice == 0) {
            String name = JOptionPane.showInputDialog(parent, "输入用户名：", "离线登录", JOptionPane.PLAIN_MESSAGE);
            if (name == null || name.isBlank()) return null;
            return OfflineAuth.login(name.trim());
        }
        if (choice == 1) {
            return microsoftLogin(parent);
        }
        if (choice == 2) {
            return littleskinLogin(parent);
        }
        return null;
    }

    private static Account littleskinLogin(Component parent) {
        JTextField user = new JTextField(18);
        JPasswordField pass = new JPasswordField(18);
        JPanel form = new JPanel(new GridBagLayout());
        GridBagConstraints g = new GridBagConstraints();
        g.insets = new Insets(6, 6, 6, 6);
        g.anchor = GridBagConstraints.WEST;
        g.fill = GridBagConstraints.HORIZONTAL;
        g.gridx = 0; g.gridy = 0; g.weightx = 0;
        form.add(Ui.muted("用户名/邮箱"), g);
        g.gridx = 1; g.gridy = 0; g.weightx = 1;
        form.add(user, g);
        g.gridx = 0; g.gridy = 1; g.weightx = 0;
        form.add(Ui.muted("密码"), g);
        g.gridx = 1; g.gridy = 1; g.weightx = 1;
        form.add(pass, g);

        int r = JOptionPane.showConfirmDialog(parent, form, "LittleSkin 登录",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (r != JOptionPane.OK_OPTION) return null;
        String u = user.getText().trim();
        String p = new String(pass.getPassword());
        if (u.isEmpty() || p.isEmpty()) {
            JOptionPane.showMessageDialog(parent, "用户名和密码不能为空", "提示", JOptionPane.WARNING_MESSAGE);
            return null;
        }

        JDialog dialog = new JDialog(SwingUtilities.getWindowAncestor(parent), "LittleSkin 登录", Dialog.ModalityType.APPLICATION_MODAL);
        JLabel label = new JLabel("正在登录 LittleSkin...", SwingConstants.CENTER);
        label.setForeground(Ui.TEXT);
        JPanel panel = new JPanel(new BorderLayout(12, 12));
        panel.setBackground(Ui.opaque(Ui.CARD));
        panel.setBorder(BorderFactory.createEmptyBorder(20, 20, 20, 20));
        panel.add(label, BorderLayout.CENTER);
        dialog.setContentPane(panel);
        dialog.setSize(420, 130);
        dialog.setLocationRelativeTo(parent);

        final Account[] result = new Account[1];
        Thread t = new Thread(() -> {
            try {
                result[0] = mclauncher.auth.LittleSkinAuth.login(u, p, msg -> Ui.onEdt(() -> label.setText(msg)));
            } catch (Exception ex) {
                Ui.onEdt(() -> {
                    dialog.dispose();
                    JOptionPane.showMessageDialog(parent, ex.getMessage(), "LittleSkin 登录失败", JOptionPane.ERROR_MESSAGE);
                });
                return;
            }
            Ui.onEdt(dialog::dispose);
        }, "littleskin-login");
        t.setDaemon(true);
        t.start();
        dialog.setVisible(true);
        return result[0];
    }

    /** 登录任务，供后台线程执行。 */
    private interface LoginTask {
        Account run(java.util.function.Consumer<String> status) throws Exception;
    }

    /** 带进度提示的模态框里跑一个后台登录任务，返回结果；失败时把异常抛给调用方。 */
    private static Account runWithProgress(Component parent, String title, String initial,
                                           LoginTask task) throws Exception {
        JDialog dialog = new JDialog(SwingUtilities.getWindowAncestor(parent), title, Dialog.ModalityType.APPLICATION_MODAL);
        JLabel label = new JLabel(wrap(initial), SwingConstants.CENTER);
        label.setForeground(Ui.TEXT);
        JPanel panel = new JPanel(new BorderLayout(12, 12));
        panel.setBackground(Ui.opaque(Ui.CARD));
        panel.setBorder(BorderFactory.createEmptyBorder(20, 20, 20, 20));
        panel.add(label, BorderLayout.CENTER);
        dialog.setContentPane(panel);
        dialog.setSize(500, 180);
        dialog.setLocationRelativeTo(parent);

        final Account[] result = new Account[1];
        final Exception[] error = new Exception[1];

        Thread t = new Thread(() -> {
            try {
                result[0] = task.run(msg -> Ui.onEdt(() -> label.setText(wrap(msg))));
            } catch (Exception ex) {
                error[0] = ex;
            }
            Ui.onEdt(dialog::dispose);
        }, "ms-login");
        t.setDaemon(true);
        t.start();
        dialog.setVisible(true); // 阻塞直到 dispose
        if (error[0] != null) throw error[0];
        return result[0];
    }

    private static String wrap(String msg) {
        return "<html><div style='text-align:center;'>" + msg.replace("\n", "<br>") + "</div></html>";
    }

    /**
     * 微软正版登录：先走设备代码流（体验最省事）；若该通道在当前环境不可用
     * （应用未注册在 Azure AD 租户，返回 unauthorized_client / AADSTS700016），
     * 自动降级到 login.live.com 浏览器授权码流，保证正版登录始终可用。
     */
    private static Account microsoftLogin(Component parent) {
        try {
            try {
                return runWithProgress(parent, "微软正版登录", "正在连接微软服务器...",
                        MicrosoftAuth::login);
            } catch (MicrosoftAuth.DeviceCodeUnavailable e) {
                // 设备码通道在该租户未注册，降级到浏览器授权
            } catch (java.io.IOException e) {
                // 网络层走不通（连不上 Azure）同样降级，浏览器流走的是另一套域名，往往仍可用
                if (!isNetworkError(e)) throw e;
            }
            return browserMicrosoftLogin(parent);
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(parent, ex.getMessage(), "登录失败", JOptionPane.ERROR_MESSAGE);
            return null;
        }
    }

    private static boolean isNetworkError(Throwable t) {
        while (t != null) {
            if (t instanceof java.net.ConnectException
                    || t instanceof java.net.SocketTimeoutException
                    || t instanceof java.net.UnknownHostException
                    || t instanceof java.net.NoRouteToHostException) {
                return true;
            }
            t = t.getCause();
        }
        return false;
    }

    /** 浏览器登录：打开微软登录页，用户登录后把回调链接粘回来。 */
    private static Account browserMicrosoftLogin(Component parent) throws Exception {
        // 隐式流令牌直接在链接里，不用再换票，兼容性最好；授权码流作为备选
        final String[] mode = {MicrosoftAuth.MODE_TOKEN};
        final String[] url = {MicrosoftAuth.browserAuthUrl(mode[0])};
        MicrosoftAuth.openBrowser(url[0]);

        JComboBox<String> modeBox = new JComboBox<>(new String[]{"隐式登录（推荐）", "授权码登录"});
        JTextField urlField = new JTextField(url[0], 40);
        urlField.setEditable(false);
        urlField.setCaretPosition(0);
        JButton reopen = new JButton("重新打开登录页");
        reopen.addActionListener(e -> MicrosoftAuth.openBrowser(url[0]));
        modeBox.addActionListener(e -> {
            mode[0] = modeBox.getSelectedIndex() == 0 ? MicrosoftAuth.MODE_TOKEN : MicrosoftAuth.MODE_CODE;
            url[0] = MicrosoftAuth.browserAuthUrl(mode[0]);
            urlField.setText(url[0]);
            urlField.setCaretPosition(0);
        });

        JPanel urlRow = new JPanel(new BorderLayout(6, 0));
        urlRow.setOpaque(false);
        urlRow.add(urlField, BorderLayout.CENTER);
        urlRow.add(reopen, BorderLayout.EAST);

        JTextField pasteField = new JTextField(40);
        pasteField.setToolTipText("登录后浏览器地址栏里的整条链接");

        JPanel form = new JPanel(new GridBagLayout());
        form.setOpaque(false);
        GridBagConstraints g = new GridBagConstraints();
        g.insets = new Insets(5, 5, 5, 5);
        g.anchor = GridBagConstraints.WEST;
        g.fill = GridBagConstraints.HORIZONTAL;
        g.gridx = 0;
        g.weightx = 1;

        g.gridy = 0;
        form.add(Ui.muted("登录方式（换一种会重新生成链接）："), g);
        g.gridy = 1;
        form.add(modeBox, g);
        g.gridy = 2;
        form.add(Ui.muted("已用默认浏览器打开登录页。若没有打开，请点右侧按钮或复制下面链接："), g);
        g.gridy = 3;
        form.add(urlRow, g);
        g.gridy = 4;
        form.add(Ui.muted("登录后浏览器会跳到一个空白页面，把地址栏里的整条链接复制粘贴到下面："), g);
        g.gridy = 5;
        form.add(pasteField, g);

        while (true) {
            int r = JOptionPane.showConfirmDialog(parent, form, "微软正版登录",
                    JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
            if (r != JOptionPane.OK_OPTION) {
                return null;
            }
            String pasted = pasteField.getText().trim();
            if (pasted.isEmpty()) {
                JOptionPane.showMessageDialog(parent, "请先粘贴登录后浏览器地址栏里的链接", "提示",
                        JOptionPane.WARNING_MESSAGE);
                continue;
            }
            try {
                Account acc = runWithProgress(parent, "微软正版登录", "正在完成登录...",
                        status -> MicrosoftAuth.finishBrowser(pasted, status));
                if (acc != null) return acc;
            } catch (Exception ex) {
                JOptionPane.showMessageDialog(parent, ex.getMessage(), "登录失败", JOptionPane.ERROR_MESSAGE);
            }
            MicrosoftAuth.openBrowser(url[0]); // 失败时重新打开登录页，方便再试
        }
    }

    public static File chooseDir(Component parent, String title) {
        JFileChooser chooser = new JFileChooser();
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        chooser.setDialogTitle(title);
        if (chooser.showOpenDialog(parent) == JFileChooser.APPROVE_OPTION) {
            return chooser.getSelectedFile();
        }
        return null;
    }

    public static File chooseFile(Component parent, String title) {
        return chooseFile(parent, title, null);
    }

    /** 选单个文件，可带扩展名过滤（如 "mrpack"、"zip"）；filter 为 null 时不过滤。 */
    public static File chooseFile(Component parent, String title, String[] extensions) {
        JFileChooser chooser = new JFileChooser();
        chooser.setFileSelectionMode(JFileChooser.FILES_ONLY);
        chooser.setDialogTitle(title);
        if (extensions != null && extensions.length > 0) {
            final String[] exts = extensions;
            chooser.setFileFilter(new javax.swing.filechooser.FileFilter() {
                @Override
                public boolean accept(File f) {
                    if (f.isDirectory()) return true;
                    String n = f.getName().toLowerCase(java.util.Locale.ROOT);
                    for (String e : exts) {
                        if (n.endsWith("." + e.toLowerCase(java.util.Locale.ROOT))) return true;
                    }
                    return false;
                }

                @Override
                public String getDescription() {
                    return "整合包（" + String.join("、", exts) + "）";
                }
            });
        }
        if (chooser.showOpenDialog(parent) == JFileChooser.APPROVE_OPTION) {
            return chooser.getSelectedFile();
        }
        return null;
    }

    /** 多选文件（用于歌单）。取消返回空数组。 */
    public static File[] chooseFiles(Component parent, String title) {
        JFileChooser chooser = new JFileChooser();
        chooser.setFileSelectionMode(JFileChooser.FILES_ONLY);
        chooser.setMultiSelectionEnabled(true);
        chooser.setDialogTitle(title);
        if (chooser.showOpenDialog(parent) == JFileChooser.APPROVE_OPTION) {
            File[] fs = chooser.getSelectedFiles();
            return fs == null ? new File[0] : fs;
        }
        return new File[0];
    }

    /** 用系统默认浏览器打开网址；失败时把链接显示出来让用户手动复制。 */
    public static void openUrl(String url) {
        try {
            java.awt.Desktop.getDesktop().browse(java.net.URI.create(url));
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(null, "无法自动打开浏览器，请手动访问：\n" + url,
                    "提示", JOptionPane.INFORMATION_MESSAGE);
        }
    }

    public static void openInDesktop(java.nio.file.Path path) {
        try {
            java.awt.Desktop.getDesktop().open(path.toFile());
        } catch (Exception ex) {
            ex.printStackTrace();
        }
    }
}
