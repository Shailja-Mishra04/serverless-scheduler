import { useMemo, useState } from "react";
import {
  CartesianGrid,
  Legend,
  ResponsiveContainer,
  Scatter,
  ScatterChart,
  Tooltip,
  XAxis,
  YAxis,
} from "recharts";
import { COLORS, FAMILIES, HIGHLIGHT_FAMILIES, familyOf, int, millions, pct, relChange, legendText } from "../lib/policies.jsx";

const GATE = "gate-hist240";
const HIST = "hist-max240-fb60";
const FIXED60 = "fixed-60min";

const COLUMNS = [
  { key: "policy", label: "Policy" },
  { key: "family", label: "Family" },
  { key: "coldStartRate", label: "Cold-start rate" },
  { key: "meanFunctionColdStartRate", label: "Per-function mean" },
  { key: "idleWarmMinutes", label: "Idle warm-minutes" },
];

function ScatterTip({ active, payload }) {
  if (!active || !payload || !payload.length) return null;
  const d = payload[0].payload;
  return (
    <div className="tip">
      <div className="tip-title">{d.policy}</div>
      <div>Cold starts: {d.y.toFixed(3)}%</div>
      <div>Idle warm-minutes: {int(d.x)}</div>
    </div>
  );
}

function Kpi({ id, label, value, sub, tone }) {
  return (
    <div id={id} className={`kpi ${tone ? "kpi-" + tone : ""}`}>
      <div className="kpi-label">{label}</div>
      <div className="kpi-value">{value}</div>
      {sub && <div className="kpi-sub">{sub}</div>}
    </div>
  );
}

