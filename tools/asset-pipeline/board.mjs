// Jungle Chess terrain + base + tray models (design package assets[1], gate B round 2:
// "棋盘也做 3D 的 … 草原绿 + 河水蓝"). Procedural geometry written as GLB so the app loads real models
// instead of assembling primitives at runtime.
//
// Board space (metres): origin at the centre of the board on the nominal grass top (y = 0), +X right,
// +Z toward the player. Columns a..g at x = (c - 3) * 0.06, rows 1..9 at z = (r - 5) * 0.06 (row 9 nearest).
// Usage: node board.mjs <outDir>
import { Document, NodeIO } from '@gltf-transform/core';
import sharp from 'sharp';
import fs from 'node:fs';
import path from 'node:path';

const outDir = process.argv[2];
fs.mkdirSync(outDir, { recursive: true });

export const PITCH = 0.06, COLS = 7, ROWS = 9, BORDER = 0.04;
export const HALF_W = (COLS * PITCH) / 2 + BORDER; // 0.25
export const HALF_D = (ROWS * PITCH) / 2 + BORDER; // 0.31
export const THICK = 0.025;
const SEAM = 0.001, SEAM_Y = -0.0035, SIDE_BOTTOM = -0.0065, BORDER_Y = -0.001;
const WATER_Y = -0.006, TRAP_DEPTH = 0.003, DEN_DEPTH = 0.010, TRAP_R = 0.025, DEN_R = 0.026;
const RIVER = (c, r) => r >= 4 && r <= 6 && ((c >= 1 && c <= 2) || (c >= 4 && c <= 5));
const TRAPS = [[2, 1], [4, 1], [3, 2], [2, 9], [4, 9], [3, 8]];
const DENS = [[3, 1], [3, 9]];
const isTrap = (c, r) => TRAPS.some(([a, b]) => a === c && b === r);
const isDen = (c, r) => DENS.some(([a, b]) => a === c && b === r);
const cx = (c) => (c - 3) * PITCH, cz = (r) => (r - 5) * PITCH;

function hex(h) { // sRGB hex -> linear factor
  const v = [1, 3, 5].map((i) => parseInt(h.slice(i, i + 2), 16) / 255);
  return v.map((x) => (x <= 0.04045 ? x / 12.92 : ((x + 0.055) / 1.055) ** 2.4));
}

// ---------------------------------------------------------------- mesh builder
class Geo {
  constructor() { this.p = []; this.n = []; this.uv = []; this.i = []; }
  vert(p, n, uv = [0, 0]) { this.p.push(...p); this.n.push(...n); this.uv.push(...uv); return this.p.length / 3 - 1; }
  tri(a, b, c) { this.i.push(a, b, c); }
  /** flat quad a b c d (counter-clockwise seen from the front) */
  quad(a, b, c, d, uvs = [[0, 0], [1, 0], [1, 1], [0, 1]]) {
    const u = sub(b, a), v = sub(d, a); const n = norm(cross(u, v));
    const i0 = this.vert(a, n, uvs[0]), i1 = this.vert(b, n, uvs[1]), i2 = this.vert(c, n, uvs[2]), i3 = this.vert(d, n, uvs[3]);
    this.tri(i0, i1, i2); this.tri(i0, i2, i3);
  }
  /** axis box; faces: subset of 'tbnsew' (top, bottom, north=-z, south=+z, east=+x, west=-x) */
  box(x0, x1, y0, y1, z0, z1, faces = 'tbnsew') {
    if (faces.includes('t')) this.quad([x0, y1, z1], [x1, y1, z1], [x1, y1, z0], [x0, y1, z0]);
    if (faces.includes('b')) this.quad([x0, y0, z0], [x1, y0, z0], [x1, y0, z1], [x0, y0, z1]);
    if (faces.includes('s')) this.quad([x0, y0, z1], [x1, y0, z1], [x1, y1, z1], [x0, y1, z1]);
    if (faces.includes('n')) this.quad([x1, y0, z0], [x0, y0, z0], [x0, y1, z0], [x1, y1, z0]);
    if (faces.includes('e')) this.quad([x1, y0, z1], [x1, y0, z0], [x1, y1, z0], [x1, y1, z1]);
    if (faces.includes('w')) this.quad([x0, y0, z0], [x0, y0, z1], [x0, y1, z1], [x0, y1, z0]);
  }
  get tris() { return this.i.length / 3; }
}
const sub = (a, b) => [a[0] - b[0], a[1] - b[1], a[2] - b[2]];
const cross = (a, b) => [a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]];
const norm = (a) => { const l = Math.hypot(...a) || 1; return [a[0] / l, a[1] / l, a[2] / l]; };

