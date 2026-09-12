import { useState } from 'react'
import { useParams } from 'react-router-dom'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api, inr, when } from '../api'
import { Empty, ErrorBox, FinancePill, Loading, Pill, Ring, RiskPill, StatusPill } from '../components/Bits'
import type { MatchLine, ShipmentReport } from '../types'

type Tab = 'overview' | 'match' | 'documents' | 'packages' | 'finance' | 'scans' | 'pod'

/**
 * §6.3 Documents & Match — the per-shipment workspace.
 *
 * The tabs follow the chain of reasoning the platform actually makes: what was
 * ordered, what arrived, what the match says, and therefore what the money does.
 */
export default function ShipmentDetail() {
  const { ref = '' } = useParams()
  const [tab, setTab] = useState<Tab>('overview')
  const qc = useQueryClient()

  const report = useQuery({ queryKey: ['report', ref], queryFn: () => api.report(ref) })
  const recon = useQuery({
    queryKey: ['recon', ref],
    queryFn: () => api.reconciliation(ref).catch(() => null),
  })

  const reconcile = useMutation({
    mutationFn: () => api.reconcile(ref),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['recon', ref] })
      qc.invalidateQueries({ queryKey: ['report', ref] })
    },
  })
  const runAgent = useMutation({
    mutationFn: () => api.agentRun(ref),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['report', ref] })
      qc.invalidateQueries({ queryKey: ['recon', ref] })
    },
  })

  if (report.isLoading) return <Loading what={`Loading ${ref}`} />
  if (report.error) return <ErrorBox error={report.error} />
  if (!report.data) return <Empty>Unknown shipment.</Empty>

  const r = report.data
  const s = r.shipment

  return (
    <>
      <div className="page-head">
        <div>
          <h1 className="mono">{ref}</h1>
          <p>
            {s.supplier ?? '—'} → {s.buyer ?? '—'} · {s.vehicle ?? 'no vehicle'} ·{' '}
            {s.origin ?? '—'} → {s.destination ?? '—'}
          </p>
        </div>
        <div style={{ display: 'flex', gap: 8, alignItems: 'center' }}>
          <StatusPill status={s.status} />
          <button className="small" onClick={() => reconcile.mutate()} disabled={reconcile.isPending}>
            {reconcile.isPending ? 'Matching…' : 'Re-run four-way match'}
          </button>
          <button className="small" onClick={() => runAgent.mutate()} disabled={runAgent.isPending}>
            {runAgent.isPending ? 'Running…' : 'Run agent'}
          </button>
        </div>
      </div>

      {r.finance?.status === 'HELD' && (
        <div className="banner held">
          <b>Payment held — {inr(r.finance.heldValue)}</b> of {inr(r.finance.orderValue)}.
          {recon.data?.mismatches.length ? ' ' + recon.data.mismatches.map((m) => m.detail).join('; ') + '.' : ''}
        </div>
      )}
      {!r.complete && s.status !== 'OPEN' && (
        <div className="banner warn">
          Load incomplete: {r.accounted}/{r.expected_count} cartons accounted
          {r.missing.length > 0 && `, ${r.missing.length} missing`}
          {r.extra.length > 0 && `, ${r.extra.length} unlisted`}. Dispatch is gated until this resolves.
        </div>
      )}

      <div className="grid stats" style={{ marginBottom: 18 }}>
        <div className="stat" style={{ display: 'flex', gap: 12, alignItems: 'center' }}>
          <Ring done={r.accounted} total={r.expected_count} />
          <div>
            <div className="label">Cartons</div>
            <div className="value" style={{ fontSize: 18 }}>{r.accounted}/{r.expected_count}</div>
          </div>
        </div>
        <div className="stat">
          <div className="label">Inner boxes</div>
          <div className="value" style={{ fontSize: 18 }}>
            {r.inner_units > 0 ? `${r.inner_verified}/${r.inner_units}` : '—'}
          </div>
          <div className="note">verified inside masters</div>
        </div>
        <div className="stat">
          <div className="label">Risk</div>
          <div className="value" style={{ fontSize: 18 }}>
            <RiskPill score={r.risk?.score} band={r.risk?.band} />
          </div>
        </div>
        <div className="stat">
          <div className="label">Finance</div>
          <div className="value" style={{ fontSize: 18 }}>
            <FinancePill status={r.finance?.status} />
          </div>
          <div className="note">{inr(r.finance?.orderValue)} order value</div>
        </div>
      </div>

      <div className="tabs">
        {(['overview', 'match', 'documents', 'packages', 'finance', 'scans', 'pod'] as Tab[]).map((t) => (
          <button key={t} className={tab === t ? 'active' : ''} onClick={() => setTab(t)}>
            {t === 'match' ? 'Four-way match' : t === 'pod' ? 'Proof of delivery'
              : t[0].toUpperCase() + t.slice(1)}
          </button>
        ))}
      </div>

      {tab === 'overview' && <Overview refName={ref} report={r} />}
      {tab === 'match' && <Match recon={recon.data ?? null} />}
      {tab === 'documents' && <Documents refName={ref} />}
      {tab === 'packages' && <Packages refName={ref} />}
      {tab === 'finance' && <Finance refName={ref} />}
      {tab === 'scans' && <Scans refName={ref} />}
      {tab === 'pod' && <Pod refName={ref} />}
    </>
  )
}

