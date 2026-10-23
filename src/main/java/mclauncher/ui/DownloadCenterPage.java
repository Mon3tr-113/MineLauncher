package mclauncher.ui;

import mclauncher.net.DownloadManager;

import javax.swing.*;
import java.awt.*;
import java.util.List;

/** 下载中心：展示正在下载的内容、进度与网速。 */
public class DownloadCenterPage extends JPanel {

    private final DefaultListModel<DownloadManager.Task> model = new DefaultListModel<>();
    private final JList<DownloadManager.Task> list = new JList<>(model);
    private final JLabel summary = new JLabel();

    public DownloadCenterPage() {
        setOpaque(false);
        setLayout(new BorderLayout(0, 8));
        setBorder(BorderFactory.createEmptyBorder(14, 14, 14, 14));

        JLabel title = new JLabel("下载中心");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 18f));
        title.setForeground(Ui.TEXT);

        JPanel top = new JPanel(new BorderLayout());
        top.setOpaque(false);
        top.add(title, BorderLayout.WEST);
        JPanel right = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.RIGHT, 10, 0));
        right.setOpaque(false);
        summary.setForeground(Ui.MUTED);
        right.add(summary);
        JButton cancel = Ui.button("取消下载");
        cancel.setToolTipText("中止当前下载，并删除已下载的残留文件");
        cancel.addActionListener(e -> {
            if (DownloadManager.get().activeTask() == null) {
                JOptionPane.showMessageDialog(this, "当前没有正在进行的下载任务", "提示",
                        JOptionPane.INFORMATION_MESSAGE);
                return;
            }
            if (JOptionPane.showConfirmDialog(this, "确定要取消当前下载吗？\n已下载的残留文件会被删除。",
                    "取消下载", JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE)
                    != JOptionPane.OK_OPTION) {
                return;
            }
            DownloadManager.requestCancel();
            summary.setText("正在取消...");
        });
        right.add(cancel);
        top.add(right, BorderLayout.EAST);
        add(top, BorderLayout.NORTH);

        list.setBackground(Ui.opaque(Ui.CARD));
        list.setForeground(Ui.TEXT);
        list.setCellRenderer(new TaskRenderer());
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        JScrollPane sp = new JScrollPane(list);
        sp.setBorder(BorderFactory.createEmptyBorder());
        add(sp, BorderLayout.CENTER);

        Ui.muted("下载任务会在这里实时显示进度与网速").setForeground(Ui.MUTED);
        JLabel hint = Ui.muted("提示：在“下载资源”或“安装加载器”时，任务会出现在这里");
        hint.setBorder(BorderFactory.createEmptyBorder(6, 0, 0, 0));
        add(hint, BorderLayout.SOUTH);

        Timer timer = new Timer(300, e -> refresh());
        timer.start();
        refresh();
    }

    private void refresh() {
        List<DownloadManager.Task> tasks = DownloadManager.get().tasks();
        model.clear();
        for (DownloadManager.Task t : tasks) model.addElement(t);

        DownloadManager.Task active = DownloadManager.get().activeTask();
        if (active != null) {
            String speed = DownloadManager.formatSpeed(active.speed);
            String files = active.filesTotal > 0 && active.filesRemaining() > 0
                    ? "  ·  还剩 " + active.filesRemaining() + " 个文件" : "";
            summary.setText("正在下载：" + active.name + "  ·  " + speed + files);
            summary.setForeground(Ui.ACCENT);
        } else {
            summary.setText("当前没有下载任务");
            summary.setForeground(Ui.MUTED);
        }
    }

    private static class TaskRenderer extends JPanel implements ListCellRenderer<DownloadManager.Task> {
        private final JLabel title = new JLabel();
        private final JLabel detail = new JLabel();
        private final JProgressBar bar = new JProgressBar();

        TaskRenderer() {
            setLayout(new BorderLayout(6, 4));
            setBorder(BorderFactory.createEmptyBorder(8, 12, 8, 12));
            JPanel top = new JPanel(new BorderLayout());
            top.setOpaque(false);
            top.add(title, BorderLayout.CENTER);
            top.add(detail, BorderLayout.EAST);
            add(top, BorderLayout.NORTH);
            add(bar, BorderLayout.CENTER);
        }

        @Override
        public Component getListCellRendererComponent(JList<? extends DownloadManager.Task> list,
                                                      DownloadManager.Task t, int index,
                                                      boolean isSelected, boolean cellHasFocus) {
            String cur = t.current == null || t.current.isBlank() ? "" : "  <font color='#9a9ab5'>" + esc(t.current) + "</font>";
            title.setText("<html><b>" + esc(t.name) + "</b>" + cur + "</html>");
            title.setForeground(isSelected ? Color.WHITE : Ui.TEXT);

            String status;
            if (t.failed) status = "失败";
            else if (t.finished) status = "完成";
            else status = "下载中";
            String size = t.total > 0
                    ? DownloadManager.formatSize(t.done()) + " / " + DownloadManager.formatSize(t.total)
                    : DownloadManager.formatSize(t.done());
            String speed = t.isActive() ? "  " + DownloadManager.formatSpeed(t.speed) : "";
            String files = t.isActive() && t.filesTotal > 0
                    ? "  剩 " + t.filesRemaining() + "/" + t.filesTotal + " 个文件" : "";
            detail.setText("<html><font color='#9a9ab5'>" + size + speed + files + "  " + status + "</font></html>");

            if (t.failed) {
                bar.setIndeterminate(false);
                bar.setValue(0);
                bar.setForeground(new Color(0xe06c75));
            } else if (t.total > 0) {
                bar.setIndeterminate(false);
                bar.setMaximum(1000);
                bar.setValue((int) Math.min(1000, t.done() * 1000 / Math.max(1, t.total)));
                bar.setForeground(Ui.ACCENT);
            } else if (t.isActive()) {
                bar.setIndeterminate(true);
                bar.setForeground(Ui.ACCENT);
            } else {
                bar.setIndeterminate(false);
                bar.setMaximum(1000);
                bar.setValue(1000);
                bar.setForeground(new Color(0x5cb85c));
            }

            setBackground(isSelected ? Ui.CARD.brighter() : Ui.CARD);
            return this;
        }

        private static String esc(String s) {
            return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
        }
    }
}
