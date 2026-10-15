(ns quickstart.common.event-store
  "Component that provides the event store.

  The scaffold uses ledjer's in-memory store: events are lost when the
  process stops. To persist events, add ledjer's `umadb` sub-project
  (see README.md) and build its store here instead."
  (:require
   [dk.planm.ledjer.store.memory :as store.memory]
   [donut.system :as ds]))

(def component-map
  #::ds{:start (fn [_]
                 (store.memory/in-memory-event-store))

        :stop (fn [_]
                nil)})
