#!/usr/bin/env bb

; TODO Namespace-level docstring

;; Log format ($XDG_STATE_HOME/wean/log.edn):
;; ```clojure
;; {"<BINARY>"
;;  [{:id <UUID> :pid <WEAN PID> :start <INST>
;;                               :end <INST>   ; optional
;;                               :seen <INST>} ; heatbeat of running session(s)
;;   ...]
;;  ...}
;; ```

(ns wean)

(require '[babashka.fs :as fs]
         '[babashka.process :as p]
         '[clojure.edn :as edn]
         '[clojure.pprint :as pp])

;; Constants ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

; TODO Make these configurable
(def ^:const default-retention  (* 30 24 60 60 1000)) ; 30 day
(def ^:const default-window     (* 24 60 60 1000))    ; 24 hours
(def ^:const heartbeat-interval 60000)                ; 1 minute

;; Policy ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
; What can be gleaned from the log: the sessions that bear on a decision,
; what they add up to and the friction they incur.

(defn sessions-in-window
  "Returns all sessions for the given binary that overlap the window of
  the given width, ending now. Deliberately a superset: a session that
  began before the window may still have run inside it and one with no
  :end is still running."
  [log binary now window]

  (let [window-start (- now window)]
    (->> (get log binary)
         (filter #(> (or (:end %) now) window-start))
         vec)))

(defn usage
  "Counts sessions started within the window and sums the time actually
  spent inside it. The two use different predicates on purpose: a
  session that began before the window isn't a launch within it, but
  whatever part of it fell inside the window is still time spent."
  [sessions now window]

  (let [window-start (- now window)]
    (reduce (fn [{:keys [count duration]} {:keys [start end]}]
              (let [end (or end now)
                    overlap (max 0 (- (min end now)
                                      (max start window-start)))]

                {:count (if (>= start window-start) (inc count) count)
                 :duration (+ duration overlap)}))

            {:count 0 :duration 0}
            sessions)))

(defn friction
  "Calculate the wait time based on the window usage."
  ; TODO Take :duration into account too
  [{:keys [count]}]

  ; Legacy formula: 3 + 2^count
  (long (+ 3 (Math/pow 2 count))))

;; Log ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
; Pure operations over the whole log. Anything needing the outside world
; is injected, so that these stay testable against fixtures.

(defn- map-sessions [f log] (update-vals log #(mapv f %)))
(defn- filter-sessions [pred log] (update-vals log #(filterv pred %)))

(defn- ended
  "Mark a session as having ended at the given time. The heartbeat is
  dropped: once :end is known, :seen is redundant."
  [session now]

  (-> session (assoc :end now) (dissoc :seen)))

(defn reap
  "Reap sessions -- that is, mark as ended any whose process is no longer
  alive -- wherever they appear in the log.

  An abandoned session's true end time is unknowable, so it is charged up
  to the last moment there is evidence it was alive: its most recent
  heartbeat, or its start if it never lived long enough to record one."
  [log alive?]

  (map-sessions (fn [session]
                  (cond-> session
                    (and (not (:end session)) (not (alive? (:pid session))))
                    (ended (or (:seen session) (:start session)))))

                log))

(defn prune
  "Prune sessions that ended after the given retention period, ignoring
  any that are still running."
  [log now retention]

  (filter-sessions #(or (nil? (:end %))
                        (> (:end %) (- now retention)))

                   log))

(defn open
  "Add a session to the log, under the given binary."
  [log binary session]

  (update log binary (fnil conj []) session))

(defn- alter-session
  "Apply f to the open session with the given ID, wherever in the log it
  appears. Sessions that have already ended are left alone."
  [log id f]

  (map-sessions #(cond-> %
                   (and (= id (:id %)) (nil? (:end %)))
                   f)

                log))

(defn touch
  "Record that the session with the given ID was still running at the
  given time, so that a reap can charge it up to its last heartbeat
  rather than guessing."
  [log id now]

  (alter-session log id #(assoc % :seen now)))

(defn close
  "Mark the session with the given ID as having ended now, wherever in
  the log it appears. Sessions are identified by UUID, so the binary need
  not be known.

  The first close wins: a session that has already ended is left alone
  and an ID that is no longer in the log is ignored."
  [log id now]

  (alter-session log id #(ended % now)))

;; Persistence ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:private timestamps [:start :end :seen])

(defn- convert-session [f session]
  (reduce (fn [session key] (cond-> session (session key) (update key f)))
          session
          timestamps))

(defn- convert [f log]
  (map-sessions (partial convert-session f) log))

; Timestamps are milliseconds in memory but #inst on disk, so that the
; log stays legible. These are the only two places that bridge the two.
(def ^:private decode (partial convert java.util.Date/.getTime))
(def ^:private encode (partial convert java.util.Date/new))

(defn- pid-alive?
  "Whether the process with the given PID is still running. PIDs are
  recycled, so a false positive is possible; that leaves a dead session
  open and thus errs towards more friction, which is the safe direction."
  [pid]

  (when pid
    (let [handle (java.lang.ProcessHandle/of pid)]
      (and (.isPresent handle) (.isAlive (.get handle))))))

(defn read-log
  "Read the log from the given file, returning an empty log if it doesn't
  exist."
  [path]

  (if (fs/exists? path)
    (decode (edn/read-string (fs/slurp path)))
    {}))

(defn write-log!
  "Encode the log and atomically write it to the given file.

  NOTE The existence of the parent directory is the caller's
  responsibility."
  [log path]

  (let [tmp (fs/create-temp-file {:dir (fs/parent path)
                                  :prefix "log"
                                  :suffix ".tmp"})
        data (with-out-str (pp/pprint (encode log)))]

    (fs/spit tmp data)
    (fs/move tmp path {:replace-existing true
                       :atomic-move true})))

(def ^:private monitor (Object.))

(defn with-lock*
  "Acquire an exclusive lock on the given file, then apply f to no
  arguments.

  The lock belongs to the open file descriptor rather than to the file
  itself, so it is released whenever the channel closes -- including when
  the process dies without unwinding. There is therefore no stale lock to
  recover from. The lock file is left on disk deliberately: it must name
  a stable inode that nothing ever renames, which is exactly why it
  cannot be the log file.

  A lock is held by the process rather than by the thread that took it,
  so a second thread asking for an overlapping region gets an
  OverlappingFileLockException rather than waiting its turn. The monitor
  therefore serialises threads, before the file lock serialises
  processes."
  [lockfile f]

  (locking monitor
    (with-open [raf (java.io.RandomAccessFile. (fs/file lockfile) "rw")
                ch  (.getChannel raf)]

      ; The returned FileLock is discarded: Babashka's reflection
      ; allowlist blocks its .release, .close and .isValid methods and
      ; closing the channel releases the lock in any case.
      (.lock ch)
      (f))))

(defmacro with-lock
  "Evaluate the body under an exclusive lock on the given file."
  [lockfile & body]

  `(with-lock* ~lockfile (fn [] ~@body)))

(defn update-log!
  "Apply f to the log held in the given file, under an exclusive lock,
  writing the result back and returning it. Orphaned sessions are reaped
  and expired ones pruned on the way through, so that the log self-heals
  on every transaction."
  [path f]

  (with-lock (str path ".lock")
    (let [log (-> (read-log path)
                  (reap pid-alive?)
                  (prune (System/currentTimeMillis) default-retention)
                  f)]

      (write-log! log path)
      log)))

(defn open-session!
  "Record the start of a session for the given binary in the log held in
  the given file, returning the new session's ID so that it may be closed
  later.

  The PID recorded is wean's own, not the agent's: wean supervises the
  session, so it is wean's death that leaves one unclosed, and its PID
  that tells a later transaction whether the session was abandoned.

  NOTE Call this only once the nag has elapsed, so that a countdown the
  user abandons leaves no trace."
  [path binary now]

  (let [id (random-uuid)]
    (update-log! path #(open % binary {:id    id
                                       :pid   (.pid (java.lang.ProcessHandle/current))
                                       :start now}))
    id))

(defn touch-session!
  "Record that the session with the given ID is still running, in the log
  held in the given file. Sessions that have already ended are ignored,
  so a heartbeat arriving after the close cannot reopen one."
  [path id now]

  (update-log! path #(touch % id now)))

(defn close-session!
  "Record the end of the session with the given ID in the log held in the
  given file.

  Safe to call more than once, so the ordinary exit path and a shutdown
  hook may both invoke it without the second inflating the recorded
  duration. A session that is no longer in the log is ignored."
  [path id now]

  (update-log! path #(close % id now)))

(defn start-heartbeat!
  "Periodically record that the session with the given ID is still
  running, so that a reap can charge it up to its last heartbeat rather
  than guessing.

  A daemon thread, so it can never hold wean open past the session it is
  tracking, and silent: wean's stderr is the agent's terminal, so a
  stack trace here would land in the middle of the agent's display."
  [path id]

  (doto (Thread. (fn []
                   (loop []
                     (Thread/sleep heartbeat-interval)
                     (try (touch-session! path id (System/currentTimeMillis))
                          (catch Exception _ nil))
                     (recur))))

    (.setDaemon true)
    (.start)))

;; Signal handling policy ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:private absorb
  "A handler that does nothing, so the signal is neither acted upon nor
  passed to the default disposition."
  (reify sun.misc.SignalHandler (handle [_ _] nil)))

(defn- signals!
  "Set the disposition of each named signal."
  [dispositions]

  (doseq [[signal handler] dispositions]
    (sun.misc.Signal/handle (sun.misc.Signal. signal) handler)))

; Absorbed rather than ignored: SIG_IGN survives exec, so an ignored
; signal would leave the agent itself unable to receive it and, under
; Babashka, SIGINT cannot be restored once ignored. A caught signal is
; reset to its default across exec, so the agent starts clean and no
; restoration step is needed.
(def ^:private nag-signals
  {"INT" absorb "QUIT" absorb "TSTP" absorb})

; SIGTSTP alone changes hands. During the nag, it must not suspend the
; countdown; while supervising, it must work, because it reaches the
; whole foreground process group: were the agent to stop and wean not,
; the shell would still be waiting on wean and the terminal would sit
; with no prompt. SIGINT and SIGQUIT stay absorbed throughout: they are
; meant for the agent, which hanles them itself, and wean dying would
; orphan it and return a prompt while it still held the terminal.
(def ^:private supervise-signals
  {"TSTP" sun.misc.SignalHandler/SIG_DFL})

;; Nag UI ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:private colours
  "ANSI attributes, empty when stdout is not a terminal so that
  redirected output stays clean."
  (when (some? (System/console))
    {:bold      "\033[1m"
     :dim       "\033[2m"
     :red       "\033[31m"
     :reset     "\033[0m"}))

(defn- colour [attribute] (get colours attribute ""))

(defn- countdown
  "Count down the given number of seconds, rewriting a single line in
  place."
  [seconds]

  (doseq [remaining (range seconds 0 -1)]
    (print (format "\r\033[K%sPaused: %2d s remaining...%s"
                   (colour :dim) remaining (colour :reset)))
    (flush)
    (Thread/sleep 1000))

  (print "\r\033[K")
  (flush))

(defn nag
  "Scold the user, then pause for the given number of seconds."
  [seconds]

  (println (str (colour :bold) (colour :red)
                "Do not overuse this! Use your brain, instead!"
                (colour :reset)))

  (countdown seconds))

;; Process handling ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- spawn
  "Run the given binary with the given arguments, inheriting wean's
  streams so that the agent has the terminal.

  Where setpriv is available, the agent is given a parent-death signal,
  so that killing wean outright takes the agent with it rather than
  leaving it orphaned. SIGKILL cannot be caught, so this is the only way
  to cover that case; it is a Linux-specific facility, so elsewhere, the
  shutdown hook is the only safeguard."
  [binary args]

  (p/process (cond->> (cons binary args)
               (fs/which "setpriv") (concat ["setpriv" "--pdeathsig" "KILL" "--"]))

             {:inherit true}))

(defn- target
  "The binary wean is supervising, as an absolute path."
  []

  (or (System/getenv "WEAN_BINARY")

      ; TODO Fallback to discovery by invoked name
      (do (binding [*out* *err*] (println "WEAN_BINARY not set!"))
          (System/exit 1))))

;; Entrypoint ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:private log-file
  (fs/path (fs/xdg-state-home "wean") "log.edn"))

(defn -main [& args]
  (let [path   (str log-file)
        binary (target)
        name   (fs/file-name binary)]

    (fs/create-dirs (fs/parent path))
    (signals! nag-signals)

    (let [now (System/currentTimeMillis)]
      (-> (read-log path)
          (sessions-in-window name now default-window)
          (usage now default-window)
          friction
          nag))

    (signals! supervise-signals)

    (let [id   (open-session! path name (System/currentTimeMillis))
          proc (spawn binary args)]

      (start-heartbeat! path id)

      (.addShutdownHook (Runtime/getRuntime)
                        (Thread. (fn []
                                   (p/destroy-tree proc)
                                   (close-session! path id (System/currentTimeMillis)))))

      (let [exit (:exit @proc)]
        (close-session! path id (System/currentTimeMillis))
        (System/exit exit)))))

(when (= *file* (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
