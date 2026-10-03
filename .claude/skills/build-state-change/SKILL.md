---
name: build-state-change
authors:
  - Matthias Varberg Ingesman
description: >
  Implement DCB-style write slices using the ledjer Clojure library in this project's one
  established pattern: A function of `event-store` and command `payload` either throwing
  or appending events to `event-store`. Use when implementing a new write slice / command
  handler from a slice.jsOn event model in this project. There is exactly one supported
  style - do not offer alternatives.
---

# Build State Change Slice

> Before doing anything else, read the slice definition from `.slices/{Context}/{slicename}/slice.json`.
> This file is the **source of truth** for all fields, events, and metadata — never invent
> fields not defined there. Keep this line verbatim; it applies to every stack.

---

## What a State Change Slice is

One pattern only. Directory layout is flat - `src/{basepackage}/slices/{context}/{slicename}/slice.clj`, shared
`src/{basepackage}/slices/events.clj` for the events.

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
- **storylines[]** - (optional, may be absent) -> narrated walkthroughs with ordered `elements[]` "beats"

> **Comments & description**: each element carries a `comments: string[]` array (board comments) and a `description` field — use them as implementation hints, and resolve consumed comments via `POST <BASE_URL>/api/org/<ORG_ID>/boards/<BOARD_ID>/nodes/<nodeId>/comments/<commentId>/resolve`.

---

## Step 2 - the events - only if it doesn't alread exist

**Check `src/{basepackage}/slices/events.clj` first;** add event only if it does not exist there.

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
   [malli.transform :as mt]
   [tick.alpha.interval :as t.i]
   [tick.core :as t]))

;; Command function (step 3.1)

;; Change handler Pedestal interceptor (step 3.2)

;; Routes (step 3.3)
```

## Step 3.1 — the command function

**`{schema}` is a Malli schema derived from the command's fields**: `[:map [:<field1.name> :<field1.type>] ... [:<fieldN.name> :<fieldN.type>]]`

```clojure
(defn {commandname}-change
  [event-store payload]
  (let [coerced-payload (m/coerce
                         {schema}
                         payload
                         mt/string-transformer)]
    ...))
```

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
  (let [schema
        coerced-payload (m/coerce
                         [:map
                          [:cart-id :uuid]
                          [:item-id :uuid]
                          [:product-id :uuid]
                          [:description :string]
                          [:image :string]
                          [:price :number]]
                         payload
                         mt/string-transformer)]
    ... ;; Validation logic
    (if valid?
      (l/append event-store [(ev/event-1 ...) (ev/event-2 ...)])
      (throw (ex-info "... why it failed" {...})))))
```

Validation in the command can depend on projections of existing events.

**Use a `(l/defview ...)` from Ledjer to project events**

## Step 3.2 - the change handler interceptor

A Pedestal interceptor map with `:name` and `:enter` keys.

**Base pattern**:

```clojure
(def {commandname}-change-handler
  {:name ::{commandname}-change-handler
   :enter (fn [ctx]
            (let [event-store (:event-store ctx)]
              (try
                ({commandname}-change event-store input)
                (respond-with ctx status headers body)
                (catch Exception e
                  (let [{:keys [schema value]} (:data (ex-data e))
                        errors (merror/humanize
                                (m/explain schema value)
                                {:locale :da})]
                    (assoc-in (respond-with ctx status headers body)
                              [:response :flash :errors/add-item]
                              errors))))))})
```

where `status`, `headers` and `body` must be derived from the slice comments in slice.json to know whether it is a redirect (303 with `location` header), ok (200 with body), created (201 without body), or something else.

**Following the example above**:

```clojure
(def add-item-change-handler
  {:name ::add-item-change-handler
   :enter (fn [ctx]
            (let [event-store (:event-store ctx)]
              (try
                (add-item-change event-store {:cart-id (get-in ctx [:request :form-params :cart-id])
                                              :item-id (get-in ctx [:request :form-params :item-id])
                                              :product-id (get-in ctx [:request :form-params :product-id])
                                              :description (get-in ctx [:request :form-params :description])
                                              :image (get-in ctx [:request :form-params :image])
                                              :price (get-in ctx [:request :form-params :price])})
                (respond-with ctx 303 {"location" (utils/url-for-safe :understanding-eventsourcing/cart-page :path-params {:cart-id (get-in ctx [:request :form-params :cart-id])})} {})
                (catch Exception e
                  (let [{:keys [schema value]} (:data (ex-data e))
                        errors (merror/humanize
                                (m/explain schema value)
                                {:locale :da})]
                    (assoc-in (respond-with ctx 303
                                            {"location" (utils/url-for-safe :understanding-eventsourcing/cart-page :path-params {:cart-id (get-in ctx [:request :form-params :cart-ids])})}
                                            {})
                              [:response :flash :errors/add-item]
                              errors))))))})
```

## Step 3.3 - the routes definition

A set of routes with one entry, in the Pedestal route syntax:

```clojure
(def routes
  #{["/understanding-eventsourcing/add-item" :post
     [;; ... other interceptors (see below)
      #'add-itemchange-handler]
     :route-name :understanding-eventsourcing/add-item]})
```

Relevant interceptors may include:

- `#'interceptors/authenticated-interceptor`
- `#'interceptors/authorized-interceptor`

Look up interceptors in `src/{basepackage}/common/interceptors.clj` when necessary.

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
  (testing "given some events happended, when {commandname} with bad input, then faliure"
    (l/gwt
     :given [ ;; ... events created from functions in `ev/`
     ]
     :when [#'slice/{commandname}-change bad-input]
     :then #"error message from the expected thrown error")))
```

Create events using the event constructor functions in `src/{basepackage}/slices/events.clj`, avoid commands from other slices.

## Quality gate

Before considering the slice done:

- [ ] Every field in slice.json's `commands[]` is in the Command record — no invented fields, none missing
- [ ] Every field in slice.json's `events[]` is in the Event record — no invented fields, none missing
- [ ] Every `specifications[]` scenario has a corresponding test method
- [ ] No business rule exists in the handler that isn't traceable to slice.json's `description`/`comments`
- [ ] run the slice's own tests only (`clojure -X:test :nses '["{basepackage}.slices.{context}.{slicename}.slice-test"]'`)
- [ ] If checks pass, commit with `feat: {slicename}` and set slice status to `Done`
