"""Convert mono.svg into an Android monochrome launcher vector."""
from __future__ import annotations

import re
from pathlib import Path

ROOT = Path(__file__).resolve().parent
SVG = ROOT / "mono.svg"
OUT = ROOT / "app" / "src" / "main" / "res" / "drawable" / "ic_launcher_monochrome.xml"
VIEW = 108.0


def parse_paths(svg: str) -> list[str]:
    return re.findall(r'<path\s+d="([^"]+)"', svg)


def path_points(d: str) -> list[tuple[float, float]]:
    tokens = re.findall(r"[MmLlHhVvCcSsQqTtAaZz]|-?\d+\.?\d*(?:[eE][-+]?\d+)?", d)
    i = 0
    cmd = None
    x = y = 0.0
    pts: list[tuple[float, float]] = []

    def take(n: int) -> list[float]:
        nonlocal i
        vals = [float(tokens[i + k]) for k in range(n)]
        i += n
        return vals

    while i < len(tokens):
        t = tokens[i]
        if t.isalpha():
            cmd = t
            i += 1
            if cmd in "Zz":
                continue
        elif cmd is None:
            i += 1
            continue
        rel = cmd.islower()
        c = cmd.lower()
        if c == "m":
            vs = take(2)
            x = x + vs[0] if rel else vs[0]
            y = y + vs[1] if rel else vs[1]
            pts.append((x, y))
            cmd = "l" if rel else "L"
        elif c == "l":
            vs = take(2)
            x = x + vs[0] if rel else vs[0]
            y = y + vs[1] if rel else vs[1]
            pts.append((x, y))
        elif c == "h":
            (v,) = take(1)
            x = x + v if rel else v
            pts.append((x, y))
        elif c == "v":
            (v,) = take(1)
            y = y + v if rel else v
            pts.append((x, y))
        elif c == "c":
            vs = take(6)
            if rel:
                p1 = (x + vs[0], y + vs[1])
                p2 = (x + vs[2], y + vs[3])
                p3 = (x + vs[4], y + vs[5])
            else:
                p1 = (vs[0], vs[1])
                p2 = (vs[2], vs[3])
                p3 = (vs[4], vs[5])
            pts.extend((p1, p2, p3))
            x, y = p3
        else:
            raise SystemExit(f"unhandled SVG command {cmd}")
    return pts


def main() -> None:
    svg = SVG.read_text(encoding="utf-8")
    paths = parse_paths(svg)
    if not paths:
        raise SystemExit("no paths in mono.svg")

    vb = re.search(r'viewBox="([\d.]+)\s+([\d.]+)\s+([\d.]+)\s+([\d.]+)"', svg)
    if not vb:
        raise SystemExit("no viewBox in mono.svg")
    _, _, vb_w, vb_h = (float(x) for x in vb.groups())
    if abs(vb_w - vb_h) > 1:
        raise SystemExit(f"expected square viewBox, got {vb_w}x{vb_h}")

    tf = re.search(
        r'transform="translate\(\s*([\d.]+)\s*,\s*([\d.]+)\s*\)\s*scale\(\s*([\d.]+)\s*,\s*([-\d.]+)\s*\)"',
        svg,
    )
    if not tf:
        raise SystemExit("expected potrace translate/scale group")
    tr_x, tr_y, sc_x, sc_y = (float(x) for x in tf.groups())

    raw: list[tuple[float, float]] = []
    for d in paths:
        raw.extend(path_points(d))
    xs = [sc_x * x + tr_x for x, _ in raw]
    ys = [sc_y * y + tr_y for _, y in raw]
    minx, maxx = min(xs), max(xs)
    miny, maxy = min(ys), max(ys)

    # Map the SVG canvas 1:1 onto the 108dp adaptive glyph, same framing as logo-app.png.
    to_view = VIEW / vb_w
    gx, gy = sc_x * to_view, sc_y * to_view
    gtx, gty = tr_x * to_view, tr_y * to_view

    print(f"paths={len(paths)} viewBox={vb_w:.0f}x{vb_h:.0f}")
    print(f"content-bbox=({minx:.1f},{miny:.1f})-({maxx:.1f},{maxy:.1f})")
    print(f"content-frac={(maxx-minx)/vb_w:.3f}x{(maxy-miny)/vb_h:.3f}")
    print(f"group scale=({gx:.6f},{gy:.6f}) translate=({gtx:.4f},{gty:.4f})")

    parts = [
        '<?xml version="1.0" encoding="utf-8"?>',
        '<vector xmlns:android="http://schemas.android.com/apk/res/android"',
        '    android:width="108dp"',
        '    android:height="108dp"',
        '    android:viewportWidth="108"',
        '    android:viewportHeight="108">',
        "    <!-- White glyph, transparent ground: themed icons tint the character. -->",
        f'    <group android:scaleX="{gx:.8f}" android:scaleY="{gy:.8f}"',
        f'        android:translateX="{gtx:.4f}" android:translateY="{gty:.4f}">',
    ]
    for d in paths:
        parts.append(
            "        <path\n"
            '            android:fillColor="#FFFFFFFF"\n'
            '            android:fillType="evenOdd"\n'
            f'            android:pathData="{d}" />'
        )
    parts.extend(
        [
            "    </group>",
            "</vector>",
            "",
        ]
    )
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text("\n".join(parts), encoding="utf-8")
    print(f"wrote {OUT}")


if __name__ == "__main__":
    main()
