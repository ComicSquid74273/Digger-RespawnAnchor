package com.comicsquid.respawnanchor.modules;

final class SafetyPolicy {
    private SafetyPolicy() {
    }

    static boolean allows(float damage, double totalHealth, double maxDamage, double minHealth) {
        return Float.isFinite(damage)
            && damage >= 0
            && damage <= maxDamage
            && totalHealth - damage >= minHealth;
    }
}
