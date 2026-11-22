package com.y.netspeed;

import java.io.FileInputStream;

/** 内存读数：直接读字节流就地解析，不建 Reader、不建中间 String，不常驻对象。 */
public final class Mem {

    public static class Info {
        public long totalKB;
        public long availKB;
        public long usedKB;
        public long swapTotalKB;
        public long swapFreeKB;
        public long appRssKB;

        public int pct() {
            if (totalKB <= 0) return 0;
            long p = usedKB * 100L / totalKB;
            if (p < 0) p = 0;
            if (p > 100) p = 100;
            return (int) p;
        }
    }

    private static final byte[] BUF = new byte[2048];
    private static final long[] V = new long[6];
    private static final Info CACHE = new Info();

    private Mem() {}

    public static Info read() {
        parseMeminfo();
        Info i = CACHE;
        i.totalKB = V[0];
        i.availKB = V[1];
        long cached = V[2];
        i.swapTotalKB = V[3];
        i.swapFreeKB = V[4];
        if (V[5] == 0) i.availKB = i.availKB + cached;
        if (i.availKB < 0) i.availKB = 0;
        if (i.availKB > i.totalKB) i.availKB = i.totalKB;
        i.usedKB = i.totalKB - i.availKB;
        i.appRssKB = rss();
        return i;
    }

    private static void parseMeminfo() {
        V[0] = V[1] = V[2] = V[3] = V[4] = V[5] = 0;
        FileInputStream in = null;
        try {
            in = new FileInputStream("/proc/meminfo");
            int len = 0, r;
            while (len < 1024 && (r = in.read(BUF, len, 1024 - len)) > 0) len += r;
            scan(BUF, len);
        } catch (Throwable ignored) {
        } finally {
            if (in != null) try { in.close(); } catch (Throwable ignored) {}
        }
    }

    /** 就地按行找 key，不产生任何 String。 */
    private static void scan(byte[] b, int len) {
        int s = 0;
        for (int p = 0; p <= len; p++) {
            if (p == len || b[p] == '\n') {
                if (p > s) key(b, s, p);
                s = p + 1;
            }
        }
    }

    private static void key(byte[] b, int s, int e) {
        if (match(b, s, e, "MemTotal:")) {
            V[0] = value(b, s, e);
        } else if (match(b, s, e, "MemAvailable:")) {
            V[1] = value(b, s, e);
            V[5] = 1;
        } else if (match(b, s, e, "MemFree:")) {
            if (V[5] == 0) V[1] = value(b, s, e);
        } else if (match(b, s, e, "Buffers:")) {
            V[2] += value(b, s, e);
        } else if (match(b, s, e, "Cached:")) {
            V[2] += value(b, s, e);
        } else if (match(b, s, e, "SwapTotal:")) {
            V[3] = value(b, s, e);
        } else if (match(b, s, e, "SwapFree:")) {
            V[4] = value(b, s, e);
        }
    }

    private static boolean match(byte[] b, int s, int e, String k) {
        int n = k.length();
        if (e - s < n) return false;
        for (int i = 0; i < n; i++) {
            if (b[s + i] != (byte) k.charAt(i)) return false;
        }
        return true;
    }

    private static long value(byte[] b, int s, int e) {
        long v = 0;
        boolean any = false;
        for (int i = s; i < e; i++) {
            byte c = b[i];
            if (c >= '0' && c <= '9') {
                v = v * 10 + (c - '0');
                any = true;
            } else if (any) {
                break;
            }
        }
        return v;
    }

    private static long rss() {
        long r = 0;
        FileInputStream in = null;
        try {
            in = new FileInputStream("/proc/self/status");
            int len = 0, x;
            while (len < 2048 && (x = in.read(BUF, len, 2048 - len)) > 0) {
                len += x;
                if (len > 1600) break;
            }
            int s = 0;
            for (int p = 0; p <= len; p++) {
                if (p == len || BUF[p] == '\n') {
                    if (p > s && match(BUF, s, p, "VmRSS:")) {
                        r = value(BUF, s, p);
                        break;
                    }
                    s = p + 1;
                }
            }
        } catch (Throwable ignored) {
        } finally {
            if (in != null) try { in.close(); } catch (Throwable ignored) {}
        }
        return r;
    }

    public static String gb(long kb) {
        if (kb <= 0) return "0";
        return one(kb / 1048576.0) + "G";
    }

    public static String mb(long kb) {
        if (kb <= 0) return "0";
        if (kb >= 1024) return Math.round(kb / 1024.0) + "M";
        return kb + "K";
    }

    private static String one(double v) {
        long x = Math.round(v * 10);
        return (x / 10) + "." + (x % 10);
    }

    // ---------------- 磁盘 ----------------

    public static long diskUsed;
    public static long diskTotal;

    public static void readDisk(java.io.File dir) {
        try {
            android.os.StatFs sf = new android.os.StatFs(dir.getAbsolutePath());
            long block = sf.getBlockSizeLong();
            long total = sf.getBlockCountLong() * block;
            long avail = sf.getAvailableBlocksLong() * block;
            diskTotal = total;
            diskUsed = total - avail;
        } catch (Throwable t) {
            diskTotal = 0;
            diskUsed = 0;
        }
    }

    public static int diskPct() {
        if (diskTotal <= 0) return 0;
        long p = diskUsed * 100L / diskTotal;
        return (int) Math.max(0, Math.min(100, p));
    }

    /** 202GB / 256GB */
    public static String diskStr(long bytes) {
        if (bytes <= 0) return "0";
        double g = bytes / 1073741824.0;
        if (g >= 100) return Math.round(g) + "GB";
        return one(g) + "GB";
    }
}
