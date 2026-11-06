// Physical event sounds from Kenney CC0 packs (B20: real recordings, not synthesis). Decodes OGG, mixes to mono
// 22.05 kHz, trims silence, normalises each file to PEAK_DB and writes assets/sounds/<event>[_n].wav.
// node cc0sounds.mjs <kenney root (~/Downloads/cc0-sounds)> <outDir>
import fs from 'node:fs';
import path from 'node:path';
import { OggVorbisDecoder } from '@wasm-audio-decoders/ogg-vorbis';

const [, , root, out] = process.argv;
const RATE = 22050, PEAK_DB = -1.2;
export const MAP = {
  place: ['kenney_impact-sounds/Audio/impactWood_light_000.ogg', 'kenney_impact-sounds/Audio/impactWood_light_001.ogg', 'kenney_impact-sounds/Audio/impactWood_light_002.ogg', 'kenney_impact-sounds/Audio/impactWood_light_003.ogg'],
  jump: ['kenney_impact-sounds/Audio/impactSoft_medium_000.ogg', 'kenney_impact-sounds/Audio/impactSoft_medium_001.ogg', 'kenney_impact-sounds/Audio/impactSoft_medium_002.ogg'],
  topple: ['kenney_impact-sounds/Audio/impactWood_heavy_000.ogg', 'kenney_impact-sounds/Audio/impactWood_heavy_001.ogg', 'kenney_impact-sounds/Audio/impactWood_heavy_002.ogg'],
  trap: ['kenney_impact-sounds/Audio/impactPlank_medium_000.ogg', 'kenney_impact-sounds/Audio/impactPlank_medium_001.ogg', 'kenney_impact-sounds/Audio/impactPlank_medium_002.ogg'],
};
const dec = new OggVorbisDecoder(); await dec.ready;
function resample(x, from) { if (from === RATE) return x; const n = Math.floor(x.length * RATE / from), y = new Float32Array(n); for (let i = 0; i < n; i++) { const t = i * from / RATE, k = Math.floor(t), f = t - k; y[i] = (x[k] ?? 0) * (1 - f) + (x[k + 1] ?? 0) * f; } return y; }
function wav(file, y) {
  const buf = Buffer.alloc(44 + y.length * 2);
  buf.write('RIFF', 0); buf.writeUInt32LE(36 + y.length * 2, 4); buf.write('WAVE', 8); buf.write('fmt ', 12);
  buf.writeUInt32LE(16, 16); buf.writeUInt16LE(1, 20); buf.writeUInt16LE(1, 22); buf.writeUInt32LE(RATE, 24);
  buf.writeUInt32LE(RATE * 2, 28); buf.writeUInt16LE(2, 32); buf.writeUInt16LE(16, 34); buf.write('data', 36); buf.writeUInt32LE(y.length * 2, 40);
  for (let i = 0; i < y.length; i++) buf.writeInt16LE(Math.round(Math.max(-1, Math.min(1, y[i])) * 32767), 44 + i * 2);
  fs.writeFileSync(file, buf);
}
const sources = [];
for (const [event, files] of Object.entries(MAP)) {
  for (let v = 0; v < files.length; v++) {
    const src = path.join(root, files[v]);
    const { channelData, sampleRate } = await dec.decodeFile(new Uint8Array(fs.readFileSync(src)));
    await dec.reset();
    const n = channelData[0].length, mono = new Float32Array(n);
    for (const ch of channelData) for (let i = 0; i < n; i++) mono[i] += ch[i] / channelData.length;
    let y = resample(mono, sampleRate);
    // trim leading / trailing silence below -50 dBFS
    const th = Math.pow(10, -50 / 20); let a = 0, b = y.length - 1;
    while (a < b && Math.abs(y[a]) < th) a++; while (b > a && Math.abs(y[b]) < th) b--;
    y = y.slice(Math.max(0, a - 32), b + 1);
    const peak = y.reduce((m, s) => Math.max(m, Math.abs(s)), 0) || 1, k = Math.pow(10, PEAK_DB / 20) / peak;
    const fade = Math.floor(RATE * 0.01);
    y = y.map((s, i) => s * k * Math.min(1, (y.length - i) / fade));
    const name = v === 0 ? event : `${event}_${v + 1}`;
    wav(path.join(out, `${name}.wav`), y);
    const rms = 20 * Math.log10(Math.sqrt(y.reduce((s, q) => s + q * q, 0) / y.length));
    console.log(`${name.padEnd(10)} <- ${files[v]}  ${(y.length / RATE).toFixed(2)} s  rms ${rms.toFixed(1)} dBFS`);
    sources.push({ name, src: files[v] });
  }
}
fs.writeFileSync(path.join(out, '..', '..', '..', '..', '..', 'tools', 'asset-pipeline', 'cc0-sources.json'), JSON.stringify(sources, null, 1));
