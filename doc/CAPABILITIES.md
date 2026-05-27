# Device capabilities

The binding decouples *what a device provides* from *where the value comes
from* through a small set of capability interfaces in
`internal/capability/`. This is the openHAB-native counterpart to OpenEMS
"Natures": controllers reason about a `Battery` or an `EnergyReading`, not
about item names or sign conventions.

## The contracts

| Interface | Meaning | Canonical sign |
|---|---|---|
| `EnergyReading` | instantaneous power, `watts()` + `available()` | grid `+ = export`, load `+ = consumption` |
| `Battery` (extends `EnergyReading`) | `+ charging` power, `soc()`, `reserveTarget()` | `+ = charging` |
| `ControllableLoad` | switchable load: `id()`, `on()` | — |
| `Site` | per-tick snapshot: `grid()`, `solar()`, `house()`, `battery()`, `boiler()`, `airco()` | — |

Each interface ships a small immutable `record Of(...)` implementation so a
`Site` can be assembled from plain values (and is trivial to fabricate in
tests).

## The shipped provider

`ItemSiteReader.read(numberReader, switchReader, config)` is the default,
item-backed `Site` provider. It is the **single place** that:

1. resolves the configured item name for each reading (falling back to the
   compile-time default when the config string is blank), and
2. applies the **sign-convention normalization** (`invertGrid`, `invertSolar`,
   `invertHouse`, `invertBattery`) so everything downstream sees the canonical
   convention (grid + = export, solar + = producing, house + = consuming,
   battery + = charging).

The item reads are injected as functions, so `ItemSiteReader` has no openHAB
runtime dependency and is unit-tested directly (`ItemSiteReaderTest`).
`ContextBuilder` calls it once per tick and populates the immutable
`EnergyContext` from the resulting `Site`.

## Why this matters

- **Portability:** plugging the binding into a different installation is now
  "map your items + set the sign flags", handled in one normalization layer
  instead of scattered across readers.
- **Testability:** the device-reading + sign logic is a pure function with
  its own tests.
- **Extensibility:** a future `Site` implementation backed by Thing channels,
  a fieldbus, or computed values can be substituted without touching
  controllers — they only see `EnergyContext`.

## Migration status / follow-up

- **Done:** the capability contracts; the item-backed `ItemSiteReader`; the
  source-reading path in `ContextBuilder` (grid / solar / house / battery /
  SoC / reserve / boiler / aircon) flows through `Site`.
- **Follow-up (not yet done):**
  - extend `Site` to cover the per-EV-charger readings and the device
    sub-meters (currently still read directly in `ContextBuilder`);
  - introduce an `Evse` capability and let `EnergyContext` expose typed
    capability views to controllers (today they consume flat fields);
  - a Thing-channel-backed `Site` provider as an alternative to the
    item-backed one, for installs that prefer channel wiring.
