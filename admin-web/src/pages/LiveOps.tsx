import { useEffect, useRef, useState } from 'react'
import { Link } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import { api, auth, when } from '../api'
import { Empty, Loading, Pill } from '../components/Bits'
import type { ScanEvent } from '../types'

/**
 * §6.3 Live ops — the cross-device scan feed.
 *
 * Scans arrive over a WebSocket the server pushes to after each batch commits,
 * with a polled backfill so the page is correct even when the socket drops on
 * dock Wi-Fi. The socket is the nice path; the poll is the one that has to work.
 */
export default function LiveOps() {
  const [live, setLive] = useState<ScanEvent[]>([])
  const [connected, setConnected] = useState(false)
  const socket = useRef<WebSocket | null>(null)

  const recent = useQuery({
    queryKey: ['scans', 'all'],
    queryFn: () => api.scans(undefined, 60),
    refetchInterval: 8_000,
  })

  useEffect(() => {
    const proto = location.protocol === 'https:' ? 'wss' : 'ws'
    // A browser cannot set headers on a WebSocket handshake, so the session
    // token rides as a query parameter — the server closes the socket before
    // any data flows unless it verifies.
    const token = auth.token()
    const qs = token ? `?token=${encodeURIComponent(token)}` : ''
    const ws = new WebSocket(`${proto}://${location.host}/v1/live${qs}`)
    socket.current = ws
    ws.onopen = () => setConnected(true)
    ws.onclose = () => setConnected(false)
    ws.onmessage = (e) => {
      try {
        const event = JSON.parse(e.data) as ScanEvent
        setLive((prev) => [event, ...prev].slice(0, 80))
      } catch { /* a frame we don't understand is not worth breaking the page for */ }
    }
    return () => ws.close()
  }, [])

  if (recent.isLoading) return <Loading what="Loading the scan feed" />

  // Live events first, then the polled history, without showing anything twice.
  const seen = new Set(live.map((e) => e.client_event_id))
  const rows = [...live, ...(recent.data ?? []).filter((e) => !seen.has(e.client_event_id))]

  return (
    <>
      <div className="page-head">
        <div>
          <h1>Live scans</h1>
          <p>Every device, one feed. A duplicate that is invisible to a single phone
             is obvious here.</p>
        </div>
        <Pill tone={connected ? 'ok' : 'warn'}>
          {connected ? 'socket live' : 'polling (socket down)'}
        </Pill>
      </div>

      {!rows.length ? <Empty>No scans recorded.</Empty> : (
        <div className="card">
          <div className="table-wrap">
            <table>
              <thead>
                <tr><th>Result</th><th>Package</th><th>Shipment</th><th>Kind</th>
                  <th>Reasons</th><th>When</th></tr>
              </thead>
              <tbody>
                {rows.slice(0, 100).map((s, i) => (
                  <tr key={s.client_event_id + i}>
                    <td>
                      <Pill tone={s.result === 'VERIFIED' ? 'ok' : s.result === 'REJECTED' ? 'bad' : 'warn'}>
                        {s.result}
                      </Pill>
                    </td>
                    <td className="mono tiny">{s.package_code}</td>
                    <td className="tiny">
                      {s.shipment_ref
                        ? <Link to={`/shipments/${s.shipment_ref}`} className="mono">{s.shipment_ref}</Link>
                        : '—'}
                    </td>
                    <td className="tiny">{s.kind}</td>
                    <td className="tiny" style={{ color: s.reasons.length ? 'var(--bad)' : undefined }}>
                      {s.reasons.join(', ') || '—'}
                    </td>
                    <td className="tiny muted">{when(s.client_ts)}</td>
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
