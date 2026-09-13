package com.veritransit.inspector.data

/**
 * The exact payload encoded in the Red Apples consignment QR — the JSON in
 * `apples-qr-payload.json` at the repo root, rendered to `apples-qr.png` /
 * `apples-qr.svg`.
 *
 * Kept byte-for-byte in sync with the generator's output so the JVM tests
 * exercise the same string the camera hands [QrLabel.parse] on device. If the
 * payload file changes, change this with it and regenerate the QR.
 */
const val APPLES_QR_PAYLOAD =
    """{"product":"Red Apples","grade":"A","size":"72-80 mm","sku":"PRD-8101","gtin":"2000000000008","hsn":"0808.10","supplier":"Himachal Orchards LLP","supplierId":"MFG-APL-0042","origin":"Shimla, Himachal Pradesh, IN","batch":"LOT-26-1147","packDate":"2026-09-08","bestBefore":"2026-12-07","storage":"2-4 C, RH 90-95%","unitsPacked":120,"unit":"crates","unitNetKg":10,"totalNetKg":1200,"carton":"007/120","po":"PO-2026-4534","pl":"PL-2026-4534-A"}"""
