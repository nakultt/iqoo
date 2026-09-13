package com.veritransit.dashboard

import com.veritransit.dashboard.documents.ConsignmentDocument
import com.veritransit.dashboard.documents.ConsignmentFacts
import com.veritransit.dashboard.documents.ConsignmentPaperwork
import com.veritransit.dashboard.documents.DocumentRegistry
import com.veritransit.dashboard.documents.GoodsCategory
import com.veritransit.dashboard.documents.MovementReason
import com.veritransit.dashboard.documents.Obligation
import com.veritransit.dashboard.documents.Paperwork
import com.veritransit.dashboard.documents.Requirement
import com.veritransit.dashboard.documents.TransportMode
import com.veritransit.dashboard.documents.Verification
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

    private fun pdfHref(id: String): String = reportUrl(id)

    /** `GET /report/<id>.pdf` for one receipt, id url-encoded. */
    fun reportUrl(id: String): String =
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
  a.pdf, button.pdf { font-family:var(--mono); font-size:12px; color:var(--primary); text-decoration:none;
          border:1px solid var(--hairline); border-radius:5px; padding:6px 10px; background:var(--inset);
          white-space:nowrap; }
  a.pdf:hover, button.pdf:hover { background:var(--amber-bg); }
  button.pdf { cursor:pointer; }
  .acts { display:inline-flex; gap:8px; align-items:center; }
  dialog#report { width:min(920px, 94vw); max-width:94vw; padding:0; border:1px solid var(--hairline);
          border-radius:10px; background:var(--alabaster); color:var(--ink); }
  dialog#report::backdrop { background:rgba(15,23,42,.45); }
  dialog#report .bar { display:flex; align-items:center; gap:12px; padding:10px 14px;
          border-bottom:1px solid var(--hairline); background:var(--surface);
          border-radius:10px 10px 0 0; }
  dialog#report .bar .id { flex:1; }
  dialog#report iframe { display:block; width:100%; height:78vh; border:0; border-radius:0 0 10px 10px;
          background:var(--surface); }
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

<dialog id="report">
  <div class="bar">
    <span class="id" id="report-title">&mdash;</span>
    <a class="pdf" id="report-download" href="#">PDF report &#8595;</a>
    <button class="pdf" id="report-close" type="button">Close</button>
  </div>
  <iframe id="report-frame" title="Receipt report"></iframe>
</dialog>
<script>
  (function () {
    var dlg = document.getElementById('report');
    var frame = document.getElementById('report-frame');
    var title = document.getElementById('report-title');
    var download = document.getElementById('report-download');
    document.querySelectorAll('button.pdf.view').forEach(function (b) {
      b.addEventListener('click', function () {
        var id = b.getAttribute('data-id');
        var url = '/report/' + encodeURIComponent(id) + '.pdf';
        title.textContent = id + ' — receipt report';
        download.href = url;
        frame.src = url;
        dlg.showModal();
      });
    });
    document.getElementById('report-close').addEventListener('click', function () { dlg.close(); });
    dlg.addEventListener('close', function () { frame.src = 'about:blank'; });
  })();
</script>
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
        $paymentBadge${if (r.flagged) "<br><br><a class=\"claim\" href=\"${receiptUrl(r.id)}\">Insurance &amp; claim &#9656;</a>" else ""}
      </td>
      <td style="text-align:right">
        <span class="acts">
          <a class="pdf" href="${receiptUrl(r.id)}">Paperwork</a>
          <button class="pdf view" data-id="${esc(r.id)}" title="Open the report on this page">View report</button>
          <a class="pdf" href="${pdfHref(r.id)}">PDF &#8595;</a>
        </span>
      </td>
    </tr>"""
    }

    /** `GET /receipt/<id>` — the per-receipt paperwork page. */
    fun receiptUrl(id: String): String =
        "/receipt/" + URLEncoder.encode(id, StandardCharsets.UTF_8)

    /**
     * One receipt's paperwork, resolved live against the consignment document
     * registry: what the law requires, what commerce expects, which rules
     * cannot be decided until a fact is supplied — and, when the dock found
     * the goods short or damaged, the claim documents with their clocks.
     * Reference for the receiving team: the app verifies no document and
     * states no legal position, and there is no insurance-policy integration
     * to attach a claim to — the registry is what this app knows.
     */
    fun receiptPage(
        record: ReceivingRecord,
        facts: ConsignmentFacts,
        now: Instant,
        query: Map<String, String> = emptyMap(),
    ): String {
        val paperwork = if (facts.resolvable) ConsignmentPaperwork.resolve(facts.toConsignment()) else null
        val claimCard = receiptPageClaim(record, facts, paperwork)
        val factForm = receiptPageForm(record, facts, query)
        val resolution = receiptPageResolution(paperwork)
        val digest = receiptPageDigest(record)
        val plan = paperworkPlan(record, facts, query)
        val agentRan = query["agent"] == "run"
        val agentCard = if (agentRan) agentRunSection(record, facts, query) else ""
        val state = ShipState.of(record.outcome)
        val (stateClass, paymentBadge) = when (state) {
            ShipState.SHIPPED -> "shipped" to "OK TO PAY"
            ShipState.HELD -> "held" to "DO NOT PAY"
            ShipState.AWAITING -> "wait" to "PAYMENT ON HOLD"
        }
        return """
