import { useState } from "react";
import { runPolicy } from "../api.js";
import { int, pct } from "../lib/policies.jsx";

const FIELDS = {
  fixed: [{ name: "minutes", label: "Keep-alive minutes", id: "input-minutes", def: "10", min: 1, max: 240, step: 1, integer: true }],
  ewma: [
    { name: "alpha", label: "Alpha (smoothing, 0-1)", id: "input-alpha", def: "0.3", min: 0.01, max: 1, step: 0.05 },
    { name: "multiplier", label: "Multiplier", id: "input-multiplier", def: "2.0", min: 0.1, max: 10, step: 0.1 },
  ],
  percentile: [
    { name: "p", label: "Percentile (0-1)", id: "input-p", def: "0.9", min: 0.01, max: 0.99, step: 0.01 },
    { name: "maxMinutes", label: "Max keep-alive minutes", id: "input-max", def: "60", min: 1, max: 240, step: 1, integer: true },
  ],
};

const TYPE_LABELS = { fixed: "Fixed timeout", ewma: "EWMA", percentile: "Percentile of gaps" };

// Finds a field anywhere in the response, preferring the top level, then "overall".
function findValue(obj, key) {
  if (!obj || typeof obj !== "object") return undefined;
  if (key in obj) return obj[key];
  if (obj.overall && key in obj.overall) return obj.overall[key];
  const queue = Object.values(obj).filter((v) => v && typeof v === "object");
  while (queue.length) {
    const o = queue.shift();
    if (key in o) return o[key];
    Object.values(o).forEach((v) => v && typeof v === "object" && queue.push(v));
  }
  return undefined;
}

// Turns a nested response into [path, value] pairs for the details table.
function flatten(obj, prefix = "", out = []) {
  if (obj === null || typeof obj !== "object") {
    out.push([prefix, obj]);
    return out;
  }
  Object.entries(obj).forEach(([k, v]) => flatten(v, prefix ? `${prefix}.${k}` : k, out));
  return out;
}

function showValue(v) {
  if (typeof v === "number") return Number.isInteger(v) ? int(v) : v.toFixed(6);
  return String(v);
}

export default function RunPage() {
  const [type, setType] = useState("fixed");
  const [values, setValues] = useState(() =>
    Object.fromEntries(Object.values(FIELDS).flat().map((f) => [f.name, f.def]))
  );
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState("");
  const [result, setResult] = useState(null);
  const [history, setHistory] = useState([]);

  function validate() {
    for (const f of FIELDS[type]) {
      const n = Number(values[f.name]);
      if (values[f.name] === "" || Number.isNaN(n)) return `${f.label}: enter a number.`;
      if (n < f.min || n > f.max) return `${f.label}: must be between ${f.min} and ${f.max}.`;
      if (f.integer && !Number.isInteger(n)) return `${f.label}: must be a whole number.`;
    }
    return "";
  }

  async function onSubmit(e) {
    e.preventDefault();
    const problem = validate();
    if (problem) {
      setError(problem);
      return;
    }
    setError("");
    setLoading(true);
    setResult(null);
    try {
      const { url, data } = await runPolicy(type, values);
      const cold = findValue(data, "coldStartRate");
      const idle = findValue(data, "idleWarmMinutes");
      const entry = { type, url, data, cold, idle, at: new Date().toLocaleTimeString() };
      setResult(entry);
      setHistory((h) => [entry, ...h].slice(0, 10));
    } catch (err) {
      setError(err.message);
    } finally {
      setLoading(false);
    }
  }

  return (
    <div className="page" id="run-page">
      <div className="grid-2">
        <section className="card">
          <h2>Policy settings</h2>
          <p className="muted small">
            Runs one policy on the backend over the loaded trace. The first run after the backend starts takes 30-90
            seconds.
          </p>
          <form id="run-form" onSubmit={onSubmit} noValidate>
            <label className="field">
              <span>Policy type</span>
              <select id="run-policy-type" value={type} onChange={(e) => { setType(e.target.value); setError(""); }}>
                {Object.entries(TYPE_LABELS).map(([k, v]) => (
                  <option key={k} value={k}>
                    {v}
                  </option>
                ))}
              </select>
            </label>
            {FIELDS[type].map((f) => (
              <label className="field" key={f.name}>
                <span>{f.label}</span>
                <input
                  id={f.id}
                  name={f.name}
                  type="number"
                  min={f.min}
                  max={f.max}
                  step={f.step}
                  value={values[f.name]}
                  onChange={(e) => setValues({ ...values, [f.name]: e.target.value })}
                />
              </label>
            ))}
            <button id="run-submit" type="submit" className="btn btn-primary btn-wide" disabled={loading}>
              {loading ? "Running…" : "Run simulation"}
            </button>
          </form>
          {loading && (
            <div id="run-loading" className="banner">
              <span className="spinner" aria-hidden="true" /> Waiting for the backend…
            </div>
          )}
          {error && (
            <div id="run-error" className="banner banner-error">
              {error}
            </div>
          )}
        </section>

        <section className="card" id="run-result-card">
          <h2>Result</h2>
          {!result && !loading && <p className="muted">Run a policy to see its result here.</p>}
          {result && (
            <div id="run-result">
              <div className="muted small">
                GET <code>{result.url}</code>
              </div>
              <div className="kpi-row kpi-row-2">
                <div className="kpi kpi-purple">
                  <div className="kpi-label">Cold-start rate</div>
                  <div className="kpi-value" id="result-cold">
                    {typeof result.cold === "number" ? pct(result.cold) : "—"}
                  </div>
                </div>
                <div className="kpi kpi-teal">
                  <div className="kpi-label">Idle warm-minutes</div>
                  <div className="kpi-value" id="result-idle">
                    {typeof result.idle === "number" ? int(result.idle) : "—"}
                  </div>
                </div>
              </div>
              <details>
                <summary>All fields returned</summary>
                <div className="table-wrap">
                  <table className="table" id="run-fields">
                    <tbody>
                      {flatten(result.data).slice(0, 80).map(([k, v]) => (
                        <tr key={k}>
                          <td className="muted">{k}</td>
                          <td className="num">{showValue(v)}</td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              </details>
            </div>
          )}
        </section>
      </div>

      {history.length > 0 && (
        <section className="card">
          <h2>Runs this session</h2>
          <div className="table-wrap">
            <table className="table" id="run-history">
              <thead>
                <tr>
                  <th>Time</th>
                  <th>Request</th>
                  <th className="num">Cold-start rate</th>
                  <th className="num">Idle warm-minutes</th>
                </tr>
              </thead>
              <tbody>
                {history.map((h, i) => (
                  <tr key={i}>
                    <td className="muted">{h.at}</td>
                    <td>
                      <code>{h.url}</code>
                    </td>
                    <td className="num">{typeof h.cold === "number" ? pct(h.cold) : "—"}</td>
                    <td className="num">{typeof h.idle === "number" ? int(h.idle) : "—"}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </section>
      )}
    </div>
  );
}
