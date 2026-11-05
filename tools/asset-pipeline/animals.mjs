// Jungle Chess animal pipeline (design package step 0, profile D25 / B12).
//
// Reads the raw Tripo GLBs (~60 MB, ~1.9M triangles each, not in the repo) and writes
// app/src/main/assets/models/animals/<name>.glb:
//   - simplified to <= MAX_TRIS triangles (meshoptimizer), silhouettes kept
//   - base colour <= 512 px JPEG, normal map 256 px, metal/rough map dropped (matte wood: roughness 0.8, metallic 0)
//   - OPAQUE, single sided, one material "AnimalWood"
//   - calibrated in ONE place (this table): bottom centre at the origin, face toward -Z (the project's "front"),
//     height in metres from the design package, footprint capped so the animal stays within a 6 cm square.
// Usage: node animals.mjs <tripoDir> <outDir>
import { NodeIO } from '@gltf-transform/core';
import { weld, simplify, dedup, prune, getBounds } from '@gltf-transform/functions';
import { MeshoptSimplifier } from 'meshoptimizer';
import sharp from 'sharp';
import fs from 'node:fs';
import path from 'node:path';

const MAX_TRIS = 2000;
const MAX_FOOTPRINT = 0.058; // longest horizontal side, metres (cell pitch 0.060: two animals in a column keep a 2 mm gap)
// Heights from design-package gameSpec.pieces (cm -> m)
export const CALIBRATION = {
  elephant: { height: 0.055, footprint: 0.060 }, lion: { height: 0.052 }, tiger: { height: 0.050 }, leopard: { height: 0.046 },
  wolf: { height: 0.043 }, dog: { height: 0.040 }, cat: { height: 0.037 }, rat: { height: 0.032 },
};

const [, , srcDir, outDir] = process.argv;
fs.mkdirSync(outDir, { recursive: true });
const io = new NodeIO();
await MeshoptSimplifier.ready;

function positions(prim) { return prim.getAttribute('POSITION').getArray(); }

/** Direction (unit xz) the face looks at: centroid of the top 35 % of the vertices relative to the whole centroid. */
function faceDir(prim) {
  const p = positions(prim);
  let minY = Infinity, maxY = -Infinity;
  for (let i = 1; i < p.length; i += 3) { minY = Math.min(minY, p[i]); maxY = Math.max(maxY, p[i]); }
  const cut = minY + (maxY - minY) * 0.65;
  let cx = 0, cz = 0, n = 0, hx = 0, hz = 0, hn = 0;
  for (let i = 0; i < p.length; i += 3) {
    cx += p[i]; cz += p[i + 2]; n++;
    if (p[i + 1] > cut) { hx += p[i]; hz += p[i + 2]; hn++; }
  }
  const dx = hx / hn - cx / n, dz = hz / hn - cz / n;
  return { dx, dz };
}

