# Bringing the Kai #3478 engine to full parity, then replacing the legacy pipeline

> **⚠ HISTORICAL — completed parity program; superseded by [`EMS_ENGINE.md`](EMS_ENGINE.md).**
> Kept as the migration/decision record. The engine no longer "stays shadow-only": as of the live
> `ems.things` (2026-07-19) it runs `emsShadowEnabled=true` + `emsOwnsDispatch=true` (`shadowMode=false`)
> and **owns the EV-coordinator + battery-ToU dispatch in production** (scope: `ENGINE_REPLACED_CONTROLLERS`
> in `EmsManagerBridgeHandler`); the rest of the controller pipeline (safety, boiler-plan, solar-surplus,
> heat-pump, analytics) still actuates. The "engine stays shadow / cutover pending / rolled-back"
> statements below — including the 2026-07-17 post-rollback addendum — are obsolete. For current engine
> behaviour and config flags, read [`EMS_ENGINE.md`](EMS_ENGINE.md).

Date 2026-06-28. Decision (owner): build full feature + SAFETY parity in the new
`internal/ems/` engine BEFORE it replaces the live 18-controller pipeline. The legacy
binding stays the sole actuator until the new engine is proven to match it.

## Why this is staged, not a flip
The Kai engine today = clean architecture + 3 simple strategies (surplus / cheapest-window
/ battery-charge). The live pipeline adds, with NO equivalent in the engine yet:
SafetyBreakerController, Hard/SoftPeakShavingController, CapacityTariffShavingController,
EvCoordinatorController (+ the dispatch + analytics controllers). On a building with 4 EV
chargers + battery + a 56 A panel, those are non-negotiable. So we port them in, test each,
and validate by SHADOW-COMPARISON until the engine's decisions match the pipeline — then cut
over with backups.

## The hard validation gate (must pass before ANY cutover)
Run the new engine in shadow next to the live pipeline and log BOTH decisions per tick for
every shared asset. Cut over ONLY after a sustained window (days, across a sunny day + an EV
session + an evening peak) with ZERO unexplained divergence. Keep `emsApply=false` until then.

## Phases (each: pure tested strategy → shadow-compare → sign off)
- **P0 — foundation (this session):** pass the live `EnergyContext` into the engine so safety
  strategies have the real inputs (phase amps, grid, SoC, peak state); add a parity-divergence
  logger for the boiler (the one currently-shared asset).
- **P1 — SAFETY (do first, never skip):** breaker headroom guard (per-phase amps vs limit →
  block adding load), hard/soft peak-shaving (grid-overshoot tiers), capacity-tariff shaving.
  These must gate EVERY dispatch the engine makes.
- **P2 — EV coordination:** per-car ECO/SNEL/OFF budget solver, ramp + hysteresis, OCPP
  RemoteStart + the stuck-charger backoff. The largest port.
- **P3 — dispatch parity:** boiler plan/schedule, battery ToU, solar-surplus, production-shaving
  — match the legacy controllers' outputs.
- **P4 — analytics parity:** cost / self-consumption / CO2 (done) / anomaly / stats.
- **P5 — validation + cutover:** the shadow-compare gate above; then a reversible cutover
  (backup jar + ems.things; switch the legacy controllers off and the engine to `emsApply`),
  with instant rollback.

## Status
- **P0 — DONE.** Live `EnergyContext` fed to the engine; boiler parity-divergence logger live.
- **P1 — DONE.** Breaker-headroom gate + hard peak-shave hysteresis + capacity-tariff guard, all
  live in the shadow safety line ("breaker headroom N A · peak-shaving … · capacity-tariff …").
- **P2 — DONE (matching live).** Full `EvCoordinatorController` port in shadow: shared ECO budget,
  per-car breaker headroom (`CapabilityCheck`, 53 A effective), SNEL/ECO target amps (ECO floored
  at MIN via `evChargeTargetAmps`), ramp + hysteresis with per-car `lastSentAmps`, modbus-stale →
  MIN, low-headroom → pause, peak-shave defer, ECO external-pause respect, and the auto-RemoteStart
  wedged-charger backoff (`remoteStartDecision`). Per-car parity logged (amps + CHARGE_START) — all
  four cars + boiler read "match" live. Only gap: dynamic soft-ECO-cap lowering under export peak.
