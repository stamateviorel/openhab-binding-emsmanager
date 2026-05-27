# EMS Manager — Architecture

## The tick loop

The bridge handler runs a scheduled task every `tickIntervalSeconds` (default 5 s):

1. **`ContextBuilder`** reads the configured source items — via the **capability layer** (`ItemSiteReader` → `Site`, see [CAPABILITIES.md](CAPABILITIES.md)), which centralizes item-name resolution and sign-convention normalization — and builds an **immutable `EnergyContext`**: a snapshot of grid/solar/house/battery power, SoC, per-car state, tariff price, forecast, cloudiness, smoothed grid (EWMA), 5-min rolling average, capacity-tariff slot state, and the tick timestamp.
2. The **`PriorityScheduler`** runs every registered `Controller` in priority order, passing the same context. Each returns a list of `SetpointRequest`s.
3. The bridge collects the requests and, **only if not in shadow mode**, dispatches each to the matching **asset handler**, which owns dedupe + per-mode write gating and performs the actual item write.
4. Liveness + diagnostic channels are updated.

## The `Controller` contract

```java
public interface Controller {
    String name();
    int priority();              // lower runs first; higher wins on conflict
    boolean enabled();
    boolean shadowMode();        // per-controller shadow (in addition to the bridge flag)
    List<SetpointRequest> evaluate(EnergyContext ctx);
}
```

Control controllers are intended to be **pure functions of the context** (no I/O), which makes them trivially unit-testable with synthetic contexts. The analytics controllers are the deliberate exception: they are read-only *observers* that read accumulator items and publish derived items — they emit no setpoints.

State that must persist **across ticks** lives in the controller instance or in shared bridge components (`EwmaFilter`, `RollingAverage`, `CapacityTariffTracker`). State that must persist **across restarts** lives either in items (via openHAB persistence) or in small JSON cache files under `/var/lib/openhab/cache/`.

## Asset handlers

`SetpointRequest`s are addressed to an asset id (`boiler`, `airco-group`, `car1`…`carN`, `battery`). The handler:

- maps the request to the configured actuator item(s),
- dedupes (an ACK window prevents re-sending the same command, important for `autoupdate="false"` OCPP items),
- enforces gating (e.g. the battery handler writes only when `batteryControlMode=auto`).

This keeps controllers ignorant of item names and write mechanics.

## Providers (pluggable)

- **TariffProvider** — `flat`, `day-night`, `tou-schedule`, `dynamic-spot` (ENTSO-E / Tibber / aWATTar / CSV). Pure functions producing a 24/48 h price schedule.
- **SolarForecastProvider** — Forecast.Solar free tier; file-cached; rate-limit-aware.
- **EmissionsProvider** — fixed grid factors or live Electricity Maps.
- **Temperature forecast** — OpenMeteo (no key), feeding the heat-pump planner.

Cross-Thing data flow uses **channel-linked items**: the tariff/forecast Things publish to their channels, items mirror those channels, and `ContextBuilder` reads the items. No special Java plumbing between handlers.

## Analytics layer

- **CostAnalytics** integrates `power × dt → kWh` and `kWh × price → €` each tick, with day/month rollover; restores from persisted items on restart.
- **LongTermStats** turns those daily accumulators into yesterday/week/month/year rollups via a pure **`DailyRollup`** helper. `DailyRollup` converts a cumulative counter (kWh resets daily, EUR monthly) into correct per-day increments and keeps a 365-day ring, using the *last reading before the reset* at rollover (not the post-reset value). Persisted to a cache file; `lastSeenDay` is written *after* it advances so a restart never re-triggers a rollover.
- **Co2Tracking**, **AnomalyDetection** (per-device median±MAD z-score), **battery sizing** (DP simulation over historical buckets → payback) and **tariff comparison** (replays your load profile against each supplier) round out the suite.

## The statistics tier (why it exists)

openHAB persistence has no server-side aggregation, so a chart over a long window fetches *every* raw point (tens of thousands/day for `everyChange` power items) and the browser chokes. **StatisticsRollup** writes one finalized datapoint per day (~23:58) into dedicated `EMS_Stat_*` items persisted `everyChange` only — so a month is ~30 points and a year ~365. Charts read those. Snapshotting before midnight (not at it) keeps each value inside its own calendar day so per-month/year bucketing is correct.

## Safety model

- **Shadow mode** (bridge + per-controller) — compute-but-don't-write; the default on first install.
- **Priority ordering** — the safety-breaker controller runs first and can always pause loads; peak-shaving controllers run before the opportunistic dispatchers, which defer to them.
- **Asset dedupe + gating** — the last line before an item is written.
