(ns resident-advisor-ticket-alert.core
  (:require
   [cemerick.url :as url]
   #?(:cljs [cljs.core.async :as async])
   #?(:cljs [cljs.nodejs :as nodejs])
   #?(:clj [clj-http.client :as http] :cljs [cljs-http.client :as http])
   #?(:cljs [cljs.reader :refer [read-string]])
   [clojure.data :as data]
   #?(:clj [clojure.java.io :as io] :cljs [cljs-node-io.core :as io :refer [slurp spit]])
   #?(:clj [clojure.pprint :as pprint] :cljs [cljs.pprint :as pprint])
   [clojure.string :as str]
   [clojure.walk :as walk]
   [hickory.core :as h]
   [hickory.select :as hs]))

; domain
(defn hickory-str-trim
  [hickory-tree]
  (walk/postwalk #(if (string? %) (str/trim %) %) hickory-tree))

; domain
(defn ticket-data
  [event-data]
  (->> event-data
       h/parse
       h/as-hickory
       hickory-str-trim
       (hs/select (hs/child (hs/id :tickets) (hs/tag "ul") (hs/tag "li")))))

; domain
(defn timestamp
  []
  #?(:clj
     (str (java.time.Instant/now))
     :cljs
     (.toISOString (js/Date.))))

; domain
(defn ticket-snapshot
  [event-url ticket-data]
  (hash-map :url event-url
            :file (str (last (str/split (:path (url/url event-url)) #"/")) ".edn")
            :timestamp (timestamp); TODO replace with clock (referential transparency)
            :ticket-data ticket-data))

; domain
(defn ticket-snapshot-diff
  [previous latest]
  (hash-map :previous previous
            :latest latest
            :ticket-data-diff (data/diff (:ticket-data previous) (:ticket-data latest))))

; domain
(defn ticket-summary-changed
  [snapshot-diff]
  {:url (-> snapshot-diff :latest :url)
   :status "changed"
   :previous (dissoc (:previous snapshot-diff) :url)
   :latest (dissoc (:latest snapshot-diff) :url)
   :ticket-data-only-in-previous (-> snapshot-diff :ticket-data-diff first)
   :ticket-data-only-in-latest (-> snapshot-diff :ticket-data-diff second)
   :ticket-data-in-both (-> snapshot-diff :ticket-data-diff next next first)})

; domain
(defn ticket-summary-unchanged
  [snapshot-diff]
  {:url (-> snapshot-diff :latest :url)
   :status "unchanged"
   :previous (dissoc (:previous snapshot-diff) :url :ticket-data)
   :latest (dissoc (:latest snapshot-diff) :url :ticket-data)})

; debug
(defn doto-pprint
  [v msg]
  (println msg)
  (pprint/pprint v))

; domain
(defn ticket-summary
  [event-url previous-snapshot latest-snapshot]
  (as-> (ticket-snapshot-diff previous-snapshot latest-snapshot) $
    (doto $ (doto-pprint "ticket-snapshot-diff"))
    (if (or (first (:ticket-data-diff $)) (second (:ticket-data-diff $)))
      (ticket-summary-changed $)
      (ticket-summary-unchanged $))))

; app
(defn event-data-handler
  [event-url event-data]
  (as-> event-data $
    (ticket-data $)
    (doto $ (doto-pprint "ticket-data"))
    (ticket-snapshot event-url $)
    (doto $ (doto-pprint "ticket-snapshot (latest)"))
    (if (.exists (io/file (:file $)))
      (identity $)
      (do (spit (:file $) $) $))
    (ticket-summary event-url (read-string (slurp (:file $))) $)
    (doto $ (doto-pprint "ticket-summary"))
    (if (= "changed" (:status $)) (spit (:file (:latest $)) (:latest $)))))

#?(:cljs (def jsdom (nodejs/require "jsdom")))

; app
(defn ticket-alert
  [event-url]
  #?(:clj
     (let [response (http/get event-url)
           event-data (:body response)]
       (event-data-handler event-url event-data))
     :cljs
     (async/go
       (with-redefs [js/XMLHttpRequest (nodejs/require "xhr2")
                     js/Node goog.dom.NodeType
                     js/NodeList goog.dom.NodeList
                     js/document (.-document (.-window (jsdom.JSDOM.)))
                     hickory.core/Attribute nil] ; https://github.com/davidsantiago/hickory/pull/33#issuecomment-447198772
         (let [response (async/<! (http/get event-url))
               event-data (:body response)]
           (event-data-handler event-url event-data))))))
