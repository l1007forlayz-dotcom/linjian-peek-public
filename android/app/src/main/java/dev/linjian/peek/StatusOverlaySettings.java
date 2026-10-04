package dev.linjian.peek;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.provider.Settings;
import android.text.InputType;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

final class StatusOverlaySettings {
    static void show(Activity activity) {
        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = (int)(20 * activity.getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad / 2, pad, 0);
        TextView help = new TextView(activity);
        help.setText("粘贴「状态栏1」的完整 MCP 地址。先在服务器安装 v5 升级脚本。\n\n开启后可回 ChatGPT 正常聊天；拖动标题移动，点 − 收成小条，点 × 关闭。亮屏约 5 秒同步一次，熄屏暂停。地址仅保存在本机。\n\n长期使用：给掌心窗允许悬浮窗、通知、自启动，并把电池策略设为不限制。强行停止后需手动开启。\n");
        box.addView(help);
        EditText input = new EditText(activity);
        input.setSingleLine(true);
        input.setHint("https://你的域名/mcp/私密密钥");
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        input.setText(AppPrefs.get(activity).getString(StatusOverlayConfig.ADDRESS, ""));
        box.addView(input);
        AlertDialog dialog = new AlertDialog.Builder(activity).setTitle("祁昼 · 悬浮状态栏")
                .setView(box).setPositiveButton("保存并开启", null)
                .setNegativeButton("取消", null).setNeutralButton("关闭状态栏", (d, w) -> {
                    AppPrefs.get(activity).edit().putBoolean(StatusOverlayConfig.ENABLED, false).apply();
                    activity.stopService(new Intent(activity, StatusOverlayService.class));
                }).create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            StatusOverlayConfig config;
            try { config = new StatusOverlayConfig(input.getText().toString()); }
            catch (IllegalArgumentException e) { input.setError(e.getMessage()); return; }
            if (!Settings.canDrawOverlays(activity)) {
                new AlertDialog.Builder(activity).setMessage("请先允许掌心窗显示悬浮窗，返回后再次点「保存并开启」。")
                    .setPositiveButton("去授权", (a, b) -> activity.startActivity(new Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + activity.getPackageName()))))
                    .setNegativeButton("稍后", null).show();
                return;
            }
            boolean changed = !config.address.equals(AppPrefs.get(activity).getString(StatusOverlayConfig.ADDRESS, ""));
            android.content.SharedPreferences.Editor edit = AppPrefs.get(activity).edit()
                    .putString(StatusOverlayConfig.ADDRESS, config.address).putBoolean(StatusOverlayConfig.ENABLED, true);
            if (changed) edit.remove(StatusOverlayConfig.CACHE).remove(StatusOverlayConfig.CACHE_TIME);
            edit.apply();
            if (StatusOverlayService.restore(activity)) {
                dialog.dismiss();
                Toast.makeText(activity, "已开启；返回聊天即可", Toast.LENGTH_SHORT).show();
            } else input.setError("没有启动成功，请检查悬浮窗和后台运行权限后重试");
        }));
        dialog.show();
    }
}
