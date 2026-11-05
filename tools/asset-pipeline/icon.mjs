// App icon (design package appIcon): jungle-green ground #2F6B3A, a cream elephant #F2E8D5 in profile and a
// small vermilion rat #C8463A at its feet looking up (the rat beats the elephant). Flat, no text.
// Writes the three PICO 3D icon layers + SDFs, the flat launcher icon and the adaptive foreground.
// node icon.mjs <res dir> [preview.png]
import sharp from 'sharp';
import path from 'node:path';

const [, , res, preview] = process.argv;
const S = 1024;
const GREEN = '#2F6B3A', CREAM = '#F2E8D5', RAT = '#C8463A';

// artwork in a 100 x 100 box
const elephant = `
  <g fill="${CREAM}">
    <ellipse cx="57" cy="46" rx="25" ry="17"/>
    <circle cx="33" cy="38" r="13"/>
    <path d="M24 42 C 18 50, 18 60, 22 68 L 27 68 C 24 60, 25 52, 30 47 Z"/>
    <path d="M36 28 C 46 24, 52 32, 50 46 C 44 50, 38 48, 36 42 Z" fill="#E2D5BC"/>
    <rect x="38" y="54" width="8" height="18" rx="3"/>
    <rect x="50" y="56" width="8" height="16" rx="3"/>
    <rect x="62" y="56" width="8" height="16" rx="3"/>
    <rect x="72" y="54" width="8" height="18" rx="3"/>
    <path d="M81 42 Q 88 46 86 54" stroke="${CREAM}" stroke-width="2.4" fill="none" stroke-linecap="round"/>
  </g>
  <circle cx="30" cy="35" r="1.6" fill="${GREEN}"/>`;
const rat = `
  <g fill="${RAT}">
    <ellipse cx="24" cy="80" rx="8" ry="5"/>
    <circle cx="17" cy="75" r="4.2"/>
    <circle cx="18" cy="70.5" r="2.2"/>
    <path d="M31 81 Q 40 86 44 79" stroke="${RAT}" stroke-width="1.6" fill="none" stroke-linecap="round"/>
  </g>`;
const ground = `<path d="M8 74 Q 50 70 92 76 L 92 100 L 8 100 Z" fill="#3B7D46" opacity="0.9"/>`;

const svg = (inner, scale = 1, size = S, round = true) => {
  const k = (size / 100) * scale, off = (size - 100 * k) / 2;
  const clip = round ? `<clipPath id="c"><circle cx="${size / 2}" cy="${size / 2}" r="${size / 2}"/></clipPath>` : '';
  return Buffer.from(`<svg xmlns="http://www.w3.org/2000/svg" width="${size}" height="${size}"><defs>${clip}</defs>
    <g ${round ? 'clip-path="url(#c)"' : ''}><g transform="translate(${off} ${off}) scale(${k})">${inner}</g></g></svg>`);
};
const bg = (size, round) => Buffer.from(`<svg xmlns="http://www.w3.org/2000/svg" width="${size}" height="${size}">
  ${round ? `<circle cx="${size / 2}" cy="${size / 2}" r="${size / 2}" fill="${GREEN}"/>` : `<rect width="${size}" height="${size}" fill="${GREEN}"/>`}</svg>`);

async function png(buf) { return sharp(buf).png().toBuffer(); }
async function sdf(layerPng) {
  const { data, info } = await sharp(layerPng).ensureAlpha().raw().toBuffer({ resolveWithObject: true });
  const n = info.width * info.height;
  const inside = new Uint8Array(n);
  for (let i = 0; i < n; i++) inside[i] = data[i * 4 + 3] >= 128 ? 1 : 0;
  // two-pass chamfer distance to the nearest outside pixel
  const W = info.width, H = info.height, INF = 1e9, d = new Float32Array(n);
  for (let i = 0; i < n; i++) d[i] = inside[i] ? INF : 0;
  for (let y = 0; y < H; y++) for (let x = 0; x < W; x++) { const i = y * W + x; if (!d[i]) continue;
    if (x > 0) d[i] = Math.min(d[i], d[i - 1] + 1); if (y > 0) d[i] = Math.min(d[i], d[i - W] + 1);
    if (x > 0 && y > 0) d[i] = Math.min(d[i], d[i - W - 1] + 1.414); if (x < W - 1 && y > 0) d[i] = Math.min(d[i], d[i - W + 1] + 1.414); }
  for (let y = H - 1; y >= 0; y--) for (let x = W - 1; x >= 0; x--) { const i = y * W + x; if (!d[i]) continue;
    if (x < W - 1) d[i] = Math.min(d[i], d[i + 1] + 1); if (y < H - 1) d[i] = Math.min(d[i], d[i + W] + 1);
    if (x < W - 1 && y < H - 1) d[i] = Math.min(d[i], d[i + W + 1] + 1.414); if (x > 0 && y < H - 1) d[i] = Math.min(d[i], d[i + W - 1] + 1.414); }
  const out = Buffer.alloc(n * 4);
  for (let i = 0; i < n; i++) { const v = inside[i] ? Math.min(255, Math.round(d[i] * 8)) : 0; out[i * 4] = out[i * 4 + 1] = out[i * 4 + 2] = out[i * 4 + 3] = v; }
  return sharp(out, { raw: { width: W, height: H, channels: 4 } }).png().toBuffer();
}

const layers = [
  await sharp(bg(S, true)).composite([{ input: await png(svg(ground)) }]).png().toBuffer(),
  await png(svg(elephant)),
  await png(svg(rat)),
];
for (let i = 0; i < 3; i++) {
  await sharp(layers[i]).toFile(path.join(res, 'drawable', `icon_3d_layer_${i}.png`));
  await sharp(await sdf(layers[i])).toFile(path.join(res, 'drawable', `icon_3d_sdf_${i}.png`));
}
const flat = await sharp(layers[0]).composite([{ input: layers[1] }, { input: layers[2] }]).png().toBuffer();
await sharp(flat).toFile(path.join(res, 'mipmap-xxxhdpi', 'ic_spatial_launcher.png'));
const A = 1424;
await sharp(bg(A, false)).composite([{ input: await png(svg(ground + elephant + rat, 0.62, A, false)) }]).flatten({ background: GREEN }).png().toFile(path.join(res, 'drawable', 'ic_launcher_foreground.png'));
if (preview) await sharp(flat).resize(512, 512).png().toFile(preview);
console.log('icon written');
