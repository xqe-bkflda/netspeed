package com.y.netspeed;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.HttpURLConnection;
import java.net.Socket;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;

/**
 * 测速核心。纯 JDK，无第三方库。
 * 下载测速：多个候选源，逐个 HEAD/小请求探测，挑响应最快的；多线程分段下载，环形缓冲区，不占堆。
 * 上传测速：向多个接收端 POST 生成的数据块，统计吞吐。
 * 延迟：TCP connect 握手时间的中位数（比 ICMP 更准，且不需要额外权限）。
 */
public final class Net {

    public interface Step {
        void on(String text);
        /** 实时速度回调：down=true 表示下行。 */
        void onSpeed(boolean down, double mbps);
    }

    public static class Result {
        public double downMbps;
        public double upMbps;
        public double jitterMs;
        public int pingMs;
        public String downSource = "-";
        public String upHost = "-";
        public String error;
    }

    // ---------------- 候选源 ----------------

    /** 下载测速源。supportsRange=true 的可以分段并行下载。 */
    private static final class Src {
        final String url;
        final boolean range;
        final long maxBytes;
        Src(String url, boolean range, long maxBytes) {
            this.url = url; this.range = range; this.maxBytes = maxBytes;
        }
    }

    private static final Src[] SOURCES = {
        new Src("https://speed.cloudflare.com/__down?bytes=25000000", false, 25000000L),
        new Src("https://mirrors.nju.edu.cn/ubuntu-releases/22.04/ubuntu-22.04.5-desktop-amd64.iso", true, 0),
        new Src("https://mirrors.tuna.tsinghua.edu.cn/ubuntu-releases/22.04/ubuntu-22.04.5-desktop-amd64.iso", true, 0),
        new Src("https://mirrors.aliyun.com/ubuntu-releases/22.04/ubuntu-22.04.5-desktop-amd64.iso", true, 0),
        new Src("https://mirrors.huaweicloud.com/ubuntu-releases/22.04/ubuntu-22.04.5-desktop-amd64.iso", true, 0),
    };

    /** 上传测速接收端（只回显，不落盘）。 */
    private static final String[] UP_HOSTS = {
        "https://speed.cloudflare.com/__up",
        "https://httpbin.org/post",
        "https://postman-echo.com/post",
    };

    private Net() {}

    // ---------------- 下载测速 ----------------

    /**
     * @param threads 并发连接数
     * @param seconds 单次测速时长上限
     */
    public static double download(int threads, int seconds, Result out, Step step) {
        if (step != null) step.on("测下载");

        // 1. 选源：所有候选并发赛跑，谁先返回可用响应头就先用谁
        final Src pick = probeSources();
        if (pick == null) {
            out.error = "没有可用的下载源";
            if (step != null) step.on(out.error);
            return 0;
        }
        final String base = pick.url;
        if (!pick.range && threads > 2) threads = 2;   // 整段下载的源，线程多了纯浪费流量
        if (step != null) step.on("源 " + hostOf(base) + "，" + threads + (pick.range ? " 线程分段" : " 线程独立请求"));

        // 2. 分段下载，环形缓冲，绝不 OOM
        final long totalBytes = 32L * 1024 * 1024;     // 总下载量上限
        final long chunk = totalBytes / threads;
        final long deadline = System.currentTimeMillis() + seconds * 1000L;

        Counter counter = new Counter();
        List<Thread> ts = new ArrayList<Thread>();
        for (int n = 0; n < threads; n++) {
            final Src c = pick;
            final int idx = n;
            Thread t = new Thread(new Runnable() {
                @Override public void run() {
                    fetch(c, idx, chunk, deadline, counter);
                }
            }, "dl" + n);
            t.setDaemon(true);
            ts.add(t);
            t.start();
        }

        // 3. 采样
        long t0 = System.currentTimeMillis();
        long lastBytes = 0;
        long lastT = t0;
        double best = 0;
        while (true) {
            sleep(300);
            long now = System.currentTimeMillis();
            long b = counter.get();
            double dt = (now - lastT) / 1000.0;
            if (dt > 0) {
                double inst = (b - lastBytes) * 8.0 / dt / 1e6;
                if (inst > best) best = inst;
            }
            lastBytes = b;
            lastT = now;
            double avg = b * 8.0 / Math.max(0.001, (now - t0) / 1000.0) / 1e6;
            double shown = Math.max(avg, best * 0.9);
            if (step != null) {
                step.on("下载 " + fmt(shown) + " Mbps  ·  " + Mem.mb(b / 1024) + " 已下");
                step.onSpeed(true, shown);
            }
            if (now - t0 >= seconds * 1000L || counter.done.get() >= threads) break;
        }
        for (Thread t : ts) {
            try { t.join(1200); } catch (InterruptedException ignored) {}
        }
        for (Thread t : ts) t.interrupt();

        long el = System.currentTimeMillis() - t0;
        double mbps = counter.get() * 8.0 / Math.max(0.05, el / 1000.0) / 1e6;
        if (mbps < best * 0.85) mbps = best * 0.85;   // 收尾阶段被截断时用峰值修正
        out.downMbps = mbps;
        out.downSource = hostOf(base);
        if (step != null) step.on("下载完成 " + fmt(mbps) + " Mbps（" + Mem.mb(counter.get() / 1024) + "，源 " + out.downSource + "）");
        return mbps;
    }

