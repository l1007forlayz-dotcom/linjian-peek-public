package dev.linjian.peek;

import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import java.util.List;

/** Checks application window ownership only; no message text is collected. */
final class StatusOverlayScope {
    static boolean chatGptInFront() {
        ScreenshotService service = ScreenshotService.getInstance();
        if (service == null) return false;
        List<AccessibilityWindowInfo> windows = null;
        try {
            windows = service.getWindows();
            int top = Integer.MIN_VALUE;
            String owner = "";
            for (AccessibilityWindowInfo window : windows) {
                int type = window.getType();
                // Input methods and our own overlay do not become the foreground app.
                if (type == AccessibilityWindowInfo.TYPE_INPUT_METHOD || type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY) continue;
                AccessibilityNodeInfo root = window.getRoot();
                if (root == null) continue;
                String pkg;
                try { pkg = root.getPackageName() == null ? "" : root.getPackageName().toString(); }
                finally { root.recycle(); }
                if ("dev.linjian.peek".equals(pkg) && type != AccessibilityWindowInfo.TYPE_APPLICATION) continue;
                if (type == AccessibilityWindowInfo.TYPE_SYSTEM && (window.isActive() || window.isFocused())) return false;
                if (type == AccessibilityWindowInfo.TYPE_APPLICATION && window.getLayer() > top) {
                    owner = pkg; top = window.getLayer();
                }
            }
            return "com.openai.chatgpt".equals(owner);
        } catch (Exception ignored) { return false; }
        finally { if (windows != null) for (AccessibilityWindowInfo window : windows) window.recycle(); }
    }
}
