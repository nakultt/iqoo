import type {
  AgentAction, AuditChainStatus, AuditEntry, Discrepancy, FinanceState, IssuedLabel,
  PackageRecord, PodCertificate, ReconciliationRun, RiskScore, ScanEvent, Shipment,
  ShipmentDocument, ShipmentReport,
} from './types'

const TOKEN_KEY = 'vt.token'
const USER_KEY = 'vt.user'

export const auth = {
  token: () => localStorage.getItem(TOKEN_KEY),
  user: () => localStorage.getItem(USER_KEY),
  set(token: string, user: string) {
    localStorage.setItem(TOKEN_KEY, token)
    localStorage.setItem(USER_KEY, user)
  },
  clear() {
    localStorage.removeItem(TOKEN_KEY)
    localStorage.removeItem(USER_KEY)
  },
}

export class ApiError extends Error {
  constructor(public status: number, message: string, public detail?: string) {
    super(message)
  }
}

async function req<T>(path: string, init?: RequestInit): Promise<T> {
  const headers: Record<string, string> = { 'Content-Type': 'application/json' }
  const token = auth.token()
  if (token) headers.Authorization = `Bearer ${token}`
  // The acting user travels with every mutation so the audit chain and
  // maker-checker never have to guess who did something.
  const user = auth.user()
  if (user) headers['X-Officer'] = user

  const res = await fetch(path, { ...init, headers: { ...headers, ...(init?.headers ?? {}) } })
  const text = await res.text()
  const body = text ? JSON.parse(text) : null

  if (!res.ok) {
    throw new ApiError(res.status, body?.error ?? res.statusText, body?.detail)
  }
  return body as T
}

export const api = {
  health: () => req<{ status: string; audit_entries: string; key_id: string }>('/health'),

  login: (name: string, password: string) =>
    req<{ token: string; name: string; role: string }>('/v1/auth/login', {
      method: 'POST',
      body: JSON.stringify({ name, password }),
    }),

  shipments: () => req<Shipment[]>('/v1/shipments'),
  shipment: (ref: string) => req<Shipment>(`/v1/shipments/${ref}`),
  report: (ref: string) => req<ShipmentReport>(`/v1/shipments/${ref}/report`),
  packages: (ref: string) => req<PackageRecord[]>(`/v1/shipments/${ref}/packages`),
  documents: (ref: string) => req<ShipmentDocument[]>(`/v1/shipments/${ref}/documents`),
  reconciliation: (ref: string) => req<ReconciliationRun>(`/v1/shipments/${ref}/reconciliation`),
  reconcile: (ref: string) =>
    req<ReconciliationRun>(`/v1/shipments/${ref}/reconcile`, { method: 'POST' }),

  createShipment: (body: Record<string, unknown>) =>
    req<Shipment>('/v1/shipments', { method: 'POST', body: JSON.stringify(body) }),

  issueLabels: (ref: string, body: unknown) =>
    req<{ shipmentRef: string; labels: IssuedLabel[]; issued: number }>(
      `/v1/shipments/${ref}/labels:batch`, { method: 'POST', body: JSON.stringify(body) }),

  closeMaster: (body: unknown) =>
    req<IssuedLabel>('/v1/packages:close-master', { method: 'POST', body: JSON.stringify(body) }),

  reprint: (code: string) => req<IssuedLabel>(`/v1/labels/${code}:reprint`, { method: 'POST' }),

  finance: (ref: string) => req<FinanceState>(`/v1/shipments/${ref}/finance`),
  financeQueue: (status: string) => req<FinanceState[]>(`/v1/finance/queue?status=${status}`),
  payments: (ref: string) => req<Record<string, string>[]>(`/v1/shipments/${ref}/payments`),
  requestRelease: (ref: string, amount?: number, note?: string) =>
    req<{ message: string; status: string }>(`/v1/finance/${ref}:release`, {
      method: 'POST', body: JSON.stringify({ amount, note }),
    }),
  approveRelease: (ref: string) =>
    req<{ message: string; status: string; certificate_id?: string }>(
      `/v1/finance/${ref}:approve`, { method: 'POST' }),
  hold: (ref: string, amount: number, reason: string) =>
    req<{ message: string }>(`/v1/finance/${ref}:hold`, {
      method: 'POST', body: JSON.stringify({ amount, reason }),
    }),
  resolveHold: (ref: string, note: string) =>
    req<{ message: string }>(`/v1/finance/${ref}:resolve`, {
      method: 'POST', body: JSON.stringify({ note }),
    }),

  risk: (ref: string, recompute = false) =>
    req<RiskScore>(`/v1/risk/shipments/${ref}${recompute ? '?recompute=true' : ''}`),
  riskParties: () => req<RiskScore[]>('/v1/risk/parties'),

  discrepancies: (ref?: string, open = true) =>
    req<Discrepancy[]>(`/v1/discrepancies?open=${open}${ref ? `&shipment=${ref}` : ''}`),
  resolveDiscrepancy: (id: string, resolution: string, note: string) =>
    req<unknown>(`/v1/discrepancies/${id}/resolve?resolution=${resolution}&note=${encodeURIComponent(note)}`,
      { method: 'POST' }),

  scans: (ref?: string, limit = 100) =>
    req<ScanEvent[]>(`/v1/scans?limit=${limit}${ref ? `&shipment=${ref}` : ''}`),

  pod: (ref: string) => req<PodCertificate[]>(`/v1/shipments/${ref}/pod`),
  podRender: async (id: string) => {
    const res = await fetch(`/v1/pod/${id}/render`)
    return res.text()
  },

  agentActions: (ref?: string) =>
    req<AgentAction[]>(`/v1/agent/actions${ref ? `?shipment=${ref}` : ''}`),
  agentRun: (ref: string) => req<AgentAction>(`/v1/agent/run/${ref}?trigger=MANUAL`, { method: 'POST' }),
  agentExplain: async (ref: string) => (await fetch(`/v1/agent/explain/${ref}`)).text(),

  audit: (from?: number, to?: number) =>
    req<AuditEntry[]>(`/v1/audit?${from ? `from=${from}&` : ''}${to ? `to=${to}` : ''}`),
  auditVerify: () => req<AuditChainStatus>('/v1/audit/verify'),

  setStatus: (ref: string, to: string, override = false) =>
    req<Shipment>(`/v1/shipments/${ref}/status?to=${to}${override ? '&override=true' : ''}`,
      { method: 'POST' }),
}

/** ₹ formatting, used everywhere a value appears so the money always reads the same. */
export const inr = (v: number | undefined) =>
  v === undefined ? '—' : '₹' + v.toLocaleString('en-IN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })

export const inrShort = (v: number | undefined) => {
  if (v === undefined) return '—'
  if (v >= 10000000) return `₹${(v / 10000000).toFixed(2)}Cr`
  if (v >= 100000) return `₹${(v / 100000).toFixed(2)}L`
  return '₹' + v.toLocaleString('en-IN', { maximumFractionDigits: 0 })
}

export const when = (iso?: string) =>
  iso ? new Date(iso).toLocaleString('en-IN', { dateStyle: 'medium', timeStyle: 'short' }) : '—'
