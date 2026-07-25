package com.paymentengine.common.enums;

/**
 * How the payer is funding a payment. Compliance requirements differ by
 * method -- e.g. UPI requires the payee be a registered UPI recipient --
 * which is the reason this exists as a first-class concept rather than
 * being folded into currency/account fields.
 */
public enum PaymentMethod {
    CARD,
    UPI,
    NETBANKING
}
