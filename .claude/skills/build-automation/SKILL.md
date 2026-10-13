---
name: build-automation
authors:
  - Matthias Varberg Ingesman
description: >
  Implement automation slices (to-do list → command) using the ledjer Clojure library in this
  project's one established pattern: a private `defview` to-do list, a change function that
  performs the side effect and records the outcome as events, and an `l/automation` that runs
  the change for every to-do item whenever a trigger fires, started as a donut.system component.
  Use when implementing an automation / processor slice from a slice.json event model in this
  project. There is exactly one supported style - do not offer alternatives.
---

# Build Automation Slice

> Before doing anything else, read the slice definition from `.slices/{Context}/{slicename}/slice.json`.
> This file is the **source of truth** for all fields, events, and metadata — never invent
> fields not defined there. Keep this line verbatim; it applies to every stack.

---

## What an Automation Slice is

One pattern only. Directory layout is flat - `src/{basepackage}/slices/{context}/{slicename}/slice.clj`, shared
`src/{basepackage}/slices/events.clj` for the events. Directory names are the namespace segments with `-`
replaced by `_` (namespace `...slices.understanding-eventsourcing.publish-cart.slice` lives in
`.../slices/understanding_eventsourcing/publish_cart/slice.clj`).

In the event model, an automation (the processor) reads a **to-do list** (its INBOUND read model) and
issues a **command** for every item on it. The command's OUTBOUND events record the outcome, and the
outcome events take the item off the to-do list again.

An automation slice consists of:

- A private to-do list `defview` — this slice's own copy of the processor's INBOUND read model
- A change function of `event-store`, the external dependencies, and `payload` that performs the side effect
  (if any) and appends the outcome events
- An automation function returning an `l/automation`
- A donut.system `component-map` that starts and stops the automation

All this goes into the `slice.clj` file. There is no hiccup, page handler, or `routes` in an automation slice.

## How ledjer runs an automation

Read this before writing any code — the rules in the later steps follow from it:

- `(l/automation event-store triggers body-fn)` subscribes to the triggers and calls `(body-fn event-store)`
  every time one fires. It returns a stop function.
- **`body-fn` is not given the triggering event.** It must find its work by calling the to-do `defview`.
  That is why every automation has a to-do list.
- Triggers are a set of any combination of: event types (`(ev/some-event)` — an event constructor called
  with no arguments returns its type), `[event-type tag-bindings]` pairs, and core.async channels such as
  `(la/periodic ms)`.
- Triggers fire only on **new** events. Items already on the to-do list when the system starts are only
  processed at the next trigger.
- `body-fn` runs inside a core.async `go-loop`. **An exception escaping `body-fn` silently stops the
  automation for good.** The change function must turn expected failures into events, and the body must
  catch and log anything else per item.

## Step 1 — Read the slice.json

From the slice definition, extract:
- **sliceName** — the slice title
- **context** — the bounded context
- **processors[]** — the processor; its INBOUND `READMODEL` dependency is the to-do list, its OUTBOUND `COMMAND` dependency is the command
- **commands[]** — the command with its data fields; its OUTBOUND `EVENT` dependencies are the possible outcomes
- **events[]** — events defined in this slice
- **specifications[]** — test scenarios (given/when/then)

> **Comments & description**: each element carries a `comments: string[]` array (board comments) and a `description` field — use them as implementation hints, and resolve consumed comments via `POST <BASE_URL>/api/org/<ORG_ID>/boards/<BOARD_ID>/nodes/<nodeId>/comments/<commentId>/resolve`.

**The to-do read model is defined in another slice.** The processor only references it by `id` and
`title`. Find its definition by searching `.build-kit/.slices/{context}/*/slice.json` for a `readmodels[]`
entry with the same `id`. If it isn't there, run `/load-slice` for the slice that owns it. The read model's
`fields` and INBOUND `EVENT` dependencies are what Step 3 builds the private copy from.

**Example slice.json processor and command (abbreviated)** — slice "Publish Cart":

