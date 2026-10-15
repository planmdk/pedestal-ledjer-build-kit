---
name: build-state-change
authors:
  - Matthias Varberg Ingesman
description: >
  Implement DCB-style write slices using the ledjer Clojure library in this project's one
  established pattern: A function of `event-store` and command `payload` either throwing
  or appending events to `event-store`. Use when implementing a new write slice / command
  handler from a slice.json event model in this project. There is exactly one supported
  style - do not offer alternatives.
---

# Build State Change Slice

> Before doing anything else, read the slice definition from `.slices/{Context}/{slicename}/slice.json`.
> This file is the **source of truth** for all fields, events, and metadata — never invent
> fields not defined there. Keep this line verbatim; it applies to every stack.

---

## What a State Change Slice is

One pattern only. Directory layout is flat - `src/{basepackage-path}/slices/{context}/{slicename}/slice.clj`, shared
`src/{basepackage-path}/slices/events.clj` for the events.

A state change slice consists of:

- A command function of `event-store` and `payload`
- A projection of existing events for consistency checks
- A change handler Pedestal interceptor
- A set of routes in Pedestal route syntax in `routes` containing one entry for the change handler interceptor

All this goes into the `slice.clj` file.

## Step 1 — Read the slice.json

From the slice definition, extract:
- **sliceName** — the slice title
- **context** — the bounded context
- **commands[]** — list of commands with their data fields
- **events[]** — list of events emitted by each command
- **specifications[]** — test scenarios (given/when/then)

> **Comments & description**: each element carries a `comments: string[]` array (board comments) and a `description` field — use them as implementation hints, and resolve consumed comments via `POST <BASE_URL>/api/org/<ORG_ID>/boards/<BOARD_ID>/nodes/<nodeId>/comments/<commentId>/resolve`.

---

## Step 2 - the events - only if it doesn't alread exist

**Check `src/{basepackage-path}/slices/events.clj` first;** add event only if it does not exist there.

```clojure
(defevent {eventname}
  :type :{context}/{eventname}
  :schema [:map
           ...]
  :tags [...])
```

**`:tags` is created from the event's fields with `idAttribute: true`**: `[(payload-path <idField1>) ... (payload-path <idFieldN>)]`

**`:schema` is a Malli schema created from the event's fields**: `[:map [:<field1.name> :<field1.type>] ... [:<fieldN.name> :<fieldN.type>]]`

### Fields to Malli schema entries

The same rules apply to every schema built from slice.json fields — events here, commands in step 3.1.

- **Name**: kebab-cased keyword — `cartId` → `:cart-id`.
- **Type** (case-insensitive):

| slice.json `type` | Malli |
|---|---|
| `String` | `:string` |
| `UUID` | `:uuid` |
| `Boolean` | `:boolean` |
| `Int`, `Integer`, `Long` | `:int` |
| `Double`, `Decimal` | `:double` |
| `Date` | `:time/local-date` |
| `DateTime` | `:time/instant` — always, read and shown in the project zone (see "The project's coercion namespace" in step 3.1) |
| `Custom` | `[:map ...]` built from `subfields` with these same rules; `:map` if it has no subfields |

  A type not in the table gets the closest match, recorded as an assumption.
- **`cardinality: "List"`** wraps the type in a vector: `[:vector :uuid]`.
- **`optional: true`** marks the entry optional: `[:image {:optional true} :string]`.

**Command schemas only** — because forms send nothing for an unticked checkbox or an empty list, these two get
a default (`coercion/transformer` fills it in, see step 3.1):

- `Boolean` → `[:done {:default false} :boolean]`
- `cardinality: "List"` → `[:item-ids {:default []} [:vector :uuid]]`

Event schemas never get defaults: events are checked, never decoded, so a default there does nothing.

**Example slice.json event (abbreviated):**

```json
{
      ...,
      "modelContext": "Understanding Eventsourcing",
      "title": "Cart Created",
      "fields": [
        {
          "name": "cartId",
          "type": "UUID",
          "example": "",
          "mapping": "",
          "optional": false,
          "subfields": [],
          "cardinality": "Single",
          "idAttribute": true
        },
        {
          "name": "userId",
          "type": "UUID",
          "example": "",
          "mapping": "",
          "optional": false,
          "subfields": [],
          "cardinality": "Single",
          "idAttribute": true
        },
        {
          "name": "description",
          "type": "string",
          "example": "",
          "mapping": "",
          "optional": false,
          "subfields": [],
          "cardinality": "Single",
          "idAttribute": false
        }
      ],
    ...
    }
```

**Becomes this event in `events.clj`**:

```clojure
(defevent cart-created
  :type :understanding-eventsourcing/cart-created
  :schema [:map
           [:cart-id :uuid]
           [:user-id :uuid]
           [:description :string]]
  :tags [(payload-path :cart-id) (payload-path :user-id)])
```

