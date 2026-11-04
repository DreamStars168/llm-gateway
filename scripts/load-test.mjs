/**
 * SSE 并发压测工具
 *
 * 用法：
 *   node load-test.mjs --url http://127.0.0.1:18081/v1/messages \
 *                      --key sk-lg-demo-key-0001 \
 *                      --model claude-sonnet-4 \
 *                      --concurrency 200 --duration 30
 *
 * 指标：
 *   - 成功/失败数、错误分布
 *   - TTFT（首字节）
 *   - 总耗时
 *   - 网关附加延迟（用 X-Gateway-* 头或与基线对比）
 *   - 吞吐（req/s）
 */
import http from 'node:http';

const args = Object.fromEntries(
  process.argv.slice(2).reduce((acc, cur, i, arr) => {
    if (cur.startsWith('--')) acc.push([cur.slice(2), arr[i + 1]]);
    return acc;
  }, [])
);

const URL_ = args.url || 'http://127.0.0.1:18081/v1/messages';
const KEY = args.key || 'sk-lg-demo-key-0001';
const MODEL = args.model || 'claude-sonnet-4';
const CONCURRENCY = Number(args.concurrency || 50);
const DURATION_SEC = Number(args.duration || 20);
const MAX_TOKENS = Number(args.maxTokens || 64);

const u = new URL(URL_);
const agent = new http.Agent({ keepAlive: true, maxSockets: CONCURRENCY });

const stats = {
  ok: 0,
  fail: 0,
  errors: {},
  ttft: [],
  total: [],
  tokens: 0,
  bytes: 0,
  firstByteAt: []
};

function percentile(arr, p) {
  if (!arr.length) return 0;
  const sorted = [...arr].sort((a, b) => a - b);
  return sorted[Math.min(sorted.length - 1, Math.floor(sorted.length * p))];
}

function oneRequest() {
  return new Promise((resolve) => {
    const payload = JSON.stringify({
      model: MODEL,
      max_tokens: MAX_TOKENS,
      stream: true,
      messages: [{ role: 'user', content: 'ping' }]
    });

    const t0 = performance.now();
    let firstByte = null;
    let text = '';

    const req = http.request(
      {
        hostname: u.hostname,
        port: u.port,
        path: u.pathname,
        method: 'POST',
        agent,
        headers: {
          'Content-Type': 'application/json',
          'x-api-key': KEY,
          'Content-Length': Buffer.byteLength(payload)
        }
      },
      (res) => {
        if (res.statusCode !== 200) {
          stats.fail++;
          stats.errors[res.statusCode] = (stats.errors[res.statusCode] || 0) + 1;
          res.resume();
          res.on('end', resolve);
          return;
        }
        res.on('data', (chunk) => {
          if (firstByte === null) firstByte = performance.now();
          stats.bytes += chunk.length;
          text += chunk.toString('utf8');
        });
        res.on('end', () => {
          const tEnd = performance.now();
          stats.ok++;
          if (firstByte !== null) stats.ttft.push(firstByte - t0);
          stats.total.push(tEnd - t0);
          // 粗略统计输出 token（按 data 帧中的 text_delta 数）
          const m = text.match(/text_delta/g);
          if (m) stats.tokens += m.length;
          resolve();
        });
      }
    );

    req.on('error', (e) => {
      stats.fail++;
      const k = e.code || 'ERR';
      stats.errors[k] = (stats.errors[k] || 0) + 1;
      resolve();
    });

    req.setTimeout(60000, () => {
      req.destroy(new Error('TIMEOUT'));
    });

    req.write(payload);
    req.end();
  });
}

async function worker(deadline) {
  while (performance.now() < deadline) {
    await oneRequest();
  }
}

const start = performance.now();
const deadline = start + DURATION_SEC * 1000;

console.log(`压测开始：并发=${CONCURRENCY} 时长=${DURATION_SEC}s 模型=${MODEL} 目标=${URL_}`);

const workers = Array.from({ length: CONCURRENCY }, () => worker(deadline));
await Promise.all(workers);

const elapsed = (performance.now() - start) / 1000;
const totalReqs = stats.ok + stats.fail;

console.log('\n===== 结果 =====');
console.log(`总请求数      : ${totalReqs}`);
console.log(`成功          : ${stats.ok}`);
console.log(`失败          : ${stats.fail}`);
console.log(`吞吐          : ${(totalReqs / elapsed).toFixed(1)} req/s`);
console.log(`错误分布      : ${JSON.stringify(stats.errors)}`);
console.log(`数据量        : ${(stats.bytes / 1024 / 1024).toFixed(2)} MB`);
console.log('\n--- 延迟（ms）---');
console.log(`TTFT  P50/P95/P99 : ${percentile(stats.ttft, 0.5).toFixed(1)} / ${percentile(stats.ttft, 0.95).toFixed(1)} / ${percentile(stats.ttft, 0.99).toFixed(1)}`);
console.log(`总计  P50/P95/P99 : ${percentile(stats.total, 0.5).toFixed(1)} / ${percentile(stats.total, 0.95).toFixed(1)} / ${percentile(stats.total, 0.99).toFixed(1)}`);
console.log(`最小/最大耗时     : ${Math.min(...stats.total).toFixed(1)} / ${Math.max(...stats.total).toFixed(1)}`);

const outFile = process.env.OUT_JSON;
if (outFile) {
  const fs = await import('node:fs');
  fs.writeFileSync(outFile, JSON.stringify({
    concurrency: CONCURRENCY,
    durationSec: DURATION_SEC,
    model: MODEL,
    totalReqs,
    ok: stats.ok,
    fail: stats.fail,
    qps: totalReqs / elapsed,
    ttft: { p50: percentile(stats.ttft, .5), p95: percentile(stats.ttft, .95), p99: percentile(stats.ttft, .99) },
    total: { p50: percentile(stats.total, .5), p95: percentile(stats.total, .95), p99: percentile(stats.total, .99) },
    errors: stats.errors
  }, null, 2));
  console.log(`\n结果已写入 ${outFile}`);
}
