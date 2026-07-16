import { stateMeta } from "./states.js";

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

export default function PaymentDrawer({ state, onClose }) {
  const { saga, ledger, notifications, error } = state;
  const loading = ledger === null;

  return (
    <div className="drawer-backdrop" onClick={onClose}>
      <aside className="drawer" onClick={(e) => e.stopPropagation()}>
        <div className="drawer__header">
          <div>
            <h2>Payment detail</h2>
            <p className="mono drawer__id">{saga.paymentId}</p>
          </div>
          <button className="drawer__close" onClick={onClose} aria-label="Close">
            ×
          </button>
        </div>

        <dl className="drawer__facts">
          <div>
            <dt>State</dt>
            <dd>
              <span className={`badge badge--${stateMeta(saga.state).tone}`}>
                {stateMeta(saga.state).label}
              </span>
            </dd>
          </div>
          <div>
            <dt>Amount</dt>
            <dd className="mono">{centsToAmount(saga.amountCents, saga.currency)}</dd>
          </div>
          <div>
            <dt>Payer account</dt>
            <dd className="mono">{saga.payerAccount}</dd>
          </div>
          <div>
            <dt>Payee account</dt>
            <dd className="mono">{saga.payeeAccount}</dd>
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
                      {centsToAmount(entry.amountCents, saga.currency)}
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
