# A working realization of Kai Kreuzer's energy-management design (openhab-core #3478)

This is a runnable implementation of the core energy-management architecture Kai Kreuzer
designed in [openhab-core#3478](https://github.com/openhab/openhab-core/issues/3478) (and
which then stalled for lack of maintainer time). It lives inside the `emsmanager` binding
(package `internal/ems/`) as an additive, opt-in subsystem, and has been validated in
production on a 25 kWp solar + battery + 4-EV site — first in shadow against the binding's
own 18-controller pipeline (multi-day decision-vs-decision parity, including a real 32 A fast
charge and an ECO solar-following ramp), then briefly live-driving the EV chargers and battery
through the binding's dispatch.

## Engine vs. testbed — what to read

| Part | Files | Role |
|---|---|---|
| **Engine (the #3478 reference — read this)** | `EnergyMetadataParser`, `EnergyProvider`, `EnergyConsumer`, `PowerProfile`, `EnergyParticipant`, `ProviderRole`, `MetadataParticipantScanner`, `EnergyManagementService`, `EmsAction`, `EmsActuator` | Pure model + strategies + actuation. No site knowledge. |
| Runner | `ShadowEmsRunner` | Live glue: discover → read → plan → gate → emit/actuate. |
| **Testbed (migration scaffolding — not part of the design)** | `LegacyParityHarness` | Compares engine decisions against a pre-existing controller pipeline to validate a cutover; keeps the cumulative parity tally. A standalone deployment never uses it. |

## The model (Kai's concepts, as Java)

| Kai's #3478 concept | Class |
|---|---|
| `energy:` item-metadata namespace | `EnergyMetadataParser` (namespace `energy`) |
| `EnergyProvider` (grid/PV/battery; price; controllable) | `EnergyProvider` |
| `EnergyConsumer` / `DemandDescription` | `EnergyConsumer` (with `demandKwh` + `deadlineHour`) |
| `PowerProfile` | `PowerProfile.Simple` / `.Controllable` / `.ModeControllable` / `.Batch` |
| auto-discover marked items | `MetadataParticipantScanner` |
| `EnergyManagementService` (swappable simple algorithm) | `EnergyManagementService` |
| runs it (shadow / apply / dispatch) | `ShadowEmsRunner` |

The four `PowerProfile` classes implement the consumer taxonomy distilled from two independent
production EMS implementations (see [`ENERGY_TAXONOMY.md`](ENERGY_TAXONOMY.md)):

1. **Simple** — on/off load (boiler, immersion heater stage).
2. **Controllable** — regulated to a watt value in `[min,max]` (wallbox, battery).
3. **ModeControllable** — an ordered set of discrete modes, no power setpoint
   (SG-ready heat pump: blocked / normal / encouraged / forced).
4. **Batch** — fixed program, the planner only picks the start moment before a latest start;
   a running program is never interrupted (dishwasher, washing machine).

## Tagging items (the `energy:` namespace)

Mark the participating items — no item-name configuration needed:

```java
// providers
Number:Power Solar_Power   { energy="provider" [ role="pv" ] }
Number:Power Grid_Power    { energy="provider" [ role="grid", price="Grid_Price", schedule="Grid_Schedule24h" ] }
Number:Power Battery_Power { energy="provider" [ role="battery", control="Battery_Setpoint", min=-3000, max=3000, soc="Battery_SoC" ] }

// consumers — one line per taxonomy class
Switch       Boiler_Switch { energy="consumer" [ profile="simple", demandKwh=4, deadlineHour=7, minOnMinutes=10, priority=20 ] }
Switch       PoolPump      { energy="consumer" [ profile="simple", runAtLevel=2, ready="Pool_Enabled" ] }
Switch       Freezer       { energy="consumer" [ profile="simple", maxOffMinutes=45, minOnMinutes=15 ] }
Number:Power Wallbox_Power { energy="consumer" [ profile="controllable", min=0, max=11000 ] }
String       HeatPump_SGr  { energy="consumer" [ profile="mode", modes="blocked,normal,encouraged,forced" ] }
Switch       Dishwasher    { energy="consumer" [ profile="batch", ratedW=2000, deadlineHour=17, measure="Dish_Power", shape="0.1,1.0,0.2" ] }
```

> These same `energy:` tags also feed the binding's built-in MainUI **Energy** section
> (`EnergyUiProvider`, tabs *Now* + *Charts*) — see the [README](../README.md). Tag an item and it
> appears there automatically; the page rebuilds live when tags change.

- `role`: `grid` | `pv` (alias `solar`) | `battery` (alias `storage`). `price`/`schedule`: items
  carrying the current `Number:EnergyPrice` and a 24-hour price CSV. `control`/`min`/`max`/`soc`: a
  controllable (battery) provider's setpoint item, its `[min,max]` clamp, and the state-of-charge
  item the charge strategy reads.
- `profile`: `simple` | `controllable` | `mode` | `batch`. `demandKwh` + `deadlineHour`:
  optional daily energy demand for cheapest-window scheduling (any class). `modes`: ordered
  comma list, most-restricted first. `ratedW` + `runtimeHours`: a batch program's rough draw
  and length; optional `shape` (normalized fractions per hour) refines the start to
  shape-weighted price costing. Optional `measure` on any consumer names its measured-power
  item — running-detection and delivered-energy metering trust measurement over commanded
  state (the autonomy principle).

## The strategies (`EnergyManagementService`)

Kai noted the algorithm "could be as simple as a rule template" and should be swappable. All
strategies are pure, static and unit-tested:

1. **Unified consumer plan** (`planConsumers`) — one coherent decision per consumer per tick,
   across all four classes: deadline-driven loads run in the cheapest hours before their
   deadline (`runNowForDeadline`, wraps past midnight); otherwise surplus (derived from the
   tagged grid provider, `surplusFromGridNet`, canonical `+ = export`) is soaked greedily in
   priority order. Mode loads get the mode matching availability (`modeIndex`, graded
   restricted/normal/encouraged/maximum; `isExpensiveHour` marks the day's most expensive
   hours). Batch loads start on live surplus or in the cheapest **contiguous** window that
   still finishes before the deadline (`batchStartNow`), with a forced latest-start fallback —
   and otherwise receive an explicit `HOLD`, never an OFF.
2. **Battery charge** (`planBatteryCharge`) — Kai's controllable-provider case: charge from
   surplus (negative watt setpoint, clamped), idle when full or when there is no surplus.
3. **Safety gates** (`applyBreakerGate`, `peakShaveActive`+`applyPeakShaveGate`,
   `wouldExceedCapacityPeak`+`applyCapacityGate`) — no plan may add load when breaker headroom
   is low; sustained grid-import peaks and projected capacity-tariff peaks shed positive
   actions. Economics never outrank the fuse.
4. **EV coordination** (`evChargeTargetAmps`, `ecoBudgetPerCarW`, `rampLimitAmps`,
   `applyHysteresisAmps`, `remoteStartDecision`, `softEcoCapA`) — a full per-car EV charging
   coordinator: shared ECO budget across active cars, SNEL/ECO targets with ramp + hysteresis,
   a wedged-charger RemoteStart backoff and a sticky soft ECO cap. Validated to 100% parity
   against a production coordinator under real charging.
5. **Switch protection** (`constrainOnOff`) — the class-1 `{min,max} × {on,off}` constraint
   set: min/max runtime, cooldown, and the fridge max-off duty-cycle guarantee (force-restart;
   `minOnMinutes` doubles as the catch-up run). Enforced on the planner's desired state; safety
   gates still outrank a forced-ON — the fuse beats the freezer.
6. **The site energy level** (`energyLevel`) — one four-level availability state
   (restricted / normal / encouraged / maximum) from surplus + price (incl. a cheapest-hours
   lift), mapping 1:1 onto SG-ready and EVCC modes (storm.house's *Energieniveau*, converged
   independently). Consumers are served in deterministic `priority` order, may carry their own
   surplus `thresholdW`, a `ready` interlock, and — the simplest control of all — `runAtLevel`:
   ON whenever the site level reaches N.
7. **Battery time-of-use** (`batteryTouSetpointW`) — night-charge / evening-discharge windows,
   reserve-protected.

## Running it

Three levels, all bridge config (advanced, default off):

- **`emsShadowEnabled=true`** — the engine runs and **logs** its plan each tick; nothing is
  written. The parity harness compares against the legacy pipeline when one exists
  (`emsParityBoilerItem` names the consumer for the informational boiler comparison).
- **`emsApply=true`** — standalone actuation: the engine applies its `EmsAction`s directly to
  the tagged items (`EmsActuator`: ON/OFF, watts, mode string; `HOLD` sends nothing). Only for
  a site where the engine OWNS those items.
- **`emsOwnsDispatch=true`** — integrated actuation: the engine's decisions are emitted as
  `SetpointRequest`s (controllerName `kai-ems-engine`) and routed through the binding's
  existing priority dispatch, replacing the legacy EV-coordinator and battery-ToU controllers
  while every SAFETY controller keeps dispatching and outranks the engine on conflicts. This
  is the reversible-cutover path that ran live.

The forecast Thing additionally publishes its full-horizon hourly PV forecast (today +
tomorrow) as `TimeSeries` future states on the `forecastSeries` channel — link a `Number:Power`
item and persist it with a future-capable persistence service (e.g. `inmemory`, InfluxDB) to
chart or plan on tomorrow's production.

## Status

- **Done:** participant model, `energy:` discovery, all four profile classes, the strategy
  set above (30+ EMS unit tests), shadow + standalone-apply + dispatch-integrated actuation,
  multi-day production parity validation and a live cutover exercise (engine drove a real ECO
  solar-following charge, 11→15→12 A, matching the legacy coordinator in real time).
- **Honest limitations:** the metadata-only discovery is the *user-tagging half* of Kai's
  design — in core, bindings would additionally implement provider/consumer interfaces as
  services. The simple deadline model does not track delivered energy (a metered planner
  outperforms it for DHW — see the taxonomy doc's "cross-cutting" section). Mode loads' draw
  is unknown to the surplus budget, and shed gates hold rather than actively force a mode load
  down.
- **Upstream:** offered on #3478 as a working reference; the taxonomy synthesis lives in
  [`ENERGY_TAXONOMY.md`](ENERGY_TAXONOMY.md). The core interfaces would move to
  `openhab-core` only with a maintainer co-owner.

## Percentile level windows + participant services (2026-07-19)

- The energy level's price half now grades by **rank in the 24 h day-ahead schedule** with
  configurable windows (`levelCheapestHours`/`levelCheapHours`/`levelExpensiveHours`, default
  4/8/4) and optional **seasonal auto-profiles** (`levelSeasonalAuto`: winter 8/12/4,
  transition 6/10/8, summer 4/8/8) — storm.house's percentile config layer. A
  percentile-cheapest hour can grade **maximum** on price alone; surplus still dominates.
- `EnergyParticipantProvider` (OSGi whiteboard) previews the **core half** of #3478: any
  bundle can register participants programmatically; the scanner merges them with the
  `energy:`-tagged items, metadata winning on duplicate ids, and a throwing registrant is
  skipped, never fatal. Wired factory → bridge → runner as a live collection, so services
  appearing/vanishing are seen next tick.
