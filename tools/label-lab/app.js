"use strict";
addEventListener("error", (e) => { const d = document.createElement("pre"); d.style.cssText = "position:fixed;left:8px;bottom:8px;max-width:90vw;background:#400;color:#fcc;padding:8px;font:12px monospace;z-index:99"; d.textContent = e.message + " @" + e.lineno; document.body.appendChild(d); });
const S = 1.5;                 // layout below is drawn in a 480 x 720 space and scaled up
const W = 480 * S, H = 720 * S;
const $ = (id) => document.getElementById(id);
let current = null;
let seed = 1;

// Small seeded PRNG so "Re-roll" gives a different but repeatable look.
function rng(s) { let a = s >>> 0; return () => { a = (a + 0x6D2B79F5) >>> 0; let t = a; t = Math.imul(t ^ (t >>> 15), t | 1); t ^= t + Math.imul(t ^ (t >>> 7), t | 61); return ((t ^ (t >>> 14)) >>> 0) / 4294967296; }; }

function txt(parent, text, x, y, size, opts = {}) {
  const d = document.createElement("div");
  d.className = "abs";
  d.textContent = text;
  d.style.left = x * S + "px"; d.style.top = y * S + "px"; d.style.fontSize = size * S + "px";
  if (opts.center) { const cx = (opts.cx ?? 240) * S; d.style.left = "0"; d.style.width = 2 * cx + "px"; d.style.textAlign = "center"; }
  if (opts.rot) { d.style.transformOrigin = "0 0"; d.style.transform = `rotate(${opts.rot}deg)`; }
  if (opts.inverse) { d.style.background = "#17171a"; d.style.color = "#f1f1ef"; d.style.padding = `0 ${2 * S}px`; }
  if (opts.spacing) d.style.letterSpacing = opts.spacing;
  if (opts.weight) d.style.fontWeight = opts.weight;
  parent.appendChild(d);
  return d;
}
function rule(parent, x, y, w, h) { const d = document.createElement("div"); d.className = "rule"; Object.assign(d.style, { left: x * S + "px", top: y * S + "px", width: w * S + "px", height: h * S + "px" }); parent.appendChild(d); return d; }
function bar(parent, opts, x, y, w, h) {
  const c = document.createElement("canvas");
  bwipjs.toCanvas(c, Object.assign({ scale: 3, backgroundcolor: "F1F1EE" }, opts));
  Object.assign(c.style, { left: x * S + "px", top: y * S + "px", width: w * S + "px", height: h * S + "px", imageRendering: "pixelated" });
  parent.appendChild(c);
}
const group = (s, n) => s.match(new RegExp(`.{1,${n}}`, "g")).join(" ");

