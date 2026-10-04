#!/usr/bin/env bash
set -Eeuo pipefail

# Adds only a read-only endpoint to an existing overlay v5 installation.
PROJECT_DIR="${1:-}"
if [[ -z "$PROJECT_DIR" ]]; then
  for candidate in "$PWD" "$PWD/qizhou-status-widget" "$HOME/qizhou-status-widget" /home/ubuntu/qizhou-status-widget /root/qizhou-status-widget; do
    if [[ -f "$candidate/src/server.js" && -f "$candidate/public/status-widget.html" ]]; then PROJECT_DIR="$candidate"; break; fi
  done
fi
if [[ -z "$PROJECT_DIR" || ! -f "$PROJECT_DIR/src/server.js" ]]; then
  echo "未找到项目。用法：bash $0 /实际路径/qizhou-status-widget" >&2; exit 1
fi
MODE="${2:-install}"
if [[ "$MODE" != install && "$MODE" != --rollback ]]; then echo "不支持的参数" >&2; exit 1; fi

BACKUP="$(python3 - "$PROJECT_DIR" "$MODE" <<'PY'
from pathlib import Path
import datetime, os, shutil, subprocess, sys, tempfile
project = Path(sys.argv[1]).resolve()
target = project / 'src/server.js'
text = target.read_text(encoding='utf-8')
marker = '// QIZHOU_ANDROID_OVERLAY_V6'
mode = sys.argv[2]
backup = None
if mode == '--rollback':
    options = sorted(target.parent.glob('server.js.backup-overlay-v6-*'))
    if not options or marker not in text:
        raise SystemExit('没有可回退的 v6 升级；未修改。')
    backup = options[-1]
    replacement = backup.read_text(encoding='utf-8')
    if marker in replacement or 'QIZHOU_ANDROID_OVERLAY_V5' not in replacement:
        raise SystemExit('备份不是预期的 v5 文件；未修改。')
elif marker in text:
    print('UNCHANGED'); raise SystemExit(0)
else:
    required = ['QIZHOU_ANDROID_OVERLAY_V5', 'const MCP_PATH =', 'const PATH_SECRET =', 'const STATE_FILE =',
                'import crypto from "node:crypto";', 'readFileSync', '"show_dynamic_status"']
    if not all(item in text for item in required):
        raise SystemExit('当前服务不是预期的悬浮 v5 版本。请先安装 v5；未修改。')
    anchor = 'app.post(MCP_PATH, async (req, res) => {'
    if text.count(anchor) != 1:
        raise SystemExit('服务结构不匹配；未修改。')
    route = r'''// QIZHOU_ANDROID_OVERLAY_V6: explicit authenticated interactions.
const overlayActionCache = new Map();
let overlayLastAction = 0;
app.post(MCP_PATH + "/status/interact", (req, res) => {
  res.set("Cache-Control", "no-store");
  const actual = Buffer.from(req.get("X-Status-Token") || "", "utf8");
  const expected = Buffer.from(PATH_SECRET, "utf8");
  if (actual.length !== expected.length || !crypto.timingSafeEqual(actual, expected)) {
    return res.status(401).json({ error: "Unauthorized" });
  }
  const { action, zone, intensity = 1, requestId } = req.body || {};
  if (!["touch", "approach"].includes(action)
      || typeof requestId !== "string" || !/^[A-Za-z0-9-]{16,80}$/.test(requestId)
      || !Number.isInteger(intensity) || intensity < 1 || intensity > 3
      || (action === "touch" && !TOUCH_ZONES.includes(zone))) {
    return res.status(400).json({ error: "Invalid interaction" });
  }
  const now = Date.now();
  for (const [key, item] of overlayActionCache) {
    if (now - item.time > 300000) overlayActionCache.delete(key);
  }
  const signature = JSON.stringify([action, zone || "", intensity]);
  const previous = overlayActionCache.get(requestId);
  if (previous) {
    if (previous.signature !== signature) return res.status(409).json({ error: "Request ID conflict" });
    return res.json(previous.snapshot);
  }
  if (now - overlayLastAction < 250) return res.status(429).json({ error: "Please wait" });
  try {
    const state = action === "touch" ? store.touch(zone, intensity) : store.approach();
    const snapshot = { ...state, touchZones: TOUCH_ZONES, actionsAvailable: true, scope: "shared" };
    overlayLastAction = now;
    overlayActionCache.set(requestId, { signature, snapshot, time: now });
    if (overlayActionCache.size > 256) overlayActionCache.delete(overlayActionCache.keys().next().value);
    return res.json(snapshot);
  } catch (_error) {
    return res.status(503).json({ error: "Interaction unavailable" });
  }
});

'''
    replacement = text.replace(anchor, route + anchor, 1)
    body_anchor = '    const body = JSON.stringify(snapshot);'
    if replacement.count(body_anchor) != 1: raise SystemExit('读取接口结构不匹配；未修改。')
    replacement = replacement.replace(body_anchor,
        '    snapshot.touchZones = TOUCH_ZONES;\n'
        '    snapshot.actionsAvailable = true;\n'
        '    snapshot.scope = "shared";\n' + body_anchor, 1)
    health = 'overlayApi: "v5"'
    if replacement.count(health) != 1: raise SystemExit('健康检查结构不匹配；未修改。')
    replacement = replacement.replace(health, 'overlayApi: "v6"', 1)