function simplifyPositionOnly(doc, prim, maxTris) {
  const pos = prim.getAttribute('POSITION').getArray();
  const uv = prim.getAttribute('TEXCOORD_0').getArray();
  const idx = prim.getIndices().getArray();
  const n = pos.length / 3;
  // weld by position
  const key = new Map(); const wOf = new Uint32Array(n); const wpos = [];
  for (let v = 0; v < n; v++) {
    const k = `${Math.round(pos[3 * v] * 1e5)},${Math.round(pos[3 * v + 1] * 1e5)},${Math.round(pos[3 * v + 2] * 1e5)}`;
    let w = key.get(k);
    if (w === undefined) { w = wpos.length / 3; key.set(k, w); wpos.push(pos[3 * v], pos[3 * v + 1], pos[3 * v + 2]); }
    wOf[v] = w;
  }
  const W = wpos.length / 3;
  const variants = Array.from({ length: W }, () => []);
  for (let v = 0; v < n; v++) variants[wOf[v]].push(v);
  // UV islands: connected components of the original (UV-split) triangles
  const parent = new Int32Array(n); for (let i = 0; i < n; i++) parent[i] = i;
  const find = (a) => { while (parent[a] !== a) { parent[a] = parent[parent[a]]; a = parent[a]; } return a; };
  const unite = (a, b) => { a = find(a); b = find(b); if (a !== b) parent[a] = b; };
  for (let t = 0; t < idx.length; t += 3) { unite(idx[t], idx[t + 1]); unite(idx[t], idx[t + 2]); }
  const island = new Int32Array(n); for (let v = 0; v < n; v++) island[v] = find(v);
  const islandVerts = new Map();
  for (let v = 0; v < n; v++) { const i = island[v]; if (!islandVerts.has(i)) islandVerts.set(i, []); islandVerts.get(i).push(v); }
  const widx = new Uint32Array(idx.length); for (let i = 0; i < idx.length; i++) widx[i] = wOf[idx[i]];
  const wposF = new Float32Array(wpos);
  const [out, err] = MeshoptSimplifier.simplify(widx, wposF, 3, Math.floor(maxTris * 0.97) * 3, 1.0, []);
  const outIdx = []; const newOf = new Map(); const P = [], UV = [];
  let fixed = 0;
  const emit = (v, w) => {
    const k = `${v}:${w}`; let i = newOf.get(k);
    if (i === undefined) { i = P.length / 3; newOf.set(k, i); P.push(wpos[3 * w], wpos[3 * w + 1], wpos[3 * w + 2]); UV.push(uv[2 * v], uv[2 * v + 1]); }
    return i;
  };
  for (let t = 0; t < out.length; t += 3) {
    const ws = [out[t], out[t + 1], out[t + 2]];
    if (ws[0] === ws[1] || ws[1] === ws[2] || ws[0] === ws[2]) continue;
    const count = new Map();
    for (const w of ws) for (const isl of new Set(variants[w].map((v) => island[v]))) count.set(isl, (count.get(isl) || 0) + 1);
    let best = -1, bestC = 0;
    for (const [isl, c] of count) if (c > bestC) { best = isl; bestC = c; }
    for (const w of ws) {
      let v = variants[w].find((x) => island[x] === best);
      if (v === undefined) {
        fixed++;
        let bd = Infinity; const px = wpos[3 * w], py = wpos[3 * w + 1], pz = wpos[3 * w + 2];
        for (const x of islandVerts.get(best)) { const d = (pos[3 * x] - px) ** 2 + (pos[3 * x + 1] - py) ** 2 + (pos[3 * x + 2] - pz) ** 2; if (d < bd) { bd = d; v = x; } }
      }
      outIdx.push(emit(v, w));
    }
  }
  // smooth normals on the low-poly mesh, shared across UV splits of the same position
  const wn = new Float32Array(W * 3);
  const wOfNew = new Int32Array(P.length / 3);
  for (const [k, i] of newOf) wOfNew[i] = +k.split(':')[1];
  for (let t = 0; t < outIdx.length; t += 3) {
    const a = outIdx[t], b = outIdx[t + 1], c = outIdx[t + 2];
    const ux = P[3 * b] - P[3 * a], uy = P[3 * b + 1] - P[3 * a + 1], uz = P[3 * b + 2] - P[3 * a + 2];
    const vx = P[3 * c] - P[3 * a], vy = P[3 * c + 1] - P[3 * a + 1], vz = P[3 * c + 2] - P[3 * a + 2];
    const nx = uy * vz - uz * vy, ny = uz * vx - ux * vz, nz = ux * vy - uy * vx;
    for (const i of [a, b, c]) { const w = wOfNew[i]; wn[3 * w] += nx; wn[3 * w + 1] += ny; wn[3 * w + 2] += nz; }
  }
  const N = new Float32Array(P.length);
  for (let i = 0; i < P.length / 3; i++) {
    const w = wOfNew[i]; const l = Math.hypot(wn[3 * w], wn[3 * w + 1], wn[3 * w + 2]) || 1;
    N[3 * i] = wn[3 * w] / l; N[3 * i + 1] = wn[3 * w + 1] / l; N[3 * i + 2] = wn[3 * w + 2] / l;
  }
  prim.getAttribute('POSITION').setArray(new Float32Array(P));
  prim.getAttribute('NORMAL').setArray(N);
  prim.getAttribute('TEXCOORD_0').setArray(new Float32Array(UV));
  prim.getIndices().setArray(new Uint32Array(outIdx));
  for (const sem of prim.listSemantics()) if (!['POSITION', 'NORMAL', 'TEXCOORD_0'].includes(sem)) prim.setAttribute(sem, null);
  console.error('  welded', W, 'islands', islandVerts.size, 'simplify error', err.toFixed(4), 'corners re-uv', fixed, 'tris', outIdx.length / 3);
  return { tris: outIdx.length / 3, sloppy: fixed > 0 };
}

