#!/usr/bin/env python3
"""Build the README banner: docs/images/banner.svg (text as paths) -> banner.png (1280x640).

Needs fontTools and rsvg-convert. Run from anywhere:  python3 docs/images/banner.py
"""
import subprocess
from pathlib import Path

from fontTools.pens.svgPathPen import SVGPathPen
from fontTools.pens.transformPen import TransformPen
from fontTools.ttLib import TTFont

HERE = Path(__file__).resolve().parent
FONTS = HERE.parents[1] / "app/src/main/res/font"
W, H, M, R = 1280, 640, 16, 36  # size, transparent margin, panel corner radius

TEAL, DEEP, WHITE, MUTED = "#9FD8E3", "#0F4C5C", "#FFFFFF", "#B9D3DA"


class Font:
    def __init__(self, path):
        self.f = TTFont(path)
        self.gs = self.f.getGlyphSet()
        self.cmap = self.f.getBestCmap()
        self.upm = self.f["head"].unitsPerEm
        self.kern = self._pair_kerning()

    def _pair_kerning(self):
        """Pair-adjustment kerning from GPOS 'kern' (formats 1 and 2), enough for Latin text."""
        kern = {}
        if "GPOS" not in self.f:
            return kern
        gpos = self.f["GPOS"].table
        idx = {i for fr in gpos.FeatureList.FeatureRecord if fr.FeatureTag == "kern"
               for i in fr.Feature.LookupListIndex}
        for li in idx:
            lk = gpos.LookupList.Lookup[li]
            for st in lk.SubTable:
                if lk.LookupType == 9:
                    st = st.ExtSubTable
                if getattr(st, "LookupType", 2) != 2 and lk.LookupType != 9:
                    continue
                if not hasattr(st, "Format"):
                    continue
                cov = st.Coverage.glyphs
                if st.Format == 1:
                    for g1, ps in zip(cov, st.PairSet):
                        for pvr in ps.PairValueRecord:
                            v = getattr(pvr.Value1, "XAdvance", 0) or 0
                            kern.setdefault((g1, pvr.SecondGlyph), v)
                elif st.Format == 2:
                    c1, c2 = st.ClassDef1.classDefs, st.ClassDef2.classDefs
                    self._cls = getattr(self, "_cls", [])
                    self._cls.append((set(cov), c1, c2, st.Class1Record))
        return kern

    def pair(self, a, b):
        if (a, b) in self.kern:
            return self.kern[(a, b)]
        for cov, c1, c2, recs in getattr(self, "_cls", []):
            if a in cov:
                v = recs[c1.get(a, 0)].Class2Record[c2.get(b, 0)].Value1
                x = getattr(v, "XAdvance", 0) or 0
                if x:
                    return x
        return 0

    def run(self, text, size, x, y, fill, anchor="start", tracking=0):
        """Return (svg path element, advance width in px)."""
        s = size / self.upm
        names = [self.cmap[ord(c)] for c in text]
        pen = SVGPathPen(self.gs)
        pos, xs = 0, []
        for i, g in enumerate(names):
            xs.append(pos)
            pos += self.gs[g].width + (self.pair(g, names[i + 1]) if i + 1 < len(names) else 0)
            pos += tracking * self.upm / size if i + 1 < len(names) else 0
        width = pos * s
        x0 = x - (width / 2 if anchor == "middle" else 0)
        for g, gx in zip(names, xs):
            self.gs[g].draw(TransformPen(pen, (s, 0, 0, -s, x0 + gx * s, y)))
        return f'<path fill="{fill}" d="{pen.getCommands()}"/>', width


bold = Font(FONTS / "atkinson_bold.ttf")
reg = Font(FONTS / "atkinson_regular.ttf")

# ---- the app icon (same geometry as ic_launcher_foreground.xml) ----
ICON = """
<g transform="translate(54,54) scale(0.92) translate(-55,-50.5)">
  <path d="M68,26 C59.16,26 52,33.16 52,42 C52,53.5 68,72 68,72 C68,72 84,53.5 84,42 C84,33.16 76.84,26 68,26 Z" fill="#000" fill-opacity="0.22" transform="translate(1.6,2.2)"/>
  <path d="M32,68 C32,55 44,52 46,62 C48,73 58,77 67,71" fill="none" stroke="#9FD8E3" stroke-width="4.6" stroke-linecap="round"/>
  <path fill-rule="evenodd" fill="url(#pinG)" d="M68,26 C59.16,26 52,33.16 52,42 C52,53.5 68,72 68,72 C68,72 84,53.5 84,42 C84,33.16 76.84,26 68,26 Z M62.5,36 h11 a1.5,1.5 0 0 1 1.5,1.5 v9 a1.5,1.5 0 0 1 -1.5,1.5 h-11 a1.5,1.5 0 0 1 -1.5,-1.5 v-9 a1.5,1.5 0 0 1 1.5,-1.5 Z"/>
  <path d="M66.5,36 h3 v5 h-3 Z" fill="#F4FBFC"/>
  <circle cx="32" cy="68" r="5.6" fill="#9FD8E3"/><circle cx="32" cy="68" r="2.5" fill="#0F4C5C"/>
  <circle cx="46" cy="62" r="5.6" fill="#9FD8E3"/><circle cx="46" cy="62" r="2.5" fill="#0F4C5C"/>
</g>"""


