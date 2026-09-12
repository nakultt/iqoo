import { useState } from 'react'
import { Link } from 'react-router-dom'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api, when } from '../api'
import { Empty, ErrorBox, Loading, Pill } from '../components/Bits'

/** §6.3 discrepancy queue — flagged events, resolved or escalated, every
 *  decision landing in the audit chain. */
export default function Discrepancies() {
  const [showResolved, setShowResolved] = useState(false)
  const qc = useQueryClient()
  const { data, isLoading, error } = useQuery({
    queryKey: ['discrepancies', showResolved],
    queryFn: () => api.discrepancies(undefined, !showResolved),
  })

  const resolve = useMutation({
    mutationFn: ({ id, resolution, note }: { id: string; resolution: string; note: string }) =>
      api.resolveDiscrepancy(id, resolution, note),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['discrepancies'] }),
  })

  if (isLoading) return <Loading what="Loading discrepancies" />
  if (error) return <ErrorBox error={error} />

  return (
    <>
      <div className="page-head">
        <div>
          <h1>Discrepancy queue</h1>
          <p>Everything the check stack flagged. Resolving one is itself an audited
             decision — an override is recorded, never silently applied.</p>
        </div>
        <button className="small" onClick={() => setShowResolved(!showResolved)}>
          {showResolved ? 'Show open only' : 'Include resolved'}
        </button>
      </div>

      {!data?.length ? <Empty>Nothing flagged.</Empty> : (
        <div className="card">
          <div className="table-wrap">
            <table>
              <thead>
                <tr>
                  <th>Severity</th><th>Code</th><th>Shipment</th><th>Package</th>
                  <th>Detail</th><th>Detected</th><th></th>
                </tr>
              </thead>
              <tbody>
                {data.map((d) => (
                  <tr key={d.id}>
                    <td>
                      <Pill tone={d.severity === 'HIGH' ? 'bad' : d.severity === 'MEDIUM' ? 'warn' : 'dim'}>
                        {d.severity}
                      </Pill>
                    </td>
                    <td><b className="tiny">{d.kind}</b></td>
                    <td><Link to={`/shipments/${d.shipmentRef}`} className="mono tiny">{d.shipmentRef}</Link></td>
                    <td className="mono tiny">{d.packageCode ?? '—'}</td>
                    <td style={{ maxWidth: 380 }}>
                      {Object.entries(d.detail).slice(0, 4).map(([k, v]) => (
                        <div key={k} className="tiny">
                          <span className="muted">{k}:</span> {String(v).slice(0, 80)}
                        </div>
                      ))}
                    </td>
                    <td className="tiny muted">{when(d.detectedAt)}</td>
                    <td>
                      {d.resolvedAt ? (
                        <Pill tone="ok">{d.resolution}</Pill>
                      ) : (
                        <div style={{ display: 'flex', gap: 4 }}>
                          <button className="small" disabled={resolve.isPending}
                            onClick={() => {
                              const note = prompt('Resolution note (recorded in the audit chain):')
                              if (note !== null) resolve.mutate({ id: d.id, resolution: 'RESOLVED', note })
                            }}>Resolve</button>
                          <button className="small" disabled={resolve.isPending}
                            onClick={() => {
                              const note = prompt('Why is this being overridden? (audited)')
                              if (note !== null) resolve.mutate({ id: d.id, resolution: 'OVERRIDDEN', note })
                            }}>Override</button>
                        </div>
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </div>
      )}
    </>
  )
}
