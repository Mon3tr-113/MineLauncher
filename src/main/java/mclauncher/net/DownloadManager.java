package mclauncher.net;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 下载中心：统一登记正在进行的下载，供界面展示“正在下载什么、进度、网速”。
 * 下载方法为同步（在调用线程执行，批量下载内部并行），但会实时更新任务状态。
 */
public final class DownloadManager {

    public static class Task {
        public final String name;
        public volatile String current = "";
        private final AtomicLong doneBytes = new AtomicLong();
        public volatile long total = -1;
        public volatile long speed = 0;
        public volatile boolean finished = false;
        public volatile boolean failed = false;
        public volatile String error;
        /** 批量任务：总文件数 / 已完成数（用来在下载中心显示「还剩几个文件」）。 */
        public volatile int filesTotal = 0;
        public volatile int filesDone = 0;

        private volatile long lastDone;
        private volatile long lastTime;

        Task(String name) {
            this.name = name;
        }

        public long done() {
            return doneBytes.get();
        }

        public int filesRemaining() {
            return Math.max(0, filesTotal - filesDone);
        }

        void add(long delta) {
            if (delta <= 0) return;
            doneBytes.addAndGet(delta);
            long now = System.currentTimeMillis();
            long d = doneBytes.get();
            if (lastTime == 0) {
                lastTime = now;
                lastDone = d;
                return;
            }
            long dt = now - lastTime;
            if (dt >= 400) {
                speed = (d - lastDone) * 1000 / dt;
                lastDone = d;
                lastTime = now;
            }
        }

        public boolean isActive() {
            return !finished && !failed;
        }
    }

    public static class FileSpec {
        public final String url;
        public final Path target;
        public final long size;
        /** 期望的 SHA-1（十六进制），null 表示版本 JSON 没给。 */
        public final String sha1;
        /** 已存在的文件是否也要按 sha1 校验（assets 之类海量小文件关掉，避免每次启动全量哈希）。 */
        public final boolean verifyIfExists;

        public FileSpec(String url, Path target, long size) {
            this(url, target, size, null, true);
        }

        public FileSpec(String url, Path target, long size, String sha1) {
            this(url, target, size, sha1, true);
        }

        public FileSpec(String url, Path target, long size, String sha1, boolean verifyIfExists) {
            this.url = url;
            this.target = target;
            this.size = size;
            this.sha1 = sha1 == null || sha1.isBlank() ? null : sha1.trim().toLowerCase(java.util.Locale.ROOT);
            this.verifyIfExists = verifyIfExists;
        }
    }

    private static final DownloadManager INSTANCE = new DownloadManager();
    private final List<Task> tasks = new CopyOnWriteArrayList<>();

    /** 用户点了「取消下载」：置位后所有进行中的下载尽快中止。 */
    private static final java.util.concurrent.atomic.AtomicBoolean CANCELLED =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    /** 下载被用户取消。 */
    public static class CancelledException extends Exception {
        public CancelledException(String message) {
            super(message);
        }
    }

    public static void requestCancel() {
        CANCELLED.set(true);
    }

    public static void clearCancel() {
        CANCELLED.set(false);
    }

    public static boolean isCancelled() {
        return CANCELLED.get();
    }

    private DownloadManager() {
    }

    public static DownloadManager get() {
        return INSTANCE;
    }

    public List<Task> tasks() {
        return new ArrayList<>(tasks);
    }

    public boolean hasActive() {
        for (Task t : tasks) {
            if (t.isActive()) return true;
        }
        return false;
    }

    public Task activeTask() {
        for (Task t : tasks) {
            if (t.isActive()) return t;
        }
        return null;
    }

    private Task create(String name) {
        Task t = new Task(name);
        tasks.add(0, t);
        return t;
    }

    /** 单文件同步下载。 */
    public void download(String name, String url, Path target, long size) throws Exception {
        download(name, url, target, size, null);
    }

    /** 单文件同步下载，可选 SHA-1 校验。 */
    public void download(String name, String url, Path target, long size, String sha1) throws Exception {
        Task t = create(name);
        if (size >= 0) t.total = size;
        try {
            Http.download(url, target, (done, total) -> {
                t.add(done - t.done());
                if (total >= 0) t.total = total;
            }, size, sha1);
            t.finished = true;
            t.current = target.getFileName().toString();
        } catch (Exception e) {
            t.failed = true;
            t.error = e.getMessage();
            throw e;
        }
    }

