#!/usr/bin/env python3
"""
Generate VeriTransit demo seed SQL — MASTER_PLAN.md §2 running example.

Everything is deterministic (fixed RNG seed, uuid5 identifiers) so re-running
produces byte-identical SQL. Label signatures are *real* Ed25519 over the §4.2
token format, so a device can verify them offline exactly as it will in the
field — the seed is not decorative data.

The signing keypair is created once at db/keys/signing_key.pem and reused; only
the public half is written into the database (signing_keys).
"""
import csv, io, json, os, random, sys
from datetime import date, datetime, timedelta, timezone
from base64 import urlsafe_b64encode
from decimal import Decimal
from uuid import NAMESPACE_URL, uuid5

from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey
from cryptography.hazmat.primitives import serialization

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
KEY_PATH = os.path.join(ROOT, "keys", "signing_key.pem")
PUB_PATH = os.path.join(ROOT, "keys", "signing_key.pub")
OUT_PATH = os.path.join(ROOT, "seed", "S1__demo_data.sql")
KEY_ID = "vt-key-2026-01"

rng = random.Random(20260912)
CODE_ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"   # Crockford base32: no I L O U
EPOCH = date(1970, 1, 1)

# --------------------------------------------------------------------------
# helpers
# --------------------------------------------------------------------------

def uid(kind: str, key: str) -> str:
    return str(uuid5(NAMESPACE_URL, f"veritransit://{kind}/{key}"))

def b64u(raw: bytes) -> str:
    return urlsafe_b64encode(raw).decode().rstrip("=")

def ts(y, mo, d, h=0, mi=0, s=0) -> str:
    # IST (+05:30) — the pilot is Indian; stored as timestamptz.
    return datetime(y, mo, d, h, mi, s, tzinfo=timezone(timedelta(hours=5, minutes=30))).isoformat()

def q(v) -> str:
    """SQL literal."""
    if v is None:
        return "NULL"
    if isinstance(v, bool):
        return "true" if v else "false"
    if isinstance(v, (int, float, Decimal)):
        return str(v)
    if isinstance(v, (dict, list)):
        return "'" + json.dumps(v, separators=(",", ":"), sort_keys=True).replace("'", "''") + "'::jsonb"
    return "'" + str(v).replace("'", "''") + "'"

_used_codes = set()
def package_code() -> str:
    while True:
        c = "VT-P-" + "".join(rng.choice(CODE_ALPHABET) for _ in range(8))
        if c not in _used_codes:
            _used_codes.add(c)
            return c

# --------------------------------------------------------------------------
# signing key (§4.2 / §10 key ceremony)
# --------------------------------------------------------------------------

os.makedirs(os.path.dirname(KEY_PATH), exist_ok=True)
if os.path.exists(KEY_PATH):
    with open(KEY_PATH, "rb") as fh:
        priv = serialization.load_pem_private_key(fh.read(), password=None)
else:
    priv = Ed25519PrivateKey.generate()
    with open(KEY_PATH, "wb") as fh:
        fh.write(priv.private_bytes(serialization.Encoding.PEM,
                                    serialization.PrivateFormat.PKCS8,
                                    serialization.NoEncryption()))
    os.chmod(KEY_PATH, 0o600)

pub_raw = priv.public_key().public_bytes(serialization.Encoding.Raw,
                                         serialization.PublicFormat.Raw)
PUBKEY_B64 = b64u(pub_raw)
with open(PUB_PATH, "w") as fh:
    fh.write(PUBKEY_B64 + "\n")

def sign_label(code: str, shipment_ref: str, copy_no: int, issued: date):
    """§4.2 token: signature covers everything left of |SIG=."""
    body = f"VT1|P={code}|S={shipment_ref}|N={copy_no}|T={(issued - EPOCH).days}"
    return body, b64u(priv.sign(body.encode()))

# --------------------------------------------------------------------------
# reference data
# --------------------------------------------------------------------------

TENANT = uid("tenant", "pilot")

SITES = [
    ("KE-WH-BLR", "Kumar Electronics Warehouse, Bengaluru", "WAREHOUSE",
     "Plot 42, Peenya Industrial Area, Bengaluru 560058", 13.028000, 77.518000),
    ("ZD-DC-HYD", "Zen Digital DC, Hyderabad", "DELIVERY",
     "Survey 118, Medchal Road, Hyderabad 500100", 17.535000, 78.487000),
    ("NH44-CP",   "NH-44 Check Post, Kurnool", "GATE",
     "NH-44 Toll Plaza, Kurnool 518002", 15.828000, 78.037000),
    ("BC-WH-CHN", "Bharat Components Warehouse, Chennai", "WAREHOUSE",
     "Unit 7, Ambattur Estate, Chennai 600058", 13.098000, 80.161000),
    ("MR-DC-PUN", "Metro Retail DC, Pune", "DELIVERY",
     "Gate 3, Chakan MIDC, Pune 410501", 18.760000, 73.860000),
]

USERS = [
    ("anil.rao",     "Anil Rao",      "ADMIN",            "anil.rao@veritransit.in",     "anilrao"),
    ("priya.menon",  "Priya Menon",   "SUPERVISOR",       "priya.menon@kumarelec.in",    "priyamenon"),
    ("ravi.kumar",   "Ravi Kumar",    "PACKER",           "ravi.kumar@kumarelec.in",     None),
    ("deepak.shah",  "Deepak Shah",   "OFFICER",          "deepak.shah@kumarelec.in",    "deepakshah"),
    ("sunita.das",   "Sunita Das",    "OFFICER",          "sunita.das@zendigital.in",    "sunitadas"),
    ("farah.sheikh", "Farah Sheikh",  "FINANCE_MAKER",    "farah.sheikh@zendigital.in",  "farahsheikh"),
    ("vikram.iyer",  "Vikram Iyer",   "FINANCE_CHECKER",  "vikram.iyer@zendigital.in",   "vikramiyer"),
    ("meera.nair",   "Meera Nair",    "OFFICER",          "meera.nair@bharatcomp.in",    None),
]

DEVICES = [
    ("dock-a-1",  "Dock-A Handheld 1",    "KE-WH-BLR", True,  ts(2026,7,14,9,12)),
    ("dock-a-2",  "Dock-A Handheld 2",    "KE-WH-BLR", False, ts(2026,7,14,9,28)),
    ("recv-hyd-1","Receiving Handheld 1", "ZD-DC-HYD", True,  ts(2026,7,16,10,5)),
    ("gate-nh44", "Gate Tablet NH-44",    "NH44-CP",   False, ts(2026,7,20,8,0)),
    ("dock-chn-1","Chennai Dock Handheld","BC-WH-CHN", True,  ts(2026,8,2,9,40)),
]

PARTIES = [
    ("kumar",   "SUPPLIER",    "Kumar Electronics Pvt Ltd", "29AABCK1234M1Z5", "AABCK1234M",
     {"segment": "consumer electronics", "onboarded": "2026-06-02", "msme": True}),
    ("zen",     "BUYER",       "Zen Digital Retail Ltd",    "36AACCZ5678N1Z2", "AACCZ5678N",
     {"segment": "multi-brand retail", "onboarded": "2026-05-28", "stores": 44}),
    ("safemove","TRANSPORTER", "SafeMove Logistics LLP",    "29AACCS9012P1Z8", "AACCS9012P",
     {"fleet": 62, "onboarded": "2026-06-10"}),
    ("bharat",  "SUPPLIER",    "Bharat Components Pvt Ltd", "33AABCB3456Q1Z1", "AABCB3456Q",
     {"segment": "accessories", "onboarded": "2026-07-19", "msme": True}),
    ("shakti",  "SUPPLIER",    "Shakti Traders",            "27AAFCS7890R1Z4", "AAFCS7890R",
     {"segment": "audio", "onboarded": "2026-06-25", "watchlist": True}),
    ("metro",   "BUYER",       "Metro Retail India Ltd",    "27AAACM2345L1Z9", "AAACM2345L",
     {"segment": "large format retail", "onboarded": "2026-05-30", "stores": 19}),
]

# SKU catalogue — rates are per-unit INR, ex-tax.
SKUS = {
    "KE-TWS-P2":   ("TWS Earbuds P2 (retail box)",        "85183000", Decimal("280.00")),
    "KE-SP-A15":   ("Smartphone A15 6.1in 128GB",         "85171300", Decimal("17480.00")),
    "KE-PB-10K":   ("Power Bank 10000mAh",                "85076000", Decimal("240.00")),
    "BC-CBL-C100": ("USB-C Cable 1m braided",             "85444299", Decimal("95.00")),
    "BC-ADP-33W":  ("33W Fast Charge Adapter",            "85044030", Decimal("410.00")),
    "ST-SPKR-BT5": ("Bluetooth Speaker BT5",              "85182200", Decimal("890.00")),
}

# --------------------------------------------------------------------------
# shipment specifications — the §2 running example plus the history around it
# --------------------------------------------------------------------------
# line = (po_line_no, sku, masters, units_per_master, inners_are_labelled)

