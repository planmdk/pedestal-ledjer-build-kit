(ns quickstart.system-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [donut.system :as ds]
   [io.pedestal.connector.test :as connector.test]
   [quickstart.common.utils :as utils]
   [quickstart.system :as system]))

(deftest system-starts-and-stops
  (let [running (ds/signal (ds/system :test) ::ds/start)]
    (try
      (testing "the event store is in place"
        (is (some? (get-in running [::ds/instances :shared :event-store]))))

      (testing "an unknown route answers 404"
        (let [connector (get-in running [::ds/instances :webserver :pedestal])
              response (connector.test/response-for connector :get "/")]
          (is (= 404 (:status response)))))

      (testing "assets in public/ are served"
        (let [connector (get-in running [::ds/instances :webserver :pedestal])
              response (connector.test/response-for connector :get "/js/idiomorph.min.js")]
          (is (= 200 (:status response)))))

      (testing "the routing table is registered while the system runs"
        (is (some? utils/url-for)))

      (finally
        (ds/signal running ::ds/stop)))

    (testing "the routing table is cleared when the system has stopped"
      (is (nil? (utils/url-for-safe :anything))))))

(deftest named-systems
  (testing "dev, test and prod read their config profile"
    (is (true? (get-in (system/read-config :dev) [:webserver :service-map :dev-mode])))
    (is (true? (get-in (system/read-config :test) [:webserver :service-map :dev-mode])))
    (is (false? (get-in (system/read-config :prod) [:webserver :service-map :dev-mode])))))
