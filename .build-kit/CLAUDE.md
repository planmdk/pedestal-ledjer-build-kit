# Project Configuration

Read events in `src/{basepackage-path}/slices/events.clj` to understand the global structure.

## The base namespace

`{basepackage}` is this project's own namespace prefix, not a fixed value. Resolve it, in order:

1. the namespace of `src/**/system.clj` without `.system` (`dk.planm.gastronak.system` → `dk.planm.gastronak`);
2. the part before `.slices.` in the namespace of any existing slice.

Never hardcode one. `{basepackage-path}` is the same prefix as a source path: `.` becomes `/` and `-` becomes
`_` (`dk.planm.gastronak` → `dk/planm/gastronak`). The same `-` → `_` rule applies to every namespace segment
below it: slice `add-item` in context `understanding-eventsourcing` is namespace
`{basepackage}.slices.understanding-eventsourcing.add-item.slice` in
`src/{basepackage-path}/slices/understanding_eventsourcing/add_item/slice.clj`.

## File Structure Constraints

- **Strict Path Limitation**: if not instructed otherwise, only check the slice's own directory,
  `src/{basepackage-path}/slices/{context}/{slicename}/` (`slice.clj`, `scion.cljs`, `shared.cljc`), its test
  `test/{basepackage-path}/slices/{context}/{slicename}/slice_test.clj`, and the shared files the build skills
  say to edit: `slices/events.clj`, `system.clj` and `scions.cljs`
- **Slice Organization**: Each feature/domain should be organized as a separate slice

## Code Standards

- **Language**: Clojure and Clojurescript only
- **Module System**: standard Clojure namespaces

## Development Guidelines

1. Each slice should be self-contained and focused on a specific domain
2. Maintain clear separation of concerns within each slice
3. Use the library `ledjer` to implement read models (`defview`, or `defpersistedview` where the build skill calls for it), automations (`automation`), and GWT's (`gt` and `gwt`) - see each build skill's own reference to that infrastructure

Slice names are kebab-cased and all lowercase characters, e.g. "Add item" becomes "add-item".

**Coercion and dates**: the project provides `src/{basepackage-path}/common/coercion.clj` with its one fixed time zone and the transformer every request value is coerced with. Every `DateTime` field is a `:time/instant`, read from and shown in that zone. See "The project's coercion namespace" in the `build-state-change` skill. It is project code: never create or change it while building a slice — if it is missing, the slice is `Blocked`.

Do not change test files unless explicitly instructed, or the change brings the test in line with slice.json (e.g. step 4's field/spec diff).

At the start of every session, read `.build-kit/AGENTS.md` if it exists to load accumulated project learnings.

When starting to work on a slice, invoke the `update-slice-status` skill with `InProgress` status before doing anything else.

## Building a Slice

**CRITICAL: You MUST always use the provided skills to build slices. NEVER implement a slice manually.**
**ALL fields, event names, command names, and business rules MUST come exclusively from slice.json. Do NOT invent, assume, or guess any field or logic not present in the slice definition.**

**Default: make a reasonable assumption and build the slice.** If a detail is unclear or missing (an example value, a field type, a status mapping, a referenced event that isn't modeled yet, ...), pick the most sensible interpretation from `slice.json`, its specifications and the surrounding model, build it, and record each assumption in one line in `progress.txt` and as a code comment. Ambiguity alone is never a reason to stop. **Only if the slice literally cannot be built** — nothing runnable can be produced even with sensible assumptions — invoke `/request-feedback` with the specific question; it posts the question as a comment on the slice and marks it `Blocked`. That must be the absolute exception; a `Blocked` slice should mean "impossible without a human", never "the agent preferred to ask".

When asked to build a slice, always follow this flow:

1. Read the slice definition from `.build-kit/.slices/<context>/<slicename>/slice.json`.
2. Determine the slice type from `sliceType` in slice.json:
   - **Automation** — `"AUTOMATION"` → invoke `/build-automation`
   - **Translation** — `"TRANSLATION"` → read `description` and `notes` from slice.json for hints; default to `/build-automation` if nothing else is specified
   - **State-view** — `"STATE_VIEW"` → invoke `/build-state-view`
   - **State-change** — `"STATE_CHANGE"` → invoke `/build-state-change`
   - **Missing or `"UNDEFINED"`** — fall back on the arrays, in this order:
     - `processors` non-empty → `/build-automation`
     - `readmodels` non-empty and `commands` empty → `/build-state-view`
     - `commands` non-empty → `/build-state-change`
     - anything else (e.g. only `events`, or nothing at all) is a modelling error: invoke `/request-feedback` explaining that the slice has no command, read model or processor to build. Do not write any code for it.
3. Invoke the matching skill and follow its instructions completely. Do not deviate.
4. **Verify against slice.json**: After the skill completes, check that every command field, event field, and specification in slice.json appears in the implementation. No invented fields — if it is not in slice.json, it must not be in the code. This applies even when the slice was previously `Done` and reappears as `Planned` — never dismiss a mismatch as "already implemented" or harmless drift; diff slice.json against the code field by field and update the code to match every change.
5. Run the quality checks, in this order:
   1. **The slice's own tests** — `clojure -X:test :nses '[{basepackage}.slices.{context}.{slicename}.slice-test]'`
      (namespace symbols, not strings: strings match nothing and run zero tests). They must pass.
   2. **The system loads** — `clojure -M -e "(require '{basepackage}.system)"`. This is the only check that
      covers the slice's wiring in `system.clj`, which the slice tests never load. It must succeed.
   3. **The full suite** — `clojure -X:test`. A failure is yours only if it is caused by what this slice
      changed (e.g. an event in `events.clj` whose fields changed); fix the code, never another slice's test.
      Any other failure was there before: record it in `progress.txt` and leave it alone.
6. If checks 1 and 2 pass and the full suite has no failures caused by this slice, commit with
   `feat: [Slice Name]` and set slice status to `Done`.

## Example Slice Structure

```
src/{basepackage-path}/
├── system.clj                               ← add slice's main namespace to the require; add slice's `routes` to the `set/union`, or an automation's `component-map` under `:automations`, or a persisted view's under `:views`
├── scions.cljs                              ← add slice's scion namespace to the require
├── common/
│   ├── coercion.clj                         ← project code, never changed by a slice (see "Coercion and dates")
│   ├── interceptors.clj                     ← authenticated/authorized interceptors used in routes
│   └── utils.clj                            ← `url-for-safe`, `->uuid`, ...
├── slices/
│   ├── events.clj                           ← namespace containing every event in the system
│   ├── {context}/                           ← `-` → `_`, e.g. understanding_eventsourcing
│   │   ├── {slicename}/                     ← read, write and automation slices; `-` → `_`, e.g. add_item
│   │   │   ├── slice.clj                    ← the main namespace: read models, commands, automations, routes, hiccup go here
│   │   │   ├── scion.cljs                   ← frontend augmentation code (scions) for the slice, if any
│   │   │   └── shared.cljc                  ← code shared between slice implementation and frontend, if any

test/{basepackage-path}/
└── slices/{context}/{slicename}/slice_test.clj
```