SHIPMENTS = [
    dict(
        ref="SHP-2026-090187", vehicle="KA-01-AB-2288",
        origin="KE-WH-BLR", dest="ZD-DC-HYD",
        supplier="kumar", buyer="zen", transporter="safemove",
        status="RECEIVED",
        created=ts(2026,8,20,10,15), dispatched=ts(2026,8,21,16,40), received=ts(2026,8,23,11,20),
        lines=[(1,"KE-TWS-P2",40,10,True), (2,"KE-PB-10K",20,10,True)],
        po_no="ZD-4402", po_date=date(2026,8,18), invoice_no="KE-2203", invoice_date=date(2026,8,21),
        ewb_no="381004918227", lr_no="SM-LR-87903",
        po_qty_override={},            # PO agrees with invoice — the clean case
        load_day=(2026,8,21), recv_day=(2026,8,23),
        verify_inners_on_receipt=False,   # LOW band: master scan alone (§4.4)
    ),
    dict(
        ref="SHP-2026-090231", vehicle="KA-01-AB-4471",
        origin="KE-WH-BLR", dest="ZD-DC-HYD",
        supplier="kumar", buyer="zen", transporter="safemove",
        status="FLAGGED",
        created=ts(2026,9,7,9,5), dispatched=ts(2026,9,8,18,25), received=ts(2026,9,11,10,40),
        lines=[(1,"KE-TWS-P2",76,10,True), (2,"KE-SP-A15",6,10,True), (3,"KE-PB-10K",66,10,True)],
        po_no="ZD-4471", po_date=date(2026,8,28), invoice_no="KE-2291", invoice_date=date(2026,9,8),
        ewb_no="381005472913", lr_no="SM-LR-88214",
        po_qty_override={2: 55},       # §5.1: PO 55 vs invoice 60 → ₹87,400 delta
        load_day=(2026,9,8), recv_day=(2026,9,11),
        verify_inners_on_receipt=True,    # MEDIUM band: open and verify every inner
        inner_shortage_line=2,            # §2.2: a master declaring 10 holds only 9
    ),
    dict(
        ref="SHP-2026-090244", vehicle="TN-09-CD-7731",
        origin="BC-WH-CHN", dest="ZD-DC-HYD",
        supplier="bharat", buyer="zen", transporter="safemove",
        status="LOADING",
        created=ts(2026,9,11,14,30), dispatched=None, received=None,
        lines=[(1,"BC-CBL-C100",60,20,False),   # supplier-preprinted inners, master declares count
               (2,"BC-ADP-33W",30,10,True)],
        po_no="ZD-4488", po_date=date(2026,9,9), invoice_no=None, invoice_date=None,
        ewb_no="381005611044", lr_no="SM-LR-88390",
        po_qty_override={},
        load_day=(2026,9,12), recv_day=None,
        loaded_masters=62,             # still loading right now
        verify_inners_on_receipt=False,
    ),
    dict(
        ref="SHP-2026-090198", vehicle="MH-12-EF-9004",
        origin="KE-WH-BLR", dest="MR-DC-PUN",
        supplier="shakti", buyer="metro", transporter="safemove",
        status="DISPATCHED",
        created=ts(2026,9,5,8,50), dispatched=ts(2026,9,6,19,10), received=None,
        lines=[(1,"ST-SPKR-BT5",55,10,True)],
        po_no="MR-7712", po_date=date(2026,8,30), invoice_no="ST-1108", invoice_date=date(2026,9,5),
        ewb_no="381005204488", lr_no="SM-LR-88101",
        po_qty_override={1: 500},      # invoiced 550 against a PO for 500 → ₹44,500 delta
        load_day=(2026,9,6), recv_day=None,
        verify_inners_on_receipt=False,
        high_risk_scans=True,          # HIGH band: 100% item scan at load, with incidents
    ),
    dict(
        ref="SHP-2026-090255", vehicle=None,
        origin="KE-WH-BLR", dest="MR-DC-PUN",
        supplier="kumar", buyer="metro", transporter="safemove",
        status="OPEN",
        created=ts(2026,9,12,9,30), dispatched=None, received=None,
        lines=[(1,"KE-TWS-P2",24,10,True)],
        po_no="MR-7745", po_date=date(2026,9,10), invoice_no=None, invoice_date=None,
        ewb_no=None, lr_no=None,
        po_qty_override={},
        load_day=None, recv_day=None,
        verify_inners_on_receipt=False,
    ),
]

# --------------------------------------------------------------------------
# build packages, labels and scan events
# --------------------------------------------------------------------------

packages, labels, scans = [], [], []
shipment_facts = {}     # ref -> derived quantities used by documents/finance/risk

def add_label(pkg_id, code, ref, copy_no, issued_day, issued_at, superseded_at=None):
    body, sig = sign_label(code, ref, copy_no, issued_day)
    labels.append(dict(id=uid("label", f"{code}/{copy_no}"), package_id=pkg_id,
                       copy_no=copy_no, payload=f"{body}|SIG={sig}", signature=sig,
                       key_id=KEY_ID, issued_at=issued_at, superseded_at=superseded_at))

def add_scan(ref, code, device, officer, kind, result, reasons, ai_flags,
             when, lat, lng, evidence=None, evidence_sha=None, seq_key=None):
    scans.append(dict(
        id=uid("scan", seq_key or f"{ref}/{code}/{kind}/{len(scans)}"),
        client_event_id=uid("cev", seq_key or f"{ref}/{code}/{kind}/{len(scans)}"),
        package_code=code, shipment_ref=ref, device_id=device, officer_id=officer,
        kind=kind, result=result, reasons=reasons, ai_flags=ai_flags,
        evidence_uri=evidence, evidence_sha256=evidence_sha,
        lat=lat, lng=lng, client_ts=when, server_ts=when, source="DEVICE"))

site_by_key = {k: uid("site", k) for k, *_ in SITES}
site_geo    = {k: (lat, lng) for k, _n, _kind, _a, lat, lng in SITES}
user_by_key = {k: uid("user", k) for k, *_ in USERS}
dev_by_key  = {k: uid("device", k) for k, *_ in DEVICES}
party_by_key= {k: uid("party", k) for k, *_ in PARTIES}

for sp in SHIPMENTS:
    ref = sp["ref"]
    ship_id = uid("shipment", ref)
    sp["id"] = ship_id
    olat, olng = site_geo[sp["origin"]]
    dlat, dlng = site_geo[sp["dest"]] if sp["dest"] else (None, None)

    line_state = {}          # po_line_no -> counters used later by the matcher
    master_index = 0
    shortage_master = None

    for (line_no, sku, n_masters, per_master, labelled) in sp["lines"]:
        desc, hsn, rate = SKUS[sku]
        loaded_cut = sp.get("loaded_masters", n_masters if sp["load_day"] else 0)

        for m in range(n_masters):
            master_index += 1
            mcode = package_code()
            mid = uid("package", mcode)

            # --- master status follows where the shipment has got to -------
            if sp["status"] == "OPEN":
                mstatus = "CREATED"
            elif sp["status"] == "LOADING":
                mstatus = "LOADED" if master_index <= loaded_cut else "PRINTED"
            elif sp["status"] == "DISPATCHED":
                mstatus = "LOADED"
            else:
                mstatus = "RECEIVED"

            # §2.2 the sealed master that declares 10 and holds 9
            is_shortage = (sp.get("inner_shortage_line") == line_no and m == 3)
            if is_shortage:
                mstatus = "FLAGGED"
                shortage_master = mcode

            packages.append(dict(id=mid, shipment_id=ship_id, package_code=mcode,
                                 kind="MASTER", parent_code=None, contents=f"{per_master} × {desc}",
                                 sku=sku, hsn=hsn, qty=per_master, po_line_no=line_no,
                                 weight_g=per_master * (900 if sku == "KE-SP-A15" else 260) + 400,
                                 status=mstatus, created_at=sp["created"]))
            add_label(mid, mcode, ref, 1, date(*(sp["load_day"] or (2026, 9, 12))), sp["created"])

            # --- inner units ------------------------------------------------
            if labelled:
                for u in range(per_master):
                    ucode = package_code()
                    uid_ = uid("package", ucode)
                    if sp["status"] == "OPEN":
                        ustatus = "CREATED"
                    elif sp["status"] == "LOADING":
                        ustatus = "LOADED" if master_index <= loaded_cut else "PRINTED"
                    elif sp["status"] == "DISPATCHED":
                        ustatus = "LOADED"
                    else:
                        ustatus = "RECEIVED"
                    if is_shortage and u == per_master - 1:
                        ustatus = "MISSING"      # the one that never arrived
                    packages.append(dict(id=uid_, shipment_id=ship_id, package_code=ucode,
                                         kind="UNIT", parent_code=mcode, contents=desc,
                                         sku=sku, hsn=hsn, qty=1, po_line_no=line_no,
                                         weight_g=900 if sku == "KE-SP-A15" else 260,
                                         status=ustatus, created_at=sp["created"]))
                    add_label(uid_, ucode, ref, 1, date(*(sp["load_day"] or (2026, 9, 12))), sp["created"])

        st = line_state.setdefault(line_no, dict(sku=sku, rate=rate, desc=desc, hsn=hsn,
                                                 masters=0, per_master=per_master,
                                                 invoiced=0, physical_loaded=0, physical_recv=0,
                                                 labelled=labelled))
        st["masters"] += n_masters
        st["invoiced"] += n_masters * per_master

    sp["shortage_master"] = shortage_master
    shipment_facts[ref] = line_state

# --------------------------------------------------------------------------
# scan events (§7.1 hot path) — one per box actually handled
# --------------------------------------------------------------------------

by_ship = {}
for p in packages:
    by_ship.setdefault(p["shipment_id"], []).append(p)

OFFICER_LOAD = {"KE-WH-BLR": "deepak.shah", "BC-WH-CHN": "meera.nair"}
DEVICE_LOAD  = {"KE-WH-BLR": "dock-a-1",    "BC-WH-CHN": "dock-chn-1"}

def walk(start_dt, step_s, i):
    return (start_dt + timedelta(seconds=step_s * i)).isoformat()

