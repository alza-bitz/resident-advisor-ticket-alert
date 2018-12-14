(ns resident-advisor-ticket-alert.core
  (:require
   [cemerick.url :as url]
   #?(:cljs [cljs.core.async :as async])
   #?(:cljs [resident-advisor-ticket-alert.xhr2])
   #?(:clj [clj-http.client :as http] :cljs [cljs-http.client :as http])
   #?(:cljs [cljs.reader :refer [read-string]])
   [clojure.data :as data]
   #?(:clj [clojure.java.io :as io] :cljs [cljs-node-io.core :as io :refer [slurp spit]])
   [clojure.string :as str]
   #?(:cljs [resident-advisor-ticket-alert.jsdom])
   [hickory.core :as h]
   [hickory.select :as hs]))

(defn timestamp
  []
  #?(:clj
     (str (java.time.Instant/now))
     :cljs
     (.toISOString (js/Date.))))

(defn ticket-snapshot
  [event-url ticket-data]
  (hash-map :url event-url
            :file (str (last (str/split (:path (url/url event-url)) #"/")) ".edn")
            :timestamp (timestamp); TODO replace with clock (referential transparency)
            :ticket-data ticket-data))

(defn ticket-snapshot-diff
  [previous latest]
  (hash-map :previous previous
            :latest latest
            :ticket-data-diff (data/diff (:ticket-data previous) (:ticket-data latest))))

(defn ticket-summary-changed
  [snapshot-diff]
  {:url (-> snapshot-diff :latest :url)
   :status "changed"
   :previous (-> snapshot-diff :previous :timestamp)
   :ticket-data-only-in-previous (-> snapshot-diff :ticket-data-diff first)
   :latest (-> snapshot-diff :latest :timestamp)
   :ticket-data-only-in-latest (-> snapshot-diff :ticket-data-diff second)
   :ticket-data-in-both (-> snapshot-diff :ticket-data-diff next next first)})

(defn ticket-summary-unchanged
  [snapshot-diff]
  {:url (-> snapshot-diff :latest :url)
   :status "unchanged"
   :previous (-> snapshot-diff :previous :timestamp)
   :latest (-> snapshot-diff :latest :timestamp)})

(defn event-data
  [event-url event-handler]
  #?(:clj
     (event-handler event-url (let [response (http/get event-url)]
                                (:body response)))
     :cljs
     (async/go (let [response (async/<! (http/get event-url))]
                 (event-handler event-url (:body response))))))

(defn process-event
  [event-url event-data]
  (as-> event-data $
    (h/parse $)
    (h/as-hickory $)
    (hs/select (hs/child (hs/id :tickets) (hs/tag "ul") (hs/tag "li")) $)
    (ticket-snapshot event-url $)
    (if (.exists (io/file (:file $)))
      (identity $)
      (do (spit (:file $) $) $))
    (ticket-snapshot-diff (read-string (slurp (:file $))) $)
    (if (or (first (:ticket-data-diff $)) (second (:ticket-data-diff $)))
      (do
        (spit (:file $) (:latest $))
        (ticket-summary-changed $))
      (ticket-summary-unchanged $))))

(defn ticket-summary
  [event-url]
  (event-data event-url process-event))