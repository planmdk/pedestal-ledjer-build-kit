(ns quickstart.scions
  "Scions are small pieces of interactivity grafted on to parts of the HTML.

  Similar to the system namespace, this namespace collects the scions
  of each slice, so it is important to require a slice's scion
  namespace here, or it won't be compiled to Javascript.

  Apart from collecting slices' scions, this namespace provides a
  generally useful one:

  - reinitialize-scions: Uses a MutationObserver to set up scions
  added to the DOM after the page loaded, e.g. after Idiomorph has
  merged an AJAX response into the active DOM tree. Only scions that
  have not marked their container as initialized (see
  `scion-utils/mark-initialized`) are set up, so every scion should
  mark its container. Add it to a page with
  `(h/raw-string (graft \"reinitialize-scions\" :next-sibling {}))`
  on an element that contains the morphed content."
  (:require
   [cljs.reader :as reader]
   [quickstart.common.scion-utils :as utils]
   [shadow.graft :as graft]))

(defonce graft-decoder reader/read-string)

(defn- initialize-new-scions
  [root-element]
  (let [scion-scripts (.querySelectorAll root-element "script[type=\"shadow/graft\"]")]
    (.forEach scion-scripts
              (fn [scion-script]
                (let [scion-target (graft/get-dom-ref scion-script (.getAttribute scion-script "data-ref"))]
                  (when-not (utils/initialized? scion-target)
                    (graft/run-script-tag graft-decoder scion-script)))))))

(defmethod graft/scion "reinitialize-scions"
  [_opts container]
  (if js/MutationObserver
    (let [observer (js/MutationObserver. (fn [_]
                                           (initialize-new-scions container)))]
      (.observe observer container #js {:subtree true
                                        :childList true}))
    (.warn js/console "reinitialize-scions: MutationObserver not supported by browser.")))

(defn init
  []
  (graft/init graft-decoder))