for sp in SHIPMENTS:
    ref, ship_id = sp["ref"], sp["id"]
    pkgs = by_ship[ship_id]
    masters = [p for p in pkgs if p["kind"] == "MASTER"]
    inners  = {p["package_code"]: [] for p in masters}
    for p in pkgs:
        if p["parent_code"]:
            inners[p["parent_code"]].append(p)

    olat, olng = site_geo[sp["origin"]]
    dlat, dlng = site_geo[sp["dest"]]

    # ---- LOAD pass at origin ------------------------------------------------
    if sp["load_day"]:
        y, mo, d = sp["load_day"]
        t0 = datetime(y, mo, d, 14, 5, tzinfo=timezone(timedelta(hours=5, minutes=30)))
        officer = user_by_key[OFFICER_LOAD[sp["origin"]]]
        device  = dev_by_key[DEVICE_LOAD[sp["origin"]]]
        i = 0
        for m in masters:
            if m["status"] in ("CREATED", "PRINTED"):
                continue          # not yet loaded (the LOADING shipment's tail)
            i += 1
            result, reasons, flags = "VERIFIED", [], {"contents_match": True, "seal_intact": True}
            ev = f"s3://veritransit-evidence/{ref}/{m['package_code']}-load.jpg"
            add_scan(ref, m["package_code"], device, officer, "LOAD", result, reasons, flags,
                     walk(t0, 11, i), olat, olng, ev, f"sha256:{uid('ev', ev)[:32].replace('-','')}",
                     seq_key=f"{ref}/load/{m['package_code']}")

            # HIGH band → every inner scanned at load too (§5.3 friction)
            if sp.get("high_risk_scans"):
                for k, u in enumerate(inners[m["package_code"]]):
                    i += 1
                    add_scan(ref, u["package_code"], device, officer, "LOAD",
                             "VERIFIED", [], {"contents_match": True},
                             walk(t0, 11, i), olat, olng,
                             seq_key=f"{ref}/load-inner/{u['package_code']}")

        # planted incidents on the high-risk shipment (§3 scam walk-through)
        if sp.get("high_risk_scans"):
            dup_target = masters[7]["package_code"]
            i += 1
            add_scan(ref, dup_target, dev_by_key["dock-a-2"], user_by_key["deepak.shah"], "LOAD",
                     "SUSPECT_REVIEW", ["DUPLICATE_LABEL"],
                     {"contents_match": True, "duplicate_of_session_scan": True},
                     walk(t0, 11, i), olat, olng,
                     f"s3://veritransit-evidence/{ref}/{dup_target}-dup.jpg",
                     f"sha256:{uid('ev', dup_target + 'dup')[:32].replace('-','')}",
                     seq_key=f"{ref}/dup/{dup_target}")
            swap_target = masters[19]["package_code"]
            i += 1
            add_scan(ref, swap_target, device, officer, "LOAD",
                     "SUSPECT_REVIEW", ["QR_BARCODE_MISMATCH"],
                     {"printed_code_read": "VT-P-MISMATCH", "label_edge_lifted": True},
                     walk(t0, 11, i), olat, olng,
                     f"s3://veritransit-evidence/{ref}/{swap_target}-swap.jpg",
                     f"sha256:{uid('ev', swap_target + 'swap')[:32].replace('-','')}",
                     seq_key=f"{ref}/swap/{swap_target}")
            i += 1
            add_scan(ref, masters[31]["package_code"], dev_by_key["gate-nh44"],
                     user_by_key["priya.menon"], "FIELD", "VERIFIED", [],
                     {"contents_match": True, "risk_badge": "HIGH"},
                     walk(t0 + timedelta(days=1), 11, i), 15.828, 78.037,
                     seq_key=f"{ref}/gate/{masters[31]['package_code']}")

    # ---- RECEIVE pass at destination ---------------------------------------
    if sp["recv_day"]:
        y, mo, d = sp["recv_day"]
        t0 = datetime(y, mo, d, 10, 45, tzinfo=timezone(timedelta(hours=5, minutes=30)))
        officer = user_by_key["sunita.das"]
        device  = dev_by_key["recv-hyd-1"]
        i = 0
        for m in masters:
            i += 1
            shortage = (m["package_code"] == sp.get("shortage_master"))
            if shortage:
                add_scan(ref, m["package_code"], device, officer, "RECEIVE",
                         "SUSPECT_REVIEW", ["INNER_SHORTAGE"],
                         {"declared_inners": m["qty"], "counted_inners": m["qty"] - 1,
                          "seal_intact": False, "tape_resealed": True},
                         walk(t0, 9, i), dlat, dlng,
                         f"s3://veritransit-evidence/{ref}/{m['package_code']}-open.jpg",
                         f"sha256:{uid('ev', m['package_code'] + 'open')[:32].replace('-','')}",
                         seq_key=f"{ref}/recv/{m['package_code']}")
            else:
                add_scan(ref, m["package_code"], device, officer, "RECEIVE", "VERIFIED", [],
                         {"contents_match": True, "seal_intact": True},
                         walk(t0, 9, i), dlat, dlng,
                         seq_key=f"{ref}/recv/{m['package_code']}")

            # §4.4 verification depth: open the master and scan every inner
            if sp["verify_inners_on_receipt"]:
                for u in inners[m["package_code"]]:
                    i += 1
                    if u["status"] == "MISSING":
                        continue          # never came out of the box — no scan exists
                    add_scan(ref, u["package_code"], device, officer, "RECEIVE",
                             "VERIFIED", [], {"contents_match": True},
                             walk(t0, 9, i), dlat, dlng,
                             seq_key=f"{ref}/recv-inner/{u['package_code']}")

        # proof-of-delivery scan closing the shipment (§7.4)
        if sp["status"] in ("RECEIVED", "FLAGGED"):
            add_scan(ref, masters[0]["package_code"], device, officer, "POD",
                     "VERIFIED", [], {"signature_captured": True, "receiver": "Zen Digital DC"},
                     walk(t0, 9, i + 40), dlat, dlng,
                     f"s3://veritransit-evidence/{ref}/pod-signature.png",
                     f"sha256:{uid('ev', ref + 'pod')[:32].replace('-','')}",
                     seq_key=f"{ref}/pod")

# ---- physical counts, straight off the package rows -------------------------
for sp in SHIPMENTS:
    ls = shipment_facts[sp["ref"]]
    for p in by_ship[sp["id"]]:
        if p["po_line_no"] not in ls:
            continue
        st = ls[p["po_line_no"]]
        if p["kind"] == "MASTER" and not st["labelled"]:
            # unlabelled inners: the master's declared count is the physical count
            if p["status"] in ("LOADED", "RECEIVED"):
                st["physical_loaded"] += p["qty"]
            if p["status"] == "RECEIVED":
                st["physical_recv"] += p["qty"]
        elif p["kind"] == "UNIT":
            if p["status"] in ("LOADED", "RECEIVED"):
                st["physical_loaded"] += 1
            if p["status"] == "RECEIVED":
                st["physical_recv"] += 1

# --------------------------------------------------------------------------
# documents, four-way match, finance, risk (§5.1–§5.4)
# --------------------------------------------------------------------------

documents, recon_runs, finance, payment_events = [], [], [], []
risk_scores, discrepancies, pod_certs, agent_actions = [], [], [], []
audit_entries = []          # (at, actor, action, subject, payload, key)

def audit(at, actor, action, subject, payload, key=None):
    audit_entries.append(dict(at=at, actor=actor, action=action, subject=subject,
                              payload=payload, key=key))

def doc_lines(ls, qty_pick):
    out, total = [], Decimal("0")
    for line_no in sorted(ls):
        st = ls[line_no]
        qty = qty_pick(line_no, st)
        amount = (st["rate"] * qty).quantize(Decimal("0.01"))
        total += amount
        out.append(dict(line_no=line_no, description=st["desc"], sku=st["sku"], hsn=st["hsn"],
                        qty=qty, unit="NOS", rate=float(st["rate"]), amount=float(amount)))
    return out, total

