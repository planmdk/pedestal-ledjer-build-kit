(ns quickstart.system
  (:require
   [aero.core :as aero]
   [clojure.java.io :as io]
   [clojure.set :as set]
   [donut.system :as ds]
   [io.pedestal.connector :as connector]
   [io.pedestal.connector.dev :as connector-dev]
   [io.pedestal.http.body-params :as body-params]
   [io.pedestal.http.csrf :as csrf]
   [io.pedestal.http.jetty :as jetty]
   [io.pedestal.http.ring-middlewares :as middlewares]
   [io.pedestal.http.route :as route]
   [io.pedestal.http.secure-headers :as secure-headers]
   [io.pedestal.log :as log]
   [io.pedestal.service.interceptors :as pedestal-interceptors]
   [quickstart.common.event-store :as event-store]
   [quickstart.common.grafter :as grafter]
   [quickstart.common.interceptors :as interceptors]
   [quickstart.common.utils :as utils]))

(set! *warn-on-reflection* true)

(defn- log-progress
  [event-type]
  (fn [{::ds/keys [system]}]
    (log/info :msg "Progress event"
              :type event-type
              :component (::ds/component-id system))))

(defn read-config [profile]
  (aero/read-config (io/resource "config.edn") {:profile profile
                                                :resolver aero/resource-resolver}))

(def dev-app-config #::ds{:start (fn [_] (read-config :dev))})
(def test-app-config #::ds{:start (fn [_] (read-config :test))})
(def prod-app-config #::ds{:start (fn [_] (read-config :prod))})

(def base-system
  {::ds/base #::ds{:post-start (log-progress :start)
                   :post-stop (log-progress :stop)}
   ::ds/defs
   {:shared
    {:app-config dev-app-config

     :event-store event-store/component-map}

    ;; Slices with automations add their `component-map` here, e.g.
    ;; {:new-user slice.user-management.new-user/component-map}
    :automations
    {}

    ;; Slices with persisted views add their `component-map` here, keyed by
    ;; the view's name, e.g.
    ;; {:product-search slice.catalog.product-search/component-map}
    :views
    {}

    :webserver
    {:routes #::ds{:start (fn [_]
                            ;; Slices add their `routes` to this union (and
                            ;; require their namespace above).
                            (let [routes (set/union #{})]
                              (utils/register-routing-table (route/expand-routes routes))
                              routes))

                   :stop (fn [_]
                           (utils/clear-routing-table)
                           nil)}

     :connector #::ds{:start (fn [{:keys [::ds/config]}]
                               (let [{:keys [port dev-mode]} (get-in config [:app-config :webserver :service-map])
                                     {:keys [event-store grafter routes views]} config]
                                 (-> (connector/default-connector-map port)
                                     (cond-> dev-mode
                                       (connector/with-interceptors connector-dev/dev-interceptors))
                                     (connector/with-interceptors
                                       [pedestal-interceptors/not-found
                                        (middlewares/session {:cookie-attrs {:same-site :strict
                                                                             :secure true
                                                                             :http-only true}})
                                        (middlewares/flash)
                                        (middlewares/content-type)
                                        route/query-params
                                        (body-params/body-params (body-params/default-parser-map))
                                        (csrf/anti-forgery)
                                        (secure-headers/secure-headers
                                         {:content-security-policy-settings
                                          (secure-headers/content-security-policy-header (cond-> {:object-src "'none'"
                                                                                                  :default-src "'self'"
                                                                                                  :style-src "'self' 'unsafe-inline'"}
                                                                                           dev-mode (assoc :script-src "'self' 'unsafe-eval'"
                                                                                                           :connect-src "'self' ws://localhost:*")))})
                                        (middlewares/not-modified)
                                        (middlewares/file "public/")
                                        (middlewares/file-info)
                                        (interceptors/event-store-interceptor event-store)
                                        (interceptors/grafter-interceptor grafter)
                                        (interceptors/views-interceptor views)
                                        pedestal-interceptors/html-body
                                        interceptors/hiccup-interceptor])
                                     (connector/with-routes routes))))
                      :stop (fn [_] nil)

                      :config {:app-config (ds/ref [:shared :app-config])
                               :event-store (ds/ref [:shared :event-store])
                               :grafter (ds/ref [:webserver :grafter])
                               :routes (ds/ref [:webserver :routes])
                               ;; donut rejects a ref to an empty group. When
                               ;; adding the first persisted view, replace {}
                               ;; with (ds/ref [:views]).
                               :views {}}}

     :pedestal #::ds{:start (fn [{:keys [::ds/config]}]
                              (-> (:connector config)
                                  (jetty/create-connector nil)
                                  (connector/start!)))
                     :stop (fn [{::ds/keys [instance]}]
                             (connector/stop! instance)
                             nil)
                     :config {:connector (ds/ref [:webserver :connector])}}

     :grafter grafter/component-map}}})

(defmethod ds/named-system :base
  [_]
  base-system)

(defmethod ds/named-system :dev
  [_]
  (ds/system :base {[:shared :app-config] dev-app-config}))

(defmethod ds/named-system :test
  [_]
  (ds/system :base {[:shared :app-config] test-app-config}))

(defmethod ds/named-system :prod
  [_]
  (ds/system :base {[:shared :app-config] prod-app-config}))
