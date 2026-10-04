package dev.linjian.peek;

import android.content.Context;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;
import org.json.JSONObject;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Exactly one small window. Outside its bounds all taps go to the underlying app. */
final class StatusOverlayView {
    private final Context context;
    private final WindowManager manager;
    private final LinearLayout root, details, rows, zones, actions;
    interface Interaction { void run(String action, String zone); }
    private final Interaction interaction;
    private boolean busy;
    private String reaction = "";
    private final TextView title, mood, activity, physiology, metrics, footer, collapse;
    private final WindowManager.LayoutParams params;
    private boolean attached, collapsed;
    private JSONObject snapshot;
    private String connection = "正在连接…";
    private long receivedAt;

    StatusOverlayView(Context ctx, Runnable stop, Runnable retry, Interaction interaction) {
        context = ctx;
        this.interaction = interaction;
        manager = (WindowManager)ctx.getSystemService(Context.WINDOW_SERVICE);
        collapsed = AppPrefs.get(ctx).getBoolean(StatusOverlayConfig.COLLAPSED, false);
        root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(10), dp(4), dp(10), dp(4));
        GradientDrawable background = new GradientDrawable();
        background.setColor(Color.rgb(246, 242, 255));
        background.setCornerRadius(dp(16));
        background.setStroke(dp(1), Color.rgb(193, 173, 223));
        root.setBackground(background);

        LinearLayout header = new LinearLayout(ctx);
        header.setGravity(Gravity.CENTER_VERTICAL);
        title = text(15, Color.rgb(71, 48, 99));
        title.setContentDescription("拖动标题移动状态栏");
        header.addView(title, new LinearLayout.LayoutParams(0, dp(36), 1));
        collapse = text(20, Color.rgb(114, 79, 166));
        collapse.setGravity(Gravity.CENTER);
        collapse.setContentDescription("折叠或展开状态栏");
        header.addView(collapse, new LinearLayout.LayoutParams(dp(40), dp(36)));
        TextView close = text(20, Color.rgb(114, 79, 166));
        close.setText("×"); close.setGravity(Gravity.CENTER);
        close.setContentDescription("关闭悬浮状态栏");
        close.setOnClickListener(v -> stop.run());
        header.addView(close, new LinearLayout.LayoutParams(dp(40), dp(36)));
        root.addView(header);
        details = new LinearLayout(ctx); details.setOrientation(LinearLayout.VERTICAL);
        rows = new LinearLayout(ctx); rows.setOrientation(LinearLayout.VERTICAL);
        mood = row("♡ 心情", rows);
        activity = row("◌ 正在", rows);
        physiology = row("♨ 身体", rows);
        details.addView(rows);
        zones = new LinearLayout(ctx); zones.setOrientation(LinearLayout.VERTICAL);
        zones.setVisibility(View.GONE);
        details.addView(zones, new LinearLayout.LayoutParams(-1, dp(78)));
        metrics = text(12, Color.rgb(114, 79, 166));
        metrics.setGravity(Gravity.CENTER_VERTICAL);
        details.addView(metrics, new LinearLayout.LayoutParams(-1, dp(18)));
        actions = new LinearLayout(ctx);
        addAction(actions, "♡ 靠近", () -> interaction.run("approach", ""));
        addAction(actions, "♧ 摸摸", this::toggleZones);
        addAction(actions, "↻ 刷新", retry);
        details.addView(actions, new LinearLayout.LayoutParams(-1, dp(30)));
        footer = text(10, Color.rgb(115, 103, 137));
        footer.setGravity(Gravity.CENTER_VERTICAL);
        footer.setContentDescription("同步状态，点击重试");
        footer.setOnClickListener(v -> retry.run());
        details.addView(footer, new LinearLayout.LayoutParams(-1, dp(18)));
        root.addView(details);

