# Publishing checklist

> This file is for the maintainer's own reference. **Exclude it from an
> openhab-addons PR** (it's repo-meta, not part of the binding).

This bundle currently lives in the dir `org.openhab.binding.emsmanager.general`
only to avoid clashing with a separate production copy in the same checkout.
Its artifactId is already the canonical `org.openhab.binding.emsmanager`.

## A. This repo (your own GitHub)
1. This is the standalone source repo (canonical artifactId
   `org.openhab.binding.emsmanager`); `LICENSE` + `NOTICE` are at the root.
2. **Build** — openHAB add-ons build *inside* an `openhab-addons` checkout
   (the reactor provides the formatter, static-analysis rulesets and bundle
   tooling; it does **not** build standalone). Copy the bundle into
   `openhab-addons/bundles/org.openhab.binding.emsmanager/` and run
   `mvn clean install` there. Ship `target/*.jar` for users to drop into
   `<openhab>/addons/`.
3. **Push** — `git remote add origin <your-github-url>` then
   `git push -u origin main`.

No `bundles/pom.xml` / BOM edits are needed for a standalone repo — those are
only for the openhab-addons monorepo reactor (section B).

## B. Submit to openhab-addons (only after the design discussion is greenlit)
In a **fresh fork of `openhab/openhab-addons`** (where there is no clashing
production binding), place the bundle at
`bundles/org.openhab.binding.emsmanager/` and add the two reactor entries:

### 1. `bundles/pom.xml` — module list (alphabetical, between `emotiva` and `energenie`)
```xml
    <module>org.openhab.binding.emotiva</module>
    <module>org.openhab.binding.emsmanager</module>      <!-- add this line -->
    <module>org.openhab.binding.energenie</module>
```

### 2. `bom/openhab-addons/pom.xml` — dependency (alphabetical, between `emotiva` and `energenie`)
```xml
    <dependency>
      <groupId>org.openhab.addons.bundles</groupId>
      <artifactId>org.openhab.binding.emsmanager</artifactId>
      <version>${project.version}</version>
    </dependency>
```

### 3. Verify the full reactor build + analysis is clean
```
JAVA_HOME=/path/to/jdk-21 mvn clean install -pl :org.openhab.binding.emsmanager -am
```
Expect: Spotless clean, Checkstyle 0 errors, PMD/SpotBugs clean, tests pass.

### 4. Commit + PR conventions
- One commit, **DCO sign-off**: `git commit -s -m "[emsmanager] Initial contribution"`.
- Author identity: `Stamate Viorel <stamate.viorel@gmail.com>` (matches every file's `@author`).
- Target the `main` branch.
- PR body: short, in your own voice; no "what changes" section; no AI-attribution footer.
- Squash to a single commit if asked.

## C. Do this FIRST
Open the **architecture/design discussion** (community forum → Add-ons, or a
GitHub Discussion) before the PR — an EMS that orchestrates other bindings is
an application layer, so the maintainers should agree on whether it belongs in
openhab-addons, the Marketplace, or split (providers upstream / orchestration
on the Marketplace). A draft post is in the project notes.
