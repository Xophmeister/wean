(ns wean-test)

(require '[babashka.fs :as fs]
         '[babashka.process :as p]
         '[clojure.test :as t]
         '[wean :as w])

;; Fixtures ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def minute (* 60 1000))
(def hour   (* 60 minute))
(def day    (* 24 hour))

; The pure layers take now as an argument, so any epoch will do.
(def now 1000000000000)
(defn ago [ms] (- now ms))

; Persistence is another matter: update-log! reaps and prunes on every
; transaction, so a log of 1970 timestamps deletes itself on first touch.
(defn recently [ms] (- (System/currentTimeMillis) ms))

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

(defn- session
  "The session with the given ID, wherever in the log it appears."
  [log id]

  (first (filter #(= id (:id %)) (mapcat val log))))

(defn- own-pid [] (.pid (java.lang.ProcessHandle/current)))

(defn- dead-pid
  "A PID that is no longer in use: run a trivial process to completion
  and take its PID. (Strictly this races with PID recycling, but not
  within the lifetime of a test run.)"
  []

  (let [proc (p/process ["true"])]
    @proc
    (.pid (:proc proc))))

; Each test gets its own state directory, so that they cannot interfere
; through the filesystem and none of them touch the real log.
(def ^:dynamic *dir* nil)
(defn- log-path [] (str (fs/path *dir* "log.edn")))

(defn- with-temp-dir [f]
  (let [dir (fs/create-temp-dir {:prefix "wean-test"})]
    (try
      (binding [*dir* dir] (f))
      (finally (fs/delete-tree dir)))))

(t/use-fixtures :each with-temp-dir)

;; Policy ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

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

;; Log ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(t/deftest reaping
  ; alive? is injected precisely so that this needs no real processes: a
  ; set of live PIDs is a perfectly good liveness predicate.
  (let [before {"claude"  [{:id :live  :pid 1 :start (ago hour)}
                           {:id :gone  :pid 2 :start (ago hour)}
                           {:id :ended :pid 3 :start (ago hour) :end (ago (* 30 minute))}]
                "copilot" [{:id :nopid :start (ago hour)}]}
        after  (w/reap before #{1})]

    (t/testing "a session whose process is alive is left running"
      (t/is (nil? (:end (session after :live)))))

    (t/testing "a session whose process is gone is closed at its own start"
      (t/is (= (ago hour) (:end (session after :gone)))))

    (t/testing "a session that already ended is untouched"
      (t/is (= (ago (* 30 minute)) (:end (session after :ended)))))

    (t/testing "a session with no PID at all is treated as gone"
      (t/is (some? (:end (session after :nopid)))))

    (t/testing "reaping is idempotent"
      (t/is (= after (w/reap after #{1}))))))

(t/deftest pruning
  (let [before {"claude"  [{:id :recent  :start (ago (* 2 hour)) :end (ago (* 30 minute))}
                           {:id :expired :start (ago (* 5 hour)) :end (ago (* 4 hour))}
                           {:id :old-run :start (ago (* 9 hour))}]
                "copilot" [{:id :also-expired :start (ago (* 5 hour)) :end (ago (* 4 hour))}]}
        after  (w/prune before now hour)]

    (t/testing "a session that ended inside the retention period is kept"
      (t/is (some? (session after :recent))))

    (t/testing "a session that ended before it is dropped"
      (t/is (nil? (session after :expired))))

    (t/testing "a running session is kept however old, since it has yet to end"
      (t/is (some? (session after :old-run))))

    (t/testing "every binary is pruned, not just the first"
      (t/is (nil? (session after :also-expired))))))

(t/deftest opening
  (t/testing "the first session for a binary creates a vector, not a list"
    (t/is (vector? (get (w/open {} "claude" {:id :a}) "claude"))))

  (t/testing "further sessions are appended in order"
    (t/is (= [{:id :a} {:id :b}]
             (get (-> {} (w/open "claude" {:id :a}) (w/open "claude" {:id :b})) "claude"))))

  (t/testing "other binaries are left alone"
    (t/is (= [{:id :c}]
             (get (w/open {"copilot" [{:id :c}]} "claude" {:id :a}) "copilot")))))

(t/deftest closing
  (let [before {"claude"  [{:id :a :start (ago hour)}
                           {:id :b :start (ago hour)}]
                "copilot" [{:id :c :start (ago hour)}]}]

    (t/testing "the matching session gains an :end"
      (t/is (= now (:end (session (w/close before :a now) :a)))))

    (t/testing "no other session is touched"
      (t/is (nil? (:end (session (w/close before :a now) :b)))))

    (t/testing "sessions are found under any binary"
      (t/is (= now (:end (session (w/close before :c now) :c)))))

    (t/testing "the first close wins, so a second cannot inflate the duration"
      (t/is (= now (:end (session (-> before (w/close :a now) (w/close :a (+ now hour))) :a)))))

    (t/testing "an unknown ID is ignored"
      (t/is (= before (w/close before :nonesuch now))))))

;; Persistence ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(t/deftest round-tripping
  (t/testing "a missing file reads as an empty log"
    (t/is (= {} (w/read-log (log-path)))))

  (t/testing "a log survives a write and read unchanged"
    (let [before {"claude" [{:id (random-uuid) :pid 1 :start (recently hour) :end (recently minute)}
                            {:id (random-uuid) :pid 2 :start (recently minute)}]}]
      (w/write-log! before (log-path))
      (t/is (= before (w/read-log (log-path))))))

  (t/testing "timestamps are stored as #inst, so the file stays legible"
    (w/write-log! {"claude" [{:id :a :pid 1 :start (recently hour)}]} (log-path))
    (t/is (re-find #"#inst" (slurp (log-path)))))

  (t/testing "a running session is not given an :end by the round trip"
    (w/write-log! {"claude" [{:id :a :pid 1 :start (recently hour)}]} (log-path))
    (t/is (not (contains? (session (w/read-log (log-path)) :a) :end))))

  (t/testing "writing leaves no temporary files behind"
    (w/write-log! {"claude" []} (log-path))
    (t/is (empty? (fs/glob *dir* "*.tmp")))))

(t/deftest transactions
  (t/testing "the function is applied, and the resulting log returned"
    (t/is (= {"claude" []} (w/update-log! (log-path) #(assoc % "claude" [])))))

  (t/testing "the result is what a subsequent read sees"
    (w/update-log! (log-path) #(assoc % "claude" []))
    (t/is (= {"claude" []} (w/read-log (log-path)))))

  (t/testing "the lock lives beside the log, not in it"
    (w/update-log! (log-path) identity)
    (t/is (fs/exists? (str (log-path) ".lock")))))

(t/deftest self-healing
  (t/testing "a session abandoned by a dead process is reaped in passing"
    (let [start (recently (* 10 minute))]
      (w/write-log! {"claude" [{:id :orphan :pid (dead-pid) :start start}]} (log-path))
      (t/is (= start (:end (session (w/update-log! (log-path) identity) :orphan))))))

  (t/testing "a session whose process still lives is left running"
    (w/write-log! {"claude" [{:id :live :pid (own-pid) :start (recently minute)}]} (log-path))
    (t/is (nil? (:end (session (w/update-log! (log-path) identity) :live)))))

  (t/testing "a session that ended beyond the retention period is pruned in passing"
    (w/write-log! {"claude" [{:id :ancient :pid 1
                              :start (recently (* 40 day)) :end (recently (* 31 day))}]}
                  (log-path))
    (t/is (nil? (session (w/update-log! (log-path) identity) :ancient)))))

(t/deftest session-lifecycle
  (let [started (recently (* 5 minute))
        id      (w/open-session! (log-path) "claude" started)]

    (t/testing "opening returns an ID that identifies a session in the log"
      (t/is (= started (:start (session (w/read-log (log-path)) id)))))

    (t/testing "the new session is running"
      (t/is (nil? (:end (session (w/read-log (log-path)) id)))))

    (t/testing "the PID recorded is wean's own, so that we can be reaped"
      (t/is (= (own-pid) (:pid (session (w/read-log (log-path)) id)))))

    (t/testing "closing ends the session"
      (w/close-session! (log-path) id (recently minute))
      (t/is (some? (:end (session (w/read-log (log-path)) id)))))

    (t/testing "closing twice does not move the end, as the shutdown hook may repeat it"
      (let [ended (:end (session (w/read-log (log-path)) id))]
        (w/close-session! (log-path) id (System/currentTimeMillis))
        (t/is (= ended (:end (session (w/read-log (log-path)) id))))))))

(t/deftest concurrency
  (t/testing "simultaneous transactions do not lose one another's updates"
    ; This is the only test that justifies the lock existing: the failure
    ; it guards against cannot be reached from a single process.
    (let [n     8
          code  (str "(require '[wean :as w]) "
                     "(w/open-session! \"" (log-path) "\" \"claude\" (System/currentTimeMillis))")
          procs (doall (repeatedly n #(p/process ["bb" "-e" code])))
          exits (mapv (comp :exit deref) procs)]

      (t/is (every? zero? exits) "every appending process exited cleanly")
      (t/is (= n (count (get (w/read-log (log-path)) "claude")))))))