<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>${esc(record.id)} — VeriTransit paperwork</title>
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
  .wrap { max-width:900px; margin:0 auto; padding:28px 20px 60px; }
  .crumb { font-family:var(--mono); font-size:12px; color:var(--muted); text-decoration:none; }
  .crumb:hover { color:var(--primary); }
  h1 { margin:6px 0 0; font-size:26px; letter-spacing:-0.02em; display:flex; align-items:center; gap:14px; flex-wrap:wrap; }
  .badge { display:inline-block; font-family:var(--mono); font-weight:700; font-size:10.5px;
           letter-spacing:.07em; padding:5px 10px; border-radius:5px; border:1px solid; white-space:nowrap; }
  .badge.shipped { color:var(--emerald); background:var(--emerald-bg); border-color:var(--emerald-line); }
  .badge.held { color:var(--crimson); background:var(--crimson-bg); border-color:var(--crimson-line); }
  .badge.wait { color:var(--faint); background:var(--inset); border-color:var(--hairline); }
  .badge.pay-ok { color:#fff; background:var(--emerald); border-color:var(--emerald); }
  .badge.pay-no { color:var(--amber); background:var(--amber-bg); border-color:var(--amber-line); }
  section { margin-top:18px; }
  h2 { font-family:var(--mono); font-size:11.5px; font-weight:700; letter-spacing:.09em;
       color:var(--muted); margin:0 0 10px; text-transform:uppercase; }
  .doc { background:var(--surface); border:1px solid var(--hairline); border-radius:8px;
         padding:12px 14px; margin-bottom:10px; }
  .doc .t { font-weight:600; font-size:14.5px; color:var(--ink); display:flex; justify-content:space-between;
            gap:10px; align-items:center; flex-wrap:wrap; }
  .doc .c { font-family:var(--mono); font-size:12px; color:var(--slate); }
  .doc .s { color:var(--slate); font-size:13.5px; }
  .doc .m { color:var(--muted); font-size:12.5px; }
  .vmark { font-family:var(--mono); font-size:10.5px; padding:3px 8px; border-radius:4px;
           border:1px solid; white-space:nowrap; }
  .v-primary { color:var(--emerald); background:var(--emerald-bg); border-color:var(--emerald-line); }
  .v-weak { color:var(--amber); background:var(--amber-bg); border-color:var(--amber-line); }
  .state { font-family:var(--mono); font-weight:700; font-size:10px; letter-spacing:.07em;
           padding:4px 8px; border-radius:4px; border:1px solid; }
  .state.req { color:var(--emerald); background:var(--emerald-bg); border-color:var(--emerald-line); }
  .state.custom { color:var(--slate); background:var(--inset); border-color:var(--hairline); }
  .state.undet { color:var(--amber); background:var(--amber-bg); border-color:var(--amber-line); }
  .state.no { color:var(--faint); background:var(--inset); border-color:var(--hairline); }
  .claimcard { background:var(--amber-bg); border:1px solid var(--amber-line); border-radius:10px; padding:16px; }
  .claimcard .doc { background:var(--surface); }
  .claimcard p.lede { margin:8px 0 12px; color:var(--slate); font-size:13.5px; }
  .gate { background:var(--amber-bg); border:1px solid var(--amber-line); border-radius:10px; padding:16px; }
  .gate p { margin:6px 0 0; color:var(--slate); }
  .chips { display:flex; gap:8px; flex-wrap:wrap; margin-bottom:12px; }
  .chip { font-family:var(--mono); font-size:11.5px; padding:5px 10px; border-radius:5px; border:1px solid var(--hairline);
          background:var(--surface); color:var(--slate); }
  .chip b { font-weight:700; }
  .chip.req b { color:var(--emerald); } .chip.undet b { color:var(--amber); }
  .factform { background:var(--surface); border:1px solid var(--hairline); border-radius:10px; padding:16px; }
  .fgrid { display:grid; grid-template-columns:repeat(auto-fit,minmax(150px,1fr)); gap:12px; }
  .factform label.f { display:block; font-family:var(--mono); font-size:11px; color:var(--muted); margin:0 0 4px;
                      text-transform:uppercase; letter-spacing:.06em; }
  .factform select, .factform input[type=text], .factform input[type=number] {
      width:100%; padding:8px 10px; border:1px solid var(--hairline); border-radius:6px;
      font:14px 'Hanken Grotesk',sans-serif; background:#fff; color:var(--ink); }
  .fchecks { display:flex; flex-wrap:wrap; gap:8px 18px; margin-top:12px; }
  .fchecks label { display:flex; align-items:center; gap:7px; font-size:14px; color:var(--slate); }
  .fnote { font-family:var(--mono); font-size:10.5px; color:var(--faint); margin-top:8px; }
  button.resolve { margin-top:14px; background:var(--primary); color:#fff; border:0; border-radius:6px;
                   padding:10px 18px; font:600 14px 'Hanken Grotesk',sans-serif; cursor:pointer; }
  button.resolve:hover { background:#5C001D; }
  .digest { display:flex; gap:18px; background:var(--surface); border:1px solid var(--hairline);
            border-radius:10px; padding:14px; align-items:flex-start; flex-wrap:wrap; }
  .goodsfig { margin:0; width:220px; }
  .goodsfig img { width:100%; border-radius:8px; border:1px solid var(--hairline); display:block; }
  .goodsfig figcaption { font-family:var(--mono); font-size:10px; color:var(--faint); margin-top:6px; }
  .digestinfo { flex:1; min-width:300px; }
  table.lines { width:100%; border-collapse:collapse; font-size:13px; }
  table.lines th { font-family:var(--mono); font-size:10px; letter-spacing:.06em; color:var(--muted);
                   text-transform:uppercase; text-align:left; padding:6px 8px; border-bottom:1px solid var(--hairline); }
  table.lines td { padding:6px 8px; border-bottom:1px solid var(--inset); color:var(--slate); }
  table.lines td.num, table.lines th.num { text-align:right; font-family:var(--mono); }
  table.lines td.mono { font-family:var(--mono); font-size:12px; }
  table.lines .m { color:var(--muted); font-size:12px; }
  .state.bad { color:var(--crimson); background:var(--crimson-bg); border-color:var(--crimson-line); }
  .check { margin-top:8px; background:var(--inset); border-radius:6px; padding:8px 10px;
           font-size:12.5px; color:var(--slate); }
  .check .c { color:var(--muted); font-family:var(--mono); font-size:10.5px; text-transform:uppercase; letter-spacing:.06em; }
  .task .step { display:inline-flex; width:22px; height:22px; border-radius:50%; background:var(--primary);
                color:#fff; font-family:var(--mono); font-weight:700; font-size:11.5px;
                align-items:center; justify-content:center; margin-right:6px; }
  .task.done { opacity:.72; }
  .task.done .t span:first-child { text-decoration:line-through; }
  .task.done .step { background:var(--emerald); }
  .task.wait { background:var(--inset); }
  .state.draft { color:#0369A1; background:#F0F9FF; border-color:#BAE6FD; }
  .state.human { color:var(--amber); background:var(--amber-bg); border-color:var(--amber-line); }
  .agentcard { background:var(--surface); border:1px solid var(--hairline); border-radius:10px; padding:16px; }
  .agentcard h2 { color:var(--primary); }
  .doc.log { border-left:3px solid var(--primary); }
  pre.draft { margin:8px 0 0; padding:10px 12px; background:var(--inset); border:1px solid var(--hairline);
              border-radius:6px; font-family:var(--mono); font-size:11px; line-height:1.55; color:var(--slate);
              white-space:pre-wrap; overflow-x:auto; }
  .tick.agentgo { background:var(--primary); color:#fff; border-color:var(--primary); font-weight:700; }
  .tick.agentgo:hover { background:#5C001D; }
  .tick { font-family:var(--mono); font-size:11px; color:var(--primary); text-decoration:none;
          border:1px solid var(--hairline); border-radius:4px; padding:4px 8px; background:var(--inset); }
  .tick:hover { background:var(--amber-bg); }
  a.pdf { font-family:var(--mono); font-size:12px; color:var(--primary); text-decoration:none;
          border:1px solid var(--hairline); border-radius:5px; padding:6px 10px; background:var(--inset); }
  a.pdf:hover { background:var(--amber-bg); }
  footer { margin-top:26px; color:var(--faint); font-size:12px; font-family:var(--mono); }
</style>
</head>
<body>
<div class="wrap">
  <a class="crumb" href="/">&#8592; Receiving Dashboard</a>
  <h1>${esc(record.id)}
    <span class="badge $stateClass">${esc(state.label.uppercase())}</span>
    <span class="badge ${if (state == ShipState.SHIPPED) "pay-ok" else "pay-no"}">$paymentBadge</span>
  </h1>
  <p style="margin:4px 0 0;color:var(--muted)">${esc(record.supplier)} — ${esc(record.goods)} ·
     ${esc(record.purchaseOrderId)} · ${esc(record.packingListId)} · ${esc(record.dock)}${if (record.carrier.isNotBlank()) " · " + esc(record.carrier) else ""}</p>
$digest
$agentCard
$plan
$claimCard
$factForm
$resolution
  <footer>Reference only — the registry is data read from the cited provisions; this app verifies no
  document and states no legal position. Reports stay deterministic PDFs on
  <a class="pdf" href="${pdfHref(record.id)}">/report/${esc(record.id)}.pdf</a>.</footer>
</div>
</body>
</html>
"""
    }

    /**
     * The recommended paperwork runbook for this order: every rule that bites,
     * ordered as numbered tasks with owner, clock and a mock tick — plus the
     * rules that cannot be scheduled until a fact is answered. The tick lives
     * in the page's own link (nothing is filed anywhere); it is a demo
     * stand-in for the day documents are actually issued and tracked.
     */
    private fun paperworkPlan(record: ReceivingRecord, facts: ConsignmentFacts, query: Map<String, String>): String {
        val done = (query["done"] ?: "").split(',').filter { it.isNotBlank() }.toSet()
        // Trigger evaluation never reads the goods category, so the plan can be
        // produced even while the family question is still open.
        val resolved = ConsignmentPaperwork.resolve(facts.toConsignment())
        val tasks = resolved.required + resolved.customary
        val agentRan = query["agent"] == "run"
        val agent = if (agentRan) agentActions(record, facts, resolved) else emptyMap()
        val base = query.filterKeys { it != "done" }
        fun href(extra: Map<String, String>): String {
            val params = base + extra
            val qs = params.entries.joinToString("&") { (k, v) ->
                "${URLEncoder.encode(k, StandardCharsets.UTF_8)}=${URLEncoder.encode(v, StandardCharsets.UTF_8)}"
            }
            return receiptUrl(record.id) + "?" + qs
        }
        fun toggleHref(id: String): String {
            val newDone = (if (id in done) done - id else done + id).toSortedSet().joinToString(",")
            return href(mapOf("done" to newDone))
        }
        val agentCtl = if (agentRan) {
            "<a class=\"tick\" href=\"${esc(href(emptyMap()))}\">Reset run</a>"
        } else {
            "<a class=\"tick agentgo\" href=\"${esc(href(mapOf("agent" to "run")))}\">&#9654; Let the agent do it</a>"
        }
        val rows = tasks.mapIndexed { index, req ->
            val isDone = req.id in done
            val agentOutcome = agent[req.id]
            val stateChip = when {
                isDone -> "<span class=\"state req\">DONE</span>"
                agentOutcome != null -> "<span class=\"state ${agentOutcome.chipClass}\">${agentOutcome.status}</span>"
                req.obligation == Obligation.CUSTOMARY -> "<span class=\"state custom\">CUSTOMARY</span>"
                else -> "<span class=\"state undet\">TO DO</span>"
            }
            val clock = req.deadline?.let { "<div class=\"m\">&#9201; ${esc(it.label)}</div>" } ?: ""
            val taskClass = if (isDone) "task done" else "task"
            """
      <div class="doc $taskClass">
        <div class="t">
          <span><span class="step">${index + 1}</span> ${esc(req.document.title)}
            <span class="m">· ${esc(req.holder.label)} ${esc(req.duty.verb)}</span></span>
          <span>$stateChip <a class="tick" href="${esc(toggleHref(req.id))}">${if (isDone) "Undo" else "Mark done"}</a></span>
        </div>
        <div class="m">because: ${esc(req.trigger.label)}</div>
        $clock
      </div>"""
        }.joinToString("\n")
        val unschedulable = resolved.undetermined.joinToString("\n") { req ->
            "<div class=\"doc task wait\"><div class=\"t\"><span>${esc(req.document.title)}</span>" +
                "<span><span class=\"state no\">CAN'T SCHEDULE</span></span></div>" +
                "<div class=\"m\">needs an answer first: ${esc(req.trigger.label)}</div></div>"
        }
        val progress = "<span class=\"chip\"><b>${tasks.count { it.id in done }}</b> of ${tasks.size} done</span>"
        return """
  <section>
    <h2>Paperwork plan — recommended for this order</h2>
    <div class="chips">$progress
      <span class="chip"><b>${resolved.required.size}</b> legal</span>
      <span class="chip"><b>${resolved.customary.size}</b> customary</span>
      <span class="chip undet"><b>${resolved.undetermined.size}</b> unschedulable yet</span>
      $agentCtl
    </div>
$rows
$unschedulable
    <div class="fnote">Mock runbook — ticking stores nothing anywhere; the state lives in this page's link.
    Order follows the registry: what travels with the goods, what is generated, what follows the receipt, then practice.</div>
  </section>"""
    }

    /** The receipt's own picture and count: the goods figure beside the line table. */
    private fun receiptPageDigest(record: ReceivingRecord): String {
        val img = GoodsImage.of(record)
        val rows = record.items.joinToString("\n") { item ->
            val (cls, label) = when (item.status) {
                ItemStatus.MATCHED -> "req" to "MATCH"
                ItemStatus.OVER -> "undet" to "OVER"
                ItemStatus.UNLISTED, ItemStatus.DAMAGED -> "bad" to item.status.name
                ItemStatus.SHORT -> "undet" to "SHORT"
            }
            "<tr><td class=\"mono\">${esc(item.sku)}</td><td>${esc(item.name)}${if (item.detail.isNotBlank()) " <span class=\"m\">· ${esc(item.detail)}</span>" else ""}</td>" +
                "<td class=\"num\">${item.expected}</td><td class=\"num\">${item.received}</td><td class=\"num\">${item.damaged}</td>" +
                "<td><span class=\"state $cls\">$label</span></td></tr>"
        }
        val conf = if (record.confidence > 0f) " · model confidence ${PdfReport.pct(record.confidence)}" else ""
        return """
  <section>
    <div class="digest">
      <figure class="goodsfig">
        <img src="/img/report-${img.name.lowercase()}.jpg" alt="Goods family illustration">
        <figcaption>${esc(img.label)} — reference picture, not the consignment.
        The dock's own evidence frames live in the <a class="pdf" href="${pdfHref(record.id)}">PDF report</a>.</figcaption>
      </figure>
      <div class="digestinfo">
        <h2>The count on this receipt</h2>
        <table class="lines">
          <tr><th>SKU</th><th>Line</th><th style="text-align:right">Packed</th><th style="text-align:right">Received</th><th style="text-align:right">Damaged</th><th>Status</th></tr>
          $rows
        </table>
        <div class="m" style="margin-top:6px">${record.discrepancyCount} ${if (record.discrepancyCount == 1) "discrepancy" else "discrepancies"} across ${record.items.size} lines$conf</div>
      </div>
    </div>
  </section>"""
    }

    /** One thing the agent did: its status, the human-readable log line, and any draft it left. */
    private data class AgentOutcome(val status: String, val chipClass: String, val message: String, val artifact: String? = null)

    /**
     * The mock agent run. For every requirement the plan holds, the agent
     * "does" what a real integration could do with the data this page already
     * has: reconcile the invoice against the GRN, fill e-way Part A, draft the
     * carrier's loss notice, request the supplier's credit note — and stops
     * where a human or a real portal is required. Deterministic: same facts,
     * same run, same drafts.
     */
    private fun agentActions(
        record: ReceivingRecord,
        facts: ConsignmentFacts,
        paperwork: Paperwork,
    ): Map<String, AgentOutcome> {
        val out = LinkedHashMap<String, AgentOutcome>()
        val shortLines = record.items.filter { it.received < it.expected }
        val damagedLines = record.items.filter { it.damaged > 0 }
        val shortUnits = shortLines.sumOf { it.expected - it.received }
        val all = paperwork.required + paperwork.customary + paperwork.undetermined
        for (req in all) {
            val outcome = when (req.document) {
                ConsignmentDocument.TAX_INVOICE ->
                    if (facts.supplierEInvoicing == true) {
                        AgentOutcome("YOU", "human", "E-invoicing is notified for this supplier — the IRN must come from the " +
                            "GST portal. Verified the invoice copy against the GRN instead: ${record.items.size} lines, 0 quantity mismatches.")
                    } else {
                        AgentOutcome("DONE", "req", "Verified the invoice copy against PO ${record.purchaseOrderId.ifBlank { "—" }} " +
                            "and this GRN: ${record.items.size} lines, 0 quantity mismatches, r.48(1) copy markings checked.")
                    }
                ConsignmentDocument.EWAY_BILL ->
                    if (facts.value == null) {
                        AgentOutcome("BLOCKED", "bad", "Cannot fill Part A without the r.138(1) value — ask the supplier's invoice " +
                            "or answer it in the facts form.")
                    } else {
                        AgentOutcome("DRAFTED", "draft", "Filled Part A from the invoice and your facts; Part B (vehicle) is the " +
                            "transporter's — handed off with the draft.", ewayDraft(record, facts))
                    }
                ConsignmentDocument.CARRIER_NOTICE ->
                    if (record.flagged || facts.discrepancyAtReceipt) {
                        AgentOutcome("DRAFTED", "draft", "Drafted the written notice of loss/shortage to ${record.carrier} — " +
                            "ready for an authorised signature before it is served.", carrierNoticeDraft(record))
                    } else {
                        AgentOutcome("SKIPPED", "no", "No discrepancy at receipt — nothing to notify the carrier about.")
                    }
                ConsignmentDocument.LORRY_RECEIPT ->
                    if (facts.byCommonCarrier) {
                        AgentOutcome("DONE", "req", "Collected the consignment note from the transporter and filed it as the " +
                            "claim anchor. Booking date recorded — the 180-day clock runs from it.")
                    } else {
                        AgentOutcome("SKIPPED", "no", "Not a common-carrier handover — no consignment note on file.")
                    }
                ConsignmentDocument.CREDIT_NOTE ->
                    if (shortLines.isNotEmpty() || damagedLines.isNotEmpty()) {
                        AgentOutcome("DRAFTED", "draft", "Requested a credit note from ${record.supplier} for " +
                            "$shortUnits short unit(s)${if (damagedLines.isNotEmpty()) " and ${damagedLines.sumOf { it.damaged }} damaged" else ""} — " +
                            "declaration deadline is the s.34(2) cutoff.", creditNoteDraft(record, shortUnits))
                    } else {
                        AgentOutcome("SKIPPED", "no", "Full count — no credit note to request.")
                    }
                ConsignmentDocument.DELIVERY_CHALLAN ->
                    if (facts.reason == MovementReason.SUPPLY && !facts.inLots) {
                        AgentOutcome("SKIPPED", "no", "Movement is a supply — an invoice covers it, no challan needed.")
                    } else {
                        AgentOutcome("DRAFTED", "draft", "Prepared the r.55 delivery challan shell (triplicate markings, " +
                            "r.55(1) particulars) for the transporter's copy.")
                    }
                ConsignmentDocument.GOODS_RECEIVED_NOTE ->
                    AgentOutcome("DONE", "req", "GRN ${record.id} is on file, signed subject to inspection until the count closed.")
                ConsignmentDocument.PURCHASE_ORDER ->
                    AgentOutcome("DONE", "req", "Anchored the three-way match on PO ${record.purchaseOrderId.ifBlank { "—" }}.")
                ConsignmentDocument.PACKING_LIST ->
                    AgentOutcome("DONE", "req", "Packing list ${record.packingListId.ifBlank { "—" }} archived with the receipt.")
                ConsignmentDocument.RECORDS ->
                    AgentOutcome("DONE", "req", "Retention noted: 72 months from the annual return's due date (s.36).")
                ConsignmentDocument.BILL_OF_ENTRY ->
                    if (facts.imported) {
                        AgentOutcome("DONE", "req", "Bill-of-entry number and date copied into e-way Part A.")
                    } else {
                        AgentOutcome("SKIPPED", "no", "Not an import.")
                    }
                ConsignmentDocument.ITC_04 ->
                    if (facts.reason == MovementReason.JOB_WORK) {
                        AgentOutcome("DRAFTED", "draft", "Queued the job-work challans for ITC-04 — check the half-year split " +
                            "(turnover above ₹5 crore) before furnishing.")
                    } else {
                        AgentOutcome("SKIPPED", "no", "Not a job-work movement.")
                    }
            }
            out[req.id] = outcome
        }
        return out
    }

    /** Drafted carrier notice — the claim document for short/damaged goods. */
    private fun carrierNoticeDraft(r: ReceivingRecord): String {
        val lines = r.items.filter { it.received < it.expected || it.damaged > 0 }
            .joinToString("\n") {
                "  ${it.sku} · ${it.name} — packed ${it.expected}, received ${it.received}" +
                    (if (it.damaged > 0) ", damaged ${it.damaged}" else "")
            }
        return """
To:    ${r.carrier} (carrier of record)
From:  ${r.warehouse} — Goods-In
Re:    Written notice of loss / shortage — lorry receipt no. ______, booked on ______

We received, under the above consignment note:

$lines

The goods were received subject to inspection; the discrepancy is recorded in
goods-received note ${r.id} (PO ${r.purchaseOrderId} / PL ${r.packingListId}, ${r.supplier}).

This letter constitutes written notice of the shortage within the meaning of
the Carriage by Road Act, 2007, served within 180 days of booking. We reserve
all rights to claim the value of the missing goods and associated charges.

Please acknowledge receipt of this notice.
Goods-In Office — signature required before service."""
            .trim()
    }

    /** Drafted credit-note request — the supplier side of a shortage. */
    private fun creditNoteDraft(r: ReceivingRecord, shortUnits: Int): String =
        "To ${r.supplier}: request credit note against invoice no. ______ for $shortUnits unit(s) " +
            "short under PO ${r.purchaseOrderId} / GRN ${r.id}. Kindly reference the original invoice " +
            "and declare it by the s.34(2) cutoff. — ${r.warehouse}"

    /** Drafted e-way bill Part A — everything the app actually knows. */
    private fun ewayDraft(r: ReceivingRecord, facts: ConsignmentFacts): String =
        "PART A (draft) — supplier: ${r.supplier} · recipient: ${r.warehouse} · " +
            "value: ${com.veritransit.dashboard.documents.formatRupees(facts.value!!.paise)} " +
            "(r.138(1): declared + tax − exempt) · PART B (vehicle): with the transporter — " +
            "invalid by road until filled (r.138(3) Expl. 2)"

    /**
     * The agent run log: what the agent did for this order, in plan order, with
     * its drafts. A mock of the day an integration actually does this — it
     * sends nothing, files nothing and verifies nothing anywhere.
     */
    private fun agentRunSection(record: ReceivingRecord, facts: ConsignmentFacts, query: Map<String, String>): String {
        val resolved = ConsignmentPaperwork.resolve(facts.toConsignment())
        val actions = agentActions(record, facts, resolved)
        val tasks = resolved.required + resolved.customary
        val entries = (tasks + resolved.undetermined).mapNotNull { req ->
            val outcome = actions[req.id] ?: AgentOutcome("BLOCKED", "bad", "Needs an answer first: ${req.trigger.label}")
            val artifact = outcome.artifact?.let { "<pre class=\"draft\">${esc(it)}</pre>" } ?: ""
            "<div class=\"doc log\"><div class=\"t\">" +
                "<span><span class=\"state ${outcome.chipClass}\">${outcome.status}</span> ${esc(req.document.title)}</span>" +
                "</div><div class=\"s\">${esc(outcome.message)}</div>$artifact</div>"
        }.joinToString("\n")
        val tally = actions.values.groupingBy { it.status }.eachCount()
        val tallyChips = listOf("DONE", "DRAFTED", "SKIPPED", "BLOCKED", "YOU").mapNotNull { s ->
            tally[s]?.let { n -> "<span class=\"chip\"><b>$n</b> ${s.lowercase()}</span>" }
        }.joinToString("\n")
        return """
  <section>
    <div class="agentcard">
      <h2>Agent run — what it did for this order</h2>
      <div class="chips">$tallyChips</div>
$entries
      <div class="fnote">Mock agent run — generated on this page from the registry and the receipt. It sent,
      filed and verified nothing anywhere; drafts need an authorised human, and portal steps stay human too.</div>
    </div>
  </section>"""
    }

    private fun receiptPageClaim(record: ReceivingRecord, facts: ConsignmentFacts, paperwork: Paperwork?): String {
        if (!record.flagged && !facts.discrepancyAtReceipt) return ""
        val claimIds = listOf("carrier-notice", "lorry-receipt", "credit-note", "goods-received-note")
        val rows = claimIds.joinToString("\n") { id ->
            val req = DocumentRegistry.byId(id)
            val state = when {
                paperwork != null -> when {
                    paperwork.required.any { it.id == id } -> "req" to "REQUIRED"
                    paperwork.customary.any { it.id == id } -> "custom" to "CUSTOMARY"
                    paperwork.undetermined.any { it.id == id } -> "undet" to "CANNOT TELL"
                    else -> "no" to "NOT TRIGGERED"
                }
                else -> when (req.trigger.test(facts.toConsignment())) {
                    true -> "req" to "APPLIES"
                    null -> "undet" to "CANNOT TELL"
                    false -> "no" to "NOT TRIGGERED"
                }
            }
            docRow(req, state.first, state.second)
        }
        return """
  <section>
    <div class="claimcard">
      <h2>Insurance &amp; claim — goods found short or damaged at receipt</h2>
      <p class="lede">There is no insurance-policy integration here: the policy, its surveyor and the
      claim filing live outside this app. What the registry knows is the statutory claim paper —
      serve the notice, keep the consignment note, chase the supplier's credit — each with its clock
      and its verification mark.</p>
$rows
    </div>
  </section>"""
    }

    private fun docRow(req: Requirement, stateClass: String, stateLabel: String): String {
        val mark = req.provision?.let { p ->
            when (p.verification) {
                Verification.PRIMARY -> "<span class=\"vmark v-primary\">&#10003; govt text</span>"
                Verification.SECONDARY -> "<span class=\"vmark v-weak\">&#9651; secondary source</span>"
                Verification.UNVERIFIED -> "<span class=\"vmark v-weak\">&#9675; unverified</span>"
            }
        } ?: "<span class=\"vmark v-weak\">commercial practice</span>"
        val deadline = req.deadline?.let { "<div class=\"m\">&#9201; ${esc(it.label)}</div>" } ?: ""
        val note = if (req.note.isNullOrBlank()) "" else "<div class=\"m\">${esc(req.note)}</div>"
        val provision = req.provision?.let { "<div class=\"c\">${esc(it.cite)} — ${esc(it.substance)}</div>" } ?: ""
        val checks = docChecklist(req.document)
        val checkHtml = if (checks.isEmpty()) "" else
            "<div class=\"check\"><div class=\"c\" style=\"margin-bottom:2px\">When you hold it, check:</div>" +
                checks.joinToString("") { "<div>&#9744; ${esc(it)}</div>" } + "</div>"
        return """
      <div class="doc">
        <div class="t"><span>${esc(req.document.title)} <span class="m">· ${esc(req.holder.label)} ${esc(req.duty.verb)}</span></span>
          <span><span class="state $stateClass">$stateLabel</span> $mark</span></div>
        <div class="m">bites: ${esc(req.trigger.label)}</div>
        $provision
        $deadline
        $note
        $checkHtml
      </div>"""
    }

    /**
     * What the receiving team actually verifies on each paper, once it is in
     * hand — the page's answer to "and then what?". Deliberately operational,
     * not legal advice: every line is a match-or-mismatch a dock can check.
     */
    private fun docChecklist(doc: ConsignmentDocument): List<String> = when (doc) {
        ConsignmentDocument.TAX_INVOICE -> listOf(
            "PO ↔ invoice ↔ goods-received note: same SKUs, same quantities, same values",
            "Supplier GSTIN, and the r.48(1) copy marking on the copy you were given",
            "E-invoice case: the IRN is present and still verifiable on the portal",
        )
        ConsignmentDocument.DELIVERY_CHALLAN -> listOf(
            "r.55(1) particulars: recipient, destination, consignment value",
            "Triplicate copies — ORIGINAL FOR CONSIGNEE, DUPLICATE FOR TRANSPORTER, TRIPLICATE",
            "Job-work movement: the principal's challan, never an invoice",
        )
        ConsignmentDocument.EWAY_BILL -> listOf(
            "Part A: supplier, recipient, and a value that matches the invoice",
            "Part B: vehicle number filled — invalid by road without it (r.138(3) Expl. 2)",
            "Validity: distance against the days the bill has left",
        )
        ConsignmentDocument.BILL_OF_ENTRY -> listOf(
            "Number and date go into Part A of the e-way bill",
            "Matches the import paperwork for this consignment",
        )
        ConsignmentDocument.LORRY_RECEIPT -> listOf(
            "Carrier signature and the condition noted at handover",
            "Keep the original — it anchors any claim against the carrier",
        )
        ConsignmentDocument.ITC_04 -> listOf(
            "Challans grouped into the right half-year (above ₹5 crore turnover)",
            "Furnished by the 25th of the month after the period (r.45(3))",
        )
        ConsignmentDocument.CREDIT_NOTE -> listOf(
            "References the original invoice it corrects",
            "Declared by the s.34(2) cutoff — 30 November after the FY, or the annual return",
        )
        ConsignmentDocument.CARRIER_NOTICE -> listOf(
            "In writing, served on the carrier — before any suit",
            "The booked date anchors the 180 days, not the delivery date",
            "Keep proof of service",
        )
        ConsignmentDocument.RECORDS -> listOf(
            "Retain 72 months from the annual return's due date (s.36)",
        )
        ConsignmentDocument.PURCHASE_ORDER -> listOf(
            "It anchors the PO ↔ invoice ↔ GRN three-way match",
        )
        ConsignmentDocument.PACKING_LIST -> listOf(
            "Carton-by-carton what the supplier packed",
            "The dock count reconciles against exactly this",
        )
        ConsignmentDocument.GOODS_RECEIVED_NOTE -> listOf(
            "Signed 'subject to inspection' until the count is done",
        )
    }

    private fun receiptPageForm(record: ReceivingRecord, facts: ConsignmentFacts, query: Map<String, String>): String {
        fun sel(name: String, label: String, options: List<Triple<String, String, Boolean>>) = buildString {
            append("<label class=\"f\" for=\"$name\">$label</label><select id=\"$name\" name=\"$name\">")
            options.forEach { (v, text, picked) ->
                append("<option value=\"$v\"${if (picked) " selected" else ""}>$text</option>")
            }
            append("</select>")
        }
        fun box(name: String, label: String, checked: Boolean) =
            "<label><input type=\"checkbox\" name=\"$name\"${if (checked) " checked" else ""}> $label</label>"
        fun text(name: String, label: String, value: String) =
            "<div><label class=\"f\" for=\"$name\">$label</label>" +
                "<input type=\"text\" id=\"$name\" name=\"$name\" value=\"${esc(value)}\" placeholder=\"unknown\"></div>"
        return """
  <section>
    <h2>Consignment facts</h2>
    <form class="factform" method="get" action="${esc(receiptUrl(record.id))}">
      <input type="hidden" name="submitted" value="1">
      <input type="hidden" name="done" value="${esc((query["done"] ?: "").split(',').filter { it.isNotBlank() }.joinToString(","))}">
      <div class="fgrid">
        ${sel("category", "Goods family", listOf(
            Triple("", "— unknown —", facts.category == null),
            Triple("electronics", "Electronics", facts.category == GoodsCategory.ELECTRONICS),
            Triple("fabric", "Fabric", facts.category == GoodsCategory.FABRIC),
        ))}
        ${sel("inter", "Movement", listOf(
            Triple("", "— unknown —", facts.interState == null),
            Triple("true", "Inter-State", facts.interState == true),
            Triple("false", "Within the State", facts.interState == false),
        ))}
        ${sel("reason", "Why it moves", listOf(
            Triple("SUPPLY", "Supply", facts.reason == MovementReason.SUPPLY),
            Triple("JOB_WORK", "Job work", facts.reason == MovementReason.JOB_WORK),
            Triple("NOT_SUPPLY", "Not a supply", facts.reason == MovementReason.NOT_SUPPLY),
        ))}
        ${sel("mode", "Transport", listOf(
            Triple("ROAD", "Road", facts.mode == TransportMode.ROAD),
            Triple("RAIL", "Rail", facts.mode == TransportMode.RAIL),
            Triple("AIR", "Air", facts.mode == TransportMode.AIR),
            Triple("VESSEL", "Vessel", facts.mode == TransportMode.VESSEL),
        ))}
        ${text("declared", "r.138(1) declared ₹", ConsignmentFacts.paiseToRupeesText(facts.declaredPaise))}
        ${text("tax", "+ tax &amp; cess ₹", ConsignmentFacts.paiseToRupeesText(facts.taxPaise))}
        ${text("exempt", "− exempt ₹", ConsignmentFacts.paiseToRupeesText(facts.exemptPaise))}
        ${sel("einvoice", "E-invoicing notified", listOf(
            Triple("", "Not known", facts.supplierEInvoicing == null),
            Triple("true", "Notified", facts.supplierEInvoicing == true),
            Triple("false", "Not notified", facts.supplierEInvoicing == false),
        ))}
      </div>
      <div class="fchecks">
        ${box("supplier_reg", "Supplier GST-registered", facts.supplierRegistered)}
        ${box("recipient_reg", "Recipient GST-registered", facts.recipientRegistered)}
        ${box("imported", "Imported goods", facts.imported)}
        ${box("common_carrier", "Handed to a common carrier", facts.byCommonCarrier)}
        ${box("capital_goods", "Job work — capital goods", facts.capitalGoods)}
        ${box("in_lots", "Sent SKD/CKD or in lots", facts.inLots)}
      </div>
      <div class="fnote">Every answer left unknown keeps its rule at "cannot tell yet" — never "not required".
      The goods family and the inter-State answer gate the full resolution; the discrepancy finding comes
      from the dock receipt itself.</div>
      <button class="resolve" type="submit">Resolve paperwork</button>
    </form>
  </section>"""
    }

    private fun receiptPageResolution(paperwork: Paperwork?): String {
        if (paperwork == null) {
            return """
  <section>
    <div class="gate">
      <h2>Cannot resolve yet</h2>
      <p>Answer the goods family and whether the movement crossed a State border — the registry
      cannot default either. The claim documents above stand on their own meanwhile.</p>
    </div>
  </section>"""
        }
        val required = paperwork.required.joinToString("\n") { docRow(it, "req", "REQUIRED") }
        val customary = paperwork.customary.joinToString("\n") { docRow(it, "custom", "CUSTOMARY") }
        val undetermined = paperwork.undetermined.joinToString("\n") {
            docRow(it, "undet", "CANNOT TELL")
        }
        val profile = paperwork.profile
        val routine = profile.routine.joinToString("") { "<div class=\"m\">· ${esc(it)}</div>" }
        val notes = profile.notes.joinToString("\n") { n ->
            val (markClass, markGlyph) = when (n.provision.verification) {
                Verification.PRIMARY -> "v-primary" to "&#10003;"
                else -> "v-weak" to "&#9651;"
            }
            "<div class=\"doc\"><div class=\"s\">${esc(n.text)}</div>" +
                "<div class=\"m\"><span class=\"vmark $markClass\">$markGlyph ${esc(n.provision.cite)}</span> ${esc(n.provision.substance)}</div></div>"
        }
        val gaps = paperwork.gaps.joinToString("\n") { g ->
            "<div class=\"doc\"><div class=\"c\">${esc(g.id)}${if (g.intraStateOnly) " · intra-State only" else ""}</div>" +
                "<div class=\"s\">${esc(g.question)}</div></div>"
        }
        return """
  <section>
    <div class="chips">
      <span class="chip req"><b>${paperwork.required.size}</b> required by law</span>
      <span class="chip"><b>${paperwork.customary.size}</b> customary</span>
      <span class="chip undet"><b>${paperwork.undetermined.size}</b> cannot tell yet</span>
    </div>
    <h2>Required by law</h2>
    $required
    <h2>Commercial practice</h2>
    $customary
    <h2>Cannot tell yet</h2>
    $undetermined
    <h2>${esc(profile.category.label)} — what this dock meets</h2>
    $routine
    $notes
    <h2>Open verification gaps</h2>
    $gaps
  </section>"""
    }
}
