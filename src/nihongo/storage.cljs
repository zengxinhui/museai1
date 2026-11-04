(ns nihongo.storage
  "All user account data lives in this browser's localStorage.
   Nothing is sent to a server: there is no server in this app.
   The stored value is one EDN string, so no account data ever
   leaves the device that created it.

   Shape:
   {:version 1
    :current \"account-name\"
    :accounts {\"account-name\"
               {:name \"account-name\"
                :created-at 0
                :progress {\"item-id\" {:state \"learning\"|\"learned\"
                                       :reviews n :level n
                                       :learned-at ms :next-review ms
                                       :last-seen ms}}}}}"
  (:require [cljs.reader :as reader]))

(def storage-key "nihongo-learner-v1")

(def default-db {:version 1 :current nil :accounts {}})

(defn load-db []
  (try
    (if-let [raw (.getItem js/localStorage storage-key)]
      (let [db (reader/read-string raw)]
        (if (and (map? db) (= 1 (:version db))) db default-db))
      default-db)
    (catch :default _ default-db)))

(defn save-db! [db]
  (try
    (.setItem js/localStorage storage-key (pr-str db))
    (catch :default _ nil)))

(defn parse-backup
  "Parse a backup file's EDN text. Returns the db map if it looks like
   a Japanese Learner backup (version 1, accounts map, current account
   either nil or present), otherwise nil. Used by Upload/Restore."
  [text]
  (try
    (let [db (reader/read-string text)]
      (when (and (map? db)
                 (= 1 (:version db))
                 (map? (:accounts db))
                 (or (nil? (:current db))
                     (contains? (:accounts db) (:current db))))
        (assoc db :current (or (:current db)
                               (first (keys (:accounts db)))))))
    (catch :default _ nil)))

(defn new-account [name now]
  {:name name :created-at now :progress {}})

(defn account-progress [db account-name id]
  (get-in db [:accounts account-name :progress id]))

(defn item-state
  "One of :new :learning :learned. Absence from progress means :new."
  [db account-name id]
  (keyword (or (:state (account-progress db account-name id)) "new")))

(defn update-progress! [db-atom account-name id f]
  (swap! db-atom update-in [:accounts account-name :progress id]
         (fn [p] (f (or p {:state "new" :reviews 0 :level 0})))))

;; Spaced review: intervals in days, by review level.
;; 1, 2, 4, 8, 16, 32 -- 32 days is the upper limit: levels at or
;; above the last interval stay at 32 days, they do not grow further.
(def review-intervals [1 2 4 8 16 32])

(def max-review-level (dec (count review-intervals)))

(defn next-review-ms [now level]
  (+ now (* (nth review-intervals (min level max-review-level))
            24 60 60 1000)))
