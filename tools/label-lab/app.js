"use strict";
addEventListener("error", (e) => { const d = document.createElement("pre"); d.style.cssText = "position:fixed;left:8px;bottom:8px;max-width:90vw;background:#400;color:#fcc;padding:8px;font:12px monospace;z-index:99"; d.textContent = e.message + " @" + e.lineno; document.body.appendChild(d); });
const W = 480, H = 720;
const $ = (id) => document.getElementById(id);
let current = null;
let seed = 1;

// Small seeded PRNG so "Re-roll" gives a different but repeatable look.
function rng(s) { let a = s >>> 0; return () => { a = (a + 0x6D2B79F5) >>> 0; let t = a; t = Math.imul(t ^ (t >>> 15), t | 1); t ^= t + Math.imul(t ^ (t >>> 7), t | 61); return ((t ^ (t >>> 14)) >>> 0) / 4294967296; }; }

function txt(parent, text, x, y, size, opts = {}) {
  const d = document.createElement("div");
  d.className = "abs";
  d.textContent = text;
  d.style.left = x + "px"; d.style.top = y + "px"; d.style.fontSize = size + "px";
  if (opts.center) { d.style.left = "0"; d.style.width = W + "px"; d.style.textAlign = "center"; }
  if (opts.spacing) d.style.letterSpacing = opts.spacing;
  if (opts.weight) d.style.fontWeight = opts.weight;
  parent.appendChild(d);
  return d;
}
function rule(parent, x, y, w, h) { const d = document.createElement("div"); d.className = "rule"; Object.assign(d.style, { left: x + "px", top: y + "px", width: w + "px", height: h + "px" }); parent.appendChild(d); return d; }
function bar(parent, opts, x, y, w, h) {
  const c = document.createElement("canvas");
  bwipjs.toCanvas(c, Object.assign({ scale: 3, backgroundcolor: "F4F3EF" }, opts));
  Object.assign(c.style, { left: x + "px", top: y + "px", width: w + "px", height: h + "px", imageRendering: "pixelated" });
  parent.appendChild(c);
}
const group = (s, n) => s.match(new RegExp(`.{1,${n}}`, "g")).join(" ");

function drawLabel(l) {
  const root = $("label");
  root.innerHTML = "";
  // Postage area: the empty box with a cross, as printed on bulk labels.
  const box = document.createElementNS("http://www.w3.org/2000/svg", "svg");
  box.setAttribute("width", 150); box.setAttribute("height", 92);
  Object.assign(box.style, { position: "absolute", left: "24px", top: "18px" });
  box.innerHTML = '<g stroke="#17171a" stroke-width="1.5" fill="none"><path d="M2 2 L148 90 M2 90 L148 2"/><path d="M148 2 V92"/></g>';
  root.appendChild(box);
  txt(root, "CARRIER – LEAVE IF NO RESPONSE", 190, 62, 15);
  rule(root, 22, 118, 436, 2);
  txt(root, "PARCEL SELECT", 0, 124, 40, { center: true });
  rule(root, 22, 172, 436, 2);

  // Return block (smaller print), route code box.
  txt(root, l.returnCode, 26, 180, 12);
  l.returnLines.forEach((t, i) => txt(root, t, 26, 194 + i * 17, 15));
  const rb = rule(root, 330, 190, 112, 50, 0); rb.style.background = "transparent"; rb.style.border = "2px solid #17171a";
  txt(root, l.routeCode, 346, 198, 28);
  txt(root, "ADDRESS SERVICE REQUESTED", 24, 262, 15);

  // 2D code (Data Matrix) and the recipient.
  const dm = `~1${l.zip5}${l.zip4}${l.tracking}${l.serial}${l.name.replace(/ /g, "")}`;
  bar(root, { bcid: "datamatrix", text: dm, scale: 4 }, 26, 298, 92, 92);
  txt(root, l.name, 134, 292, 25);
  txt(root, l.street, 134, 321, 25);
  let y = 350;
  if (l.unit) { txt(root, l.unit, 134, y, 25); y += 29; }
  txt(root, `${l.city} ${l.state} ${l.zip5}–${l.zip4}`, 134, y, 25);

  rule(root, 22, 412, 436, 2);
  txt(root, "TRACKING # eVS", 0, 420, 22, { center: true });
  bar(root, { bcid: "code128", text: `^FNC1420${l.zip5}${l.tracking}`, parsefnc: true, scale: 2, height: 22, includetext: false }, 24, 452, 432, 112);
  txt(root, group(l.tracking, 4), 0, 572, 26, { center: true, spacing: "1px" });
  rule(root, 22, 612, 436, 2);

  // Sort codes, big, then the sequence marks.
  l.sortCodes.forEach((c, i) => txt(root, c, 26 + i * 108, 620, 36));
  txt(root, l.seq, 26, 668, 24);
  txt(root, l.weight, 130, 672, 18);
  txt(root, "TEST LABEL – NOT FOR SHIPPING", 0, 702, 10, { center: true, weight: 600 });
}

// ---- Damage: warp, shading, dirt ------------------------------------------------------------------------------

