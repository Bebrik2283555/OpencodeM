#!/usr/bin/env python3
"""Render assets/banner.png.

The MargyT banner's layout and palette: mint field, white glyph on the left
inside a faint rounded outline, wordmark and subtitle on the right. The glyph
is the speech bubble with </>, the same drawing as the launcher icon.
"""

import pathlib
import subprocess

HERE = pathlib.Path(__file__).parent

SVG = """<svg xmlns="http://www.w3.org/2000/svg" width="900" height="260" viewBox="0 0 900 260">
  <rect width="900" height="260" fill="#8DD1B0"/>
  <rect x="65" y="32" width="185" height="185" rx="58" fill="none" stroke="#9ED8BC" stroke-width="2"/>
  <g transform="translate(157.5 124.5) scale(2.25) translate(-54 -54)">
    <path fill="#fff" d="M40,30 L68,30 A14,14 0 0 1 82,44 L82,58 A14,14 0 0 1 68,72 L49,72 L38,81 L38,72 A14,14 0 0 1 26,58 L26,44 A14,14 0 0 1 40,30 Z"/>
    <path fill="none" stroke="#8DD1B0" stroke-width="4.6" stroke-linecap="round" stroke-linejoin="round" d="M44,44 L36,51 L44,58 M64,44 L72,51 L64,58 M57,39.5 L51,62.5"/>
  </g>
  <text x="285" y="148" font-family="Noto Sans" font-weight="700" font-size="82" fill="#1C2C24">MargyCode</text>
  <text x="288" y="198" font-family="Noto Sans" font-size="30" fill="#304A3C">opencode on your phone</text>
</svg>"""


def main():
    source = HERE / "banner.svg"
    source.write_text(SVG)
    out = HERE / "banner.png"
    subprocess.run(["rsvg-convert", "-w", "900", "-h", "260", str(source), "-o", str(out)], check=True)
    source.unlink()
    print("wrote", out)


if __name__ == "__main__":
    main()