```json
"processors": [{
  "title": "automation",
  "type": "AUTOMATION",
  "dependencies": [
    { "id": "1ecaf8a8-...", "type": "OUTBOUND", "title": "Publish Cart", "elementType": "COMMAND" },
    { "id": "e3dc2c7e-...", "type": "INBOUND", "title": "Submitted Cart Data", "elementType": "READMODEL" }
  ]
}],
"commands": [{
  "title": "Publish Cart",
  "fields": [
    { "name": "aggregateId", "type": "UUID", "cardinality": "Single", "idAttribute": false },
    { "name": "orderedProducts", "type": "Custom", "cardinality": "List", "idAttribute": false },
    { "name": "totalPrice", "type": "Double", "cardinality": "Single", "idAttribute": false }
  ],
  "dependencies": [
    { "type": "OUTBOUND", "title": "Cart Publication Failed", "elementType": "EVENT" },
    { "type": "OUTBOUND", "title": "Cart Published", "elementType": "EVENT" },
    { "type": "OUTBOUND", "title": "External Cart Published", "elementType": "EVENT" },
    { "type": "INBOUND", "title": "automation", "elementType": "AUTOMATION" }
  ]
}]
```

and the read model `e3dc2c7e-...` is found in `.build-kit/.slices/Understanding Eventsourcing/submittedcartdata/slice.json`,
with the fields `aggregateId`, `orderedProducts`, `totalPrice` and the INBOUND event `Cart Submitted`.

## Step 2 — the events - only if they don't already exist

**Check `src/{basepackage}/slices/events.clj` first;** add any events which do not exist to this file, taken from:

- all the to-do read model's `INBOUND` events
- all the command's `OUTBOUND` events
- all the slice's `events`

Write them exactly as described in step 2 of the `build-state-change` skill (`defevent`, `:schema` from
the fields, `:tags` from the `idAttribute: true` fields).

## Step 3 — the external dependencies

Decide what the command needs from outside the event store to carry out its side effect (sending mail,
calling an HTTP API, publishing to a message broker, ...). Use the command's `description`, its comments,
and the names of its OUTBOUND events (e.g. "External Cart Published", "Welcome Email Sent") as evidence.
A command with no side effect has no external dependencies.

Every external dependency must already exist as a component under `:shared` in
`src/{basepackage}/system.clj` (e.g. `[:shared :mailer]`). Pass it to the change function as an argument.

**If a dependency the command needs does not exist, do not create it and do not stub it.** Invoke
`/request-feedback` naming the dependency that is missing and what the command needs it for. This marks
the slice `Blocked`.

## Step 4 — the automation slice

```clojure
(ns {basepackage}.slices.{context}.{slicename}.slice
  (:require
   [{basepackage}.slices.events :as ev]
   [dk.planm.ledjer :as l]
   [dk.planm.ledjer.automation :as la]
   [dk.planm.ledjer.event :as le]
   [donut.system :as ds]
   [io.pedestal.log :as log]
   [malli.core :as m]))

;; To-do list view (step 4.1)

;; Change function (step 4.2)

;; Automation function (step 4.3)

;; Component (step 4.4)
```

## Step 4.1 — the to-do list view

A `defview` built from the to-do read model's definition (Step 1), exactly as in step 3.1 of the
`build-state-view` skill, with two additions:

- **The initial value is an empty collection of items** — `#{}` when an item is identified by all its
  fields, otherwise `{}` keyed by the item's id. Each item holds exactly the read model's fields.
- **The view also has a reducer for every outcome event that ends the processing of an item**, which
  removes the item. Without it the automation runs the command for the same item on every trigger,
  forever. An outcome that should be retried (e.g. "... Failed" when the slice describes retries) gets no
  reducer here.

The view is private to this slice: define it here even if the owning state-view slice already has a
`defview` for the same read model. Never require another slice's namespace.

