import { Link } from 'react-router-dom'
import { useQueries, useQuery } from '@tanstack/react-query'
import { api } from '../api'
import { Empty, Loading, RiskPill } from '../components/Bits'

/** §6.3 Risk console — scores with their factor drilldown always attached. */
export default function Risk() {
  const shipments = useQuery({ queryKey: ['shipments'], queryFn: api.shipments })
  const parties = useQuery({ queryKey: ['risk', 'parties'], queryFn: api.riskParties })

  const scores = useQueries({
    queries: (shipments.data ?? []).map((s) => ({
      queryKey: ['risk', s.ref],
      queryFn: () => api.risk(s.ref).catch(() => null),
    })),
  })

  if (shipments.isLoading) return <Loading what="Loading risk scores" />

  const rows = scores.map((q) => q.data).filter(Boolean).sort((a, b) => b!.score - a!.score)

  return (
    <>
      <div className="page-head">
        <div>
          <h1>Risk console</h1>
          <p>Bands drive friction: LOW takes a master scan, MEDIUM opens every carton,
             HIGH also holds payment until a supervisor signs off. Every score shows
             what produced it.</p>
        </div>
      </div>

      <div className="card">
        <h2>Shipments by score</h2>
        <p className="sub">Highest first. The factors below each score are the whole
           calculation — nothing is hidden in a model.</p>
        {!rows.length ? <Empty>No scores computed yet.</Empty> : rows.map((r) => (
          <div key={r!.subjectId} style={{ borderBottom: '1px solid var(--border)', padding: '12px 0' }}>
            <div style={{ display: 'flex', gap: 10, alignItems: 'center', marginBottom: 6 }}>
              <RiskPill score={r!.score} band={r!.band} />
              <Link to={`/shipments/${r!.subjectId}`} className="mono"><b>{r!.subjectId}</b></Link>
            </div>
            {r!.factors.map((f, i) => (
              <div className="factor" key={i}>
                <span className="w">+{f.weight}</span>
                <span className="n">{f.factor}</span>
                <span className="d">{f.detail}</span>
              </div>
            ))}
          </div>
        ))}
      </div>

      <div className="card">
        <h2>Party watchlist</h2>
        <p className="sub">Rolling profiles per supplier, buyer and transporter.</p>
        {!parties.data?.length ? <Empty>No party scores yet.</Empty> : (
          <div className="table-wrap">
            <table>
              <thead><tr><th>Score</th><th>Party</th><th>Leading factors</th></tr></thead>
              <tbody>
                {parties.data.map((p) => (
                  <tr key={p.subjectId}>
                    <td><RiskPill score={p.score} band={p.band} /></td>
                    <td className="mono tiny">{p.subjectId.slice(0, 8)}…</td>
                    <td>
                      {p.factors.slice(0, 3).map((f, i) => (
                        <div key={i} className="tiny"><b>+{f.weight}</b> {f.detail}</div>
                      ))}
                    </td>
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
