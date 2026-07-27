import { useEffect, useMemo, useState } from "react";
import { createPayment, pollUntilTerminal } from "./api.js";
import "./checkout.css";

// The customer-facing counterpart to the ops console: what someone
// actually paying would see, not what an engineer watching Kafka would.
// Deliberately no fake card-number/CVV fields -- this project models
// accounts as opaque UUIDs throughout (see the root README), and building
// a realistic-looking card entry form for a demo with no real card network
// behind it would be dishonest about what's actually happening.

// Reuses the same demo payee the root README's "Try it" walkthrough uses,
// so a payment made here shows up with a recognizable account if you go
// looking for it in the ops console or via psql.
const MERCHANT_ACCOUNT = "22222222-2222-2222-2222-222222222222";
const MERCHANT_NAME = "The Corner Bookstore";
const CUSTOMER_ID_KEY = "payflow_customer_id";

const PRESETS = [
  { label: "$25.00", cents: 2500, note: null },
  { label: "$9,500.00", cents: 950000, note: "triggers a compensation demo" },
  { label: "$15,000.00", cents: 1500000, note: "triggers a fraud decline" },
];

const METHODS = [
  { value: "CARD", label: "Card", icon: "💳" },
  { value: "UPI", label: "UPI", icon: "📱" },
  { value: "NETBANKING", label: "Net Banking", icon: "🏦" },
];

const STEPS = [
  { state: "INITIATED", label: "Payment received" },
  { state: "COMPLIANCE_CHECKED", label: "Compliance verified" },
  { state: "FRAUD_CHECKED", label: "Fraud check passed" },
  { state: "AUTHORIZED", label: "Funds authorized" },
  { state: "LEDGER_POSTED", label: "Recorded to ledger" },
  { state: "SETTLED", label: "Payment settled" },
];

function getCustomerId() {
  let id = localStorage.getItem(CUSTOMER_ID_KEY);
  if (!id) {
    id = crypto.randomUUID();
    localStorage.setItem(CUSTOMER_ID_KEY, id);
  }
  return id;
}

function centsToDisplay(cents) {
  return (cents / 100).toLocaleString("en-US", { style: "currency", currency: "USD" });
}

function Stepper({ currentState }) {
  const currentIndex = STEPS.findIndex((s) => s.state === currentState);
  return (
    <ul className="checkout__stepper">
      {STEPS.map((step, i) => {
        const status = currentIndex < 0 ? "pending" : i < currentIndex ? "done" : i === currentIndex ? "active" : "pending";
        return (
          <li key={step.state} className={`checkout__step checkout__step--${status}`}>
            <span className="checkout__step-dot">{status === "done" ? "✓" : i + 1}</span>
            <span className="checkout__step-label">{step.label}</span>
          </li>
        );
      })}
    </ul>
  );
}

// notification-service's message text always leads with "Your payment
// {uuid} failed: " -- redundant here since the id is already shown on its
// own line below, so strip it back to just the reason.
function reasonOnly(message) {
  return message?.replace(/^Your payment [0-9a-f-]+ failed: /i, "");
}

function Outcome({ detail, onReset }) {
  const state = detail.payment.state;
  const failureNote = detail.notifications?.find((n) => n.outcome === "FAILURE" || n.outcome === "REVERSED");

  if (state === "SETTLED") {
    return (
      <div className="checkout__outcome checkout__outcome--success">
        <div className="checkout__outcome-icon">✓</div>
        <h2>Payment successful</h2>
        <p>{centsToDisplay(detail.payment.amountCents)} to {MERCHANT_NAME}</p>
        <p className="checkout__txn mono">{detail.payment.paymentId}</p>
        <button className="checkout__button" onClick={onReset}>Make another payment</button>
      </div>
    );
  }
  if (state === "COMPENSATED") {
    return (
      <div className="checkout__outcome checkout__outcome--reversed">
        <div className="checkout__outcome-icon">↺</div>
        <h2>Payment reversed</h2>
        <p>Your bank declined the capture after funds were held -- the full amount has been returned to you.</p>
        <p className="checkout__txn mono">{detail.payment.paymentId}</p>
        <button className="checkout__button" onClick={onReset}>Try again</button>
      </div>
    );
  }
  return (
    <div className="checkout__outcome checkout__outcome--failed">
      <div className="checkout__outcome-icon">✕</div>
      <h2>Payment failed</h2>
      <p>{reasonOnly(failureNote?.message) ?? "This payment could not be completed."}</p>
      <p className="checkout__txn mono">{detail.payment.paymentId}</p>
      <button className="checkout__button" onClick={onReset}>Try again</button>
    </div>
  );
}

