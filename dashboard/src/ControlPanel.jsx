import { useState } from "react";
import { createPayment, flagAccount, verifyAccount, registerUpi } from "./api.js";

// The dashboard's one write path -- everything else in this app only reads
// from read-model-service. A collapsible panel above the grid so a demo
// doesn't need a terminal open: a real POST /payments, plus the three
// API-only demo levers compliance-service exposes (flag/verify KYC,
// register a UPI recipient). See dashboard/README.md for the full
// endpoint list this hits.

// Matches the accounts used in the root README's "Try it" curl walkthrough,
// so demo behavior here lines up with what the docs describe.
const DEMO_PAYER = "11111111-1111-1111-1111-111111111111";
const DEMO_PAYEE = "22222222-2222-2222-2222-222222222222";

function ResultLine({ result }) {
  if (!result) return null;
  if (result.status === "loading") {
    return <p className="control-panel__result control-panel__result--pending">Working…</p>;
  }
  if (result.status === "error") {
    return <p className="control-panel__result control-panel__result--error">{result.message}</p>;
  }
  return <p className="control-panel__result control-panel__result--ok">{result.message}</p>;
}

function SendPaymentForm() {
  const [form, setForm] = useState({
    payerAccount: DEMO_PAYER,
    payeeAccount: DEMO_PAYEE,
    amountCents: "2500",
    currency: "USD",
    paymentMethod: "NETBANKING",
  });
  const [result, setResult] = useState(null);

  const update = (field) => (e) => setForm({ ...form, [field]: e.target.value });

  const submit = async (e) => {
    e.preventDefault();
    setResult({ status: "loading" });
    try {
      const payment = await createPayment({
        payerAccount: form.payerAccount,
        payeeAccount: form.payeeAccount,
        amountCents: Number(form.amountCents),
        currency: form.currency,
        paymentMethod: form.paymentMethod,
      });
      setResult({ status: "ok", message: `Created ${payment.id.slice(0, 8)}… — watch it in the grid.` });
    } catch (err) {
      setResult({ status: "error", message: err.message });
    }
  };

  return (
    <form className="control-panel__form" onSubmit={submit}>
      <div className="control-panel__row">
        <label>
          Payer account
          <input className="mono" value={form.payerAccount} onChange={update("payerAccount")} />
        </label>
        <label>
          Payee account
          <input className="mono" value={form.payeeAccount} onChange={update("payeeAccount")} />
        </label>
      </div>
      <div className="control-panel__row">
        <label>
          Amount (cents)
          <input className="mono" value={form.amountCents} onChange={update("amountCents")} />
        </label>
        <label>
          Currency
          <input className="mono" value={form.currency} onChange={update("currency")} maxLength={3} />
        </label>
        <label>
          Method
          <select value={form.paymentMethod} onChange={update("paymentMethod")}>
            <option value="NETBANKING">NETBANKING</option>
            <option value="CARD">CARD</option>
            <option value="UPI">UPI</option>
          </select>
        </label>
      </div>
      <div className="control-panel__actions">
        <button type="submit" className="control-panel__button">Send payment</button>
        <span className="control-panel__hint">
          Try 950000 for a compensation demo, or flag the payer below first for a KYC rejection.
        </span>
      </div>
      <ResultLine result={result} />
    </form>
  );
}

function ComplianceActions() {
  const [accountId, setAccountId] = useState(DEMO_PAYER);
  const [result, setResult] = useState(null);

  const run = (action, label) => async () => {
    setResult({ status: "loading" });
    try {
      await action(accountId);
      setResult({ status: "ok", message: `${label} — ${accountId.slice(0, 8)}…` });
    } catch (err) {
      setResult({ status: "error", message: err.message });
    }
  };

  return (
    <div className="control-panel__form">
      <label>
        Account ID
        <input className="mono" value={accountId} onChange={(e) => setAccountId(e.target.value)} />
      </label>
      <div className="control-panel__actions">
        <button className="control-panel__button control-panel__button--danger" onClick={run(flagAccount, "Flagged for KYC review")}>
          Flag for KYC review
        </button>
        <button className="control-panel__button" onClick={run(verifyAccount, "Verified")}>
          Verify
        </button>
        <button className="control-panel__button" onClick={run(registerUpi, "Registered as UPI recipient")}>
          Register as UPI recipient
        </button>
      </div>
      <ResultLine result={result} />
    </div>
  );
}

export default function ControlPanel() {
  const [open, setOpen] = useState(false);

  return (
    <div className="control-panel">
      <button className="control-panel__toggle" onClick={() => setOpen(!open)}>
        {open ? "Hide demo controls" : "Demo controls"}
      </button>
      {open && (
        <div className="control-panel__body">
          <section>
            <h3>Send a payment</h3>
            <SendPaymentForm />
          </section>
          <section>
            <h3>Compliance actions</h3>
            <ComplianceActions />
          </section>
        </div>
      )}
    </div>
  );
}
