# Starter scaffold for the pedestal-umadb build kit — working notes

Self-contained notes for building the kit's **starter scaffold**: the minimal project a new
`pedestal-umadb` project starts from. Written so the work can be picked up without the session
that produced it. Check `git log` and `git status` first — this document describes intent, the
repository describes what was actually done.

## Context

| What                                                                      | Where                                             |
|---------------------------------------------------------------------------|---------------------------------------------------|
| This build kit                                                            | `~/Projekter/em-studio/build-kits/pedestal-umadb` |
| Reference project the kit is modelled on                                  | `~/Projekter/gastronak`                           |
| Event sourcing library (`defevent`, `defview`, `automation`, `gwt`, `gt`) | `~/Projekter/ledjer`                              |
| Sibling kit with a working scaffold, for shape only (Java/Spring)         | `~/Projekter/em-studio/build-kits/umadb`          |

A build kit is installed into a project by the eventmodelers CLI. It consists of:

- `.build-kit/` — the agent runtime (ralph loop, prompts in `lib/`, `CLAUDE.md` with the build rules)
- `.claude/skills/` — the skills; `build-state-change`, `build-state-view` and `build-automation` are
  this kit's own, the rest are generic eventmodelers skills
- a **root scaffold** — the starter project. This kit doesn't have one yet; that is this task.
  `README.md` at the kit root is still the CLI's placeholder text describing it.

The placeholder README says a finished kit's `.build-kit/`, `.claude/skills/` and root scaffold are
copied into `stacks/<name>/templates/` in the eventmodelers-cli repository and registered in `STACKS`
(`cli.js`); see that repo's README, "Adding a stack". **Check that repository first** for how the
scaffold must be laid out (`templates/root/`?) and whether anything at the kit root, such as this
`docs/` directory, would be copied into projects.

## State of the kit when this was written

- The three build skills are complete. `build-automation` was rewritten from gastronak's
  `slices/user_management/new_user`; `build-state-view` and `build-state-change` describe pages,
  handlers, routes, error handling and coercion.
- `.build-kit/CLAUDE.md` routes slices on `sliceType`, defines the base namespace, and defines the
  quality checks (step 5).
- The install manifest says `"stack": "pedestal-umadb"`; `.gitignore` now lets it be tracked while
  both `.eventmodelers/config.json` files (credentials) stay ignored.
- Gastronak got the reference implementation of the coercion namespace (commit `4465eee`).

## The contract the scaffold must satisfy

Everything below is something the skills or `.build-kit/CLAUDE.md` assume exists. A slice built into the
scaffold must work without the agent creating any of it. `{basepackage}` below is the scaffold's own
namespace prefix (see decision D1).

1. **Base namespace** — `.build-kit/CLAUDE.md` resolves `{basepackage}` from the namespace of
   `src/**/system.clj` minus `.system`. So the scaffold needs exactly one `system.clj`, at
   `src/{basepackage-path}/system.clj`.