```clojure
(l/defview submitted-cart-data
  {}
  (ev/cart-submitted)
  (fn cart-submitted-reducer
    [acc event]
    (let [{:keys [aggregate-id ordered-products total-price]} (le/payload event)]
      (assoc acc aggregate-id {:aggregate-id aggregate-id
                               :ordered-products ordered-products
                               :total-price total-price})))

  (ev/cart-published)
  (fn cart-published-reducer
    [acc event]
    (dissoc acc (:aggregate-id (le/payload event)))))
```

## Step 4.2 — the change function

**`{schema}` is a Malli schema derived from the command's fields**, as in step 3.1 of the
`build-state-change` skill. The payload comes from the to-do view, so it is already typed — use plain
`m/coerce` without a string transformer.

External dependencies come **before** `payload`, so `l/gwt` can pass them in tests
(`:when [#'slice/{commandname}-change stub-dependency payload]`).

```clojure
(defn {commandname}-change
  [event-store {dependency} payload]
  (let [coerced-payload (m/coerce {schema} payload)]
    (try
      ;; ... the side effect, using {dependency}
      (l/append event-store [(ev/{success-event} ...)])
      (catch Exception e
        (l/append event-store [(ev/{failure-event} ...)])))))
```

- When the command has **one** OUTBOUND event, append it after the side effect and let failures throw —
  step 4.3 catches and logs them, and the item is retried at the next trigger.
- When the command has a **success and a failure** event, catch the side effect's exception and append
  the failure event, as above.
- Only add retry limits, abort events, or other logic when slice.json describes them. In that case source
  the earlier attempts with `l/source` — e.g. "abort after 5 failed attempts":

```clojure
(let [failures (l/source event-store #{{:event-types #{(ev/{failure-event})}
                                        :tag-bindings {(le/payload-path :{id-field}) id}}})]
  (if (<= 4 (count failures))
    (l/append event-store [(ev/{aborted-event} ...)])
    (l/append event-store [(ev/{failure-event} ...)])))
```

**Following the example above** (the publisher is assumed to exist as `[:shared :cart-publisher]`):

```clojure
(defn publish-cart-change
  [event-store cart-publisher payload]
  (let [coerced-payload (m/coerce
                         [:map
                          [:aggregate-id :uuid]
                          [:ordered-products [:vector ...]]
                          [:total-price :double]]
                         payload)]
    (try
      (cart-publisher coerced-payload)
      (l/append event-store [(ev/external-cart-published coerced-payload)
                             (ev/cart-published {...})])
      (catch Exception e
        (l/append event-store [(ev/cart-publication-failed {...})])))))
```

## Step 4.3 — the automation function

```clojure
(defn {slicename}-automation
  [event-store {dependency} & {:keys [interval-ms]}]
  (l/automation
   event-store
   (cond-> #{(ev/{todo-inbound-event-1}) ... (ev/{todo-inbound-event-n})}
     interval-ms (conj (la/periodic interval-ms)))
   (fn [event-store']
     (doseq [item (vals ({todo-view} event-store' {}))]
       (try
         ({commandname}-change event-store' {dependency} item)
         (catch Exception e
           (log/error :msg "{slicename} automation failed for item"
                      :item item
                      :exception e)))))))
```

- **Triggers are the to-do read model's INBOUND event types** — the events that put items on the list.
- Use `(vals ...)` for a map-shaped view, and the view itself for a set-shaped one.
- **Periodic trigger**: only when slice.json describes retries, scheduling, or a time-based rule. Then the
  component passes `:interval-ms`, taken from the description or comments; if no interval is given,
  use 5 minutes and record the assumption.
- Anything non-deterministic the change needs (random ids, nonces, the current time) is passed as an
  option with a default, e.g. `:nonce-fn`, so tests can make it deterministic.

## Step 4.4 — the component

```clojure
(def component-map
  #::ds{:start (fn [{::ds/keys [config]}]
                 (let [{:keys [event-store {dependency}]} config]
                   ({slicename}-automation event-store {dependency})))

        :stop (fn [{::ds/keys [instance]}]
                (instance))

        :config {:event-store (ds/ref [:shared :event-store])
                 :{dependency} (ds/ref [:shared :{dependency}])}})
```

