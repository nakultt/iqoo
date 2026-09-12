#!/usr/bin/env python3
"""
Verify stored label signatures the way a field device does (§4.2 / §7.1 L1).

Reads the public key from `signing_keys` and every live label from the database,
then checks the Ed25519 signature over the token body — no private key, no
network, exactly the offline check the phone performs. Also plants a tampered
token to confirm verification actually fails when it should.
"""
import csv, io, subprocess, sys
from base64 import urlsafe_b64decode
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PublicKey
from cryptography.exceptions import InvalidSignature

DB = sys.argv[1] if len(sys.argv) > 1 else "veritransit"

def b64u_dec(s: str) -> bytes:
    return urlsafe_b64decode(s + "=" * (-len(s) % 4))

def psql(sql: str):
    """CSV, not psql's default pipe format — label payloads contain '|' themselves."""
    out = subprocess.run(["psql", "-d", DB, "-qc", r"\copy (" + sql + r") TO STDOUT WITH (FORMAT csv)"],
                         capture_output=True, text=True, check=True).stdout
    return list(csv.reader(io.StringIO(out)))

pubkeys = {}
for kid, pk in psql("SELECT key_id, public_key FROM signing_keys WHERE active"):
    pubkeys[kid] = Ed25519PublicKey.from_public_bytes(b64u_dec(pk))
print(f"loaded {len(pubkeys)} active signing key(s): {', '.join(pubkeys)}")

rows = psql(
    "SELECT l.key_id, l.payload, p.package_code, s.ref "
    "FROM labels l JOIN packages p ON p.id=l.package_id "
    "JOIN shipments s ON s.id=p.shipment_id WHERE l.superseded_at IS NULL")

ok = bad = 0
failures = []
for kid, payload, code, ref in rows:
    body, _, sig = payload.rpartition("|SIG=")
    try:
        pubkeys[kid].verify(b64u_dec(sig), body.encode())
        # §7.1 L2: the token must also bind to the package and shipment it is on
        assert f"P={code}" in body and f"S={ref}" in body, "binding mismatch"
        ok += 1
    except (InvalidSignature, AssertionError, KeyError) as e:
        bad += 1
        failures.append((code, type(e).__name__))

print(f"verified {ok}/{len(rows)} live labels   signature+binding OK")
if failures:
    print(f"FAILURES ({bad}):")
    for code, err in failures[:10]:
        print(f"  {code}: {err}")

# Negative control: a forged token must be rejected (§3 check 1).
kid, payload, code, ref = rows[0]
body, _, sig = payload.rpartition("|SIG=")
forged = body.replace(code, "VT-P-FORGED01")
try:
    pubkeys[kid].verify(b64u_dec(sig), forged.encode())
    print("TAMPER CHECK FAILED — a forged token verified!")
    sys.exit(1)
except InvalidSignature:
    print("tamper check: forged package code rejected")

sys.exit(1 if bad else 0)