## Step 3 - the change slice

```clojure
(ns {basepackage}.slices.{context}.{slicename}.slice
  (:require
   [{basepackage}.common.interceptors :as interceptors]
   [{basepackage}.slices.events :as ev]
   [{basepackage}.common.utils :as utils]
   [dk.planm.ledjer :as l]
   [dk.planm.ledjer.event :as le]
   [io.pedestal.http.response :refer [respond-with]]
   [malli.core :as m]
   [malli.error :as merror]
   [{basepackage}.common.coercion :as coercion]
   [tick.alpha.interval :as t.i]
   [tick.core :as t]))

;; Command function (step 3.1)

;; Change handler Pedestal interceptor (step 3.2)

;; Routes (step 3.3)
```

## Step 3.1 — the command function

**`{schema}` is a Malli schema derived from the command's fields**: `[:map [:<field1.name> :<field1.type>] ... [:<fieldN.name> :<fieldN.type>]]`, following "Fields to Malli schema entries" in step 2.

```clojure
(defn {commandname}-change
  [event-store payload]
  (let [coerced-payload (m/coerce
                         {schema}
                         payload
                         coercion/transformer)]
    ...))
```

**Always coerce with `coercion/transformer`** (see "The project's coercion namespace" below). Form and path
values arrive as strings, and it turns them into the schema's types. Never parse values in the handler before
calling the change function (e.g. with `t/date`): a value that doesn't parse would then throw outside coercion,
as a server error instead of a field error.

### The project's coercion namespace

Coercion of request values is project code, not slice code. The project provides it in
`src/{basepackage-path}/common/coercion.clj`, required as `[{basepackage}.common.coercion :as coercion]`:

| Var | What it is |
|---|---|
| `coercion/zone` | The project's one fixed time zone, a `java.time.ZoneId` |
| `coercion/transformer` | The malli transformer for every `m/coerce` of request values |
| `coercion/instant->local-date-time` | An instant as a local date-time in `zone`, to the minute — for pages and `datetime-local` inputs |

What `coercion/transformer` does with form values, which the schema rules in step 2 rely on:

- **Strings** become the schema's type — numbers, UUIDs, booleans, `:time/local-date`, and `:time/instant`.
- **`:time/instant`** also accepts a local date-time without a zone (what `<input type="datetime-local">`
  sends) and reads it in `zone`.
- **Empty values (`""`) are dropped**, as if the input wasn't sent: an empty optional field is left out, and an
  empty required field fails as a missing key.
- **A checkbox's `"on"`** becomes `true`.
- **A single value for a `[:vector ...]`** becomes a one-element vector.
- **Missing keys with a `:default`** get the default.

**If the namespace doesn't exist, or lacks one of these vars, do not create or change it.** Invoke
`/request-feedback` naming what is missing; this marks the slice `Blocked`. The same applies to the zone:
never guess or change it while building a slice.

**Example slice.json command (abbreviated)**:

```json
{
      ...,
      "modelContext": "Understanding Eventsourcing",
      "title": "Add Item",
      "fields": [
        {
          "name": "cartId",
          "type": "UUID",
          "example": "",
          "mapping": "",
          "optional": false,
          "subfields": [],
          "cardinality": "Single",
          "idAttribute": false
        },
        {
          "name": "description",
          "type": "String",
          "example": "",
          "mapping": "",
          "optional": false,
          "subfields": [],
          "cardinality": "Single",
          "idAttribute": false
        },
        {
          "name": "image",
          "type": "String",
          "example": "",
          "mapping": "",
          "optional": false,
          "subfields": [],
          "cardinality": "Single",
          "idAttribute": false
        },
        {
          "name": "price",
          "type": "Double",
          "example": "",
          "mapping": "",
          "optional": false,
          "subfields": [],
          "cardinality": "Single",
          "idAttribute": false
        },
        {
          "name": "itemId",
          "type": "UUID",
          "example": "",
          "mapping": "",
          "optional": false,
          "subfields": [],
          "cardinality": "Single",
          "idAttribute": false
        },
        {
          "name": "productId",
          "type": "UUID",
          "example": "",
          "mapping": "",
          "optional": false,
          "subfields": [],
          "cardinality": "Single",
          "idAttribute": false
        }
      ],
      "type": "COMMAND",
      ...
    }
```

**Becomes this change function in the slice**:

```clojure
(defn add-item-change
  [event-store payload]
  (let [coerced-payload (m/coerce
                         [:map
                          [:cart-id :uuid]
                          [:description :string]
                          [:image :string]
                          [:price :double]
                          [:item-id :uuid]
                          [:product-id :uuid]]
                         payload
                         coercion/transformer)]
    ... ;; Validation logic
    (if valid?
      (l/append event-store [(ev/event-1 ...) (ev/event-2 ...)])
      (throw (ex-info "... why it failed, for the user" {:type :command/rejected})))))
```

