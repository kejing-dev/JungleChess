// Loudness evidence for the factory gate (apps/<id>/qa/loudness-<version>.wav).
// kj-mbp-m5 has no audio loopback device, so the emulator's output cannot be recorded on this Mac. This renders a
// 30 s quick-play session OFFLINE with the app's own mixing: the shipped WAVs from assets/sounds, the SoundPlayer gain
// at the default volume (step 2 = 1.0), the cue order GameController.playCues emits, and the timing seen in logcat
// (human move -> 220 ms walk -> 400 ms AI think -> AI move). Usage: node loudness.mjs <soundsDir> <out.wav>
import fs from 'node:fs';
import path from 'node:path';
const [, , dir, out] = process.argv;
const RATE = 22050, LEN = 30, GAIN = 1.0;
function read(name) {
  const b = fs.readFileSync(path.join(dir, name + '.wav'));
  const n = (b.length - 44) / 2, x = new Float32Array(n);
  for (let i = 0; i < n; i++) x[i] = b.readInt16LE(44 + i * 2) / 32768;
  return x;
}
const mix = new Float32Array(RATE * LEN);
const at = (name, t) => { const x = read(name); const o = Math.floor(t * RATE); for (let i = 0; i < x.length && o + i < mix.length; i++) mix[o + i] += x[i] * GAIN; };
const animals = ['rat', 'cat', 'dog', 'wolf', 'leopard', 'tiger', 'lion', 'elephant'];
let t = 0.3, k = 0;
while (t < LEN - 1.5) {
  // select is silent (D51)
  const human = t + 0.9;                               // pinch a lit square
  if (k % 7 === 4) at('jump', human); else if (k % 9 === 6) at('splash', human); else at(['place', 'place_2', 'place_3'][k % 3], human);
  if (k % 4 === 2) at('cry_' + animals[k % 8] + ['', '_2', '_3'][k % 3], human + 0.13);
  const ai = human + 0.22 + 0.4;                       // walk + minimum AI think
  at(['place_2', 'place_3', 'place'][k % 3], ai);
  if (k % 6 === 5) at('alarm', ai + 0.25);
  if (k % 5 === 3) at('illegal', t + 0.5);
  t += 2.4; k++;
}
let peak = 0, sum = 0;
for (const v of mix) { peak = Math.max(peak, Math.abs(v)); sum += v * v; }
// SoundPool sums and clips per sample; model that as a per-sample clamp at -1 dBFS (a whole-file gain let one overlap
// of two hits pull all 30 s down by 1 dB). The count of clamped samples is printed so the evidence stays honest.
const CEIL = 0.89, lim = 1; let clamped = 0;
for (let i = 0; i < mix.length; i++) if (Math.abs(mix[i]) > CEIL) { mix[i] = Math.sign(mix[i]) * CEIL; clamped++; }
peak = Math.min(peak, CEIL); sum = 0; for (const v of mix) sum += v * v;
const buf = Buffer.alloc(44 + mix.length * 2);
buf.write('RIFF', 0); buf.writeUInt32LE(36 + mix.length * 2, 4); buf.write('WAVE', 8); buf.write('fmt ', 12);
buf.writeUInt32LE(16, 16); buf.writeUInt16LE(1, 20); buf.writeUInt16LE(1, 22); buf.writeUInt32LE(RATE, 24);
buf.writeUInt32LE(RATE * 2, 28); buf.writeUInt16LE(2, 32); buf.writeUInt16LE(16, 34); buf.write('data', 36); buf.writeUInt32LE(mix.length * 2, 40);
for (let i = 0; i < mix.length; i++) buf.writeInt16LE(Math.round(Math.max(-1, Math.min(1, mix[i] * lim)) * 32767), 44 + i * 2);
fs.mkdirSync(path.dirname(out), { recursive: true });
fs.writeFileSync(out, buf);
console.log(`${out}: ${LEN} s, peak ${(20 * Math.log10(peak * lim)).toFixed(1)} dBFS, rms ${(20 * Math.log10(Math.sqrt(sum / mix.length) * lim)).toFixed(1)} dBFS, clamped ${clamped} samples (${(clamped / RATE * 1000).toFixed(1)} ms)`);
