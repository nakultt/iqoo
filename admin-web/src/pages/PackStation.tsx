import { useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api } from '../api'
import { Empty, Loading, Pill } from '../components/Bits'
import type { IssuedLabel } from '../types'

/**
 * §6.3 Pack station + label studio — where boxes become data.
 *
 * The ordering shown here is the product requirement from §4.5: the database
 * rows are written and the labels signed *before* anything prints. A printed
 * label whose row failed to commit is a physical object the platform does not
 * know about, and no retry can fix that.
 */
export default function PackStation() {
  const qc = useQueryClient()
  const shipments = useQuery({ queryKey: ['shipments'], queryFn: api.shipments })
  const [ref, setRef] = useState('')
  const [labels, setLabels] = useState<IssuedLabel[]>([])
  const [error, setError] = useState<string | null>(null)

  const [line, setLine] = useState({
    po_line_no: 1, sku: '', contents: '', hsn: '',
    masters: 10, units_per_master: 10, label_inners: true,
  })
  const [childCodes, setChildCodes] = useState('')

  const issue = useMutation({
    mutationFn: () => api.issueLabels(ref, { lines: [line] }),
    onSuccess: (res) => {
      setLabels(res.labels); setError(null)
      qc.invalidateQueries({ queryKey: ['shipments'] })
      qc.invalidateQueries({ queryKey: ['packages', ref] })
    },
    onError: (e: { detail?: string; message?: string }) => setError(e.detail ?? e.message ?? 'Failed'),
  })

  const close = useMutation({
    mutationFn: () => api.closeMaster({
      shipment_ref: ref,
      child_codes: childCodes.split(/[\s,]+/).map((c) => c.trim()).filter(Boolean),
    }),
    onSuccess: (res) => {
      setLabels([res]); setError(null); setChildCodes('')
      qc.invalidateQueries({ queryKey: ['packages', ref] })
    },
    onError: (e: { detail?: string; message?: string }) => setError(e.detail ?? e.message ?? 'Failed'),
  })

  if (shipments.isLoading) return <Loading />

  const open = (shipments.data ?? []).filter((s) => s.status === 'OPEN' || s.status === 'LOADING')

  return (
    <>
      <div className="page-head">
        <div>
          <h1>Pack station</h1>
          <p>Register cartons and issue signed labels. Every row lands in the database
             before a label prints — never the other way round.</p>
        </div>
      </div>

      {error && <div className="banner bad">{error}</div>}

      <div className="card">
        <h2>1 · Choose the shipment being packed</h2>
        <div className="field">
          <label>Shipment</label>
          <select value={ref} onChange={(e) => { setRef(e.target.value); setLabels([]) }}>
            <option value="">Select…</option>
            {open.map((s) => (
              <option key={s.ref} value={s.ref}>
                {s.ref} — {s.supplier ?? '—'} → {s.buyer ?? '—'} ({s.status})
              </option>
            ))}
          </select>
        </div>
        {open.length === 0 && <p className="tiny muted">No open shipments. Create one from the Shipments page.</p>}
      </div>

      <div className="grid two">
        <div className="card">
          <h2>2 · Bulk issue</h2>
          <p className="sub">The §4.5 bulk variant: “300 units ÷ 10 per master = 30 masters”,
             all rows and signatures in one transaction.</p>
          <div className="row">
            <div className="field"><label>PO line</label>
              <input type="number" value={line.po_line_no}
                onChange={(e) => setLine({ ...line, po_line_no: +e.target.value })} /></div>
            <div className="field"><label>SKU</label>
              <input value={line.sku} placeholder="KE-SP-A15"
                onChange={(e) => setLine({ ...line, sku: e.target.value })} /></div>
            <div className="field"><label>HSN</label>
              <input value={line.hsn} placeholder="85171300"
                onChange={(e) => setLine({ ...line, hsn: e.target.value })} /></div>
          </div>
          <div className="field"><label>Contents (one inner box)</label>
            <input value={line.contents} placeholder="Smartphone A15 6.1in 128GB"
              onChange={(e) => setLine({ ...line, contents: e.target.value })} /></div>
          <div className="row">
            <div className="field"><label>Master cartons</label>
              <input type="number" value={line.masters}
                onChange={(e) => setLine({ ...line, masters: +e.target.value })} /></div>
            <div className="field"><label>Inner boxes per master</label>
              <input type="number" value={line.units_per_master}
                onChange={(e) => setLine({ ...line, units_per_master: +e.target.value })} /></div>
          </div>
          <div className="field">
            <label style={{ textTransform: 'none', letterSpacing: 0 }}>
              <input type="checkbox" style={{ width: 'auto', marginRight: 6 }}
                checked={line.label_inners}
                onChange={(e) => setLine({ ...line, label_inners: e.target.checked })} />
              Label each inner box individually
            </label>
            <p className="tiny muted" style={{ margin: '2px 0 0' }}>
              Unchecked, the inners are supplier-preprinted: the master declares the
              count and the receiver verifies by counting (§4.4).
            </p>
          </div>
          <button className="primary" disabled={!ref || !line.contents || issue.isPending}
            onClick={() => issue.mutate()}>
            {issue.isPending ? 'Writing rows and signing…' : `Issue ${line.masters * (line.label_inners ? line.units_per_master + 1 : 1)} labels`}
          </button>
        </div>

        <div className="card">
          <h2>3 · Close a master</h2>
          <p className="sub">The packer scans the inner boxes into a carton and closes it.
             The master declares exactly what was scanned in — it cannot claim ten when
             nine went in.</p>
          <div className="field">
            <label>Inner box codes (paste or scan, any separator)</label>
            <textarea rows={5} value={childCodes} className="mono"
              placeholder="VT-P-8F3K2M9D VT-P-QNS2JPK9 …"
              onChange={(e) => setChildCodes(e.target.value)} />
          </div>
          <p className="tiny muted">
            {childCodes.split(/[\s,]+/).filter(Boolean).length} codes scanned in
          </p>
          <button className="primary" disabled={!ref || !childCodes.trim() || close.isPending}
            onClick={() => close.mutate()}>
            {close.isPending ? 'Closing…' : 'Close master'}
          </button>
        </div>
      </div>

      {labels.length > 0 && (
        <div className="card">
          <h2>Labels issued — ready to print</h2>
          <p className="sub">Each QR carries the signed token below. The signature is what
             a phone verifies offline; the QR itself proves nothing.</p>
          <div className="table-wrap">
            <table>
              <thead><tr><th>Code</th><th>Kind</th><th>Contents</th><th className="num">Qty</th><th>Signed token</th></tr></thead>
              <tbody>
                {labels.slice(0, 60).map((l) => (
                  <tr key={l.package_code}>
                    <td className="mono tiny"><b>{l.package_code}</b></td>
                    <td><Pill tone={l.kind === 'MASTER' ? 'accent' : 'dim'}>{l.kind}</Pill></td>
                    <td className="tiny">{l.contents}</td>
                    <td className="num">{l.qty}</td>
                    <td className="mono tiny" style={{ maxWidth: 340, wordBreak: 'break-all' }}>
                      {l.payload}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          {labels.length > 60 && <p className="tiny muted">Showing 60 of {labels.length}.</p>}
          <button className="small" style={{ marginTop: 10 }} onClick={() => window.print()}>
            Print label sheet
          </button>
        </div>
      )}

      {ref && <Registered refName={ref} />}
    </>
  )
}

function Registered({ refName }: { refName: string }) {
  const { data } = useQuery({ queryKey: ['packages', refName], queryFn: () => api.packages(refName) })
  if (!data) return null
  const masters = data.filter((p) => !p.parentCode)
  return (
    <div className="card">
      <h2>Already registered on {refName}</h2>
      {!data.length ? <Empty>Nothing packed yet.</Empty> : (
        <p className="tiny muted">
          {masters.length} top-level cartons · {data.length - masters.length} inner boxes ·{' '}
          {data.filter((p) => p.status === 'PRINTED').length} awaiting load
        </p>
      )}
    </div>
  )
}