for sp in SHIPMENTS:
    ref, ship_id = sp["ref"], sp["id"]
    ls = shipment_facts[ref]
    supplier, buyer = party_by_key[sp["supplier"]], party_by_key[sp["buyer"]]
    sup_name = dict((k, n) for k, _kind, n, *_ in PARTIES)[sp["supplier"]]
    buy_name = dict((k, n) for k, _kind, n, *_ in PARTIES)[sp["buyer"]]
    sup_gstin = dict((k, g) for k, _kind, _n, g, *_ in PARTIES)[sp["supplier"]]
    buy_gstin = dict((k, g) for k, _kind, _n, g, *_ in PARTIES)[sp["buyer"]]
    ship_to = dict((k, n) for k, n, *_ in SITES)[sp["dest"]]

    po_lines, po_total = doc_lines(ls, lambda n, st: sp["po_qty_override"].get(n, st["invoiced"]))
    inv_lines, inv_total = doc_lines(ls, lambda n, st: st["invoiced"])

    def fact(kind, no, dt, lines, total, extra=None):
        f = dict(kind=kind, doc_no=no, date=dt.isoformat(),
                 seller=dict(name=sup_name, gstin=sup_gstin),
                 buyer=dict(name=buy_name, gstin=buy_gstin),
                 ship_to=ship_to, lines=lines,
                 totals=dict(taxable_value=float(total),
                             igst=float((total * Decimal("0.18")).quantize(Decimal("0.01"))),
                             grand_total=float((total * Decimal("1.18")).quantize(Decimal("0.01")))),
                 tax=dict(scheme="IGST", rate_pct=18.0))
        if extra:
            f.update(extra)
        return f

    # Purchase order — the anchor of the match (§5.1)
    documents.append(dict(id=uid("doc", f"PO/{sp['po_no']}"), shipment_id=ship_id, kind="PO",
        doc_no=sp["po_no"], doc_date=sp["po_date"].isoformat(), party_from=buyer, party_to=supplier,
        fact=fact("PO", sp["po_no"], sp["po_date"], po_lines, po_total),
        source_uri=f"s3://veritransit-docs/{ref}/po-{sp['po_no']}.pdf", read_by="web_parser",
        confidence=None, confirmed_by=user_by_key["anil.rao"],
        uploaded_by=user_by_key["anil.rao"], uploaded_at=sp["created"]))

    if sp["invoice_no"]:
        documents.append(dict(id=uid("doc", f"INV/{sp['invoice_no']}"), shipment_id=ship_id, kind="INVOICE",
            doc_no=sp["invoice_no"], doc_date=sp["invoice_date"].isoformat(),
            party_from=supplier, party_to=buyer,
            fact=fact("INVOICE", sp["invoice_no"], sp["invoice_date"], inv_lines, inv_total),
            source_uri=f"s3://veritransit-docs/{ref}/invoice-{sp['invoice_no']}.jpg",
            read_by="device_npu", confidence=0.947, confirmed_by=user_by_key["priya.menon"],
            uploaded_by=user_by_key["deepak.shah"], uploaded_at=sp["dispatched"] or sp["created"]))

    if sp["ewb_no"]:
        # The e-way bill is raised off the invoice, so it agrees with the invoice
        # and inherits its disagreement with the PO — exactly the real failure mode.
        ewb_lines = inv_lines if sp["invoice_no"] else po_lines
        ewb_total = inv_total if sp["invoice_no"] else po_total
        documents.append(dict(id=uid("doc", f"EWB/{sp['ewb_no']}"), shipment_id=ship_id, kind="EWB",
            doc_no=sp["ewb_no"], doc_date=(sp["invoice_date"] or sp["po_date"]).isoformat(),
            party_from=supplier, party_to=buyer,
            fact=fact("EWB", sp["ewb_no"], sp["invoice_date"] or sp["po_date"], ewb_lines, ewb_total,
                      extra=dict(vehicle=sp["vehicle"], transport_mode="ROAD",
                                 valid_until=((sp["invoice_date"] or sp["po_date"]) + timedelta(days=3)).isoformat())),
            source_uri=f"s3://veritransit-docs/{ref}/ewb-{sp['ewb_no']}.jpg",
            read_by="device_npu", confidence=0.962, confirmed_by=user_by_key["deepak.shah"],
            uploaded_by=user_by_key["deepak.shah"], uploaded_at=sp["dispatched"] or sp["created"]))

    if sp["lr_no"]:
        documents.append(dict(id=uid("doc", f"LR/{sp['lr_no']}"), shipment_id=ship_id, kind="LR",
            doc_no=sp["lr_no"], doc_date=(sp["invoice_date"] or sp["po_date"]).isoformat(),
            party_from=party_by_key[sp["transporter"]], party_to=buyer,
            fact=dict(kind="LR", doc_no=sp["lr_no"], date=(sp["invoice_date"] or sp["po_date"]).isoformat(),
                      seller=dict(name="SafeMove Logistics LLP", gstin="29AACCS9012P1Z8"),
                      buyer=dict(name=buy_name, gstin=buy_gstin), ship_to=ship_to,
                      lines=[dict(line_no=1, description="Consignment — electronics",
                                  qty=sum(st["masters"] for st in ls.values()), unit="CARTONS",
                                  rate=0.0, amount=0.0)],
                      totals=dict(taxable_value=0.0), tax=dict(scheme="RCM"),
                      vehicle=sp["vehicle"], driver="—"),
            source_uri=f"s3://veritransit-docs/{ref}/lr-{sp['lr_no']}.pdf", read_by="web_parser",
            confidence=None, confirmed_by=None,
            uploaded_by=user_by_key["deepak.shah"], uploaded_at=sp["dispatched"] or sp["created"]))

    # Packing list is generated from the registry, never typed (§4.5)
    pl_no = f"PL-{ref.split('-')[-1]}"
    pl_lines, _ = doc_lines(ls, lambda n, st: st["invoiced"])
    documents.append(dict(id=uid("doc", f"PL/{pl_no}"), shipment_id=ship_id, kind="PACKING_LIST",
        doc_no=pl_no, doc_date=(sp["invoice_date"] or sp["po_date"]).isoformat(),
        party_from=supplier, party_to=buyer,
        fact=dict(kind="PACKING_LIST", doc_no=pl_no,
                  date=(sp["invoice_date"] or sp["po_date"]).isoformat(),
                  seller=dict(name=sup_name, gstin=sup_gstin),
                  buyer=dict(name=buy_name, gstin=buy_gstin), ship_to=ship_to,
                  lines=pl_lines,
                  totals=dict(masters=sum(st["masters"] for st in ls.values()),
                              units=sum(st["invoiced"] for st in ls.values())),
                  tax=dict(scheme="NA"), generated=True),
        source_uri=None, read_by="manual", confidence=None, confirmed_by=None,
        uploaded_by=None, uploaded_at=sp["created"]))

    sp["_po_total"], sp["_inv_total"] = po_total, inv_total
    sp["_po_lines"], sp["_inv_lines"] = po_lines, inv_lines

# --------------------------------------------------------------------------
# the four-way matcher (§5.1) — PO is the anchor; deterministic, explainable
# --------------------------------------------------------------------------

def run_match(sp, physical_of, invoice_present, shortage_line=None):
    ls = shipment_facts[sp["ref"]]
    rows, mismatches, held = [], [], Decimal("0")
    for line_no in sorted(ls):
        st = ls[line_no]
        ordered  = sp["po_qty_override"].get(line_no, st["invoiced"])
        invoiced = st["invoiced"] if invoice_present else None
        declared = invoiced if (invoice_present and sp["ewb_no"]) else None
        physical = physical_of(line_no, st)
        rows.append(dict(line_no=line_no, sku=st["sku"], description=st["desc"], hsn=st["hsn"],
                         rate=float(st["rate"]), ordered_qty=ordered, invoiced_qty=invoiced,
                         declared_qty=declared, physical_qty=physical))

        if invoiced is not None and invoiced != ordered:
            delta = (abs(invoiced - ordered) * st["rate"]).quantize(Decimal("0.01"))
            held += delta
            mismatches.append(dict(code="QTY_MISMATCH", pair="PO_VS_INVOICE", line_no=line_no,
                sku=st["sku"], ordered_qty=ordered, invoiced_qty=invoiced,
                declared_qty=declared, physical_qty=physical,
                delta_qty=invoiced - ordered, delta_value=float(delta),
                detail=f"invoiced {invoiced} against PO {ordered}"))

        if invoiced is not None and physical is not None and physical < invoiced:
            delta = ((invoiced - physical) * st["rate"]).quantize(Decimal("0.01"))
            held += delta
            code = "INNER_SHORTAGE" if line_no == shortage_line else "QTY_MISMATCH"
            mismatches.append(dict(code=code, pair="INVOICE_VS_PHYSICAL", line_no=line_no,
                sku=st["sku"], ordered_qty=ordered, invoiced_qty=invoiced,
                declared_qty=declared, physical_qty=physical,
                delta_qty=physical - invoiced, delta_value=float(delta),
                detail=f"physically verified {physical} of {invoiced} invoiced"))

    if not invoice_present:
        mismatches.append(dict(code="DOC_MISSING", pair="PO_VS_INVOICE", line_no=None,
            sku=None, ordered_qty=None, invoiced_qty=None, declared_qty=None,
            physical_qty=None, delta_qty=None, delta_value=0.0,
            detail="tax invoice not yet attached to the shipment"))

    status = "MATCHED" if not mismatches else ("PARTIAL" if not invoice_present else "MISMATCHED")
    matrix = dict(anchor="PO", tolerances=dict(qty="exact", value_pct=2.0, party="gstin"),
                  lines=rows,
                  four_way=dict(ordered=sum(r["ordered_qty"] or 0 for r in rows),
                                invoiced=sum(r["invoiced_qty"] or 0 for r in rows),
                                declared=sum(r["declared_qty"] or 0 for r in rows),
                                physical=sum(r["physical_qty"] or 0 for r in rows)))
    return matrix, mismatches, held, status

FINANCE_TERMS = {"on_verified_delivery_pct": 70, "balance": "net_30", "balance_pct": 30}

for sp in SHIPMENTS:
    ref, ship_id = sp["ref"], sp["id"]
    ls = shipment_facts[ref]
    has_inv = sp["invoice_no"] is not None
    order_value = sp["_inv_total"] if has_inv else sp["_po_total"]
    held_final = Decimal("0")
    last_status = "MATCHED"

    # run at dispatch — the load was complete, so physical == what was scanned out
    if sp["dispatched"]:
        m, mm, held, stt = run_match(sp, lambda n, st: st["invoiced"], has_inv)
        recon_runs.append(dict(id=uid("recon", f"{ref}/dispatch"), shipment_id=ship_id,
            matrix=m, mismatches=mm, status=stt, held_value=float(held),
            run_by="system", created_at=sp["dispatched"]))
        held_final, last_status = held, stt

    # run at receipt — physical is now what the receiver actually verified
    if sp["received"]:
        m, mm, held, stt = run_match(sp, lambda n, st: st["physical_recv"], has_inv,
                                     shortage_line=sp.get("inner_shortage_line"))
        recon_runs.append(dict(id=uid("recon", f"{ref}/receipt"), shipment_id=ship_id,
            matrix=m, mismatches=mm, status=stt, held_value=float(held),
            run_by="agent", created_at=sp["received"]))
        held_final, last_status = held, stt

    # interim run while still loading
    if sp["status"] == "LOADING":
        m, mm, held, stt = run_match(sp, lambda n, st: st["physical_loaded"], has_inv)
        recon_runs.append(dict(id=uid("recon", f"{ref}/interim"), shipment_id=ship_id,
            matrix=m, mismatches=mm, status=stt, held_value=float(held),
            run_by=user_by_key["meera.nair"], created_at=ts(2026,9,12,11,5)))
        held_final, last_status = held, stt

    # --- finance state (§5.2) ----------------------------------------------
    if sp["status"] == "RECEIVED" and held_final == 0:
        fstatus = "RELEASED"
        released = (order_value * Decimal("0.70")).quantize(Decimal("0.01"))
        held_v = Decimal("0")
    elif held_final > 0:
        fstatus, released, held_v = "HELD", Decimal("0"), held_final
    else:
        fstatus, released, held_v = "AWAITING", Decimal("0"), Decimal("0")

    finance.append(dict(id=uid("fin", ref), shipment_id=ship_id, currency="INR",
        order_value=float(order_value), terms=FINANCE_TERMS, status=fstatus,
        released_value=float(released), held_value=float(held_v),
        updated_at=sp["received"] or sp["dispatched"] or sp["created"]))
    sp["_held"], sp["_order_value"], sp["_fstatus"] = held_v, order_value, fstatus

