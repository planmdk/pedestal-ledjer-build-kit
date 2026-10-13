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

Directory names are the namespace segments with `-` replaced by `_` (namespace
`...slices.understanding-eventsourcing.cart-items.slice` lives in
`.../slices/understanding_eventsourcing/cart_items/slice.clj`).

A state view slice consists of:

- A projection for each `readmodel` in the slice
- Only if a read model has an OUTBOUND `SCREEN` dependency:
  - A page generation function returning hiccup
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
   [huff2.core :as h]
   [io.pedestal.http.csrf :as csrf]
   [io.pedestal.http.response :refer [respond-with]]
   [tick.alpha.interval :as ti]
   [tick.core :as t]))

;; Projections / views (step 3.1)

;; Hiccup generation (step 3.2)

;; Page handler Pedestal interceptor (step 3.3)

;; Routes (step 3.4)
```

Remove requires the slice ends up not using.


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

Build up `acc` from the **INBOUND** events of the readmodel. Every INBOUND event type gets exactly one reducer — ledjer throws if a sourced event has none. Read an event's fields with `(le/payload event)`. The specifications define any special requirements on how the readmodel's reducers must update `acc`.

**The shape of `acc`** follows from how the read model is queried (step 3.3):

- **One entry** — every id attribute of the read model is known when it is queried (from the path or the session): `acc` is a single map of the read model's fields, starting as `{}`.
- **Many entries** — some id attribute is *not* known when it is queried (e.g. `cartId` is in the path but `itemId` isn't, so the read model lists the items in a cart), or the screen shows a list: `acc` is a map from that id to a map of the read model's fields, starting as `{}`. Use a set, `#{}`, only when entries have no id attribute at all.

