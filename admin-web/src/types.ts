// Mirrors :core-models. The server is the source of truth for these shapes;
// this file exists so the UI fails at compile time when they drift, not in a
// dashboard a supervisor is relying on.

export type ShipmentStatus = 'OPEN' | 'LOADING' | 'DISPATCHED' | 'RECEIVED' | 'FLAGGED'
export type FinanceStatus = 'AWAITING' | 'VERIFIED' | 'HELD' | 'RELEASE_PENDING' | 'RELEASED'
export type RiskBand = 'LOW' | 'MEDIUM' | 'HIGH'
export type PackageKind = 'UNIT' | 'MASTER' | 'PALLET'
export type ScanResult = 'VERIFIED' | 'SUSPECT_REVIEW' | 'REJECTED'
export type MatchStatus = 'MATCHED' | 'MISMATCHED' | 'PARTIAL'

export interface Shipment {
  ref: string
  vehicle?: string
  origin?: string
  destination?: string
  expectedCount: number
  status: ShipmentStatus
  supplier?: string
  buyer?: string
  dispatchedAt?: string
  receivedAt?: string
}

export interface PackageRecord {
  packageCode: string
  shipmentRef: string
  kind: PackageKind
  parentCode?: string
  contents?: string
  sku?: string
  qty: number
  poLineNo?: number
  status: string
  labelPayload?: string
  copyNo: number
}

export interface RiskFactor { factor: string; weight: number; detail: string }

export interface RiskScore {
  subjectKind: string
  subjectId: string
  score: number
  band: RiskBand
  factors: RiskFactor[]
  computedAt?: string
}

export interface FinanceState {
  shipmentRef: string
  currency: string
  orderValue: number
  status: FinanceStatus
  releasedValue: number
  heldValue: number
  terms: Record<string, string>
}

export interface Discrepancy {
  id: string
  shipmentRef: string
  kind: string
  packageCode?: string
  severity: 'LOW' | 'MEDIUM' | 'HIGH'
  detail: Record<string, string>
  detectedAt: string
  resolvedAt?: string
  resolution?: string
}

export interface ShipmentReport {
  shipment: Shipment
  expected_count: number
  accounted: number
  missing: string[]
  extra: string[]
  flagged: string[]
  inner_units: number
  inner_verified: number
  discrepancies: Discrepancy[]
  risk?: RiskScore
  finance?: FinanceState
  complete: boolean
}

export interface Mismatch {
  code: string
  pair: string
  line_no?: number
  sku?: string
  ordered_qty?: number
  invoiced_qty?: number
  declared_qty?: number
  physical_qty?: number
  delta_qty?: number
  delta_value: number
  detail: string
}

export interface MatchLine {
  line_no: number
  sku?: string
  description: string
  hsn?: string
  rate: number
  ordered_qty?: number
  invoiced_qty?: number
  declared_qty?: number
  physical_qty?: number
}

export interface ReconciliationRun {
  shipmentRef: string
  matrix: { anchor: string; lines: MatchLine[]; four_way: Record<string, number> }
  mismatches: Mismatch[]
  status: MatchStatus
  held_value: number
  run_by: string
  created_at?: string
}

export interface DocumentLine {
  line_no: number
  description: string
  sku?: string
  hsn?: string
  qty: number
  unit: string
  rate: number
  amount: number
}

export interface ShipmentDocument {
  id?: string
  shipment_ref: string
  kind: 'PO' | 'INVOICE' | 'EWB' | 'LR' | 'PACKING_LIST' | 'CHALLAN'
  doc_no: string
  doc_date?: string
  fact: {
    kind: string
    doc_no: string
    date?: string
    seller: { name: string; gstin?: string }
    buyer: { name: string; gstin?: string }
    ship_to?: string
    lines: DocumentLine[]
    totals: { taxable_value: number; igst: number; grand_total: number }
  }
  source_uri?: string
  read_by: string
  confidence?: number
}

export interface ScanEvent {
  client_event_id: string
  package_code: string
  shipment_ref?: string
  kind: string
  result: ScanResult
  reasons: string[]
  evidence_uri?: string
  lat?: number
  lng?: number
  client_ts: string
}

export interface AgentStep { step: string; detail: string }

export interface AgentAction {
  id: string
  shipment_ref?: string
  trigger_event: string
  steps: AgentStep[]
  outcome: string
  reasoning_summary?: string
  created_at?: string
}

export interface AuditEntry {
  seq: number
  at: string
  actor: string
  action: string
  subject: string
  prev_hash?: string
  hash: string
}

export interface AuditChainStatus {
  ok: boolean
  checked: number
  firstBadSeq?: number
  detail: string
}

export interface PodCertificate {
  id: string
  shipment_ref: string
  signature: string
  key_id: string
  issued_at: string
  evidence_hashes: Record<string, string>
  match_result: Record<string, string>
  pdf_uri?: string
}

export interface IssuedLabel {
  package_code: string
  kind: PackageKind
  parent_code?: string
  payload: string
  contents?: string
  qty: number
}