// smooth hash noise for the turf undulation (+-1.5 mm)
function h2(x, z) { const s = Math.sin(x * 127.1 + z * 311.7) * 43758.5453; return s - Math.floor(s); }
function turf(x, z) {
  const f = 60; // ~ one bump per 1.7 cm
  const X = x * f, Z = z * f, ix = Math.floor(X), iz = Math.floor(Z), fx = X - ix, fz = Z - iz;
  const sm = (t) => t * t * (3 - 2 * t);
  const a = h2(ix, iz), b = h2(ix + 1, iz), c = h2(ix, iz + 1), d = h2(ix + 1, iz + 1);
  const v = a + (b - a) * sm(fx) + (c - a) * sm(fz) + (a - b - c + d) * sm(fx) * sm(fz);
  return (v - 0.5) * 0.003; // +-1.5 mm
}

/** A turf tile: undulating top (n x n grid, smooth normals) + soil sides down to SIDE_BOTTOM. */
function tile(grass, soil, x0, x1, z0, z1, n = 4) {
  const H = (x, z) => turf(x, z);
  const idx = [];
  for (let j = 0; j <= n; j++) for (let i = 0; i <= n; i++) {
    const x = x0 + ((x1 - x0) * i) / n, z = z0 + ((z1 - z0) * j) / n, e = 0.0004;
    // gentle edge roll so the tile reads as a sod block
    const edge = Math.min(i, n - i, j, n - j) === 0 ? -0.0006 : 0;
    const y = H(x, z) + edge;
    const nx = -(H(x + e, z) - H(x - e, z)) / (2 * e), nz = -(H(x, z + e) - H(x, z - e)) / (2 * e);
    idx.push(grass.vert([x, y, z], norm([nx, 1, nz]), [i / n, j / n]));
  }
  for (let j = 0; j < n; j++) for (let i = 0; i < n; i++) {
    const a = idx[j * (n + 1) + i], b = idx[j * (n + 1) + i + 1], c = idx[(j + 1) * (n + 1) + i + 1], d = idx[(j + 1) * (n + 1) + i];
    grass.tri(a, d, c); grass.tri(a, c, b);
  }
  const top = (x, z) => H(x, z) - 0.0006;
  // sides (each split into n segments following the top edge)
  for (let k = 0; k < n; k++) {
    const xa = x0 + ((x1 - x0) * k) / n, xb = x0 + ((x1 - x0) * (k + 1)) / n;
    const za = z0 + ((z1 - z0) * k) / n, zb = z0 + ((z1 - z0) * (k + 1)) / n;
    soil.quad([xa, SIDE_BOTTOM, z1], [xb, SIDE_BOTTOM, z1], [xb, top(xb, z1), z1], [xa, top(xa, z1), z1]); // south
    soil.quad([xb, SIDE_BOTTOM, z0], [xa, SIDE_BOTTOM, z0], [xa, top(xa, z0), z0], [xb, top(xb, z0), z0]); // north
    soil.quad([x1, SIDE_BOTTOM, zb], [x1, SIDE_BOTTOM, za], [x1, top(x1, za), za], [x1, top(x1, zb), zb]); // east
    soil.quad([x0, SIDE_BOTTOM, za], [x0, SIDE_BOTTOM, zb], [x0, top(x0, zb), zb], [x0, top(x0, za), za]); // west
  }
}

