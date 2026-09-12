package com.veritransit.dashboard

import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.time.Instant

/**
 * Server-rendered HTML for the back-office dashboard — the same "Field
 * Operational Precision" language as the app: alabaster canvas, transit
 * burgundy, mono type for machine-verified values. Accepted receipts show
 * ACCEPTED and an OK TO PAY badge; flagged ones hold the delivery and the payment.
 */
object Pages {

    private fun esc(s: String): String = s
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private fun pdfHref(id: String): String =
        "/report/" + URLEncoder.encode(id, StandardCharsets.UTF_8) + ".pdf"

    fun dashboard(vault: Vault, now: Instant): String {
        val rows = vault.all.joinToString("\n") { row(it, now) }
        val cleared = vault.shipped
        return """
<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>VeriTransit — Receiving Dashboard</title>
<style>
  :root {
    --alabaster:#F4F3F0; --surface:#FFFFFF; --inset:#EFEDE9; --hairline:#E2E8F0;
    --primary:#7E1530; --ink:#0F172A; --slate:#334155; --muted:#64748B; --faint:#94A3B8;
    --emerald:#059669; --emerald-bg:#ECFDF5; --emerald-line:#A7F3D0;
    --amber:#B45309; --amber-bg:#FFFBEB; --amber-line:#FDE68A;
    --crimson:#B91C1C; --crimson-bg:#FEF2F2; --crimson-line:#FECACA;
    --mono:'JetBrains Mono','SF Mono',Menlo,Consolas,monospace;
  }
  * { box-sizing:border-box; }
  body { margin:0; background:var(--alabaster); color:var(--ink);
         font:15px/1.5 'Hanken Grotesk',-apple-system,'Segoe UI',sans-serif; }
  .wrap { max-width:1060px; margin:0 auto; padding:28px 20px 60px; }
  header h1 { margin:0; font-size:30px; letter-spacing:-0.02em; }
  header p { margin:4px 0 0; color:var(--muted); }
  .brand { display:flex; align-items:center; gap:14px; }
  .mark { width:52px; height:52px; border-radius:12px; background:var(--primary);
          color:#fff; display:flex; align-items:center; justify-content:center;
          font-size:26px; }
  .stats { display:grid; grid-template-columns:repeat(auto-fit,minmax(210px,1fr));
           gap:12px; margin:24px 0; }
  .stat { background:var(--surface); border:1px solid var(--hairline); border-radius:10px;
          padding:14px 16px; }
  .stat .n { font-family:var(--mono); font-size:26px; font-weight:700; }
  .stat .l { color:var(--muted); font-size:12.5px; text-transform:uppercase; letter-spacing:.08em; }
  .stat.ok .n { color:var(--emerald); }
  .stat.hold .n { color:var(--amber); }
  .stat.wait .n { color:var(--faint); }
  table { width:100%; border-collapse:separate; border-spacing:0 10px; }
  tr.card td { background:var(--surface); border-top:1px solid var(--hairline);
               border-bottom:1px solid var(--hairline); padding:14px 16px; vertical-align:middle; }
  tr.card td:first-child { border-left:1px solid var(--hairline); border-radius:10px 0 0 10px; }
  tr.card td:last-child { border-right:1px solid var(--hairline); border-radius:0 10px 10px 0; }
  .id { font-family:var(--mono); font-weight:600; font-size:13.5px; }
  .ref { font-family:var(--mono); color:var(--slate); font-size:12.5px; }
  .meta { color:var(--muted); font-size:12.5px; }
  .badge { display:inline-block; font-family:var(--mono); font-weight:700; font-size:10.5px;
           letter-spacing:.07em; padding:5px 10px; border-radius:5px; border:1px solid; white-space:nowrap; }
  .badge.shipped { color:var(--emerald); background:var(--emerald-bg); border-color:var(--emerald-line); }
  .badge.held { color:var(--crimson); background:var(--crimson-bg); border-color:var(--crimson-line); }
  .badge.wait { color:var(--faint); background:var(--inset); border-color:var(--hairline); }
  .badge.pay-ok { color:#fff; background:var(--emerald); border-color:var(--emerald); }
  .badge.pay-no { color:var(--amber); background:var(--amber-bg); border-color:var(--amber-line); }
  a.pdf { font-family:var(--mono); font-size:12px; color:var(--primary); text-decoration:none;
          border:1px solid var(--hairline); border-radius:5px; padding:6px 10px; background:var(--inset);
          white-space:nowrap; }
  a.pdf:hover { background:var(--amber-bg); }
  footer { margin-top:26px; color:var(--faint); font-size:12px; font-family:var(--mono); }
</style>
</head>
<body>
<div class="wrap">
  <header>
    <div class="brand">
      <div class="mark">&#10003;</div>
      <div>
        <h1>VeriTransit <span style="color:var(--muted);font-weight:400">· Receiving Dashboard</span></h1>
        <p>Dock receipts, acceptance state &amp; payment clearance — ${vault.all.size} receipts on file</p>
      </div>
    </div>
  </header>

  <div class="stats">
    <div class="stat ok"><div class="n">${vault.shipped}</div><div class="l">Accepted · cleared to pay</div></div>
    <div class="stat hold"><div class="n">${vault.held}</div><div class="l">Held — do not pay</div></div>
    <div class="stat wait"><div class="n">${vault.awaiting}</div><div class="l">Awaiting count</div></div>
  </div>

  <table>
$rows
  </table>

  <footer>Reports are deterministic PDFs — identical records hash identically.
  Dock app pushes land on POST /api/records.</footer>
</div>
</body>
</html>
"""
    }

    private fun relative(now: Instant, ts: Long): String {
        if (ts <= 0) return "—"
        val mins = Duration.between(Instant.ofEpochMilli(ts), now).toMinutes().coerceAtLeast(0)
        return when {
            mins < 1 -> "just now"
            mins < 60 -> "$mins min ago"
            mins < 24 * 60 -> "${mins / 60} hr ago"
            else -> "${mins / (24 * 60)} days ago"
        }
    }

    private fun row(r: ReceivingRecord, now: Instant): String {
        val state = ShipState.of(r.outcome)
        val (stateClass, paymentBadge) = when (state) {
            ShipState.SHIPPED -> "shipped" to "<span class=\"badge pay-ok\">OK TO PAY</span>"
            ShipState.HELD -> "held" to "<span class=\"badge pay-no\">DO NOT PAY</span>"
            ShipState.AWAITING -> "wait" to "<span class=\"badge pay-no\">PAYMENT ON HOLD</span>"
        }
        return """
    <tr class="card">
      <td style="min-width:150px">
        <div class="id">${esc(r.id)}</div>
        <div class="meta">${esc(relative(now, r.timestamp))}</div>
      </td>
      <td>
        <div class="ref">${esc(r.purchaseOrderId)} · ${esc(r.packingListId)}</div>
        <div>${esc(r.supplier)} — ${esc(r.goods)}</div>
        <div class="meta">${esc(r.dock)}${if (r.carrier.isNotBlank()) " · ${esc(r.carrier)}" else ""} · ${r.totalUnits} units packed</div>
      </td>
      <td style="text-align:right;white-space:nowrap">
        <span class="badge $stateClass">${esc(state.label.uppercase())}</span><br><br>
        $paymentBadge
      </td>
      <td style="text-align:right">
        <a class="pdf" href="${pdfHref(r.id)}">PDF report &#8595;</a>
      </td>
    </tr>"""
    }
}
