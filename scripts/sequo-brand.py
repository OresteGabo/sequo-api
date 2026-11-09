#!/usr/bin/env python3
"""Build the folded S parcel logo and render review sheets."""

import argparse
from pathlib import Path
import shutil
import struct
import subprocess
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
DEST = ROOT / "docs/branding"
DARK, PRIMARY, SECONDARY = "#002C1C", "#0B6B46", "#087F4F"
MINT, WHITE = "#7AD8A9", "#FFFFFF"
SURFACE = "#F7F8F4"

# The parcel wall itself follows an S-shaped continuous folded strip.
# Both channels open into the outer contour; there is no applied letter.
# Front plane: 224 x 216, shear .25. Extrusion: (+144, -84).
# All three faces use the same edges; both S returns have 60-unit spines.
TOP = "M72 170 L209 90.083333 Q216 86 224 88 L432 140 Q440 142 433 146.083333 L296 226 Z"
SIDE = "M296 226 L433 146.083333 Q440 142 440 150 V186 L296 270 Z M296 312 L440 228 V350 Q440 358 433 362.083333 L296 442 Z"
FRONT = "M0 0 H224 V44 H66 Q60 44 60 50 V80 Q60 86 66 86 H224 V216 H8 Q0 216 0 208 V172 H158 Q164 172 164 166 V136 Q164 130 158 130 H0 Z"
FRONT_TRANSFORM = "matrix(1 .25 0 1 72 170)"

# Custom modular SEQUO lettering: S repeats the package wall; all glyphs share
# its 44-unit bands, open channels and angled folded corners. No font is used.
WORDMARK = [
    (0, "M0 0 H192 V44 H50 V88 H192 V220 H8 Q0 220 0 210 V176 H142 V132 H0 Z"),
    (222, "M24 0 H168 V44 H44 V88 H148 V132 H44 V176 H168 V220 H0 V24 Z"),
    (420, "M30 0 H166 L196 30 V190 L180 206 L202 224 L170 248 L136 220 H30 L0 190 V30 Z M44 44 V176 H108 L124 160 L152 183 V44 Z"),
    (648, "M0 0 H44 V166 L54 176 H130 L140 166 V0 H184 V188 L152 220 H32 L0 188 Z"),
    (862, "M30 0 H166 L196 30 V190 L166 220 H30 L0 190 V30 Z M44 44 V176 H152 V44 Z"),
]


def path(d, color):
    return f'<path fill="{color}" fill-rule="evenodd" d="{d}"/>'


def symbol(mono=False):
    if mono:
        # Same physical silhouette in one ink, no artificial face separators.
        return path(TOP, WHITE) + path(SIDE, WHITE) + f'<g transform="{FRONT_TRANSFORM}">{path(FRONT, WHITE)}</g>'
    body = path(TOP, MINT) + path(SIDE, SECONDARY)
    body += f'<g transform="{FRONT_TRANSFORM}">{path(FRONT, PRIMARY)}</g>'
    return body


def svg(body, box, title):
    return (f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="{box}" role="img" '
            f'aria-labelledby="title"><title id="title">{title}</title>{body}</svg>\n')


def build():
    DEST.mkdir(parents=True, exist_ok=True)
    for name, color, mono in [("horizontal", WHITE, False), ("light", DARK, False), ("monochrome", WHITE, True)]:
        body = symbol(mono) + '<g transform="translate(548 152)">'
        for x, d in WORDMARK:
            body += f'<g transform="translate({x} 0)">{path(d, color)}</g>'
        body += '</g>'
        (DEST / f"sequo-logo-{name}.svg").write_text(svg(body, "24 40 1622 448", f"SEQUO — {name} folded parcel logo"))
    (DEST / "sequo-symbol.svg").write_text(svg(symbol(), "24 40 464 448", "Sequo parcel formed by an S-shaped folded wall"))
    for name, small in [("app-icon", False), ("favicon", True)]:
        body = f'<rect width="512" height="512" rx="112" fill="{SURFACE}"/>'
        scale = "1.10" if small else "1"
        body += f'<g transform="translate(256 256) scale({scale}) translate(-256 -264)">{symbol()}</g>'
        (DEST / f"sequo-{name}.svg").write_text(svg(body, "0 0 512 512", f"Sequo {name}"))


