// Event sounds for Jungle Chess, synthesised (no samples, no ambience — B24). 16-bit mono WAV, 22.05 kHz.
// Wood / water / drum timbres, soft attacks, low-passed. Peaks normalised to PEAK_DB.
// node sounds.mjs <outDir>          writes the files and prints peak / RMS of each
import fs from 'node:fs';
import path from 'node:path';

const RATE = 22050, PEAK_DB = -1.2;
// gentle tanh drive before normalising: louder body (RMS) for short wooden knocks (B20: board sounds were too quiet elsewhere)
const DRIVE = 2.2;
const out = process.argv[2];
fs.mkdirSync(out, { recursive: true });
let seed = 11; const rnd = () => ((seed = (seed * 16807) % 2147483647) / 2147483647) * 2 - 1;

const N = (s) => Math.floor(s * RATE);
const silence = (s) => new Float32Array(N(s));
function mix(...parts) { // [buffer, offsetSeconds, gain]
  const len = Math.max(...parts.map(([b, o]) => b.length + N(o)));
  const y = new Float32Array(len);
  for (const [b, o, g = 1] of parts) for (let i = 0; i < b.length; i++) y[i + N(o)] += b[i] * g;
  return y;
}
function lowpass(x, f) { const a = 1 - Math.exp(-2 * Math.PI * f / RATE); let y = 0; return x.map((v) => (y += a * (v - y))); }
function highpass(x, f) { const lp = lowpass(x, f); return x.map((v, i) => v - lp[i]); }
/** a struck wooden bar: a few inharmonic modes with fast decay */
function wood(f, dur = 0.18, decay = 28, modes = [[1, 1], [2.76, 0.35], [5.4, 0.12]]) {
  const y = new Float32Array(N(dur));
  for (let i = 0; i < y.length; i++) {
    const t = i / RATE; const att = Math.min(1, t / 0.002);
    let v = 0; for (const [m, g] of modes) v += g * Math.sin(2 * Math.PI * f * m * t) * Math.exp(-decay * m * 0.6 * t);
    y[i] = v * att;
  }
  return y;
}
function noise(dur, env) { const y = new Float32Array(N(dur)); for (let i = 0; i < y.length; i++) y[i] = rnd() * env(i / RATE); return y; }
function tone(f0, f1, dur, env, wave = Math.sin) {
  const y = new Float32Array(N(dur)); let ph = 0;
  for (let i = 0; i < y.length; i++) { const t = i / RATE; const f = f0 + (f1 - f0) * (t / dur); ph += 2 * Math.PI * f / RATE; y[i] = wave(ph) * env(t); }
  return y;
}
const thud = (f = 90, dur = 0.25) => lowpass(mix([tone(f * 1.6, f, dur, (t) => Math.exp(-14 * t) * Math.min(1, t / 0.004)), 0, 1], [lowpass(noise(0.05, (t) => Math.exp(-60 * t)), 800), 0, 0.4]), 1200);
function normalise(x0, db = PEAK_DB) {
  const p0 = x0.reduce((m, v) => Math.max(m, Math.abs(v)), 0) || 1;
  const x = x0.map((v) => Math.tanh((v / p0) * DRIVE));
  const peak = x.reduce((m, v) => Math.max(m, Math.abs(v)), 0) || 1;
  const k = Math.pow(10, db / 20) / peak;
  const fade = N(0.01);
  return x.map((v, i) => v * k * Math.min(1, (x.length - i) / fade));
}
function write(name, x) {
  const y = normalise(x);
  const buf = Buffer.alloc(44 + y.length * 2);
  buf.write('RIFF', 0); buf.writeUInt32LE(36 + y.length * 2, 4); buf.write('WAVE', 8); buf.write('fmt ', 12);
  buf.writeUInt32LE(16, 16); buf.writeUInt16LE(1, 20); buf.writeUInt16LE(1, 22); buf.writeUInt32LE(RATE, 24);
  buf.writeUInt32LE(RATE * 2, 28); buf.writeUInt16LE(2, 32); buf.writeUInt16LE(16, 34); buf.write('data', 36); buf.writeUInt32LE(y.length * 2, 40);
  for (let i = 0; i < y.length; i++) buf.writeInt16LE(Math.round(Math.max(-1, Math.min(1, y[i])) * 32767), 44 + i * 2);
  fs.writeFileSync(path.join(out, `${name}.wav`), buf);
  const peak = 20 * Math.log10(y.reduce((m, v) => Math.max(m, Math.abs(v)), 0));
  const rms = 20 * Math.log10(Math.sqrt(y.reduce((s, v) => s + v * v, 0) / y.length));
  console.log(`${name.padEnd(16)} ${(y.length / RATE).toFixed(2)} s  peak ${peak.toFixed(1)} dBFS  rms ${rms.toFixed(1)} dBFS`);
}

// select: silent by default (D51), no file
// v0.1.3: place comes from Kenney CC0 (cc0sounds.mjs)
// v0.1.3: place_2 comes from Kenney CC0 (cc0sounds.mjs)
// v0.1.3: place_3 comes from Kenney CC0 (cc0sounds.mjs)
// eight "wooden fish" knocks, rank 1 (rat) highest -> 8 (elephant) lowest: the capture cry fallback of the design
const animals = ['rat', 'cat', 'dog', 'wolf', 'leopard', 'tiger', 'lion', 'elephant'];
animals.forEach((a, i) => {
  const f = 900 * Math.pow(0.82, i);
  write(`cry_${a}`, lowpass(mix([wood(f, 0.32, 14, [[1, 1], [1.5, 0.25], [2.9, 0.1]]), 0, 1], [wood(f * 1.12, 0.22, 18), 0.09, 0.55]), 3000));
});
// v0.1.3: topple comes from Kenney CC0 (cc0sounds.mjs)
// v0.1.3: jump comes from Kenney CC0 (cc0sounds.mjs)
write('splash', lowpass(mix([noise(0.35, (t) => Math.exp(-9 * t) * Math.min(1, t / 0.01)), 0, 0.8], [tone(500, 220, 0.12, (t) => Math.exp(-25 * t)), 0, 0.5]), 2200));
write('drip', lowpass(mix([tone(1400, 2300, 0.06, (t) => Math.exp(-50 * t)), 0, 1], [tone(1200, 2000, 0.05, (t) => Math.exp(-60 * t)), 0.12, 0.6]), 3000));
// v0.1.3: trap comes from Kenney CC0 (cc0sounds.mjs)
write('illegal', lowpass(mix([wood(220, 0.14, 45, [[1, 1], [1.9, 0.4]]), 0, 1], [thud(110, 0.1), 0, 0.6]), 1500));
write('alarm', mix([thud(75, 0.35), 0, 1], [thud(75, 0.35), 0.28, 0.9]));
write('win', lowpass(mix(...[523, 659, 784, 1047].map((f, i) => [wood(f, 0.5, 9), i * 0.11, 1])), 3500));
write('lose', lowpass(mix(...[523, 440, 349].map((f, i) => [wood(f, 0.5, 9), i * 0.14, 1])), 3000));
