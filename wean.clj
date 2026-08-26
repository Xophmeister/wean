#!/usr/bin/env bb

;; State format ($XDG_STATE_HOME/wean/state.edn):
;; ```clojure
;; {"<BINARY>"
;;  [{:id <UUID> :start <INST>
;;               :end <INST> ; optional}
;;   ...]
;;  ...}
;; ```

(ns wean)

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

(defn -main [& _args]
  (println "Hello, World!"))

;; Entrypoint
(when (= *file* (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
