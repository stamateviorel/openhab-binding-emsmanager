# A working realization of Kai Kreuzer's energy-management design (openhab-core #3478)

This is a runnable implementation of the core energy-management architecture Kai Kreuzer
designed in [openhab-core#3478](https://github.com/openhab/openhab-core/issues/3478) (and
which then stalled for lack of maintainer time). It lives inside the `emsmanager` binding
(package `internal/ems/`) as an additive, opt-in subsystem and runs **shadow/log-only** by
default, so it makes real decisions on a live site without disturbing anything.

## The model (Kai's concepts, as Java)

| Kai's #3478 concept | Class |
|---|---|
| `energy:` item-metadata namespace | `EnergyMetadataParser` (namespace `energy`) |
| `EnergyProvider` (grid/PV/battery; price; controllable) | `EnergyProvider` |
| `EnergyConsumer` / `DemandDescription` | `EnergyConsumer` (with `demandKwh` + `deadlineHour`) |
| `PowerProfile` → `SimplePowerProfile` / `ControllablePowerProfile` | `PowerProfile.Simple` / `PowerProfile.Controllable` |
| auto-discover marked items | `MetadataParticipantScanner` |
| `EnergyManagementService` (swappable simple algorithm) | `EnergyManagementService` |
| runs it, advisory | `ShadowEmsRunner` |

## Tagging items (the `energy:` namespace)

Mark the participating items — no item-name configuration needed:

```java
// providers
Number:Power Solar_Power  { energy="provider" [ role="pv" ] }
Number:Power Grid_Power    { energy="provider" [ role="grid", price="Grid_Price", schedule="Grid_Schedule24h" ] }
Number:Power Battery_Power { energy="provider" [ role="battery", control="Battery_Setpoint", min=-3000, max=3000, soc="Battery_SoC" ] }

// consumers
Switch       Boiler_Switch { energy="consumer" [ profile="simple", demandKwh=4, deadlineHour=7 ] }
Number:Power Wallbox_Power { energy="consumer" [ profile="controllable", min=0, max=11000 ] }
```

- `role`: `grid` | `pv` | `battery`. `price`/`schedule`: items carrying the current
  `Number:EnergyPrice` and a 24-hour price CSV. `control`/`min`/`max`: a controllable
  (battery) provider's setpoint item and clamp.
- `profile`: `simple` (on/off Switch) | `controllable` (regulate to watts). `demandKwh` +
  `deadlineHour`: optional daily energy demand for cheapest-window scheduling.

## The strategies (`EnergyManagementService`)

Kai noted the algorithm "could be as simple as a rule template" and should be swappable. Two
ship today, both pure/unit-tested:

1. **Surplus dispatch** (`planSurplusDispatch`) — derives surplus from the tagged grid
   provider's live net power (`surplusFromGridNet`, canonical `+ = export`) and greedily soaks
   it into the consumers in priority order: a controllable load is set to the fitting watt
   value within `[min,max]`; a simple load is switched on once surplus clears a threshold.
2. **Cheapest-window deadline** (`runNowForDeadline`) — a consumer with a `demandKwh` and a
   daily `deadlineHour` runs in the cheapest hours before its deadline, using the grid
   provider's 24-hour price schedule. The window wraps past midnight (e.g. 23:00 now, ready by
   07:00 → an overnight window), so an evening boiler correctly waits for the cheap small
   hours. With no price schedule it falls back to the latest hours before the deadline.
3. **Battery charge** (`planBatteryCharge`) — Kai's controllable-provider case: a controllable
   battery provider is commanded to charge from the surplus (a negative watt setpoint, clamped
   to its charge limit), idling when full (`soc=` item) or when there is no surplus.

## Running it (shadow)

Set `emsShadowEnabled=true` on the bridge (advanced config; default off). Each cycle it
discovers the tagged items, reads their live state, runs both strategies, and **logs** the
plan it would apply — it never writes. Example from a live site at 21:39:

```
[EMS-SHADOW] Kai #3478 model: surplus 0 W · 2 provider(s) · 1 consumer(s) → 1 action(s) (no writes)
[EMS-SHADOW]   provider Grid_load role=GRID power=0 W, price=0.300
[EMS-SHADOW]   provider Solar_load role=PV power=1150 W
[EMS-SHADOW]   would set Boiler_technical_room_real -> OFF — surplus dispatch: surplus below threshold, off
[EMS-SHADOW]   deadline plan Boiler_technical_room_real -> WAIT (demand 4 kWh by 7:00, cheapest-hour ranking)
```

## What we hit that the 2023 design never reached

The production binding already solved the operational concerns the #3478 sketch hand-waved:
priority-ordered conflict resolution, per-asset dedupe (the `autoupdate=false` ACK problem),
shadow mode, and safety ownership (breaker/peak-shaving outranking economics). The shadow EMS
runs alongside that proven pipeline rather than replacing it.

## Actuation (`EmsActuator`, opt-in)

The engine can also **act**, not just advise. With `emsApply=true` (advanced, default off) it
applies each action: a `Simple` load gets ON/OFF, a `Controllable` load gets its watt value
(`EmsActuator.toCommand` → `OnOffType` / `DecimalType`). The log switches from `[EMS-SHADOW] …
would set` to `[EMS-APPLY] … set`. **Caveat:** only enable on a site where this engine OWNS
those items — never where a legacy controller pipeline already commands the same boiler / cars
/ battery (two writers conflict).

## Status

- **Done:** the participant model + `energy:` discovery + two strategies (surplus + cheapest-
  window) + live shadow runner + opt-in actuation, all unit-tested (8 EMS tests), deployed and
  demonstrated on a live 25 kWp solar + battery + EV site (shadow; actuation left off there
  because the legacy pipeline owns control).
- **Upstream:** offered on #3478 as a working reference for Kai's design; the core interfaces
  would move to `openhab-core` only with a maintainer co-owner.
```