subprocess.run(['node', '--check', '--input-type=module'], input=replacement, text=True, check=True)
if mode != '--rollback':
    stamp = datetime.datetime.now().strftime('%Y%m%d-%H%M%S-%f')
    backup = target.with_name('server.js.backup-overlay-v6-' + stamp)
    shutil.copy2(target, backup)
    original = target.stat()
    if os.geteuid() == 0: os.chown(backup, original.st_uid, original.st_gid)
fd, temporary = tempfile.mkstemp(prefix='.overlay-v6-', dir=target.parent)
try:
    with os.fdopen(fd, 'w', encoding='utf-8') as out:
        out.write(replacement); out.flush(); os.fsync(out.fileno())
    shutil.copystat(target, temporary)
    original = target.stat()
    if os.geteuid() == 0: os.chown(temporary, original.st_uid, original.st_gid)
    os.replace(temporary, target)
finally:
    if os.path.exists(temporary): os.unlink(temporary)
print(backup)
PY
)"

if [[ "$BACKUP" == UNCHANGED ]]; then echo "v6 已安装，继续检查运行中的服务。"; else echo "文件检查通过，备份：$BACKUP"; fi
if [[ "${QIZHOU_SKIP_RESTART:-0}" == 1 ]]; then echo "已跳过服务重启（本地验证模式）。"; exit 0; fi

PM2_BIN="$(command -v pm2 || true)"
if [[ -z "$PM2_BIN" ]]; then
  echo "没有找到 pm2。文件已升级，请用原账户运行：pm2 restart qizhou-status --update-env" >&2; exit 1
fi
SERVICE_USER="${SUDO_USER:-}"
if [[ -z "$SERVICE_USER" || "$SERVICE_USER" == root ]]; then SERVICE_USER="$(stat -c '%U' "$PROJECT_DIR")"; fi
restart_service() {
  if [[ "$(id -un)" == root && "$SERVICE_USER" != root ]]; then
    SERVICE_HOME="$(getent passwd "$SERVICE_USER" | cut -d: -f6)"
    [[ -n "$SERVICE_HOME" ]] || return 1
    runuser -u "$SERVICE_USER" -- env HOME="$SERVICE_HOME" PM2_HOME="$SERVICE_HOME/.pm2" "$PM2_BIN" restart qizhou-status --update-env
  else "$PM2_BIN" restart qizhou-status --update-env; fi
}
health_check() {
  local result
  for _attempt in {1..15}; do
    result="$(curl -fsS --max-time 2 "${QIZHOU_HEALTH_URL:-http://127.0.0.1:2091/health}" 2>/dev/null || true)"
    if [[ "$MODE" == --rollback ]]; then
      if [[ "$result" == *'"ok":true'* && "$result" == *'"overlayApi":"v5"'* ]]; then return 0; fi
    elif [[ "$result" == *'"overlayApi":"v6"'* && "$result" == *'"ok":true'* ]]; then return 0; fi
    sleep 1
  done
  return 1
}
if restart_service && health_check; then
  if [[ "$MODE" == --rollback ]]; then echo "已回退到 v5。"; else
    echo "v6 已运行。隧道和原 MCP 地址保持原值。"
    echo "在掌心窗 → 设置 → 权限与运行 → 祁昼悬浮状态栏，保留原 MCP 地址，开启无障碍权限后返回 ChatGPT。"
  fi
else
  echo "新服务未通过健康检查。" >&2
  if [[ "$MODE" != --rollback && "$BACKUP" != UNCHANGED ]]; then
    echo "正在恢复本次升级前的文件。" >&2
    python3 - "$BACKUP" "$PROJECT_DIR/src/server.js" <<'PY'
from pathlib import Path
import os, shutil, sys, tempfile
source, target = map(Path, sys.argv[1:])
fd, name = tempfile.mkstemp(prefix='.overlay-restore-', dir=target.parent)
os.close(fd)
try:
    shutil.copy2(source, name)
    stat = source.stat()
    if os.geteuid() == 0: os.chown(name, stat.st_uid, stat.st_gid)
    os.replace(name, target)
finally:
    if os.path.exists(name): os.unlink(name)
PY
    restart_service || true
  fi
  echo "请用原账户查看：pm2 logs qizhou-status --lines 50 --nostream" >&2; exit 1
fi