- **P3 — DONE (matching live).** Battery ToU dispatch (`batteryTouSetpointW`, verified live in the
  evening-peak window: 2000 W = 2000 W discharge) + production/export-shave soft-ECO-cap sticky band
  (`softEcoCapA`, now feeding the EV ECO path — the last EV approximation removed). Solar-surplus
  boiler matched since P0/P2. Nothing in the control surface is approximated anymore.
- **P4 — analytics: no port needed.** Cost / self-consumption / CO2 (done) / anomaly / stats are
  read-only computed items, not control decisions. They keep running independently of the control
  engine and survive a cutover untouched (only the control controllers get disabled at P5).
- **P5 — validation gate + reversible cutover.** TOOLING DONE: the runner now logs a cumulative
  per-asset `parity cumulative: boiler N/N · ev:carN N/N · battery N/N (pct%)` tally each tick — the
  measurable go/no-go. REMAINING (time-based, not code): watch it hold ~100% across a representative
  window (a sunny day with solar surplus + a real EV charge session + an evening peak), THEN the
  reversible cutover (backup jar + ems.things; flip control controllers off + engine to `emsApply`;
  instant rollback). No cutover until that window is clean.

The engine stays shadow-only (`emsApply=false`); the live building keeps the proven pipeline
throughout. **144 tests.** Deployed jar carries P0–P3 + the P5 parity tally. All assets 100% live.

## Pre-go double-check (2026-07-13) — three findings, two fixed
A cutover readiness review found that "the log says match" was necessary but NOT sufficient:
1. **Cadence (FIXED).** The engine ran every 12th tick (~60 s) vs the real 5 s pipeline, so its
   ramp/hysteresis/soft-cap state advanced 12× too slow — a live EV ramp would show FALSE
   divergences. Now runs every tick (state accurate, tally 12× denser), logs once a minute.
3. **Safety-layer coverage (FIXED, honestly).** The breaker/peak-shave/capacity gates were
   engine-internal, never compared to the legacy safety controllers. Breaker is already covered via
   the per-car EV parity; added a coarse `safety-shed` comparison (engine shed-intent vs legacy
   peak-shaving-hard/-capacity actually shedding) that tallies only when an event fires — so it
   honestly reads "no event seen yet" instead of a fake 100%. The legacy tiered state machine is NOT
   faithfully ported (rare-event, conservative engine gate) — documented, not hidden.
2. **Actuation (OPEN — the real cutover blocker).** In `emsApply=true` the engine actuates ONLY the
   boiler (a tagged consumer). EV amps/pause/RemoteStart and battery ToU are **log-only**, and
   `EmsActuator` maps only `ONOFF`/`SET_WATTS` — no `AMPS`/`PAUSE`/`CHARGE_START`. So flipping the
   flag today would leave all four chargers + the battery uncontrolled. The parity validates the
   DECISIONS; the engine is not yet an actuator for EV/battery.

### Resolution (2026-07-13) — all three findings addressed, cutover now a gated flip
1. **Cadence — FIXED.** Engine runs every tick (accurate 5 s hysteresis, 12× denser tally), logs 1/min.
3. **Safety coverage — FIXED.** Added the `safety-shed` parity line (engine shed-intent vs legacy
   peak-shaving-hard/-capacity actually shedding); breaker already covered via per-car EV parity.
2. **Actuation — CLOSED via the recommended architecture.** The engine now emits its decisions as
   `SetpointRequest`s (boiler ONOFF, per-car EV AMPS/PAUSE/CHARGE_START, battery WATTS_BATTERY;
   controllerName `kai-ems-engine`, dispatch-band priority) and — behind the new **`emsOwnsDispatch`**
   flag (default OFF) — they route through the existing AssetHandler dispatch: the four replaced
   dispatch controllers are suppressed, the engine fills only slots no surviving legacy request holds,
   and safety + everything else keep dispatching untouched. No bespoke actuator, no duplicated dedupe.
   Verified live with the flag OFF: byte-identical behaviour (0 apply, 0 "OWNS dispatch", parity match).

**Cutover is now a single reversible flip** (`emsOwnsDispatch=true` in `ems.things`), gated on a
validated multi-day parity window + explicit owner go. Rollback = set it back to false (or restore
the backup jar/things). The engine stays shadow until then.

