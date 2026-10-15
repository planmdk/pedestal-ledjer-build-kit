(ns quickstart.slices.events
  "Every event in the system. Slices add their events here."
  (:require
   [dk.planm.ledjer :refer [defevent]]
   [dk.planm.ledjer.event :refer [payload-path]]
   [malli.core :as m]
   [malli.experimental.time :as met]
   [malli.registry :as mr]))

(mr/set-default-registry!
  (mr/composite-registry
    (m/default-schemas)
    (met/schemas)))
