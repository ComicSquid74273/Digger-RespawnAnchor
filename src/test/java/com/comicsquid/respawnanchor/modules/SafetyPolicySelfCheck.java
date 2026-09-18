package com.comicsquid.respawnanchor.modules;

public final class SafetyPolicySelfCheck {
    private SafetyPolicySelfCheck() {
    }

    public static void main(String[] args) {
        assert SafetyPolicy.allows(4, 14, 4, 10);
        assert !SafetyPolicy.allows(4.1f, 20, 4, 10);
        assert !SafetyPolicy.allows(4, 13.9, 4, 10);
        assert !SafetyPolicy.allows(Float.NaN, 20, 4, 10);
    }
}
