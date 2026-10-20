import { useEffect, useRef, useState } from "react";
import savedResults from "./data/savedResults.js";
import { fetchSummary, API_BASE } from "./api.js";
import ResultsPage from "./pages/ResultsPage.jsx";
import BandsPage from "./pages/BandsPage.jsx";
import RunPage from "./pages/RunPage.jsx";

const PAGES = [
  { id: "results", label: "Results", title: "Cold starts vs idle cost" },
  { id: "bands", label: "Per band", title: "Results by call-count band" },
  { id: "run", label: "Run a policy", title: "Run a policy on the backend" },
];

// Simple hash routing: #/results, #/bands, #/run (no extra library needed).
function pageFromHash() {
  const id = window.location.hash.replace("#/", "");
  return PAGES.some((p) => p.id === id) ? id : "results";
}

export default function App() {
  const [page, setPage] = useState(pageFromHash());
  const [rows, setRows] = useState(savedResults);
  const [source, setSource] = useState("saved"); // "saved" | "live"
  const [loading, setLoading] = useState(false);
  const [elapsed, setElapsed] = useState(0);
  const [error, setError] = useState("");
  const timerRef = useRef(null);

  useEffect(() => {
    const onHash = () => setPage(pageFromHash());
    window.addEventListener("hashchange", onHash);
    return () => window.removeEventListener("hashchange", onHash);
  }, []);

  useEffect(() => () => clearInterval(timerRef.current), []);

  async function loadLive() {
    setLoading(true);
    setError("");
    setElapsed(0);
    const start = Date.now();
    timerRef.current = setInterval(() => setElapsed(Math.floor((Date.now() - start) / 1000)), 1000);
    try {
      const data = await fetchSummary();
      if (!Array.isArray(data) || data.length === 0) throw new Error("The backend returned no rows.");
      setRows(data);
      setSource("live");
    } catch (err) {
      setError(err.message);
    } finally {
      clearInterval(timerRef.current);
      setLoading(false);
    }
  }

  function useSaved() {
    setRows(savedResults);
    setSource("saved");
    setError("");
  }

  const current = PAGES.find((p) => p.id === page);

  return (
    <div className="layout">
      <aside className="sidebar">
        <div className="brand">Keep-Alive Scheduler</div>
        <div className="side-heading">Dashboard</div>
        <nav>
          {PAGES.map((p) => (
            <a
              key={p.id}
              id={`nav-${p.id}`}
              href={`#/${p.id}`}
              className={page === p.id ? "nav-link active" : "nav-link"}
            >
              {p.label}
            </a>
          ))}
        </nav>

        <div className="side-heading">Data source</div>
        <div className="source-box">
          <div id="data-source-label" className="source-label">
            {source === "saved" ? "Saved results (days 6-7)" : "Live from backend"}
          </div>
          <div className="muted small">{rows.length} policies</div>
          <button id="load-live" className="btn btn-primary" onClick={loadLive} disabled={loading}>
            {loading ? "Loading…" : "Load live from backend"}
          </button>
          {source === "live" && (
            <button id="use-saved" className="btn btn-ghost" onClick={useSaved} disabled={loading}>
              Use saved results
            </button>
          )}
        </div>

        <div className="side-foot muted small">
          Sample B · 2,000 Azure functions
          <br />
          Backend: {API_BASE}
        </div>
      </aside>

      <main className="main">
        <header className="page-header">
          <h1 id="page-title">{current.title}</h1>
        </header>

        {loading && (
          <div id="summary-loading" className="banner">
            <span className="spinner" aria-hidden="true" /> Running all policies on the backend… {elapsed}s.
            The first call after start-up takes 30-90 seconds while the data loads.
          </div>
        )}
        {error && (
          <div id="summary-error" className="banner banner-error">
            {error}
          </div>
        )}

        {page === "results" && <ResultsPage rows={rows} />}
        {page === "bands" && <BandsPage rows={rows} />}
        {page === "run" && <RunPage />}
      </main>
    </div>
  );
}
