(ns nihongo.core
  (:require [reagent.core :as r]
            [reagent.dom.client :as rdom-client]
            [nihongo.data :as data]
            [nihongo.storage :as store]))

;; ---- State ---------------------------------------------------------------
;; db is the only persisted atom; a watch writes it to localStorage.
;; ui holds transient view / quiz state and is never persisted.
(defonce db (r/atom (store/load-db)))
(defonce ui (r/atom {:view :learn :track :kana :level "N5"
                     :learn-item nil :search "" :quiz nil
                     :new-account "" :furigana? true}))

(add-watch db :persist
           (fn [_ _ _ new-db] (store/save-db! new-db)))

(defn current-name [] (:current @db))
(defn current-account [] (get-in @db [:accounts (current-name)]))
(defn state-of [id] (store/item-state @db (current-name) id))
(defn progress-of [id] (store/account-progress @db (current-name) id))

(def kanji-by-level (group-by :level data/kanji))
(def vocab-by-level (group-by :level data/vocab))
(def grammar-by-level (group-by :level data/grammar))

(defn track-items [track level]
  (case track
    :kana data/kana
    :kanji (get kanji-by-level level [])
    :vocab (get vocab-by-level level [])
    :grammar (get grammar-by-level level [])
    []))

(defn all-items []
  (concat data/kana data/kanji data/vocab data/grammar))

(defn current-items []
  (track-items (:track @ui) (:level @ui)))