/** Square rim with a round hole (top at y), the hole wall down to `floor`, and the floor disc. */
function pit(rim, wall, floor, x, z, r, yTop, yFloor, seg = 28) {
  const h = PITCH / 2 - SEAM / 2;
  const ring = [];
  for (let k = 0; k < seg; k++) {
    const a = (k / seg) * Math.PI * 2;
    const dx = Math.cos(a), dz = Math.sin(a);
    const m = Math.max(Math.abs(dx), Math.abs(dz));
    ring.push({ c: [x + dx * r, z + dz * r], s: [x + (dx / m) * h, z + (dz / m) * h], d: [dx, dz] });
  }
  for (let k = 0; k < seg; k++) {
    const A = ring[k], B = ring[(k + 1) % seg];
    // rim top: between circle and square boundary
    rim.quad([A.c[0], yTop, A.c[1]], [A.s[0], yTop, A.s[1]], [B.s[0], yTop, B.s[1]], [B.c[0], yTop, B.c[1]].slice(0), [[0, 0], [1, 0], [1, 1], [0, 1]]);
    // fix winding: rim faces must point up
    // hole wall (facing inward)
    wall.quad([A.c[0], yFloor, A.c[1]], [B.c[0], yFloor, B.c[1]], [B.c[0], yTop, B.c[1]], [A.c[0], yTop, A.c[1]]);
  }
  // floor disc
  const centre = floor.vert([x, yFloor, z], [0, 1, 0], [0.5, 0.5]);
  const rimIdx = ring.map((q) => floor.vert([q.c[0], yFloor, q.c[1]], [0, 1, 0], [0.5 + q.d[0] / 2, 0.5 + q.d[1] / 2]));
  for (let k = 0; k < seg; k++) floor.tri(centre, rimIdx[(k + 1) % seg], rimIdx[k]);
  // square sides of the rim block (soil)
  return ring;
}

function fixUp(geo) { // make every triangle of a horizontal surface face +Y (pit rims built from the polar fan)
  for (let t = 0; t < geo.i.length; t += 3) {
    const [a, b, c] = [geo.i[t], geo.i[t + 1], geo.i[t + 2]];
    const P = (k) => [geo.p[3 * k], geo.p[3 * k + 1], geo.p[3 * k + 2]];
    const n = cross(sub(P(b), P(a)), sub(P(c), P(a)));
    if (n[1] < 0 && Math.abs(n[1]) > Math.abs(n[0]) + Math.abs(n[2])) { geo.i[t + 1] = c; geo.i[t + 2] = b; }
  }
  for (let k = 0; k < geo.n.length; k += 3) if (Math.abs(geo.n[k + 1]) > 0.9) geo.n[k + 1] = Math.abs(geo.n[k + 1]);
}

// ---------------------------------------------------------------- board
const G = { grassA: new Geo(), grassB: new Geo(), soil: new Geo(), seam: new Geo(), water: new Geo(), trapRim: new Geo(), trapPit: new Geo(), teeth: new Geo(), denPit: new Geo(), stone: new Geo() };
const gx0 = -COLS * PITCH / 2, gx1 = COLS * PITCH / 2, gz0 = -ROWS * PITCH / 2, gz1 = ROWS * PITCH / 2;