`instance` is the stop function returned by `l/automation`.

## Step 5 — wire it into the system

In `src/{basepackage}/system.clj`:

1. Require the slice: `[{basepackage}.slices.{context}.{slicename}.slice :as slice.{context}.{slicename}]`
2. Add the component under `:automations` in `base-system`:

```clojure
:automations
{...
 :{slicename} slice.{context}.{slicename}/component-map}
```

## Step 6 — the tests

Ledjer ships with an in-memory event-store to be used for testing. Write one `deftest` per part of the slice,
with the Given-When-Then scenarios as plain clojure.test `testing` s-expressions:

```clojure
(ns {basepackage}.slices.{context}.{slicename}.slice-test
  (:require
   [clojure.test :refer [deftest testing]]
   [{basepackage}.slices.events :as ev]
   [{basepackage}.slices.{context}.{slicename}.slice :as slice]
   [dk.planm.ledjer :as l]))

;; 1. The to-do view: an item appears, and every outcome event that ends processing removes it
(deftest {todo-view}
  (testing "item added to the to-do list"
    (l/gwt
     :given [(ev/{todo-inbound-event} {...})]
     :when [#'slice/{todo-view} {}]
     :then {...}))
  (testing "item removed once processed"
    (l/gwt
     :given [(ev/{todo-inbound-event} {...})
             (ev/{success-event} {...})]
     :when [#'slice/{todo-view} {}]
     :then {})))

;; 2. The change function: one scenario per outcome, with stub dependencies
(deftest {commandname}-change
  (let [succeeding-dependency (fn [& _] nil)
        failing-dependency (fn [& _] (throw (ex-info "failure reason" {})))]
    (testing "side effect succeeds"
      (l/gwt
       :given [...]
       :when [#'slice/{commandname}-change succeeding-dependency {...}]
       :then [(ev/{success-event} {...})]))
    (testing "side effect fails"
      (l/gwt
       :given [...]
       :when [#'slice/{commandname}-change failing-dependency {...}]
       :then [(ev/{failure-event} {...})]))))

;; 3. The automation end to end: given the to-do inbound event, then the outcome events
(deftest {slicename}-automation
  (l/gt
   :automation (fn [event-store]
                 (slice/{slicename}-automation event-store (fn [& _] nil)))
   :timeout-ms 1000
   :given [(ev/{todo-inbound-event} {...})]
   :then [(ev/{success-event} {...})]))
```

Automation scenarios in `specifications[]` read "given events, then command (or nothing)". Test each one on
the change function (part 2), with `:then` being the events the command results in — or on the view
(part 1) when the scenario is about whether an item is on the to-do list at all.

Create events using the event constructor functions in `src/{basepackage}/slices/events.clj`, avoid commands
from other slices.

## Quality gate

Before considering the slice done:

- [ ] The to-do view's items have exactly the read model's fields — no invented fields, none missing
- [ ] Every outcome event that ends processing removes the item from the to-do view
- [ ] Every field in slice.json's `commands[]` is in the change function's schema — no invented fields, none missing
- [ ] Every field in slice.json's `events[]` is in the event's schema — no invented fields, none missing
- [ ] Every external dependency exists under `:shared` in `system.clj` — otherwise the slice is `Blocked`
- [ ] No exception can escape the automation's body
- [ ] The component is registered under `:automations` in `system.clj`
- [ ] Every `specifications[]` scenario has a corresponding test
- [ ] No business rule (retry limits, intervals, ...) exists that isn't traceable to slice.json's `description`/`comments`
- [ ] run the slice's own tests only (`clojure -X:test :nses '[{basepackage}.slices.{context}.{slicename}.slice-test]'` — namespace symbols, not strings; strings match nothing and run zero tests)
- [ ] If checks pass, commit with `feat: {slicename}` and set slice status to `Done`
