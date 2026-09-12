import { useState } from 'react'
import { Link } from 'react-router-dom'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api, inr } from '../api'
import { Empty, ErrorBox, Loading, Pill } from '../components/Bits'
import type { FinanceState } from '../types'

/**
 * §6.3 Commerce / Payments — the release-ready queue, the held queue, and the
 * maker-checker actions.
 *
 * The server enforces maker-checker; this page simply surfaces the refusal when
 * it happens, rather than trying to hide the button. Seeing "you requested this
 * release" is more useful than wondering why the button did nothing.
 */
export default function Payments() {
  const qc = useQueryClient()
  const [message, setMessage] = useState<{ tone: string; text: string } | null>(null)

  const held = useQuery({ queryKey: ['finance', 'HELD'], queryFn: () => api.financeQueue('HELD') })
  const verified = useQuery({ queryKey: ['finance', 'VERIFIED'], queryFn: () => api.financeQueue('VERIFIED') })
  const pending = useQuery({ queryKey: ['finance', 'RELEASE_PENDING'], queryFn: () => api.financeQueue('RELEASE_PENDING') })
  const released = useQuery({ queryKey: ['finance', 'RELEASED'], queryFn: () => api.financeQueue('RELEASED') })

  const refresh = () => {
    qc.invalidateQueries({ queryKey: ['finance'] })
    qc.invalidateQueries({ queryKey: ['shipments'] })
  }

  const act = useMutation({
    mutationFn: async ({ kind, ref, note }: { kind: string; ref: string; note?: string }) => {
      if (kind === 'release') return api.requestRelease(ref, undefined, note)
      if (kind === 'approve') return api.approveRelease(ref)
      return api.resolveHold(ref, note ?? '')
    },
    onSuccess: (res) => { setMessage({ tone: 'ok', text: res.message }); refresh() },
    onError: (e: { message?: string; detail?: string }) =>
      setMessage({ tone: 'bad', text: e.detail ?? e.message ?? 'Action refused' }),
  })

  if (held.isLoading) return <Loading what="Loading the payment queues" />
  if (held.error) return <ErrorBox error={held.error} />

  const totalHeld = (held.data ?? []).reduce((a, f) => a + f.heldValue, 0)
  const totalReleased = (released.data ?? []).reduce((a, f) => a + f.releasedValue, 0)

  return (
    <>
      <div className="page-head">
        <div>
          <h1>Payments</h1>
          <p>VeriTransit never moves money. It issues a signed certificate or hold
             notice that the payer's system acts on — these queues are where a human
             decides which.</p>
        </div>
      </div>

      {message && <div className={`banner ${message.tone}`}>{message.text}</div>}

      <div className="grid stats" style={{ marginBottom: 18 }}>
        <div className="stat"><div className="label">Held</div>
          <div className="value" style={{ color: 'var(--held)' }}>{inr(totalHeld)}</div>
          <div className="note">{held.data?.length ?? 0} shipments</div></div>
        <div className="stat"><div className="label">Awaiting a checker</div>
          <div className="value">{pending.data?.length ?? 0}</div>
          <div className="note">maker has requested release</div></div>
        <div className="stat"><div className="label">Release-ready</div>
          <div className="value">{verified.data?.length ?? 0}</div>
          <div className="note">verified, nothing held</div></div>
        <div className="stat"><div className="label">Released</div>
          <div className="value" style={{ color: 'var(--ok)' }}>{inr(totalReleased)}</div>
          <div className="note">certificates issued</div></div>
      </div>

      <Queue title="Awaiting a checker"
        sub="A second role must approve. The maker cannot approve their own request — the server refuses it."
        rows={pending.data} tone="warn"
        action={(f) => (
          <button className="small primary" disabled={act.isPending}
            onClick={() => act.mutate({ kind: 'approve', ref: f.shipmentRef })}>
            Approve release
          </button>
        )} />

      <Queue title="Held — mismatch must be resolved first"
        sub="The exact disputed delta is withheld; the rest of the value stays releasable."
        rows={held.data} tone="held"
        action={(f) => (
          <button className="small" disabled={act.isPending}
            onClick={() => {
              const note = prompt('How was this resolved? (credit note, recount, approve-as-is)')
              if (note) act.mutate({ kind: 'resolve', ref: f.shipmentRef, note })
            }}>
            Resolve hold
          </button>
        )} />

      <Queue title="Release-ready"
        sub="Four-way match is clean. A finance maker raises the request."
        rows={verified.data} tone="ok"
        action={(f) => (
          <button className="small primary" disabled={act.isPending}
            onClick={() => act.mutate({ kind: 'release', ref: f.shipmentRef, note: 'released from the payments console' })}>
            Request release
          </button>
        )} />

      <Queue title="Released" sub="Signed certificates delivered to the payer."
        rows={released.data} tone="dim" />
    </>
  )
}

function Queue({ title, sub, rows, tone, action }: {
  title: string; sub: string; rows?: FinanceState[]; tone: string
  action?: (f: FinanceState) => React.ReactNode
}) {
  return (
    <div className="card">
      <h2>{title} {rows && <Pill tone={tone}>{rows.length}</Pill>}</h2>
      <p className="sub">{sub}</p>
      {!rows?.length ? <Empty>Nothing here.</Empty> : (
        <div className="table-wrap">
          <table>
            <thead>
              <tr><th>Shipment</th><th className="num">Order value</th><th className="num">Held</th>
                <th className="num">Released</th><th>Terms</th><th></th></tr>
            </thead>
            <tbody>
              {rows.map((f) => (
                <tr key={f.shipmentRef}>
                  <td><Link to={`/shipments/${f.shipmentRef}`} className="mono">{f.shipmentRef}</Link></td>
                  <td className="num">{inr(f.orderValue)}</td>
                  <td className="num" style={{ color: f.heldValue ? 'var(--held)' : undefined }}>
                    {f.heldValue ? inr(f.heldValue) : '—'}</td>
                  <td className="num" style={{ color: f.releasedValue ? 'var(--ok)' : undefined }}>
                    {f.releasedValue ? inr(f.releasedValue) : '—'}</td>
                  <td className="tiny muted">
                    {f.terms.on_verified_delivery_pct ?? '—'}% on delivery, balance {f.terms.balance ?? '—'}</td>
                  <td>{action?.(f)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  )
}
