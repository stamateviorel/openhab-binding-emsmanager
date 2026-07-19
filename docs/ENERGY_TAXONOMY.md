# An energy consumer/provider taxonomy for openHAB — design draft

*Draft for the discussion in [openhab-core#3478](https://github.com/openhab/openhab-core/issues/3478).
Grounded in two independent production EMS implementations: the author's `emsmanager` binding
(25 kWp PV + battery + 4 EV chargers + DHW boiler, Belgian capacity tariff) and Markus Storm's
storm.house EMS (rules-based; ~20 PV vendors, heat pumps via SG-ready, white goods via Home
Connect, German §14a). Every element below runs in at least one of the two systems today —
nothing is speculative.*

## 1. Why a taxonomy, and why it is smaller than it looks

The #3478 discussion stalled partly on the fear that a universal device model is intractable:
a dishwasher, a wallbox and a heat pump differ *by nature* (can/cannot set power, can/cannot
be interrupted, fixed vs. variable draw). That concern is real — but comparing two full,
independently-built production systems shows the differences **repeat** rather than multiply.
Every consumer in both systems falls into one of four profile classes, and two of the four
exist precisely *because* of those natural differences.

## 2. The `energy:` item metadata namespace

Kai's original sketch: mark participating items with metadata; the energy-management service
discovers them. This is the *user-facing half* of the design (the other half — bindings
implementing provider/consumer interfaces as services — is future core work, §6).

```java
// providers
Number:Power Solar_Power   { energy="provider" [ role="pv" ] }
Number:Power Grid_Power    { energy="provider" [ role="grid", price="Grid_Price", schedule="Grid_Schedule24h" ] }
Number:Power Battery_Power { energy="provider" [ role="battery", control="Battery_Setpoint", min=-3000, max=3000, soc="Battery_SoC" ] }

// consumers — one per profile class
Switch       Boiler_Switch { energy="consumer" [ profile="simple", demandKwh=4, deadlineHour=7 ] }
Number:Power Wallbox_Power { energy="consumer" [ profile="controllable", min=0, max=11000 ] }
String       HeatPump_SGr  { energy="consumer" [ profile="mode", modes="blocked,normal,encouraged,forced" ] }
Switch       Dishwasher    { energy="consumer" [ profile="batch", ratedW=2000, runtimeHours=2, deadlineHour=17 ] }
```

Providers carry a `role` (`grid` / `pv` [alias `solar`] / `battery` [alias `storage`]), optionally a live price item, a 24 h
price schedule, and — when controllable — a setpoint item with clamps. The grid provider's
sign convention is canonical `+ = export, − = import`; surplus derives directly from it.

## 3. The four consumer profile classes

| # | Class | Control surface | Examples | Extra parameters | Runs today in |
|---|---|---|---|---|---|
| 1 | **Simple** | ON/OFF | DHW boiler, immersion-heater stage, fridge/freezer, dumb resistive load | on-threshold; *protection:* the full `{min,max} × {on,off}` set — min/max runtime, cooldown, **max-off** (duty-cycle guarantee) | both systems (protection set implemented in the reference) |
| 2 | **Controllable** | continuous W (or A) | EV charger, battery | `min`/`max`, ramp limit, hysteresis | both systems |
| 3 | **ModeControllable** | small **ordered** set of discrete modes, *no power setpoint* | SG-ready heat pump (blocked / normal / encouraged / forced) | `modes` list, most-restricted first | storm.house (SG-ready); reference impl |
| 4 | **Batch** (fixed program) | **start moment only**; never interrupt a running program | dishwasher (the case #3478 opened with), washing machine | `ratedW`, `runtimeHours`; machine state (idle/running/finished) | storm.house (Home Connect); reference impl |

Notes drawn from production experience:

- **Class 3 exists because you often cannot set power.** SG-ready contacts and many heat-pump
  APIs accept only encouragement levels. The planner grades availability
  (restricted / normal / encouraged / maximum) and maps it onto however many modes the device
  has; a 2-mode device degrades naturally to allow/deny.
- **Class 4 exists because you must not interrupt.** A washing program loses heat and user
  trust if paused. The planner's whole freedom is the start moment: ideally the cheapest
  *contiguous* window that still finishes before the deadline, with a **forced latest start**
  as the fallback both systems implement independently.
- **The fridge closes the class-1 symmetry.** A fridge or freezer is interruptible — but never
  for longer than a bounded off-time (food safety), and after a forced restart it needs a
  catch-up run. storm.house implements this in production (cooling devices with a max-off
  timer and a post-restart exemption window); in the reference it is the `maxOffMinutes`
  parameter (force-ON when exceeded) with `minOnMinutes` naturally providing the catch-up.
  Together the class-1 protection set is the full `{min,max} × {on,off}` matrix.
- **The complete generic-consumer parameter set** (storm.house's AN-AUS Verbraucher docs,
  carried over in full): per-consumer **priority** (deterministic surplus allocation order),
  per-consumer **surplus threshold** (`Schwellwert`, typically rated power), the
  **`runAtLevel` switching level** (`Schaltniveau` — purely energy-level-driven on/off), a
  **readiness interlock** (`startklar`), a measured-power item, and the protection matrix
  above. All implemented in the reference.
- **Classes 1–2 carry surprisingly far.** A full EV-charging coordinator (shared solar budget
  across cars, ramp, hysteresis, fast/eco modes) is expressible entirely in class 2 and was
  validated to 100% decision parity against a production coordinator under real charging.

## 4. Cross-cutting concerns (apply to any class)

1. **Demand + deadline** — `demandKwh` to deliver by a recurring daily `deadlineHour`
   (windows wrap past midnight). Drives cheapest-hours scheduling for classes 1–2, mode
   pressure for class 3, and the latest-start for class 4.
2. **Metered delivered energy** — track kWh actually delivered against the demand. Hard
   production lesson: a deadline planner *without* delivered-energy metering keeps heating
   water that is already hot. This is the first extension a real site needs, well before the
   exotic device classes.
3. **Grid-side constraints as first-class inputs** — the German §14a dimming signal and the
   Belgian capacity tariff (peak-of-month billing) are different regulations with the same
   shape: *the grid can cap you*. A taxonomy that models only devices misses them; both
   production systems treat them as top-priority inputs that outrank economics.
4. **Safety outranks economics** — breaker headroom per phase gates every dispatch decision.
5. **Priority order** across consumers — configurable in one system, deliberately fixed
   (EV > DHW > dishes > laundry > space heat > generic) in the other; the taxonomy only needs
   *an* order.
6. **Autonomy — devices with their own control intelligence** (raised by M. Storm from
   practice; confirmed by ours). Heat pumps decide for themselves even under SG-ready;
   batteries are inverter-controlled; some EV chargers run their own PV-surplus logic — and in
   OCPP the *car* decides what it actually draws below the charger's limit (`SuspendedEV` is
   the car declining). This is not a fifth class — the classes describe the control *surface* —
   but a property the model must assume everywhere:
   - every command in classes 2–4 is an **envelope** (limit / suggestion), never an order;
   - the planner must plan on **measured** power, not commanded values (the delivered-energy
     rule of §4.2, generalized: feedback is mandatory);
   - the EMS is the slow **supervisory outer loop** moving envelopes; the device is the fast
     inner loop. Ramp limits and hysteresis exist precisely to keep the two from fighting;
   - which aspects a given device lets you override is implementation-specific knowledge that
     belongs in the *binding* that normalizes the device onto a class (the binding-side half of
     the core design, §6).
   The grid applies the same pattern to the house one level up (§14a dimming, capacity
   tariffs): envelopes all the way down.
7. **The site energy level** (storm.house's *Energieniveau*, independently converged on by the
   reference's availability grading). The site computes ONE four-level state — restricted /
   normal / encouraged / maximum — from PV surplus and price, and every consumer class maps it
   onto its own control surface: SG-ready modes 1:1, EVCC charge modes 1:1, collapsed to
   allow/deny for plain on/off. Four levels is deliberate — it is exactly the granularity the
   real control surfaces have. This also yields the most intuitive user control found in
   either system: per consumer, a single "run at level ≥ N" choice ("always / when not
   expensive / when cheap / only at best price / never"), and for the UI a single glanceable
   site gauge. Documented at storm.house (Energieniveaumanagement); implemented in the
   reference as `energyLevel()` (with `modeIndex()` as the class-3 mapping, a cheapest-hours
   price lift, and the per-consumer `runAtLevel` gate as the class-1 mapping). The percentile
   layer is ported too: the reference grades the price half by rank in the day-ahead schedule
   using configurable windows (`LevelWindows`, default 4/8/4 hours) with optional seasonal
   auto-profiles (winter 8/12/4, transition 6/10/8, summer 4/8/8 — the winter-4 h/transition-8 h
   block anchors are storm.house's documented values, the rest is interpolation a site can
   override with fixed windows).

## 5. What the two production systems weigh differently (open, not blocking)

- **Planning style:** horizon energy-balance (battery + forecast remaining yield vs. remaining
  needs until next sunrise — storm.house) vs. reactive per-tick surplus dispatch (reference
  impl). Both fit the same participant model; the strategy is swappable, exactly as Kai
  intended ("could be as simple as a rule template").
- **EV control depth:** EVCC modes (off/pv/minpv/now) vs. direct OCPP amps. Both are class 2;
  the mode-based variant is effectively class 3 over an external optimizer.
- **Heat-pump efficiency modelling** (COP vs. outside temperature) refines *how much* energy a
  thermal demand needs — an input to the demand, not a new class.

## 6. Profiles as time series (idea: M. Storm)

Two refinements proposed in the #3478 discussion that fit the model cleanly:

- **Consumer prototype profiles.** Represent a load's characteristic draw as a normalized
  shape — percent of rated power over time *relative to program start* — plus a scale factor
  and min/max limits. Class 4's `ratedW × runtimeHours` is exactly the rectangular special
  case of this. The scheduler barely changes: today a candidate start is costed as the flat
  sum of hourly prices over the runtime; with a profile it becomes the price series convolved
  with the shape — same algorithm, better weights. One representational nuance: a *prototype*
  profile is relative-time (an array / metadata), not yet an openHAB `TimeSeries`; the moment
  the program is scheduled or started it projects onto absolute time and becomes a natural
  `TimeSeries`. Standardized load profiles (e.g. the German SLP a regulator publishes) can
  seed household/base-load priors before a site has learned its own — both production systems
  currently learn base load empirically.
- **Provider profiles.** Generation and price forecasts as absolute `TimeSeries` — partly
  de-facto openHAB practice already (solar-forecast bindings and day-ahead price bindings
  publish future states as `TimeSeries` today). Formalizing "a provider declares a forecast
  `TimeSeries`" means any prediction source — including AI models or third-party predictions —
  plugs in without the engine caring where the numbers came from.

Both ideas are implemented in the reference: the batch profile takes an optional
`shape="0.1,1.0,0.2"` (shape-weighted start costing, `batchStartNow`), consumers take an
optional `measure="<power item>"` (the autonomy principle: running-detection from measured
power, not switch state), and the forecast Thing publishes its full-horizon hourly forecast as
`TimeSeries` future states on a `forecastSeries` channel.

## 7. What could live where

- **openhab-core:** the `energy:` metadata namespace + the participant model
  (provider/consumer/profile records or interfaces) + discovery. Optionally binding-side
  `EnergyProvider`/`EnergyConsumer` service interfaces so integrations can register
  participants programmatically (the half the metadata approach does not cover) — previewed
  in the reference as `EnergyParticipantProvider`, an OSGi whiteboard service whose
  participants merge with the tagged items (metadata wins on a duplicate id); the interface
  is item-based and minimal so it can move to core verbatim. Builds on
  `Number:EnergyPrice` and TimeSeries, which already landed.
- **A default strategy** (surplus + cheapest-window, ~small and pure) could ship as a core
  service default, an add-on, or a rule template — genuinely Kai's call from 2023.
- **Add-ons / user land:** the sophisticated orchestration (EV coordinators, metered DHW
  planners, capacity-tariff guards, horizon planners) stays out of core. Both production
  systems are proof that this layer is where sites differ.

## 8. Reference implementation

Everything in §2–§4 (all four classes, the strategies, gates, EV coordination, actuation) is
implemented and unit-tested in this repository, package
`src/main/java/org/openhab/binding/emsmanager/internal/ems/` — see
[`EMS_ENGINE.md`](EMS_ENGINE.md) for the map, including an honest list of limitations (the
delivered-energy metering of §4.2 lives in the binding's production planner, not yet in the
simple engine model; mode loads' draw is opaque to the surplus budget).
