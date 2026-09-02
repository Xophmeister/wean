(ns wean-test)

(require '[babashka.fs :as fs]
         '[babashka.process :as p]
         '[clojure.edn :as edn]
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

(def ^:private bb
  "Babashka's own path, so that a subprocess can still be started under
  an environment that has no PATH to find it by."
  (str (fs/which "bb")))

(defn- bb-eval
  "Evaluate the given code in a fresh babashka process, under exactly
  the given environment.

  Resolution reads PATH and WEAN_BINARY from the environment, which
  cannot be changed in place, so these are the only tests that need a
  process of their own."
  [env code]

  (let [{:keys [exit out err]} @(p/process [bb "-e" code]
                                           {:env env :out :string :err :string})]
    {:exit exit :out out :err err}))

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

(t/deftest friction-schedule
  (t/testing "an unused window costs the base wait alone"
    (t/is (= 4 (w/friction {:count 0 :duration 0}))))

  (t/testing "each further session in the window doubles the wait"
    (t/is (= [5 7 11 19] (mapv #(w/friction {:count % :duration 0}) [1 2 3 4]))))

  (t/testing "the wait is integral, being both formatted and slept on"
    ; Math/pow returns a double, and (format "%2d" 5.0) throws
    (t/is (integer? (w/friction {:count 3 :duration 0}))))

  ; TODO Invert once friction takes time spent into account
  (t/testing "time spent is deliberately not yet taken into account"
    (t/is (= (w/friction {:count 1 :duration 0})
             (w/friction {:count 1 :duration (* 5 hour)})))))

;; Log ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(t/deftest reaping
  ; alive? is injected precisely so that this needs no real processes: a
  ; set of live PIDs is a perfectly good liveness predicate.
  (let [before {"claude"  [{:id :live  :pid 1 :start (ago hour)}
                           {:id :gone  :pid 2 :start (ago hour)}
                           {:id :beat  :pid 3 :start (ago hour) :seen (ago (* 10 minute))}
                           {:id :ended :pid 4 :start (ago hour) :end (ago (* 30 minute))}]
                "copilot" [{:id :nopid :start (ago hour)}]}
        after  (w/reap before #{1})]

    (t/testing "a session whose process is alive is left running"
      (t/is (nil? (:end (session after :live)))))

    (t/testing "a session whose process is gone is closed at its own start"
      (t/is (= (ago hour) (:end (session after :gone)))))

    (t/testing "a session with a heartbeat is charged up to its last beat"
      (t/is (= (ago (* 10 minute)) (:end (session after :beat)))))

    (t/testing "the heartbeat is dropped once an end is known, being redundant"
      (t/is (not (contains? (session after :beat) :seen))))

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

(t/deftest touching
  (let [before {"claude"  [{:id :a    :start (ago hour)}
                           {:id :b    :start (ago hour)}
                           {:id :done :start (ago hour) :end (ago (* 30 minute))}]
                "copilot" [{:id :c    :start (ago hour)}]}]

    (t/testing "the matching session gains a heartbeat"
      (t/is (= now (:seen (session (w/touch before :a now) :a)))))

    (t/testing "no other session is touched"
      (t/is (nil? (:seen (session (w/touch before :a now) :b)))))

    (t/testing "sessions are found under any binary"
      (t/is (= now (:seen (session (w/touch before :c now) :c)))))

    (t/testing "a later heartbeat replaces an earlier one"
      (t/is (= now (:seen (session (-> before
                                       (w/touch :a (ago minute))
                                       (w/touch :a now))
                                   :a)))))

    (t/testing "a session that has already ended gains no heartbeat"
      (t/is (nil? (:seen (session (w/touch before :done now) :done)))))

    (t/testing "an unknown ID is ignored"
      (t/is (= before (w/touch before :nonesuch now))))))

(t/deftest closing
  (let [before {"claude"  [{:id :a :start (ago hour) :seen (ago minute)}
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

    (t/testing "closing drops the heartbeat, which the :end supersedes"
      (t/is (not (contains? (session (w/close before :a now) :a) :seen))))

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

  (t/testing "a heartbeat is stored as an #inst too, not left as a raw count"
    (w/write-log! {"claude" [{:id :a :pid 1 :start (recently hour) :seen (recently minute)}]}
                  (log-path))
    (t/is (inst? (:seen (session (edn/read-string (slurp (log-path))) :a)))))

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

(t/deftest heartbeating
  (let [id   (w/open-session! (log-path) "claude" (recently (* 5 minute)))
        beat (recently (* 3 minute))]

    (t/testing "a heartbeat is recorded against the running session"
      (w/touch-session! (log-path) id beat)
      (t/is (= beat (:seen (session (w/read-log (log-path)) id)))))

    (t/testing "a heartbeat arriving after the close cannot reopen the session"
      (w/close-session! (log-path) id (recently minute))
      (let [closed (session (w/read-log (log-path)) id)]
        (w/touch-session! (log-path) id (System/currentTimeMillis))
        (t/is (= closed (session (w/read-log (log-path)) id)))))))

(t/deftest thread-safety
  (t/testing "simultaneous transactions in one process do not collide"
    ; The heartbeat runs on a thread of its own, so update-log! must
    ; tolerate two callers in the same process. A file lock belongs to
    ; the process rather than to the thread that took it, so without a
    ; monitor beneath it the second caller gets an
    ; OverlappingFileLockException rather than its turn.
    ; The path is resolved here rather than in the workers because
    ; binding is thread-local: a bare Thread sees *dir*'s root value,
    ; and fs/path quietly turns a nil parent into a relative path.
    (let [path    (log-path)
          threads 4
          each    25
          failed  (atom [])
          workers (doall (for [_ (range threads)]
                           (Thread.
                            (fn []
                              (dotimes [_ each]
                                (try (w/open-session! path "claude"
                                                      (System/currentTimeMillis))
                                     (catch Exception e (swap! failed conj (class e)))))))))]

      (run! #(.start %) workers)
      (run! #(.join %) workers)

      (t/is (empty? @failed) "every transaction succeeded")
      (t/is (= (* threads each) (count (get (w/read-log (log-path)) "claude")))))))

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

;; Resolution ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- executable
  "Create a trivial executable at the given path, standing in for the
  binary wean is meant to find."
  [path]

  (fs/create-dirs (fs/parent path))
  (spit (fs/file path) "#!/bin/sh\nexit 0\n")
  (fs/set-posix-file-permissions path "rwxr-xr-x")
  path)

(defn- shadowed
  "A PATH in which wean, symlinked as claude, shadows a real claude
  further along. Returns the directories in order, the symlink and the
  binary it ought to resolve to."
  []

  (let [wean (str (fs/real-path "wean.clj"))
        near (fs/path *dir* "near")
        far  (fs/path *dir* "far")
        link (str (fs/path near "claude"))
        real (str (fs/path far "claude"))]

    (fs/create-dirs near)
    (executable real)
    (fs/create-sym-link link wean)

    {:wean wean :near near :far far :link link :real real}))

(t/deftest discovery-by-name
  (let [{:keys [wean near far link real]} (shadowed)
        discovers (fn [path]
                    (let [{:keys [exit out err]}
                          (bb-eval {"PATH" (str path)}
                                   (str "(require '[wean :as w])"
                                        "(prn (some-> (#'w/discover \"" link "\") str))"))]

                      (t/is (zero? exit) err)
                      (edn/read-string out)))]

    (t/testing "the binary that wean's own symlink shadows is found"
      (t/is (= real (discovers (str near ":" far)))))

    (t/testing "wean is skipped however often it appears"
      (let [also (fs/path *dir* "also")]
        (fs/create-dirs also)
        (fs/create-sym-link (fs/path also "claude") wean)
        (t/is (= real (discovers (str near ":" also ":" far))))))

    (t/testing "nothing is found when wean is the only candidate"
      (t/is (nil? (discovers near))))))

(t/deftest target-resolution
  (let [{:keys [near far link real]} (shadowed)
        code (str "(require '[wean :as w])"
                  "(prn (#'w/target \"" link "\"))")]

    (t/testing "discovery supplies the binary when nothing else does"
      (t/is (= real (edn/read-string (:out (bb-eval {"PATH" (str near ":" far)} code))))))

    (t/testing "WEAN_BINARY takes precedence over anything on PATH"
      (t/is (= "/elsewhere/claude"
               (edn/read-string (:out (bb-eval {"PATH"        (str near ":" far)
                                                "WEAN_BINARY" "/elsewhere/claude"}
                                               code))))))

    (t/testing "failing to resolve exits non-zero rather than running nothing"
      ; The empty string is truthy in Clojure, so an empty result must
      ; not be allowed to pass for an answer.
      (let [{:keys [exit err]} (bb-eval {"PATH" (str near)} code)]
        (t/is (= 1 exit))
        (t/is (re-find #"claude" err))))))
