(ns quickstart.common.interceptors
  (:require
   [dk.planm.ledjer :as l]
   [dk.planm.ledjer.event :as le]
   [huff2.core :as h]
   [io.pedestal.http.response :refer [respond-with]]
   [io.pedestal.interceptor :as interceptor]
   [quickstart.common.utils :as utils]))

(defn grafter-interceptor
  [grafter]
  (interceptor/interceptor
   {:name ::grafter
    :enter (fn [context]
             (assoc-in context [:request :grafter] grafter))}))

(defn event-store-interceptor
  [event-store]
  (interceptor/interceptor
   {:name ::event-store
    :enter (fn [ctx]
             (assoc ctx :event-store event-store))}))

(defn- build-page
  [h]
  (h/page [:html {:lang "en"}
           [:head
            [:meta {:charset "utf-8"}]
            [:meta {:name "referrer" :content "no-referrer"}]
            [:meta {:name "viewport" :content "width=device-width, initial-scale=1.0"}]
            [:script {:src "/js/scions.js" :defer true}]
            [:script {:src "/js/idiomorph.min.js"}]]
           [:body
            h]]))

(def hiccup-interceptor
  (interceptor/interceptor
   {:name ::hiccup
    :leave (fn [context]
             (let [response (:response context)]
               (if (vector? (:body response))
                 (update-in context [:response :body] build-page)
                 context)))}))

(defn authenticated?
  [context]
  (get-in context [:request :session :user-id]))

(def authenticated-interceptor
  "Lets authenticated requests through. Others are redirected to the
  route named :user-management/login-page.

  The scaffold has no login slice: model login on the board. Until a
  route with that name exists, unauthenticated requests get a plain 401."
  (interceptor/interceptor
   {:name ::authenticated?
    :enter (fn [context]
             (if (authenticated? context)
               context
               (let [request (:request context)
                     login-url (utils/url-for-safe :user-management/login-page
                                                   {:query-params {:next (:uri request)}})]
                 (if login-url
                   (respond-with context 303 {"location" login-url} "Unauthenticated")
                   (respond-with context 401 "Unauthenticated")))))}))

(defn- authorized?
  "Test if session user is authorized to access a resource.

  Authorization is determined by:

  - event-type of event that contains the association between entity
  and user (e.g. :recipe-management/recipe-created), tagged with both
  entity-id-key and :user-id
  - entity-id-key, the payload key holding the entity's ID
  - entity-id-path to determine where
  in the context to find the entity-id of the entity being accessed"
  [context event-type entity-id-key entity-id-path]
  (let [event-store (get-in context [:event-store])
        user-id (get-in context [:request :session :user-id])
        entity-id (utils/->uuid (get-in context entity-id-path))]
    ;; Never query with a nil binding: it is a wildcard.
    (boolean
     (and (uuid? user-id)
          (uuid? entity-id)
          (seq (l/source event-store #{{:event-types #{event-type}
                                        :tag-bindings {(le/payload-path entity-id-key) entity-id
                                                       (le/payload-path :user-id) user-id}}}))))))

(defn authorized-interceptor
  "Responds with 404 Not Found unless the session user is authorized to
  access the entity (see authorized?). 404 rather than 403, so as not
  to reveal whether other users' entities exist."
  [& {:keys [event-type entity-id-key entity-id-path]}]
  (interceptor/interceptor
   {:name ::authorized?
    :enter (fn [context]
             (if (authorized? context event-type entity-id-key entity-id-path)
               context
               (respond-with context 404 "Not found")))}))