function applyDamage() {
  const r = rng(seed * 7919);
  const warp = +$("warp").value, dirt = +$("dirtAmt").value, soft = +$("soft").value;
  const sheet = $("sheet"), paper = $("paper");
  // Slight perspective as if photographed by hand.
  const rx = (r() - .5) * 9 * warp, ry = (r() - .5) * 9 * warp, rz = (r() - .5) * 5 * warp;
  sheet.style.transform = `rotateX(${rx}deg) rotateY(${ry}deg) rotateZ(${rz}deg) translate(${(r() - .5) * 14}px, ${(r() - .5) * 10}px)`;
  // Gentle curl: low-frequency displacement of everything on the paper.
  const noise = $("warpNoise"), map = $("warpMap");
  noise.setAttribute("seed", String(1 + Math.floor(r() * 500)));
  noise.setAttribute("baseFrequency", `${(0.004 + r() * .006).toFixed(4)} ${(0.006 + r() * .008).toFixed(4)}`);
  map.setAttribute("scale", String((5 + r() * 9) * warp));
  paper.style.filter = `url(#warpFilter) blur(${(soft * 0.55).toFixed(2)}px) contrast(${(1 - 0.05 * soft).toFixed(2)})`;

  // Shading: a soft light band and an edge darkening, plus an optional crease.
  const ang = Math.floor(r() * 180), lightAt = 20 + r() * 60;
  let shade = `linear-gradient(${ang}deg, rgba(0,0,0,${(0.07 * warp).toFixed(3)}) 0%, rgba(255,255,255,${(0.10 * warp).toFixed(3)}) ${lightAt}%, rgba(0,0,0,${(0.10 * warp).toFixed(3)}) 100%)`;
  if (r() < 0.55) {
    const at = 25 + r() * 50, a2 = Math.floor(r() * 180);
    shade += `, linear-gradient(${a2}deg, transparent ${at - 0.6}%, rgba(0,0,0,${(0.16 * warp).toFixed(3)}) ${at}%, rgba(255,255,255,${(0.35 * warp).toFixed(3)}) ${at + 0.5}%, transparent ${at + 1.4}%)`;
  }
  $("shade").style.background = shade;
  $("shade").style.boxShadow = `inset 0 0 ${(30 + r() * 30).toFixed(0)}px rgba(60,40,10,${(0.18 * warp).toFixed(3)})`;

  drawDirt(r, dirt);
}

function drawDirt(r, amount) {
  const cv = $("dirt"), g = cv.getContext("2d");
  g.clearRect(0, 0, W, H);
  g.filter = "none"; g.globalAlpha = 1;
  if (amount <= 0) return;
  // Paper grain.
  const img = g.createImageData(W, H);
  for (let i = 0; i < img.data.length; i += 4) { img.data[i] = 50; img.data[i + 1] = 40; img.data[i + 2] = 20; img.data[i + 3] = Math.floor(r() * 22 * amount); }
  g.putImageData(img, 0, 0);
  const blob = (x, y, rx, ry, rot, color, alpha, blur) => {
    g.save(); g.filter = `blur(${blur}px)`; g.globalAlpha = Math.min(1, alpha * amount); g.fillStyle = color;
    g.translate(x, y); g.rotate(rot); g.beginPath(); g.ellipse(0, 0, rx, ry, 0, 0, 7); g.fill(); g.restore();
  };
  // Big soft smudges (grease / grime), mostly toward the edges and corners.
  const n = 3 + Math.floor(r() * 4);
  for (let i = 0; i < n; i++) {
    const edge = r() < 0.6;
    const x = edge ? (r() < .5 ? r() * 90 : W - r() * 90) : r() * W, y = edge ? r() * H : r() * H;
    blob(x, y, 25 + r() * 70, 14 + r() * 40, r() * 3, r() < .5 ? "#6b5a3a" : "#4a4a48", 0.10 + r() * 0.16, 10 + r() * 14);
  }
  // Thumbprint-like ovals: a few concentric faint rings.
  if (r() < 0.7) {
    const x = 60 + r() * 360, y = 40 + r() * 640, rot = r() * 3;
    for (let k = 0; k < 7; k++) blob(x, y, 26 - k * 3, 36 - k * 4, rot, "#5a5040", 0.05, 1.6);
  }
  // Scuffs and scratches.
  g.save(); g.filter = "blur(0.6px)"; g.strokeStyle = "#555"; g.lineCap = "round";
  for (let i = 0; i < 4 + Math.floor(r() * 6); i++) {
    g.globalAlpha = (0.12 + r() * 0.18) * amount; g.lineWidth = 0.6 + r() * 1.4;
    const x = r() * W, y = r() * H, a = r() * 6.28, len = 15 + r() * 60;
    g.beginPath(); g.moveTo(x, y); g.lineTo(x + Math.cos(a) * len, y + Math.sin(a) * len); g.stroke();
  }
  g.restore();
  // Specks.
  for (let i = 0; i < 40 + Math.floor(r() * 50); i++) blob(r() * W, r() * H, 0.6 + r() * 1.8, 0.6 + r() * 1.8, 0, "#3a3328", 0.25 + r() * 0.35, 0.4);
  g.filter = "none"; g.globalAlpha = 1;
}

async function newAddress() {
  const res = await fetch("/api/label", { cache: "no-store" });
  current = await res.json();
  seed = Math.floor(Math.random() * 1e6);
  render();
}
function render() {
  drawLabel(current);
  applyDamage();
  $("meta").textContent = `${current.street}, ${current.city} ${current.zip5}`;
}

$("newAddr").onclick = newAddress;
$("reroll").onclick = () => { seed = Math.floor(Math.random() * 1e6); applyDamage(); };
for (const id of ["warp", "dirtAmt", "soft"]) $(id).oninput = applyDamage;
addEventListener("keydown", (e) => {
  if (e.target.tagName === "INPUT") return;
  if (e.code === "Space") { e.preventDefault(); newAddress(); }
  else if (e.key === "r" || e.key === "R") $("reroll").click();
});
if (/clean/.test(location.search)) for (const id of ["warp", "dirtAmt", "soft"]) $(id).value = 0; // ?clean: undamaged label
if (window.__first) { current = window.__first; seed = Math.floor(Math.random() * 1e6); render(); } else newAddress();