function Overview({ refName, report }: { refName: string; report: ShipmentReport }) {
  const explain = useQuery({ queryKey: ['explain', refName], queryFn: () => api.agentExplain(refName) })
  return (
    <div className="grid two">
      <div className="card">
        <h2>Why this shipment is in its current state</h2>
        <p className="sub">The agent's plain answer, assembled from engine output.</p>
        <pre className="doc">{explain.data ?? '…'}</pre>
      </div>
      <div className="card">
        <h2>Risk factors</h2>
        <p className="sub">Every score shows what produced it — a number nobody can
           explain is a number operations will not act on.</p>
        {report.risk?.factors.length ? report.risk.factors.map((f, i) => (
          <div className="factor" key={i}>
            <span className="w">+{f.weight}</span>
            <span className="n">{f.factor}</span>
            <span className="d">{f.detail}</span>
          </div>
        )) : <Empty>No risk score computed yet.</Empty>}
      </div>
      {(report.missing.length > 0 || report.extra.length > 0 || report.flagged.length > 0) && (
        <div className="card">
          <h2>Exceptions by package</h2>
          {report.missing.length > 0 && (
            <p className="tiny"><b>Missing:</b> <span className="mono">{report.missing.join(', ')}</span></p>
          )}
          {report.flagged.length > 0 && (
            <p className="tiny"><b>Flagged:</b> <span className="mono">{report.flagged.join(', ')}</span></p>
          )}
          {report.extra.length > 0 && (
            <p className="tiny"><b>Unlisted (scanned but not registered):</b>{' '}
              <span className="mono">{report.extra.join(', ')}</span></p>
          )}
        </div>
      )}
      <div className="card">
        <h2>Open discrepancies</h2>
        {report.discrepancies.length ? (
          <table>
            <tbody>
              {report.discrepancies.map((d) => (
                <tr key={d.id}>
                  <td><Pill tone={d.severity === 'HIGH' ? 'bad' : 'warn'}>{d.severity}</Pill></td>
                  <td><b className="tiny">{d.kind}</b>
                    <div className="tiny muted mono">{d.packageCode}</div></td>
                  <td className="tiny muted">{when(d.detectedAt)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        ) : <Empty>None open.</Empty>}
      </div>
    </div>
  )
}

/** The four-way matrix — ordered ↔ invoiced ↔ declared ↔ physical, one row per line. */
function Match({ recon }: { recon: Awaited<ReturnType<typeof api.reconciliation>> | null }) {
  if (!recon) return <Empty>No reconciliation run yet — use “Re-run four-way match”.</Empty>

  const cell = (line: MatchLine, key: keyof MatchLine, compare?: number | null) => {
    const v = line[key] as number | undefined
    if (v === undefined || v === null) return <td className="num muted">—</td>
    const off = compare !== undefined && compare !== null && v !== compare
    return (
      <td className="num" style={off ? { color: 'var(--bad)', fontWeight: 700 } : undefined}>
        {v.toLocaleString('en-IN')}
      </td>
    )
  }

  return (
    <>
      <div className="card">
        <h2>Four-way match — {recon.status}</h2>
        <p className="sub">
          The PO is the anchor: it is the only document the buyer wrote, so it defines
          what was authorised. Run {when(recon.created_at)} by {recon.run_by}.
        </p>
        <div className="table-wrap">
          <table>
            <thead>
              <tr>
                <th>Line</th><th>SKU / description</th>
                <th className="num">Ordered</th><th className="num">Invoiced</th>
                <th className="num">Declared</th><th className="num">Physical</th>
                <th className="num">Rate</th>
              </tr>
            </thead>
            <tbody>
              {recon.matrix.lines.map((l) => (
                <tr key={l.line_no}>
                  <td>{l.line_no}</td>
                  <td>
                    <b className="mono tiny">{l.sku}</b>
                    <div className="tiny muted">{l.description}</div>
                  </td>
                  {cell(l, 'ordered_qty')}
                  {cell(l, 'invoiced_qty', l.ordered_qty)}
                  {cell(l, 'declared_qty', l.invoiced_qty)}
                  {cell(l, 'physical_qty', l.invoiced_qty)}
                  <td className="num">{inr(l.rate)}</td>
                </tr>
              ))}
            </tbody>
            <tfoot>
              <tr style={{ fontWeight: 700 }}>
                <td colSpan={2}>Totals</td>
                <td className="num">{recon.matrix.four_way.ordered?.toLocaleString('en-IN')}</td>
                <td className="num">{recon.matrix.four_way.invoiced?.toLocaleString('en-IN')}</td>
                <td className="num">{recon.matrix.four_way.declared?.toLocaleString('en-IN')}</td>
                <td className="num">{recon.matrix.four_way.physical?.toLocaleString('en-IN')}</td>
                <td></td>
              </tr>
            </tfoot>
          </table>
        </div>
      </div>

      <div className="card">
        <h2>Mismatches — {inr(recon.held_value)} held</h2>
        <p className="sub">Each one names the two corners that disagreed and the exact
           value it withholds. Nothing is held without a number behind it.</p>
        {recon.mismatches.length === 0 ? <Empty>Everything reconciles.</Empty> : (
          <div className="table-wrap">
            <table>
              <thead>
                <tr><th>Code</th><th>Corners</th><th>Line</th><th>Detail</th><th className="num">Held</th></tr>
              </thead>
              <tbody>
                {recon.mismatches.map((m, i) => (
                  <tr key={i}>
                    <td><Pill tone={m.delta_value > 0 ? 'bad' : 'warn'}>{m.code}</Pill></td>
                    <td className="tiny muted">{m.pair.replace(/_/g, ' ').toLowerCase()}</td>
                    <td className="tiny">{m.line_no ? `L${m.line_no} ${m.sku ?? ''}` : '—'}</td>
                    <td className="tiny">{m.detail}</td>
                    <td className="num">{m.delta_value > 0 ? inr(m.delta_value) : '—'}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </div>
    </>
  )
}

function Documents({ refName }: { refName: string }) {
  const { data, isLoading } = useQuery({ queryKey: ['docs', refName], queryFn: () => api.documents(refName) })
  if (isLoading) return <Loading />
  if (!data?.length) return <Empty>No documents attached.</Empty>
  return (
    <div className="grid two">
      {data.map((d) => (
        <div className="card" key={d.id ?? d.doc_no}>
          <h2>{d.kind} · <span className="mono">{d.doc_no}</span></h2>
          <p className="sub">
            {d.doc_date} · {d.fact.seller.name} → {d.fact.buyer.name}
            {' · '}read by {d.read_by}{d.confidence ? ` (${(d.confidence * 100).toFixed(1)}% confident)` : ''}
          </p>
          <table>
            <thead>
              <tr><th>#</th><th>Description</th><th className="num">Qty</th>
                <th className="num">Rate</th><th className="num">Amount</th></tr>
            </thead>
            <tbody>
              {d.fact.lines.map((l) => (
                <tr key={l.line_no}>
                  <td>{l.line_no}</td>
                  <td><div className="tiny">{l.description}</div>
                    <div className="tiny muted mono">{l.sku} · HSN {l.hsn}</div></td>
                  <td className="num">{l.qty}</td>
                  <td className="num">{inr(l.rate)}</td>
                  <td className="num">{inr(l.amount)}</td>
                </tr>
              ))}
            </tbody>
            <tfoot>
              <tr style={{ fontWeight: 700 }}>
                <td colSpan={4}>Taxable value</td>
                <td className="num">{inr(d.fact.totals.taxable_value)}</td>
              </tr>
            </tfoot>
          </table>
        </div>
      ))}
    </div>
  )
}

function Packages({ refName }: { refName: string }) {
  const { data, isLoading } = useQuery({ queryKey: ['packages', refName], queryFn: () => api.packages(refName) })
  const [expanded, setExpanded] = useState<string | null>(null)
  if (isLoading) return <Loading what="Loading packages" />
  if (!data?.length) return <Empty>No packages registered.</Empty>

  const masters = data.filter((p) => !p.parentCode)
  const childrenOf = (code: string) => data.filter((p) => p.parentCode === code)

  return (
    <div className="card">
      <h2>{masters.length} top-level packages · {data.length - masters.length} inner boxes</h2>
      <p className="sub">Click a master to open it — the nesting is in the database,
         so a carton can be repacked without reprinting a single inner label.</p>
      <div className="table-wrap">
        <table>
          <thead>
            <tr><th>Code</th><th>Kind</th><th>Contents</th><th className="num">Qty</th>
              <th>Status</th><th>Inside</th></tr>
          </thead>
          <tbody>
            {masters.slice(0, 200).map((p) => {
              const kids = childrenOf(p.packageCode)
              const verified = kids.filter((k) => k.status === 'RECEIVED').length
              const isOpen = expanded === p.packageCode
              return [
                <tr key={p.packageCode} onClick={() => setExpanded(isOpen ? null : p.packageCode)}
                    style={{ cursor: kids.length ? 'pointer' : undefined }}>
                  <td className="mono tiny">{p.packageCode}</td>
                  <td className="tiny">{p.kind}</td>
                  <td className="tiny">{p.contents}</td>
                  <td className="num">{p.qty}</td>
                  <td>
                    <Pill tone={p.status === 'FLAGGED' ? 'bad' : p.status === 'MISSING' ? 'bad'
                      : p.status === 'RECEIVED' ? 'ok' : 'dim'}>{p.status}</Pill>
                  </td>
                  <td className="tiny">
                    {kids.length > 0 && (
                      <span style={{ color: verified === kids.length ? 'var(--ok)' : 'var(--bad)' }}>
                        {verified}/{kids.length} verified {isOpen ? '▾' : '▸'}
                      </span>
                    )}
                  </td>
                </tr>,
                ...(isOpen ? kids.map((k) => (
                  <tr key={k.packageCode} style={{ background: 'var(--surface-2)' }}>
                    <td className="mono tiny" style={{ paddingLeft: 28 }}>↳ {k.packageCode}</td>
                    <td className="tiny">{k.kind}</td>
                    <td className="tiny">{k.contents}</td>
                    <td className="num">{k.qty}</td>
                    <td><Pill tone={k.status === 'MISSING' ? 'bad' : k.status === 'RECEIVED' ? 'ok' : 'dim'}>
                      {k.status}</Pill></td>
                    <td></td>
                  </tr>
                )) : []),
              ]
            })}
          </tbody>
        </table>
      </div>
      {masters.length > 200 && <p className="tiny muted">Showing first 200 of {masters.length}.</p>}
    </div>
  )
}

function Finance({ refName }: { refName: string }) {
  const { data } = useQuery({ queryKey: ['payments', refName], queryFn: () => api.payments(refName) })
  const finance = useQuery({ queryKey: ['finance', refName], queryFn: () => api.finance(refName).catch(() => null) })
  const f = finance.data
  return (
    <>
      {f && (
        <div className="grid stats" style={{ marginBottom: 16 }}>
          <div className="stat"><div className="label">Order value</div>
            <div className="value" style={{ fontSize: 18 }}>{inr(f.orderValue)}</div></div>
          <div className="stat"><div className="label">Released</div>
            <div className="value" style={{ fontSize: 18, color: 'var(--ok)' }}>{inr(f.releasedValue)}</div></div>
          <div className="stat"><div className="label">Held</div>
            <div className="value" style={{ fontSize: 18, color: f.heldValue ? 'var(--held)' : undefined }}>
              {inr(f.heldValue)}</div></div>
          <div className="stat"><div className="label">Terms</div>
            <div className="value" style={{ fontSize: 15 }}>
              {f.terms.on_verified_delivery_pct ?? '—'}% on delivery</div>
            <div className="note">balance {f.terms.balance ?? '—'}</div></div>
        </div>
      )}
      <div className="card">
        <h2>Payment events</h2>
        <p className="sub">Every entry is audit-chained to the evidence it was based on.</p>
        {!data?.length ? <Empty>No payment activity.</Empty> : (
          <table>
            <thead><tr><th>Event</th><th>Actor</th><th className="num">Amount</th><th>When</th></tr></thead>
            <tbody>
              {data.map((e, i) => (
                <tr key={i}>
                  <td><Pill tone={e.kind.includes('RELEASE') ? 'ok' : e.kind === 'HOLD' ? 'held' : 'dim'}>
                    {e.kind}</Pill></td>
                  <td className="tiny">{e.actor}</td>
                  <td className="num">{e.amount ? inr(Number(e.amount)) : '—'}</td>
                  <td className="tiny muted">{when(e.at)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </div>
    </>
  )
}

function Scans({ refName }: { refName: string }) {
  const { data, isLoading } = useQuery({
    queryKey: ['scans', refName],
    queryFn: () => api.scans(refName, 200),
    refetchInterval: 10_000,
  })
  if (isLoading) return <Loading what="Loading scans" />
  if (!data?.length) return <Empty>No scans yet.</Empty>
  return (
    <div className="card">
      <h2>{data.length} most recent scans</h2>
      <div className="table-wrap">
        <table>
          <thead><tr><th>Result</th><th>Package</th><th>Kind</th><th>Reasons</th><th>When</th></tr></thead>
          <tbody>
            {data.map((s) => (
              <tr key={s.client_event_id}>
                <td><Pill tone={s.result === 'VERIFIED' ? 'ok' : s.result === 'REJECTED' ? 'bad' : 'warn'}>
                  {s.result}</Pill></td>
                <td className="mono tiny">{s.package_code}</td>
                <td className="tiny">{s.kind}</td>
                <td className="tiny">{s.reasons.join(', ') || '—'}</td>
                <td className="tiny muted">{when(s.client_ts)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  )
}

function Pod({ refName }: { refName: string }) {
  const { data, isLoading } = useQuery({ queryKey: ['pod', refName], queryFn: () => api.pod(refName) })
  const [text, setText] = useState<string | null>(null)
  if (isLoading) return <Loading />
  if (!data?.length) return <Empty>No proof-of-delivery certificate issued yet.</Empty>
  return (
    <>
      {data.map((c) => (
        <div className="card" key={c.id}>
          <h2>Certificate <span className="mono tiny">{c.id}</span></h2>
          <p className="sub">Issued {when(c.issued_at)} · signed with {c.key_id}</p>
          <div className="grid two">
            <div>
              <b className="tiny">Match at delivery</b>
              {Object.entries(c.match_result).map(([k, v]) => (
                <div key={k} className="tiny"><span className="muted">{k}:</span> {v}</div>
              ))}
            </div>
            <div>
              <b className="tiny">Evidence (hashed on the device before upload)</b>
              {Object.entries(c.evidence_hashes).slice(0, 6).map(([k, v]) => (
                <div key={k} className="tiny mono">{k}: {String(v).slice(0, 28)}…</div>
              ))}
            </div>
          </div>
          <button className="small" style={{ marginTop: 10 }}
            onClick={async () => setText(await api.podRender(c.id))}>View certificate</button>
          {text && <pre className="doc" style={{ marginTop: 10 }}>{text}</pre>}
        </div>
      ))}
    </>
  )
}
