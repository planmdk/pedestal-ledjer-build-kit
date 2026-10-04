---
name: build-state-view
authors:
  - Matthias Varberg Ingesman
description: >
  Use when implementing a new read slice / projection from a slice.json event model in this project.
  One established pattern: Always use the Ledjer library's `defview` macro. There is exactly one
  supported style - do not offer alternatives.
---

# Build State View Slice

> **TODO — placeholder installed by `init --build-kit`.** Study an existing stack's
> build-state-view/SKILL.md (stacks/node, stacks/supabase, stacks/axon, or stacks/umadb,
> under `templates/.claude/skills/`) for the level of detail expected, then rewrite
> every section below for your stack's real conventions — file layout, naming, and
> the actual framework/language idioms. Delete this callout once done.

> Before doing anything else, read the slice definition from `.slices/{Context}/{slicename}/slice.json`.
> This file is the **source of truth** for all fields, events, and metadata — never invent
> fields not defined there. Keep this line verbatim; it applies to every stack.

---

## What a State View Slice is

One pattern only. Directory layout is flat:
- `src/{basepackage}/slices/{context}/{slicename}/slice.clj`
- `src/{basepackage}/slices/{context}/{slicename}/scion.cljs` for any client-side dynamic behaviour
- `src/{basepackage}/slices/{context}/{slicename}/shared.cljc` for anything shared between the main slice code and the scion code
- shared `src/{basepackage}/slices/events.clj` for the events

A state change slice consists of:

- A page generation function returning hiccup
- A projection for each `readmodel` in the slice
- A page handler interceptor
- A set of routes in Pedestal route syntax in `routes` containing one entry for the page handler interceptor

All this goes into the `slice.clj` file.

**Optionally** a `scion.cljs` file which contains client-side scripts for dynamic page functionality (think Javascript, but using Clojurescript instead). Code that is usable in both `slice.clj` and `scion.cljs` is put in `shared.cljc`, making it available in both settings.

The page handler interceptor `{slicename}-page-handler` uses the defined `defview`'s to get the data, then pass that data to the `{slicename}-page` function, which returns the hiccup that is put in the response body from the page handler.

## Step 1 — Read the slice.json

From the slice definition, extract:
- **sliceName** — the slice title
- **context** — the bounded context
- **events[]** - the events defined in this slice
- **readmodels[]** — the read model(s) this slice serves
- **specifications[]** — test scenarios (given/when/then)

> **Comments & description**: each element carries a `comments: string[]` array (board comments) and a `description` field — use them as implementation hints, and resolve consumed comments via `POST <BASE_URL>/api/org/<ORG_ID>/boards/<BOARD_ID>/nodes/<nodeId>/comments/<commentId>/resolve`.

---

## Step 2 - the events - only if it doesn't alread exist

**Check `src/{basepackage}/slices/events.clj` first;** add any events which do not exist to this file, taken from:

- all the readmodel's `INBOUND` dependencies
- all the slice's `events`

```clojure
(defevent {eventname}
  :type :{context}/{eventname}
  :schema [:map
           ...]
  :tags [...])
```

**`:tags` is created from the event's fields with `idAttribute: true`**: `[(payload-path <idField1>) ... (payload-path <idFieldN>)]`

**`:schema` is a Malli schema created from the event's fields**: `[:map [:<field1.name> :<field1.type>] ... [:<fieldN.name> :<fieldN.type>]]`

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


## Step 3 — the read slice

```clojure
(ns {basepackage}.slices.{context}.{slicename}.slice
  (:require
   [{basepackage}.common.interceptors :as interceptors]
   [{basepackage}.common.utils :as utils]
   [{basepackage}.slices.events :as ev]
   [dk.planm.ledjer :as l]
   [dk.planm.ledjer.event :as le]
   [io.pedestal.http.response :refer [respond-with]]
   [tick.alpha.interval :as ti]
   [tick.core :as t]))

;; hiccup generation (step 3.1)

;; Projections / views (step 3.2)

;; Page handler Pedestal interceptor (step 3.3)

;; Routes (step 3.4)
```

TODO — file layout, naming conventions, framework idioms, how the handler/projection/reactor is wired up.


## Step 3.1 - projections / views

Exactly one pattern: Each readmodel in slice.json becomes a `defview` from ledjer.

Base pattern is this:

