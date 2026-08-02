import { useEffect, useState } from "react";
import { loadStripe } from "@stripe/stripe-js";
import { CardElement, Elements, useElements, useStripe } from "@stripe/react-stripe-js";
import { confirmStepUp, createPayment, declineStepUp, pollUntilTerminal } from "./api.js";
import "./checkout.css";

// The customer-facing counterpart to the ops console: what someone
// actually paying would see, not what an engineer watching Kafka would.
//
// CARD payments are tokenized client-side via Stripe Elements when a
// publishable key is configured -- real card entry, real Stripe test-mode
// validation, but raw card data never reaches this app's own backend (see
// payment-api's StripeCardTokenVerifier). Without a key configured (the
// default for local dev/CI without a Stripe account), CARD falls back to
// exactly what this page has always done: no card fields at all, since a
// fake-looking card form with nothing real behind it would be dishonest
// about what's actually happening -- this project models accounts as
// opaque UUIDs throughout (see the root README).
const STRIPE_PUBLISHABLE_KEY = import.meta.env.VITE_STRIPE_PUBLISHABLE_KEY;
const stripePromise = STRIPE_PUBLISHABLE_KEY ? loadStripe(STRIPE_PUBLISHABLE_KEY) : null;

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

// Each method's note describes real backend behavior, not decoration.
// CARD is tokenized via Stripe (when configured) and always pauses for an
// explicit step-up confirmation (see PaymentEngineTransitions.requireStepUp
// / StepUpController) before funds are authorized. UPI only clears a payee
// explicitly registered as a UPI recipient (compliance-service's
// UpiDirectoryRule). NETBANKING resolves the payer's account to one of
// five mock banks and fails if that bank's gateway has been marked down
// (funds-auth-service's NetBankingAvailabilityRule) -- see the ops
// console's Demo controls panel for both the UPI-registration and
// bank-outage levers. CARD's note is computed once from stripePromise
// (static for the page's lifetime, since it only depends on an env var)
// rather than living in this array, so it can't overclaim tokenization
// that isn't actually happening when no Stripe key is configured.
const CARD_NOTE = stripePromise
  ? "Tokenized via Stripe (test mode) before authorization -- your card number never reaches our servers. Also pauses for a step-up confirmation, same as any card payment."
  : "No Stripe key configured in this environment, so card details aren't collected -- still pauses for a step-up confirmation before funds are authorized.";

const METHODS = [
  { value: "CARD", label: "Card", icon: "💳", note: CARD_NOTE },
  { value: "UPI", label: "UPI", icon: "📱", note: "Only clears if the payee is a registered UPI recipient -- see compliance-service's UPI directory rule." },
  { value: "NETBANKING", label: "Net Banking", icon: "🏦", note: "Fails if your account's bank gateway is down for maintenance -- see funds-auth-service's bank-availability rule." },
];