# --------------------------------------------------------------------------
# risk (§5.3) — every score carries the factors that produced it
# --------------------------------------------------------------------------

def band_of(score):
    return "LOW" if score <= 30 else ("MEDIUM" if score <= 60 else "HIGH")

def risk(kind, subject, score, factors, at):
    risk_scores.append(dict(id=uid("risk", f"{kind}/{subject}"), subject_kind=kind,
                            subject_id=subject, score=score, band=band_of(score),
                            factors=factors, computed_at=at))

risk("shipment", "SHP-2026-090187", 18, [
    dict(factor="party_history", weight=-8, detail="Kumar Electronics: 0 mismatches in prior 3 shipments"),
    dict(factor="route_history", weight=6, detail="BLR→HYD: 1 delay in 12 runs"),
    dict(factor="value_band", weight=4, detail="order value ₹1.60L — below ₹2L threshold"),
], ts(2026,8,23,11,30))

risk("shipment", "SHP-2026-090231", 54, [
    dict(factor="doc_mismatch", weight=26, detail="invoice line 2 qty 60 vs PO 55 (₹87,400)"),
    dict(factor="inner_shortage", weight=18, detail="1 master declared 10, verified 9 — seal re-taped"),
    dict(factor="value_band", weight=12, detail="order value ₹14.20L — above ₹2L auto-release ceiling"),
    dict(factor="party_history", weight=-2, detail="Kumar Electronics: clean on prior shipment 090187"),
], ts(2026,9,11,11,15))

risk("shipment", "SHP-2026-090244", 24, [
    dict(factor="doc_missing", weight=14, detail="tax invoice not yet attached"),
    dict(factor="party_history", weight=6, detail="Bharat Components: 2 shipments, 0 mismatches"),
    dict(factor="value_band", weight=4, detail="order value ₹2.37L"),
], ts(2026,9,12,11,10))

risk("shipment", "SHP-2026-090198", 74, [
    dict(factor="duplicate_label", weight=24, detail="1 duplicate-label event at load (2nd device)"),
    dict(factor="label_swap", weight=22, detail="QR↔barcode mismatch on 1 master — label edge lifted"),
    dict(factor="doc_mismatch", weight=20, detail="invoiced 550 against PO 500 (₹44,500)"),
    dict(factor="party_history", weight=8, detail="Shakti Traders: 4 value mismatches in 6 shipments"),
], ts(2026,9,6,19,30))

risk("shipment", "SHP-2026-090255", 22, [
    dict(factor="party_history", weight=10, detail="Kumar Electronics: 1 held shipment in last 30 days"),
    dict(factor="value_band", weight=4, detail="order value ₹0.67L"),
    dict(factor="stage", weight=8, detail="not yet packed — score provisional"),
], ts(2026,9,12,9,45))

risk("party", party_by_key["kumar"], 34, [
    dict(factor="mismatch_rate", weight=20, detail="1 of 4 shipments held in 90 days"),
    dict(factor="mismatch_type_mix", weight=10, detail="over-invoicing (qty) — no forged labels"),
    dict(factor="resolution_speed", weight=4, detail="median 1.5 days to credit note"),
], ts(2026,9,11,11,20))
risk("party", party_by_key["zen"], 12, [
    dict(factor="receipt_discipline", weight=6, detail="100% of masters opened on MEDIUM band"),
    dict(factor="dispute_rate", weight=6, detail="0 disputes raised post-PoD"),
], ts(2026,9,11,11,20))
risk("party", party_by_key["safemove"], 20, [
    dict(factor="transit_incidents", weight=14, detail="1 inner shortage across 9 consignments"),
    dict(factor="route_adherence", weight=6, detail="no GPS deviation events"),
], ts(2026,9,11,11,20))
risk("party", party_by_key["bharat"], 26, [
    dict(factor="doc_timeliness", weight=18, detail="invoice attached late on 2 of 2 shipments"),
    dict(factor="mismatch_rate", weight=8, detail="0 mismatches to date"),
], ts(2026,9,12,11,10))
risk("party", party_by_key["shakti"], 78, [
    dict(factor="mismatch_rate", weight=30, detail="4 value mismatches in 6 shipments"),
    dict(factor="duplicate_label", weight=26, detail="2 duplicate-label events in 30 days"),
    dict(factor="route_deviation", weight=14, detail="route deviation 11 Feb, 6 Sep"),
    dict(factor="override_frequency", weight=8, detail="officer overrode SUSPECT 3 times"),
], ts(2026,9,6,19,30))
risk("party", party_by_key["metro"], 15, [
    dict(factor="receipt_discipline", weight=9, detail="receives on schedule, scans complete"),
    dict(factor="dispute_rate", weight=6, detail="1 dispute in 14 deliveries"),
], ts(2026,9,6,19,30))

risk("route", "KE-WH-BLR→ZD-DC-HYD", 28, [
    dict(factor="incident_rate", weight=18, detail="1 inner shortage in 12 runs"),
    dict(factor="transit_time_variance", weight=10, detail="±4h on a 14h lane"),
], ts(2026,9,11,11,25))
risk("route", "KE-WH-BLR→MR-DC-PUN", 61, [
    dict(factor="incident_rate", weight=34, detail="3 label incidents in 8 runs"),
    dict(factor="night_halt", weight=17, detail="unsupervised overnight halt at Kurnool"),
    dict(factor="transit_time_variance", weight=10, detail="±9h on a 16h lane"),
], ts(2026,9,6,19,30))

# --------------------------------------------------------------------------
# discrepancies (§6.3 queue)
# --------------------------------------------------------------------------

def disc(ref, kind, code, severity, detail, at, resolved=None):
    d = dict(id=uid("disc", f"{ref}/{kind}/{code or ''}"), shipment_id=uid("shipment", ref),
             kind=kind, package_code=code, severity=severity, detail=detail,
             detected_at=at, resolved_by=None, resolved_at=None, resolution=None, note=None)
    if resolved:
        d.update(resolved)
    discrepancies.append(d)

h = next(s for s in SHIPMENTS if s["ref"] == "SHP-2026-090231")
disc("SHP-2026-090231", "INNER_SHORTAGE", h["shortage_master"], "HIGH",
     dict(declared_inners=10, verified_inners=9, sku="KE-SP-A15",
          value_at_risk=17480.0, evidence="s3://veritransit-evidence/SHP-2026-090231/"
          f"{h['shortage_master']}-open.jpg", note="seal re-taped; photographed on opening"),
     ts(2026,9,11,10,58))
disc("SHP-2026-090231", "QTY_MISMATCH", None, "HIGH",
     dict(pair="PO_VS_INVOICE", line_no=2, sku="KE-SP-A15", ordered=55, invoiced=60,
          delta_qty=5, delta_value=87400.0, documents=["ZD-4471", "KE-2291"]),
     ts(2026,9,8,18,40))

hr = next(s for s in SHIPMENTS if s["ref"] == "SHP-2026-090198")
hr_masters = [p for p in by_ship[hr["id"]] if p["kind"] == "MASTER"]
disc("SHP-2026-090198", "DUPLICATE_LABEL", hr_masters[7]["package_code"], "HIGH",
     dict(seen_on_devices=["Dock-A Handheld 1", "Dock-A Handheld 2"], occurrences=2,
          detected_by="server cross-device history"),
     ts(2026,9,6,18,55))
disc("SHP-2026-090198", "QR_BARCODE_MISMATCH", hr_masters[19]["package_code"], "HIGH",
     dict(qr_code=hr_masters[19]["package_code"], barcode_code="VT-P-MISMATCH",
          ai_flag="label_edge_lifted"),
     ts(2026,9,6,19,2))
disc("SHP-2026-090198", "VALUE_MISMATCH", None, "HIGH",
     dict(pair="PO_VS_INVOICE", line_no=1, sku="ST-SPKR-BT5", ordered=500, invoiced=550,
          delta_qty=50, delta_value=44500.0, documents=["MR-7712", "ST-1108"]),
     ts(2026,9,6,19,12))

disc("SHP-2026-090244", "DOC_MISSING", None, "MEDIUM",
     dict(missing_kind="INVOICE", po="ZD-4488",
          note="loading may continue; dispatch gate blocks until attached"),
     ts(2026,9,12,11,6))

disc("SHP-2026-090187", "NOT_IN_MANIFEST", None, "LOW",
     dict(note="one carton scanned twice by the same device within 3s — debounce artefact"),
     ts(2026,8,21,16,10),
     resolved=dict(resolved_by=user_by_key["priya.menon"], resolved_at=ts(2026,8,21,16,22),
                   resolution="DISMISSED", note="duplicate beep, single physical carton — confirmed on video"))

# --------------------------------------------------------------------------
# policies, webhooks, PoD, agent actions (§5.4, §5.5)
# --------------------------------------------------------------------------

