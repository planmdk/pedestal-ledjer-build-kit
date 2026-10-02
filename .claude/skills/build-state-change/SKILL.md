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

> **TODO — placeholder installed by `init --build-kit`.** Study an existing stack's
> build-state-change/SKILL.md (stacks/node, stacks/supabase, stacks/axon, or stacks/umadb,
> under `templates/.claude/skills/`) for the level of detail expected, then rewrite
> every section below for your stack's real conventions — file layout, naming, and
> the actual framework/language idioms. Delete this callout once done.

> Before doing anything else, read the slice definition from `.slices/{Context}/{slicename}/slice.json`.
> This file is the **source of truth** for all fields, events, and metadata — never invent
> fields not defined there. Keep this line verbatim; it applies to every stack.

---

## What a State Change Slice is

TODO — describe a write-side slice (command validated against replayed events, new events emitted) in terms of your stack's own primitives.

One pattern only. Directory layout is flat - `src/{basepackage}/slices/{context}/{slicename}/slice.clj`, shared
`src/{basepackage}/slices/events.clj` for the events.

A state change slice consists of:

- A command function of `event-store` and `payload`
- A projection of existing events for consistency checks
- A change handler Pedestal interceptor
- A set of routes in Pedestal route syntax in `routes` containing one entry for the change handler interceptor

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

## Step 3 — the command function

## Step 4 - the projection of existing events

## Step 5 - the change handler interceptor

## Step 6 - the routes definition

## Step 7 - the tests

## Step 8 - the storyline-derived tests (optional)

TODO — file layout, naming conventions, framework idioms, how the handler/projection/reactor is wired up.

## Quality gate

TODO — this stack's build command and how to run only this slice's tests (not the full suite).

Before considering the slice done:

- [ ] Every field in slice.json's `commands[]` is in the Command record — no invented fields, none missing
- [ ] Every field in slice.json's `events[]` is in the Event record — no invented fields, none missing
- [ ] Every `specifications[]` scenario has a corresponding test method
??- [ ] If `storylines[]` is present: every COMMAND beat for this slice's command has a storyline test — or was deliberately skipped as untraceable
- [ ] No business rule exists in the handler that isn't traceable to slice.json's `description`/`comments`
??- [ ] The Decision's `relevantEvents` query and the append's consistency-boundary query are the SAME query
??- [ ] `mvn compile -q`, then run the slice's own tests only
- [ ] If checks pass, commit with `feat: {Slice Name}` and set slice status to `Done`
