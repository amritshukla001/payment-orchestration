import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { AgGridReact } from "ag-grid-react";
import {
  ModuleRegistry,
  AllCommunityModule,
  themeQuartz,
  colorSchemeDark,
} from "ag-grid-community";
import { fetchSagas, fetchLedgerEntries, fetchNotifications } from "./api.js";
import { stateMeta } from "./states.js";
import PaymentDrawer from "./PaymentDrawer.jsx";
import "./App.css";

ModuleRegistry.registerModules([AllCommunityModule]);

const gridTheme = themeQuartz.withPart(colorSchemeDark).withParams({
  accentColor: "#3fd0c9",
  backgroundColor: "#12161c",
  foregroundColor: "#dbe4ea",
  chromeBackgroundColor: "#171c24",
  borderColor: "#232b36",
  headerFontWeight: 600,
  fontFamily: [
    "-apple-system",
    "BlinkMacSystemFont",
    "Segoe UI",
    "sans-serif",
  ],
  dataFontSize: 13,
  headerFontSize: 12,
  rowHoverColor: "#1c2530",
  selectedRowBackgroundColor: "#1a3634",
});

const REFRESH_INTERVAL_MS = 3000;

function centsToAmount(cents, currency) {
  return new Intl.NumberFormat("en-US", {
    style: "currency",
    currency,
    minimumFractionDigits: 2,
  }).format(cents / 100);
}

function StateBadge({ state }) {
  const meta = stateMeta(state);
  return <span className={`badge badge--${meta.tone}`}>{meta.label}</span>;
}

function shortId(id) {
  return id ? `${id.slice(0, 8)}…` : "";
}

export default function App() {
  const [sagas, setSagas] = useState([]);
  const [error, setError] = useState(null);
  const [lastRefreshed, setLastRefreshed] = useState(null);
  const [selected, setSelected] = useState(null);
  const gridRef = useRef(null);

  const load = useCallback(async () => {
    try {
      const data = await fetchSagas();
      setSagas(data);
      setError(null);
      setLastRefreshed(new Date());
    } catch (e) {
      setError(e.message);
    }
  }, []);

  useEffect(() => {
    load();
    const id = setInterval(load, REFRESH_INTERVAL_MS);
    return () => clearInterval(id);
  }, [load]);

  const summary = useMemo(() => {
    const counts = {};
    for (const s of sagas) counts[s.state] = (counts[s.state] ?? 0) + 1;
    return counts;
  }, [sagas]);

  const columnDefs = useMemo(
    () => [
      {
        headerName: "Payment ID",
        field: "paymentId",
        valueFormatter: (p) => shortId(p.value),
        tooltipField: "paymentId",
        width: 130,
        cellClass: "mono",
      },
      {
        headerName: "Payer",
        field: "payerAccount",
        valueFormatter: (p) => shortId(p.value),
        tooltipField: "payerAccount",
        width: 110,
        cellClass: "mono",
      },
      {
        headerName: "Payee",
        field: "payeeAccount",
        valueFormatter: (p) => shortId(p.value),
        tooltipField: "payeeAccount",
        width: 110,
        cellClass: "mono",
      },
      {
        headerName: "Amount",
        field: "amountCents",
        valueFormatter: (p) => centsToAmount(p.value, p.data.currency),
        cellClass: "mono numeric",
        width: 130,
      },
      {
        headerName: "State",
        field: "state",
        cellRenderer: (p) => <StateBadge state={p.value} />,
        width: 150,
        sortable: true,
      },
      {
        headerName: "Updated",
        field: "updatedAt",
        valueFormatter: (p) => new Date(p.value).toLocaleString(),
        cellClass: "mono",
        flex: 1,
        sort: "desc",
      },
    ],
    [],
  );

  const openDetail = useCallback(async (saga) => {
    setSelected({ saga, ledger: null, notifications: null, error: null });
    try {
      const [ledger, notifications] = await Promise.all([
        fetchLedgerEntries(saga.paymentId),
        fetchNotifications(saga.paymentId),
      ]);
      setSelected({ saga, ledger, notifications, error: null });
    } catch (e) {
      setSelected({ saga, ledger: [], notifications: [], error: e.message });
    }
  }, []);

  return (
    <div className="console">
      <header className="console__header">
        <div>
          <h1>PayFlow Ops Console</h1>
          <p className="console__subtitle">
            Live view over the saga-orchestrator, ledger-service and
            notification-service read APIs — no separate query database,
            straight from each service's own Postgres.
          </p>
        </div>
        <div className="console__meta">
          {error ? (
            <span className="status status--error">
              Cannot reach a service — {error}
            </span>
          ) : (
            <span className="status status--ok">
              {sagas.length} payments tracked
            </span>
          )}
          <span className="console__refreshed">
            {lastRefreshed
              ? `Updated ${lastRefreshed.toLocaleTimeString()}`
              : "Loading…"}
          </span>
        </div>
      </header>

      <div className="summary-strip">
        {Object.entries(summary).map(([state, count]) => (
          <div key={state} className="summary-strip__item">
            <StateBadge state={state} />
            <span className="summary-strip__count">{count}</span>
          </div>
        ))}
      </div>

      <div className="grid-shell">
        <AgGridReact
          ref={gridRef}
          theme={gridTheme}
          rowData={sagas}
          columnDefs={columnDefs}
          getRowId={(p) => p.data.paymentId}
          onRowClicked={(e) => openDetail(e.data)}
          rowSelection={{ mode: "singleRow" }}
          animateRows={true}
          tooltipShowDelay={200}
        />
      </div>

      {selected && (
        <PaymentDrawer state={selected} onClose={() => setSelected(null)} />
      )}
    </div>
  );
}