POLICIES = [
    dict(id=uid("policy", "auto-release"), tenant_id=TENANT, kind="agent_auto_release",
         name="Auto-release — low risk, small value",
         rules=dict(max_order_value=200000, max_risk_score=30, require_zero_mismatches=True,
                    require_pod=True, currency="INR",
                    note="§5.5: the agent proposes, policy disposes"),
         active=True, approved_by=user_by_key["vikram.iyer"], approved_at=ts(2026,7,30,15,0),
         created_at=ts(2026,7,30,14,20)),
    dict(id=uid("policy", "friction-bands"), tenant_id=TENANT, kind="friction_band",
         name="Friction bands v1",
         rules=dict(LOW=dict(max_score=30, photo_evidence="spot", supervisor_signoff=False,
                             inner_verification="master_scan_only"),
                    MEDIUM=dict(max_score=60, photo_evidence="every_scan", supervisor_signoff=False,
                                inner_verification="open_and_verify"),
                    HIGH=dict(max_score=100, photo_evidence="every_scan", supervisor_signoff=True,
                              inner_verification="open_and_verify", payment="auto_hold")),
         active=True, approved_by=user_by_key["anil.rao"], approved_at=ts(2026,7,30,15,5),
         created_at=ts(2026,7,30,14,25)),
    dict(id=uid("policy", "tolerances"), tenant_id=TENANT, kind="tolerance",
         name="Default commodity tolerances",
         rules=dict(qty="exact", value_pct=2.0, party_match="gstin",
                    per_commodity={"85171300": dict(qty="exact", value_pct=0.0)}),
         active=True, approved_by=user_by_key["vikram.iyer"], approved_at=ts(2026,7,30,15,10),
         created_at=ts(2026,7,30,14,30)),
]

WEBHOOKS = [
    dict(id=uid("webhook", "zen-erp"), tenant_id=TENANT, name="Zen Digital ERP — payables",
         url="https://erp.zendigital.in/hooks/veritransit",
         secret_hash="sha256:" + uid("secret", "zen-erp").replace("-", ""),
         events=["RELEASE_CERTIFICATE", "HOLD_NOTICE", "POD_ISSUED"],
         active=True, created_at=ts(2026,8,1,10,0)),
    dict(id=uid("webhook", "metro-erp"), tenant_id=TENANT, name="Metro Retail ERP — payables",
         url="https://ap.metroretail.co.in/api/veritransit",
         secret_hash="sha256:" + uid("secret", "metro-erp").replace("-", ""),
         events=["RELEASE_CERTIFICATE", "HOLD_NOTICE"],
         active=True, created_at=ts(2026,8,4,12,30)),
]

WEBHOOK_DELIVERIES = [
    dict(id=uid("wd", "090187-release"), webhook_id=uid("webhook", "zen-erp"),
         event="RELEASE_CERTIFICATE",
         payload=dict(shipment_ref="SHP-2026-090187", released_value=112000.00, currency="INR",
                      certificate_id=uid("pod", "SHP-2026-090187")),
         status_code=200, attempts=1, delivered_at=ts(2026,8,25,12,5), last_error=None,
         created_at=ts(2026,8,25,12,4)),
    dict(id=uid("wd", "090231-hold"), webhook_id=uid("webhook", "zen-erp"), event="HOLD_NOTICE",
         payload=dict(shipment_ref="SHP-2026-090231", held_value=104880.00, currency="INR",
                      reasons=["QTY_MISMATCH", "INNER_SHORTAGE"]),
         status_code=200, attempts=1, delivered_at=ts(2026,9,11,11,32), last_error=None,
         created_at=ts(2026,9,11,11,30)),
    dict(id=uid("wd", "090198-hold"), webhook_id=uid("webhook", "metro-erp"), event="HOLD_NOTICE",
         payload=dict(shipment_ref="SHP-2026-090198", held_value=44500.00, currency="INR",
                      reasons=["VALUE_MISMATCH", "DUPLICATE_LABEL", "QR_BARCODE_MISMATCH"]),
         status_code=502, attempts=3, delivered_at=None,
         last_error="502 Bad Gateway from ap.metroretail.co.in (retry scheduled)",
         created_at=ts(2026,9,6,19,40)),
]

# --- PoD certificates: signed over the certificate body, like the labels -----
def pod_cert(ref, evidence_count, match_snapshot, issued_at, note=None):
    ship_id = uid("shipment", ref)
    ev_hashes = {f"photo-{i+1}.jpg": "sha256:" + uid("ph", f"{ref}/{i}")[:32].replace("-", "")
                 for i in range(evidence_count)}
    ev_hashes["signature.png"] = "sha256:" + uid("ph", f"{ref}/sig")[:32].replace("-", "")
    events = dict(
        scans=dict(masters=len([p for p in by_ship[ship_id] if p["kind"] == "MASTER"]),
                   inners_verified=len([p for p in by_ship[ship_id]
                                        if p["kind"] == "UNIT" and p["status"] == "RECEIVED"])),
        receiver=dict(name="Zen Digital Retail Ltd", gstin="36AACCZ5678N1Z2",
                      signed_by="Sunita Das", method="on_screen_signature"),
        gps=dict(lat=17.535, lng=78.487), delivered_at=issued_at)
    if note:
        events["note"] = note
    body = json.dumps(dict(shipment_ref=ref, events=events, evidence_hashes=ev_hashes,
                           match=match_snapshot), separators=(",", ":"), sort_keys=True)
    pod_certs.append(dict(id=uid("pod", ref), shipment_id=ship_id, events=events,
                          evidence_hashes=ev_hashes, match_result=match_snapshot,
                          signature=b64u(priv.sign(body.encode())), key_id=KEY_ID,
                          issued_at=issued_at,
                          pdf_uri=f"s3://veritransit-pod/{ref}/certificate.pdf",
                          bundle_uri=f"s3://veritransit-pod/{ref}/claims-bundle.zip"))

pod_cert("SHP-2026-090187", 6, dict(status="MATCHED", held_value=0.0, mismatches=[]),
         ts(2026,8,23,12,10))
pod_cert("SHP-2026-090231", 11,
         dict(status="MISMATCHED", held_value=104880.0,
              mismatches=["QTY_MISMATCH:line2:87400", "INNER_SHORTAGE:line2:17480"]),
         ts(2026,9,11,11,28),
         note="1 of 148 masters short by one inner unit; evidence photographed on opening")

AGENT_ACTIONS = [
    dict(id=uid("agent", "090187"), shipment_id=uid("shipment", "SHP-2026-090187"),
         trigger_event="SHIPMENT_COMPLETED",
         steps=[dict(step="gather", detail="PoD issued, four-way MATCHED, risk 18 LOW"),
                dict(step="update", detail="finance AWAITING → VERIFIED"),
                dict(step="notify", detail="Telegram to @farahsheikh, @vikramiyer"),
                dict(step="act", detail="within policy 'Auto-release — low risk, small value'; "
                                        "release request raised for maker-checker"),
                dict(step="report", detail="summary PDF + factor breakdown sent")],
         outcome="COMPLETED",
         reasoning_summary="Order value ₹1.60L is under the ₹2L ceiling, risk band LOW (18), "
                           "zero mismatches, PoD present — every auto-release precondition met. "
                           "Raised the release request; the human checker approved it.",
         policy_id=uid("policy", "auto-release"), created_at=ts(2026,8,24,9,15)),
    dict(id=uid("agent", "090231"), shipment_id=uid("shipment", "SHP-2026-090231"),
         trigger_event="RECONCILIATION_DONE",
         steps=[dict(step="gather", detail="four-way MISMATCHED: PO 55 vs invoice 60; physical 59"),
                dict(step="update", detail="finance VERIFIED → HELD, held ₹1,04,880"),
                dict(step="notify", detail="Telegram to Kumar Electronics + Zen Digital finance"),
                dict(step="act", detail="auto-release NOT attempted: ₹14.2L exceeds the ₹2L "
                                        "policy ceiling and mismatches are non-zero"),
                dict(step="report", detail="hold notice delivered to Zen ERP webhook (200)")],
         outcome="AWAITING_HUMAN",
         reasoning_summary="Two independent deltas on the same line: the invoice bills 5 units "
                           "more than the PO authorises (₹87,400), and one sealed master yielded "
                           "9 of 10 declared handsets (₹17,480). Held the exact sum, ₹1,04,880, "
                           "and left the remaining value releasable once a credit note lands. "
                           "No policy permits me to release at this value — routed to finance.",
         policy_id=uid("policy", "auto-release"), created_at=ts(2026,9,11,11,26)),
    dict(id=uid("agent", "090198"), shipment_id=uid("shipment", "SHP-2026-090198"),
         trigger_event="DISCREPANCY_RAISED",
         steps=[dict(step="gather", detail="duplicate label, QR↔barcode mismatch, invoice 550 vs PO 500"),
                dict(step="update", detail="risk 74 HIGH → finance auto-hold per friction band"),
                dict(step="notify", detail="supervisor + Metro Retail AP notified"),
                dict(step="act", detail="BLOCKED: HIGH band forbids agent-executed release"),
                dict(step="report", detail="webhook delivery to Metro ERP failed 502, retrying")],
         outcome="BLOCKED_BY_POLICY",
         reasoning_summary="Risk band HIGH with two label-integrity events on the same load. "
                           "The friction-band policy auto-holds payment and forbids any "
                           "agent-executed release, so I stopped at notification.",
         policy_id=uid("policy", "friction-bands"), created_at=ts(2026,9,6,19,35)),
]

# --------------------------------------------------------------------------
# audit chain entries (§3 check 9) — appended in chronological order so the
# hash chain the trigger builds reflects the real sequence of events
# --------------------------------------------------------------------------