### FINISHED (2026-07-16) — validated + scoped, ready for the flip
Multi-day shadow (incl. a real **SNEL 32 A** session + an **ECO ramp** 8/9/11/14 A) gave the honest
verdict: **EV 100% and battery 100%** under real load; **boiler 86%** (engine's simple deadline model
doesn't track delivered energy like the metered `BoilerPlanController`); **safety-shed 0%** (engine
capacity gate shed where the ECO-sacrosanct site holds). Two fixes landed:
- **ECO-sacrosanct respected:** engine capacity-tariff is now observe-only under `evEcoSacrosanct`
  (matches the legacy) → no more shedding the site deliberately holds.
- **Ownership scoped to EV + battery** (the assets parity proved). The **boiler stays on the proven
  metered planner**; safety, boiler-schedule and observers keep dispatching untouched. The boiler is
  now a `(demo, not owned)` #3478-model comparison, not a cutover asset.

**Remaining = only the live flip** (`emsOwnsDispatch=true`) — reversible, scoped, monitored — on
explicit owner go. Nothing else to build.

### Recommended cutover architecture (for the actuation gap)
Do NOT rebuild actuation inside the engine (that duplicates the tested AssetHandler dedupe +
`autoupdate="false"` handling — the exact bug class CLAUDE.md §14 warns about). Instead:
- **Partial cutover.** KEEP the legacy safety controllers (breaker, hard-peak, capacity) running —
  they're proven and only ADD pauses/sheds. Replace only the DISPATCH controllers (EV coordinator,
  solar-surplus boiler, battery ToU, boiler plan) with the engine.
- **Route engine decisions through the existing dispatch.** Have the engine emit `SetpointRequest`s
  into the same priority scheduler the legacy controllers use, so safety (higher priority) always
  wins and all actuation/dedupe stays in the proven AssetHandler layer.
This keeps the safety net on the live building and reuses tested actuation — the safe path.

**Status: engine stays shadow-only. Next large piece (actuation-via-dispatch) awaits an
architecture decision — see below.**

## What "all of Kai's dream + all of my EMS" now means concretely
- Kai #3478: metadata participants (`energy:` provider/consumer/profile), self-derived surplus from
  the tagged grid provider, swappable pure strategy, controllable-provider actuation — all live.
- Legacy EMS control surface, ported as pure tested functions and matched in shadow: breaker safety,
  hard peak-shave, capacity-tariff, full EV coordinator (shared ECO budget, per-car headroom, SNEL/
  ECO amps with ECO floor, ramp+hysteresis, modbus-stale, external-pause, RemoteStart backoff,
  dynamic soft cap), solar-surplus boiler (cloudiness-adaptive), battery ToU.
- The one thing deliberately NOT auto-flipped: going to `emsApply`. That waits on the P5 window +
  explicit owner go, because it removes the proven pipeline from a live building.

Related: [EMS_ENGINE.md](EMS_ENGINE.md), [ENERGY_TAXONOMY.md](ENERGY_TAXONOMY.md).

## Post-rollback addendum (2026-07-17) — the engine as upstream exhibit
The site rolled back to the full legacy pipeline on 2026-07-16 (owner's call; both engine flags
off in ems.things). The #3478 probe then drew replies from florian-h05 (asked for the code),
lsiepel and mstormi; the reply pointed them at the repo and proposed a 4-class consumer taxonomy.
"Waiting work" done 2026-07-17 (commit 8c87105, deployed dormant, 152 tests):
- Classes 3 (ModeControllable/SG-ready) + 4 (Batch/dishwasher) implemented — the reference now
  implements the full taxonomy from the comment (SET_MODE + HOLD action kinds; batchStartNow
  cheapest-contiguous-window; modeIndex availability grading).
- Engine de-sited: hardcoded boiler item → emsParityBoilerItem config; all parity scaffolding
  extracted to LegacyParityHarness; engine reads clean for a maintainer review.
- docs/EMS_ENGINE.md rewritten + docs/ENERGY_TAXONOMY.md drafted (the offered design doc,
  ready to become a GitHub Discussion on request — posting gated on owner go).
This plan is COMPLETE as a parity program; the remaining life of internal/ems/ is as the #3478
reference implementation. If upstream stays quiet for weeks, cleanup of internal/ems/ is the
documented fallback (see memory).
