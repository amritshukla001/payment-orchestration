package com.paymentengine.complianceservice.rules;

public record Verdict(boolean approved, String reason) {
    public static Verdict approve() {
        return new Verdict(true, null);
    }
    public static Verdict reject(String reason) {
        return new Verdict(false, reason);
    }
}
