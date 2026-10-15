(ns quickstart.common.coercion-test
  (:require
   [clojure.test :refer [deftest is testing use-fixtures]]
   [quickstart.common.coercion :as coercion]
   [malli.core :as m]
   [malli.experimental.time :as met]
   [malli.registry :as mr]))

;; The project zone is deliberately unset in the scaffold. These tests
;; check the coercion against a fixed zone, whatever the project sets.
(use-fixtures :each
  (fn [f]
    (with-redefs [coercion/zone (java.time.ZoneId/of "Europe/Copenhagen")]
      (f))))

(def ^:private registry
  (mr/composite-registry (m/default-schemas) (met/schemas)))

(defn- coerce
  [schema value]
  (m/coerce schema value coercion/transformer {:registry registry}))

(defn- error-paths
  "The paths of the fields that fail coercion, or nil if none do."
  [schema value]
  (try
    (coerce schema value)
    nil
    (catch clojure.lang.ExceptionInfo e
      (when (= :malli.core/coercion (:type (ex-data e)))
        (set (map :in (-> e ex-data :data :explain :errors)))))))

(def ^:private id
  #uuid "9e7ae82a-42f0-4298-85f4-fc2f0f689f43")

(deftest scalar-values
  (testing "strings become the schema's types"
    (is (= {:id id :n 3 :x 4.5 :day (java.time.LocalDate/parse "2026-10-13")}
           (coerce [:map [:id :uuid] [:n :int] [:x :double] [:day :time/local-date]]
                   {:id (str id) :n "3" :x "4.5" :day "2026-10-13"})))))

(deftest instants
  (testing "a datetime-local value is read in the project zone"
    (is (= (java.time.Instant/parse "2026-10-13T08:00:00Z")
           (coerce :time/instant "2026-10-13T10:00"))
        "summer time, UTC+2")
    (is (= (java.time.Instant/parse "2026-01-13T09:00:00Z")
           (coerce :time/instant "2026-01-13T10:00"))
        "winter time, UTC+1"))

  (testing "ISO instants and offset date-times are kept as they are"
    (is (= (java.time.Instant/parse "2026-10-13T08:00:00Z")
           (coerce :time/instant "2026-10-13T08:00:00Z")))
    (is (= (java.time.Instant/parse "2026-10-13T08:00:00Z")
           (coerce :time/instant "2026-10-13T10:00+02:00"))))

  (testing "anything else fails coercion"
    (is (= #{[:at]}
           (error-paths [:map [:at :time/instant]] {:at "not a date"})))))

(deftest form-shapes
  (let [schema [:map
                [:name :string]
                [:qty {:optional true} :int]
                [:done {:default false} :boolean]
                [:ids {:default []} [:vector :uuid]]]]
    (testing "a ticked checkbox sends \"on\""
      (is (true? (:done (coerce schema {:name "x" :done "on"})))))

    (testing "an unticked checkbox and an empty list send nothing and get their defaults"
      (is (= {:name "x" :done false :ids []}
             (coerce schema {:name "x"}))))

    (testing "a single value for a list becomes a one-element vector"
      (is (= [id] (:ids (coerce schema {:name "x" :ids (str id)}))))
      (is (= [id id] (:ids (coerce schema {:name "x" :ids [(str id) (str id)]})))))

    (testing "an empty optional value is dropped"
      (is (= {:name "x" :done false :ids []}
             (coerce schema {:name "x" :qty ""}))))

    (testing "an empty required value fails as missing"
      (is (= #{[:name]}
             (error-paths schema {:name ""}))))

    (testing "invalid values fail at their path"
      (is (= #{[:qty]}
             (error-paths schema {:name "x" :qty "two"})))
      (is (= #{[:ids 0]}
             (error-paths schema {:name "x" :ids "nope"}))))))

(deftest instant->local-date-time
  (is (= (java.time.LocalDateTime/parse "2026-01-13T10:00")
         (coercion/instant->local-date-time (java.time.Instant/parse "2026-01-13T09:00:30Z"))))
  (is (nil? (coercion/instant->local-date-time nil))))

(deftest unset-zone
  (with-redefs [coercion/zone nil]
    (testing "a local date-time cannot be read without a zone"
      (is (thrown-with-msg? Exception #"time zone is not set"
                            (coerce :time/instant "2026-10-13T10:00"))))

    (testing "an instant cannot be shown without a zone"
      (is (thrown-with-msg? Exception #"time zone is not set"
                            (coercion/instant->local-date-time (java.time.Instant/parse "2026-01-13T09:00:30Z")))))

    (testing "ISO instants need no zone"
      (is (= (java.time.Instant/parse "2026-10-13T08:00:00Z")
             (coerce :time/instant "2026-10-13T08:00:00Z"))))))
