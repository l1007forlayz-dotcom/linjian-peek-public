#!/usr/bin/env bash
set -Eeuo pipefail

# Adds only a read-only endpoint to an existing compact v4 installation.
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
marker = '// QIZHOU_ANDROID_OVERLAY_V5'
mode = sys.argv[2]
backup = None
if mode == '--rollback':
    options = sorted(target.parent.glob('server.js.backup-overlay-v5-*'))
    if not options or marker not in text:
        raise SystemExit('没有可回退的 v5 升级；未修改。')
    backup = options[-1]
    replacement = backup.read_text(encoding='utf-8')
    if marker in replacement or 'QIZHOU_COMPACT_V4' not in replacement:
        raise SystemExit('备份不是预期的 v4 文件；未修改。')
elif marker in text:
    print('UNCHANGED'); raise SystemExit(0)
else:
    required = ['QIZHOU_COMPACT_V4', 'const MCP_PATH =', 'const PATH_SECRET =', 'const STATE_FILE =',
                'import crypto from "node:crypto";', 'readFileSync', '"show_dynamic_status"']
    if not all(item in text for item in required):
        raise SystemExit('当前服务不是预期的紧凑 v4 版本。请先安装 v4；未修改。')
    anchor = 'app.post(MCP_PATH, async (req, res) => {'
    if text.count(anchor) != 1:
        raise SystemExit('服务结构不匹配；未修改。')
    route = r'''// QIZHOU_ANDROID_OVERLAY_V5: authenticated reads never update the store.
app.get(MCP_PATH + "/status", (req, res) => {
  res.set("Cache-Control", "no-store");
  res.set("X-Content-Type-Options", "nosniff");
  const supplied = req.get("X-Status-Token");
  const expected = Buffer.from(PATH_SECRET, "utf8");
  const actual = Buffer.from(typeof supplied === "string" ? supplied : "", "utf8");
  if (actual.length !== expected.length || !crypto.timingSafeEqual(actual, expected)) {
    return res.status(401).json({ error: "Unauthorized" });
  }
  try {
    const parsed = JSON.parse(readFileSync(STATE_FILE, "utf8"));
    const data = parsed?.snapshot ?? parsed?.state ?? parsed;
    if (!data || typeof data !== "object") throw new Error("schema");
    const snapshot = {};
    for (const key of ["name", "mood", "activity", "physiology"]) {
      if (typeof data[key] !== "string" || data[key].length > 1000) throw new Error("schema");
      snapshot[key] = data[key];
    }
    for (const key of ["energy", "bpm"]) {
      if (typeof data[key] !== "number" || !Number.isFinite(data[key])) throw new Error("schema");
      snapshot[key] = data[key];
    }
    snapshot.version = 1;
    snapshot.online = data.online === true;
    for (const key of ["lastTouch", "reaction", "updatedAt"]) {
      if (typeof data[key] === "string" && data[key].length <= 1000) snapshot[key] = data[key];
    }
    const body = JSON.stringify(snapshot);
    const etag = '"' + crypto.createHash("sha256").update(body).digest("hex") + '"';
    res.set("ETag", etag);
    if (req.get("If-None-Match") === etag) return res.status(304).end();
    return res.type("application/json").send(body);
  } catch (_error) {
    // No synthetic updates, no secret/state logging, and no side effects on failed reads.
    return res.status(503).json({ error: "Status temporarily unavailable" });
  }
});

'''
    replacement = text.replace(anchor, route + anchor, 1)
    # This public health flag confirms the new process actually loaded the route.
    health = 'res.json({ ok: true, service: "qizhou-dynamic-status", version: "0.1.0" });'
    if replacement.count(health) != 1: raise SystemExit('健康检查结构不匹配；未修改。')
    replacement = replacement.replace(health, 'res.json({ ok: true, service: "qizhou-dynamic-status", version: "0.1.0", overlayApi: "v5" });', 1)

subprocess.run(['node', '--check', '--input-type=module'], input=replacement, text=True, check=True)
if mode != '--rollback':
    stamp = datetime.datetime.now().strftime('%Y%m%d-%H%M%S-%f')
    backup = target.with_name('server.js.backup-overlay-v5-' + stamp)
    shutil.copy2(target, backup)
    original = target.stat()
    if os.geteuid() == 0: os.chown(backup, original.st_uid, original.st_gid)
fd, temporary = tempfile.mkstemp(prefix='.overlay-v5-', dir=target.parent)
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

if [[ "$BACKUP" == UNCHANGED ]]; then echo "v5 已安装，继续检查运行中的服务。"; else echo "文件检查通过，备份：$BACKUP"; fi
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
      if [[ "$result" == *'"ok":true'* && "$result" != *'"overlayApi"'* ]]; then return 0; fi
    elif [[ "$result" == *'"overlayApi":"v5"'* && "$result" == *'"ok":true'* ]]; then return 0; fi
    sleep 1
  done
  return 1
}
if restart_service && health_check; then
  if [[ "$MODE" == --rollback ]]; then echo "已回退到 v4。"; else
    echo "v5 已运行。隧道和原 MCP 地址保持原值。"
    echo "在掌心窗 → 设置 → 权限与运行 → 祁昼悬浮状态栏，粘贴原来完整的 HTTPS MCP 地址并开启。"
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
