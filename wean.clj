#!/usr/bin/env bb

;; wean: Seize the means of production from our agentic overlords ;;;;;;
; Copyright (C) 2026 Christopher Harrison

; Log format ($XDG_STATE_HOME/wean/log.edn):
; ```edn
; {"<BINARY>"
;  [{:id    <UUID>      ; unique session ID
;    :pid   <WEAN PID>  ; the process wean is supervising
;    :start <INST>      ; when the session began
;    :end   <INST>      ; when the session ended (optional)
;    :seen  <INST>}     ; last heartbeat of a running session
;   ...]
;  ...}
; ```

; This program is free software: you can redistribute it and/or modify
; it under the terms of the GNU General Public License as published by
; the Free Software Foundation, either version 3 of the License, or (at
; your option) any later version.
;
; This program is distributed in the hope that it will be useful, but
; WITHOUT ANY WARRANTY; without even the implied warranty of
; MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
; General Public License for more details.
;
; You should have received a copy of the GNU General Public License
; along with this program. If not, see <https://www.gnu.org/licenses/>.

(ns wean
  "Wrap an agentic coding tool in a start-up delay that grows with how
  much it has lately been leant on, counting both how often it was
  launched and how long it was left running.

  wean supervises the tool rather than exec'ing it -- Babashka has no
  exec -- so it stays in the process tree for the whole session and can
  therefore record when that session began and ended.")

(require '[babashka.fs :as fs]
         '[babashka.process :as p]
         '[clojure.edn :as edn]
         '[clojure.pprint :as pp]
         '[clojure.string :as str])

(defn- die
  "Complain on stderr and give up."
  [& lines]

  (binding [*out* *err*] (run! println lines))
  (System/exit 1))

;; Configuration ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
; Every setting wean has and the reading and vetting of the wean.edn
; that may turn them. Pure and impure are kept together here, because a
; setting and the checking of it belong side by side.

(def ^:const defaults
  "Every setting wean has, at its factory value.

  Spans of history are milliseconds and a configuration may give them
  as either a bare number of those or an [n unit] pair. Waits are plain
  seconds throughout, being what one actually sits through."
  {:window             (* 7 24 60 60 1000)   ; The decay's mean lifetime
   :retention          (* 30 24 60 60 1000)  ; How long a session is kept
   :heartbeat          (* 60 1000)           ; Between proofs of life
   :session-equivalent (* 30 60 1000)        ; Runtime worth one launch
   :max-friction       1200                  ; The longest possible wait
   :anchors            [[10 10] [50 120]]    ; See below
   :log                nil})                 ; Defaults to the XDG path

; The friction curve is pinned by two opinions rather than by its own
; parameters: [score seconds] pairs saying what a light week and a heavy
; one ought to cost. Its steepness and midpoint fall out of them.

(def ^:const spans
  "The settings measured in milliseconds."
  [:window :retention :heartbeat :session-equivalent])

(def ^:const units
  "What a span may be written in, in milliseconds apiece."
  {:ms 1 :seconds 1000 :minutes 60000 :hours 3600000 :days 86400000})

(defn span
  "A span in milliseconds, from either a bare number of them or an
  [n unit] pair; nil from anything else, which the vetting reports."
  [value]

  (cond
    (number? value) value

    (and (vector? value)
         (= 2 (count value))
         (number? (first value))
         (contains? units (second value)))
    (* (first value) (units (second value)))))

(defn problems
  "Everything wrong with a configuration, as a list of complaints; empty
  means it is fit to use. Every fault is reported at once, so that
  correcting a file is not a guessing game one error at a time."
  [{:keys [max-friction anchors log] :as config}]

  (let [ceiling (when (and (number? max-friction) (pos? max-friction))
                  max-friction)]

    (concat
     (for [k (remove (set (keys defaults)) (keys config))]
       (str k " is not a setting wean has"))

     (for [k spans
           :when (not (pos? (or (span (get config k)) 0)))]
       (str k " must be a positive span: milliseconds, or [n unit] with"
            " unit one of " (str/join ", " (sort (map name (keys units))))))

     (when-not ceiling
       [":max-friction must be a positive number of seconds"])

     (if-not (and (vector? anchors)
                  (= 2 (count anchors))
                  (every? #(and (vector? %) (= 2 (count %)) (every? number? %))
                          anchors))
       [":anchors must be two [score seconds] pairs"]

       (let [[[u1 f1] [u2 f2]] anchors]
         (concat
          (when-not (< u1 u2)
            [":anchors must be given in ascending order of score"])

          ; The logit is finite only strictly inside (0, max-friction).
          ; At the ceiling exactly it divides by zero and throws; at
          ; nothing, or beyond the ceiling, it gives NaN, which rounds
          ; to a wait of nothing at all. Failing wide open, in silence,
          ; is the one direction wean must not fail in.
          (when (and ceiling (not (< 0 f1 f2 ceiling)))
            [(str ":anchors must rise, cost more than nothing and stay"
                  " under :max-friction (" ceiling " s)")]))))

     (when-not (or (nil? log) (string? log))
       [":log must be a path, given as a string"]))))

(defn configure
  "wean's defaults, overlaid by the given configurations in ascending
  order of precedence."
  [configs]

  (apply merge defaults (reverse configs)))

(defn resolved
  "A configuration with every span reduced to milliseconds, so that
  nothing downstream need care how it was written."
  [config]

  (reduce #(update %1 %2 span) config spans))

(defn config-files
  "Where a wean.edn may live, in descending order of precedence: the
  user's own config home first, then each entry of the given
  XDG_CONFIG_DIRS, so that a system-wide file may set a policy its users
  can still overrule."
  [config-home config-dirs]

  (cons (fs/path config-home "wean.edn")
        (for [dir (str/split (or config-dirs "/etc/xdg") #":")
              :when (seq dir)]
          (fs/path dir "wean.edn"))))

(defn- log-file
  "Where the log lives: as configured, or else the XDG state path."
  [config]

  (str (or (:log config) (fs/path (fs/xdg-state-home "wean") "log.edn"))))

(defn- configure!
  "The effective configuration -- the defaults, overlaid by every
  wean.edn on the search path, with its spans reduced and its log path
  settled -- or death listing everything wrong with it."
  []

  (let [read   (fn [path]
                 (try (edn/read-string (slurp (fs/file path)))
                      (catch Exception e
                        (die (str path " is not readable EDN: "
                                  (ex-message e))))))

        config (configure (mapv read
                                (filter fs/exists?
                                        (config-files (fs/xdg-config-home)
                                                      (System/getenv "XDG_CONFIG_DIRS")))))]

    (when-let [faults (seq (problems config))]
      (apply die "wean cannot use its configuration:"
             (map #(str "  " %) faults)))

    (let [effective (resolved config)]
      (assoc effective :log (log-file effective)))))

;; Policy ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
; What can be gleaned from the log: the sessions that bear on a decision,
; what they add up to and the friction they incur.

(defn sessions-for
  "Returns all sessions for the given binary."
  [log binary]

  (into [] (get log binary)))

(defn usage
  "Weighs a binary's sessions by age, so that recent use counts for more
  than old. Weight decays exponentially with a mean lifetime of one
  window -- a half-life of window * ln 2, or a little under five days
  at a week -- so nothing is excluded outright and there is no cliff to
  sit out.

  The two terms are weighed differently on purpose: a launch is an
  instant and takes the weight of its moment, whereas time spent is a
  span and is integrated across the session, discounting its older part
  against its newer. Both are therefore fractional and time spent
  remains in milliseconds. A session left running converges on one
  window's worth, which is what bounds the cost of never closing one."
  [sessions now window]

  (let [weight (fn [t] (Math/exp (/ (- (min t now) now) window)))]
    (reduce (fn [{:keys [count duration]} {:keys [start end]}]
              (let [w-start (weight start)
                    w-end   (weight (max start (or end now)))]

                {:count    (+ count w-start)
                 :duration (+ duration (* window (- w-end w-start)))}))

            {:count 0.0 :duration 0.0}
            sessions)))

(defn score
  "Usage as a single figure, trading time spent against launches: a
  session running for one session-equivalent counts for as much as
  starting another. Both terms arrive already decayed by age."
  [{:keys [session-equivalent]} {:keys [count duration]}]

  (+ count (/ duration session-equivalent)))

(defn curve
  "Steepness and midpoint, solved for from the anchors. Inverting the
  sigmoid gives ln(f / (max - f)) at each; its gradient in score is the
  steepness and the score at which it vanishes is the midpoint."
  [{:keys [max-friction anchors]}]

  (let [[[u1 f1] [u2 f2]] anchors
        logit (fn [f] (Math/log (/ f (- max-friction f))))
        k     (/ (- (logit f2) (logit f1)) (- u2 u1))]

    {:steepness k
     :midpoint  (- u1 (/ (logit f1) k))}))

(defn friction
  "The wait a given usage has earned, in seconds. A logistic in the
  score: mild while usage is ordinary, steep once it is not and
  levelling off at max-friction so that the tool stays worth obeying
  rather than worth deleting."
  [config usage]

  (let [{:keys [steepness midpoint]} (curve config)]
    ; Rounded rather than truncated: the anchors are transcendental
    ; round-trips that land a hair below their own target and taking the
    ; floor would miss every one of them by a second.
    (Math/round (/ (double (:max-friction config))
                   (+ 1 (Math/exp (- (* steepness (- (score config usage)
                                                     midpoint)))))))))

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
    (decode (edn/read-string (slurp (fs/file path))))
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

    (spit (fs/file tmp) data)
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
  "Apply f to the configured log, under an exclusive lock, writing the
  result back and returning it. Orphaned sessions are reaped and expired
  ones pruned on the way through, so that the log self-heals on every
  transaction."
  [{:keys [log retention]} f]

  (with-lock (str log ".lock")
    (let [updated (-> (read-log log)
                      (reap pid-alive?)
                      (prune (System/currentTimeMillis) retention)
                      f)]

      (write-log! updated log)
      updated)))

(defn open-session!
  "Record the start of a session for the given binary in the log held in
  the given file, returning the new session's ID so that it may be closed
  later.

  The PID recorded is wean's own, not the agent's: wean supervises the
  session, so it is wean's death that leaves one unclosed and its PID
  that tells a later transaction whether the session was abandoned.

  NOTE Call this only once the nag has elapsed, so that a countdown the
  user abandons leaves no trace."
  [config binary now]

  (let [id (random-uuid)]
    (update-log! config #(open % binary {:id    id
                                         :pid   (.pid (java.lang.ProcessHandle/current))
                                         :start now}))
    id))

(defn touch-session!
  "Record that the session with the given ID is still running. Sessions
  that have already ended are ignored, so a heartbeat arriving after the
  close cannot reopen one."
  [config id now]

  (update-log! config #(touch % id now)))

(defn close-session!
  "Record the end of the session with the given ID.

  Safe to call more than once, so the ordinary exit path and a shutdown
  hook may both invoke it without the second inflating the recorded
  duration. A session that is no longer in the log is ignored."
  [config id now]

  (update-log! config #(close % id now)))

(defn start-heartbeat!
  "Periodically record that the session with the given ID is still
  running, so that a reap can charge it up to its last heartbeat rather
  than guessing.

  A daemon thread, so it can never hold wean open past the session it is
  tracking and silent: wean's stderr is the agent's terminal, so a stack
  trace here would land in the middle of the agent's display."
  [config id]

  (doto (Thread. (fn []
                   (loop []
                     (Thread/sleep (:heartbeat config))
                     (try (touch-session! config id (System/currentTimeMillis))
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

; SIGINT is conspicuously absent here. During the nag no session has
; been recorded and no agent yet exists, so dying on it costs nothing
; and orphans nobody: Ctrl+C abandons the launch, as it means anywhere
; else. Nor can it be used to duck the wait, there being no agent on
; the far side of it -- so absorbing it would only trap somebody who
; had changed their mind, which is not a habit worth discouraging.
(def ^:private nag-signals
  {"QUIT" absorb "TSTP" absorb})

; Both dispositions that change do so here, in opposite directions.
; SIGINT must now be absorbed: it reaches the whole foreground process
; group, so the agent receives it and answers it itself, whereas wean
; dying on it would orphan the agent and return a prompt while it still
; held the terminal. SIGTSTP must now work, for the mirror image of
; that reason: were the agent to stop and wean not, the shell would
; still be waiting on wean and the terminal would sit with no prompt.
; SIGQUIT stays absorbed throughout, being the agent's to answer too.
(def ^:private supervise-signals
  {"INT"  absorb
   "TSTP" sun.misc.SignalHandler/SIG_DFL})

;; Nag UI ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:private terminal?
  "Whether wean has a terminal to draw on. Without one, colour and cursor
  control alike are just noise in somebody's log file."
  (some? (System/console)))

(def ^:private colours
  "ANSI attributes, empty when there is no terminal so that redirected
  output stays clean."
  (when terminal?
    {:bold      "\033[1m"
     :dim       "\033[2m"
     :red       "\033[31m"
     :reset     "\033[0m"}))

(defn- colour [attribute] (get colours attribute ""))

(defn spoken
  "A span of milliseconds, in whichever units read most naturally."
  [ms]

  (let [seconds (long (/ ms 1000))
        hours   (quot seconds 3600)
        minutes (rem (quot seconds 60) 60)]

    (cond
      (pos? hours)   (format "%dh %dm" hours minutes)
      (pos? minutes) (format "%dm" minutes)
      :else          (format "%ds" (rem seconds 60)))))

(defn summary
  "What the wait was earned with, in a line.

  The figures are decayed by age, so they are what wean is weighing
  rather than a raw tally: a fortnight-old session is in there, but
  barely. Worth saying at all because the cost of leaving a session open
  is charged the next time round and a penalty nobody can connect to
  what caused it teaches nothing."
  [config usage]

  (let [launches (Math/round (double (:count usage)))]
    (format "Lately: %d %s, %s running, for a score of %d."
            launches
            (if (= 1 launches) "launch" "launches")
            (spoken (:duration usage))
            (Math/round (double (score config usage))))))

(defn- countdown
  "Count down the given number of seconds, rewriting a single line in
  place. Without a terminal the wait still happens, in silence: cursor
  control smeared through a redirected log helps nobody."
  ([seconds] (countdown seconds terminal?))

  ([seconds draw?]
   (if-not draw?
     (Thread/sleep (* 1000 seconds))

     (do (doseq [remaining (range seconds 0 -1)]
           (print (format "\r\033[K%sPaused: %d s remaining...%s"
                          (colour :dim) remaining (colour :reset)))
           (flush)
           (Thread/sleep 1000))

         (print "\r\033[K")
         (flush)))))

(defn nag
  "Scold the user, account for why, then pause for the given number of
  seconds."
  [config usage seconds]

  (println (str (colour :bold) (colour :red)
                "Do not overuse this! Use your brain, instead!"
                (colour :reset)))

  (println (str (colour :dim) (summary config usage) (colour :reset)))

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

(defn- discover
  "The first binary in the PATH under the name wean was invoked as,
  other than wean itself."
  [me]

  (let [self (fs/real-path me)]
    (->> (fs/which-all (fs/file-name me))
         (remove #(= self (fs/real-path %)))
         first)))

(defn- target
  "The binary wean is supervising, as an absolute path."
  [me]

  (or (System/getenv "WEAN_BINARY")  ; Env var, mostly for Nix...
      (some-> (discover me) str)     ; ...otherwise, PATH discovery...

      ; ...or die horribly
      (die (str "WEAN_BINARY not set, nor " (fs/file-name me)
                " found in PATH!"))))

;; Entrypoint ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn -main [& args]
  (let [config (configure!)
        binary (target (System/getProperty "babashka.file"))
        name   (fs/file-name binary)]

    (fs/create-dirs (fs/parent (:log config)))
    (signals! nag-signals)

    (let [now  (System/currentTimeMillis)
          used (-> (read-log (:log config))
                   (reap pid-alive?)
                   (sessions-for name)
                   (usage now (:window config)))]

      (nag config used (friction config used)))

    (signals! supervise-signals)

    (let [id   (open-session! config name (System/currentTimeMillis))
          proc (spawn binary args)]

      (start-heartbeat! config id)

      (.addShutdownHook (Runtime/getRuntime)
                        (Thread. (fn []
                                   (p/destroy-tree proc)
                                   (close-session! config id (System/currentTimeMillis)))))

      (let [exit (:exit @proc)]
        (close-session! config id (System/currentTimeMillis))
        (System/exit exit)))))

(when (= *file* (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