    /**
     * 批量并行同步下载，聚合为一个任务；progress 收到聚合的字节进度。
     *
     * <p>已存在的文件按 size / sha1 判断能否复用：大小对、摘要对就跳过；
     * 损坏（大小不对或摘要不符）的会被重新下载，坏文件不会留在库里。
     */
    public void downloadAll(String name, List<FileSpec> files, int threads, Http.Progress progress) throws Exception {
        clearCancel();
        Task t = create(name);
        List<FileSpec> pending = new ArrayList<>();
        for (FileSpec f : files) {
            if (alreadyOk(f)) continue;
            pending.add(f);
        }
        if (pending.isEmpty()) {
            t.finished = true;
            t.total = 0;
            if (progress != null) progress.on(0, 0);
            return;
        }

        long totalBytes = 0;
        for (FileSpec f : pending) {
            if (f.size > 0) totalBytes += f.size;
        }
        t.total = totalBytes > 0 ? totalBytes : -1;
        t.filesTotal = pending.size();

        int n = Math.max(1, Math.min(threads, pending.size()));
        ExecutorService pool = Executors.newFixedThreadPool(n);
        ConcurrentLinkedQueue<Exception> errors = new ConcurrentLinkedQueue<>();
        ConcurrentLinkedQueue<FileSpec> failed = new ConcurrentLinkedQueue<>();
        java.util.Set<java.nio.file.Path> written = java.util.concurrent.ConcurrentHashMap.newKeySet();
        AtomicLong lastReport = new AtomicLong();
        // 每个文件已计入的「最高水位」：断点续传/重试时 done 会从续传点重新开始，
        // 用绝对值加会把同一批字节算两遍（曾经出现「依赖库 203%」）
        java.util.Map<java.nio.file.Path, Long> highWater = new java.util.concurrent.ConcurrentHashMap<>();

        for (FileSpec f : pending) {
            pool.submit(() -> {
                try {
                    t.current = f.target.getFileName().toString();
                    Http.download(f.url, f.target, (done, total) -> {
                        long prev = highWater.getOrDefault(f.target, 0L);
                        if (done > prev) {
                            highWater.put(f.target, done);
                            t.add(done - prev);
                        }
                        // 节流回调
                        long now = System.currentTimeMillis();
                        if (progress != null && now - lastReport.get() >= 200) {
                            lastReport.set(now);
                            progress.on(t.done(), t.total);
                        }
                    }, f.size, f.sha1);
                    written.add(f.target);
                    t.filesDone++;
                } catch (Exception e) {
                    if (isCancelled()) return;
                    failed.add(f);
                    errors.add(e);
                }
            });
        }
        pool.shutdown();
        boolean finished = false;
        while (!finished) {
            try {
                finished = pool.awaitTermination(1, TimeUnit.SECONDS);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        // 用户取消：删掉本次已下完的文件和所有 .part 残留
        if (isCancelled()) {
            cleanupCancelled(pending, written);
            t.failed = true;
            t.error = "已取消";
            clearCancel();
            if (progress != null) progress.on(0, 0);
            throw new CancelledException("下载已取消，已清理未完成的文件");
        }
        // 失败的文件再并行补一遍，避免一个文件失败就整批作废（串行重试会让尾巴慢到无法接受）
        if (!failed.isEmpty()) {
            List<FileSpec> retry = new ArrayList<>(failed);
            failed.clear();
            errors.clear();
            t.current = "重试剩余 " + retry.size() + " 个文件";
            int rn = Math.max(1, Math.min(Math.min(n, 8), retry.size()));
            ExecutorService retryPool = Executors.newFixedThreadPool(rn);
            for (FileSpec f : retry) {
                retryPool.submit(() -> {
                    try {
                        t.current = f.target.getFileName().toString();
                        // Http 内部会断点续传 + 换源重试，进度不会从头再来
                        Http.download(f.url, f.target, tracker(t, f, highWater), f.size, f.sha1);
                        t.filesDone++;
                    } catch (Exception e) {
                        failed.add(f);
                        errors.add(e);
                    }
                });
            }
            retryPool.shutdown();
            try {
                retryPool.awaitTermination(20, TimeUnit.MINUTES);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
        }
        // 还剩下的顽固文件：串行再来一轮，慢也至少能看见它在动，而不是整批挂着
        if (!failed.isEmpty()) {
            List<FileSpec> stubborn = new ArrayList<>(failed);
            failed.clear();
            errors.clear();
            int stillOk = 0;
            for (FileSpec f : stubborn) {
                if (isCancelled()) break;
                try {
                    t.current = f.target.getFileName().toString();
                    Http.download(f.url, f.target, tracker(t, f, highWater), f.size, f.sha1);
                    t.filesDone++;
                    stillOk++;
                } catch (Exception e) {
                    failed.add(f);
                    errors.add(e);
                }
            }
            if (stillOk > 0 && progress != null) progress.on(t.done(), t.total);
        }
        if (!errors.isEmpty()) {
            t.failed = true;
            t.error = errors.peek().getMessage();
            throw new java.io.IOException("下载失败：" + failed.size() + " 个文件没下完（"
                    + errors.peek().getMessage() + "）", errors.peek());
        }
        t.finished = true;
        t.current = "";
        if (progress != null) progress.on(t.done(), t.total);
    }

    /**
     * 单个文件字节进度的记账器：按「最高水位」累加，续传/重试都不会把同一批字节算两遍。
     */
    private static Http.Progress tracker(Task t, FileSpec f, java.util.Map<java.nio.file.Path, Long> highWater) {
        return (done, total) -> {
            long prev = highWater.getOrDefault(f.target, 0L);
            if (done > prev) {
                highWater.put(f.target, done);
                t.add(done - prev);
            }
        };
    }

    /** 取消后清理：删掉本轮已下载的文件、所有 .part 临时文件。 */
    private static void cleanupCancelled(List<FileSpec> pending, java.util.Set<java.nio.file.Path> written) {
        for (java.nio.file.Path p : written) {
            try {
                Files.deleteIfExists(p);
            } catch (Exception ignored) {
            }
        }
        for (FileSpec f : pending) {
            try {
                Files.deleteIfExists(f.target);
            } catch (Exception ignored) {
            }
            try {
                Files.deleteIfExists(f.target.resolveSibling(f.target.getFileName() + ".part"));
            } catch (Exception ignored) {
            }
        }
    }

    /**
     * 已存在的文件能否复用。
     * <ul>
     *   <li>有 sha1 且要求校验已存在文件 → 大小先挡一道，再比摘要；</li>
     *   <li>只知道大小 → 比大小；</li>
     *   <li>大小和摘要都没有（Fabric / NeoForge 的部分库）→ 存在且非空即复用，
     *       否则每次安装/启动都会把它们全部重下一遍。</li>
     * </ul>
     */
    private static boolean alreadyOk(FileSpec f) {
        if (!Files.isRegularFile(f.target)) return false;
        long actual = sizeOf(f.target);
        if (actual <= 0) return false;                       // 0 字节说明上次下载被打断，重下
        if (f.size > 0 && actual != f.size) return false;    // 大小不对，肯定不是同一个文件
        if (f.sha1 != null && f.verifyIfExists) return Http.hashMatches(f.target, f.sha1);
        return true;
    }

    private static long sizeOf(Path p) {
        try {
            return Files.size(p);
        } catch (Exception e) {
            return -1;
        }
    }

    public static String formatSpeed(long bytesPerSec) {
        if (bytesPerSec >= 1024 * 1024) return String.format("%.1f MB/s", bytesPerSec / 1048576.0);
        if (bytesPerSec >= 1024) return String.format("%.0f KB/s", bytesPerSec / 1024.0);
        return bytesPerSec + " B/s";
    }

    public static String formatSize(long bytes) {
        if (bytes < 0) return "?";
        if (bytes >= 1024 * 1024 * 1024) return String.format("%.2f GB", bytes / 1073741824.0);
        if (bytes >= 1024 * 1024) return String.format("%.1f MB", bytes / 1048576.0);
        if (bytes >= 1024) return String.format("%.0f KB", bytes / 1024.0);
        return bytes + " B";
    }
}
