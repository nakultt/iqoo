import { Link } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import { api, when } from '../api'
import { Empty, Loading, Pill } from '../components/Bits'

/**
 * §5.5 agent action log.
 *
 * The runs where the agent *refused* to act matter more than the ones where it
 * proceeded: "blocked by policy, here is which precondition failed" is the
 * evidence that autonomy over money is actually bounded.
 */
export default function AgentLog() {
  const { data, isLoading } = useQuery({ queryKey: ['agent'], queryFn: () => api.agentActions() })
  if (isLoading) return <Loading what="Loading agent actions" />

  const blocked = (data ?? []).filter((a) => a.outcome !== 'COMPLETED').length

  return (
    <>
      <div className="page-head">
        <div>
          <h1>Agent log</h1>
          <p>The agent proposes; policy disposes. Deterministic engines decide
             match, score and finance state — the agent gathers, notifies, and acts
             only inside an approved policy.</p>
        </div>
      </div>

      <div className="grid stats" style={{ marginBottom: 18 }}>
        <div className="stat"><div className="label">Runs</div>
          <div className="value">{data?.length ?? 0}</div></div>
        <div className="stat"><div className="label">Stopped short of acting</div>
          <div className="value" style={{ color: blocked ? 'var(--warn)' : undefined }}>{blocked}</div>
          <div className="note">routed to a human or refused by policy</div></div>
      </div>

      {!data?.length ? <Empty>The agent has not run yet.</Empty> : data.map((a) => (
        <div className="card" key={a.id}>
          <div style={{ display: 'flex', gap: 10, alignItems: 'center', marginBottom: 4 }}>
            <Pill tone={
              a.outcome === 'COMPLETED' ? 'ok'
              : a.outcome === 'BLOCKED_BY_POLICY' ? 'bad' : 'warn'
            }>{a.outcome}</Pill>
            {a.shipment_ref && (
              <Link to={`/shipments/${a.shipment_ref}`} className="mono"><b>{a.shipment_ref}</b></Link>
            )}
            <span className="tiny muted">{a.trigger_event} · {when(a.created_at)}</span>
          </div>
          <table style={{ marginTop: 8 }}>
            <tbody>
              {a.steps.map((s, i) => (
                <tr key={i}>
                  <td style={{ width: 80 }}><b className="tiny">{s.step}</b></td>
                  <td className="tiny">{s.detail}</td>
                </tr>
              ))}
            </tbody>
          </table>
          {a.reasoning_summary && (
            <pre className="doc" style={{ marginTop: 10 }}>{a.reasoning_summary}</pre>
          )}
        </div>
      ))}
    </>
  )
}
