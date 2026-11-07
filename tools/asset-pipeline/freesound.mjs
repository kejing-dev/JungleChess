// Animal cries + water from freesound CC0 HQ previews (coordinator download, LICENSE.txt = one page URL per file).
// Per file: ffmpeg decode -> mono 22.05 kHz, high-pass, low-pass (cuts > 4–6 kHz spikes), FFT denoise; then pick the
// loudest short window (<= 0.8 s), back it up to the onset, cut the tail, fade, normalise to the event's peak.
// node freesound.mjs <dir with mp3 + LICENSE.txt> <assets/sounds> <sources json>
import fs from 'node:fs';
import path from 'node:path';
import { execFileSync } from 'node:child_process';

const [, , dir, out, srcJson] = process.argv;
const RATE = 22050;
// event -> [window s, low-pass Hz, peak dBFS, how many variants]
const SPEC = {
  rat: [0.45, 7000, -2, 3], cat: [0.7, 6000, -1.5, 3], dog: [0.5, 5000, -1.5, 3], wolf: [0.8, 4500, -2, 3],
  leopard: [0.7, 4000, -2, 3], tiger: [0.8, 4000, -2.5, 3], lion: [0.8, 4000, -2.5, 3], elephant: [0.8, 4500, -2.5, 3],
  splash: [0.55, 6000, -1.5, 3], drip: [0.3, 6000, -2, 3],
};
const urls = Object.fromEntries(fs.readFileSync(path.join(dir, 'LICENSE.txt'), 'utf8').split('\n')
  .map((l) => l.trim().split(/\s+/)).filter((p) => p.length === 2 && p[0].endsWith('.mp3')));

function decode(file, lp) {
  const raw = execFileSync('ffmpeg', ['-v', 'error', '-i', file, '-ac', '1', '-ar', String(RATE),
    '-af', `highpass=f=90,lowpass=f=${lp},afftdn=nf=-40`, '-f', 'f32le', '-'], { maxBuffer: 1 << 28 });
  return new Float32Array(raw.buffer, raw.byteOffset, raw.length / 4);
}
function wav(file, y) {
  const buf = Buffer.alloc(44 + y.length * 2);
  buf.write('RIFF', 0); buf.writeUInt32LE(36 + y.length * 2, 4); buf.write('WAVE', 8); buf.write('fmt ', 12);
  buf.writeUInt32LE(16, 16); buf.writeUInt16LE(1, 20); buf.writeUInt16LE(1, 22); buf.writeUInt32LE(RATE, 24);
  buf.writeUInt32LE(RATE * 2, 28); buf.writeUInt16LE(2, 32); buf.writeUInt16LE(16, 34); buf.write('data', 36); buf.writeUInt32LE(y.length * 2, 40);
  for (let i = 0; i < y.length; i++) buf.writeInt16LE(Math.round(Math.max(-1, Math.min(1, y[i])) * 32767), 44 + i * 2);
  fs.writeFileSync(file, buf);
}
const db = (x) => 20 * Math.log10(Math.max(x, 1e-9));

function cut(x, win, transient) {
  const F = Math.floor(RATE * 0.01), nf = Math.floor(x.length / F), env = new Float32Array(nf);
  for (let f = 0; f < nf; f++) { let s = 0; for (let i = f * F; i < (f + 1) * F; i++) s += x[i] * x[i]; env[f] = Math.sqrt(s / F); }
  const W = Math.max(1, Math.min(nf, Math.round(win / 0.01)));
  let best = 0, bestE = -1, acc = 0;
  if (transient) {
    // water: an isolated hit -- loud frame with a quiet 250 ms before it and a decay after (crest score)
    const gmax = Math.max(...env);
    for (let f = 25; f < nf - W; f++) {
      if (env[f] < gmax * 0.15) continue;
      const pre = Array.from(env.slice(f - 25, f - 3)).sort((p, q) => p - q)[11] || 1e-6;
      const post = Array.from(env.slice(f + W - 8, f + W)).reduce((m, v) => Math.max(m, v), 0) || 1e-6;
      const sc = env[f] / Math.max(pre, post);
      if (sc > bestE) { bestE = sc; best = Math.max(0, f - 2); }
    }
  } else for (let f = 0; f < nf; f++) { acc += env[f] ** 2; if (f >= W) acc -= env[f - W] ** 2; if (f >= W - 1 && acc > bestE) { bestE = acc; best = f - W + 1; } }
  const peak = Math.max(...env.slice(best, best + W));
  // back up to the onset (<= 120 ms) while still above 8 % of the window peak
  let a = best; while (a > 0 && best - a < 12 && env[a - 1] > peak * 0.08) a--;
  while (a < best + W - 5 && env[a] < peak * 0.05) a++; // no quiet lead-in: starts within 10 ms of the onset
  let b = Math.min(nf, a + W); while (b > a + 5 && env[b - 1] < peak * 0.03) b--; // drop a silent tail
  const sorted = Array.from(env).sort((p, q) => p - q), floor = sorted[Math.floor(sorted.length * 0.2)] || 1e-6;
  return { y: x.slice(a * F, b * F), snr: db(peak / floor) };
}

const sources = [];
for (const [ev, [win, lp, peakDb, count]] of Object.entries(SPEC)) {
  const files = fs.readdirSync(dir).filter((f) => f.startsWith(ev + '_') && f.endsWith('.mp3')).sort();
  const cands = files.map((f) => ({ f, ...cut(decode(path.join(dir, f), lp), win, ev === 'drip' || ev === 'splash') })).sort((p, q) => q.snr - p.snr).slice(0, count);
  const base = ev === 'splash' || ev === 'drip' ? ev : `cry_${ev}`;
  cands.forEach((c, v) => {
    let y = c.y;
    const pk = y.reduce((m, s) => Math.max(m, Math.abs(s)), 0) || 1, k = Math.pow(10, peakDb / 20) / pk;
    const fin = Math.floor(RATE * 0.006), fout = Math.min(Math.floor(RATE * 0.12), Math.floor(y.length / 3));
    y = y.map((s, i) => s * k * Math.min(1, i / fin) * Math.min(1, (y.length - i) / fout));
    // body too thin next to its peak (one click, then quiet): soft-limit (tanh) up to ~ -16 dBFS RMS, same ceiling
    const ceil = Math.pow(10, peakDb / 20), rms0 = Math.sqrt(y.reduce((s, q) => s + q * q, 0) / y.length);
    if (db(rms0) < -16) {
      const drive = Math.min(3, Math.pow(10, (-16 - db(rms0)) / 20));
      y = y.map((s) => ceil * Math.tanh((s * drive) / ceil) / Math.tanh(drive));
    }
    const name = v === 0 ? base : `${base}_${v + 1}`;
    wav(path.join(out, `${name}.wav`), y);
    const url = urls[c.f] || '';
    const m = url.match(/people\/([^/]+)\/sounds\/(\d+)/);
    const rms = db(Math.sqrt(y.reduce((s, q) => s + q * q, 0) / y.length));
    console.log(`${name.padEnd(16)} <- ${c.f.padEnd(22)} ${(y.length / RATE).toFixed(2)} s  snr ${c.snr.toFixed(0)} dB  rms ${rms.toFixed(1)} dBFS`);
    sources.push({ name, file: c.f, id: m?.[2], author: m?.[1], url, seconds: +(y.length / RATE).toFixed(2) });
  });
}
fs.writeFileSync(srcJson, JSON.stringify(sources, null, 1));
