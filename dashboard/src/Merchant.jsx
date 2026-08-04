import { useCallback, useEffect, useState } from "react";
import { fetchAnalyticsSummary } from "./api.js";
import "./merchant.css";

// The third view: a business-owner-facing summary, not a row-by-row grid
// (that's the ops console) and not a place to pay (that's checkout).
// Backed by real GROUP BY aggregation against read-model-service's CQRS
// projection -- see PaymentViewRepository's countByState/aggregateByMethod/
// dailyVolumeSince and PaymentViewController's /analytics/summary endpoint.

// Mirrors App.jsx's METHOD_ICONS -- each view keeps its own small copy
// rather than sharing a util module, matching this dashboard's existing
// convention (Checkout.jsx does the same for its own METHODS icons).
const METHOD_ICONS = { CARD: "💳", UPI: "📱", NETBANKING: "🏦" };
const METHOD_LABELS = { CARD: "Card", UPI: "UPI", NETBANKING: "Net Banking" };

const REFRESH_INTERVAL_MS = 10000; // Slower than the grid's 3s -- aggregate stats don't need sub-second freshness.
const DAYS = 7;

function centsToAmount(cents) {
  return new Intl.NumberFormat("en-US", {
    style: "currency",
    currency: "USD",
    minimumFractionDigits: 2,
  }).format(cents / 100);
}

function formatPercent(part, whole) {
  if (!whole) return "0%";
  return `${Math.round((part / whole) * 100)}%`;
}

function KpiCard({ label, value, sub }) {
  return (
    <div className="merchant__kpi">
      <div className="merchant__kpi-label">{label}</div>
      <div className="merchant__kpi-value">{value}</div>
      {sub && <div className="merchant__kpi-sub">{sub}</div>}
    </div>
  );
}

function MethodBreakdown({ byMethod, totalPayments }) {
  if (byMethod.length === 0) {
    return <p className="merchant__empty">No payments yet.</p>;
  }
  return (
    <div className="merchant__methods">
      {byMethod.map((m) => (
        <div key={m.method} className="merchant__method-row">
          <span className="merchant__method-label">
            {METHOD_ICONS[m.method] ?? ""} {METHOD_LABELS[m.method] ?? m.method}
          </span>
          <div className="merchant__method-bar">
            <div
              className="merchant__method-bar-fill"
              style={{ width: formatPercent(m.count, totalPayments) }}
            />
          </div>
          <span className="merchant__method-pct mono">{formatPercent(m.count, totalPayments)}</span>
        </div>
      ))}
    </div>
  );
}

function DailyVolumeChart({ dailyVolume }) {
  if (dailyVolume.length === 0) {
    return <p className="merchant__empty">No payments in the last {DAYS} days.</p>;
  }
  const maxCount = Math.max(...dailyVolume.map((d) => d.count));
  return (
    <div className="merchant__chart">
      {dailyVolume.map((d) => (
        <div key={d.date} className="merchant__chart-col" title={`${d.date}: ${d.count} payment(s), ${centsToAmount(d.amountCents)}`}>
          <div
            className="merchant__chart-bar"
            style={{ height: `${Math.max(4, (d.count / maxCount) * 100)}%` }}
          />
          <span className="merchant__chart-label">
            {new Date(d.date).toLocaleDateString("en-US", { weekday: "short" })}
          </span>
        </div>
      ))}
    </div>
  );
}

export default function Merchant() {
  const [summary, setSummary] = useState(null);
  const [error, setError] = useState(null);
  const [lastRefreshed, setLastRefreshed] = useState(null);

  const load = useCallback(async () => {
    try {
      const data = await fetchAnalyticsSummary(DAYS);
      setSummary(data);
      setError(null);
      setLastRefreshed(new Date());
    } catch (e) {
      setError(e.message);
    }
  }, []);

  useEffect(() => {
    document.title = "PayFlow Merchant Analytics";
    load();
    const id = setInterval(load, REFRESH_INTERVAL_MS);
    return () => clearInterval(id);
  }, [load]);

  const completed = summary ? summary.settledCount + summary.failedCount + summary.compensatedCount : 0;

  return (
    <div className="merchant">
      <header className="merchant__header">
        <div>
          <h1>Merchant Analytics</h1>
          <p className="merchant__subtitle">
            Aggregated from the same read-model-service CQRS projection the ops console's grid
            uses -- GROUP BY queries, not a row-by-row scan.
          </p>
        </div>
        <div className="merchant__meta">
          {error ? (
            <span className="merchant__status merchant__status--error">Cannot reach a service — {error}</span>
          ) : (
            <span className="merchant__status merchant__status--ok">
              {summary ? `${summary.totalPayments} payments tracked` : "Loading…"}
            </span>
          )}
          <span className="merchant__refreshed">
            {lastRefreshed ? `Updated ${lastRefreshed.toLocaleTimeString()}` : ""}
          </span>
          <a className="merchant__back-link" href="/">
            ← Back to ops console
          </a>
        </div>
      </header>

      {summary && (
        <>
          <div className="merchant__kpis">
            <KpiCard label="Total payments" value={summary.totalPayments} />
            <KpiCard label="Settled volume" value={centsToAmount(summary.settledAmountCents)} />
            <KpiCard
              label="Settled rate"
              value={formatPercent(summary.settledCount, completed)}
              sub={`${summary.settledCount} of ${completed} completed`}
            />
            <KpiCard
              label="Reversed rate"
              value={formatPercent(summary.compensatedCount, completed)}
              sub={`${summary.compensatedCount} compensated`}
            />
            <KpiCard
              label="Failed rate"
              value={formatPercent(summary.failedCount, completed)}
              sub={`${summary.failedCount} failed`}
            />
          </div>

          <div className="merchant__panels">
            <section className="merchant__panel">
              <h2>By payment method</h2>
              <MethodBreakdown byMethod={summary.byMethod} totalPayments={summary.totalPayments} />
            </section>

            <section className="merchant__panel">
              <h2>Volume, last {DAYS} days</h2>
              <DailyVolumeChart dailyVolume={summary.dailyVolume} />
            </section>
          </div>
        </>
      )}
    </div>
  );
}