        params = new WindowManager.LayoutParams(width(), collapsed ? dp(44) : dp(188),
                Build.VERSION.SDK_INT >= 26 ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY : WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.START;
        params.x = AppPrefs.get(ctx).getInt(StatusOverlayConfig.X, dp(8));
        params.y = AppPrefs.get(ctx).getInt(StatusOverlayConfig.Y, dp(90));
        title.setOnTouchListener(new View.OnTouchListener() {
            float startX, startY; int x, y; boolean moved;
            @Override public boolean onTouch(View v, MotionEvent event) {
                if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                    startX = event.getRawX(); startY = event.getRawY(); x = params.x; y = params.y; moved = false;
                    return true;
                }
                if (event.getActionMasked() == MotionEvent.ACTION_MOVE) {
                    float dx = event.getRawX() - startX, dy = event.getRawY() - startY;
                    if (Math.hypot(dx, dy) > ViewConfiguration.get(ctx).getScaledTouchSlop()) moved = true;
                    if (moved) { params.x = x + Math.round(dx); params.y = y + Math.round(dy); resize(); }
                    return true;
                }
                if (event.getActionMasked() == MotionEvent.ACTION_UP || event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                    if (moved) savePosition();
                    else if (event.getActionMasked() == MotionEvent.ACTION_UP) { v.performClick(); retry.run(); }
                    return true;
                }
                return true;
            }
        });
        collapse.setOnClickListener(v -> {
            collapsed = !collapsed;
            AppPrefs.get(ctx).edit().putBoolean(StatusOverlayConfig.COLLAPSED, collapsed).apply();
            resize(); render();
        });
        resize(); render();
    }

    void show(boolean visible) {
        if (!visible) { remove(); return; }
        if (!attached) { resize(); manager.addView(root, params); attached = true; }
    }

    void remove() {
        if (!attached) return;
        try { manager.removeView(root); } catch (IllegalArgumentException ignored) { }
        attached = false;
    }

    void update(JSONObject data, String message, long timestamp) {
        snapshot = data; connection = message; receivedAt = timestamp; render();
    }

    void setBusy(boolean value) {
        busy = value;
        for (int i = 0; i < actions.getChildCount(); i++) actions.getChildAt(i).setEnabled(!value);
        actions.setAlpha(value ? .45f : 1f);
    }

    void showReaction(String value) { reaction = value; }

    private void toggleZones() {
        if (busy) return;
        if (zones.getVisibility() == View.VISIBLE) {
            zones.setVisibility(View.GONE); rows.setVisibility(View.VISIBLE); return;
        }
        org.json.JSONArray options = snapshot == null ? null : snapshot.optJSONArray("touchZones");
        if (options == null || options.length() == 0) {
            footer.setText("请先安装服务器 v6 以启用触碰"); return;
        }
        zones.removeAllViews();
        LinearLayout line = null;
        int count = Math.min(9, options.length());
        for (int i = 0; i < count; i++) {
            if (i % 3 == 0) {
                line = new LinearLayout(context);
                zones.addView(line, new LinearLayout.LayoutParams(-1, dp(26)));
            }
            final String zone = options.optString(i);
            addAction(line, zone, () -> {
                if (busy) return;
                zones.setVisibility(View.GONE); rows.setVisibility(View.VISIBLE);
                interaction.run("touch", zone);
            });
        }
        rows.setVisibility(View.GONE); zones.setVisibility(View.VISIBLE);
    }

    private void addAction(LinearLayout parent, String label, Runnable action) {
        TextView button = text(12, Color.rgb(114, 79, 166));
        button.setText(label); button.setGravity(Gravity.CENTER);
        button.setContentDescription(label); button.setOnClickListener(v -> { if (!busy) action.run(); });
        parent.addView(button, new LinearLayout.LayoutParams(0, -1, 1));
    }

    void resize() {
        params.width = width();
        params.height = collapsed ? dp(44) : dp(188);
        int w = context.getResources().getDisplayMetrics().widthPixels;
        int h = context.getResources().getDisplayMetrics().heightPixels;
        params.x = Math.max(0, Math.min(params.x, w - params.width));
        params.y = Math.max(0, Math.min(params.y, h - params.height - dp(48)));
        details.setVisibility(collapsed ? View.GONE : View.VISIBLE);
        if (attached) manager.updateViewLayout(root, params);
    }

    private void savePosition() {
        AppPrefs.get(context).edit().putInt(StatusOverlayConfig.X, params.x).putInt(StatusOverlayConfig.Y, params.y).apply();
    }

    private void render() {
        boolean synced = "已同步".equals(connection);
        String name = field("name", "祁昼");
        String state = synced ? (snapshot != null && snapshot.optBoolean("online", true) ? "在线" : "离线")
                : (snapshot == null ? "连接中" : "暂未同步");
        title.setText(name + " · 共用 · " + state);
        collapse.setText(collapsed ? "+" : "−");
        mood.setText(field("mood", "等待同步"));
        activity.setText(field("activity", "等待同步"));
        physiology.setText(field("physiology", "等待同步"));
        if (snapshot == null) metrics.setText("⚡ 精力 —     ·     心跳 — BPM");
        else metrics.setText("⚡ 精力 " + Math.max(0, Math.min(100, snapshot.optInt("energy")))
                + "%     ·     心跳 " + Math.max(0, Math.min(300, snapshot.optInt("bpm"))) + " BPM");
        String time = receivedAt > 0 ? new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date(receivedAt)) : "";
        footer.setText(synced && !reaction.isEmpty() ? reaction : connection + (time.isEmpty() ? "" : " · 上次 " + time));
    }

    private String field(String key, String fallback) {
        if (snapshot == null) return fallback;
        String value = snapshot.optString(key, fallback).replaceAll("[\\r\\n\\t]", " ");
        return value.length() > 180 ? value.substring(0, 180) : value;
    }

    private TextView row(String label, LinearLayout parent) {
        LinearLayout row = new LinearLayout(context); row.setGravity(Gravity.CENTER_VERTICAL);
        TextView caption = text(12, Color.rgb(151, 121, 182)); caption.setText(label);
        row.addView(caption, new LinearLayout.LayoutParams(dp(64), -1));
        TextView value = text(13, Color.rgb(66, 49, 85)); value.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(value, new LinearLayout.LayoutParams(0, -1, 1));
        parent.addView(row, new LinearLayout.LayoutParams(-1, dp(26)));
        return value;
    }

    private TextView text(int size, int color) {
        TextView v = new TextView(context);
        // Fixed small-window geometry, independent of very large system text settings.
        v.setTextSize(TypedValue.COMPLEX_UNIT_DIP, size); v.setTextColor(color);
        v.setSingleLine(true); v.setEllipsize(TextUtils.TruncateAt.END);
        return v;
    }
    private int width() { return Math.min(dp(360), context.getResources().getDisplayMetrics().widthPixels - dp(16)); }
    private int dp(int value) { return Math.round(value * context.getResources().getDisplayMetrics().density); }
}
