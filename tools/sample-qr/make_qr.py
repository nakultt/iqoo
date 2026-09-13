#!/usr/bin/env python3
"""Sample sender-mode QR labels to scan with the VeriTransit app.

Builds the demo master box MB-4471-01 (preset PO-2025-4471,
Bright Electronics Pvt Ltd): the big outer box plus the 2 little boxes
inside it, one product per box so the photo check is easy:

  B01: 3 x Dell UltraSharp 27" Monitor (ELC-2710)
  B02: 4 x Logitech Mechanical Keyboard (ELC-1180)

The payload strings are exactly what the app's sender mode prints
(BoxLabel payloads), so Receive -> Scan Label resolves them to the
packing list, shows product names + counts + supplier, and asks for
the photo check.

Outputs (in this folder):
  qr-master.png / qr-box1.png / qr-box2.png  - large scannable PNGs
  payloads.txt                               - exact QR text per label
  print.html                                 - printable sheet (3 cards)

Usage:  python3 tools/sample-qr/make_qr.py
"""
import base64
from pathlib import Path

import qrcode

OUT = Path(__file__).resolve().parent

LABELS = [
    {
        "file": "qr-master",
        "kind": "BIG BOX (master, outer)",
        "box_id": "MB-4471-01",
        "title": "MB-4471-01 - the big outer box",
        "detail": "2 little boxes inside - 7 items altogether",
        "payload": "VTBOX1 MASTER PO:PO-2025-4471 PL:PL-2025-4471-A ID:MB-4471-01 BOXES:2 UNITS:7",
    },
    {
        "file": "qr-box1",
        "kind": "LITTLE BOX 1 OF 2",
        "box_id": "MB-4471-01-B01",
        "title": '3 x Dell UltraSharp 27" Monitor',
        "detail": "ELC-2710 x 3 - inside MB-4471-01",
        "payload": "VTBOX1 BOX PO:PO-2025-4471 PL:PL-2025-4471-A ID:MB-4471-01-B01 IN:MB-4471-01 NO:1/2 ITEMS:ELC-2710*3",
    },
    {
        "file": "qr-box2",
        "kind": "LITTLE BOX 2 OF 2",
        "box_id": "MB-4471-01-B02",
        "title": "4 x Logitech Mechanical Keyboard",
        "detail": "ELC-1180 x 4 - inside MB-4471-01",
        "payload": "VTBOX1 BOX PO:PO-2025-4471 PL:PL-2025-4471-A ID:MB-4471-01-B02 IN:MB-4471-01 NO:2/2 ITEMS:ELC-1180*4",
    },
]

SUPPLIER = "Bright Electronics Pvt Ltd"
PO = "PO-2025-4471 - PL-2025-4471-A"


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

    with open(OUT / "payloads.txt", "w") as f:
        for label in LABELS:
            f.write(f'[{label["kind"]}] {label["box_id"]}\n{label["payload"]}\n\n')
    print("wrote payloads.txt")

    cards = []
    for label in LABELS:
        cards.append(f"""
    <div class="card">
      <div class="kind">{label['kind']}</div>
      <img src="data:image/png;base64,{images[label['file']]}" alt="QR for {label['box_id']}"/>
      <div class="boxid">{label['box_id']}</div>
      <div class="title">{label['title']}</div>
      <div class="detail">{label['detail']}</div>
      <div class="meta">{SUPPLIER}<br/>{PO}</div>
      <div class="payload">{label['payload']}</div>
    </div>""")

    html = f"""<!DOCTYPE html>
<html><head><meta charset="utf-8"/>
<title>VeriTransit sample box labels - MB-4471-01</title>
<style>
  body {{ font-family: -apple-system, 'Segoe UI', sans-serif; background: #f4f1ec; color: #222; }}
  h1 {{ text-align: center; }} p.how {{ text-align: center; max-width: 640px; margin: 0 auto 24px; }}
  .sheet {{ display: flex; flex-wrap: wrap; gap: 24px; justify-content: center; }}
  .card {{ background: #fff; border: 2px dashed #999; border-radius: 8px;
           width: 320px; padding: 20px; text-align: center; }}
  .kind {{ font-size: 12px; letter-spacing: 1px; color: #a4123f; font-weight: bold; }}
  img {{ width: 260px; height: 260px; margin: 12px 0; }}
  .boxid {{ font-family: monospace; font-weight: bold; font-size: 18px; }}
  .title {{ font-weight: bold; margin-top: 8px; }}
  .detail, .meta {{ color: #555; font-size: 14px; margin-top: 4px; }}
  .payload {{ font-family: monospace; font-size: 10px; color: #888; margin-top: 10px; word-break: break-all; }}
  @media print {{ body {{ background: #fff; }} .card {{ break-inside: avoid; }} }}
</style></head>
<body>
<h1>Sample box labels - MB-4471-01</h1>
<p class="how">Open this page (or the PNGs) on a screen, or print it. In the app:
<b>Receiving -&gt; Start Receiving</b>, point the camera at any QR. The app shows
the supplier, product names and counts, then asks for a photo to confirm the match.</p>
<div class="sheet">{''.join(cards)}</div>
</body></html>"""
    with open(OUT / "print.html", "w") as f:
        f.write(html)
    print("wrote print.html")


if __name__ == "__main__":
    main()
