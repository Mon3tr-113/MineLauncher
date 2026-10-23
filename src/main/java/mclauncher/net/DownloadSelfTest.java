package mclauncher.net;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 下载层的无网络自检（对应 {@code --dltest}）：起一个本地 HTTP 服务，验证
 * <ol>
 *   <li>正常下载 + SHA-1 校验；</li>
 *   <li><b>断点续传</b>：预置半个 {@code .part} 后能从断点接着下（服务端会回 206），
 *       且续传后的整体 SHA-1 仍然正确（摘要要把已落盘部分算进去）；
 *       这条正是「下载停在 99%、只有 2 KB/s」那类问题的兜底；</li>
 *   <li>卡死检测：服务端「回 200 再一滴一滴挤」时能主动断开重试，而不是永远挂着；</li>
 *   <li>5xx / 大小不符的报错。</li>
 * </ol>
 */
public final class DownloadSelfTest {

    private static int failures;
    private static final AtomicLong rangeHits = new AtomicLong();
    private static final AtomicInteger attempts = new AtomicInteger();

    private DownloadSelfTest() {
    }

    /** @return 失败断言数（0 表示全部通过） */
    public static int run() {
        failures = 0;
        HttpServer server = null;
        Path tmp = null;
        String oldWindow = System.getProperty("mclauncher.stallWindowMs");
        String oldRate = System.getProperty("mclauncher.minRateBps");
        try {
            tmp = Files.createTempDirectory("mclauncher-dltest");
            byte[] payload = new byte[512 * 1024];
            new Random(1234).nextBytes(payload);   // 不可压缩，避免被压缩干扰
            String sha1 = Http.sha1Of(payload);

            server = startServer(payload);
            String base = "http://127.0.0.1:" + server.getAddress().getPort();

            // 1) 正常下载 + sha1
            Path a = tmp.resolve("a.bin");
            Http.download(base + "/file", a, null, payload.length, sha1);
            eq("正常下载大小", (long) payload.length, Files.size(a));
            eq("正常下载 sha1", sha1, Http.sha1Of(a));

            // 2) 断点续传：先把文件的开头 1/3 写成 .part
            Path b = tmp.resolve("b.bin");
            Path bPart = tmp.resolve("b.bin.part");
            long keep = payload.length / 3;
            Files.write(bPart, java.util.Arrays.copyOf(payload, (int) keep));
            long before = rangeHits.get();
            Http.download(base + "/file", b, null, payload.length, sha1);
            eq("续传后大小", (long) payload.length, Files.size(b));
            eq("续传后 sha1（摘要已包含已落盘部分）", sha1, Http.sha1Of(b));
            eq("服务端收到 Range 请求", true, rangeHits.get() > before);
            eq("续传后 .part 已清理", false, Files.exists(bPart));

            // 3) 大小不符要报错（服务端给的长度和期望不同）
            Path c = tmp.resolve("c.bin");
            Exception sizeErr = null;
            try {
                Http.download(base + "/file", c, null, payload.length + 1, null);
            } catch (Exception e) {
                sizeErr = e;
            }
            eq("大小不符会抛错", true, sizeErr != null);
            eq("大小不符不会留下目标文件", false, Files.exists(c));

            // 4) 5xx 要报错（且不再重试到天荒地老）
            Path d = tmp.resolve("d.bin");
            Exception httpErr = null;
            try {
                Http.download(base + "/boom", d, null, -1, null);
            } catch (Exception e) {
                httpErr = e;
            }
            eq("HTTP 500 会抛错", true, httpErr != null);
            eq("HTTP 500 错误信息带状态码", true,
                    httpErr != null && httpErr.getMessage() != null && httpErr.getMessage().contains("500"));

            // 5) 卡死检测：服务端回 200 后只挤一点点就不动了
            System.setProperty("mclauncher.stallWindowMs", "1200");
            System.setProperty("mclauncher.minRateBps", "1000000");
            Path e = tmp.resolve("e.bin");
            long t0 = System.currentTimeMillis();
            Exception stallErr = null;
            try {
                Http.download(base + "/stall", e, null, 200 * 1024, null);
            } catch (Exception ex) {
                stallErr = ex;
            }
            long elapsed = System.currentTimeMillis() - t0;
            eq("卡死会被判定为失败", true, stallErr != null);
            eq("卡死错误信息可读", true,
                    stallErr != null && stallErr.getMessage() != null
                            && (stallErr.getMessage().contains("下载卡住") || stallErr.getMessage().contains("下载太慢")));
            eq("卡死不会无限挂起（<30s）", true, elapsed < 30_000);
            eq("卡死后不产生目标文件", false, Files.exists(e));

        } catch (Exception ex) {
            fail("自检抛异常: " + ex);
            ex.printStackTrace();
        } finally {
            if (oldWindow == null) System.clearProperty("mclauncher.stallWindowMs");
            else System.setProperty("mclauncher.stallWindowMs", oldWindow);
            if (oldRate == null) System.clearProperty("mclauncher.minRateBps");
            else System.setProperty("mclauncher.minRateBps", oldRate);
            if (server != null) server.stop(0);
            if (tmp != null) {
                try (var walk = Files.walk(tmp)) {
                    walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
                } catch (Exception ignored) {
                }
            }
        }
        System.out.println(failures == 0 ? "[dltest] 全部通过" : "[dltest] 失败 " + failures + " 项");
        return failures;
    }

