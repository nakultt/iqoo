import { useQuery } from '@tanstack/react-query'
import { api, when } from '../api'
import { Empty, Loading, Pill } from '../components/Bits'

/**
 * §6.3 Audit — the hash-chain viewer with its tamper indicator.
 *
 * The verification is done by the database function, not recomputed here: the
 * chain's guarantee comes from the fact that one authority checks it the same
 * way every time, and a browser re-implementation would only give a second
 * opinion that could disagree.
 */
export default function Audit() {
  const chain = useQuery({ queryKey: ['audit', 'verify'], queryFn: api.auditVerify, refetchInterval: 30_000 })
  const entries = useQuery({ queryKey: ['audit', 'entries'], queryFn: () => api.audit() })

  if (entries.isLoading) return <Loading what="Loading the audit chain" />

  return (
    <>
      <div className="page-head">
        <div>
          <h1>Audit chain</h1>
          <p>Every scan and decision is sealed to the one before it. Rip out a page and
             the seals stop matching — which is exactly what the check below detects.</p>
        </div>
      </div>

      {chain.data && (
        <div className={`banner ${chain.data.ok ? 'ok' : 'bad'}`}>
          <b>{chain.data.ok ? 'Chain intact' : `CHAIN BROKEN at seq ${chain.data.firstBadSeq}`}</b>
          {' — '}{chain.data.checked.toLocaleString()} entries verified. {chain.data.detail}
        </div>
      )}

      <div className="card">
        <h2>{entries.data?.length ?? 0} entries</h2>
        <p className="sub">Oldest first. Each hash covers the previous hash, so the
           sequence itself is the evidence.</p>
        {!entries.data?.length ? <Empty>Nothing recorded.</Empty> : (
          <div className="table-wrap">
            <table>
              <thead>
                <tr><th className="num">Seq</th><th>When</th><th>Actor</th><th>Action</th>
                  <th>Subject</th><th>Prev</th><th>Hash</th></tr>
              </thead>
              <tbody>
                {entries.data.map((e) => (
                  <tr key={e.seq}>
                    <td className="num mono">{e.seq}</td>
                    <td className="tiny muted">{when(e.at)}</td>
                    <td className="tiny">{e.actor}</td>
                    <td>
                      <Pill tone={
                        e.action.includes('RELEASE') ? 'ok'
                        : e.action.includes('HELD') || e.action.includes('DISCREPANCY') ? 'bad'
                        : e.action.includes('OVERRIDE') ? 'warn' : 'dim'
                      }>{e.action}</Pill>
                    </td>
                    <td className="mono tiny">{e.subject}</td>
                    <td className="mono tiny muted">{e.prev_hash?.slice(0, 10) ?? 'GENESIS'}</td>
                    <td className="mono tiny">{e.hash.slice(0, 10)}</td>
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
