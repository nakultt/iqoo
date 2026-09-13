#!/usr/bin/env python3
"""Mock goods photos for the AI photo-check demo.

Real goods aren't on hand, so these are clean, high-contrast stand-ins to
display on a screen (or print) and photograph with the phone on the
"Does it match?" step:

  goods-box1.png - 3 x Dell UltraSharp 27" Monitor (ELC-2710)
  goods-box2.png - 4 x Logitech Mechanical Keyboard (ELC-1180)

Each shows exactly N labelled cartons, so the AI can count them against the
box QR's declaration. Usage: python3 tools/sample-qr/make_goods_photos.py
"""
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

OUT = Path(__file__).resolve().parent

BOXES = [
    {
        "file": "goods-box1",
        "count": 3,
        "product": 'Dell UltraSharp 27" Monitor',
        "sku": "ELC-2710",
        "box_id": "MB-4471-01-B01",
    },
    {
        "file": "goods-box2",
        "count": 4,
        "product": "Logitech Mechanical Keyboard",
        "sku": "ELC-1180",
        "box_id": "MB-4471-01-B02",
    },
]

W, H = 1200, 900


def font(size: int):
    for name in ("DejaVuSans-Bold.ttf", "DejaVuSans.ttf"):
        try:
            return ImageFont.truetype(name, size)
        except OSError:
            continue
    return ImageFont.load_default()


def draw_carton(draw: ImageDraw.ImageDraw, x0, y0, x1, y1, label: str, sub: str):
    draw.rectangle([x0, y0, x1, y1], fill=(176, 124, 66), outline=(90, 60, 30), width=6)
    # tape strip + flaps
    draw.rectangle([(x0 + x1) / 2 - 14, y0, (x0 + x1) / 2 + 14, y1], fill=(210, 190, 150))
    draw.line([x0, y0, x1, y1], fill=(90, 60, 30), width=2)
    # white shipping label
    pad = 24
    draw.rectangle([x0 + pad, y0 + pad, x1 - pad, y0 + pad + 120], fill="white", outline=(60, 60, 60), width=3)
    f1, f2 = font(34), font(30)
    draw.text((x0 + pad + 14, y0 + pad + 12), label, font=f1, fill=(20, 20, 20))
    draw.text((x0 + pad + 14, y0 + pad + 60), sub, font=f2, fill=(60, 60, 60))


def main() -> None:
    for spec in BOXES:
        img = Image.new("RGB", (W, H), (38, 50, 66))
        draw = ImageDraw.Draw(img)
        # floor
        draw.rectangle([0, H - 140, W, H], fill=(70, 78, 90))
        n = spec["count"]
        cols = 2 if n > 2 else n
        rows = (n + cols - 1) // cols
        cw, chh = 500, 300
        gap = 60
        grid_w = cols * cw + (cols - 1) * gap
        grid_h = rows * chh + (rows - 1) * gap
        ox, oy = (W - grid_w) / 2, (H - 140 - grid_h) / 2 - 40
        for i in range(n):
            cx = ox + (i % cols) * (cw + gap)
            cy = oy + (i // cols) * (chh + gap)
            draw_carton(draw, cx, cy, cx + cw, cy + chh, spec["product"], spec["sku"])
        # header banner
        draw.rectangle([0, 0, W, 110], fill=(164, 18, 63))
        draw.text((40, 28), f'{spec["count"]} x {spec["product"]}', font=font(44), fill="white")
        draw.text((40, H - 100), f'Box {spec["box_id"]} - scan the box QR, then photograph this image',
                  font=font(30), fill=(220, 225, 235))
        path = OUT / f'{spec["file"]}.png'
        img.save(path)
        print(f"wrote {path.name} ({W}x{H}px)")


if __name__ == "__main__":
    main()
