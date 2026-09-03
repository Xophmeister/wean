(ns plot)

(require '[babashka.fs :as fs]
         '[clojure.string :as str]
         '[wean :as w])

; The README's plots, drawn from wean's own implementation rather than
; from a restatement of it, so that they cannot quietly go stale when
; the constants are retuned. Run with `bb plot`.

;; Canvas ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:const width 680)
(def ^:const left 66)    ; Leaving room for the y axis labels
(def ^:const right 656)
(def ^:const top 26)

; Mid grey and a strong orange, both legible against a light or a dark
; page: the SVGs have no background of their own, so they are rendered
; against whatever GitHub is themed as.
(def ^:const ink "#888888")
(def ^:const accent "#e8710a")

(def ^:const font "sans-serif")

;; Primitives ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- co
  "A coordinate, at a precision that keeps the files small."
  [x]

  (format "%.2f" (double x)))

(defn- line
  "A rule between two points, optionally dashed."
  [x1 y1 x2 y2 colour & [dash]]

  (str "<line x1=\"" (co x1) "\" y1=\"" (co y1)
       "\" x2=\"" (co x2) "\" y2=\"" (co y2)
       "\" stroke=\"" colour "\" stroke-width=\"1\""
       (if dash (str " stroke-dasharray=\"" dash "\"") "")
       "/>"))

(defn- curve
  "A polyline through the given points."
  [points colour]

  (str "<polyline fill=\"none\" stroke=\"" colour
       "\" stroke-width=\"2.5\" stroke-linejoin=\"round\" points=\""
       (str/join " " (map (fn [[x y]] (str (co x) "," (co y))) points))
       "\"/>"))

(defn- dot [x y] (str "<circle cx=\"" (co x) "\" cy=\"" (co y)
                      "\" r=\"4\" fill=\"" accent "\"/>"))

(defn- label
  "Text, anchored at one of start, middle or end."
  [x y s & {:keys [anchor size fill] :or {anchor "start" size 12 fill ink}}]

  (str "<text x=\"" (co x) "\" y=\"" (co y) "\" fill=\"" fill
       "\" font-family=\"" font "\" font-size=\"" size
       "\" text-anchor=\"" anchor "\">" s "</text>"))

(defn- svg
  "Wrap the given elements in a document of the given height."
  [height elements]

  (str "<svg xmlns=\"http://www.w3.org/2000/svg\""
       " viewBox=\"0 0 " width " " height "\""
       " width=\"" width "\" height=\"" height "\" role=\"img\">\n"
       (str/join "\n" (flatten elements))
       "\n</svg>\n"))

(defn- frame
  "The axes, the horizontal gridlines and the caption beneath, given the
  y of the baseline and the ticks to rule against."
  [floor y-of y-ticks x-of x-ticks caption]

  [(for [[v _] (rest y-ticks)] (line left (y-of v) right (y-of v) ink "2 4"))
   (line left top left floor ink)
   (line left floor right floor ink)

   (for [[v l] y-ticks] (label (- left 8) (+ (y-of v) 4) l :anchor "end" :size 11))
   (for [[v l] x-ticks] (label (x-of v) (+ floor 18) l :anchor "middle" :size 11))
   (label (/ (+ left right) 2) (+ floor 40) caption :anchor "middle")])

;; The friction curve ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:const friction-floor 320)
(def ^:const score-max 200)
(def ^:const seconds-max 1300)  ; A little headroom above the ceiling

(defn- fx [score] (+ left (* (- right left) (/ (double score) score-max))))
(defn- fy [seconds] (- friction-floor
                       (* (- friction-floor top) (/ (double seconds) seconds-max))))

(def ^:private config
  "The plots describe wean as shipped, so they are drawn at its
  defaults."
  w/defaults)

(defn- waits
  "The wait a bare score earns, in seconds."
  [score]

  (w/friction config {:count score :duration 0}))

(defn- spoken
  "A wait, in whichever unit reads more naturally."
  [seconds]

  (if (< seconds 60) (str seconds " s") (format "%.0f min" (/ seconds 60.0))))

(def ^:private habits
  "The three usage patterns the README describes, by score."
  [[10 "a light week"]
   [50 "a heavy week"]
   [119 "one long session a day"]])

(defn friction-plot []
  (svg 380
       [(frame friction-floor
               fy [[0 "0"] [300 "5 min"] [600 "10 min"]
                   [900 "15 min"] [1200 "20 min"]]
               fx (for [s [0 50 100 150 200]] [s (str s)])
               "usage score")

        ; The ceiling, and a crosshair on the midpoint
        (let [midpoint (:midpoint (w/curve config))
              ceiling  (:max-friction config)]
          [(line left (fy ceiling) right (fy ceiling) accent "5 4")
           (label (- right 4) (- (fy ceiling) 8) "max-friction"
                  :anchor "end" :size 11 :fill accent)

           (line (fx midpoint) friction-floor (fx midpoint) (fy 600) ink "3 3")
           (line left (fy 600) (fx midpoint) (fy 600) ink "3 3")
           (label (- (fx midpoint) 8) (- (fy 600) 10) "midpoint" :anchor "end" :size 11)])

        (curve (for [s (range 0 (inc score-max) 0.5)] [(fx s) (fy (waits s))]) accent)

        ; The habits, marked on the curve and named in a legend
        (for [[score _] habits] (dot (fx score) (fy (waits score))))
        (for [[i [score name]] (map-indexed vector habits)
              :let [y (+ 84 (* i 22))]]
          [(dot (+ left 34) (- y 4))
           (label (+ left 48) y (str name " &#8212; score " score
                                     ", waits " (spoken (waits score))))])]))

;; The decay ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:const decay-floor 240)
(def ^:const windows-max 5)

(defn- dx [age] (+ left (* (- right left) (/ (double age) windows-max))))
(defn- dy [weight] (- decay-floor (* (- decay-floor top) (double weight))))

(defn- crosshair
  "Rule out to the curve at the given age, and annotate it."
  [age weight text-x text-y text]

  [(line left (dy weight) (dx age) (dy weight) ink "3 3")
   (line (dx age) decay-floor (dx age) (dy weight) ink "3 3")
   (label text-x text-y text)])

(defn decay-plot []
  (svg 300
       [(frame decay-floor
               dy (for [w [0 0.25 0.5 0.75 1.0]] [w (format "%.2f" (double w))])
               dx (for [a (range 0 (inc windows-max))] [a (str a)])
               "age, in windows")

        (crosshair (Math/log 2) 0.5
                   (+ (dx (Math/log 2)) 10) (- (dy 0.5) 10)
                   "half-life: window &#215; ln 2, a shade under 5 days")

        (crosshair 1 (/ 1 Math/E)
                   (dx 1.5) (dy 0.34)
                   "one window on: worth 1/e of what it was")

        (curve (for [a (range 0 (+ windows-max 0.02) 0.02)]
                 [(dx a) (dy (Math/exp (- a)))])
               accent)]))

;; Entrypoint ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn generate!
  "Write both plots into doc/, whence the README references them."
  []

  (fs/create-dirs "doc")
  (spit "doc/friction.svg" (friction-plot))
  (spit "doc/decay.svg" (decay-plot))
  (println "Wrote doc/friction.svg and doc/decay.svg"))
