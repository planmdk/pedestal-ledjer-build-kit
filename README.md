# Pedestal + Ledjer starter

A minimal Clojure project for event-sourced web applications: [Pedestal](https://pedestal.io/) for HTTP,
[ledjer](https://github.com/planmdk/ledjer) for events, views and automations, donut.system for wiring, and
[shadow-graft](https://github.com/thheller/shadow-graft) scions for client-side behaviour. It is ready for the
`build-state-change`, `build-state-view` and `build-automation` skills in `.claude/skills/` to generate slices
into.

No slices are included: each one is generated from a board's `slice.json`.

## Before you start

1. **Set the project's time zone.** In `src/quickstart/common/coercion.clj`, `zone` is `nil`. Set it to your
   zone, for example `(ZoneId/of "Europe/Copenhagen")`. Until you do, anything that reads or shows a
   local date-time (`DateTime` fields, `datetime-local` inputs) throws "The project time zone is not set".
   That is on purpose: slices must never guess a zone, so a slice that needs it stays blocked until you
   have chosen one.
2. **Install the tools.** `mise install` gives you Java 25, Clojure and Node (the `.build-kit` runtime
   needs Node).
3. **Install the JavaScript dependencies and build the scions.** Needed once, and after every change to a
   `scion.cljs`:

   ```bash
   npm install
   clojure -M -m shadow.cljs.devtools.cli release scions
   ```

   This writes `public/js/scions.js`, which is not committed. For development, replace `release` with
   `watch`.

## Running

```bash
clojure -X:run          # starts the :prod system on http://localhost:8080
```

For a REPL, `clojure -M:nrepl` starts shadow-cljs' `clj-repl`. Then:

```clojure
(require '[donut.system :as ds] 'quickstart.system)
(def system (ds/signal (ds/system :dev) ::ds/start))
(do (ds/signal system ::ds/stop) :stopped)
```

Without slices every route answers 404. That is expected.

## Testing

```bash
clojure -X:test                                          # everything
clojure -X:test :nses '[quickstart.common.coercion-test]'  # one namespace: symbols, not strings
clojure -M -e "(require 'quickstart.system)"             # the system loads, with every slice's wiring
```

`:nses` must be a vector of symbols. Strings run zero tests without an error.

## What's here

```
src/quickstart/
├── system.clj              donut.system: config, event store, automations, routes, connector and server
├── main.clj                entry point for `clojure -X:run`
├── scions.cljs             requires every slice's scion; provides `reinitialize-scions`
├── common/
│   ├── coercion.clj        the project's time zone and the transformer for request values
│   ├── event_store.clj     the event store component
│   ├── interceptors.clj    layout, session user checks, event store and grafter on the request
│   ├── grafter.clj         shadow-graft component, so pages can embed scions
│   ├── scion_utils.cljs    helpers for scions
│   └── utils.clj           url-for-safe, ->uuid and the routing table they depend on
└── slices/
    └── events.clj          every event in the system (malli registry set up)
resources/config.edn        webserver settings per profile (:dev, :test, :prod)
public/                     static files; js/idiomorph.min.js is vendored
```

The namespace prefix is `quickstart`. The kit installs this scaffold as it is, so rename it in your project
(directories, namespaces, `shadow-cljs.edn`, `deps.edn`'s `:run`). Everything finds the prefix from
`src/**/system.clj`.

### Authentication

`authenticated-interceptor` lets requests through that have a `:user-id` in their session, and redirects the
rest to the route named `:user-management/login-page`. The scaffold has no login: model it on your board,
and the routes will start working. Until a route with that name exists, unauthenticated requests get a plain
401.

`authorized-interceptor` answers 404 unless the session user is tied to the entity in the path by a given
event type.

### Layout and styling

`hiccup-interceptor` wraps every hiccup response in a bare HTML page with `scions.js` and Idiomorph. There is
no stylesheet and no design system, on purpose. The `build-state-view` skill says styling follows the
project's own `CLAUDE.md`, so describe your design system there before building pages.

### Scions

Interactive behaviour is a scion: a `scion.cljs` in the slice, required in `src/quickstart/scions.cljs`
or it is never compiled. After a scion has set itself up, it should call
`(scion-utils/mark-initialized container)`. When a page morphs new HTML into the document (see
`scion-utils/morph-html-into-document`), the `reinitialize-scions` scion sets up the scions that are not
marked yet.

`public/js/idiomorph.min.js` is a minified build of [Idiomorph](https://github.com/bigskysoftware/idiomorph)
copied from another project. Replace it with a release of your choice.

## Events are stored in memory

`common/event_store.clj` builds ledjer's in-memory store. Events are lost when the process stops, which suits
development and tests but not production. To persist them, use ledjer's UmaDB store:

1. In `deps.edn`, add ledjer's `umadb` sub-project next to `core`, at the same sha:

   ```clojure
   io.github.planmdk/ledjer-umadb {:git/url "https://github.com/planmdk/ledjer"
                                   :git/sha "<the same sha as io.github.planmdk/ledjer>"
                                   :deps/root "umadb"}
   ```

2. Run an [UmaDB](https://umadb.io/) server and put its host and port in `resources/config.edn`.
3. In `common/event_store.clj`, build `dk.planm.ledjer.store.umadb/umadb-event-store` with that host and
   port, call `.connect` on it when starting, and `close-umadb-client` when stopping. Keep the in-memory
   store for the `:test` profile.

## Building slices

The agent runtime in `.build-kit/` reads slices from the board and builds them with the skills.
`.build-kit/CLAUDE.md` has the rules; in short, for each slice it:

- builds it in `src/quickstart/slices/{context}/{slicename}/slice.clj`, with its test, and adds its events to
  `slices/events.clj`;
- adds the slice to `system.clj` (its namespace in the `:require`, its `routes` in the `set/union`, or an
  automation's `component-map` under `:automations`) and its scion to `scions.cljs`;
- runs the slice's tests, checks that the system loads, and runs the full suite.

Everything the skills assume exists is in `common/`. Slices do not change it.