(defn current-batch []
  (take 5 (remove #(= :learned (state-of (:id %))) (current-items))))

(defn counts-for [items]
  (let [states (map #(state-of (:id %)) items)]
    {:new (count (filter #(= :new %) states))
     :learning (count (filter #(= :learning %) states))
     :learned (count (filter #(= :learned %) states))}))

(defn due-items []
  (let [now (js/Date.now)]
    (->> (all-items)
         (filter (fn [item]
                   (let [p (progress-of (:id item))]
                     (and (= :learned (state-of (:id item)))
                          (:next-review p)
                          (<= (:next-review p) now)))))
         (sort-by (fn [item] (:next-review (progress-of (:id item))))))))

;; ---- Furigana ------------------------------------------------------------
;; OpenJLPT example sentences carry furigana as {kanji|reading} notation.
;; Render that as native HTML <ruby>, with no extra library. When furigana
;; is off (or a sentence has no furigana field), show the plain sentence.
;; plain-furigana strips the notation down to the kanji text, which is
;; what quizzes use so the reading answer is not given away.
(defn parse-furigana [s]
  (loop [s (or s "") out []]
    (let [open (clojure.string/index-of s "{")]
      (if (nil? open)
        (if (empty? s) out (conj out [:text s]))
        (let [bar (clojure.string/index-of s "|" open)
              close (clojure.string/index-of s "}" bar)]
          (if (or (nil? bar) (nil? close))
            (conj out [:text s])
            (recur (subs s (inc close))
                   (into out (filter some?
                                     [(when (pos? open) [:text (subs s 0 open)])
                                      [:ruby (subs s (inc open) bar)
                                       (subs s (inc bar) close)]])))))))))

(defn plain-furigana [s]
  (.replace (or s "") #"\{([^|]+)\|[^}]+\}" "$1"))

(defn ruby-view [furigana-text plain-text]
  (if (and (:furigana? @ui) (seq furigana-text))
    [:span
     (map-indexed
      (fn [i part]
        (case (first part)
          :text ^{:key i} [:span (second part)]
          :ruby ^{:key i} [:ruby (second part) [:rt (nth part 2)]]
          ^{:key i} [:span ""]))
      (parse-furigana furigana-text))]
    [:span plain-text]))

;; ---- Promotion -----------------------------------------------------------
(defn mark-learning! [id]
  (store/update-progress! db (current-name) id
                          (fn [p] (assoc p :state "learning" :last-seen (js/Date.now)))))

(defn record-quiz-pass! [id]
  (let [now (js/Date.now)]
    (store/update-progress! db (current-name) id
                            (fn [q] (assoc q :state "learned"
                                           :learned-at now
                                           :level 0
                                           :next-review (store/next-review-ms now 0))))))

(defn reset-review! [id]
  (let [now (js/Date.now)]
    (store/update-progress! db (current-name) id
                            (fn [q] (assoc q :state "learned"
                                           :level 0
                                           :next-review (store/next-review-ms now 0)
                                           :last-seen now)))))

(defn record-review-pass! [id]
  (let [p (progress-of id)
        new-level (min (inc (:level p 0)) store/max-review-level)
        now (js/Date.now)]
    (store/update-progress! db (current-name) id
                            (fn [q] (assoc q :state "learned"
                                           :reviews (inc (:reviews q 0))
                                           :level new-level
                                           :next-review (store/next-review-ms now new-level)
                                           :last-seen now)))))

;; ---- Quizzes -------------------------------------------------------------
;; Kana is tested on romaji only. Kanji and vocab are tested on reading
;; AND meaning. Grammar is tested on meaning only (the pattern is shown).
;; A quiz pass moves a Learning item to Learned. A review pass moves the
;; interval up a step; a review fail resets it to 1 day, staying Learned.
(defn make-options [correct others]
  (let [distractors (->> others
                         (remove #(= correct %))
                         distinct
                         (take 3))]
    (vec (shuffle (cons correct distractors)))))

(defn make-question [item pool]
  (case (:track item)
    :kana {:romaji-opts (make-options (:reading item) (map :reading pool))}
    :grammar {:meaning-opts (make-options (:meaning item) (map :meaning pool))}
    {:reading-opts (make-options (:reading item) (map :reading pool))
     :meaning-opts (make-options (:meaning item) (map :meaning pool))}))

(defn start-quiz! [kind queue]
  (if (empty? queue)
    (swap! ui assoc :quiz {:kind kind :queue [] :pos 0 :empty? true})
    (let [first-item (first queue)
          pool (track-items (:track first-item) (:level first-item))]
      (swap! ui assoc :quiz (merge {:kind kind :queue (vec queue) :pos 0
                                    :answered? false :sel-reading nil
                                    :sel-meaning nil :sel-romaji nil :ok? false}
                                   (make-question first-item pool))))))

(defn quiz-item []
  (let [q (:quiz @ui)]
    (when (and q (< (:pos q) (count (:queue q))))
      (nth (:queue q) (:pos q)))))

(defn advance-quiz! []
  (let [q (:quiz @ui)
        next-pos (inc (:pos q))]
    (if (>= next-pos (count (:queue q)))
      (swap! ui assoc-in [:quiz :pos] next-pos)
      (let [next-item (nth (:queue q) next-pos)
            pool (track-items (:track next-item) (:level next-item))]
        (swap! ui assoc :quiz (merge q {:pos next-pos :answered? false
                                        :sel-reading nil :sel-meaning nil
                                        :sel-romaji nil :ok? false}
                                     (make-question next-item pool)))))))

(defn submit-quiz! []
  (let [q (:quiz @ui)
        item (quiz-item)
        ok? (case (:track item)
              :kana (= (:sel-romaji q) (:reading item))
              :grammar (= (:sel-meaning q) (:meaning item))
              (and (= (:sel-reading q) (:reading item))
                   (= (:sel-meaning q) (:meaning item))))]
    (swap! ui assoc-in [:quiz :answered?] true)
    (swap! ui assoc-in [:quiz :ok?] ok?)
    (cond
      (and (= (:kind q) :review) ok?) (record-review-pass! (:id item))
      (and (= (:kind q) :review) (not ok?)) (reset-review! (:id item))
      ok? (record-quiz-pass! (:id item)))))

;; ---- Small pieces --------------------------------------------------------
(defn state-badge [id]
  (let [s (state-of id)]
    [:span {:class (str "badge badge-" (name s))}
     (case s :new "New" :learning "Learning" :learned "Learned")]))

(defn track-label [track]
  (case track :kana "Kana" :kanji "Kanji" :vocab "Vocabulary" :grammar "Grammar" "Item"))

(defn option-list [label options selected on-select]
  [:div {:style {:margin "12px 0"}}
   [:p [:strong label]]
   (for [opt options]
     ^{:key opt}
     [:label {:style {:display "block" :margin "6px 0" :cursor "pointer"}}
      [:input {:type "radio" :name label :checked (= selected opt)
               :on-change #(on-select opt)}]
      " " (if (> (count opt) 90) (str (subs opt 0 90) "…") opt)])])

(defn quiz-question-view []
  (let [q (:quiz @ui)
        item (quiz-item)]
    [:div {:class "card"}
     [:h3 (str (if (= (:kind q) :review) "Review: " "Quiz: ")
               (track-label (:track item))
               (when (:level item) (str " " (:level item)))
               "  (" (inc (:pos q)) " of " (count (:queue q)) ")")]
     [:div {:style {:font-size "72px" :text-align "center"}} (:display item)]
     (when (= :kana (:track item))
       [option-list "Romaji" (:romaji-opts q) (:sel-romaji q)
        #(swap! ui assoc-in [:quiz :sel-romaji] %)])
     (when (contains? #{:kanji :vocab} (:track item))
       [:div
        [option-list "Reading" (:reading-opts q) (:sel-reading q)
         #(swap! ui assoc-in [:quiz :sel-reading] %)]
        [option-list "Meaning" (:meaning-opts q) (:sel-meaning q)
         #(swap! ui assoc-in [:quiz :sel-meaning] %)]])
     (when (= :grammar (:track item))
       [option-list "Meaning" (:meaning-opts q) (:sel-meaning q)
        #(swap! ui assoc-in [:quiz :sel-meaning] %)])
     (if (:answered? q)
       [:div
        [:p (if (:ok? q)
              "✓ Correct."
              (str "✗ Not quite. The answer is "
                   (case (:track item)
                     :kana (:reading item)
                     :grammar (:meaning item)
                     (str (:reading item) " — " (:meaning item)))
                   "."))]
        (when (and (= (:kind q) :review) (not (:ok? q)))
          [:p "This item stays Learned, but its review interval resets to 1 day. It will be due again tomorrow."])
        (when (and (= (:kind q) :quiz) (not (:ok? q)))
          [:p "This item stays in Learning. Study it in Learn, then try the quiz again."])
        [:button {:on-click advance-quiz!} "Next"]]
       [:button {:disabled (case (:track item)
                         :kana (nil? (:sel-romaji q))
                         :grammar (nil? (:sel-meaning q))
                         (or (nil? (:sel-reading q)) (nil? (:sel-meaning q))))
                 :on-click submit-quiz!}
        "Check answer"])]))

(defn quiz-view [kind title blurb queue-fn]
  (let [q (:quiz @ui)]
    [:div
     [:h2 title] [:p blurb]
     (cond
       (nil? q)
       [:button {:on-click #(start-quiz! kind (queue-fn))} "Start"]
       (:empty? q)
       [:div [:p "Nothing to test here yet. In Learn, mark items as Learning first (for a quiz), or learn some items first (for Review)."]
        [:button {:on-click #(swap! ui assoc :quiz nil)} "Back"]]
       (>= (:pos q) (count (:queue q)))
       [:div [:p "✓ Quiz finished."] [:button {:on-click #(swap! ui assoc :quiz nil)} "Back"]]
       :else [quiz-question-view])]))

;; ---- Item detail ---------------------------------------------------------
(defn item-detail [item]
  [:div {:class "card"}
   [:div {:style {:display "flex" :align-items "baseline" :gap "16px" :flex-wrap "wrap"}}
    [:span {:style {:font-size "72px" :line-height "1"}} (:display item)]
    [:span {:style {:font-size "28px"}} (:reading item)]
    [state-badge (:id item)]]
   (case (:track item)
     :kana
     [:div
      [:p (str (:script item) " · " (:kind item))]
      [:p [:strong "Romaji: "] (:reading item)]]
     :kanji
     [:div
      [:p (str "JLPT " (:level item) " · " (:strokes item) " strokes"
               (when (:radical item) (str " · Radical " (:radical item)))
               (when (:freq item) (str " · Newspaper frequency #" (:freq item))))]
      [:p [:strong "On'yomi: "] (clojure.string/join ", " (:onyomi item))]
      [:p [:strong "Kun'yomi: "] (clojure.string/join ", " (:kunyomi item))]
      [:p [:strong "Meanings: "] (clojure.string/join "; " (:meanings item))]
      (when (seq (:words item))
        [:div [:p [:strong "Example words: "]]
         (for [[w r] (:words item)]
           ^{:key w}
           [:span {:style {:margin-right "12px"}} w (when r (str " (" r ")"))])])]
     :vocab
     [:div
      [:p (str "JLPT " (:level item) " · Romaji: " (:romaji item))]
      [:p [:strong "Meanings: "] (clojure.string/join "; " (:meanings item))]
      (when (:example item)
        [:div
         [:p [:strong "Example: "]
          [ruby-view (:furigana (:example item)) (:ja (:example item))]]
         [:p (:en (:example item))]])]
     :grammar
     [:div
      [:p (str "JLPT " (:level item))]
      [:p [:strong "Meaning: "] (:short item)]
      (when (seq (:formation item)) [:p [:strong "Formation: "] (:formation item)])
      (when (seq (:long item)) [:p (:long item)])
      (when (seq (:examples item))
        [:div [:p [:strong "Examples: "]]
         (for [e (:examples item)]
           ^{:key (:ja e)}
           [:div {:style {:margin "8px 0"}}
            [:p (:ja e)]
            [:p (:en e)]])])]
     [:p (:meaning item)])])

(defn track-selector []
  [:div {:style {:display "flex" :gap "8px" :flex-wrap "wrap" :margin "12px 0"}}
   (for [[t label] [[:kana "Kana"] [:kanji "Kanji"] [:vocab "Vocabulary"] [:grammar "Grammar"]]]
     ^{:key t}
     [:button {:class (when (= t (:track @ui)) "active")
               :on-click #(swap! ui assoc :track t :learn-item nil :quiz nil)}
      label])])

(defn level-selector []
  [:div {:style {:display "flex" :gap "8px" :flex-wrap "wrap" :margin "12px 0"}}
   (for [lvl ["N5" "N4" "N3" "N2" "N1"]]
     ^{:key lvl}
     [:button {:class (when (= lvl (:level @ui)) "active")
               :disabled (= :kana (:track @ui))
               :on-click #(swap! ui assoc :level lvl :learn-item nil :quiz nil)}
      lvl])])

;; ---- Main views ----------------------------------------------------------
(defn learn-view []
  (let [batch (current-batch)
        selected (or (some #(when (= (:id %) (:learn-item @ui)) %) batch)
                     (first batch))
        c (counts-for (current-items))]
    [:div
     [:h2 "Learn"]
     [track-selector]
     [level-selector]
     [:p (str "At most 5 at a time. In this selection: "
              (:learned c) " learned, " (:learning c) " in progress, " (:new c) " still new. "
              (count (due-items)) " item(s) are due for review overall.")]
     [:div {:class "batch"}
      (for [item batch]
        ^{:key (:id item)}
        [:button {:class (str "batch-btn" (when (= (:id item) (:id selected)) " active"))
                  :on-click #(swap! ui assoc :learn-item (:id item) :view :learn)}
         [:span {:style {:font-size "28px"}} (:display item)]
         [:br] (:reading item) [:br] [state-badge (:id item)]])]
     (if selected
       [:div
        [item-detail selected]
        [:div {:style {:margin-top "12px" :display "flex" :gap "8px" :flex-wrap "wrap"}}
         [:button {:on-click #(mark-learning! (:id selected))}
          (if (= :new (state-of (:id selected)))
            "I've studied this — move it to Learning"
            "Keep this in Learning")]
         [:button {:on-click #(swap! ui assoc :view :quiz :quiz nil)} "Test this batch"]]]
       [:p "🎉 Everything in this selection is learned. Pick another track or level."])]))

(defn browse-view []
  (let [search (:search @ui)
        q (clojure.string/lower-case (or search ""))
        items (current-items)
        filtered (filter (fn [item]
                           (or (empty? q)
                               (clojure.string/includes? (:display item) q)
                               (clojure.string/includes?
                                (clojure.string/lower-case (or (:reading item) "")) q)
                               (clojure.string/includes?
                                (clojure.string/lower-case (or (:meaning item) "")) q)))
                         items)]
    [:div
     [:h2 "Browse"]
     [track-selector]
     [level-selector]
     [:input {:type "text" :placeholder "Search word, reading or meaning…"
              :value search :style {:width "100%" :padding "10px" :font-size "16px"}
              :on-change #(swap! ui assoc :search (.. % -target -value))}]
     [:p (str (count filtered) " items in this selection"
              (when (> (count filtered) 200) ", showing the first 200. Search to narrow it down."))]
     [:div {:class "browse-grid"}
      (for [item (take 200 filtered)]
        ^{:key (:id item)}
        [:div {:class "browse-card"}
         [:span {:style {:font-size "32px"}} (:display item)] [:br]
         (:reading item) [:br]
         [:small (:meaning item)] [:br]
         [state-badge (:id item)]])]]))

(defn handle-restore-file [e]
  (when-let [file (aget (.. e -target -files) 0)]
    (let [reader (js/FileReader.)]
      (set! (.-onload reader)
            (fn []
              (let [restored (store/parse-backup (.-result reader))]
                (if (nil? restored)
                  (swap! ui assoc :restore-msg
                         "Could not restore: that file is not a valid Japanese Learner backup (EDN, version 1). Nothing was changed.")
                  (when (js/confirm (str "Restore backup? This will replace all "
                                         (count (:accounts @db))
                                         " account(s) currently in this browser with "
                                         (count (:accounts restored))
                                         " account(s) from the backup."))
                    (reset! db restored)
                    (swap! ui assoc :restore-msg
                           (str "Restored " (count (:accounts restored))
                                " account(s) from backup. Current account: "
                                (or (:current restored) "none") ".")))))))
      (.readAsText reader file))
    (set! (.. e -target -value) "")))

(defn restore-control []
  [:div {:style {:margin-top "12px"}}
   [:p "Restore from a backup file (.edn) previously downloaded from this app. "
    "This replaces all accounts currently in this browser — download a fresh backup first if you want to keep them."]
   [:input {:type "file" :accept ".edn" :on-change handle-restore-file}]
   (when-let [msg (:restore-msg @ui)]
     [:p msg])])

(defn accounts-view []
  (let [names (sort (keys (:accounts @db)))
        new-name (:new-account @ui)]
    [:div
     [:h2 "Accounts"]
     [:p "Accounts live only in this browser's localStorage. There is no server account, no password, "
      "and nothing is uploaded. If you clear this browser's site data, these accounts go with it — "
      "use Download backup to keep a copy."]
     [:div {:class "card"}
      [:h3 "Switch account"]
      (for [n names]
        ^{:key n}
        [:div {:style {:display "flex" :gap "8px" :margin "6px 0" :align-items "center"}}
         [:span (str n (when (= n (current-name)) "  (current)"))]
         [:button {:on-click #(swap! db assoc :current n)} "Switch"]
         [:button {:on-click (fn []
                               (when (js/confirm (str "Delete account " n " and all its progress?"))
                                 (swap! db (fn [d]
                                             (let [d2 (update d :accounts dissoc n)]
                                               (assoc d2 :current (first (keys (:accounts d2)))))))))}
          "Delete"]])
      [:h3 "New account"]
      [:input {:type "text" :placeholder "Account name" :value new-name
               :on-change #(swap! ui assoc :new-account (.. % -target -value))}]
      [:button {:disabled (empty? (clojure.string/trim new-name))
                :on-click (fn []
                            (let [n (clojure.string/trim new-name)
                                  now (js/Date.now)]
                              (swap! db (fn [d] (-> d
                                                    (assoc-in [:accounts n] (store/new-account n now))
                                                    (assoc :current n))))
                              (swap! ui assoc :new-account "" :view :learn)))}
       "Create and switch"]
      [:h3 "Backup"]
      [:button {:on-click (fn []
                            (let [blob (js/Blob. #js [(pr-str @db)] #js {:type "application/edn"})
                                  url (.createObjectURL js/URL blob)
                                  a (.createElement js/document "a")]
                              (set! (.-href a) url)
                              (set! (.-download a) "nihongo-learner-backup.edn")
                              (.click a)
                              (.revokeObjectURL js/URL url)))}
       "Download backup (EDN)"]
      [restore-control]]
     [:div {:class "card"}
      [:h3 "About the data"]
      [:p "JLPT has not published official vocabulary, kanji or grammar lists since 2010. Levels here are community-standard lists, a well-informed approximation, not a guarantee of exam content."]
      [:p "Vocabulary and kanji: OpenJLPT (CC BY-SA 4.0), verified against JMdict, with Tatoeba example sentences. Grammar: Hanabira (Creative Commons in-house content). Furigana in example sentences comes from OpenJLPT's {kanji|reading} notation; Hanabira grammar examples have no furigana field, so they show plain Japanese."]
      [:p "No stroke data is included, by design: JLPT does not test handwriting."]]]))

(defn account-setup []
  [:div {:class "card"}
   [:h2 "Welcome — make an account"]
   [:h3 "Restore from backup"]
   [restore-control]
   [:hr]
   [:p "This app keeps everything in this browser. Pick a name for your account to start learning Japanese for JLPT N5 to N1: kana first, then kanji, vocabulary and grammar by level."]
   [:input {:type "text" :placeholder "Your name"
            :value (:new-account @ui)
            :on-change #(swap! ui assoc :new-account (.. % -target -value))}]
   [:button {:disabled (empty? (clojure.string/trim (:new-account @ui)))
             :on-click (fn []
                         (let [n (clojure.string/trim (:new-account @ui))
                               now (js/Date.now)]
                           (swap! db (fn [d] (-> d
                                                 (assoc-in [:accounts n] (store/new-account n now))
                                                 (assoc :current n))))
                           (swap! ui assoc :new-account "" :view :learn)))}
    "Start learning"]])

(defn app []
  (if (nil? (current-name))
    [:div {:class "wrap"}
     [:header [:h1 "日本語 · Japanese Learner"] [:p "Learn Japanese for JLPT N5 to N1: kana, kanji, vocabulary and grammar."]]
     [account-setup]]
    (let [view (:view @ui)
          due (count (due-items))
          total (counts-for (all-items))]
      [:div {:class "wrap"}
       [:header
        [:h1 "日本語 · Japanese Learner"]
        [:p (str "Account: " (current-name)
                 "  ·  Learned " (:learned total) " of " (+ (:new total) (:learning total) (:learned total))
                 "  ·  Due for review: " due)]
        [:button {:on-click #(swap! ui update :furigana? not)}
         (str "Furigana: " (if (:furigana? @ui) "On" "Off"))]]
       [:nav
        (for [[v label] [[:learn "Learn"] [:quiz "Quiz"]
                         [:review (str "Review" (when (pos? due) (str " (" due ")")))]
                         [:browse "Browse"] [:accounts "Accounts"]]]
          ^{:key v}
          [:button {:class (when (= view v) "active")
                    :on-click #(swap! ui assoc :view v :quiz nil)}
           label])]
       [:main
        (case view
          :learn [learn-view]
          :quiz [quiz-view :quiz "Quiz"
                 "Test the items in your current batch that are in Learning. Kana: pick the romaji. Kanji/vocabulary: pick the reading and the meaning. Grammar: pick the meaning. Furigana is hidden in quizzes. Passing moves an item to Learned."
                 (fn [] (filter #(= :learning (state-of (:id %))) (current-batch)))]
          :review [quiz-view :review "Review learned items"
                   "Learned items come back after 1, 2, 4, 8, 16, then 32 days (32 is the upper limit). Pass to go up a step; fail and the interval resets to 1 day, staying Learned."
                   (fn [] (if (seq (due-items)) (due-items)
                            (filter #(= :learned (state-of (:id %))) (all-items))))]
          :browse [browse-view]
          :accounts [accounts-view]
          [learn-view])]])))

;; React 19 (via Reagent 2.0.1) has no react-dom/render: use create-root.
(defonce app-root (atom nil))

(defn ^:export init []
  (let [root (or @app-root
                 (let [r (rdom-client/create-root
                           (.getElementById js/document "app"))]
                   (reset! app-root r)
                   r))]
    (rdom-client/render root [app])))