export default function Checkout() {
  const customerId = useMemo(getCustomerId, []);
  const [amountCents, setAmountCents] = useState(2500);
  const [method, setMethod] = useState("CARD");
  const [phase, setPhase] = useState("idle"); // idle | processing | done
  const [liveState, setLiveState] = useState("INITIATED");
  const [detail, setDetail] = useState(null);
  const [error, setError] = useState(null);

  useEffect(() => {
    document.title = "PayFlow Checkout";
  }, []);

  const pay = async (e) => {
    e.preventDefault();
    setError(null);
    setPhase("processing");
    setLiveState("INITIATED");
    try {
      const payment = await createPayment({
        payerAccount: customerId,
        payeeAccount: MERCHANT_ACCOUNT,
        amountCents,
        currency: "USD",
        paymentMethod: method,
      });
      const final = await pollUntilTerminal(payment.id, {
        onUpdate: (d) => setLiveState(d.payment.state),
      });
      setDetail(final);
      setPhase("done");
    } catch (err) {
      setError(err.message);
      setPhase("idle");
    }
  };

  const reset = () => {
    setPhase("idle");
    setDetail(null);
    setError(null);
  };

  return (
    <div className="checkout">
      <div className="checkout__card">
        <div className="checkout__brand">
          <span className="checkout__lock">🔒</span> PayFlow Secure Checkout
        </div>

        {phase === "idle" && (
          <form onSubmit={pay}>
            <div className="checkout__merchant">
              <div className="checkout__merchant-avatar">📚</div>
              <div>
                <div className="checkout__merchant-name">{MERCHANT_NAME}</div>
                <div className="checkout__merchant-sub">Demo merchant · payment-orchestration</div>
              </div>
            </div>

            <label className="checkout__label">Amount</label>
            <div className="checkout__amount-input">
              <span>$</span>
              <input
                type="number"
                min="1"
                step="0.01"
                value={(amountCents / 100).toFixed(2)}
                onChange={(e) => setAmountCents(Math.round(Number(e.target.value) * 100))}
              />
            </div>
            <div className="checkout__presets">
              {PRESETS.map((p) => (
                <button
                  type="button"
                  key={p.label}
                  className={`checkout__chip ${amountCents === p.cents ? "checkout__chip--active" : ""}`}
                  title={p.note ?? undefined}
                  onClick={() => setAmountCents(p.cents)}
                >
                  {p.label}
                </button>
              ))}
            </div>

            <label className="checkout__label">Payment method</label>
            <div className="checkout__methods">
              {METHODS.map((m) => (
                <button
                  type="button"
                  key={m.value}
                  className={`checkout__method ${method === m.value ? "checkout__method--active" : ""}`}
                  onClick={() => setMethod(m.value)}
                >
                  <span className="checkout__method-icon">{m.icon}</span>
                  {m.label}
                </button>
              ))}
            </div>

            <button type="submit" className="checkout__button">
              Pay {centsToDisplay(amountCents)}
            </button>
            {error && <p className="checkout__error">{error}</p>}

            <p className="checkout__customer">
              Paying as customer <span className="mono">{customerId.slice(0, 8)}…</span>
            </p>
          </form>
        )}

        {phase === "processing" && (
          <div className="checkout__processing">
            <h2>Processing your payment…</h2>
            <Stepper currentState={liveState} />
          </div>
        )}

        {phase === "done" && <Outcome detail={detail} onReset={reset} />}

        <div className="checkout__footer">
          <a href="/">← Back to ops console</a>
          <span>Demo project — not a real payment processor</span>
        </div>
      </div>
    </div>
  );
}
