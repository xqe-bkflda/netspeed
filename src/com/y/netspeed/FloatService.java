package com.y.netspeed;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 悬浮窗服务。
 * 常驻只有一个 BallView（只显示内存百分比）+ 一个 Handler。
 * 点击 = 打开主界面；长按 = 贴边隐藏（留一条边，再点一下回来）。
 * 拖动用绝对坐标换算窗口位置，完全跟手。
 */
public class FloatService extends Service implements BallView.Callback {

    public static final String ACTION_START = "com.y.netspeed.START";
    public static final String ACTION_STOP = "com.y.netspeed.STOP";


    private WindowManager wm;
    private BallView ball;
    private LinearLayout panel;
    private TextView tvLine1, tvLine2;
    private boolean panelOpen;
    private boolean shown;
    private boolean hidden;
    private boolean dragging;

    private WindowManager.LayoutParams bp;
    private float grabDx, grabDy;
    private int screenW, screenH;

    private final Handler h = new Handler(Looper.getMainLooper());

    /** 空闲 3 秒自动贴边 + 半透明 */
    private static final long IDLE_MS = 3000;
    private final Runnable idle = new Runnable() {
        @Override public void run() {
            if (!hidden && !dragging) hideToEdge();
        }
    };

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            refresh();
            h.postDelayed(this, panelOpen ? 1000 : 2000);
        }
    };

    @Override public IBinder onBind(Intent i) { return null; }

    @Override
    public void onCreate() {
        super.onCreate();
        wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        screenW = getResources().getDisplayMetrics().widthPixels;
        screenH = getResources().getDisplayMetrics().heightPixels;
        startForeground(Notif.NID, Notif.build(this, "内存悬浮球运行中"));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String a = intent == null ? null : intent.getAction();
        if (ACTION_STOP.equals(a)) {
            markState(false);
            stopSelf();
            return START_NOT_STICKY;
        }
        markState(true);
        if (!shown) showBall();
        h.removeCallbacks(tick);
        h.post(tick);
        return START_STICKY;
    }

    private SharedPreferences prefs() {
        return getSharedPreferences("ns", MODE_PRIVATE);
    }

    /** 跨进程状态标记：悬浮球是否开着。文件比 SharedPreferences 可靠（无进程内缓存）。 */
    private void markState(boolean on) {
        try {
            java.io.File f = new java.io.File(getFilesDir(), "float.state");
            if (on) {
                if (!f.exists()) f.createNewFile();
            } else {
                if (f.exists()) f.delete();
            }
        } catch (Throwable ignored) {}
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }

    private static int overlayType() {
        return Build.VERSION.SDK_INT >= 26
            ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            : WindowManager.LayoutParams.TYPE_PHONE;
    }

    private void showBall() {
        if (ball != null) return;
        ball = new BallView(this, this);

        bp = new WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT);
        bp.gravity = Gravity.TOP | Gravity.START;
        bp.x = prefs().getInt("bx", screenW - (int) dp(72));
        bp.y = prefs().getInt("by", (int) dp(240));

        try {
            wm.addView(ball, bp);
            shown = true;
            touchActivity();
        } catch (Throwable t) {
            stopSelf();
        }
    }

    private void refresh() {
        Mem.Info m = Mem.read();
        if (ball != null) ball.setPct(m.pct());
        if (panelOpen && tvLine1 != null) {
            Mem.readDisk(getFilesDir());
            tvLine1.setText("RAM " + Mem.gb(m.usedKB) + "/" + Mem.gb(m.totalKB) + " " + m.pct() + "%");
            tvLine2.setText("DISK " + Mem.diskStr(Mem.diskUsed) + "/" + Mem.diskStr(Mem.diskTotal)
                + " " + Mem.diskPct() + "%");
        }
    }

    // ---------------- 文字面板（无按钮） ----------------

    private void touchActivity() {
        h.removeCallbacks(idle);
        if (!hidden) h.postDelayed(idle, IDLE_MS);
    }

    private void openPanel() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable g = new GradientDrawable();
        g.setColor(0xF214181D);
        g.setCornerRadius(dp(13));
        g.setStroke((int) dp(1), 0x33FFFFFF);
        box.setBackground(g);
        int pad = (int) dp(12);
        box.setPadding(pad, (int) dp(9), pad, (int) dp(9));

        tvLine1 = new TextView(this);
        tvLine1.setTextColor(Color.WHITE);
        tvLine1.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        tvLine1.setTypeface(android.graphics.Typeface.MONOSPACE);

        tvLine2 = new TextView(this);
        tvLine2.setTextColor(Color.WHITE);
        tvLine2.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        tvLine2.setTypeface(android.graphics.Typeface.MONOSPACE);
        tvLine2.setPadding(0, (int) dp(5), 0, 0);

        box.addView(tvLine1);
        box.addView(tvLine2);

        WindowManager.LayoutParams p = new WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT);
        p.gravity = Gravity.TOP | Gravity.START;

        int pw = (int) dp(178);
        int px = bp.x - pw - (int) dp(8);
        if (px < 0) px = bp.x + (int) dp(64);
        p.x = Math.max(0, Math.min(screenW - pw, px));
        p.y = Math.max(0, Math.min(screenH - (int) dp(64), bp.y));

        try {
            wm.addView(box, p);
            panel = box;
            panelOpen = true;
            refresh();
            h.removeCallbacks(idle);
            h.removeCallbacks(tick);
            h.postDelayed(tick, 800);
        } catch (Throwable ignored) {
            panelOpen = false;
        }
    }

    private void closePanel() {
        if (panel != null) {
            try { wm.removeView(panel); } catch (Throwable ignored) {}
            panel = null;
        }
        panelOpen = false;
        tvLine1 = null;
        tvLine2 = null;
        h.removeCallbacks(tick);
        h.postDelayed(tick, 2000);
    }

    // ---------------- 贴边隐藏 ----------------

    private void hideToEdge() {
        if (ball == null) return;
        closePanel();
        boolean toLeft = bp.x + dp(29) < screenW / 2f;
        bp.x = toLeft ? (int) -dp(18) : (int) (screenW - dp(40));
        bp.y = Math.max(0, Math.min(screenH - (int) dp(58), bp.y));
        ball.setAlpha(0.55f);
        try { wm.updateViewLayout(ball, bp); } catch (Throwable ignored) {}
        prefs().edit().putInt("bx", bp.x).putInt("by", bp.y)
            .putBoolean("edge", true).putBoolean("edge_left", toLeft).apply();
        hidden = true;
    }

    private void unhide() {
        if (ball == null) return;
        boolean toLeft = prefs().getBoolean("edge_left", true);
        bp.x = toLeft ? (int) dp(4) : (int) (screenW - dp(62));
        ball.setAlpha(1f);
        try { wm.updateViewLayout(ball, bp); } catch (Throwable ignored) {}
        prefs().edit().putInt("bx", bp.x).putBoolean("edge", false).apply();
        hidden = false;
        refresh();
        touchActivity();
    }

    // ---------------- 回调 ----------------

    @Override
    public void onTap() {
        if (hidden) { unhide(); touchActivity(); return; }
        if (panelOpen) { closePanel(); } else { openPanel(); }
        touchActivity();
    }

    @Override
    public void onLongPress() {
        h.removeCallbacks(idle);
        hideToEdge();
    }

    @Override
    public void onDragStart(float rawX, float rawY) {
        closePanel();
        h.removeCallbacks(idle);
        dragging = true;
        if (bp == null || ball == null) return;
        grabDx = rawX - bp.x;
        grabDy = rawY - bp.y;
        if (hidden) {
            // 从贴边状态直接拖出来
            hidden = false;
            ball.setAlpha(1f);
        }
    }

    @Override
    public void onDrag(float rawX, float rawY) {
        if (bp == null || ball == null) return;
        // 绝对坐标换算，绝不在旧位置上累加
        int w = ball.getWidth() > 0 ? ball.getWidth() : (int) dp(58);
        int hgt = ball.getHeight() > 0 ? ball.getHeight() : (int) dp(58);
        int nx = (int) (rawX - grabDx);
        int ny = (int) (rawY - grabDy);
        nx = Math.max(0, Math.min(screenW - w, nx));
        ny = Math.max(0, Math.min(screenH - hgt, ny));
        bp.x = nx;
        bp.y = ny;
        try { wm.updateViewLayout(ball, bp); } catch (Throwable ignored) {}
    }

    @Override
    public void onDragEnd() {
        dragging = false;
        if (bp != null) {
            prefs().edit().putInt("bx", bp.x).putInt("by", bp.y).putBoolean("edge", false).apply();
        }
        touchActivity();
    }

    @Override
    public void onDestroy() {
        h.removeCallbacks(tick);
        h.removeCallbacks(idle);
        closePanel();
        if (ball != null) {
            try { wm.removeView(ball); } catch (Throwable ignored) {}
            ball = null;
        }
        shown = false;
        markState(false);
        super.onDestroy();
    }
}