A field with a non-empty `mapping` takes its value from the event field named by `mapping` (e.g. `totalPrice` with `"mapping": "price"` is set from the event's `price`).

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

is queried by `cartId` (the cart page's path) but not by `itemId`, so it has many entries keyed by `item-id`. It becomes this ledjer `defview` (abbreviated, showing two of the five events):

```clojure
(l/defview cart-items
  {}
  (ev/item-added)
  (fn item-added-reducer
    [acc event]
    (let [{:keys [cart-id item-id product-id description image price]} (le/payload event)]
      (assoc acc item-id {:cart-id cart-id
                          :item-id item-id
                          :product-id product-id
                          :description description
                          :image image
                          :price price
                          :total-price price})))

  (ev/item-removed)
  (fn item-removed-reducer
    [acc event]
    (dissoc acc (:item-id (le/payload event))))

  ;; ... add the reducers for cart-created, cart-cleared and item-archived
  )
```

## Steps 3.2–3.4 — only for read models shown on a screen

Steps 3.2 to 3.4 apply only if the readmodel is attached to a screen element, i.e. it has an **OUTBOUND**
dependency of elementType **SCREEN** in slice.json. Otherwise the slice is just the `defview` and its tests.

**Find the screen's commands first.** The read model's OUTBOUND SCREEN dependencies point at screens, most
of which live in *other* slices. Look each one up by `id` in `.build-kit/.slices/{context}/*/slice.json`
(`screens[]`). Every OUTBOUND `COMMAND` dependency on those screens is a form or button on this page,
posting to the state-change slice that owns the command.

Example: the `cart items` read model has three OUTBOUND SCREEN dependencies. One is the screen in this slice;
the others are in `removeitem` and `clearcart`, with OUTBOUND commands `Remove Item` and `Clear Cart`. The
cart items page therefore has a form per item posting to Remove Item, and one form posting to Clear Cart.

## Step 3.2 - hiccup generation

A function `{slicename}-page` taking keyword arguments and returning a hiccup vector:

```clojure
(defn {slicename}-page
  [& attrs]
  (let [{:keys [csrf-token
                graft
                {readmodel-data}
                {command}-url
                {command}-errors]} attrs]
    [:main ...]))
```

- **Return only the page content.** Never emit `[:html ...]`, `[:head ...]` or `[:body ...]` — the
  `hiccup-interceptor` wraps every hiccup response body in the site layout.
- **Show the screen's `fields`** — they are what the page displays. Take their values from the read model.
- **One form per command found above**, posting to that command's change slice:

```clojure
[:form {:action {command}-url
        :method :post}
 ;; one input per command field the user fills in; hidden inputs for the ids
 [:input {:type "hidden"
          :name "{id-field}"
          :value {id-value}}]
 [:input {:type "hidden"
          :name "__anti-forgery-token"
          :value csrf-token}]
 (when (seq {command}-errors)
   [:span ... {command}-errors])
 [:button {:type "submit"} ...]]
```

  - Input `name`s are the command's field names, kebab-cased — the change handler reads them from
    `:form-params`.
  - `__anti-forgery-token` is required on every form; without it Pedestal's CSRF protection rejects the post.
  - `{command}-errors` are the errors the change handler put in the flash (step 3.3): a map from field
    name to messages, with `:command` holding errors not tied to a field. Show field errors next to their
    input and `:command` errors with the submit button.
- **Styling and layout follow the project's own `CLAUDE.md`** (design system, CSS classes and tokens).
  Never write raw design values the project's rules forbid.
- **Client-side behaviour** (only when the screen's description or comments call for it — show/hide,
  drag and drop, ...) is a scion:
  - in the page: `(h/raw-string (graft "{scion-name}" :prev-sibling {...opts}))`, placed directly after the element it acts on;
  - in `src/{basepackage}/slices/{context}/{slicename}/scion.cljs`: a `(defmethod graft/scion "{scion-name}" [opts container] ...)`;
  - in `src/{basepackage}/scions.cljs`: require the scion namespace, or it is never compiled to JavaScript.

## Step 3.3 - Page handler Pedestal interceptor

A Pedestal interceptor map named `{slicename}-page-handler` that queries the views and responds with the page.

```clojure
(def {slicename}-page-handler
  {:name ::{slicename}-page-handler
   :enter (fn [ctx]
            (let [event-store (:event-store ctx)
                  {id-field} (utils/->uuid (get-in ctx [:request :path-params :{id-field}]))
                  user-id (get-in ctx [:request :session :user-id])]
              (if-not {id-field}
                (respond-with ctx 404 "Not found")
                (let [{readmodel-data} ({readmodel} event-store {(le/payload-path :{id-field}) {id-field}
                                                                 (le/payload-path :user-id) user-id})]
                  (respond-with ctx 200 ({slicename}-page
                                         :csrf-token (get-in ctx [:request ::csrf/anti-forgery-token])
                                         :graft (get-in ctx [:request :grafter])
                                         :{readmodel-data} {readmodel-data}
                                         :{command}-url (utils/url-for-safe :{context}/{command-slicename}
                                                                            :path-params {:{id-field} {id-field}})
                                         :{command}-errors (get-in ctx [:request :flash :errors/{commandname}])))))))})
```

- **Tag bindings** are the read model's id attributes known from the request — path parameters
  (parsed with `utils/->uuid` for UUIDs) and `:user-id` from the session.
  - Bind only tags that the view's events have. Ledjer validates every query: a binding for a tag none of
    the view's event types have throws, and so does a `nil` value.
  - An event type without the bound tag is sourced **unfiltered** — every event of that type is reduced.
    If that is not what the read model means, the event's `:tags` are missing an id attribute.
  - Respond 404 when a path id doesn't parse, rather than letting the query throw.
- **Command URLs** use `utils/url-for-safe` with the command slice's route name (see "Routes and paths"
  below). It returns `nil` while that slice isn't built yet, so the page can be built first.
- **Flash errors** are read from `[:request :flash :errors/{commandname}]` — the key the change handler of
  the command's slice writes them under.

## Step 3.4 - Routes

```clojure
(def routes
  #{["{path}" :get
     [#'interceptors/authenticated-interceptor
      ;; authorized-interceptor, see below
      #'{slicename}-page-handler]
     :route-name :{context}/{slicename}-page]})
```

- `authenticated-interceptor` is always included.
- Add `authorized-interceptor` when the page shows one entity owned by a user — i.e. the entity's creation
  event has both the entity's id and `user-id` as tags:

```clojure
(interceptors/authorized-interceptor
 :event-type (ev/{creation-event})
 :entity-id-key :{id-field}
 :entity-id-path [:request :path-params :{id-field}])
```

  It responds 404 when the session user doesn't own the entity.
- `{path}` is described in "Routes and paths" below.

**Wire the routes into the system**: in `src/{basepackage}/system.clj`, require the slice namespace as
`slice.{context}.{slicename}` and add `slice.{context}.{slicename}/routes` to the `set/union` of routes.

## Routes and paths

The **route name is the contract between slices**: pages and redirects always refer to routes by name via
`utils/url-for-safe`, never by path. Route names are mechanical:

- page (state view): `:{context}/{slicename}-page`
- change (state change): `:{context}/{slicename}`

The **path** is resource-style. Decide it in this order:

1. **Override** — if the screen (for a page) or the command (for a change) has a comment `path: /...`, use
   exactly that path. Never resolve a `path:` comment: it stays on the board as the route's specification,
   so a later rebuild of the slice produces the same path.
2. **Resource** — one `/{entities}/:{id-field}` pair per id attribute, leaving out `user-id` (it comes from
   the session):
   - `{entities}` is the plural of the id field's name without `-id`: `cart-id` → `/carts/:cart-id`.
     For a generic id field (`id`, `aggregate-id`), name the entity from the read model, screen or command
     title instead (`Publish Cart` → `carts`).
   - With several ids, the parent comes first: the entity that exists before the other, i.e. whose id the
     other entity's events carry. `cart-id` + `item-id` → `/carts/:cart-id/items/:item-id`. If that can't
     be decided from the model, keep the order of the fields in slice.json.
3. **Suffix**
   - A page for one entity has no suffix (`/recipes/:recipe-id`), unless that path is taken; then use the
     screen's purpose from the slice name (`/recipes/:recipe-id/edit`).
   - A page listing entities is `/{entities}`, plus a qualifier from the slice name when needed
     (`/recipes/unarchived`).
   - A change is a `POST` to the resource plus the command name, kebab-cased
     (`/recipes/:recipe-id/archive-recipe`). A change that creates the entity posts to `/{entities}`.
4. **Collision check** — search the existing `routes` in `src/{basepackage}/slices/**/slice.clj` for the same
   path and verb. Depending on the router, a duplicate either stops Pedestal from starting or silently
   shadows one of the routes — and slice tests don't load `system.clj`, so they won't catch it. On a
   collision, add a suffix from the slice name.

Unless the path came from a `path:` comment, record it as an assumption in `progress.txt`.

The same rules are in the `build-state-change` skill; keep the two in sync.

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

(deftest {readmodel}
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

Only the views are tested; the page function, page handler and routes are not.

## Quality gate

Before marking this slice as `Done`, verify the implementation against slice.json:

- [ ] Every field in the read model / query result definition in slice.json has a field in the output of the `defview` — no invented fields
- [ ] Every INBOUND event of the read model has a reducer in its `defview` — no events missed or assumed
- [ ] Every GWT scenario in `specifications[]` has a corresponding test method
- [ ] No extra query parameters or filter logic were added beyond what slice.json defines
- [ ] No field names were assumed or guessed — if a field is not in slice.json, it is not in the code
- [ ] If there is a screen: the page shows the screen's fields, has a form for every command on the read model's screens, and every form has the `__anti-forgery-token` input
- [ ] If there is a screen: the path follows "Routes and paths", and the routes are in the `set/union` in `system.clj`
- [ ] If there is a scion: it is required in `scions.cljs`
- [ ] run the slice's own tests only (`clojure -X:test :nses '[{basepackage}.slices.{context}.{slicename}.slice-test]'` — namespace symbols, not strings; strings match nothing and run zero tests)
