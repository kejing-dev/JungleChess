// Software preview of GLB models (no GPU / browser needed): textured, lambert + ambient, z-buffered.
// node preview.mjs <out.png> <yawDeg> <pitchDeg> <a.glb> [b.glb ...]   -> one row, same scale, same camera.
// Convention check (D25): a model whose face points to -Z shows its face when yaw = 180 (camera on the -Z side).
import { NodeIO } from '@gltf-transform/core';
import sharp from 'sharp';

const [, , out, yawDeg = '200', pitchDeg = '20', ...files] = process.argv;
const io = new NodeIO();
const SCALE = +(process.env.SCALE || 3400), CELL = +(process.env.CELL || 220), H = +(process.env.H || 260), W = CELL * files.length;
const img = new Float32Array(W * H * 3).fill(0.93);
const zb = new Float32Array(W * H).fill(Infinity);
const yaw = (+yawDeg * Math.PI) / 180, pitch = (+pitchDeg * Math.PI) / 180;

for (let m = 0; m < files.length; m++) {
  const doc = await io.read(files[m]);
  for (const mesh of doc.getRoot().listMeshes()) for (const prim of mesh.listPrimitives()) {
  const P = prim.getAttribute('POSITION').getArray();
  const N = prim.getAttribute('NORMAL').getArray();
  const UV = prim.getAttribute('TEXCOORD_0')?.getArray();
  const I = prim.getIndices().getArray();
  const tex = prim.getMaterial()?.getBaseColorTexture();
  let tw = 1, th = 1, tpx = new Uint8Array([255, 255, 255]);
  const fac = (prim.getMaterial()?.getBaseColorFactor() ?? [1, 1, 1, 1]).map((v) => Math.pow(v, 1 / 2.2));
  if (tex) {
    const { data, info } = await sharp(Buffer.from(tex.getImage())).removeAlpha().raw().toBuffer({ resolveWithObject: true });
    tw = info.width; th = info.height; tpx = data;
  }
  // camera: look at the model from direction (yaw, pitch); +Y up. View = R_pitch * R_yaw
  const cy = Math.cos(yaw), sy = Math.sin(yaw), cp = Math.cos(pitch), sp = Math.sin(pitch);
  const view = (x, y, z) => {
    // rotate world so that the camera (at angle yaw around Y, looking toward origin) looks down -Z
    const x1 = x * cy - z * sy, z1 = x * sy + z * cy;
    const y2 = y * cp - z1 * sp, z2 = y * sp + z1 * cp;
    return [x1, y2, z2];
  };
  const light = (() => { const l = view(-0.4, 0.8, 0.45); const n = Math.hypot(...l); return l.map((v) => v / n); })();
  const ox = m * CELL + CELL / 2, oy = H - +(process.env.OY || 40);
  const vs = [], ns = [];
  for (let i = 0; i < P.length; i += 3) {
    const v = view(P[i], P[i + 1] - 0.0, P[i + 2]);
    vs.push([ox + v[0] * SCALE, oy - v[1] * SCALE, -v[2]]);
    ns.push(view(N[i], N[i + 1], N[i + 2]));
  }
  for (let t = 0; t < I.length; t += 3) {
    const a = I[t], b = I[t + 1], c = I[t + 2];
    const A = vs[a], B = vs[b], C = vs[c];
    const area = (B[0] - A[0]) * (C[1] - A[1]) - (B[1] - A[1]) * (C[0] - A[0]);
    if (area >= 0) continue; // back face (screen y down)
    const minx = Math.max(0, Math.floor(Math.min(A[0], B[0], C[0]))), maxx = Math.min(W - 1, Math.ceil(Math.max(A[0], B[0], C[0])));
    const miny = Math.max(0, Math.floor(Math.min(A[1], B[1], C[1]))), maxy = Math.min(H - 1, Math.ceil(Math.max(A[1], B[1], C[1])));
    for (let y = miny; y <= maxy; y++) for (let x = minx; x <= maxx; x++) {
      const w0 = ((B[0] - x) * (C[1] - y) - (B[1] - y) * (C[0] - x)) / area;
      const w1 = ((C[0] - x) * (A[1] - y) - (C[1] - y) * (A[0] - x)) / area;
      const w2 = 1 - w0 - w1;
      if (w0 < 0 || w1 < 0 || w2 < 0) continue;
      const z = w0 * A[2] + w1 * B[2] + w2 * C[2];
      const k = y * W + x;
      if (z >= zb[k]) continue;
      zb[k] = z;
      let r = 1, g = 1, bl = 1;
      if (UV) {
        const u = w0 * UV[2 * a] + w1 * UV[2 * b] + w2 * UV[2 * c];
        const v = w0 * UV[2 * a + 1] + w1 * UV[2 * b + 1] + w2 * UV[2 * c + 1];
        const px = Math.min(tw - 1, Math.max(0, Math.floor((u - Math.floor(u)) * tw)));
        const py = Math.min(th - 1, Math.max(0, Math.floor((v - Math.floor(v)) * th)));
        const o = (py * tw + px) * 3;
        r = tpx[o] / 255; g = tpx[o + 1] / 255; bl = tpx[o + 2] / 255;
      }
      r *= fac[0]; g *= fac[1]; bl *= fac[2];
      const n0 = ns[a], n1 = ns[b], n2 = ns[c];
      const nx = w0 * n0[0] + w1 * n1[0] + w2 * n2[0], ny = w0 * n0[1] + w1 * n1[1] + w2 * n2[1], nz = w0 * n0[2] + w1 * n1[2] + w2 * n2[2];
      const nl = Math.hypot(nx, ny, nz) || 1;
      const d = Math.max(0, (nx * light[0] + ny * light[1] + nz * light[2]) / nl);
      const s = 0.45 + 0.65 * d;
      img[k * 3] = r * s; img[k * 3 + 1] = g * s; img[k * 3 + 2] = bl * s;
    }
  }
  }
}
const buf = Buffer.alloc(W * H * 3);
for (let i = 0; i < buf.length; i++) buf[i] = Math.max(0, Math.min(255, Math.round(img[i] * 255)));
await sharp(buf, { raw: { width: W, height: H, channels: 3 } }).png().toFile(out);
console.log(out);