const report = [];
for (const name of Object.keys(CALIBRATION)) {
  const src = path.join(srcDir, `${name}.glb`);
  const doc = await io.read(src);
  const root = doc.getRoot();
  const prim = root.listMeshes()[0].listPrimitives()[0];
  const before = prim.getIndices().getCount() / 3;
  // Bake the node translation into nothing: we recompute the whole transform below.
  const { dx, dz } = faceDir(prim);
  console.error(name, "face dir", dx.toFixed(4), dz.toFixed(4));
  // Choose the yaw (multiple of 90 deg) that turns the face toward -Z.
  const ang = Math.atan2(dx, dz); // 0 = +Z
  const quarter = Math.round(ang / (Math.PI / 2)) * (Math.PI / 2);
  const yaw = Math.PI - quarter; // rotate so that face direction maps to -Z

  // Tripo UV atlases have thousands of seams, so a UV-aware simplifier stalls near 150k triangles.
  // Simplify on positions only, then give every output corner a UV from the original vertex that shares
  // a UV island with the other two corners (nearest vertex of that island when a corner has none).
  const { tris, sloppy } = simplifyPositionOnly(doc, prim, MAX_TRIS);
  await doc.transform(prune(), dedup());

  // ---- calibration: rotate, scale, move bottom centre to origin ----
  const p = positions(prim);
  const c = Math.cos(yaw), s = Math.sin(yaw);
  const nrm = prim.getAttribute('NORMAL')?.getArray();
  for (let i = 0; i < p.length; i += 3) {
    const x = p[i], z = p[i + 2];
    p[i] = x * c + z * s; p[i + 2] = -x * s + z * c;
    if (nrm) { const nx = nrm[i], nz = nrm[i + 2]; nrm[i] = nx * c + nz * s; nrm[i + 2] = -nx * s + nz * c; }
  }
  let mn = [Infinity, Infinity, Infinity], mx = [-Infinity, -Infinity, -Infinity];
  for (let i = 0; i < p.length; i += 3) for (let k = 0; k < 3; k++) { mn[k] = Math.min(mn[k], p[i + k]); mx[k] = Math.max(mx[k], p[i + k]); }
  const H = mx[1] - mn[1];
  const L = Math.max(mx[0] - mn[0], mx[2] - mn[2]);
  const target = CALIBRATION[name].height;
  const k = Math.min(target / H, (CALIBRATION[name].footprint ?? MAX_FOOTPRINT) / L);
  const cx = (mn[0] + mx[0]) / 2, cz = (mn[2] + mx[2]) / 2;
  for (let i = 0; i < p.length; i += 3) {
    p[i] = (p[i] - cx) * k; p[i + 1] = (p[i + 1] - mn[1]) * k; p[i + 2] = (p[i + 2] - cz) * k;
  }
  prim.getAttribute('POSITION').setArray(p);
  if (nrm) prim.getAttribute('NORMAL').setArray(nrm);
  for (const node of root.listNodes()) { node.setTranslation([0, 0, 0]); node.setRotation([0, 0, 0, 1]); node.setScale([1, 1, 1]); node.setName(`animal_${name}`); }
  root.listMeshes()[0].setName(`animal_${name}_mesh`);

  // ---- material + textures ----
  const mat = prim.getMaterial();
  mat.setName('AnimalWood').setAlphaMode('OPAQUE').setDoubleSided(false).setRoughnessFactor(0.8).setMetallicFactor(0);
  const rm = mat.getMetallicRoughnessTexture();
  mat.setMetallicRoughnessTexture(null);
  if (rm) rm.dispose();
  const base = mat.getBaseColorTexture();
  if (base) base.setImage(await sharp(Buffer.from(base.getImage())).resize(512, 512, { fit: 'inside' }).jpeg({ quality: 82 }).toBuffer()).setMimeType('image/jpeg');
  const normal = mat.getNormalTexture();
  if (normal) normal.setImage(await sharp(Buffer.from(normal.getImage())).resize(256, 256, { fit: 'inside' }).jpeg({ quality: 85 }).toBuffer()).setMimeType('image/jpeg');
  await doc.transform(prune());

  const b = getBounds(root.listScenes()[0]);
  const out = path.join(outDir, `${name}.glb`);
  await io.write(out, doc);
  const bytes = fs.statSync(out).size;
  const row = {
    name, trianglesBefore: before, triangles: tris, sloppy, yawDeg: Math.round(yaw * 180 / Math.PI), scale: +k.toFixed(5),
    heightM: +(b.max[1] - b.min[1]).toFixed(4), widthM: +(b.max[0] - b.min[0]).toFixed(4), lengthM: +(b.max[2] - b.min[2]).toFixed(4),
    bottomCentre: [+((b.max[0] + b.min[0]) / 2).toFixed(4), +b.min[1].toFixed(4), +((b.max[2] + b.min[2]) / 2).toFixed(4)],
    targetHeightM: target, heightLimitedByFootprint: k < target / H - 1e-9, kb: Math.round(bytes / 1024),
  };
  report.push(row);
  console.log(JSON.stringify(row));
}
fs.writeFileSync(path.join(outDir, 'calibration-report.json'), JSON.stringify(report, null, 1));
