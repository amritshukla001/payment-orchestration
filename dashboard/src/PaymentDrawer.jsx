import { useState } from "react";
import { stateMeta } from "./states.js";
import { fetchPaymentEngineSummary } from "./api.js";

function centsToAmount(cents, currency) {
  return new Intl.NumberFormat("en-US", {
    style: "currency",
    currency,
    minimumFractionDigits: 2,
  }).format(cents / 100);
}

function PostingBadge({ type }) {
  const tone =
    type === "REVERSAL" ? "reversed" : type === "FINAL" ? "success" : "inflight";
  return <span className={`badge badge--${tone}`}>{type}</span>;
}

function OutcomeBadge({ outcome }) {
  const tone =
    outcome === "SUCCESS" ? "success" : outcome === "REVERSED" ? "reversed" : "danger";
  return <span className={`badge badge--${tone}`}>{outcome}</span>;
}

// Only offered for COMPENSATED payments -- an on-demand, human-triggered
// LLM call that explains the payment's history; it never decides anything.
// The source badge is honest about which path produced the text: the
// Claude API, or the server's deterministic fallback template.
function IncidentSummary({ paymentId }) {
  const [state, setState] = useState({ status: "idle", summary: null, error: null });

  const generate = async () => {
    setState({ status: "loading", summary: null, error: null });
    try {
      const summary = await fetchPaymentEngineSummary(paymentId);
      setState({ status: "done", summary, error: null });
    } catch (e) {
      setState({ status: "error", summary: null, error: e.message });
    }
  };

  return (
    <section className="drawer__section">
      <h3>Incident summary</h3>
      {state.status === "idle" && (
        <button className="drawer__generate" onClick={generate}>
          Generate summary
        </button>
      )}
      {state.status === "loading" && <p className="drawer__empty">Generating…</p>}
      {state.status === "error" && <p className="status status--error">{state.error}</p>}
      {state.status === "done" && (
        <div className="drawer__summary">
          <span
            className={`badge badge--${state.summary.source === "AI" ? "inflight" : "warning"}`}
          >
            {state.summary.source === "AI" ? "AI" : "FALLBACK"}
          </span>
          <p className="drawer__summary-text">{state.summary.summary}</p>
        </div>
      )}
    </section>
  );
}

export default function PaymentDrawer({ state, onClose }) {
  const { payment, ledger, notifications, error } = state;
  const loading = ledger === null;

  return (
    <div className="drawer-backdrop" onClick={onClose}>
      <aside className="drawer" onClick={(e) => e.stopPropagation()}>
        <div className="drawer__header">
          <div>
            <h2>Payment detail</h2>
            <p className="mono drawer__id">{payment.paymentId}</p>
          </div>
          <button className="drawer__close" onClick={onClose} aria-label="Close">
            ×
          </button>
        </div>

        <dl className="drawer__facts">
          <div>
            <dt>State</dt>
            <dd>
              <span className={`badge badge--${stateMeta(payment.state).tone}`}>
                {stateMeta(payment.state).label}
              </span>
            </dd>
          </div>
          <div>
            <dt>Amount</dt>
            <dd className="mono">{centsToAmount(payment.amountCents, payment.currency)}</dd>
          </div>
          <div>
            <dt>Payer account</dt>
            <dd className="mono">{payment.payerAccount}</dd>
          </div>
          <div>
            <dt>Payee account</dt>
            <dd className="mono">{payment.payeeAccount}</dd>
          </div>
        </dl>

        {error && <p className="status status--error">{error}</p>}

        <section className="drawer__section">
          <h3>Ledger postings</h3>
          {loading ? (
            <p className="drawer__empty">Loading…</p>
          ) : ledger.length === 0 ? (
            <p className="drawer__empty">No postings yet.</p>
          ) : (
            <table className="drawer__table">
              <thead>
                <tr>
                  <th>Type</th>
                  <th>Debit</th>
                  <th>Credit</th>
                  <th className="numeric">Amount</th>
                  <th>Posted</th>
                </tr>
              </thead>
              <tbody>
                {ledger.map((entry) => (
                  <tr key={entry.id}>
                    <td>
                      <PostingBadge type={entry.postingType} />
                    </td>
                    <td className="mono">{entry.debitAccount.slice(0, 8)}…</td>
                    <td className="mono">{entry.creditAccount.slice(0, 8)}…</td>
                    <td className="mono numeric">
                      {centsToAmount(entry.amountCents, payment.currency)}
                    </td>
                    <td className="mono">
                      {new Date(entry.postedAt).toLocaleTimeString()}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </section>

        {payment.state === "COMPENSATED" && <IncidentSummary paymentId={payment.paymentId} />}

        <section className="drawer__section">
          <h3>Notifications</h3>
          {loading ? (
            <p className="drawer__empty">Loading…</p>
          ) : notifications.length === 0 ? (
            <p className="drawer__empty">No notifications sent yet.</p>
          ) : (
            <ul className="drawer__notifications">
              {notifications.map((n) => (
                <li key={n.id}>
                  <div className="drawer__notification-head">
                    <span className="drawer__recipient">{n.recipient}</span>
                    <OutcomeBadge outcome={n.outcome} />
                    <span className="mono drawer__notification-time">
                      {new Date(n.sentAt).toLocaleTimeString()}
                    </span>
                  </div>
                  <p className="drawer__notification-message">{n.message}</p>
                </li>
              ))}
            </ul>
          )}
        </section>
      </aside>
    </div>
  );
}
