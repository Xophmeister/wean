(require '[clojure.test :as t]
         '[wean :as w])

;; Fixtures
(def minute (* 60 1000))
(def hour   (* 60 minute))
(def now    1000000000000)
(defn ago [ms] (- now ms))

(def log
  {"claude"
   [{:id :inside   :start (ago (* 50 minute)) :end (ago (* 40 minute))}  ; 10m, wholly inside
    {:id :straddle :start (ago (* 90 minute)) :end (ago (* 30 minute))}  ; 60m long, 30m inside
    {:id :running  :start (ago (* 20 minute))}                           ; 20m so far
    {:id :long-run :start (ago (* 3 hour))}                              ; running 3h, 60m inside
    {:id :ancient  :start (ago (* 5 hour))    :end (ago (* 4 hour))}]    ; wholly outside

   "copilot"
   [{:id :other    :start (ago (* 10 minute)) :end (ago (* 5 minute))}]})

(defn ids [] (set (map :id (w/sessions-in-window log "claude" now hour))))
(defn u   [] (w/usage (w/sessions-in-window log "claude" now hour) now hour))

;; Tests
(t/deftest window-selection
  (t/testing "other binaries are not considered"
    (t/is (not (contains? (ids) :other))))

  (t/testing "sessions wholly outside the window are dropped"
    (t/is (not (contains? (ids) :ancient))))

  (t/testing "sessions overlapping the window are kept, however they started"
    (t/is (= #{:inside :straddle :running :long-run} (ids)))))

(t/deftest count-predicate
  (t/testing "counts only sessions launched inside the window"
    (t/is (= 2 (:count (u))))))

(t/deftest duration-predicate
  (t/testing "sums time spent inside the window, clamped at both ends"
    (t/is (= (* 120 minute) (:duration (u)))))

  (t/testing "a session running since before the window contributes the whole window"
    (t/is (= (* 60 minute)
             (:duration (w/usage [{:start (ago (* 3 hour))}] now hour)))))

  (t/testing "a session ending inside contributes only its tail"
    (t/is (= (* 30 minute)
             (:duration (w/usage [{:start (ago (* 90 minute)) :end (ago (* 30 minute))}] now hour))))))

(t/deftest degenerate-cases
  (t/testing "empty log"
    (t/is (= {:count 0 :duration 0} (w/usage (w/sessions-in-window {} "claude" now hour) now hour))))

  (t/testing "a clock-skewed session starting in the future never goes negative"
    (t/is (= {:count 1 :duration 0} (w/usage [{:start (+ now (* 5 minute))}] now hour)))))

;; Entrypoint
(let [{:keys [fail error]} (t/run-tests 'user)]
  (System/exit (if (pos? (+ fail error)) 1 0)))