Validation in the command can depend on projections of existing events.

**Business-rule violations throw `(ex-info message {:type :command/rejected})`.** The `:type` is required:
it is the only thing that tells the change handler (step 3.2) the error is meant for the user, and it shows
the message as is — so write the message for the end user, in the UI's language. An `ex-info` without it is
treated as a bug and becomes a server error. Invalid input needs no extra code — `m/coerce` already throws
an `ex-info` with `:type :malli.core/coercion` for it.

**Use a `(l/defview ...)` from Ledjer to project events**: see step 3.1 from the build-state-view skill for guidance.

## Step 3.2 - the change handler interceptor

A Pedestal interceptor map with `:name` and `:enter` keys.

**Base pattern**:

```clojure
(defn- {commandname}-errors
  [e]
  (case (:type (ex-data e))
    :malli.core/coercion (merror/humanize (-> e ex-data :data :explain) {:locale :da})
    :command/rejected {:command/rejected [(ex-message e)]}
    (throw e)))

(def {commandname}-change-handler
  {:name ::{commandname}-change-handler
   :enter (fn [ctx]
            (let [event-store (:event-store ctx)]
              (try
                ({commandname}-change event-store input)
                (respond-with ctx status headers body)
                (catch clojure.lang.ExceptionInfo e
                  (assoc-in (respond-with ctx status headers body)
                            [:response :flash :errors/{commandname}]
                            ({commandname}-errors e))))))})
```

where `status`, `headers` and `body` must be derived from the slice comments in slice.json to know whether it is a redirect (303 with `location` header), ok (200 with body), created (201 without body), or something else.

- **Only two kinds of error become form errors**: invalid input (`:malli.core/coercion`) and business-rule
  violations (`:command/rejected`). Everything else — other exceptions, and `ex-info`s from ledjer itself
  such as query validation, event schema or append-condition failures — is rethrown and surfaces as a
  server error, so internal messages never reach the user.
- **The errors are a map from field name to messages**, the shape `merror/humanize` returns for invalid
  input. A business-rule violation isn't tied to a field, so its message goes under `:command/rejected`.
  Field keys are never namespaced, so it can't collide with a field.
- **The flash key is `:errors/{commandname}`** — the page showing the form reads its errors from there.
- **`:locale`**: use the locale the project's other change handlers pass to `merror/humanize`; leave the
  option out if there are none.
- **Input** comes from `:form-params`, and from `:path-params` for the ids in the route's path (see
  "Routes and paths"). Ids of the session user come from `[:request :session :user-id]`, never from the form.

**Following the example above**:

```clojure
(defn- add-item-errors
  [e]
  (case (:type (ex-data e))
    :malli.core/coercion (merror/humanize (-> e ex-data :data :explain) {:locale :da})
    :command/rejected {:command/rejected [(ex-message e)]}
    (throw e)))

(def add-item-change-handler
  {:name ::add-item-change-handler
   :enter (fn [ctx]
            (let [event-store (:event-store ctx)
                  cart-id (get-in ctx [:request :path-params :cart-id])
                  cart-page-url (utils/url-for-safe :understanding-eventsourcing/cart-items-page
                                                    :path-params {:cart-id cart-id})]
              (try
                (add-item-change event-store {:cart-id cart-id
                                              :description (get-in ctx [:request :form-params :description])
                                              :image (get-in ctx [:request :form-params :image])
                                              :price (get-in ctx [:request :form-params :price])
                                              :item-id (get-in ctx [:request :form-params :item-id])
                                              :product-id (get-in ctx [:request :form-params :product-id])})
                (respond-with ctx 303 {"location" cart-page-url} {})
                (catch clojure.lang.ExceptionInfo e
                  (assoc-in (respond-with ctx 303 {"location" cart-page-url} {})
                            [:response :flash :errors/add-item]
                            (add-item-errors e))))))})
```

## Step 3.3 - the routes definition

A set of routes with one entry, in the Pedestal route syntax:

```clojure
(def routes
  #{["/carts/:cart-id/add-item" :post
     [#'interceptors/authenticated-interceptor
      ;; authorized-interceptor, see below
      #'add-item-change-handler]
     :route-name :understanding-eventsourcing/add-item]})
```

- `#'interceptors/authenticated-interceptor` is **always included**, first in the chain. A change route without
  it lets anyone write events.
- Add `interceptors/authorized-interceptor` when the command changes one entity owned by a user — i.e. the
  entity's creation event has both the entity's id and `user-id` as tags. It responds 404 when the session
  user doesn't own the entity. It takes the same arguments as in step 3.4 of `build-state-view`:

