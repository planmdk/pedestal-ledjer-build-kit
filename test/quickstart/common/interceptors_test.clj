(ns quickstart.common.interceptors-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [dk.planm.ledjer :as l :refer [defevent]]
   [dk.planm.ledjer.event :refer [payload-path]]
   [dk.planm.ledjer.store.memory :as store.memory]
   [io.pedestal.http.route :as route]
   [quickstart.common.interceptors :as interceptors]
   [quickstart.common.utils :as utils]
   ;; Registers the :time/... schemas the events may use.
   [quickstart.slices.events]))

;; A test-only event: the scaffold has no events of its own.
(defevent note-created
  :type :test/note-created
  :schema [:map
           [:note-id :uuid]
           [:user-id :uuid]]
  :tags [(payload-path :note-id) (payload-path :user-id)])

(def note-authorization
  (interceptors/authorized-interceptor
   :event-type (note-created)
   :entity-id-key :note-id
   :entity-id-path [:request :path-params :note-id]))

(defn- enter
  [interceptor context]
  ((:enter interceptor) context))

(defn- status
  [context]
  (get-in context [:response :status]))

(deftest authenticated-interceptor
  (let [login-routes (route/expand-routes
                      #{["/login" :get (fn [_] {:status 200}) :route-name :test/login]})
        config {:auth {:login-route :test/login}}]
    (try
      (testing "an authenticated request is let through"
        (let [context {:app-config config
                       :request {:uri "/x" :session {:user-id (random-uuid)}}}]
          (is (= context (enter interceptors/authenticated-interceptor context)))))

      (testing "without a login route in the routing table, the response is 401"
        (utils/clear-routing-table)
        (is (= 401 (status (enter interceptors/authenticated-interceptor
                                  {:app-config config
                                   :request {:uri "/x"}})))))

      (testing "otherwise it redirects to the configured login route, remembering where to go next"
        (utils/register-routing-table login-routes)
        (let [context (enter interceptors/authenticated-interceptor
                             {:app-config config
                              :request {:uri "/x"}})]
          (is (= 303 (status context)))
          (is (= "/login?next=%2Fx" (get-in context [:response :headers "location"])))))

      (finally
        (utils/clear-routing-table)))))

(defn- authorize
  "Run the authorization for a request by user-id for note-id."
  [event-store user-id note-id]
  (enter note-authorization
         {:event-store event-store
          :request {:session (when user-id {:user-id user-id})
                    :path-params {:note-id note-id}}}))

(defn- rejected?
  [context]
  (= 404 (status context)))

(deftest authorized-interceptor
  (let [event-store (store.memory/in-memory-event-store)
        owner (random-uuid)
        other-user (random-uuid)
        note-id (random-uuid)
        other-note-id (random-uuid)]
    (l/append event-store [(note-created {:note-id note-id :user-id owner})
                           (note-created {:note-id other-note-id :user-id other-user})])

    (testing "the owner is let through, with the ID as it arrives in the path"
      (is (nil? (:response (authorize event-store owner (str note-id))))))

    (testing "another user gets 404"
      (is (rejected? (authorize event-store other-user (str note-id)))))

    (testing "the owner gets 404 for another user's note"
      (is (rejected? (authorize event-store owner (str other-note-id)))))

    (testing "an unknown ID gets 404"
      (is (rejected? (authorize event-store owner (str (random-uuid))))))

    (testing "a malformed ID gets 404"
      (is (rejected? (authorize event-store owner "not-a-uuid"))))

    (testing "a missing ID gets 404"
      (is (rejected? (authorize event-store owner nil))))

    (testing "a missing session user gets 404"
      (is (rejected? (authorize event-store nil (str note-id)))))))