for (let r = 1; r <= ROWS; r++) for (let c = 0; c < COLS; c++) {
  const x = cx(c), z = cz(r), h = PITCH / 2 - SEAM / 2;
  if (RIVER(c, r)) continue;
  if (isTrap(c, r) || isDen(c, r)) {
    const den = isDen(c, r);
    const yTop = 0.0005;
    pit(G.trapRim, den ? G.denPit : G.trapPit, den ? G.denPit : G.trapPit, x, z, den ? DEN_R : TRAP_R, yTop, den ? -DEN_DEPTH : -TRAP_DEPTH);
    G.soil.box(x - h, x + h, SIDE_BOTTOM, yTop, z - h, z + h, 'nsew');
    if (!den) {
      // teeth: little wedges on the rim pointing into the pit
      const n = 10;
      for (let k = 0; k < n; k++) {
        const a = (k / n) * Math.PI * 2, dx = Math.cos(a), dz = Math.sin(a), tx = -dz, tz = dx;
        const r0 = TRAP_R + 0.0035, r1 = TRAP_R - 0.003, w = 0.0028, ht = 0.0022;
        const b1 = [x + dx * r0 + tx * w, yTop, z + dz * r0 + tz * w], b2 = [x + dx * r0 - tx * w, yTop, z + dz * r0 - tz * w];
        const tip = [x + dx * r1, yTop + ht * 0.4, z + dz * r1], top = [x + dx * r0, yTop + ht, z + dz * r0];
        G.teeth.quad(b2, b1, top, top); // back (degenerate quad = triangle)
        const t1 = G.teeth; t1.quad(b1, tip, top, top); t1.quad(tip, b2, top, top);
      }
    } else {
      // stone ring + lintel at the outer edge of the cell (away from the board centre), inscription added at runtime
      const seg = 28, rIn = DEN_R, rOut = DEN_R + 0.0035, ht = 0.003;
      for (let k = 0; k < seg; k++) {
        const a0 = (k / seg) * Math.PI * 2, a1 = ((k + 1) / seg) * Math.PI * 2;
        const p = (rr, a, y) => [x + Math.cos(a) * rr, y, z + Math.sin(a) * rr];
        G.stone.quad(p(rIn, a0, yTop + ht), p(rOut, a0, yTop + ht), p(rOut, a1, yTop + ht), p(rIn, a1, yTop + ht));
        G.stone.quad(p(rOut, a1, yTop), p(rOut, a0, yTop), p(rOut, a0, yTop + ht), p(rOut, a1, yTop + ht));
        G.stone.quad(p(rIn, a0, yTop), p(rIn, a1, yTop), p(rIn, a1, yTop + ht), p(rIn, a0, yTop + ht));
      }
      const outer = r === 1 ? -1 : 1, zl = z + outer * (h - 0.003), lw = 0.022, post = 0.0035, lh = 0.015, beam = 0.004;
      G.stone.box(x - lw, x - lw + post * 2, yTop, yTop + lh, zl - post, zl + post, 'nsew');
      G.stone.box(x + lw - post * 2, x + lw, yTop, yTop + lh, zl - post, zl + post, 'nsew');
      G.stone.box(x - lw - 0.002, x + lw + 0.002, yTop + lh, yTop + lh + beam, zl - post - 0.001, zl + post + 0.001);
    }
    continue;
  }
  tile((r + c) % 2 === 0 ? G.grassA : G.grassB, G.soil, x - h, x + h, z - h, z + h);
}
fixUp(G.trapRim);