const STEPS = [
  { state: "INITIATED", label: "Payment received" },
  { state: "COMPLIANCE_CHECKED", label: "Compliance verified" },
  { state: "FRAUD_CHECKED", label: "Fraud check passed" },
  { state: "AWAITING_STEP_UP", label: "Card verification" },
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

const UUID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

// Editable rather than fixed -- paste in an account you already flagged
// for KYC review or registered for UPI via the ops console's Demo
// controls panel, to see that decision from the paying customer's side
// instead of the engineer's.
function CustomerPicker({ customerId, onChange }) {
  const [editing, setEditing] = useState(false);
  const [draft, setDraft] = useState(customerId);

  if (!editing) {
    return (
      <p className="checkout__customer">
        Paying as customer <span className="mono">{customerId.slice(0, 8)}…</span>{" "}
        <button type="button" className="checkout__link" onClick={() => { setDraft(customerId); setEditing(true); }}>
          switch
        </button>
      </p>
    );
  }

  const apply = () => {
    if (UUID_RE.test(draft)) {
      onChange(draft);
      setEditing(false);
    }
  };

  return (
    <div className="checkout__customer-edit">
      <input
        className="mono"
        value={draft}
        placeholder="00000000-0000-0000-0000-000000000000"
        onChange={(e) => setDraft(e.target.value.trim())}
      />
      <button
        type="button"
        className="checkout__link"
        onClick={() => {
          const fresh = crypto.randomUUID();
          setDraft(fresh);
          onChange(fresh);
        }}
      >
        new
      </button>
      <button type="button" className="checkout__link" onClick={apply}>
        use
      </button>
      <button type="button" className="checkout__link" onClick={() => setEditing(false)}>
        cancel
      </button>
      {!UUID_RE.test(draft) && draft.length > 0 && (
        <p className="checkout__error">Not a valid account ID (expects a UUID).</p>
      )}
    </div>
  );
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
  return (
    <Elements stripe={stripePromise}>
      <CheckoutForm />
    </Elements>
  );
}

function CheckoutForm() {
  const stripe = useStripe();
  const elements = useElements();
  const [customerId, setCustomerId] = useState(getCustomerId);
  const [amountCents, setAmountCents] = useState(2500);
  const [method, setMethod] = useState("CARD");
  const [phase, setPhase] = useState("idle"); // idle | processing | done
  const [liveState, setLiveState] = useState("INITIATED");
  const [paymentId, setPaymentId] = useState(null);
  const [stepUpBusy, setStepUpBusy] = useState(false);
  const [detail, setDetail] = useState(null);
  const [error, setError] = useState(null);

  useEffect(() => {
    document.title = "PayFlow Checkout";
  }, []);

  const changeCustomer = (id) => {
    setCustomerId(id);
    localStorage.setItem(CUSTOMER_ID_KEY, id);
  };

  const pay = async (e) => {
    e.preventDefault();
    setError(null);

    // Tokenize before flipping to the "processing" phase -- a declined or
    // incomplete card should look like a form error, not a failed payment
    // (no payment has been created yet at this point).
    let cardToken;
    if (method === "CARD" && stripePromise && stripe && elements) {
      const { error: stripeError, paymentMethod: stripePaymentMethod } = await stripe.createPaymentMethod({
        type: "card",
        card: elements.getElement(CardElement),
      });
      if (stripeError) {
        setError(stripeError.message);
        return;
      }
      cardToken = stripePaymentMethod.id;
    }

    setPhase("processing");
    setLiveState("INITIATED");
    try {
      const payment = await createPayment({
        payerAccount: customerId,
        payeeAccount: MERCHANT_ACCOUNT,
        amountCents,
        currency: "USD",
        paymentMethod: method,
        ...(cardToken ? { cardToken } : {}),
      });
      setPaymentId(payment.id);
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

  // Only updates local "busy" state -- the actual outcome (moving past
  // AWAITING_STEP_UP, or landing on FAILED) arrives through pay()'s
  // still-running pollUntilTerminal loop, same as every other transition.
  const respondToStepUp = (action) => async () => {
    setStepUpBusy(true);
    try {
      await action(paymentId);
    } catch (err) {
      setError(err.message);
    } finally {
      setStepUpBusy(false);
    }
  };

  const reset = () => {
    setPhase("idle");
    setDetail(null);
    setError(null);
    setPaymentId(null);
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
            <p className="checkout__method-note">
              {METHODS.find((m) => m.value === method)?.note}
            </p>
            {method === "CARD" && stripePromise && (
              <div className="checkout__card-element">
                <CardElement
                  options={{
                    style: {
                      base: {
                        fontSize: "16px",
                        color: "#101828",
                        fontFamily: "inherit",
                        "::placeholder": { color: "#98a2b3" },
                      },
                    },
                  }}
                />
              </div>
            )}

            <button type="submit" className="checkout__button">
              Pay {centsToDisplay(amountCents)}
            </button>
            {error && <p className="checkout__error">{error}</p>}

            <CustomerPicker customerId={customerId} onChange={changeCustomer} />
          </form>
        )}

        {phase === "processing" && (
          <div className="checkout__processing">
            <h2>Processing your payment…</h2>
            <Stepper currentState={liveState} />
            {liveState === "AWAITING_STEP_UP" && (
              <div className="checkout__stepup">
                <p>Your bank needs you to approve this payment. In a real app this would be a push notification or an OTP prompt -- here, it's just these two buttons.</p>
                <div className="checkout__stepup-actions">
                  <button
                    type="button"
                    className="checkout__button"
                    disabled={stepUpBusy}
                    onClick={respondToStepUp(confirmStepUp)}
                  >
                    Approve in bank app
                  </button>
                  <button
                    type="button"
                    className="checkout__button checkout__button--ghost"
                    disabled={stepUpBusy}
                    onClick={respondToStepUp(declineStepUp)}
                  >
                    Decline
                  </button>
                </div>
                <p className="checkout__stepup-timeout">Left unanswered, this request expires on its own after a minute.</p>
              </div>
            )}
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
