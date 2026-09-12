import type { ReactNode } from 'react'
import type { FinanceStatus, RiskBand, ShipmentStatus } from '../types'

/** Small shared pieces. State colour is decided here once so the same condition
 *  never reads green on one page and amber on another. */

export function Pill({ tone, children }: { tone: string; children: ReactNode }) {
  return <span className={`pill ${tone}`}>{children}</span>
}

export function StatusPill({ status }: { status: ShipmentStatus }) {
  const tone = status === 'FLAGGED' ? 'bad'
    : status === 'RECEIVED' ? 'ok'
    : status === 'DISPATCHED' ? 'accent'
    : status === 'LOADING' ? 'warn' : 'dim'
  return <Pill tone={tone}>{status}</Pill>
}

export function FinancePill({ status, held }: { status?: FinanceStatus; held?: number }) {
  if (!status) return <span className="muted">—</span>
  const tone = status === 'RELEASED' ? 'ok'
    : status === 'HELD' ? 'held'
    : status === 'RELEASE_PENDING' ? 'warn'
    : status === 'VERIFIED' ? 'accent' : 'dim'
  return (
    <Pill tone={tone}>
      {status}{status === 'HELD' && held ? ` ₹${held.toLocaleString('en-IN')}` : ''}
    </Pill>
  )
}

export function RiskPill({ score, band }: { score?: number; band?: RiskBand }) {
  if (score === undefined || !band) return <span className="muted">—</span>
  const tone = band === 'HIGH' ? 'bad' : band === 'MEDIUM' ? 'warn' : 'ok'
  return <Pill tone={tone}>{score} {band}</Pill>
}

/** §6.3 completeness ring — the "are all the boxes here?" answer, pre-attention. */
export function Ring({ done, total }: { done: number; total: number }) {
  const pct = total > 0 ? Math.round((done / total) * 100) : 0
  const color = pct >= 100 ? 'var(--ok)' : pct >= 90 ? 'var(--warn)' : 'var(--bad)'
  return (
    <div className="ring" style={{ ['--pct' as string]: pct, ['--ring-color' as string]: color }}>
      <span>{pct}%</span>
    </div>
  )
}

export function Stat({ label, value, note }: { label: string; value: ReactNode; note?: ReactNode }) {
  return (
    <div className="stat">
      <div className="label">{label}</div>
      <div className="value">{value}</div>
      {note && <div className="note">{note}</div>}
    </div>
  )
}

export function Loading({ what = 'Loading' }: { what?: string }) {
  return <div className="empty">{what}…</div>
}

export function Empty({ children }: { children: ReactNode }) {
  return <div className="empty">{children}</div>
}

export function ErrorBox({ error }: { error: unknown }) {
  const e = error as { message?: string; detail?: string }
  return (
    <div className="banner bad">
      <b>{e?.message ?? 'Request failed'}</b>
      {e?.detail && <div className="tiny" style={{ marginTop: 4 }}>{e.detail}</div>}
    </div>
  )
}