def build():
    parts = [f'<svg xmlns="http://www.w3.org/2000/svg" width="{W}" height="{H}" viewBox="0 0 {W} {H}">',
             f"""<defs>
  <linearGradient id="panel" x1="0" y1="0" x2="{W}" y2="{H}" gradientUnits="userSpaceOnUse">
    <stop offset="0" stop-color="#11505F"/><stop offset="0.55" stop-color="#0B3A46"/><stop offset="1" stop-color="#072630"/>
  </linearGradient>
  <radialGradient id="glow" cx="300" cy="300" r="420" gradientUnits="userSpaceOnUse">
    <stop offset="0" stop-color="#9FD8E3" stop-opacity="0.12"/><stop offset="1" stop-color="#9FD8E3" stop-opacity="0"/>
  </radialGradient>
  <linearGradient id="pinG" x1="56" y1="28" x2="82" y2="66" gradientUnits="userSpaceOnUse">
    <stop offset="0" stop-color="#FFFFFF"/><stop offset="1" stop-color="#D3EEF3"/>
  </linearGradient>
  <clipPath id="panelClip"><rect x="{M}" y="{M}" width="{W-2*M}" height="{H-2*M}" rx="{R}"/></clipPath>
  <clipPath id="iconClip"><rect x="18" y="18" width="72" height="72" rx="17"/></clipPath>
</defs>""",
             f'<rect x="{M}" y="{M}" width="{W-2*M}" height="{H-2*M}" rx="{R}" fill="url(#panel)"/>',
             '<g clip-path="url(#panelClip)">',
             f'<rect width="{W}" height="{H}" fill="url(#glow)"/>']

    # ---- route motif: faint winding road along the bottom, numbered stops ----
    road = "M-20,560 C140,600 250,470 400,515 S640,600 790,540 S1010,450 1130,505 S1260,560 1320,520"
    parts.append(f'<path d="{road}" fill="none" stroke="{TEAL}" stroke-opacity="0.10" stroke-width="26" stroke-linecap="round"/>')
    parts.append(f'<path d="{road}" fill="none" stroke="{TEAL}" stroke-opacity="0.55" stroke-width="4" '
                 'stroke-linecap="round" stroke-dasharray="2 14"/>')
    stops = [(250, 523, "1"), (560, 566, "2"), (880, 506, "3"), (1130, 505, "4")]
    for i, (x, y, n) in enumerate(stops):
        done = i < 2
        parts.append(f'<circle cx="{x}" cy="{y}" r="26" fill="#07262F" fill-opacity="0.6"/>')
        parts.append(f'<circle cx="{x}" cy="{y}" r="21" fill="{TEAL if done else "#0B3A46"}" '
                     f'stroke="{TEAL}" stroke-width="3"/>')
        t, _ = bold.run(n, 25, x, y + 9, DEEP if done else TEAL, anchor="middle")
        parts.append(t)
    parts.append("</g>")

    # ---- icon tile ----
    S, ix, iy = 3.2, 96, 128  # 72dp visible area -> 230px tile
    parts.append(f'<g transform="translate({ix - 18*S},{iy - 18*S}) scale({S})">'
                 f'<rect x="19" y="20.5" width="72" height="72" rx="17" fill="#000" fill-opacity="0.28"/>'
                 f'<g clip-path="url(#iconClip)"><rect width="108" height="108" fill="{DEEP}"/>{ICON}</g>'
                 f'<rect x="18.4" y="18.4" width="71.2" height="71.2" rx="16.6" fill="none" stroke="#9FD8E3" stroke-opacity="0.18" stroke-width="0.8"/>'
                 '</g>')

    # ---- text ----
    tx = 380
    t, _ = bold.run("Route Planner", 104, tx, 240, WHITE, tracking=-1)
    parts.append(t)
    t, _ = bold.run("Plan it. Drive it. Deliver it.", 44, tx + 4, 312, TEAL)
    parts.append(t)
    t, _ = reg.run("Offline-first route planning for couriers.", 32, tx + 4, 364, MUTED)
    parts.append(t)

    parts.append("</svg>")
    svg = "\n".join(parts)
    (HERE / "banner.svg").write_text(svg)
    subprocess.run(["rsvg-convert", "-w", str(W), "-h", str(H), "-o", str(HERE / "banner.png"), str(HERE / "banner.svg")], check=True)


if __name__ == "__main__":
    build()
