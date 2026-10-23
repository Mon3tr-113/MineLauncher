package mclauncher.ui;

import mclauncher.net.Http;

import javax.swing.ImageIcon;
import java.awt.Image;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Modrinth 图标异步加载与缓存。 */
public final class IconCache {

    private static final Map<String, ImageIcon> CACHE = new ConcurrentHashMap<>();
    private static final Set<String> LOADING = ConcurrentHashMap.newKeySet();
    private static final ExecutorService POOL = Executors.newFixedThreadPool(4, r -> {
        Thread t = new Thread(r, "icon-loader");
        t.setDaemon(true);
        return t;
    });

    private IconCache() {
    }

    /** 取图标：缓存命中直接返回；否则后台加载并在完成后回调（需在 EDT 里 repaint）。 */
    public static ImageIcon get(String url, Runnable onLoaded) {
        if (url == null || url.isBlank()) return null;
        ImageIcon icon = CACHE.get(url);
        if (icon != null) return icon;
        if (LOADING.add(url)) {
            POOL.submit(() -> {
                try {
                    byte[] bytes = Http.getBytes(url);
                    Image img = new ImageIcon(bytes).getImage()
                            .getScaledInstance(32, 32, Image.SCALE_SMOOTH);
                    CACHE.put(url, new ImageIcon(img));
                    if (onLoaded != null) onLoaded.run();
                } catch (Exception ignored) {
                    // 图标加载失败：不阻塞列表，下次再试
                } finally {
                    LOADING.remove(url);
                }
            });
        }
        return null;
    }
}