// seam floor (dark turf between the tiles), with holes where the rivers are
for (let r = 1; r <= ROWS; r++) for (let c = 0; c < COLS; c++) {
  if (RIVER(c, r) || isDen(c, r)) continue;
  const x0 = cx(c) - PITCH / 2, x1 = cx(c) + PITCH / 2, z0 = cz(r) - PITCH / 2, z1 = cz(r) + PITCH / 2;
  G.seam.quad([x0, SEAM_Y, z1], [x1, SEAM_Y, z1], [x1, SEAM_Y, z0], [x0, SEAM_Y, z0]);
}
// rivers: water surface sunk 6 mm, uv over the whole river
for (const [x0, x1] of [[-0.15, -0.03], [0.03, 0.15]]) G.water.quad([x0, WATER_Y, 0.09], [x1, WATER_Y, 0.09], [x1, WATER_Y, -0.09], [x0, WATER_Y, -0.09], [[0, 1], [1, 1], [1, 0], [0, 0]]);
// soil border ring: top at BORDER_Y with a 3 mm bevel to the outer edge, outer sides down to -THICK, bottom
{
  const X = HALF_W, Z = HALF_D, b = 0.003, yb = BORDER_Y - 0.003;
  const top = (x0, x1, z0, z1) => G.soil.quad([x0, BORDER_Y, z1], [x1, BORDER_Y, z1], [x1, BORDER_Y, z0], [x0, BORDER_Y, z0]);
  top(-X + b, X - b, -Z + b, gz0); top(-X + b, X - b, gz1, Z - b); top(-X + b, gx0, gz0, gz1); top(gx1, X - b, gz0, gz1);
  // bevels
  G.soil.quad([-X, yb, Z], [X, yb, Z], [X - b, BORDER_Y, Z - b], [-X + b, BORDER_Y, Z - b]);
  G.soil.quad([X, yb, -Z], [-X, yb, -Z], [-X + b, BORDER_Y, -Z + b], [X - b, BORDER_Y, -Z + b]);
  G.soil.quad([X, yb, Z], [X, yb, -Z], [X - b, BORDER_Y, -Z + b], [X - b, BORDER_Y, Z - b]);
  G.soil.quad([-X, yb, -Z], [-X, yb, Z], [-X + b, BORDER_Y, Z - b], [-X + b, BORDER_Y, -Z + b]);
  // outer sides and bottom
  G.soil.box(-X, X, -THICK, yb, -Z, Z, 'bnsew');
  // inner lip down to the seam floor
  G.soil.quad([gx1, SIDE_BOTTOM, gz1], [gx0, SIDE_BOTTOM, gz1], [gx0, BORDER_Y, gz1], [gx1, BORDER_Y, gz1]);
  G.soil.quad([gx0, SIDE_BOTTOM, gz0], [gx1, SIDE_BOTTOM, gz0], [gx1, BORDER_Y, gz0], [gx0, BORDER_Y, gz0]);
  G.soil.quad([gx0, SIDE_BOTTOM, gz1], [gx0, SIDE_BOTTOM, gz0], [gx0, BORDER_Y, gz0], [gx0, BORDER_Y, gz1]);
  G.soil.quad([gx1, SIDE_BOTTOM, gz0], [gx1, SIDE_BOTTOM, gz1], [gx1, BORDER_Y, gz1], [gx1, BORDER_Y, gz0]);
}

// ---------------------------------------------------------------- textures
async function noiseTex(size, fn) {
  const buf = Buffer.alloc(size * size * 3);
  for (let y = 0; y < size; y++) for (let x = 0; x < size; x++) {
    const [r, g, b] = fn(x / size, y / size, x, y);
    const o = (y * size + x) * 3; buf[o] = r; buf[o + 1] = g; buf[o + 2] = b;
  }
  return sharp(buf, { raw: { width: size, height: size, channels: 3 } }).png().toBuffer();
}
let seed = 7; const rnd = () => ((seed = (seed * 16807) % 2147483647) / 2147483647);
const blades = Array.from({ length: 900 }, () => [rnd(), rnd(), rnd()]);
const grassPng = await noiseTex(128, (u, v, x, y) => {
  let k = 0.9 + (h2(x * 0.37, y * 0.53) - 0.5) * 0.12;
  for (const [bx, by, s] of blades) { const dx = Math.abs(u - bx), dy = v - by; if (dx < 0.006 && dy > 0 && dy < 0.03 + s * 0.02) { k += s > 0.5 ? 0.12 : -0.14; break; } }
  const t = Math.max(0, Math.min(1, k)) * 255; return [t, t, t];
});
const waterPng = await noiseTex(256, (u, v) => {
  // deep #2F7FB8 at the banks -> light #5BB0E0 in the middle, with soft ripple lines
  const mid = 1 - Math.abs(u - 0.5) * 2;
  const rip = 0.5 + 0.5 * Math.sin((v * 9 + Math.sin(u * 7) * 0.4) * Math.PI * 2);
  const k = Math.min(1, mid * 0.75 + (rip > 0.93 ? 0.35 : 0));
  const A = [0x2f, 0x7f, 0xb8], B = [0x5b, 0xb0, 0xe0];
  return A.map((a, i) => Math.round(a + (B[i] - a) * k));
});
const soilPng = await noiseTex(128, (u, v, x, y) => { const k = 0.82 + h2(x * 0.71, y * 0.29) * 0.18; const t = k * 255; return [t, t, t]; });

