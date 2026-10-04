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
        help.setText("保留原 MCP 地址，服务器升级到 v6 可恢复靠近、摸摸按钮。\n\n只在 ChatGPT 应用前台显示；切换应用自动隐藏，返回聊天再出现。需开启掌心窗无障碍权限，以识别当前应用。\n\n拖动标题移动，− 折叠，× 关闭。触碰会更新状态；聊天回复仍需你发消息。\n\n长期使用请允许通知、自启动，并把电池策略设为不限制。\n");
        box.addView(help);
        help.append("\n同一个 MCP 地址的状态由多端、多条聊天共用；聊天记忆不会因此互通。这里只识别 ChatGPT 应用，无法自动识别某条对话。\n");
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
            if (!ScreenshotService.ready()) {
                new AlertDialog.Builder(activity).setMessage("只在 ChatGPT 显示需要掌心窗无障碍服务识别应用窗口。请开启后返回，再点保存并开启。")
                    .setPositiveButton("去设置", (a, b) -> activity.startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)))
                    .setNegativeButton("稍后", null).show();
                return;
            }
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