    private static HttpServer startServer(byte[] payload) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/file", ex -> serveFile(ex, payload));
        server.createContext("/stall", ex -> serveStall(ex));
        server.createContext("/boom", ex -> {
            byte[] body = "boom".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            ex.sendResponseHeaders(500, body.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(body);
            } catch (IOException ignored) {
            }
        });
        server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
        server.start();
        return server;
    }

    private static void serveFile(HttpExchange ex, byte[] payload) throws IOException {
        attempts.incrementAndGet();
        long start = 0;
        String range = ex.getRequestHeaders().getFirst("Range");
        if (range != null && range.startsWith("bytes=")) {
            rangeHits.incrementAndGet();
            String spec = range.substring("bytes=".length());
            int dash = spec.indexOf('-');
            if (dash > 0) {
                try {
                    start = Long.parseLong(spec.substring(0, dash));
                } catch (NumberFormatException ignored) {
                }
            }
        }
        long remaining = payload.length - start;
        if (remaining <= 0) {
            ex.sendResponseHeaders(416, -1);
            ex.close();
            return;
        }
        if (start > 0) {
            ex.getResponseHeaders().set("Content-Range",
                    "bytes " + start + "-" + (payload.length - 1) + "/" + payload.length);
            ex.sendResponseHeaders(206, remaining);
        } else {
            ex.sendResponseHeaders(200, payload.length);
        }
        try (OutputStream os = ex.getResponseBody()) {
            os.write(payload, (int) start, (int) remaining);
        } catch (IOException ignored) {
        }
    }

    /** 回 200，立刻塞几 KB，然后「一动不动」——模拟镜像一滴一滴挤的情况。 */
    private static void serveStall(HttpExchange ex) throws IOException {
        ex.sendResponseHeaders(200, 200 * 1024L + 10);
        OutputStream os = ex.getResponseBody();
        byte[] head = new byte[2048];
        os.write(head);
        os.flush();
        try {
            Thread.sleep(30_000);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        } finally {
            try {
                os.close();
            } catch (IOException ignored) {
            }
        }
    }

    private static void eq(String label, Object expected, Object actual) {
        boolean ok = expected == null ? actual == null : expected.equals(actual);
        if (!ok) fail(label + "：期望 " + expected + "，实际 " + actual);
    }

    private static void fail(String msg) {
        failures++;
        System.out.println("[dltest] 失败 -> " + msg);
    }
}
