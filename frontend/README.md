# Keep-Alive Scheduler Dashboard

React + Vite + Recharts dashboard for the Spring Boot simulator.

Pages (hash URLs, handy for Selenium):
- `http://localhost:5173/#/results`: scatter of cold-start % vs idle warm-minutes, KPI tiles, sortable table
- `http://localhost:5173/#/bands`: per-band bar charts for up to 4 policies
- `http://localhost:5173/#/run`: form that calls `/api/simulate/fixed`, `/ewma`, `/percentile`

The dashboard opens with the saved days 6-7 results built in (`src/data/savedResults.js`), so it works even when the
backend is off. "Load live from backend" calls `GET /api/simulate/summary` (30-90 s on the first call).

## Run (Windows PowerShell)

```powershell
cd frontend
npm install
npm run dev
```

Then open http://localhost:5173. The backend must have `CorsConfig.java` and run on port 8080.

## Settings
- Backend address: copy `.env.example` to `.env` and change `VITE_API_BASE`.
- Query parameter names for the Run page: `ENDPOINTS` in `src/api.js`.
- Serif font: change `--font` at the top of `src/styles.css` to `Georgia, "Times New Roman", serif;`.

## Stable element ids (for Selenium)
`nav-results`, `nav-bands`, `nav-run`, `load-live`, `summary-loading`, `summary-error`, `kpi-gate-cold`,
`results-chart`, `zoom-toggle`, `results-table`, `family-filter`, `sort-coldStartRate`, `band-cold-chart`,
`band-idle-chart`, `band-add-select`, `band-add`, `run-form`, `run-policy-type`, `input-minutes`, `input-alpha`,
`input-multiplier`, `input-p`, `input-max`, `run-submit`, `run-loading`, `run-error`, `run-result`, `result-cold`,
`result-idle`, `run-history`.