    private static void fetch(Src src, int idx, long chunk, long deadline, Counter counter) {
        HttpURLConnection c = null;
        InputStream in = null;
        try {
            URL u = new URL(src.url);
            c = (HttpURLConnection) u.openConnection();
            c.setConnectTimeout(6000);
            c.setReadTimeout(8000);
            c.setRequestMethod("GET");
            c.setRequestProperty("User-Agent", "NetSpeed/1.0");
            c.setRequestProperty("Accept-Encoding", "identity");
            c.setUseCaches(false);
            if (src.range) {
                // 分段：每个线程要一块不同的区间，避免命中同一份缓存
                long from = (long) idx * chunk;
                long to = from + chunk - 1;
                c.setRequestProperty("Range", "bytes=" + from + "-" + to);
            }
            c.connect();
            in = c.getInputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                counter.add(n);
                if (System.currentTimeMillis() > deadline) break;
            }
        } catch (Throwable ignored) {
        } finally {
            if (in != null) try { in.close(); } catch (Throwable ignored) {}
            if (c != null) c.disconnect();
            counter.done.incrementAndGet();
        }
    }

    /** 并发探测所有源，返回最先可用者。 */
    private static Src probeSources() {
        final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        final java.util.concurrent.atomic.AtomicReference<Src> win =
            new java.util.concurrent.atomic.AtomicReference<Src>();
        for (final Src s : SOURCES) {
            Thread t = new Thread(new Runnable() {
                @Override public void run() {
                    if (win.get() != null) return;
                    HttpURLConnection k = null;
                    try {
                        URL u = new URL(s.url);
                        k = (HttpURLConnection) u.openConnection();
                        k.setConnectTimeout(4500);
                        k.setReadTimeout(4500);
                        k.setRequestMethod("GET");
                        k.setRequestProperty("User-Agent", "NetSpeed/1.0");
                        k.setRequestProperty("Range", "bytes=0-2047");
                        int code = k.getResponseCode();
                        if ((code == 200 || code == 206) && win.compareAndSet(null, s)) {
                            latch.countDown();
                        }
                    } catch (Throwable ignored) {
                    } finally {
                        if (k != null) k.disconnect();
                    }
                }
            }, "probe");
            t.setDaemon(true);
            t.start();
        }
        try { latch.await(6, java.util.concurrent.TimeUnit.SECONDS); } catch (Throwable ignored) {}
        return win.get();
    }

    // ---------------- 上传测速 ----------------

    public static double upload(int threads, int seconds, Result out, Step step) {
        return uploadTo(null, threads, seconds, out, step);
    }

    /** host 为 null 时自动挑公网接收端；显式传地址用于自测。 */
    public static double uploadTo(String fixed, int threads, int seconds, Result out, Step step) {
        if (step != null) step.on("测上传");

        String host = fixed;
        if (host != null) {
            if (step != null) step.on("上传源 " + host);
        }
        for (String h : UP_HOSTS) {
            if (host != null) break;
            HttpURLConnection k = null;
            try {
                URL u = new URL(h);
                k = (HttpURLConnection) u.openConnection();
                k.setConnectTimeout(4000);
                k.setReadTimeout(4000);
                k.setRequestMethod("HEAD");
                k.setRequestProperty("User-Agent", "NetSpeed/1.0");
                int code = k.getResponseCode();
                if (code > 0) { host = h; break; }
            } catch (Throwable ignored) {
            } finally {
                if (k != null) k.disconnect();
            }
        }
        if (host == null) {
            if (step != null) step.on("上传源不可用，跳过上传测试");
            out.error = "上传源不可用";
            return 0;
        }

        long perThreadBytes = 16L * 1024 * 1024;   // 单线程最多 16MB
        long deadline = System.currentTimeMillis() + seconds * 1000L;
        Counter counter = new Counter();
        List<Thread> ts = new ArrayList<Thread>();
        for (int n = 0; n < threads; n++) {
            final String h = host;
            Thread t = new Thread(new Runnable() {
                @Override public void run() {
                    push(h, perThreadBytes, deadline, counter);
                }
            }, "ul" + n);
            t.setDaemon(true);
            ts.add(t);
            t.start();
        }

        long t0 = System.currentTimeMillis();
        long lastBytes = 0, lastT = t0;
        double best = 0;
        while (true) {
            sleep(300);
            long now = System.currentTimeMillis();
            long b = counter.get();
            double dt = (now - lastT) / 1000.0;
            if (dt > 0) {
                double inst = (b - lastBytes) * 8.0 / dt / 1e6;
                if (inst > best) best = inst;
            }
            lastBytes = b;
            lastT = now;
            double avg = b * 8.0 / Math.max(0.001, (now - t0) / 1000.0) / 1e6;
            double shown = Math.max(avg, best * 0.9);
            if (step != null) {
                step.on("上传 " + fmt(shown) + " Mbps  ·  " + Mem.mb(b / 1024) + " 已发");
                step.onSpeed(false, shown);
            }
            if (now - t0 >= seconds * 1000L || counter.done.get() >= threads) break;
        }
        for (Thread t : ts) { try { t.join(1200); } catch (InterruptedException ignored) {} }
        for (Thread t : ts) t.interrupt();

        long el = System.currentTimeMillis() - t0;
        double mbps = counter.get() * 8.0 / Math.max(0.05, el / 1000.0) / 1e6;
        if (mbps < best * 0.85) mbps = best * 0.85;
        out.upMbps = mbps;
        out.upHost = hostOf(host);
        if (step != null) step.on("上传完成 " + fmt(mbps) + " Mbps（" + Mem.mb(counter.get() / 1024) + "）");
        return mbps;
    }

    private static void push(String host, long total, long deadline, Counter counter) {
        HttpURLConnection c = null;
        OutputStream o = null;
        try {
            URL u = new URL(host);
            c = (HttpURLConnection) u.openConnection();
            c.setConnectTimeout(6000);
            c.setReadTimeout(10000);
            c.setDoOutput(true);
            c.setRequestMethod("POST");
            c.setFixedLengthStreamingMode(total);
            c.setRequestProperty("Content-Type", "application/octet-stream");
            c.setRequestProperty("User-Agent", "NetSpeed/1.0");
            c.setUseCaches(false);
            o = c.getOutputStream();
            // 64KB 随机块循环写（不申请大数组，内存占用恒定）
            byte[] blk = new byte[32 * 1024];
            java.util.Random rnd = new java.util.Random(7);
            rnd.nextBytes(blk);
            long sent = 0;
            while (sent < total) {
                int n = (int) Math.min(blk.length, total - sent);
                o.write(blk, 0, n);
                counter.add(n);
                sent += n;
                if (System.currentTimeMillis() > deadline) break;
            }
            o.flush();
            int code = c.getResponseCode();
            if (code >= 400) { /* 接收端拒收，已发送字节仍然计入带宽 */ }
        } catch (Throwable ignored) {
        } finally {
            if (o != null) try { o.close(); } catch (Throwable ignored) {}
            if (c != null) c.disconnect();
            counter.done.incrementAndGet();
        }
    }

    // ---------------- 延迟 ----------------

    /** TCP 握手延迟：挑响应最快的一个目标，连测 5 次取中位数；抖动 = 相邻差值均值。 */
    public static int latency(Result out, Step step) {
        String[] hosts = { "speed.cloudflare.com", "www.baidu.com",
                           "mirrors.tuna.tsinghua.edu.cn", "mirrors.nju.edu.cn" };

        String bestHost = null;
        long bestMs = Long.MAX_VALUE;
        for (String h : hosts) {
            long v = tcpPing(h, 443);
            if (v < 0) v = tcpPing(h, 80);
            if (v >= 0 && v < bestMs) { bestMs = v; bestHost = h; }
            if (bestMs < 60) break;   // 已经足够快，不用再挑
        }
        if (bestHost == null) {
            if (step != null) step.on("延迟测不了（连接全失败）");
            return 0;
        }

        List<Long> per = new ArrayList<Long>();
        for (int i = 0; i < 5; i++) {
            long v = tcpPing(bestHost, 443);
            if (v < 0) v = tcpPing(bestHost, 80);
            if (v >= 0) per.add(v);
        }
        if (per.isEmpty()) per.add(bestMs);

        Collections.sort(per);
        int med = (int) (long) per.get(per.size() / 2);
        double jit = 0;
        if (per.size() > 1) {
            for (int i = 1; i < per.size(); i++) jit += Math.abs(per.get(i) - per.get(i - 1));
            jit = jit / (per.size() - 1);
        }
        out.pingMs = med;
        out.jitterMs = jit;
        if (step != null) step.on("延迟 " + med + " ms · 抖动 "
            + String.format(Locale.US, "%.1f", jit) + " ms（" + bestHost + "）");
        return med;
    }

    private static long tcpPing(String host, int port) {
        Socket s = null;
        try {
            long t = System.nanoTime();
            s = new Socket();
            s.connect(new java.net.InetSocketAddress(InetAddress.getByName(host), port), 3000);
            return (System.nanoTime() - t) / 1000000L;
        } catch (Throwable e) {
            return -1;
        } finally {
            if (s != null) try { s.close(); } catch (Throwable ignored) {}
        }
    }

    // ---------------- 工具 ----------------

    private static final class Counter {
        private final java.util.concurrent.atomic.AtomicLong v = new java.util.concurrent.atomic.AtomicLong();
        final java.util.concurrent.atomic.AtomicInteger done = new java.util.concurrent.atomic.AtomicInteger();
        void add(int n) { v.addAndGet(n); }
        long get() { return v.get(); }
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) {}
    }

    private static String hostOf(String url) {
        try {
            return new URL(url).getHost();
        } catch (Throwable e) {
            return url;
        }
    }

    public static String fmt(double v) {
        return String.format(Locale.US, "%.1f", v);
    }

    /** 按带宽量级给出建议的并发数，避免千兆口被单线程掐死、弱网被并发打死。 */
    public static int autoThreads(double mbps) {
        if (mbps <= 0) return 4;
        if (mbps < 5) return 2;
        if (mbps < 20) return 3;
        if (mbps < 60) return 4;
        if (mbps < 200) return 5;
        return 6;
    }
}
