(ns quickstart.common.grafter
  (:require
   [donut.system :as ds]
   [shadow.graft :as graft]))

(def component-map
  #::ds{:start (fn [_opts]
                 (graft/start pr-str))
        :stop (fn [{::ds/keys [instance]}]
                (graft/stop instance)
                nil)})
