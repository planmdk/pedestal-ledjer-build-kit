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
- **readmodels[]** — the read model(s) this slice serves
- **specifications[]** — test scenarios (given/when/then)

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


## Step 3 — the read slice

```clojure
(ns {basepackage}.slices.{context}.{slicename}.slice
  (:require
   [clojure.set :as set]
   [{basepackage}.common.interceptors :as interceptors]
   [{basepackage}.slices.events :as ev]
   [{basepackage}.common.utils :as utils]
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

## Quality gate

TODO — this stack's build command and how to run only this slice's tests (not the full suite).