// ---------------------------------------------------------------- write
async function write(file, parts) {
  const doc = new Document();
  const buffer = doc.createBuffer();
  const scene = doc.createScene('Scene');
  const root = doc.createNode(path.basename(file, '.glb')); scene.addChild(root);
  let tris = 0;
  for (const p of parts) {
    if (!p.geo.i.length) continue;
    const mat = doc.createMaterial(p.name).setBaseColorFactor([...hex(p.color), 1]).setRoughnessFactor(p.rough ?? 0.9).setMetallicFactor(0).setAlphaMode('OPAQUE').setDoubleSided(!!p.double);
    if (p.png) mat.setBaseColorTexture(doc.createTexture(p.name + 'Tex').setImage(p.png).setMimeType('image/png'));
    const prim = doc.createPrimitive()
      .setAttribute('POSITION', doc.createAccessor().setType('VEC3').setArray(new Float32Array(p.geo.p)).setBuffer(buffer))
      .setAttribute('NORMAL', doc.createAccessor().setType('VEC3').setArray(new Float32Array(p.geo.n)).setBuffer(buffer))
      .setAttribute('TEXCOORD_0', doc.createAccessor().setType('VEC2').setArray(new Float32Array(p.geo.uv)).setBuffer(buffer))
      .setIndices(doc.createAccessor().setType('SCALAR').setArray(new Uint32Array(p.geo.i)).setBuffer(buffer))
      .setMaterial(mat);
    root.addChild(doc.createNode(p.name).setMesh(doc.createMesh(p.name).addPrimitive(prim)));
    tris += p.geo.tris;
  }
  await new NodeIO().write(path.join(outDir, file), doc);
  console.log(file, 'triangles', tris, 'bytes', fs.statSync(path.join(outDir, file)).size);
}

await write('board.glb', [
  { name: 'GrassA', geo: G.grassA, color: '#5A9A44', png: grassPng, rough: 0.95 },
  { name: 'GrassB', geo: G.grassB, color: '#549240', png: grassPng, rough: 0.95 },
  { name: 'Soil', geo: G.soil, color: '#6B4A2E', png: soilPng },
  { name: 'Seam', geo: G.seam, color: '#2F5A22' },
  { name: 'Water', geo: G.water, color: '#FFFFFF', png: waterPng, rough: 0.35 },
  { name: 'TrapRim', geo: G.trapRim, color: '#7A5434', png: soilPng },
  { name: 'TrapPit', geo: G.trapPit, color: '#4A2D16' },
  { name: 'TrapTeeth', geo: G.teeth, color: '#3A2614', double: true },
  { name: 'DenPit', geo: G.denPit, color: '#24170C' },
  { name: 'Stone', geo: G.stone, color: '#8F8C82', rough: 0.85, double: true },
]);