export default function ResultsPage({ rows }) {
  const [sortKey, setSortKey] = useState("coldStartRate");
  const [sortAsc, setSortAsc] = useState(true);
  const [family, setFamily] = useState("all");
  const [zoom, setZoom] = useState(false);

  const byName = useMemo(() => Object.fromEntries(rows.map((r) => [r.policy, r])), [rows]);
  const gate = byName[GATE];
  const hist = byName[HIST];
  const fixed60 = byName[FIXED60];
  const best = useMemo(
    () => rows.reduce((a, b) => (b.coldStartRate < a.coldStartRate ? b : a), rows[0]),
    [rows]
  );

  // Scatter series: fixed curve, other baselines (muted), then the highlighted families.
  const series = useMemo(() => {
    const point = (r) => ({ policy: r.policy, x: r.idleWarmMinutes, y: r.coldStartRate * 100 });
    const groups = { fixed: [], other: [], hist: [], gate: [], lstm: [] };
    rows.forEach((r) => {
      const f = familyOf(r.policy);
      if (f === "fixed") groups.fixed.push(point(r));
      else if (HIGHLIGHT_FAMILIES.includes(f)) groups[f].push(point(r));
      else groups.other.push(point(r));
    });
    groups.fixed.sort((a, b) => a.x - b.x);
    return groups;
  }, [rows]);

  const tableRows = useMemo(() => {
    const list = rows
      .map((r) => ({ ...r, family: familyOf(r.policy) }))
      .filter((r) => family === "all" || r.family === family);
    list.sort((a, b) => {
      const va = a[sortKey];
      const vb = b[sortKey];
      const cmp = typeof va === "string" ? va.localeCompare(vb) : va - vb;
      return sortAsc ? cmp : -cmp;
    });
    return list;
  }, [rows, family, sortKey, sortAsc]);

  function sortBy(key) {
    if (key === sortKey) setSortAsc(!sortAsc);
    else {
      setSortKey(key);
      setSortAsc(true);
    }
  }

  const xDomain = zoom ? [1350000, 2000000] : ["auto", "auto"];
  const xTicks = zoom ? [1400000, 1500000, 1600000, 1700000, 1800000, 1900000, 2000000] : undefined;
  const yDomain = zoom ? [0.4, 1.2] : [0.3, 40];
  const yTicks = zoom ? [0.4, 0.5, 0.6, 0.8, 1.0, 1.2] : [0.5, 1, 2, 5, 10, 20, 40];

  return (
    <div className="page" id="results-page">
      <section className="kpi-row">
        <Kpi
          id="kpi-gate-cold"
          tone="purple"
          label="Gate cold-start rate"
          value={gate ? pct(gate.coldStartRate) : "—"}
          sub={gate && hist ? `${relChange(gate.coldStartRate, hist.coldStartRate)} vs hybrid histogram` : "gate-hist240 not in data"}
        />
        <Kpi
          id="kpi-gate-idle"
          tone="teal"
          label="Gate idle warm-minutes"
          value={gate ? millions(gate.idleWarmMinutes) : "—"}
          sub={gate && hist ? `${relChange(gate.idleWarmMinutes, hist.idleWarmMinutes)} vs hybrid histogram` : ""}
        />
        <Kpi
          id="kpi-best"
          label="Fewest cold starts"
          value={best ? pct(best.coldStartRate) : "—"}
          sub={best ? best.policy : ""}
        />
        <Kpi
          id="kpi-vs-fixed"
          label="Idle saved vs fixed 60 min"
          value={gate && fixed60 ? relChange(gate.idleWarmMinutes, fixed60.idleWarmMinutes) : "—"}
          sub={gate && fixed60 ? `cold ${pct(gate.coldStartRate)} vs ${pct(fixed60.coldStartRate)}` : ""}
        />
      </section>

      <section className="card">
        <div className="card-head">
          <div>
            <h2>Cold-start rate vs idle warm-minutes</h2>
            <p className="muted small">
              Each dot is one policy. Down and to the left is better. The gray line is the fixed-timeout curve
              (1 to 60 min); a policy beats it if it sits below the line. Cold-start axis is logarithmic.
            </p>
          </div>
          <button id="zoom-toggle" className="btn btn-ghost" onClick={() => setZoom(!zoom)}>
            {zoom ? "Show all policies" : "Zoom to best region"}
          </button>
        </div>
        <div className="chart-box" id="results-chart">
          <ResponsiveContainer width="100%" height={380}>
            <ScatterChart margin={{ top: 10, right: 24, bottom: 28, left: 8 }}>
              <CartesianGrid stroke="#2a3352" strokeDasharray="3 3" />
              <XAxis
                type="number"
                dataKey="x"
                name="Idle warm-minutes"
                domain={xDomain}
                ticks={xTicks}
                allowDataOverflow
                tickFormatter={(v) => `${(v / 1e6).toFixed(zoom ? 1 : 2)}M`}
                stroke="#8a93b2"
                tick={{ fontSize: 12 }}
                label={{ value: "Idle warm-minutes (cost)", position: "insideBottom", offset: -16, fill: "#8a93b2", fontSize: 12 }}
              />
              <YAxis
                type="number"
                dataKey="y"
                name="Cold-start rate"
                scale="log"
                domain={yDomain}
                ticks={yTicks}
                allowDataOverflow
                tickFormatter={(v) => `${v}%`}
                stroke="#8a93b2"
                tick={{ fontSize: 12 }}
                width={52}
              />
              <Tooltip content={<ScatterTip />} cursor={{ stroke: "#3a4468" }} />
              <Legend verticalAlign="top" height={32} iconSize={10} wrapperStyle={{ fontSize: 12 }} formatter={legendText} />
              <Scatter
                name="Fixed timeout"
                data={series.fixed}
                fill={COLORS.fixed}
                line={{ stroke: COLORS.fixed, strokeWidth: 2 }}
                lineType="joint"
                isAnimationActive={false}
              />
              <Scatter name="Other baselines" data={series.other} fill={COLORS.other} isAnimationActive={false} />
              <Scatter name={FAMILIES.hist} data={series.hist} fill={COLORS.hist} isAnimationActive={false} />
              <Scatter name={FAMILIES.gate} data={series.gate} fill={COLORS.gate} shape="diamond" isAnimationActive={false} />
              <Scatter name={FAMILIES.lstm} data={series.lstm} fill={COLORS.lstm} shape="triangle" isAnimationActive={false} />
            </ScatterChart>
          </ResponsiveContainer>
        </div>
      </section>

      <section className="card">
        <div className="card-head">
          <h2>All policies</h2>
          <label className="inline-field">
            <span className="muted small">Family</span>
            <select id="family-filter" value={family} onChange={(e) => setFamily(e.target.value)}>
              <option value="all">All</option>
              {Object.entries(FAMILIES)
                .filter(([k]) => k !== "other")
                .map(([k, v]) => (
                  <option key={k} value={k}>
                    {v}
                  </option>
                ))}
            </select>
          </label>
        </div>
        <div className="table-wrap">
          <table id="results-table" className="table">
            <thead>
              <tr>
                {COLUMNS.map((c) => (
                  <th
                    key={c.key}
                    id={`sort-${c.key}`}
                    onClick={() => sortBy(c.key)}
                    className={c.key === "policy" || c.key === "family" ? "" : "num"}
                  >
                    {c.label}
                    {sortKey === c.key ? (sortAsc ? " ▲" : " ▼") : ""}
                  </th>
                ))}
              </tr>
            </thead>
            <tbody>
              {tableRows.map((r) => (
                <tr key={r.policy} className={HIGHLIGHT_FAMILIES.includes(r.family) ? `row-${r.family}` : ""}>
                  <td>{r.policy}</td>
                  <td className="muted">{FAMILIES[r.family]}</td>
                  <td className="num">{pct(r.coldStartRate)}</td>
                  <td className="num">{pct(r.meanFunctionColdStartRate, 2)}</td>
                  <td className="num">{int(r.idleWarmMinutes)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
        <p className="muted small">
          Cold-start rate is weighted by events, so busy functions dominate. The per-function mean gives every
          function equal weight. Click a column to sort.
        </p>
      </section>
    </div>
  );
}
