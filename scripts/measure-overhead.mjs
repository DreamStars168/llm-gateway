/**
 * 网关附加延迟测量
 *
 * 思路：同一并发下分别压「直连上游」与「经网关」，
 * 用 TTFT 差值近似网关附加延迟（上游处理时间被抵消）。
 */
import http from 'node:http';

const args = Object.fromEntries(
  process.argv.slice(2).reduce((acc, cur, i, arr) => {
    if (cur.startsWith('--')) acc.push([cur.slice(2), arr[i + 1]]);
    return acc;
  }, [])
);

const CONCURRENCY = Number(args.concurrency || 50);
const SAMPLES = Number(args.samples || 300);
const MODEL = args.model || 'claude-sonnet-4';

const agent = new http.Agent({ keepAlive: true, maxSockets: CONCURRENCY });

function once(target, withKey) {
  return new Promise((resolve) => {
    const payload = JSON.stringify({
      model: MODEL, max_tokens: 16, stream: true,
      messages: [{ role: 'user', content: 'ping' }]
    });
    const u = new URL(target);
    const t0 = performance.now();
    let ttft = null;

    const headers = { 'Content-Type': 'application/json', 'Content-Length': Buffer.byteLength(payload) };
    if (withKey) headers['x-api-key'] = withKey;

    const req = http.request({
      hostname: u.hostname, port: u.port, path: u.pathname,
      method: 'POST', agent, headers
    }, (res) => {
      res.on('data', () => { if (ttft === null) ttft = performance.now() - t0; });
      res.on('end', () => resolve(ttft));
    });
    req.on('error', () => resolve(null));
    req.setTimeout(30000, () => req.destroy());
    req.write(payload);
    req.end();
  });
}

function pct(arr, p) {
  if (!arr.length) return 0;
  const s = [...arr].sort((a, b) => a - b);
  return s[Math.min(s.length - 1, Math.floor(s.length * p))];
}

async function measure(target, withKey, label) {
  const results = [];
  let idx = 0;
  async function worker() {
    while (idx < SAMPLES) {
      idx++;
      const r = await once(target, withKey);
      if (r !== null) results.push(r);
    }
  }
  await Promise.all(Array.from({ length: CONCURRENCY }, worker));
  return {
    label,
    count: results.length,
    p50: pct(results, 0.5),
    p95: pct(results, 0.95),
    p99: pct(results, 0.99)
  };
}

const mode = args.mode || 'both';

if (mode === 'direct') {
  const r = await measure('http://127.0.0.1:9000/v1/messages', null, '直连上游');
  console.log(JSON.stringify(r));
  process.exit(0);
}
if (mode === 'gateway') {
  const r = await measure('http://127.0.0.1:18081/v1/messages', 'sk-lg-demo-key-0001', '经网关');
  console.log(JSON.stringify(r));
  process.exit(0);
}

// both：两轮之间强制间隔，避免上游积压影响第二轮
const direct = await measure('http://127.0.0.1:9000/v1/messages', null, '直连上游');
await new Promise(r => setTimeout(r, 3000));
const viaGw = await measure('http://127.0.0.1:18081/v1/messages', 'sk-lg-demo-key-0001', '经网关');

console.log('\n===== 网关附加延迟 =====');
console.log(`并发 ${CONCURRENCY}，各 ${SAMPLES} 样本\n`);
console.log(`${'路径'.padEnd(12)} ${'P50'.padStart(9)} ${'P95'.padStart(9)} ${'P99'.padStart(9)}`);
for (const r of [direct, viaGw]) {
  console.log(`${r.label.padEnd(12)} ${r.p50.toFixed(1).padStart(9)} ${r.p95.toFixed(1).padStart(9)} ${r.p99.toFixed(1).padStart(9)}`);
}
const added = {
  p50: viaGw.p50 - direct.p50,
  p95: viaGw.p95 - direct.p95,
  p99: viaGw.p99 - direct.p99
};
console.log(`\n${'附加延迟'.padEnd(10)} ${added.p50.toFixed(1).padStart(9)} ${added.p95.toFixed(1).padStart(9)} ${added.p99.toFixed(1).padStart(9)}`);

const fs = await import('node:fs');
if (process.env.OUT_JSON) {
  fs.writeFileSync(process.env.OUT_JSON, JSON.stringify({ concurrency: CONCURRENCY, samples: SAMPLES, direct, viaGateway: viaGw, added }, null, 2));
  console.log(`\n已写入 ${process.env.OUT_JSON}`);
}