2. **`system.clj`** in the shape the skills edit (gastronak's `src/dk/planm/gastronak/system.clj`):
   - a donut.system `base-system` with `[:shared :app-config]`, `[:shared :event-store]`, an
     `:automations` group (empty map), and `[:webserver :routes]` whose start fn builds routes from a
     `(set/union ...)` of slice `routes` — slices add themselves to both the require list and the union;
   - `[:webserver :routes]` must call `utils/register-routing-table` (start) and
     `utils/clear-routing-table` (stop) — `url-for-safe` depends on it;
   - the connector's interceptor chain: session (cookie attrs), flash, content-type, query-params,
     body-params, `csrf/anti-forgery`, secure headers, not-modified, `file "public/"`, file-info,
     `event-store-interceptor`, `grafter-interceptor` (if D3), `html-body`, `hiccup-interceptor`;
   - named systems `:base`, `:dev`, `:prod`.
3. **`slices/events.clj`** — empty apart from the malli registry setup (gastronak's first lines):
   `(mr/set-default-registry! (mr/composite-registry (m/default-schemas) (met/schemas)))`.
   The `:time/...` schemas depend on it.
4. **`common/coercion.clj`** — copy gastronak's `src/dk/planm/gastronak/common/coercion.clj` and its
   test `test/dk/planm/gastronak/common/coercion_test.clj` (commit `4465eee`), changing only the
   namespace. Its contract is in `build-state-change`, "The project's coercion namespace":
   `zone`, `transformer`, `instant->local-date-time`. Decide the zone (D4).
5. **`common/interceptors.clj`** — `event-store-interceptor`, `hiccup-interceptor` (wraps hiccup bodies
   in the page layout), `authenticated-interceptor`, `authorized-interceptor` (see D2), and
   `grafter-interceptor` if D3.
6. **`common/utils.clj`** — only `url-for-safe`, `->uuid`, `register-routing-table`,
   `clear-routing-table` and the `url-for` var they maintain. Gastronak's file also has Danish number
   and date formatters and week helpers; leave those out.
7. **`common/event_store.clj`** — the donut component opening the UmaDB store. Gastronak hardcodes
   `localhost:50051`; the scaffold should read host and port from `config.edn`.
8. **The UmaDB store for ledjer** — see D5.
9. **`deps.edn`** — at least: clojure, ledjer (git sha **at or after `de3fba7`**: query validation and
   reliable in-memory subscriptions), `io.pedestal/pedestal.jetty` 0.8.x, huff, malli, aero,
   donut system, umadb-java-client 0.7, deed-core (used by the UmaDB store), tick, logback; aliases:
   - `:test` with `cognitect.test-runner` and `:exec-fn cognitect.test-runner.api/test` — the skills
     run `clojure -X:test :nses '[ns.symbol]'`;
   - `:run` pointing at a main namespace **that exists** (gastronak's `:run` points at
     `dk.planm.gastronak.main/start-service`, which doesn't exist).
10. **Quality checks** (`.build-kit/CLAUDE.md` step 5) must all work in the scaffold:
    - `clojure -X:test :nses '[{basepackage}.common.coercion-test]'`
    - `clojure -M -e "(require '{basepackage}.system)"`
    - `clojure -X:test` (must pass completely in the scaffold — it has no pre-existing failures)
11. **Flash and sessions** — change handlers write errors to `[:response :flash :errors/{command}]`
    and pages read them, so the session and flash middlewares must be in the chain (item 2).

## Proposed files

| File                                                                                                 | Based on (gastronak, unless stated)                                   |
|------------------------------------------------------------------------------------------------------|-----------------------------------------------------------------------|
| `deps.edn`                                                                                           | `deps.edn`, trimmed (item 9)                                          |
| `mise.toml`                                                                                          | Java, Clojure and Node (`node` is needed by the `.build-kit` runtime) |
| `docker-compose.yml`                                                                                 | `umadb/umadb` with a **pinned** version and port 50051 — see pitfalls |
| `resources/config.edn`                                                                               | `resources/config.edn`, trimmed to webserver + UmaDB host/port        |
| `resources/logback.xml`, `test-resources/logback-test.xml`                                           | same files                                                            |
| `src/{basepackage-path}/system.clj`                                                                  | `system.clj`, with no slices                                          |
| `src/{basepackage-path}/main.clj`                                                                    | new: start the `:prod` named system                                   |
| `src/{basepackage-path}/common/{coercion,event_store,interceptors,utils}.clj`                        | items 4–7                                                             |
| `src/{basepackage-path}/slices/events.clj`                                                           | item 3                                                                |
| `test/{basepackage-path}/common/coercion_test.clj`                                                   | item 4                                                                |
| `test/{basepackage-path}/system_test.clj`                                                            | new: the system starts and stops with the in-memory store             |
| `README.md`                                                                                          | replace the placeholder: start UmaDB, run, test, how slices are built |
| if D3: `shadow-cljs.edn`, `package.json`, `src/{basepackage-path}/scions.cljs`, `common/grafter.clj` | same files, `scions.cljs` without gastronak's drag-and-drop scions    |

## Not to copy from gastronak

Penpot scripts and skill, `public/css/main.css` design system, Danish texts and formatters, the
mailer, datalevin, NATS, embeddings, OpenTelemetry, the `infrastructure/` directory, `artwork/`,
`design/`, `dev/` helpers, and every slice.

## Decisions to make

**My general rule of thumb:** This is a kit for me to quickly spin up new SaaS projects, and needs to be relatively feature-complete.

**D1. The scaffold's namespace.** The `umadb` kit uses a fixed package (`io.umadb.quickstart`) and the
base-namespace rule picks it up from the code, so no install-time placeholder is needed. Proposal:
`quickstart`. Check whether the eventmodelers CLI can substitute a project name instead.

**D2. Authentication.** Both page and change routes always get `authenticated-interceptor`, which
redirects to `:user-management/login-page`. The scaffold has no login slice. Options:

- ship a minimal login (session-based, like gastronak's `user_management` slices) — realistic, but
  pulls registration, password hashing and mail in with it;
- make the rule conditional in the skills: "only if the project has an `authenticated-interceptor`",
  and ship the scaffold without one — smaller, but every slice in a new project is then public until
  someone adds authentication;
- ship the interceptor with a configurable login route and no login slice — the board would then model
  login as ordinary slices.

**D2. My decision:** ship the interceptor with a configurable login route and no login slice — the board would then model login as ordinary slices.

**D3. Scions / ClojureScript.** `build-state-view` writes scions into `scion.cljs` and requires them in
`scions.cljs`. Including them costs `shadow-cljs.edn`, `package.json`, `scions.cljs`, the grafter
component and interceptor, and a JS build step. Leaving them out means changing the skill to "only if
the project has `scions.cljs`".

**D3. My decision:** Add Scions / ClojureScript to the build-kit

**D4. The time zone.** The coercion namespace needs one fixed zone. Either the scaffold ships with a
default (and `README.md` says to change it), or it ships with a value that fails loudly until set. The
skills already say: never guess or change the zone while building a slice.

**D4. My decision:** Ship with a value that fails loudly until set, and add a note in the README to set it.

**D5. Where the UmaDB store lives.** It is gastronak's `src/dk/planm/ledjer/store/umadb.clj`, with its
comparison test `test/dk/planm/ledjer/store/umadb_test.clj`; ledjer itself only has
`store/memory.clj`. Moving it into ledjer (next to `memory.clj`, with the comparison test) means every
project gets it from the dependency; copying it into the scaffold duplicates it per project.
Recommendation: move it into ledjer, then depend on that sha.

**D5. My decision:** This build kit should actually be about pedestal + ledjer, not strictly UmaDB, so we should not add the umadb dependency. I have moved the store dependency out of gastronak and into ledjer though.

## How to verify the scaffold

1. In a copy of the scaffold, the three quality checks in contract item 10 pass.
2. Run the app; `GET /` answers (add a trivial route or a 404 check).
3. Build one slice of each type into it with the kit, e.g. from the demo board slices in
   `.build-kit/.slices/Understanding Eventsourcing/` (`additem` = state change, `cartitems` = state
   view with a screen, `publishcart` = automation). Each must reach `Done` without the agent creating
   project infrastructure. Note: `publishcart` needs an external publisher dependency, so with the
   kit's rules it should end up `Blocked` — that is the expected result, and a good test of the rule.
4. Run the full suite again after the three slices.

## Pitfalls found while writing the kit

- **Parallel edits to one file race.** Two edits to the same file in one parallel tool batch truncated
  `build-state-change/SKILL.md` once. Edit a file one change at a time, and check `git diff` line counts.
- **`clojure` outside a project directory** — the `mise` shim falls back to a system `clojure` that
  rejects `-Sdeps` and `-M`. Run scratch scripts from a project directory (e.g. gastronak), adding
  paths with `-Sdeps '{:paths [...]}'`.
- **`:nses` takes symbols** — `clojure -X:test :nses '["ns"]'` (strings) runs zero tests silently.
- **malli error locations** — use `:in` (the path in the value), not `:path` (the path in the schema;
  the time schemas add an inner step).
- **`#time/...` literals in tests** need tick's data readers loaded; use `LocalDate/parse` etc.
- **`mett/time-transformer`'s `formats` argument** doesn't change its parsers (they always fall back to
  the defaults); per-schema `:formatter` properties do. That is why the coercion namespace has its own
  `:time/instant` decoder.
- **Gastronak's full suite has a pre-existing failure** in `login` (`login-user-change`, "correct
  password + email": no `user-session-created` event is appended). Not caused by the kit work; it is
  why step 5 only requires the slice's own tests and the system load check to pass.
- **Ledjer queries are validated.** A `nil` tag value or a tag none of the event types have throws;
  `"*"` matches any value.
