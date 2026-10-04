import assert from 'node:assert/strict';
import { mkdtemp, writeFile, readFile, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { spawn } from 'node:child_process';
import { setTimeout as delay } from 'node:timers/promises';

const project = path.resolve(process.argv[2]);
const temporary = await mkdtemp(path.join(tmpdir(), 'qizhou-api-'));
const statePath = path.join(temporary, 'state.json');
const token = 'test-overlay-private-token-123456';
const port = 24000 + process.pid % 15000;
const base = `http://127.0.0.1:${port}`;
const endpoint = `${base}/mcp/${token}/status`;
const headers = { 'X-Status-Token': token };
const state = { version: 1, name: '祁昼', online: true, mood: '平静', activity: '测试同步', physiology: '放松', energy: 60, bpm: 76, updatedAt: new Date().toISOString(), internalSecret: 'must-not-leak' };
await writeFile(statePath, JSON.stringify(state));
const original = await readFile(statePath, 'utf8');
let logs = '';
const child = spawn(process.execPath, ['src/server.js'], {
    cwd: project, env: { ...process.env, PORT: String(port), MCP_PATH_SECRET: token, STATE_FILE: statePath, NODE_ENV: 'production' }, stdio: ['ignore', 'pipe', 'pipe']
});
child.stdout.on('data', data => logs += data);
child.stderr.on('data', data => logs += data);
try {
    let healthy = false;
    for (let i = 0; i < 40; i++) {
        try { const r = await fetch(base + '/health'); healthy = ['v5', 'v6'].includes((await r.json()).overlayApi); } catch {}
        if (healthy) break;
        await delay(100);
    }
    assert.ok(healthy, 'new server starts with v5 route');
    assert.equal((await fetch(endpoint)).status, 401);
    assert.equal((await fetch(endpoint, { headers: { 'X-Status-Token': token.replace('1', '2') } })).status, 401);
    const response = await fetch(endpoint, { headers });
    assert.equal(response.status, 200);
    assert.equal(response.headers.get('cache-control'), 'no-store');
    const etag = response.headers.get('etag');
    const payload = await response.json();
    assert.equal(payload.mood, '平静');
    assert.equal(payload.internalSecret, undefined);
    const unchanged = await fetch(endpoint, { headers: { ...headers, 'If-None-Match': etag } });
    assert.equal(unchanged.status, 304);
    assert.equal(await unchanged.text(), '');
    await Promise.all(Array.from({ length: 25 }, async () => assert.equal((await fetch(endpoint, { headers })).status, 200)));
    assert.equal(await readFile(statePath, 'utf8'), original, 'polls do not change state');
    await writeFile(statePath, JSON.stringify({ ...state, mood: '更新完成' }));
    const changed = await fetch(endpoint, { headers: { ...headers, 'If-None-Match': etag } });
    assert.equal(changed.status, 200);
    assert.notEqual(changed.headers.get('etag'), etag);
    assert.equal((await changed.json()).mood, '更新完成');
    await writeFile(statePath, '{broken');
    assert.equal((await fetch(endpoint, { headers })).status, 503);
    assert.equal(await readFile(statePath, 'utf8'), '{broken', 'failed reads do not overwrite data');
    await writeFile(statePath, original);
    assert.equal((await fetch(endpoint, { headers })).status, 200, 'recovers when state becomes readable');
    if (payload.actionsAvailable) {
        assert.equal(payload.scope, 'shared');
        assert.ok(payload.touchZones.length > 0);
        const actionUrl = endpoint + '/interact';
        const actionBody = { action: 'touch', zone: payload.touchZones[0], intensity: 1, requestId: 'test-interaction-request-001' };
        const post = async (body, auth = headers) => fetch(actionUrl, {
            method: 'POST', headers: { ...auth, 'Content-Type': 'application/json' }, body: JSON.stringify(body)
        });
        assert.equal((await post(actionBody, {})).status, 401);
        assert.equal((await post({ ...actionBody, zone: 'invalid-zone' })).status, 400);
        assert.equal((await post({ ...actionBody, intensity: 99 })).status, 400);
        const first = await post(actionBody);
        assert.equal(first.status, 200);
        const result = await first.json();
        assert.equal(result.lastTouch, actionBody.zone);
        assert.equal(result.scope, 'shared');
        const saved = await readFile(statePath, 'utf8');
        const replay = await post(actionBody);
        assert.equal(replay.status, 200);
        assert.deepEqual(await replay.json(), result);
        assert.equal(await readFile(statePath, 'utf8'), saved, 'same action ID is not performed twice');
        assert.equal((await post({ ...actionBody, action: 'approach' })).status, 409);
        assert.equal((await post({ ...actionBody, requestId: 'test-interaction-request-002' })).status, 429);
        console.log('PASS: interactive controls, authentication, invalid input rejection, deduplication and shared-state labeling');
    }
    const init = await fetch(`${base}/mcp/${token}`, {
        method: 'POST', headers: { 'Content-Type': 'application/json', Accept: 'application/json, text/event-stream' },
        body: JSON.stringify({ jsonrpc: '2.0', id: 1, method: 'initialize', params: { protocolVersion: '2025-03-26', capabilities: {}, clientInfo: { name: 'overlay-test', version: '1' } } })
    });
    assert.equal(init.status, 200, 'original MCP endpoint still initializes');
    assert.match(await init.text(), /protocolVersion/);
    assert.ok(!logs.includes(token), 'logs do not contain private token');
    console.log('PASS: authentication, read-only polling, 304, updates, corrupt-state recovery, MCP initialization, secret-free logs');
} finally {
    child.kill('SIGTERM');
    await Promise.race([new Promise(resolve => child.once('exit', resolve)), delay(2000)]);
    if (child.exitCode === null) child.kill('SIGKILL');
    await rm(temporary, { recursive: true, force: true });
}