```clojure
(l/defview {readmodel}
  {} ;; <- the initial value of acc, a map of the readmodel's fields with default values
  (ev/{event1})
  (fn {event1}-reducer
    [acc event]
    ;; ...modify acc as necessary
    )

  (ev/{event2})
  (fn {event2}-reducer
    [acc event]
    ;; ... modify acc as necessary
    )

  ... and so on
  )
```

Build up `acc` from the **INBOUND** events of the readmodel. The specifications define any special requirements on how the readmodel's reducers must update `acc`.

**Example (abbreviated)**:

This slice.json readmodel

```json
{
      ...,
      "title": "cart items",
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
          "name": "totalPrice",
          "type": "Double",
          "example": "",
          "mapping": "price",
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
        },
        {
          "name": "itemId",
          "type": "UUID",
          "example": "",
          "mapping": "",
          "optional": false,
          "subfields": [],
          "cardinality": "Single",
          "idAttribute": true
        }
      ],
      "type": "READMODEL",
      "dependencies": [
        ...,
        {
          "id": "a39b1e41-7b38-42b1-8ad3-c9cc18ca2f7c",
          "type": "INBOUND",
          "title": "Cart Created",
          "elementType": "EVENT"
        },
        {
          "id": "544d0a8c-809c-4670-a9d9-aa8259f43c46",
          "type": "INBOUND",
          "title": "Item Added",
          "elementType": "EVENT"
        },
        {
          "id": "b5b7ae0a-d0a7-4b2b-bc14-2086993eface",
          "type": "INBOUND",
          "title": "Item Removed",
          "elementType": "EVENT"
        },
        {
          "id": "d7cb2a4a-6d1a-4abf-9a37-0bdd9b84eb32",
          "type": "INBOUND",
          "title": "Cart Cleared",
          "elementType": "EVENT"
        },
        {
          "id": "3d3f9528-08f8-473b-8456-7b03f6d368ca",
          "type": "INBOUND",
          "title": "Item Archived",
          "elementType": "EVENT"
        }
      ],
      ...
}
```

becomes this ledjer `defview` (abbreviated, showing first event only)

```clojure
(l/defview cart-items
  {:cart-id nil
   :description nil
   :image nil
   :price nil
   :total-price nil
   :product-id nil
   :item-id nil}
  (ev/cart-created)
  (fn cart-created-reducer
    [acc event]
    (-> acc
        (assoc :cart-id (get event :cart-id))
        (assoc :user-id (get event :user-id))
        (assoc :description (get event :description))))

  ;; ... add the rest of the event reducers
  )
```

## Step 3.2 - hiccup generation

Only if the readmodel is attached to a screen element, i.e. it has an **OUTBOUND** dependency of elementType **SCREEN** in slice.json.

## Step 3.3 - Page handler Pedestal interceptor

Only if the readmodel is attached to a screen element, i.e. it has an **OUTBOUND** dependency of elementType **SCREEN** in slice.json.

## Step 3.4 - Routes

Only if the readmodel is attached to a screen element, i.e. it has an **OUTBOUND** dependency of elementType **SCREEN** in slice.json.

## Step 3.5 - Tests

Ledjer ships with an in-memory event-store to be used for testing.

Write Given-When-Then scenarios as plain clojure.test `testing` s-expressions under the same `deftest` expression.

```
(ns {basepackage}.slices.{context}.{slicename}.slice-test
  (:require
   [clojure.test :refer [deftest testing]]
   [{basepackage}.slices.events :as ev]
   [{basepackage}.slices.{context}.{slicename}.slice :as slice]
   [dk.planm.ledjer :as l]
   [dk.planm.ledjer.event :as le]))

(deftest {slicename}-change
  (testing "test 1"
    (l/gwt
     :given [(ev/event-1 {...})]
     :when [#'slice/{readmodel} {(le/payload-path :id-attribute-1) "value-1"
                                 ;; ... other id attributes
                                 }]
     :then {;; ... the readmodel's expected shape
     })))
```

Create events using the event constructor functions in `src/{basepackage}/slices/events.clj`, avoid commands from other slices.

## Quality gate

Before marking this slice as `Done`, verify the implementation against slice.json:

- [ ] Every field in the read model / query result definition in slice.json has a field in the output of the `defview` — no invented fields
- [ ] Every event type in `events[]` has a reducer in the readmodel — no events missed or assumed
- [ ] Every GWT scenario in `specifications[]` has a corresponding test method
- [ ] No extra query parameters or filter logic were added beyond what slice.json defines
- [ ] No field names were assumed or guessed — if a field is not in slice.json, it is not in the code
