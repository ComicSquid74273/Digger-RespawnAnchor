# Digger Respawn Anchor

A small Meteor 26.2 addon that automates respawn-anchor excavation while you hold the use key and aim at terrain.

## Anchor Digger

The module performs one tracked cycle at a time:

1. Place a respawn anchor at the aimed block face.
2. Charge it with one glowstone.
3. Check predicted self-damage.
4. Detonate it.
5. Select the next respawn anchor.

Settings control rotation, action delay, interaction range, full-inventory item search, the inventory staging slot, maximum self-damage, and minimum remaining health.

The module pauses in the Nether, while a screen is open, when required items are unavailable, when the target is out of range, or when the predicted explosion is unsafe.

## Build

```powershell
./gradlew.bat build
```

The output JAR is written to `build/libs`.
