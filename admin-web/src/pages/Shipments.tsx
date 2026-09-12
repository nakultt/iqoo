import { useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { useMutation, useQueries, useQuery, useQueryClient } from '@tanstack/react-query'
import { api, inrShort } from '../api'
import { Empty, ErrorBox, FinancePill, Loading, Ring, RiskPill, StatusPill } from '../components/Bits'

/**
 * §6.3 Shipments — the "are all the packages there?" dashboard.
 *
 * Completeness, risk and finance sit on the same row deliberately: the whole
 * product claim is that those three are one question, and a supervisor should
 * never have to open three pages to see that a truck is short *and* the money
 * is held because of it.
 */
export default function Shipments() {
  const { data, isLoading, error } = useQuery({ queryKey: ['shipments'], queryFn: api.shipments })
  const [creating, setCreating] = useState(false)
  const navigate = useNavigate()
  const qc = useQueryClient()

  const create = useMutation({
    mutationFn: (body: Record<string, unknown>) => api.createShipment(body),
    onSuccess: (s) => {
      qc.invalidateQueries({ queryKey: ['shipments'] })
      setCreating(false)
      navigate(`/shipments/${s.ref}`)
    },
  })

  // Each row's counts come from the report endpoint, which is also what the
  // device and the bot read — one computation of "accounted", not three.
  const reports = useQueries({
    queries: (data ?? []).map((s) => ({
      queryKey: ['report', s.ref],
      queryFn: () => api.report(s.ref),
      staleTime: 8_000,
    })),
  })

  if (isLoading) return <Loading what="Loading shipments" />
  if (error) return <ErrorBox error={error} />

  const byRef = new Map(reports.filter((r) => r.data).map((r) => [r.data!.shipment.ref, r.data!]))
  const totalHeld = [...byRef.values()].reduce((a, r) => a + (r.finance?.heldValue ?? 0), 0)
  const flagged = data?.filter((s) => s.status === 'FLAGGED').length ?? 0

  return (
    <>
      <div className="page-head">
        <div>
          <h1>Shipments</h1>
          <p>Every dispatch, its completeness, its risk band and what its paperwork
             is doing to the money.</p>
        </div>
        <button className="primary" onClick={() => setCreating((v) => !v)}>
          {creating ? 'Cancel' : '+ New shipment'}
        </button>
      </div>

      {creating && (
        <NewShipmentForm pending={create.isPending} error={create.error}
          onSubmit={(body) => create.mutate(body)} />
      )}

      {data && (
        <div className="grid stats" style={{ marginBottom: 18 }}>
          <div className="stat">
            <div className="label">Shipments</div>
            <div className="value">{data.length}</div>
            <div className="note">{data.filter((s) => s.status === 'LOADING').length} loading now</div>
          </div>
          <div className="stat">
            <div className="label">Flagged</div>
            <div className="value" style={{ color: flagged ? 'var(--bad)' : undefined }}>{flagged}</div>
            <div className="note">unresolved verification issues</div>
          </div>
          <div className="stat">
            <div className="label">Payment held</div>
            <div className="value" style={{ color: totalHeld ? 'var(--held)' : undefined }}>
              {inrShort(totalHeld)}
            </div>
            <div className="note">across {[...byRef.values()].filter((r) => (r.finance?.heldValue ?? 0) > 0).length} shipments</div>
          </div>
          <div className="stat">
            <div className="label">Cartons tracked</div>
            <div className="value">{data.reduce((a, s) => a + s.expectedCount, 0)}</div>
            <div className="note">top-level packages</div>
          </div>
        </div>
      )}

      {!data?.length ? (
        <Empty>No shipments yet — create the first one above.</Empty>
      ) : (
        <div className="card">
          <div className="table-wrap">
            <table>
              <thead>
                <tr>
                  <th></th>
                  <th>Shipment</th>
                  <th>Status</th>
                  <th>Supplier → Buyer</th>
                  <th className="num">Cartons</th>
                  <th className="num">Inners</th>
                  <th>Risk</th>
                  <th>Finance</th>
                  <th className="num">Order</th>
                  <th className="num">Issues</th>
                </tr>
              </thead>
              <tbody>
                {data.map((s) => {
                  const r = byRef.get(s.ref)
                  return (
                    <tr key={s.ref}>
                      <td><Ring done={r?.accounted ?? 0} total={r?.expected_count ?? s.expectedCount} /></td>
                      <td>
                        <Link to={`/shipments/${s.ref}`} className="mono"><b>{s.ref}</b></Link>
                        <div className="tiny muted">{s.vehicle ?? 'no vehicle'}</div>
                      </td>
                      <td><StatusPill status={s.status} /></td>
                      <td>
                        <div style={{ fontSize: 12 }}>{s.supplier ?? '—'}</div>
                        <div className="tiny muted">→ {s.buyer ?? '—'}</div>
                      </td>
                      <td className="num">
                        {r ? `${r.accounted}/${r.expected_count}` : s.expectedCount}
                        {r && r.missing.length > 0 && (
                          <div className="tiny" style={{ color: 'var(--bad)' }}>{r.missing.length} missing</div>
                        )}
                      </td>
                      <td className="num">
                        {r && r.inner_units > 0 ? `${r.inner_verified}/${r.inner_units}` : '—'}
                      </td>
                      <td><RiskPill score={r?.risk?.score} band={r?.risk?.band} /></td>
                      <td><FinancePill status={r?.finance?.status} held={r?.finance?.heldValue} /></td>
                      <td className="num">{inrShort(r?.finance?.orderValue)}</td>
                      <td className="num">
                        {r && r.discrepancies.length > 0
                          ? <span className="pill bad">{r.discrepancies.length}</span>
                          : <span className="muted">—</span>}
                      </td>
                    </tr>
                  )
                })}
              </tbody>
            </table>
          </div>
        </div>
      )}
    </>
  )
}

/**
 * The sender-side step 0. Parties are matched by GSTIN first, then name —
 * typing a known party name is enough for the demo; a GSTIN pins it exactly.
 */
function NewShipmentForm({ pending, error, onSubmit }: {
  pending: boolean
  error: unknown
  onSubmit: (body: Record<string, unknown>) => void
}) {
  const [f, setF] = useState({
    supplier: '', buyer: '', transporter: '', vehicle: '', origin_site: '', dest_site: '', po_no: '',
  })
  const set = (k: keyof typeof f) => (e: { target: { value: string } }) =>
    setF((v) => ({ ...v, [k]: e.target.value }))
  const text = (k: keyof typeof f, label: string, ph: string) => (
    <div className="field">
      <label>{label}</label>
      <input value={f[k]} onChange={set(k)} placeholder={ph} />
    </div>
  )

  return (
    <div className="card" style={{ marginBottom: 18 }}>
      <h2>New shipment</h2>
      <p className="sub">The shipment starts OPEN — paperwork and cartons come
         next, from the detail page and the pack station.</p>
      {error ? <ErrorBox error={error} /> : null}
      <div className="row">
        {text('supplier', 'Supplier', 'Kumar Electronics Pvt Ltd')}
        {text('buyer', 'Buyer', 'Zen Digital Retail Ltd')}
        {text('transporter', 'Transporter', 'SafeMove Logistics')}
        {text('vehicle', 'Vehicle', 'KA-01-AB-1234')}
      </div>
      <div className="row">
        {text('origin_site', 'Origin site', 'Pune DC')}
        {text('dest_site', 'Destination site', 'Bengaluru Hub')}
        {text('po_no', 'PO number', 'PO-2026-0142')}
        <div className="field">
          <label>&nbsp;</label>
          <button className="primary" disabled={pending}
            onClick={() =>
              onSubmit(Object.fromEntries(Object.entries(f).filter(([, v]) => v.trim() !== '')))}>
            {pending ? 'Creating…' : 'Create shipment'}
          </button>
        </div>
      </div>
    </div>
  )
}
