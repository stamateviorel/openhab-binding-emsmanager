# Bringing the Kai #3478 engine to full parity, then replacing the legacy pipeline

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
- P0 started this session. P1–P5 are subsequent sessions. The engine stays shadow-only; the
  live building keeps the proven pipeline throughout.

Related: ./EMS_ENGINE_KAI_3478.md, ./EMS_CORE_PICKUP_PLAN.md.