audit(ts(2026,7,10,11,0), "anil.rao", "SIGNING_KEY_ISSUED", KEY_ID,
      dict(algorithm="Ed25519", public_key=PUBKEY_B64, ceremony="two-person, offline"))
audit(ts(2026,7,12,9,30), "anil.rao", "TENANT_CREATED", "VeriTransit Pilot", dict(sites=len(SITES)))
for k, label, site, npu, act in DEVICES:
    audit(act, "anil.rao", "DEVICE_ACTIVATED", label, dict(site=site, npu_capable=npu))
for p in POLICIES:
    audit(p["approved_at"], "vikram.iyer" if p["kind"] != "friction_band" else "anil.rao",
          "POLICY_APPROVED", p["name"], dict(kind=p["kind"], rules=p["rules"]))

for sp in SHIPMENTS:
    ref = sp["ref"]
    ls = shipment_facts[ref]
    n_masters = sum(st["masters"] for st in ls.values())
    n_units = sum(st["invoiced"] for st in ls.values())
    audit(sp["created"], "anil.rao", "SHIPMENT_CREATED", ref,
          dict(expected_count=n_masters, vehicle=sp["vehicle"], po=sp["po_no"]))
    audit(sp["created"], "ravi.kumar", "LABELS_ISSUED", ref,
          dict(masters=n_masters, units=n_units, key_id=KEY_ID))
    if sp["dispatched"]:
        audit(sp["dispatched"], "deepak.shah", "SCANS_INGESTED", ref,
              dict(kind="LOAD", count=n_masters, complete=True))
        audit(sp["dispatched"], "system", "RECONCILIATION_RUN", ref,
              dict(stage="dispatch", status="MISMATCHED" if sp["_held"] else "MATCHED"))
        audit(sp["dispatched"], "deepak.shah", "SHIPMENT_DISPATCHED", ref, dict(vehicle=sp["vehicle"]))
    if sp["received"]:
        audit(sp["received"], "sunita.das", "SCANS_INGESTED", ref,
              dict(kind="RECEIVE", count=n_masters, inners_verified=sp["verify_inners_on_receipt"]))

audit(ts(2026,8,23,12,10), "system", "POD_ISSUED", "SHP-2026-090187",
      dict(certificate=uid("pod", "SHP-2026-090187"), key_id=KEY_ID))
audit(ts(2026,8,24,9,15), "agent", "AGENT_RUN", "SHP-2026-090187",
      dict(trigger="SHIPMENT_COMPLETED", outcome="COMPLETED", policy="Auto-release — low risk, small value"))
audit(ts(2026,8,24,9,20), "farah.sheikh", "RELEASE_REQUESTED", "SHP-2026-090187",
      dict(amount=112000.0, currency="INR", basis="70% on verified delivery"), key="090187-request")
audit(ts(2026,8,25,11,58), "vikram.iyer", "RELEASE_APPROVED", "SHP-2026-090187",
      dict(amount=112000.0, maker="farah.sheikh", evidence_hash=uid("pod", "SHP-2026-090187")),
      key="090187-approve")
audit(ts(2026,8,25,12,4), "system", "CERTIFICATE_ISSUED", "SHP-2026-090187",
      dict(kind="RELEASE_CERTIFICATE", amount=112000.0), key="090187-cert")
audit(ts(2026,8,25,12,5), "system", "WEBHOOK_DELIVERED", "SHP-2026-090187",
      dict(endpoint="Zen Digital ERP — payables", status=200), key="090187-webhook")

audit(ts(2026,9,6,19,12), "system", "DISCREPANCY_RAISED", "SHP-2026-090198",
      dict(kinds=["DUPLICATE_LABEL", "QR_BARCODE_MISMATCH", "VALUE_MISMATCH"]))
audit(ts(2026,9,6,19,35), "agent", "AGENT_RUN", "SHP-2026-090198",
      dict(trigger="DISCREPANCY_RAISED", outcome="BLOCKED_BY_POLICY", policy="Friction bands v1"))
audit(ts(2026,9,6,19,38), "agent", "PAYMENT_HELD", "SHP-2026-090198",
      dict(amount=44500.0, reasons=["VALUE_MISMATCH"]), key="090198-hold")

audit(ts(2026,9,11,10,58), "sunita.das", "DISCREPANCY_RAISED", "SHP-2026-090231",
      dict(kind="INNER_SHORTAGE", package=h["shortage_master"], declared=10, verified=9))
audit(ts(2026,9,11,11,15), "system", "RECONCILIATION_RUN", "SHP-2026-090231",
      dict(stage="receipt", status="MISMATCHED", held_value=104880.0))
audit(ts(2026,9,11,11,26), "agent", "AGENT_RUN", "SHP-2026-090231",
      dict(trigger="RECONCILIATION_DONE", outcome="AWAITING_HUMAN"))
audit(ts(2026,9,11,11,28), "system", "POD_ISSUED", "SHP-2026-090231",
      dict(certificate=uid("pod", "SHP-2026-090231"), key_id=KEY_ID, mismatches=2))
audit(ts(2026,9,11,11,30), "agent", "PAYMENT_HELD", "SHP-2026-090231",
      dict(amount=104880.0, reasons=["QTY_MISMATCH", "INNER_SHORTAGE"]), key="090231-hold")
audit(ts(2026,9,11,11,32), "system", "WEBHOOK_DELIVERED", "SHP-2026-090231",
      dict(endpoint="Zen Digital ERP — payables", status=200))
audit(ts(2026,9,12,11,6), "system", "DISCREPANCY_RAISED", "SHP-2026-090244",
      dict(kind="DOC_MISSING", missing="INVOICE"))

audit_entries.sort(key=lambda e: (e["at"], e["action"]))
audit_seq = {e["key"]: i + 1 for i, e in enumerate(audit_entries) if e["key"]}

payment_events = [
    dict(id=uid("pay", "090187-request"), shipment_id=uid("shipment", "SHP-2026-090187"),
         kind="RELEASE_REQUEST", actor="farah.sheikh", policy_id=uid("policy", "auto-release"),
         amount=112000.00, payload=dict(basis="70% on verified delivery", currency="INR"),
         audit_seq=audit_seq["090187-request"], created_at=ts(2026,8,24,9,20)),
    dict(id=uid("pay", "090187-approve"), shipment_id=uid("shipment", "SHP-2026-090187"),
         kind="RELEASE_APPROVED", actor="vikram.iyer", policy_id=uid("policy", "auto-release"),
         amount=112000.00, payload=dict(maker="farah.sheikh", checker="vikram.iyer",
                                        evidence_hash=uid("pod", "SHP-2026-090187")),
         audit_seq=audit_seq["090187-approve"], created_at=ts(2026,8,25,11,58)),
    dict(id=uid("pay", "090187-cert"), shipment_id=uid("shipment", "SHP-2026-090187"),
         kind="CERTIFICATE_ISSUED", actor="system", policy_id=None, amount=112000.00,
         payload=dict(kind="RELEASE_CERTIFICATE", key_id=KEY_ID,
                      pdf="s3://veritransit-certs/SHP-2026-090187/release.pdf"),
         audit_seq=audit_seq["090187-cert"], created_at=ts(2026,8,25,12,4)),
    dict(id=uid("pay", "090187-webhook"), shipment_id=uid("shipment", "SHP-2026-090187"),
         kind="WEBHOOK_DELIVERED", actor="system", policy_id=None, amount=112000.00,
         payload=dict(endpoint="Zen Digital ERP — payables", status=200, attempts=1),
         audit_seq=audit_seq["090187-webhook"], created_at=ts(2026,8,25,12,5)),
    dict(id=uid("pay", "090231-hold"), shipment_id=uid("shipment", "SHP-2026-090231"),
         kind="HOLD", actor="agent", policy_id=uid("policy", "auto-release"), amount=104880.00,
         payload=dict(reasons=["QTY_MISMATCH", "INNER_SHORTAGE"],
                      breakdown=[dict(code="QTY_MISMATCH", value=87400.0),
                                 dict(code="INNER_SHORTAGE", value=17480.0)],
                      releasable_on_resolution=1315120.00),
         audit_seq=audit_seq["090231-hold"], created_at=ts(2026,9,11,11,30)),
    dict(id=uid("pay", "090198-hold"), shipment_id=uid("shipment", "SHP-2026-090198"),
         kind="HOLD", actor="agent", policy_id=uid("policy", "friction-bands"), amount=44500.00,
         payload=dict(reasons=["VALUE_MISMATCH"], risk_band="HIGH", auto_hold=True),
         audit_seq=audit_seq["090198-hold"], created_at=ts(2026,9,6,19,38)),
]

# --------------------------------------------------------------------------
# emit SQL
# --------------------------------------------------------------------------

out = io.StringIO()
W = out.write

W(f"""-- =============================================================================
-- VeriTransit — demo seed data  (GENERATED — edit generate_seed.py, not this file)
--
-- The MASTER_PLAN.md §2 running example plus the history that makes the risk and
-- finance engines meaningful. Label signatures are real Ed25519 over the §4.2
-- token format, verifiable offline with the public key in `signing_keys`.
--
-- Generated by db/scripts/generate_seed.py — deterministic, safe to re-run.
-- =============================================================================

BEGIN;

-- Wipe demo rows only; the schema itself is untouched.
TRUNCATE tenants, parties, shipments, packages, labels, scan_events, discrepancies,
         documents, reconciliation_runs, finance_terms, payment_events, risk_scores,
         pod_certificates, agent_actions, policies, webhooks, webhook_deliveries,
         audit_log, signing_keys, shipment_parties, sites, users, devices
    RESTART IDENTITY CASCADE;

-- The audit chain is append-only in normal operation; seeding is the one moment
-- the guard is stood down, and it goes straight back up at the end of the file.
ALTER TABLE audit_log DISABLE TRIGGER audit_log_no_update_trg;

-- §4.2 / §10 — only the public half of the signing key ever reaches the database.
INSERT INTO signing_keys (key_id, algorithm, public_key, active, created_at) VALUES
  ({q(KEY_ID)}, 'Ed25519', {q(PUBKEY_B64)}, true, {q(ts(2026,7,10,11,0))});

INSERT INTO tenants (id, name, created_at) VALUES
  ({q(TENANT)}, 'VeriTransit Pilot', {q(ts(2026,7,12,9,30))});
""")

