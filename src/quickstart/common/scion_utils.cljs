(ns quickstart.common.scion-utils)

(defn add-class
  [subjects class]
  (doseq [subject subjects]
    (-> subject .-classList (.add class)))
  subjects)

(defn remove-class
  [subjects class]
  (doseq [subject subjects]
    (-> subject .-classList (.remove class)))
  subjects)

(defn has-class?
  [subject class]
  (-> subject .-classList (.contains class)))

(defn request-current-url
  "Make a GET request to the current URL."
  [& {:keys [on-load]}]
  (let [request (doto (js/XMLHttpRequest.)
                  (.open "GET" "" true)
                  (.send))]
    (.addEventListener request "load" (fn [load-event]
                                        (on-load request load-event)))))

(defn post-form-data
  [url form-data & {:keys [on-load]}]
  (let [request (doto (js/XMLHttpRequest.)
                    (.open "POST" url true)
                    (.setRequestHeader "Content-Type" "application/x-www-form-urlencoded")
                    (.send (js/URLSearchParams. form-data)))]
      (.addEventListener request "load" (fn [load-event]
                                          (when on-load
                                            (on-load request load-event))))))

(defn post
  "Make POST request with params sent as form data."
  [url params & {:keys [on-load]}]
  (let [form-data (js/FormData.)]
    (doseq [[k v] params]
      (.append form-data (name k) v))
    (post-form-data url form-data :on-load on-load)))



(def dom-parser (js/DOMParser.))

(defn morph-html-into-document
  "Morph a root HTML element into the current document."
  [html]
  (let [html-document (.parseFromString dom-parser html "text/html")]
    (.morph js/Idiomorph
            (.querySelector js/document "html")
            (.querySelector html-document "html"))))