```clojure
(interceptors/authorized-interceptor
 :event-type (ev/{creation-event})
 :entity-id-key :{id-field}
 :entity-id-path [:request :path-params :{id-field}])
```

  A command that creates the entity has nothing to be authorized against yet: authenticated only.

Look up interceptors in `src/{basepackage-path}/common/interceptors.clj` when necessary.

**Wire the routes into the system**: in `src/{basepackage-path}/system.clj`, require the slice namespace as
`slice.{context}.{slicename}` and add `slice.{context}.{slicename}/routes` to the `set/union` of routes.

### Routes and paths

The **route name is the contract between slices**: pages and redirects always refer to routes by name via
`utils/url-for-safe`, never by path. Route names are mechanical:

- change (state change): `:{context}/{slicename}`
- page (state view): `:{context}/{slicename}-page`

The **path** is resource-style. Decide it in this order:

1. **Override** — if the command has a comment `path: /...`, use exactly that path. Never resolve a `path:`
   comment: it stays on the board as the route's specification, so a later rebuild of the slice produces
   the same path.
2. **Resource** — one `/{entities}/:{id-field}` pair per id attribute of the entity the command acts on,
   leaving out `user-id` (it comes from the session):
   - `{entities}` is the plural of the id field's name without `-id`: `cart-id` → `/carts/:cart-id`.
     For a generic id field (`id`, `aggregate-id`), name the entity from the command title instead
     (`Publish Cart` → `carts`).
   - With several ids, the parent comes first: the entity that exists before the other, i.e. whose id the
     other entity's events carry. `cart-id` + `item-id` → `/carts/:cart-id/items/:item-id`. If that can't
     be decided from the model, keep the order of the fields in slice.json.
3. **Suffix** — the command name, kebab-cased: `/carts/:cart-id/add-item`, `/recipes/:recipe-id/archive-recipe`.
   A command that creates the entity posts to `/{entities}` without a suffix.
4. **Collision check** — search the existing `routes` in `src/{basepackage-path}/slices/**/slice.clj` for the same
   path and verb. Depending on the router, a duplicate either stops Pedestal from starting or silently
   shadows one of the routes — and slice tests don't load `system.clj`, so they won't catch it. On a
   collision, add a suffix from the slice name.

Unless the path came from a `path:` comment, record it as an assumption in `progress.txt`.

The same rules are in the `build-state-view` skill; keep the two in sync.

## Step 4 - the tests

Ledjer ships with an in-memory event-store to be used for testing.

Write Given-When-Then scenarios as plain clojure.test `testing` s-expressions under the same `deftest` expression.

```
(ns {basepackage}.slices.{context}.{slicename}.slice-test
  (:require
   [clojure.test :refer [deftest testing]]
   [{basepackage}.slices.events :as ev]
   [{basepackage}.slices.{context}.{slicename}.slice :as slice]
   [dk.planm.ledjer :as l]))

(deftest {slicename}-change
  (testing "given no prior activity, when {commandname}, then succeeds"
    (l/gwt
     :given []
     :when [#'slice/{commandname}-change input]
     :then [ ;;... events created from constructor functions in `ev/`
     ]))
  (testing "given some events happened, when {commandname} with bad input, then failure"
    (l/gwt
     :given [ ;; ... events created from functions in `ev/`
     ]
     :when [#'slice/{commandname}-change bad-input]
     :then #"error message from the expected thrown error")))
```

Create events using the event constructor functions in `src/{basepackage-path}/slices/events.clj`, avoid commands from other slices.

## Quality gate

Before considering the slice done:

- [ ] Every field in slice.json's `commands[]` is in the change function's schema — no invented fields, none missing
- [ ] Every field in slice.json's `events[]` is in the event's `defevent` schema — no invented fields, none missing
- [ ] Every `specifications[]` scenario has a corresponding test
- [ ] No business rule exists in the handler that isn't traceable to slice.json's `description`/`comments`
- [ ] Every business-rule `ex-info` has `{:type :command/rejected}` and a message written for the end user
- [ ] The command is coerced with `coercion/transformer`, and no value is parsed in the handler first
- [ ] Every `Boolean` and `List` field of the command schema has its `:default` (`false` / `[]`); no event schema has one
- [ ] The path follows "Routes and paths", the route has `authenticated-interceptor`, and the routes are in the `set/union` in `system.clj`
- [ ] run the slice's own tests only (`clojure -X:test :nses '[{basepackage}.slices.{context}.{slicename}.slice-test]'` — namespace symbols, not strings; strings match nothing and run zero tests)
- [ ] If checks pass, commit with `feat: {slicename}` and set slice status to `Done`
