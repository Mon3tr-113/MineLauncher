package mclauncher.net;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;

/** 基于 JDK HttpClient 的轻量网络封装。 */
public final class Http {

    public interface Progress {
        void on(long done, long total);
    }

    private static final String UA = "MineLauncher/0.1.0 (a Minecraft launcher)";

    /** 读超时：单次 socket 读等这么久没数据就抛异常（卡死保护的第一道）。 */
    private static final int READ_TIMEOUT_MS = 20000;

    /**
     * 卡死检测窗口（毫秒）与窗口内的最低速度（字节/秒）。
     * 都支持用系统属性覆盖，方便在特别慢的线路上放宽：
     * {@code -Dmclauncher.stallWindowMs=20000 -Dmclauncher.minRateBps=0}（0 = 只看「完全没数据」）
     */
    private static long stallWindowMs() {
        return Long.getLong("mclauncher.stallWindowMs", 12000L);
    }

    private static long minRateBps() {
        return Long.getLong("mclauncher.minRateBps", 1024L);
    }

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            // 强制 HTTP/1.1：镜像站的 HTTP/2 单连接多路复用会互相拖累（实测比 curl 慢 6 倍），
            // HTTP/1.1 让每个并发各自占一条连接，才吃得住并发。
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(20))
            .build();

    private Http() {
    }

    public static String getString(String url) throws Exception {
        return getString(url, new String[0]);
    }

    /** 连接/读取超时的统一重试：网络抖动很常见，重试 3 次，并把主机名带进错误信息。 */
    private static HttpResponse<String> sendText(HttpRequest req, String url) throws Exception {
        java.io.IOException last = null;
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                return CLIENT.send(req, HttpResponse.BodyHandlers.ofString());
            } catch (java.net.http.HttpTimeoutException | java.net.ConnectException e) {
                last = e;
                if (attempt == 3) break;
                Thread.sleep(500L * attempt);
            }
        }
        throw new IOException("连接超时：" + hostOf(url) + "\n地址：" + url
                + "\n请检查网络后重试；若该地址在国内不可达，可在「设置」里切换国内镜像。", last);
    }

    private static HttpResponse<byte[]> sendBytes(HttpRequest req, String url) throws Exception {
        java.io.IOException last = null;
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                return CLIENT.send(req, HttpResponse.BodyHandlers.ofByteArray());
            } catch (java.net.http.HttpTimeoutException | java.net.ConnectException e) {
                last = e;
                if (attempt == 3) break;
                Thread.sleep(500L * attempt);
            }
        }
        throw new IOException("连接超时：" + hostOf(url) + "\n地址：" + url, last);
    }

    private static String hostOf(String url) {
        try {
            String h = URI.create(safeUrl(url)).getHost();
            return h == null ? url : h;
        } catch (Exception e) {
            return url;
        }
    }

    /**
     * 把 URL 里不合法但很常见的字符编码掉。
     *
     * <p>CurseForge 上有些文件名带空格（如 {@code Display Case-forge-1.20-1.0.8.jar}），
     * 直接把这种字符串丢给 {@code URI.create} 会抛 {@code Illegal character in path}。
     */
    public static String safeUrl(String url) {
        if (url == null || url.isBlank()) return url;
        StringBuilder sb = new StringBuilder(url.length() + 16);
        for (int i = 0; i < url.length(); i++) {
            char c = url.charAt(i);
            switch (c) {
                case ' ' -> sb.append("%20");
                case '"' -> sb.append("%22");
                case '<' -> sb.append("%3C");
                case '>' -> sb.append("%3E");
                case '[' -> sb.append("%5B");
                case ']' -> sb.append("%5D");
                case '{' -> sb.append("%7B");
                case '}' -> sb.append("%7D");
                case '|' -> sb.append("%7C");
                case '\\' -> sb.append("%5C");
                case '^' -> sb.append("%5E");
                case '`' -> sb.append("%60");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }

    public static byte[] getBytes(String url) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(safeUrl(url)))
                .header("User-Agent", UA)
                .timeout(Duration.ofSeconds(60))
                .GET()
                .build();
        HttpResponse<byte[]> resp = sendBytes(req, url);
        if (resp.statusCode() >= 400) {
            throw new IOException("HTTP " + resp.statusCode() + " for " + url);
        }
        return resp.body();
    }

    public static String getString(String url, String... headers) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(safeUrl(url)))
                .header("User-Agent", UA)
                .timeout(Duration.ofSeconds(90))
                .GET();
        for (int i = 0; i + 1 < headers.length; i += 2) {
            b.header(headers[i], headers[i + 1]);
        }
        String u = url;
        HttpResponse<String> resp = sendText(b.build(), u);
        if (resp.statusCode() >= 400) {
            throw new IOException("HTTP " + resp.statusCode() + " for " + u);
        }
        return resp.body();
    }

    public static String postForm(String url, String form) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(safeUrl(url)))
                .header("User-Agent", UA)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Accept", "application/json")
                .timeout(Duration.ofSeconds(90))
                .POST(HttpRequest.BodyPublishers.ofString(form))
                .build();
        HttpResponse<String> resp = sendText(req, url);
        // OAuth 的 authorization_pending / 错误都是 4xx + JSON body，不能直接抛异常
        if (resp.statusCode() >= 500) {
            throw new IOException("HTTP " + resp.statusCode() + " for " + url);
        }
        return resp.body();
    }

    public static String postJson(String url, String json) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(safeUrl(url)))
                .header("User-Agent", UA)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .timeout(Duration.ofSeconds(90))
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();
        HttpResponse<String> resp = sendText(req, url);
        // Xbox/XSTS/LittleSkin 的错误也是 4xx + JSON body，交给调用方解析
        if (resp.statusCode() >= 500) {
            throw new IOException("HTTP " + resp.statusCode() + " for " + url);
        }
        return resp.body();
    }

    /** 流式下载到文件，带进度回调；目标不存在或大小不符才重新下载。 */
    public static void download(String url, Path target, Progress progress) throws Exception {
        download(url, target, progress, -1, null);
    }

    public static void download(String url, Path target, Progress progress, long expectedSize) throws Exception {
        download(url, target, progress, expectedSize, null);
    }

    /**
     * 流式下载到文件，并可选校验 SHA-1。
     *
     * <p>几条为了「下载不会卡在 2 KB/s 上」而加的规则：
     * <ul>
     *   <li><b>断点续传</b>：失败重试时保留 {@code .part}，用 {@code Range} 接着下，
     *       不会每次都从 0 开始（大文件/慢镜像上这是能不能下完的关键）；</li>
     *   <li><b>卡死判定</b>：一个检测窗口内平均速度低于 {@code mclauncher.minRateBps}（默认 4 KB/s）
     *       就主动断开重连 —— 镜像偶尔会「回 200 然后一滴一滴挤」，读超时抓不到这种情况，
     *       表现就是进度条停在 99% 且只有几 KB/s；</li>
     *   <li><b>换源重试</b>：重试时在镜像与官方地址之间来回切（见 {@link Mirror#alternate}）。</li>
     * </ul>
     *
     * @param expectedSize 期望字节数，&lt;=0 表示未知
     * @param expectedSha1 期望的 SHA-1（十六进制），null 表示不校验；校验不过的文件不会落到最终路径
     */
    public static void download(String url, Path target, Progress progress, long expectedSize,
                                String expectedSha1) throws Exception {
        if (url == null || url.isBlank()) {
            throw new IOException("没有可用的下载地址：" + target.getFileName());
        }
        // 只有「知道期望大小或 sha1」时才复用已有文件：
        // 没有任何期望值的调用方（安装器 jar、JRE 压缩包等）保持原来的「每次都重下」语义，
        // 免得一个被截断的旧文件被当成好的继续用。
        boolean hasExpectation = expectedSize > 0 || expectedSha1 != null;
        if (hasExpectation && Files.isRegularFile(target)) {
            long actualSize = sizeOf(target);
            boolean sizeOk = expectedSize <= 0 || actualSize == expectedSize;
            if (sizeOk && (expectedSha1 == null || hashMatches(target, expectedSha1))) {
                // 文件已经好了，顺手清掉上次中断留下的 .part
                try {
                    Files.deleteIfExists(target.resolveSibling(target.getFileName() + ".part"));
                } catch (Exception ignored) {
                }
                if (progress != null) progress.on(actualSize, actualSize);
                return;
            }
        }
        // 网络抖动很常见，单个文件失败重试；有备用地址时交替使用，避免一直撞同一个坏节点
        String primary = url;
        String alternate = Mirror.alternate(url);
        Exception last = null;
        boolean triedAlternate = false;
        for (int attempt = 1; attempt <= 4; attempt++) {
            boolean useAlternate = alternate != null && attempt % 2 == 0;
            String cur = useAlternate ? alternate : primary;
            try {
                downloadOnce(cur, target, progress, expectedSize, expectedSha1);
                return;
            } catch (Exception e) {
                last = e;
                String msg = e.getMessage() == null ? "" : e.getMessage();
                boolean clientError = msg.startsWith("HTTP 4") && !msg.startsWith("HTTP 429");
                boolean corrupt = msg.startsWith("SHA-1 校验失败");
                if (corrupt) {
                    // 内容不对：把 .part 和目标都丢掉，下次从 0 开始，别在坏数据上续传
                    try {
                        Files.deleteIfExists(target.resolveSibling(target.getFileName() + ".part"));
                    } catch (Exception ignored) {
                    }
                }
                // 4xx 不一定是「真没有这个文件」：镜像缺文件时官方地址往往有（反之亦然），
                // 所以先换一次源再说，两个源都 4xx 才放弃
                if (clientError) {
                    if (alternate != null && !triedAlternate) {
                        triedAlternate = true;
                        continue;
                    }
                    break;
                }
                if (attempt == 4) break;
                Thread.sleep(400L * attempt);
            }
        }
        throw last;
    }

    private static void downloadOnce(String url, Path target, Progress progress, long expectedSize,
                                     String expectedSha1) throws Exception {
        Files.createDirectories(target.getParent());
        Path tmp = target.resolveSibling(target.getFileName() + ".part");

        long resumeFrom = Files.isRegularFile(tmp) ? sizeOf(tmp) : 0;
        if (resumeFrom < 0) resumeFrom = 0;
        // 续传点已经超过期望大小 → 本地 .part 是别的版本留下的垃圾，重来
        if (expectedSize > 0 && resumeFrom > expectedSize) {
            Files.deleteIfExists(tmp);
            resumeFrom = 0;
        }

        // 用 HttpURLConnection 而不是 HttpClient：小文件高并发时 HttpClient 明显更慢
        // （实测 1.8 文件/秒 vs 5.3 文件/秒），且读超时天然就是卡死保护。
        java.net.HttpURLConnection conn =
                (java.net.HttpURLConnection) java.net.URI.create(safeUrl(url)).toURL().openConnection();
        conn.setConnectTimeout(15000);
        // 读超时用卡死窗口：这个时长没有新数据就当断了（配合断点续传，重连不会丢进度）。
        // 只靠 watch 线程去 disconnect() 在 HttpURLConnection 上不保证能打断阻塞的 read。
        conn.setReadTimeout((int) Math.max(1000, Math.min(Integer.MAX_VALUE, stallWindowMs())));
        conn.setInstanceFollowRedirects(true);
        conn.setRequestProperty("User-Agent", UA);
        if (resumeFrom > 0) {
            conn.setRequestProperty("Range", "bytes=" + resumeFrom + "-");
        }
        boolean append = false;
        try {
            int code = conn.getResponseCode();
            if (code == 416) {
                // 服务端认为 Range 越界：丢掉 .part 重来
                Files.deleteIfExists(tmp);
                throw new IOException("续传位置无效（HTTP 416），已重置");
            }
            if (code >= 400) {
                throw new IOException("HTTP " + code + " for " + url);
            }
            if (resumeFrom > 0 && code == 206) {
                append = true;
            } else if (resumeFrom > 0) {
                // 服务端不支持 Range，只能从头下
                resumeFrom = 0;
            }
            long total = conn.getContentLengthLong();
            long expectedTotal = expectedSize > 0 ? expectedSize : (total > 0 ? total + resumeFrom : -1);

            java.security.MessageDigest md = expectedSha1 == null ? null : newSha1();
            if (md != null && append) {
                // 续传：摘要要把已经落盘的字节一起算进去
                byte[] seed = new byte[256 * 1024];
                try (InputStream old = Files.newInputStream(tmp)) {
                    int r;
                    while ((r = old.read(seed)) > 0) md.update(seed, 0, r);
                }
            }
            long windowStartTime = System.currentTimeMillis();
            long windowStartBytes = 0;
            int slowWindows = 0;

            // 看门狗：完全没数据（服务端「挂着不回」）时主动断开。
            // 光靠读超时要等满 READ_TIMEOUT_MS，而且「回了几个字节就卡住」读超时根本抓不到。
            final java.util.concurrent.atomic.AtomicLong lastByteAt =
                    new java.util.concurrent.atomic.AtomicLong(System.currentTimeMillis());
            final java.util.concurrent.atomic.AtomicBoolean stopped =
                    new java.util.concurrent.atomic.AtomicBoolean(false);
            final java.util.concurrent.atomic.AtomicBoolean stalled =
                    new java.util.concurrent.atomic.AtomicBoolean(false);
            Thread watchdog = new Thread(() -> {
                while (!stopped.get()) {
                    try {
                        Thread.sleep(500);
                    } catch (InterruptedException e) {
                        return;
                    }
                    long idle = System.currentTimeMillis() - lastByteAt.get();
                    if (idle > stallWindowMs()) {
                        stalled.set(true);
                        try {
                            conn.disconnect();
                        } catch (Exception ignored) {
                        }
                        return;
                    }
                }
            }, "download-watchdog");
            watchdog.setDaemon(true);
            watchdog.start();

            try (InputStream in = conn.getInputStream();
                 OutputStream out = append
                         ? Files.newOutputStream(tmp, java.nio.file.StandardOpenOption.APPEND)
                         : Files.newOutputStream(tmp)) {
                byte[] buf = new byte[64 * 1024];
                long done = resumeFrom;
                int n;
                while ((n = in.read(buf)) > 0) {
                    if (DownloadManager.isCancelled()) {
                        throw new IOException("下载已取消");
                    }
                    out.write(buf, 0, n);
                    if (md != null) md.update(buf, 0, n);
                    done += n;
                    lastByteAt.set(System.currentTimeMillis());
                    if (progress != null) progress.on(done, expectedTotal);

                    // 细水长流检测：连续两个窗口平均速度都低于阈值 → 断开重连（配合续传，进度不丢）
                    long now = System.currentTimeMillis();
                    long windowMs = now - windowStartTime;
                    if (windowMs >= stallWindowMs()) {
                        long bytes = done - windowStartBytes;
                        if (bytes * 1000 / windowMs < minRateBps()) {
                            slowWindows++;
                        } else {
                            slowWindows = 0;
                        }
                        if (slowWindows >= 2) {
                            throw new IOException("下载太慢已断开重试：" + target.getFileName()
                                    + "（" + windowMs / 1000 + " 秒只收到 " + bytes + " 字节，来源 "
                                    + hostOf(url) + "）");
                        }
                        windowStartTime = now;
                        windowStartBytes = done;
                    }
                }
                if (md != null) {
                    String actual = hex(md.digest());
                    if (!actual.equalsIgnoreCase(expectedSha1)) {
                        throw new IOException("SHA-1 校验失败：" + target.getFileName()
                                + "（期望 " + expectedSha1 + "，实际 " + actual + "，来源 " + hostOf(url) + "）");
                    }
                }
                if (expectedSize > 0 && done != expectedSize) {
                    throw new IOException("文件大小不符：" + target.getFileName()
                            + "（期望 " + expectedSize + " 字节，实际 " + done + " 字节）");
                }
            } catch (IOException ioe) {
                String m = ioe.getMessage() == null ? "" : ioe.getMessage();
                boolean timedOut = stalled.get()
                        || ioe instanceof java.net.SocketTimeoutException
                        || m.contains("Read timed out")
                        || m.contains("timed out")
                        || m.contains("Connection reset");
                if (timedOut) {
                    throw new IOException("下载卡住已断开重试：" + target.getFileName()
                            + "（" + stallWindowMs() / 1000 + " 秒没有收到新数据，来源 " + hostOf(url) + "）");
                }
                throw ioe;
            } finally {
                stopped.set(true);
                watchdog.interrupt();
            }
        } finally {
            conn.disconnect();
        }
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
    }

    /** 计算文件内容的 SHA-1（十六进制小写）。 */
    public static String sha1Of(byte[] data) {
        java.security.MessageDigest md = newSha1();
        md.update(data);
        return hex(md.digest());
    }

    /** 计算文件的 SHA-1（十六进制小写）；读失败或文件不存在返回 null。 */
    public static String sha1Of(Path file) {
        try {
            if (!Files.isRegularFile(file)) return null;
            java.security.MessageDigest md = newSha1();
            try (InputStream in = Files.newInputStream(file)) {
                byte[] buf = new byte[256 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
            }
            return hex(md.digest());
        } catch (Exception e) {
            return null;
        }
    }

    /** 文件的 SHA-1 是否等于期望值（期望为 null 时直接返回 true）。 */
    public static boolean hashMatches(Path file, String expectedSha1) {
        if (expectedSha1 == null) return true;
        String actual = sha1Of(file);
        return actual != null && actual.equalsIgnoreCase(expectedSha1);
    }

    private static java.security.MessageDigest newSha1() {
        try {
            return java.security.MessageDigest.getInstance("SHA-1");
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("当前 JVM 没有 SHA-1 实现", e);
        }
    }

    private static String hex(byte[] data) {
        StringBuilder sb = new StringBuilder(data.length * 2);
        for (byte b : data) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    private static long sizeOf(Path p) {
        try {
            return Files.size(p);
        } catch (Exception e) {
            return -1;
        }
    }
}
