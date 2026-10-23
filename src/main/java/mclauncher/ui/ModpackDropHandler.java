package mclauncher.ui;

import mclauncher.modpack.ModpackFile;

import javax.swing.*;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 把外部整合包文件直接拖进启动器窗口就能安装。
 *
 * <p>挂在整个窗口上（{@code root}），AWT 会把落在没有自己拖放处理器的子组件上的拖放
 * 冒泡到最近的祖先，所以列表、面板、状态栏都算「窗口」的一部分。
 *
 * <p>支持：Modrinth {@code .mrpack}、CurseForge 导出的整合包 zip（manifest.json）、
 * 带 mods/ 的普通整合包 zip、已解压的 Prism/MultiMC/HMCL 实例目录。
 */
public final class ModpackDropHandler extends TransferHandler {

    private final MainFrame frame;

    public ModpackDropHandler(MainFrame frame) {
        this.frame = frame;
    }

    /** 把处理器挂到窗口的根面板上（可以挂多个组件，越近的优先）。 */
    public static void install(MainFrame frame, java.awt.Component... targets) {
        ModpackDropHandler handler = new ModpackDropHandler(frame);
        for (java.awt.Component c : targets) {
            if (c instanceof javax.swing.JComponent jc) {
                jc.setTransferHandler(handler);
            }
        }
    }

    @Override
    public boolean canImport(TransferSupport support) {
        return support.isDataFlavorSupported(DataFlavor.javaFileListFlavor);
    }

    @Override
    public boolean importData(TransferSupport support) {
        if (!canImport(support)) return false;
        List<File> files;
        try {
            Transferable t = support.getTransferable();
            Object data = t.getTransferData(DataFlavor.javaFileListFlavor);
            if (!(data instanceof List<?> raw)) return false;
            files = new ArrayList<>();
            for (Object o : raw) {
                if (o instanceof File f) files.add(f);
            }
        } catch (Exception e) {
            frame.setStatus("拖入的文件读取失败：" + e.getMessage());
            return false;
        }
        if (files.isEmpty()) return false;

        List<Path> recognized = new ArrayList<>();
        List<String> ignored = new ArrayList<>();
        for (File f : files) {
            Path p = f.toPath();
            if (ModpackFile.maybeModpackFile(p)) {
                recognized.add(p);
            } else {
                ignored.add(f.getName());
            }
        }
        if (recognized.isEmpty()) {
            JOptionPane.showMessageDialog(frame,
                    "拖进来的文件不是整合包：\n" + String.join("\n", ignored)
                            + "\n\n支持：.mrpack / CurseForge 整合包 zip / 带 mods 的普通 zip / 已解压的实例目录",
                    "无法安装", JOptionPane.WARNING_MESSAGE);
            return false;
        }
        if (!ignored.isEmpty()) {
            frame.setStatus("已忽略 " + ignored.size() + " 个不认识的文件");
        }
        // 一次只装一个：多个文件时提示用户，先装第一个
        if (recognized.size() > 1) {
            JOptionPane.showMessageDialog(frame,
                    "一次只能安装一个整合包，已选择拖进来的第一个：\n" + recognized.get(0).getFileName()
                            + "\n\n装完后可以把剩下的再拖一次。",
                    "提示", JOptionPane.INFORMATION_MESSAGE);
        }
        Path target = recognized.get(0);
        frame.setStatus("检测到整合包：" + target.getFileName() + "，正在打开安装页…");
        SwingUtilities.invokeLater(() -> frame.installModpackFile(target));
        return true;
    }
}
