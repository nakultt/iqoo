import { useState } from 'react'
import { NavLink, Navigate, Route, Routes } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import { api, auth } from './api'
import Shipments from './pages/Shipments'
import ShipmentDetail from './pages/ShipmentDetail'
import Payments from './pages/Payments'
import Risk from './pages/Risk'
import Discrepancies from './pages/Discrepancies'
import PackStation from './pages/PackStation'
import LiveOps from './pages/LiveOps'
import Audit from './pages/Audit'
import AgentLog from './pages/AgentLog'

export default function App() {
  const [user, setUser] = useState(auth.user())
  if (!user) return <Login onLogin={(u) => setUser(u)} />

  return (
    <div className="shell">
      <Sidebar user={user} onLogout={() => { auth.clear(); setUser(null) }} />
      <div className="main">
        <Routes>
          <Route path="/" element={<Navigate to="/shipments" replace />} />
          <Route path="/shipments" element={<Shipments />} />
          <Route path="/shipments/:ref" element={<ShipmentDetail />} />
          <Route path="/payments" element={<Payments />} />
          <Route path="/risk" element={<Risk />} />
          <Route path="/discrepancies" element={<Discrepancies />} />
          <Route path="/pack" element={<PackStation />} />
          <Route path="/live" element={<LiveOps />} />
          <Route path="/agent" element={<AgentLog />} />
          <Route path="/audit" element={<Audit />} />
        </Routes>
      </div>
    </div>
  )
}

function Sidebar({ user, onLogout }: { user: string; onLogout: () => void }) {
  const { data: open } = useQuery({
    queryKey: ['discrepancies', 'count'],
    queryFn: () => api.discrepancies(),
    refetchInterval: 15_000,
  })
  const { data: held } = useQuery({
    queryKey: ['finance', 'HELD'],
    queryFn: () => api.financeQueue('HELD'),
    refetchInterval: 20_000,
  })
  const { data: chain } = useQuery({ queryKey: ['audit', 'verify'], queryFn: api.auditVerify })

  return (
    <aside className="sidebar">
      <div className="brand">
        <b>VeriTransit</b>
        <span>ops</span>
      </div>

      <nav className="nav">
        <div className="nav-section">Verification</div>
        <NavLink to="/shipments">Shipments</NavLink>
        <NavLink to="/live">Live scans</NavLink>
        <NavLink to="/discrepancies">
          Discrepancies
          {open && open.length > 0 && <span className="count">{open.length}</span>}
        </NavLink>
        <NavLink to="/pack">Pack station</NavLink>

        <div className="nav-section">Commerce</div>
        <NavLink to="/payments">
          Payments
          {held && held.length > 0 && <span className="count">{held.length}</span>}
        </NavLink>
        <NavLink to="/risk">Risk console</NavLink>

        <div className="nav-section">Oversight</div>
        <NavLink to="/agent">Agent log</NavLink>
        <NavLink to="/audit">Audit chain</NavLink>
      </nav>

      <div style={{ marginTop: 'auto', paddingTop: 16 }}>
        {/* §6.3 tamper indicator. It lives in the chrome, not on a page someone
            has to remember to open — a broken chain should be impossible to miss. */}
        {chain && !chain.ok && (
          <div className="banner bad tiny" style={{ marginBottom: 10 }}>
            Audit chain broken at seq {chain.firstBadSeq}
          </div>
        )}
        <div className="tiny muted" style={{ padding: '0 10px 8px' }}>{user}</div>
        <button className="small" style={{ width: '100%' }} onClick={onLogout}>Sign out</button>
      </div>
    </aside>
  )
}

function Login({ onLogin }: { onLogin: (user: string) => void }) {
  const [name, setName] = useState('Anil Rao')
  const [password, setPassword] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  async function submit(e: React.FormEvent) {
    e.preventDefault()
    setBusy(true); setError(null)
    try {
      const res = await api.login(name, password)
      auth.set(res.token, res.name)
      onLogin(res.name)
    } catch {
      setError('Sign-in failed — check the name and operator secret.')
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="login">
      <form className="card" onSubmit={submit}>
        <h2>VeriTransit operations</h2>
        <p className="sub">Sign in with your name and the operator secret.</p>
        {error && <div className="banner bad">{error}</div>}
        <div className="field">
          <label>Name</label>
          <input value={name} onChange={(e) => setName(e.target.value)} autoFocus />
        </div>
        <div className="field">
          <label>Operator secret</label>
          <input type="password" value={password} onChange={(e) => setPassword(e.target.value)} />
        </div>
        <button className="primary" style={{ width: '100%' }} disabled={busy}>
          {busy ? 'Signing in…' : 'Sign in'}
        </button>
        <p className="tiny muted" style={{ marginTop: 12, marginBottom: 0 }}>
          Pilot builds share one operator secret; per-user credentials are not yet
          implemented. Roles and maker-checker are enforced once you are signed in.
        </p>
      </form>
    </div>
  )
}
