package com.y.netspeed;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Locale;

public class MainActivity extends Activity {

    private static final int REQ_NOTIF = 1001;

    private TextView tvDown, tvUp, tvPing, tvStatus, tvSrc, tvMemLine, tvMemTotal, tvHint;
    private ProgressBar bar;
    private Button btnStart, btnFloat;

    private final Handler h = new Handler(Looper.getMainLooper());
    private volatile boolean running;
    private Thread worker;
    private long lastMemRun;

    private final Runnable memTick = new Runnable() {
        @Override public void run() {
            updateMem();
            h.postDelayed(this, 2000);
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(buildUi());
        askNotifPermission();
    }

    @Override
    protected void onResume() {
        super.onResume();
        h.removeCallbacks(memTick);
        h.post(memTick);
        updateFloatBtn();
    }

    @Override
    protected void onPause() {
        super.onPause();
        h.removeCallbacks(memTick);
    }

    @Override
    protected void onDestroy() {
        h.removeCallbacksAndMessages(null);
        running = false;
        super.onDestroy();
    }

    // ---------------- UI ----------------

    private int dp(float v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    private View buildUi() {
        int pad = dp(16);

        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(Color.parseColor("#0F1419"));
        sv.setFillViewport(true);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, dp(24), pad, dp(24));
        sv.addView(root, new ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        root.addView(title("网络测速"));
        root.addView(small("下载 / 上传 / 延迟，悬浮球看内存"));

        // 主数字
        LinearLayout nums = new LinearLayout(this);
        nums.setOrientation(LinearLayout.HORIZONTAL);
        nums.setPadding(0, dp(20), 0, 0);
        nums.addView(numBox("下载", "Mbps", true));
        nums.addView(spacer(dp(10)));
        nums.addView(numBox("上传", "Mbps", false));
        root.addView(nums);

        LinearLayout meta = new LinearLayout(this);
        meta.setOrientation(LinearLayout.HORIZONTAL);
        meta.setPadding(0, dp(14), 0, 0);
        tvPing = small("延迟 -- ms   抖动 -- ms");
        tvPing.setTextColor(Color.parseColor("#8FA3B8"));
        tvPing.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        meta.addView(tvPing);
        root.addView(meta);

        tvSrc = small("源 --");
        tvSrc.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        tvSrc.setTextColor(Color.parseColor("#5C6E80"));
        tvSrc.setPadding(0, dp(4), 0, 0);
        root.addView(tvSrc);

        bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(100);
        bar.setProgress(0);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(4));
        blp.topMargin = dp(12);
        bar.setLayoutParams(blp);
        root.addView(bar);

        tvStatus = small("点下面的按钮开始");
        tvStatus.setTextColor(Color.parseColor("#C7D3DF"));
        tvStatus.setPadding(0, dp(8), 0, 0);
        root.addView(tvStatus);

        // 按钮
        LinearLayout btns = new LinearLayout(this);
        btns.setOrientation(LinearLayout.HORIZONTAL);
        btns.setPadding(0, dp(18), 0, 0);

        btnStart = new Button(this);
        btnStart.setText("开始测速");
        btnStart.setAllCaps(false);
        btnStart.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { startTest(); }
        });
        LinearLayout.LayoutParams lp1 = new LinearLayout.LayoutParams(0,
            ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        btnStart.setLayoutParams(lp1);
        btns.addView(btnStart);

        LinearLayout.LayoutParams gap = new LinearLayout.LayoutParams(dp(10),
            ViewGroup.LayoutParams.WRAP_CONTENT);
        btns.addView(new View(this), gap);

        btnFloat = new Button(this);
        btnFloat.setAllCaps(false);
        btnFloat.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { toggleFloat(); }
        });
        btnFloat.setLayoutParams(new LinearLayout.LayoutParams(0,
            ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        btns.addView(btnFloat);

        root.addView(btns);

        // 内存区
        root.addView(divider(dp(26)));
        root.addView(sectionTitle("内存"));

        tvMemLine = new TextView(this);
        tvMemLine.setTextColor(Color.WHITE);
        tvMemLine.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        tvMemLine.setPadding(0, dp(6), 0, 0);
        root.addView(tvMemLine);

        tvMemTotal = new TextView(this);
        tvMemTotal.setTextColor(Color.parseColor("#8FA3B8"));
        tvMemTotal.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        tvMemTotal.setPadding(0, dp(2), 0, 0);
        root.addView(tvMemTotal);

        tvHint = new TextView(this);
        tvHint.setTextColor(Color.parseColor("#5C6E80"));
        tvHint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        tvHint.setLineSpacing(dp(3), 1f);
        tvHint.setPadding(0, dp(22), 0, 0);
        tvHint.setText("悬浮球：单击展开 / 收起，拖动换位置，长按回到这个页面。\n"
            + "球上只显示内存占用百分比，外圈颜色越红就是越吃紧。\n"
            + "长按球 → 贴边隐藏；单击贴边的那条再点出来。");
        root.addView(tvHint);

        return sv;
    }

    private TextView title(String t) {
        TextView v = new TextView(this);
        v.setText(t);
        v.setTextColor(Color.WHITE);
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26);
        v.setTypeface(v.getTypeface(), android.graphics.Typeface.BOLD);
        return v;
    }

    private TextView sectionTitle(String t) {
        TextView v = new TextView(this);
        v.setText(t);
        v.setTextColor(Color.parseColor("#8FA3B8"));
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        return v;
    }

    private TextView small(String t) {
        TextView v = new TextView(this);
        v.setText(t);
        v.setTextColor(Color.parseColor("#8FA3B8"));
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        return v;
    }

    private View spacer(int w) {
        View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(w, dp(1)));
        return v;
    }

    private View divider(int top) {
        View v = new View(this);
        v.setBackgroundColor(Color.parseColor("#1E262F"));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
        lp.topMargin = top;
        v.setLayoutParams(lp);
        return v;
    }

    private View numBox(String label, String unit, boolean down) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackgroundColor(Color.parseColor("#161C23"));
        box.setPadding(dp(12), dp(12), dp(12), dp(12));
        box.setLayoutParams(new LinearLayout.LayoutParams(0,
            ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView l = new TextView(this);
        l.setText(label);
        l.setTextColor(Color.parseColor("#8FA3B8"));
        l.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);

        TextView v = new TextView(this);
        v.setText("--");
        v.setTextColor(down ? Color.parseColor("#4CD07D") : Color.parseColor("#4FA8FF"));
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, 30);
        v.setTypeface(v.getTypeface(), android.graphics.Typeface.BOLD);

        TextView u = new TextView(this);
        u.setText(unit);
        u.setTextColor(Color.parseColor("#5C6E80"));
        u.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);

        box.addView(l);
        box.addView(v);
        box.addView(u);

        if (down) tvDown = v; else tvUp = v;
        return box;
    }

    // ---------------- 测速 ----------------

    private void startTest() {
        if (running) {
            running = false;
            if (worker != null) worker.interrupt();
            status("已取消");
            return;
        }
        running = true;
        btnStart.setText("停止");
        tvDown.setText("--");
        tvUp.setText("--");
        tvPing.setText("延迟 -- ms   抖动 -- ms");
        bar.setProgress(0);
        progress = 0;


        worker = new Thread(new Runnable() {
            @Override public void run() {
                final Net.Result r = new Net.Result();
                Net.Step step = new Net.Step() {
                    @Override public void on(String text) { status(text); }
                    @Override public void onSpeed(boolean down, double mbps) {
                        pushValue(down, mbps);
                    }
                };
                try {
                    status("测量延迟…");
                    if (!running) return;
                    Net.latency(r, null);
                    ui(new Runnable() {
                        @Override public void run() {
                            tvPing.setText("延迟 " + r.pingMs + " ms   抖动 "
                                + String.format(Locale.US, "%.1f", r.jitterMs) + " ms");
                        }
                    });

                    if (!running) return;
                    status("准备下载源…");
                    Net.download(4, 8, r, new Net.Step() {
                        @Override public void on(String text) { status(text); }
                        @Override public void onSpeed(boolean down, double mbps) {
                            pushValue(down, mbps);
                        }
                    });
                    ui(new Runnable() {
                        @Override public void run() {
                            tvDown.setText(Net.fmt(r.downMbps));
                        }
                    });

                    if (!running) return;
                    Net.upload(3, 8, r, new Net.Step() {
                        @Override public void on(String text) { status(text); }
                        @Override public void onSpeed(boolean down, double mbps) {
                            pushValue(down, mbps);
                        }
                    });
                    ui(new Runnable() {
                        @Override public void run() {
                            tvUp.setText(Net.fmt(r.upMbps));
                        }
                    });

                    final String src = "源 " + r.downSource + " / 上传 " + r.upHost;
                    ui(new Runnable() {
                        @Override public void run() { tvSrc.setText(src); }
                    });
                    status(r.error != null ? ("完成（" + r.error + "）") : "完成");
                } catch (Throwable t) {
                    status("出错：" + t.getClass().getSimpleName());
                } finally {
                    running = false;
                    ui(new Runnable() {
                        @Override public void run() {
                            btnStart.setText("开始测速");
                            bar.setProgress(0);
                        }
                    });
                }
            }
        }, "speedtest");
        worker.setDaemon(true);
        worker.start();
    }

    /** 从 "下载 45.3 Mbps · ..." 里抠出数字，同步到界面和悬浮球 */
    private int progress;

    /** 直接吃结构化数值，不再解析字符串（少一堆临时 String）。 */
    private void pushValue(final boolean down, final double mbps) {
        progress = Math.min(100, progress + 1);
        final int p = progress;
        ui(new Runnable() {
            @Override public void run() {
                bar.setProgress(p);
                if (down) tvDown.setText(Net.fmt(mbps));
                else tvUp.setText(Net.fmt(mbps));
            }
        });
    }

    private static String firstNumber(String s) {
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c >= '0' && c <= '9') break;
            i++;
        }
        int j = i;
        while (j < s.length()) {
            char c = s.charAt(j);
            if ((c >= '0' && c <= '9') || c == '.') j++;
            else break;
        }
        if (j <= i) return null;
        return s.substring(i, j);
    }

    private void status(final String t) {
        ui(new Runnable() {
            @Override public void run() {
                tvStatus.setText(t);
            }
        });
    }

    private void ui(Runnable r) {
        h.post(r);
    }

    // ---------------- 内存 ----------------

    private void updateMem() {
        Mem.Info m = Mem.read();
        tvMemLine.setText(Mem.gb(m.usedKB) + " / " + Mem.gb(m.totalKB) + "   " + m.pct() + "%");
        tvMemTotal.setText("剩余 " + Mem.gb(m.availKB)
            + "   ·   本应用 " + Mem.mb(m.appRssKB)
            + "   ·   SWAP " + Mem.gb(m.swapTotalKB - m.swapFreeKB) + "/" + Mem.gb(m.swapTotalKB));
        if (!running) bar.setProgress(m.pct());
    }

    // ---------------- 悬浮窗 ----------------

    private boolean canOverlay() {
        if (Build.VERSION.SDK_INT < 23) return true;
        return Settings.canDrawOverlays(this);
    }

    private void updateFloatBtn() {
        boolean on = new java.io.File(getFilesDir(), "float.state").exists();
        btnFloat.setText(!canOverlay() ? "开启悬浮窗" : (on ? "关闭悬浮球" : "开启悬浮球"));
    }

    private void toggleFloat() {
        if (!canOverlay()) {
            try {
                Intent i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName()));
                startActivityForResult(i, 1002);
                Toast.makeText(this, "打开「显示在其他应用上层」后返回", Toast.LENGTH_LONG).show();
            } catch (Throwable t) {
                Toast.makeText(this, "请在系统设置里给本应用悬浮窗权限", Toast.LENGTH_LONG).show();
            }
            return;
        }
        Intent i = new Intent(this, FloatService.class);
        boolean running = new java.io.File(getFilesDir(), "float.state").exists();
        i.setAction(running ? FloatService.ACTION_STOP : FloatService.ACTION_START);
        if (running) {
            startService(i);                       // 停止动作不需要前台，避免 5s 超时 ANR
        } else if (Build.VERSION.SDK_INT >= 26) {
            startForegroundService(i);
        } else {
            startService(i);
        }
        btnFloat.setText(running ? "开启悬浮球" : "关闭悬浮球");
        if (!running) moveTaskToBack(true);
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == 1002) updateFloatBtn();
    }

    // ---------------- 通知权限 ----------------

    private void askNotifPermission() {
        if (Build.VERSION.SDK_INT >= 33) {
            if (checkSelfPermission("android.permission.POST_NOTIFICATIONS")
                != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, REQ_NOTIF);
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] p, int[] g) {
        super.onRequestPermissionsResult(req, p, g);
    }
}
