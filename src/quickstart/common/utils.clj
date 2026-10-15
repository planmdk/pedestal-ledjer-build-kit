(ns quickstart.common.utils
  (:require
   [io.pedestal.http.route :as route]))

(set! *warn-on-reflection* true)

(defonce ^{:private true}
  routing-fn (atom nil))

(defn register-routing-table
  [rt]
  (try
    (reset! routing-fn (route/url-for-routes rt))
    (catch Exception e
      (println (ex-data e))
      (println (ex-cause e)))))

(defn clear-routing-table
  []
  (reset! routing-fn nil))

(def ^{:doc "Matches pedestal's url-for function but constructed
without use of dynamic vars, and thus usable outside a request
context."}
  url-for nil)

(defn url-for-safe
  "The safe cousin of url-for, returns nil when a route is not found."
  [route-name & options]
  (when-let [backing-fn @routing-fn]
    (try
      (apply backing-fn route-name options)
      (catch Exception _ex
        nil))))

(add-watch routing-fn
           :routing-fn-watch
           (fn [_key _ref _old new]
             (alter-var-root #'url-for (constantly new))))

(defn ->uuid
  "Coerce x to a UUID, e.g. an ID taken from a path parameter.

  UUIDs are returned as is and strings are parsed. Anything else,
  including strings that are not UUIDs, gives nil."
  [x]
  (cond
    (uuid? x) x
    (string? x) (parse-uuid x)
    :else nil))