def validate():
    files = sorted(DEST.glob("sequo-*.svg"))
    assert len(files) == 6, "Expected exactly six delivery SVGs"
    for file in files:
        root = ET.parse(file).getroot()
        assert len(root.attrib["viewBox"].split()) == 4
        assert root.find("{http://www.w3.org/2000/svg}title") is not None
        for element in root.iter():
            assert element.tag.rsplit('}', 1)[-1] in {"svg", "title", "g", "path", "rect"}
            assert not any(key.rsplit('}', 1)[-1] in {"href", "style", "filter", "font-family"} for key in element.attrib)
        print(f"PASS XML, viewBox, accessible title, vector-only, no fonts/external resources: {file.name}")


def render():
    renderer = shutil.which("rsvg-convert")
    if not renderer:
        raise SystemExit("Preview generation requires rsvg-convert (librsvg). SVGs have no runtime dependencies.")
    previews = DEST / "previews"
    previews.mkdir(exist_ok=True)

    def png(source, target, width, background=None):
        command = [renderer, "--width", str(width), "--output", str(target)]
        if background:
            command += ["--background-color", background]
        subprocess.run(command + [str(source)], check=True)
        data = target.read_bytes()
        assert data[:8] == b'\x89PNG\r\n\x1a\n'
        assert struct.unpack('>II', data[16:24])[0] == width

    for source in sorted(DEST.glob("sequo-*.svg")):
        background = SURFACE if source.stem.endswith("light") else DARK
        png(source, previews / f"{source.stem}.png", 1200 if "logo" in source.stem else 512, background)
    for size in (24, 48, 96, 192):
        png(DEST / "sequo-symbol.svg", previews / f"symbol-{size}.png", size)
    for size in (16, 32):
        png(DEST / "sequo-favicon.svg", previews / f"favicon-{size}.png", size)
    for width in (200, 320, 640):
        png(DEST / "sequo-logo-light.svg", previews / f"full-logo-{width}.png", width, SURFACE)

    def embed(name, x, y, w, h):
        root = ET.parse(DEST / name).getroot()
        content = ''.join(ET.tostring(child, encoding="unicode") for child in root if not child.tag.endswith("title"))
        return f'<svg x="{x}" y="{y}" width="{w}" height="{h}" viewBox="{root.attrib["viewBox"]}">{content}</svg>'

    sheet = '<rect width="1440" height="1000" fill="#E9E7EB"/>'
    sheet += f'<rect x="20" y="20" width="1400" height="280" rx="16" fill="{DARK}"/>'
    sheet += embed("sequo-logo-horizontal.svg", 80, 40, 1280, 240)
    sheet += '<rect x="20" y="320" width="1400" height="280" rx="16" fill="#FFFFFF"/>'
    sheet += embed("sequo-logo-light.svg", 80, 340, 1280, 240)
    sheet += f'<rect x="20" y="620" width="780" height="200" rx="16" fill="{DARK}"/>'
    sheet += embed("sequo-logo-monochrome.svg", 40, 640, 740, 160)
    sheet += embed("sequo-app-icon.svg", 840, 640, 180, 180)
    sheet += f'<rect x="1060" y="620" width="360" height="220" rx="16" fill="{DARK}"/>'
    sheet += embed("sequo-symbol.svg", 1130, 640, 180, 180)
    sheet += f'<rect x="20" y="860" width="1400" height="120" rx="16" fill="{DARK}"/>'
    for size, x in [(24, 60), (48, 140), (96, 260)]:
        sheet += embed("sequo-symbol.svg", x, 870, size, size)
    for size, x in [(16, 500), (32, 560)]:
        sheet += embed("sequo-favicon.svg", x, 900, size, size)
    # Contact sheet contains paths only, so also remains portable offline.
    sheet_file = previews / "review-sheet.svg"
    sheet_file.write_text(svg(sheet, "0 0 1440 1000", "Sequo dark, light, monochrome, app, symbol and small-size review"))
    png(sheet_file, previews / "review-sheet.png", 1440)
    print("PASS librsvg rendering: all six variants, symbol 24/48/96/192, favicon 16/32, review sheet")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--render", action="store_true", help="Also render PNG previews and a review sheet with librsvg")
    args = parser.parse_args()
    build()
    validate()
    if args.render:
        render()