const rnd = (n, set = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz0123456789") => Array.from({ length: n }, () => set[Math.floor(Math.random() * set.length)]).join("");

function drawLabel(l) {
  const root = $("label");
  root.innerHTML = "";
  // Postage area: the empty box with a cross, and a small indicia box with generic text.
  const box = document.createElementNS("http://www.w3.org/2000/svg", "svg");
  box.setAttribute("width", 120 * S); box.setAttribute("height", 62 * S);
  Object.assign(box.style, { position: "absolute", left: "0px", top: 50 * S + "px" });
  box.innerHTML = `<g stroke="#17171a" stroke-width="${1.2 * S}" fill="none"><path d="M${S} ${S} L${116 * S} ${60 * S} M${S} ${60 * S} L${116 * S} ${S}"/><path d="M${116 * S} ${S} V${62 * S}"/></g>`;
  root.appendChild(box);
  txt(root, "CARRIER \u2013 LEAVE IF NO RESPONSE", 121, 80, 14);
  const ind = rule(root, 336, 52, 116, 56, 0); ind.style.background = "transparent"; ind.style.border = `${1 * S}px solid #17171a`;
  ["PARCEL SELECT", "POSTAGE PAID", "SAMPLE", "TEST"].forEach((t, i) => txt(root, t, 0, 56 + i * 11.5, 9.5, { center: true, cx: 394, weight: 600 }));
  rule(root, 0, 112, 480, 1.5);
  txt(root, "PARCEL SELECT", 0, 117, 29, { center: true, cx: 222 });
  rule(root, 0, 137, 480, 1);

  // Return block (small print) and the route code box.
  l.returnLines.forEach((t, i) => txt(root, t, 9, 143 + i * 11.8, 12.5));
  const rb = rule(root, 241, 150, 44, 27, 0); rb.style.background = "transparent"; rb.style.border = `${1.4 * S}px solid #17171a`;
  txt(root, l.routeCode, 0, 154, 16.5, { center: true, cx: 263 });
  txt(root, "ADDRESS SERVICE REQUESTED", 6, 208, 11);

  // Small Data Matrix and the recipient (the text the scanner has to read).
  const dm = `~1${l.zip5}${l.zip4}${l.tracking}${l.serial}`;
  bar(root, { bcid: "datamatrix", text: dm, scale: 4 }, 14, 236, 46, 46);
  txt(root, l.name, 76, 226, 16.5);
  txt(root, l.street, 76, 240.5, 16.5);
  let y = 255;
  if (l.unit) { txt(root, l.unit, 76, y, 16.5); y += 14.5; }
  txt(root, `${l.city} ${l.state} ${l.zip5}\u2013${l.zip4}`, 76, y, 16.5);
  bar(root, { bcid: "datamatrix", text: `${l.serial}${l.zip5}${l.zip4}${rnd(12, "0123456789")}`, scale: 4 }, 385, 304, 47, 47);

  rule(root, 0, 353, 480, 1.5);
  txt(root, "TRACKING # eVS", 81, 358, 15);
  bar(root, { bcid: "code128", text: `^FNC1420${l.zip5}${l.tracking}`, parsefnc: true, scale: 2, height: 22, includetext: false }, 14, 383, 352, 74);
  txt(root, group(l.tracking, 4), 110, 466, 18);
  rule(root, 0, 493, 480, 1.5);

  // Four Data Matrix codes with the sequence marks down both sides.
  for (let i = 0; i < 4; i++) bar(root, { bcid: "datamatrix", text: rnd(46), scale: 4 }, 28 + i * 104.5, 537, 85, 83);
  const side = `${rnd(9)}   ${l.seq.replace(/\D/g, "")[0] || 3} / ${rnd(4, "0123456789")}`;
  txt(root, side, 9, 610, 9.5, { rot: -90 });
  txt(root, side, 449, 547, 9.5, { rot: 90 });

  // Sort-code boxes.
  rule(root, 0, 636, 100, 0.8, 0); rule(root, 100, 636, 0.8, 84); rule(root, 224, 636, 0.8, 84);
  const a1 = l.sortCodes[0], b1 = l.sortCodes[2];
  txt(root, a1, 0, 636, 27, { center: true, cx: 52 });
  txt(root, a1[0], 8, 664, 21, { inverse: true });
  txt(root, a1.slice(1), 29, 664, 21);
  txt(root, b1, 0, 636, 27, { center: true, cx: 163 });
  txt(root, "D", 112, 662, 21, { inverse: true }); txt(root, "001", 134, 662, 21);
  txt(root, "\u2192", 106, 683, 18); txt(root, "B", 128, 683, 21, { inverse: true }); txt(root, "001", 152, 683, 21);
  txt(root, "TEST LABEL \u2013 NOT FOR SHIPPING", 0, 702, 8.5, { center: true, weight: 600, cx: 350 });
}

// ---- Damage: warp, shading, dirt ------------------------------------------------------------------------------

function applyDamage() {
  const r = rng(seed * 7919);
  const warp = +$("warp").value, dirt = +$("dirtAmt").value, soft = +$("soft").value;
  const fade = +$("fade").value, streak = +$("streak").value, feed = +$("feed").value;
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
  printerDefects(r, fade, streak, feed);
}

// ---- Printer defects ------------------------------------------------------------------------------------------

function printerDefects(r, fade, streak, feed) {
  const label = $("label"), cv = $("defects"), g = cv.getContext("2d");
  g.clearRect(0, 0, W, H);

  // Uneven feed: vertical displacement that depends only on the row. Bands compress (bunch) where it falls quickly.
  const fn = $("feedNoise"), fm = $("feedMap");
  fn.setAttribute("seed", String(1 + Math.floor(r() * 900)));
  fn.setAttribute("baseFrequency", `0 ${(0.003 + r() * 0.004).toFixed(4)}`);
  fm.setAttribute("scale", String(Math.round((14 + r() * 16) * feed * S)));
  label.style.filter = feed > 0 ? "url(#feedFilter)" : "none";

  // Fade: a worn head prints lighter overall, and some patches almost vanish.
  const ink = Math.round(23 + 90 * Math.min(1, fade * (0.15 + r() * 0.35)));
  label.style.color = `rgb(${ink},${ink},${ink + 2})`;
  const dim = (el, p) => { el.style.opacity = r() < p ? String((0.28 + r() * 0.5).toFixed(2)) : ""; };
  label.querySelectorAll(".abs").forEach((el) => { el.style.opacity = ""; if (fade > 0) dim(el, 0.12 * fade); });
  label.querySelectorAll("canvas").forEach((el) => { el.style.opacity = ""; if (fade > 0 && r() < 0.15 * fade) el.style.opacity = String((0.55 + r() * 0.3).toFixed(2)); });
  if (fade > 0) {
    // Washed-out zones across the print: horizontal-ish bands where the head ran cold, lighter toward one side.
    const zones = 1 + Math.floor(r() * 2 * fade);
    for (let i = 0; i < zones; i++) {
      const y = r() * H, hgt = (50 + r() * 140) * S / 1.5, x0 = r() * W * 0.5;
      const grad = g.createLinearGradient(x0, 0, W, 0);
      const a = Math.min(0.7, (0.25 + r() * 0.3) * fade);
      grad.addColorStop(0, `rgba(241,241,238,${a})`); grad.addColorStop(1, `rgba(241,241,238,${a * 0.25})`);
      g.fillStyle = grad; g.fillRect(0, y, W, hgt);
    }
  }

  // Dead print-head pixels: thin white vertical lines (some run the full height, some stop part-way) plus a few skipped rows.
  if (streak > 0) {
    const n = Math.round((2 + r() * 5) * streak);
    for (let i = 0; i < n; i++) {
      const x = r() * W, w = (0.8 + r() * 2.6) * S / 1.5;
      const y0 = r() < 0.6 ? 0 : r() * H * 0.7, y1 = r() < 0.6 ? H : Math.min(H, y0 + r() * H);
      g.fillStyle = `rgba(241,241,238,${(0.85 + r() * 0.15).toFixed(2)})`;
      g.fillRect(x, y0, w, y1 - y0);
    }
    const skips = Math.round(r() * 3 * streak);
    for (let i = 0; i < skips; i++) {
      const y = r() * H, hgt = (1 + r() * 2.2) * S / 1.5, x0 = r() * W * 0.3;
      g.fillStyle = "rgba(241,241,238,0.92)"; g.fillRect(x0, y, W - x0 - r() * W * 0.2, hgt);
    }
  }
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
for (const id of ["warp", "dirtAmt", "soft", "fade", "streak", "feed"]) $(id).oninput = applyDamage;
addEventListener("keydown", (e) => {
  if (e.target.tagName === "INPUT") return;
  if (e.code === "Space") { e.preventDefault(); newAddress(); }
  else if (e.key === "r" || e.key === "R") $("reroll").click();
});
const qs = new URLSearchParams(location.search);
for (const [k, id] of [["warp", "warp"], ["dirt", "dirtAmt"], ["soft", "soft"], ["fade", "fade"], ["streak", "streak"], ["feed", "feed"]]) if (qs.has(k)) $(id).value = qs.get(k);
if (qs.has("shot")) document.body.classList.add("shot");
if (qs.has("fit")) {      // scale the label to fill the window height, as a phone camera would like it
  const fit = () => { const k = Math.min((innerHeight - 40) / 1100, innerWidth / 760); $("scene").style.transform = `scale(${k})`; $("scene").style.transformOrigin = "top center"; };
  fit(); addEventListener("resize", fit);
}
if (qs.has("remote")) { let seen = null; setInterval(async () => { const c = await (await fetch("/api/cmd", { cache: "no-store" })).json(); if (seen !== null && c.n !== seen) newAddress(); seen = c.n; }, 700); }   // ?remote: the test driver advances labels
if (qs.has("auto")) setInterval(() => newAddress(), Math.max(3, +qs.get("auto")) * 1000);   // ?auto=SECONDS: a new label on a timer
if (/clean/.test(location.search)) for (const id of ["warp", "dirtAmt", "soft", "fade", "streak", "feed"]) $(id).value = 0; // ?clean: undamaged label
if (window.__first) { current = window.__first; seed = qs.has("seed") ? +qs.get("seed") * 31 + 7 : Math.floor(Math.random() * 1e6); render(); } else newAddress();