// ---------------------------------------------------------------- base: disc 4.6 x 0.8 cm, top face separate for the label
{
  const R = 0.023, Hh = 0.008, bev = 0.0012, seg = 40, ringY0 = 0.0028, ringY1 = 0.0046;
  const body = new Geo(), ring = new Geo(), top = new Geo();
  const P = (rr, a, y) => [Math.cos(a) * rr, y, Math.sin(a) * rr];
  for (let k = 0; k < seg; k++) {
    const a0 = (k / seg) * Math.PI * 2, a1 = ((k + 1) / seg) * Math.PI * 2;
    body.quad(P(R, a1, 0), P(R, a0, 0), P(R, a0, ringY0), P(R, a1, ringY0));
    ring.quad(P(R, a1, ringY0), P(R, a0, ringY0), P(R, a0, ringY1), P(R, a1, ringY1));
    body.quad(P(R, a1, ringY1), P(R, a0, ringY1), P(R, a0, Hh - bev), P(R, a1, Hh - bev));
    body.quad(P(R, a1, Hh - bev), P(R, a0, Hh - bev), P(R - bev, a0, Hh), P(R - bev, a1, Hh));
  }
  fixOutward(body); fixOutward(ring);
  const c0 = top.vert([0, Hh, 0], [0, 1, 0], [0.5, 0.5]);
  const rim = [];
  for (let k = 0; k < seg; k++) { const a = (k / seg) * Math.PI * 2; rim.push(top.vert([Math.cos(a) * (R - bev), Hh, Math.sin(a) * (R - bev)], [0, 1, 0], [0.5 + Math.cos(a) * 0.5, 0.5 + Math.sin(a) * 0.5])); }
  for (let k = 0; k < seg; k++) top.tri(c0, rim[(k + 1) % seg], rim[k]);
  fixUp(top);
  const bottom = new Geo();
  const b0 = bottom.vert([0, 0, 0], [0, -1, 0]); const brim = [];
  for (let k = 0; k < seg; k++) { const a = (k / seg) * Math.PI * 2; brim.push(bottom.vert([Math.cos(a) * R, 0, Math.sin(a) * R], [0, -1, 0])); }
  for (let k = 0; k < seg; k++) bottom.tri(b0, brim[k], brim[(k + 1) % seg]);
  body.p.push(...bottom.p); body.n.push(...bottom.n); body.uv.push(...bottom.uv);
  const off = body.p.length / 3 - bottom.p.length / 3; body.i.push(...bottom.i.map((x) => x + off));
  await write('base.glb', [
    { name: 'BaseBody', geo: body, color: '#B5452E', rough: 0.55 },
    { name: 'BaseRing', geo: ring, color: '#D9B45A', rough: 0.45 },
    { name: 'BaseTop', geo: top, color: '#EFE3C8', rough: 0.6 },
  ]);
}
function fixOutward(geo) {
  for (let t = 0; t < geo.i.length; t += 3) {
    const [a, b, c] = [geo.i[t], geo.i[t + 1], geo.i[t + 2]];
    const P = (k) => [geo.p[3 * k], geo.p[3 * k + 1], geo.p[3 * k + 2]];
    const n = cross(sub(P(b), P(a)), sub(P(c), P(a)));
    const m = P(a);
    if (n[0] * m[0] + n[2] * m[2] < 0) { geo.i[t + 1] = c; geo.i[t + 2] = b; }
  }
  for (let k = 0; k < geo.p.length; k += 3) {
    // recompute vertex normals as the outward horizontal direction (smooth cylinder) except the bevel tilt
    const x = geo.p[k], z = geo.p[k + 2], l = Math.hypot(x, z) || 1;
    const ny = geo.n[k + 1];
    const tilt = Math.abs(ny) > 0.3 ? ny : 0;
    const h = Math.sqrt(Math.max(0, 1 - tilt * tilt));
    geo.n[k] = (x / l) * h; geo.n[k + 1] = Math.abs(tilt); geo.n[k + 2] = (z / l) * h;
  }
}

// ---------------------------------------------------------------- tray: 0.14 x 0.05, rim 1.5 cm, floor 3 mm
{
  const W = 0.14, D = 0.05, Hh = 0.015, t = 0.004, f = 0.003;
  const g = new Geo(), felt = new Geo();
  const x0 = -W / 2, x1 = W / 2, z0 = -D / 2, z1 = D / 2;
  g.box(x0, x1, 0, f, z0, z1, 'bnsew');
  g.box(x0, x1, 0, Hh, z1 - t, z1); g.box(x0, x1, 0, Hh, z0, z0 + t);
  g.box(x0, x0 + t, 0, Hh, z0 + t, z1 - t); g.box(x1 - t, x1, 0, Hh, z0 + t, z1 - t);
  felt.quad([x0 + t, f, z1 - t], [x1 - t, f, z1 - t], [x1 - t, f, z0 + t], [x0 + t, f, z0 + t]);
  await write('tray.glb', [
    { name: 'TrayWood', geo: g, color: '#7E6A52', png: soilPng, rough: 0.8 },
    { name: 'TrayFloor', geo: felt, color: '#5C4A36', rough: 0.95 },
  ]);
}
