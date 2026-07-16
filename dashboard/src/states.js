// Mirrors com.payflow.common.enums.PaymentState. Grouped by what the
// state means for the payer/payee, not just alphabetically.
export const STATE_META = {
  INITIATED: { label: "Initiated", tone: "inflight" },
  FRAUD_CHECKED: { label: "Fraud Checked", tone: "inflight" },
  AUTHORIZED: { label: "Authorized", tone: "inflight" },
  LEDGER_POSTED: { label: "Ledger Posted", tone: "inflight" },
  SETTLED: { label: "Settled", tone: "success" },
  COMPENSATING: { label: "Compensating", tone: "warning" },
  COMPENSATED: { label: "Compensated", tone: "reversed" },
  FAILED: { label: "Failed", tone: "danger" },
};

export function stateMeta(state) {
  return STATE_META[state] ?? { label: state, tone: "inflight" };
}
