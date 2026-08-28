#!/usr/bin/env bb

; TODO Namespace-level docstring

;; Log format ($XDG_STATE_HOME/wean/log.edn):
;; ```clojure
;; {"<BINARY>"
;;  [{:id <UUID> :pid <WEAN PID> :start <INST>
;;                               :end <INST> ; optional}
;;   ...]
;;  ...}
;; ```

(ns wean)

(require '[babashka.fs :as fs]
         '[clojure.edn :as edn]
         '[clojure.pprint :as pp])

;; Constants ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

; TODO Make the retention period configurable
(def ^:const default-retention (* 30 24 60 60 1000)) ; 30 days, in milliseconds

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
  [{:keys [count duration]}]

  ; Legacy formula: 3 + 2^count
  (+ 3 (Math/pow 2 count)))

;; Log ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
; Pure operations over the whole log. Anything needing the outside world
; is injected, so that these stay testable against fixtures.

(defn- map-sessions [f log] (update-vals log #(mapv f %)))
(defn- filter-sessions [pred log] (update-vals log #(filterv pred %)))

(defn reap
  "Reap sessions -- that is, mark them as ended with zero duration -- for
  any processes that are no longer alive that haven't already been marked
  as ended."
  [log alive?]

  (map-sessions (fn [session]
                  (cond-> session
                    (and (not (:end session)) (not (alive? (:pid session))))
                    (assoc :end (:start session))))

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

(defn close
  "Mark the session with the given ID as having ended now, wherever in
  the log it appears. Sessions are identified by UUID, so the binary need
  not be known.

  The first close wins: a session that has already ended is left alone
  and an ID that is no longer in the log is ignored."
  [log id now]

  (map-sessions #(cond-> %
                   (and (= id (:id %)) (nil? (:end %)))
                   (assoc :end now))

                log))

;; Persistence ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- convert-session [f session]
  (cond-> session
    (:start session) (update :start f)
    (:end   session) (update :end   f)))

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

(defn with-lock*
  "Acquire an exclusive lock on the given file, then apply f to no
  arguments.

  The lock belongs to the open file descriptor rather than to the file
  itself, so it is released whenever the channel closes -- including when
  the process dies without unwinding. There is therefore no stale lock to
  recover from. The lock file is left on disk deliberately: it must name
  a stable inode that nothing ever renames, which is exactly why it
  cannot be the log file."
  [lockfile f]

  (with-open [raf (java.io.RandomAccessFile. (fs/file lockfile) "rw")
              ch  (.getChannel raf)]

    ; The returned FileLock is discarded: Babashka's reflection allowlist
    ; blocks its .release, .close and .isValid methods and closing the
    ; channel releases the lock in any case.
    (.lock ch)
    (f)))

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

(defn close-session!
  "Record the end of the session with the given ID in the log held in the
  given file.

  Safe to call more than once, so the ordinary exit path and a shutdown
  hook may both invoke it without the second inflating the recorded
  duration. A session that is no longer in the log is ignored."
  [path id now]

  (update-log! path #(close % id now)))

;; Entrypoint ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn -main [& _args]
  (println "Hello, World!"))

(when (= *file* (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