W("\nINSERT INTO sites (id, tenant_id, name, kind, address, lat, lng, created_at) VALUES\n")
W(",\n".join(f"  ({q(site_by_key[k])}, {q(TENANT)}, {q(n)}, {q(kind)}, {q(addr)}, {lat}, {lng}, {q(ts(2026,7,12,9,35))})"
             for k, n, kind, addr, lat, lng in SITES) + ";\n")

W("\nINSERT INTO users (id, tenant_id, name, role, email, tg_handle, active, created_at) VALUES\n")
W(",\n".join(f"  ({q(user_by_key[k])}, {q(TENANT)}, {q(n)}, {q(role)}, {q(em)}, {q(tg)}, true, {q(ts(2026,7,12,10,0))})"
             for k, n, role, em, tg in USERS) + ";\n")

W("\nINSERT INTO devices (id, tenant_id, site_id, label, api_key_hash, npu_capable, activated_at, last_seen_at) VALUES\n")
W(",\n".join(f"  ({q(dev_by_key[k])}, {q(TENANT)}, {q(site_by_key[s])}, {q(lbl)}, "
             f"{q('sha256:' + uid('apikey', k).replace('-', ''))}, {q(npu)}, {q(act)}, {q(ts(2026,9,12,8,30))})"
             for k, lbl, s, npu, act in DEVICES) + ";\n")

W("\nINSERT INTO parties (id, tenant_id, kind, name, gstin, pan, meta, created_at) VALUES\n")
W(",\n".join(f"  ({q(party_by_key[k])}, {q(TENANT)}, {q(kind)}, {q(n)}, {q(g)}, {q(pan)}, {q(meta)}, {q(ts(2026,7,12,10,30))})"
             for k, kind, n, g, pan, meta in PARTIES) + ";\n")

W("\nINSERT INTO policies (id, tenant_id, kind, name, rules, active, approved_by, approved_at, created_at) VALUES\n")
W(",\n".join(f"  ({q(p['id'])}, {q(TENANT)}, {q(p['kind'])}, {q(p['name'])}, {q(p['rules'])}, "
             f"{q(p['active'])}, {q(p['approved_by'])}, {q(p['approved_at'])}, {q(p['created_at'])})"
             for p in POLICIES) + ";\n")

W("\nINSERT INTO webhooks (id, tenant_id, name, url, secret_hash, events, active, created_at) VALUES\n")
W(",\n".join(f"  ({q(w['id'])}, {q(TENANT)}, {q(w['name'])}, {q(w['url'])}, {q(w['secret_hash'])}, "
             f"ARRAY[{', '.join(q(e) for e in w['events'])}]::text[], {q(w['active'])}, {q(w['created_at'])})"
             for w in WEBHOOKS) + ";\n")

W("\nINSERT INTO shipments (id, tenant_id, ref, vehicle, origin_site, dest_site, expected_count,\n"
  "                       status, created_by, created_at, dispatched_at, received_at) VALUES\n")
rows = []
for sp in SHIPMENTS:
    n_masters = sum(st["masters"] for st in shipment_facts[sp["ref"]].values())
    rows.append(f"  ({q(sp['id'])}, {q(TENANT)}, {q(sp['ref'])}, {q(sp['vehicle'])}, "
                f"{q(site_by_key[sp['origin']])}, {q(site_by_key[sp['dest']])}, {n_masters}, "
                f"{q(sp['status'])}, {q(user_by_key['anil.rao'])}, {q(sp['created'])}, "
                f"{q(sp['dispatched'])}, {q(sp['received'])})")
W(",\n".join(rows) + ";\n")

W("\nINSERT INTO shipment_parties (shipment_id, party_id, role) VALUES\n")
rows = []
for sp in SHIPMENTS:
    for key, role in ((sp["supplier"], "SUPPLIER"), (sp["buyer"], "BUYER"), (sp["transporter"], "TRANSPORTER")):
        rows.append(f"  ({q(sp['id'])}, {q(party_by_key[key])}, {q(role)})")
W(",\n".join(rows) + ";\n")

# --- bulk tables via COPY (CSV keeps JSON payloads readable and unescaped) ----
def copy_block(table, cols, rows_iter):
    W(f"\nCOPY {table} ({', '.join(cols)}) FROM stdin WITH (FORMAT csv, NULL '');\n")
    buf = io.StringIO()
    w = csv.writer(buf, lineterminator="\n")
    for r in rows_iter:
        w.writerow(["" if r[c] is None else
                    (json.dumps(r[c], separators=(",", ":"), sort_keys=True)
                     if isinstance(r[c], (dict, list)) else
                     ("true" if r[c] is True else "false" if r[c] is False else r[c]))
                    for c in cols])
    W(buf.getvalue())
    W("\\.\n")

PKG_COLS = ["id", "shipment_id", "package_code", "kind", "parent_code", "contents",
            "sku", "hsn", "qty", "po_line_no", "weight_g", "status", "created_at"]
W("\n-- Masters first: a UNIT's parent_code FK must already resolve (§4.4 hierarchy).")
copy_block("packages", PKG_COLS, (p for p in packages if p["kind"] != "UNIT"))
copy_block("packages", PKG_COLS, (p for p in packages if p["kind"] == "UNIT"))

copy_block("labels", ["id", "package_id", "copy_no", "payload", "signature", "key_id",
                      "issued_at", "superseded_at"], labels)

copy_block("scan_events", ["id", "client_event_id", "package_code", "shipment_ref", "device_id",
                           "officer_id", "kind", "result", "reasons", "ai_flags", "evidence_uri",
                           "evidence_sha256", "lat", "lng", "client_ts", "server_ts", "source"], scans)

copy_block("documents", ["id", "shipment_id", "kind", "doc_no", "doc_date", "party_from",
                         "party_to", "fact", "source_uri", "read_by", "confidence",
                         "confirmed_by", "uploaded_by", "uploaded_at"], documents)

copy_block("reconciliation_runs", ["id", "shipment_id", "matrix", "mismatches", "status",
                                   "held_value", "run_by", "created_at"], recon_runs)

copy_block("finance_terms", ["id", "shipment_id", "currency", "order_value", "terms", "status",
                             "released_value", "held_value", "updated_at"], finance)

copy_block("risk_scores", ["id", "subject_kind", "subject_id", "score", "band", "factors",
                           "computed_at"], risk_scores)

copy_block("discrepancies", ["id", "shipment_id", "kind", "package_code", "severity", "detail",
                             "detected_at", "resolved_by", "resolved_at", "resolution", "note"],
           discrepancies)

copy_block("pod_certificates", ["id", "shipment_id", "events", "evidence_hashes", "match_result",
                                "signature", "key_id", "issued_at", "pdf_uri", "bundle_uri"], pod_certs)

copy_block("agent_actions", ["id", "shipment_id", "trigger_event", "steps", "outcome",
                             "reasoning_summary", "policy_id", "created_at"], AGENT_ACTIONS)

copy_block("webhook_deliveries", ["id", "webhook_id", "event", "payload", "status_code",
                                  "attempts", "delivered_at", "last_error", "created_at"],
           WEBHOOK_DELIVERIES)

# --- audit chain: one call per entry so the sealing trigger builds the links --
W("\n-- Each append is sealed to the previous by the audit_log_seal trigger.\n")
for e in audit_entries:
    W(f"SELECT audit_append({q(e['actor'])}, {q(e['action'])}, {q(e['subject'])}, "
      f"{q(e['payload'])}, {q(e['at'])});\n")

W("\nINSERT INTO payment_events (id, shipment_id, kind, actor, policy_id, amount, payload, audit_seq, created_at) VALUES\n")
W(",\n".join(f"  ({q(p['id'])}, {q(p['shipment_id'])}, {q(p['kind'])}, {q(p['actor'])}, "
             f"{q(p['policy_id'])}, {p['amount']}, {q(p['payload'])}, {p['audit_seq']}, {q(p['created_at'])})"
             for p in payment_events) + ";\n")

W("""
ALTER TABLE audit_log ENABLE TRIGGER audit_log_no_update_trg;

COMMIT;

-- Sanity: the chain must verify immediately after seeding.
SELECT * FROM audit_verify_chain();
""")

os.makedirs(os.path.dirname(OUT_PATH), exist_ok=True)
with open(OUT_PATH, "w") as fh:
    fh.write(out.getvalue())

print(f"wrote {OUT_PATH}  ({len(out.getvalue())/1024:.0f} KB)")
print(f"  packages   {len(packages):>6}   (masters {sum(1 for p in packages if p['kind']!='UNIT')}, "
      f"units {sum(1 for p in packages if p['kind']=='UNIT')})")
print(f"  labels     {len(labels):>6}")
print(f"  scans      {len(scans):>6}")
print(f"  documents  {len(documents):>6}")
print(f"  audit      {len(audit_entries):>6}")
print(f"  public key {PUBKEY_B64}")
for sp in SHIPMENTS:
    print(f"  {sp['ref']}  {sp['status']:<11} order ₹{sp['_order_value']:>12,.2f}  "
          f"{sp['_fstatus']:<9} held ₹{sp['_held']:>10,.2f}")
