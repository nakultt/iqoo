#!/usr/bin/env python3
"""Apple demo: box QR labels + goods photos + a mismatch photo.

Product: fresh apples from Himachal Orchard Farms (PO-2025-4600), packed as
master box MB-4600-01 with 2 little boxes inside, one variety per box:

  B01: 6 x Royal Gala Apples, loose (APL-1001, red apples)
  B02: 4 x Shimla Green Apples, loose (APL-1002, green apples)

Mismatch photo: a crate of oranges - photographing it against either apple
QR must come back "does not match".

Outputs (in this folder):
  qr-apple-master.png / qr-apple-box1.png / qr-apple-box2.png
  goods-apples-red.png / goods-apples-green.png / goods-oranges.png
  payloads-apple.txt

Demo: scan qr-apple-box1.png in the app (Receive -> Start Receiving), then
photograph goods-apples-red.png (AI: match, phone shouts it). Then retake
with goods-oranges.png on screen (AI: no match, phone shouts that too).

Usage: python3 tools/sample-qr/make_apple_demo.py
"""
import base64
from pathlib import Path

import qrcode
from PIL import Image, ImageDraw, ImageFont

OUT = Path(__file__).resolve().parent

PO_LINE = "PO-2025-4600 - PL-2025-4600-A"
SUPPLIER = "Himachal Orchard Farms"

LABELS = [
    {
        "file": "qr-apple-master",
        "kind": "BIG BOX (master, outer)",
        "box_id": "MB-4600-01",
        "title": "MB-4600-01 - the big outer box",
        "detail": "2 little boxes inside - 10 packs of apples altogether",
        "payload": "VTBOX1 MASTER PO:PO-2025-4600 PL:PL-2025-4600-A ID:MB-4600-01 BOXES:2 UNITS:10",
    },
    {
        "file": "qr-apple-box1",
        "kind": "LITTLE BOX 1 OF 2 - APPLES",
        "box_id": "MB-4600-01-B01",
        "title": "6 x Royal Gala Apples, loose",
        "detail": "APL-1001 x 6 - inside MB-4600-01",
        "payload": "VTBOX1 BOX PO:PO-2025-4600 PL:PL-2025-4600-A ID:MB-4600-01-B01 IN:MB-4600-01 NO:1/2 ITEMS:APL-1001*6",
    },
    {
        "file": "qr-apple-box2",
        "kind": "LITTLE BOX 2 OF 2 - APPLES",
        "box_id": "MB-4600-01-B02",
        "title": "4 x Shimla Green Apples, loose",
        "detail": "APL-1002 x 4 - inside MB-4600-01",
        "payload": "VTBOX1 BOX PO:PO-2025-4600 PL:PL-2025-4600-A ID:MB-4600-01-B02 IN:MB-4600-01 NO:2/2 ITEMS:APL-1002*4",
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


def draw_apple(draw, cx, cy, r, color, number=None, leaf=True):
    draw.ellipse([cx - r, cy - r, cx + r, cy + r], fill=color, outline=(60, 20, 20), width=4)
    draw.arc([cx - r * 0.55, cy - r * 0.35, cx + r * 0.15, cy + r * 0.45], start=200, end=340, fill=(255, 255, 255), width=6)
    draw.rectangle([cx - 4, cy - r - 16, cx + 4, cy - r + 4], fill=(90, 60, 30))
    if leaf:
        draw.ellipse([cx + 4, cy - r - 24, cx + 34, cy - r - 6], fill=(46, 125, 50))
    if number is not None:
        bx, by, br = cx - r + 6, cy - r + 6, 30
        draw.ellipse([bx - br, by - br, bx + br, by + br], fill="white", outline=(30, 30, 30), width=4)
        txt = str(number)
        f = font(40)
        bb = draw.textbbox((0, 0), txt, font=f)
        draw.text((bx - (bb[2] - bb[0]) / 2, by - (bb[3] - bb[1]) / 2 - bb[1]), txt, font=f, fill=(20, 20, 20))


def draw_crate(draw, x0, y0, x1, y1, title):
    draw.rectangle([x0, y0, x1, y1], fill=(150, 100, 55), outline=(80, 52, 25), width=8)
    # slats
    for i in range(1, 4):
        y = y0 + (y1 - y0) * i / 4
        draw.line([x0, y, x1, y], fill=(80, 52, 25), width=4)
    draw.rectangle([x0 + 20, y0 + 16, x0 + 620, y0 + 76], fill="white", outline=(60, 60, 60), width=3)
    draw.text((x0 + 34, y0 + 24), title, font=font(34), fill=(20, 20, 20))


def goods_photo(path, title, footer, fruits):
    """fruits: list of (color, count, variety) — big numbered fruit, no crate.

    The demo photo is judged by kind and count, so each fruit is large,
    numbered, and well separated on a plain dark background.
    """
    img = Image.new("RGB", (W, H), (30, 34, 42))
    draw = ImageDraw.Draw(img)
    draw.rectangle([0, 0, W, 110], fill=(164, 18, 63))
    draw.text((40, 28), title, font=font(44), fill="white")
    total = sum(count for _, count, _ in fruits)
    cols = 3
    rows = (total + cols - 1) // cols
    r = 105 if total > 4 else 125
    step_x, step_y = 2 * r + 90, 2 * r + 80
    ox = (W - (cols * step_x - 90)) / 2 + r
    oy = (H - ((rows * step_y - 80))) / 2 + 20
    n = 0
    idx = 0
    for color, count, _variety in fruits:
        for _ in range(count):
            n += 1
            cx = ox + (idx % cols) * step_x
            cy = oy + (idx // cols) * step_y
            draw_apple(draw, cx, cy, r, color, number=n)
            idx += 1
    variety = fruits[0][2] if fruits else ""
    draw.text((40, H - 100), footer, font=font(30), fill=(220, 225, 235))
    img.save(path)
    print(f"wrote {path.name} ({total} {variety})")


def main() -> None:
    images = {}
    for label in LABELS:
        qr = qrcode.QRCode(error_correction=qrcode.constants.ERROR_CORRECT_Q, box_size=12, border=4)
        qr.add_data(label["payload"])
        qr.make(fit=True)
        img = qr.make_image(fill_color="black", back_color="white")
        path = OUT / f'{label["file"]}.png'
        img.save(path)
        print(f"wrote {path.name} ({img.size[0]}x{img.size[1]}px)")
        with open(path, "rb") as f:
            images[label["file"]] = base64.b64encode(f.read()).decode()

    with open(OUT / "payloads-apple.txt", "w") as f:
        for label in LABELS:
            f.write(f'[{label["kind"]}] {label["box_id"]}\n{label["payload"]}\n\n')
    print("wrote payloads-apple.txt")

    footer = "Scan the apple box QR, then photograph this image"
    goods_photo(OUT / "goods-apples-red.png", "6 red apples - Royal Gala", footer,
                [((200, 30, 30), 6, "red apples")])
    goods_photo(OUT / "goods-apples-green.png", "4 green apples - Shimla Green", footer,
                [((110, 170, 60), 4, "green apples")])
    goods_photo(OUT / "goods-oranges.png", "5 oranges - NOT apples", footer,
                [((240, 140, 20), 5, "oranges")])


if __name__ == "__main__":
    main()
