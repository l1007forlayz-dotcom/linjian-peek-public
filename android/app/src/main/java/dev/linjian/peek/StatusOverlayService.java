package dev.linjian.peek;

import android.app.KeyguardManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Configuration;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkRequest;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public final class StatusOverlayService extends Service {
    private static final String CHANNEL = "qizhou_status_overlay";
    private static final String STOP = "dev.linjian.peek.STOP_STATUS_OVERLAY";
    private static final int NOTIFICATION_ID = 7308;
    private final Handler main = new Handler(Looper.getMainLooper());
    private ScheduledExecutorService worker;
    private ScheduledFuture<?> pending;
    private volatile HttpURLConnection request;
    private volatile boolean alive;
    private volatile int generation;
    private StatusOverlayConfig config;
    private StatusOverlayView overlay;
    private JSONObject snapshot;
    private long receivedAt, persistedAt;
    private String etag = "", message = "正在连接…";
    private int failures;
    private boolean screenRegistered, networkRegistered;
    private ConnectivityManager connectivity;
    private boolean inChat, actionBusy;
    private final Runnable scopeTick = new Runnable() {
        @Override public void run() {
            if (!alive) return;
            boolean next = interactive() && StatusOverlayScope.chatGptInFront();
            if (next != inChat) {
                inChat = next;
                refreshVisibility();
                if (next) kick();
            }
            main.postDelayed(this, interactive() ? 350 : 2000);
        }
    };

    public static boolean restore(Context ctx) {
        if (!AppPrefs.get(ctx).getBoolean(StatusOverlayConfig.ENABLED, false) || !Settings.canDrawOverlays(ctx)) return false;
        try {
            new StatusOverlayConfig(AppPrefs.get(ctx).getString(StatusOverlayConfig.ADDRESS, ""));
            Intent intent = new Intent(ctx, StatusOverlayService.class);
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(intent); else ctx.startService(intent);
            return true;
        } catch (Exception e) { return false; }
    }

    @Override public void onCreate() {
        super.onCreate();
        worker = Executors.newSingleThreadScheduledExecutor(r -> new Thread(r, "QizhouStatusPoll"));
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(CHANNEL, "祁昼悬浮状态栏", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("状态栏运行期间保持显示；可随时关闭");
            channel.setShowBadge(false);
            getSystemService(NotificationManager.class).createNotificationChannel(channel);
        }
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && STOP.equals(intent.getAction())) {
            AppPrefs.get(this).edit().putBoolean(StatusOverlayConfig.ENABLED, false).apply();
            stopSelf(); return START_NOT_STICKY;
        }
        if (!AppPrefs.get(this).getBoolean(StatusOverlayConfig.ENABLED, false) || !Settings.canDrawOverlays(this)) {
            stopSelf(); return START_NOT_STICKY;
        }
        try {
            StatusOverlayConfig next = new StatusOverlayConfig(AppPrefs.get(this).getString(StatusOverlayConfig.ADDRESS, ""));
            startForeground(NOTIFICATION_ID, notification());
            if (config == null || !next.address.equals(config.address)) {
                generation++; cancelRequest(); config = next; etag = ""; failures = 0;
                snapshot = null; receivedAt = AppPrefs.get(this).getLong(StatusOverlayConfig.CACHE_TIME, 0);
                try { snapshot = validSnapshot(new JSONObject(AppPrefs.get(this).getString(StatusOverlayConfig.CACHE, "{}"))); }
                catch (Exception ignored) { }
                message = snapshot == null ? "正在连接…" : "缓存状态 · 正在连接…";
            }
            alive = true;
            if (overlay == null) overlay = new StatusOverlayView(this, () -> {
                AppPrefs.get(this).edit().putBoolean(StatusOverlayConfig.ENABLED, false).apply(); stopSelf();
            }, this::kick, this::interact);
            main.removeCallbacks(scopeTick); main.post(scopeTick);
            bindSignals(); refreshVisibility(); kick();
            return START_STICKY;
        } catch (Exception e) {
            // Avoid logging URLs, tokens or snapshots.
            DebugState.append(this, "悬浮状态栏启动失败，请检查悬浮窗和后台权限");
            stopSelf(); return START_NOT_STICKY;
        }
    }

    private Notification notification() {
        Intent settings = new Intent(this, MainActivity.class).putExtra("open_status_overlay", true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent open = PendingIntent.getActivity(this, 7308, settings, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, 7309, new Intent(this, StatusOverlayService.class).setAction(STOP),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
        return b.setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle("祁昼状态栏正在陪着你")
                .setContentText("回 ChatGPT 可照常聊天 · 点此设置")
                .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
                .setVisibility(Notification.VISIBILITY_SECRET).addAction(new Notification.Action.Builder(
                    android.R.drawable.ic_menu_close_clear_cancel, "关闭状态栏", stop).build()).build();
    }

    private boolean interactive() {
        PowerManager power = (PowerManager)getSystemService(POWER_SERVICE);
        KeyguardManager lock = (KeyguardManager)getSystemService(KEYGUARD_SERVICE);
        return power.isInteractive() && !lock.isKeyguardLocked();
    }

    private void refreshVisibility() {
        if (overlay == null || !alive) return;
        try { overlay.update(snapshot, message, receivedAt); overlay.show(interactive() && inChat); }
        catch (Exception e) { stopSelf(); }
    }

    private final BroadcastReceiver screen = new BroadcastReceiver() {
        @Override public void onReceive(Context ctx, Intent intent) {
            if (!alive) return;
            if (!interactive()) {
                generation++; cancelRequest();
                synchronized (StatusOverlayService.this) { if (pending != null) pending.cancel(false); }
            } else kick();
            refreshVisibility();
        }
    };
    private final ConnectivityManager.NetworkCallback network = new ConnectivityManager.NetworkCallback() {
        @Override public void onAvailable(Network n) { main.post(() -> { if (alive) kick(); }); }
        @Override public void onLost(Network n) { main.post(() -> { if (alive) { message = "网络切换 · 正在重连"; refreshVisibility(); kick(); } }); }
    };

    private void bindSignals() {
        if (!screenRegistered) {
            IntentFilter filter = new IntentFilter();
            filter.addAction(Intent.ACTION_SCREEN_OFF); filter.addAction(Intent.ACTION_SCREEN_ON); filter.addAction(Intent.ACTION_USER_PRESENT);
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(screen, filter, Context.RECEIVER_NOT_EXPORTED);
            else registerReceiver(screen, filter);
            screenRegistered = true;
        }
        if (!networkRegistered) {
            connectivity = (ConnectivityManager)getSystemService(CONNECTIVITY_SERVICE);
            try {
                if (Build.VERSION.SDK_INT >= 24) connectivity.registerDefaultNetworkCallback(network);
                else connectivity.registerNetworkCallback(new NetworkRequest.Builder().build(), network);
                networkRegistered = true;
            } catch (Exception ignored) { /* Timed polling remains available. */ }
        }
    }

    private void kick() { if (alive && interactive() && inChat) schedule(0); }
    private synchronized void schedule(long delay) {
        if (!alive || worker.isShutdown() || !interactive() || !inChat || actionBusy) return;
        if (pending != null) pending.cancel(false);
        final int stamp = generation;
        final StatusOverlayConfig current = config;
        pending = worker.schedule(() -> poll(stamp, current), delay, TimeUnit.MILLISECONDS);
    }

    private void poll(int stamp, StatusOverlayConfig current) {
        if (!alive || stamp != generation || !interactive()) return;
        HttpURLConnection conn = null;
        JSONObject data = null;
        String tag = etag, result = "已同步";
        boolean success = false;
        try {
            conn = (HttpURLConnection)new URL(current.endpoint).openConnection(); request = conn;
            conn.setInstanceFollowRedirects(false);
            conn.setConnectTimeout(7000); conn.setReadTimeout(7000);
            conn.setUseCaches(false);
            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestProperty("X-Status-Token", current.token);
            if (!etag.isEmpty()) conn.setRequestProperty("If-None-Match", etag);
            int code = conn.getResponseCode();
            if (code == 304 && snapshot != null) success = true;
            else if (code == 200) {
                try (InputStream input = conn.getInputStream(); ByteArrayOutputStream body = new ByteArrayOutputStream()) {
                    byte[] buffer = new byte[2048]; int n;
                    while ((n = input.read(buffer)) != -1) {
                        if (body.size() + n > 16384) throw new IllegalArgumentException("size");
                        body.write(buffer, 0, n);
                    }
                    data = validSnapshot(new JSONObject(body.toString(StandardCharsets.UTF_8.name())));
                }
                String value = conn.getHeaderField("ETag");
                tag = value != null && value.length() < 200 ? value : "";
                success = true;
            } else if (code == 401 || code == 403) result = "地址验证失败 · 请检查设置";
            else if (code == 404) result = "未找到接口 · 请安装服务端 v5";
            else result = "服务暂不可用 · 自动重连中";
        } catch (Exception e) { result = "连接中断 · 自动重连中"; }
        finally { if (conn != null) conn.disconnect(); if (request == conn) request = null; }
        final JSONObject update = data;
        final boolean okay = success;
        final String nextTag = tag, nextMessage = result;
        main.post(() -> {
            if (!alive || stamp != generation) return;
            if (okay) {
                failures = 0; receivedAt = System.currentTimeMillis(); etag = nextTag;
                if (update != null) snapshot = update;
                if (update != null || receivedAt - persistedAt >= 300000) {
                    android.content.SharedPreferences.Editor edit = AppPrefs.get(this).edit().putLong(StatusOverlayConfig.CACHE_TIME, receivedAt);
                    if (update != null) edit.putString(StatusOverlayConfig.CACHE, update.toString());
                    edit.apply(); persistedAt = receivedAt;
                }
            } else failures = Math.min(10, failures + 1);
            message = nextMessage; refreshVisibility();
            schedule(okay ? 5000 : StatusOverlayConfig.retryDelay(failures));
        });
    }

    private static JSONObject validSnapshot(JSONObject json) throws Exception {
        for (String key : new String[]{"name", "mood", "activity", "physiology"}) {
            Object value = json.get(key);
            if (!(value instanceof String) || ((String)value).length() > 1000) throw new IllegalArgumentException("schema");
        }
        if (!(json.get("energy") instanceof Number) || !(json.get("bpm") instanceof Number)) throw new IllegalArgumentException("schema");
        return json;
    }

    private void cancelRequest() { HttpURLConnection conn = request; if (conn != null) conn.disconnect(); }

    private void interact(String action, String zone) {
        if (!alive || actionBusy || !inChat) return;
        if (snapshot == null || !snapshot.optBoolean("actionsAvailable", false)) {
            message = "请先安装服务器 v6 以启用互动"; refreshVisibility(); return;
        }
        actionBusy = true; overlay.setBusy(true);
        generation++; cancelRequest();
        synchronized (this) { if (pending != null) pending.cancel(false); }
        final int stamp = generation;
        final StatusOverlayConfig target = config;
        worker.execute(() -> {
            JSONObject result = null;
            String error = "未确认结果，请先刷新状态再决定是否重试";
            HttpURLConnection conn = null;
            try {
                JSONObject body = new JSONObject().put("action", action).put("zone", zone)
                        .put("intensity", 1).put("requestId", java.util.UUID.randomUUID().toString());
                byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
                conn = (HttpURLConnection)new URL(target.endpoint + "/interact").openConnection();
                conn.setInstanceFollowRedirects(false); conn.setUseCaches(false);
                conn.setRequestMethod("POST"); conn.setDoOutput(true);
                conn.setConnectTimeout(7000); conn.setReadTimeout(7000);
                // Streaming mode prevents HttpURLConnection replaying the POST on authentication/redirect.
                conn.setFixedLengthStreamingMode(bytes.length);
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setRequestProperty("X-Status-Token", target.token);
                try (java.io.OutputStream out = conn.getOutputStream()) { out.write(bytes); }
                int code = conn.getResponseCode();
                if (code == 200) {
                    try (InputStream input = conn.getInputStream(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                        byte[] buffer = new byte[2048]; int n;
                        while ((n = input.read(buffer)) != -1) {
                            if (output.size() + n > 16384) throw new IllegalArgumentException();
                            output.write(buffer, 0, n);
                        }
                        result = validSnapshot(new JSONObject(output.toString("UTF-8")));
                    }
                } else if (code == 404) error = "请先安装服务器 v6";
                else if (code == 429) error = "点得有点快，稍等再试";
                else if (code == 401) error = "地址验证失败，请检查设置";
            } catch (Exception ignored) { }
            finally { if (conn != null) conn.disconnect(); }
            final JSONObject updated = result;
            final String warning = error;
            main.post(() -> {
                if (!alive) return;
                actionBusy = false; overlay.setBusy(false);
                if (stamp != generation) { kick(); return; }
                if (updated != null) {
                    snapshot = updated; receivedAt = System.currentTimeMillis(); etag = "";
                    AppPrefs.get(this).edit().putString(StatusOverlayConfig.CACHE, updated.toString())
                            .putLong(StatusOverlayConfig.CACHE_TIME, receivedAt).apply();
                    message = "已同步";
                    overlay.showReaction(updated.optString("reaction", "已更新"));
                } else message = warning;
                refreshVisibility(); schedule(5000);
            });
        });
    }
    @Override public void onConfigurationChanged(Configuration configuration) {
        super.onConfigurationChanged(configuration);
        if (overlay != null) { try { overlay.resize(); } catch (Exception e) { stopSelf(); } }
    }
    @Override public void onDestroy() {
        alive = false; generation++; cancelRequest();
        synchronized (this) { if (pending != null) pending.cancel(true); }
        if (worker != null) worker.shutdownNow();
        main.removeCallbacksAndMessages(null);
        if (screenRegistered) unregisterReceiver(screen);
        if (networkRegistered) { try { connectivity.unregisterNetworkCallback(network); } catch (Exception ignored) { } }
        if (overlay != null) overlay.remove();
        stopForeground(true);
        super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent) { return null; }
}
