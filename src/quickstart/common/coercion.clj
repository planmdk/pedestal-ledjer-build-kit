(ns quickstart.common.coercion
  "Coercion of request values, and the project's fixed time zone.

  Form and path values arrive as strings. `transformer` turns them into
  the types of a command's malli schema, including the shapes HTML
  forms send:

  - an empty value (\"\") is dropped, as if it wasn't sent
  - a ticked checkbox sends \"on\", which becomes true
  - a single value for a [:vector ...] becomes a one-element vector
  - a missing key with a :default gets the default (an unticked
    checkbox, an empty list)
  - a :time/instant may be a local date-time without a zone, as sent by
    <input type=\"datetime-local\">; it is read in `zone`

  The :time/... schemas must be in malli's default registry."
  (:require
   [malli.experimental.time.transform :as mett]
   [malli.transform :as mt])
  (:import
   (java.time Instant LocalDateTime OffsetDateTime ZoneId)
   (java.time.format DateTimeParseException)
   (java.time.temporal ChronoUnit)))

(set! *warn-on-reflection* true)

(def ^ZoneId zone
  "The project's fixed time zone, a java.time.ZoneId.

  TODO: set this once, to the zone of your project, e.g.
  (ZoneId/of \"Europe/Copenhagen\"). It is nil until you do, and
  everything that needs a zone throws (see `zone!`) rather than
  guessing one. Slices must never set or change it."
  nil)

(defn- zone!
  "The project's time zone. Throws if it has not been set."
  ^ZoneId []
  (or zone
      (throw (ex-info (str "The project time zone is not set. Set `zone` in "
                           "quickstart.common.coercion (see README.md). "
                           "Slices must not guess a zone: ask for feedback.")
                      {:var #'zone}))))

(defn- parse-or-nil
  [parse s]
  (try
    (parse s)
    (catch DateTimeParseException _
      nil)))

(defn- string->instant
  "Decode an ISO instant, an offset date-time, or a local date-time in
  `zone` to an Instant. Anything else is returned unchanged, so it
  fails validation."
  [x]
  (if (string? x)
    (or (parse-or-nil #(Instant/parse %) x)
        (parse-or-nil #(.toInstant (OffsetDateTime/parse %)) x)
        (parse-or-nil #(.toInstant (.atZone (LocalDateTime/parse %) (zone!))) x)
        x)
    x))

(defn- drop-blank-values
  [x]
  (if (map? x)
    (into {} (remove (fn [[_ v]] (= "" v))) x)
    x))

(defn- single->vector
  [x]
  (if (or (nil? x) (sequential? x))
    x
    [x]))

(defn- checkbox->boolean
  [x]
  (if (= "on" x)
    true
    x))

(def transformer
  "The transformer for every m/coerce of request values."
  (mt/transformer
   {:name ::form
    :decoders {:map drop-blank-values
               :vector single->vector
               :boolean checkbox->boolean}}
   mt/default-value-transformer
   mt/string-transformer
   {:name ::instant-in-zone
    :decoders {:time/instant string->instant}}
   (mett/time-transformer)))

(defn instant->local-date-time
  "The local date-time of instant in `zone`, to the minute: the value of
  an <input type=\"datetime-local\">, and what pages show."
  [^Instant instant]
  (some-> instant
          (.atZone (zone!))
          (.toLocalDateTime)
          (.truncatedTo ChronoUnit/MINUTES)))
