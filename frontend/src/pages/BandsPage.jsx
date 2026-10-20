import { useMemo, useState } from "react";
import { Bar, BarChart, CartesianGrid, Legend, ResponsiveContainer, Tooltip, XAxis, YAxis } from "recharts";
import { BANDS, DEFAULT_BAND_PICKS, PICK_COLORS, int, pct, legendText } from "../lib/policies.jsx";

const MAX_PICKS = 4;

function BandTip({ active, payload, label, format }) {
  if (!active || !payload || !payload.length) return null;
  return (
    <div className="tip">
      <div className="tip-title">Band {label} events</div>
      {payload.map((p) => (
        <div key={p.dataKey}>
          <span className="swatch" style={{ background: p.color }} /> {p.dataKey}: {format(p.value)}
        </div>
      ))}
    </div>
  );
}

export default function BandsPage({ rows }) {
  const names = rows.map((r) => r.policy);
  const [picks, setPicks] = useState(() => {
    const d = DEFAULT_BAND_PICKS.filter((p) => names.includes(p));
    return d.length ? d : names.slice(0, MAX_PICKS);
  });
  const [toAdd, setToAdd] = useState("");

  const byName = useMemo(() => Object.fromEntries(rows.map((r) => [r.policy, r])), [rows]);
  const active = picks.filter((p) => byName[p]);

  // Colors follow the policy's slot, so removing one policy does not repaint the others.
  const colorOf = (p) => PICK_COLORS[picks.indexOf(p) % PICK_COLORS.length];

  const coldData = BANDS.map((b) => {
    const row = { band: b };
    active.forEach((p) => (row[p] = (byName[p].bandMeanFunctionColdStartRate?.[b] ?? null)));
    return row;
  });
  const idleData = BANDS.map((b) => {
    const row = { band: b };
    active.forEach((p) => (row[p] = byName[p].bandIdleWarmMinutes?.[b] ?? null));
    return row;
  });

  function remove(p) {
    setPicks(picks.filter((x) => x !== p));
  }
  function add() {
    if (!toAdd || picks.includes(toAdd) || picks.length >= MAX_PICKS) return;
    setPicks([...picks, toAdd]);
    setToAdd("");
  }

  const chartProps = { margin: { top: 10, right: 16, bottom: 24, left: 8 }, barGap: 2, barCategoryGap: "22%" };
  const axis = { stroke: "#8a93b2", tick: { fontSize: 12 } };

  return (
    <div className="page" id="bands-page">
      <section className="card">
        <div className="card-head">
          <div>
            <h2>Policies to compare</h2>
            <p className="muted small">Pick up to {MAX_PICKS}. Bands group functions by how many events they have on the scored days.</p>
          </div>
        </div>
        <div className="chips" id="band-picks">
          {active.map((p) => (
            <span key={p} className="chip">
              <span className="swatch" style={{ background: colorOf(p) }} />
              {p}
              <button className="chip-x" id={`remove-${p}`} aria-label={`Remove ${p}`} onClick={() => remove(p)}>
                ×
              </button>
            </span>
          ))}
          {picks.length < MAX_PICKS && (
            <span className="inline-field">
              <select id="band-add-select" value={toAdd} onChange={(e) => setToAdd(e.target.value)}>
                <option value="">Add a policy…</option>
                {names
                  .filter((n) => !picks.includes(n))
                  .map((n) => (
                    <option key={n} value={n}>
                      {n}
                    </option>
                  ))}
              </select>
              <button id="band-add" className="btn btn-primary" onClick={add} disabled={!toAdd}>
                Add
              </button>
            </span>
          )}
        </div>
      </section>

      <div className="grid-2">
        <section className="card">
          <h2>Per-function mean cold-start rate</h2>
          <p className="muted small">Average over the functions in each band. Lower is better.</p>
          <div className="chart-box" id="band-cold-chart">
            <ResponsiveContainer width="100%" height={300}>
              <BarChart data={coldData} {...chartProps}>
                <CartesianGrid stroke="#2a3352" strokeDasharray="3 3" vertical={false} />
                <XAxis dataKey="band" {...axis} label={{ value: "Events per function", position: "insideBottom", offset: -14, fill: "#8a93b2", fontSize: 12 }} />
                <YAxis {...axis} tickFormatter={(v) => `${Math.round(v * 100)}%`} width={44} />
                <Tooltip content={<BandTip format={(v) => pct(v, 2)} />} cursor={{ fill: "#232b47" }} />
                <Legend verticalAlign="top" height={30} iconSize={10} wrapperStyle={{ fontSize: 12 }} formatter={legendText} />
                {active.map((p) => (
                  <Bar key={p} dataKey={p} fill={colorOf(p)} radius={[4, 4, 0, 0]} isAnimationActive={false} />
                ))}
              </BarChart>
            </ResponsiveContainer>
          </div>
        </section>

        <section className="card">
          <h2>Idle warm-minutes</h2>
          <p className="muted small">Minutes kept warm with no call, per band. Lower is cheaper.</p>
          <div className="chart-box" id="band-idle-chart">
            <ResponsiveContainer width="100%" height={300}>
              <BarChart data={idleData} {...chartProps}>
                <CartesianGrid stroke="#2a3352" strokeDasharray="3 3" vertical={false} />
                <XAxis dataKey="band" {...axis} label={{ value: "Events per function", position: "insideBottom", offset: -14, fill: "#8a93b2", fontSize: 12 }} />
                <YAxis {...axis} tickFormatter={(v) => (v >= 1000 ? `${Math.round(v / 1000)}k` : v)} width={48} />
                <Tooltip content={<BandTip format={int} />} cursor={{ fill: "#232b47" }} />
                <Legend verticalAlign="top" height={30} iconSize={10} wrapperStyle={{ fontSize: 12 }} formatter={legendText} />
                {active.map((p) => (
                  <Bar key={p} dataKey={p} fill={colorOf(p)} radius={[4, 4, 0, 0]} isAnimationActive={false} />
                ))}
              </BarChart>
            </ResponsiveContainer>
          </div>
        </section>
      </div>

      <section className="card">
        <h2>Table view</h2>
        <div className="table-wrap">
          <table id="bands-table" className="table">
            <thead>
              <tr>
                <th>Policy</th>
                {BANDS.map((b) => (
                  <th key={b} className="num">
                    {b} cold
                  </th>
                ))}
                {BANDS.map((b) => (
                  <th key={"i" + b} className="num">
                    {b} idle
                  </th>
                ))}
              </tr>
            </thead>
            <tbody>
              {active.map((p) => (
                <tr key={p}>
                  <td>
                    <span className="swatch" style={{ background: colorOf(p) }} /> {p}
                  </td>
                  {BANDS.map((b) => (
                    <td key={b} className="num">
                      {pct(byName[p].bandMeanFunctionColdStartRate?.[b], 1)}
                    </td>
                  ))}
                  {BANDS.map((b) => (
                    <td key={"i" + b} className="num">
                      {int(byName[p].bandIdleWarmMinutes?.[b])}
                    </td>
                  ))}
                </tr>
              ))}
            </tbody>
          </table>
        </div>
        <p className="muted small">
          The 1-9 band stays near 95% cold under every policy: these functions are called too rarely for any
          keep-alive to help, which is a known limitation.
        </p>
      </section>
    </div>
  );
}
