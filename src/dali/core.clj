(ns dali.core
  (:use [dali.math]
        [dali.utils]))

(doall
 (map
  #(derive % ::shape)
  [::point ::line ::rectangle ::ellipse ::arc ::circle ::curve
   ::quad-curve ::text ::polyline ::polygon ::path ::group]))

(doall
 (map
  #(derive % ::fillable)
  [::rectangle ::ellipse ::circle
   ::text ::polygon ::path ::group]))

(defn point [x y] [x y])

(defn dimensions [w h] [w h])

(defn set-style [shape s]
  (assoc shape :style s))

(defn set-id [shape id]
  (assoc shape :id id))

(defn add-category [shape category]
  (if-not (:categories shape)
    (assoc shape :categories #{category})
    (assoc shape :categories (conj (:categories shape) category))))

(defn ^{:private true} parse-attr-map
  [m]
  (into {}
   (remove
    (fn [[_ v]] (or (nil? v) (and (coll? v) (empty? v))))
    {:id (:id m)
     :category (cond (nil? (:category m)) nil
                     (coll? (:category m)) (into #{} (:category m))
                     :else #{(:category m)})
     :transform (:transform m)
     :style (-> m
                (dissoc :id)
                (dissoc :category)
                (dissoc :transform))})))

(defn has-stroke? [shape]
  (not (nil? (get-in shape [:style :stroke]))))

(defn has-fill? [shape]
  (not (nil? (get-in shape [:style :fill]))))

(defn has-transform? [shape]
  (not (nil? (get shape :transform))))

(defmacro defshape [shape & geometry-components]
  `(defn ~shape
     ([~@geometry-components]
        {:type ~(keyword (str shape))
         :geometry ~(zipmap (map (fn [x] (keyword (str x)))
                                 geometry-components)
                            geometry-components)})
     ([~'attr-map ~@geometry-components]
        (merge
         (parse-attr-map ~'attr-map)
         (~shape ~@geometry-components)))))

(defshape line start end)
(defshape rectangle position dimensions)
(defshape ellipse position dimensions)
(defshape arc position dimensions angles)
(defshape circle center radius)
(defshape curve start control1 control2 end)
(defshape quad-curve start control end)

(defn text
  ([position txt]
     {:type :text,
      :geometry {:position position},
      :content txt})
  ([attr-map position txt]
     (merge
      (parse-attr-map attr-map)
      (text position txt))))


(defn ^{:private true} extract-attr-map
  "If the first parameter is a map, return [rest attr-map], otherwise
  [params nil]."
  [params]
  (if (map? (first params))
    [(rest params) (first params)]
    [params nil]))

(defn polyline [& points]
  (let [[points attr-map] (extract-attr-map points)]
    (merge
     (parse-attr-map attr-map)
     {:type :polyline
      :geometry {:points points}})))

(defn polygon [& points]
  (let [[points attr-map] (extract-attr-map points)]
    (merge
     (parse-attr-map attr-map)
     {:type :polygon
      :geometry {:points points}})))

;;; example:
;; (path :move-to [10 10]
;;       :line-to [20 20]
;;       :curve-to [[40 20] [40 100] [20 100]]
;;       :quad-to [[20 10] [0 0]]
;;       :close)
(defn path [& path-spec]
  (let [[path-spec attr-map] (extract-attr-map path-spec)]
    (merge
     (parse-attr-map attr-map)
     {:type :path
      :geometry {:path-spec path-spec}})))

(defn group [attr-map & content]
  (merge
   (parse-attr-map attr-map)
   {:type :group
    :content content}))


(defn rectangle->polygon
  [{{[px py] :position [w h] :dimensions} :geometry :as shape}]
  (-> shape
      (assoc :type :polygon)
      (assoc :geometry
        {:points [[px py] [(+ px w) py] [(+ px w) (+ py h)] [px (+ py h)]]})))

(defn polygon->lines
  [{{points :points} :geometry}]
  (map (fn [[p1 p2]] (line p1 p2))
       (take (count points) (partition 2 1 (cycle points)))))

(defn line-start [line] (get-in line [:geometry :start]))
(defn line-end [line] (get-in line [:geometry :end]))

(defn shape-type [shape] (if (vector? shape) :point (:type shape)))

;;;;;;;; Translate ;;;;;;;;

(defmulti translate (fn [shape delta] (shape-type shape)))

(defmethod translate :point
  [shape delta]
  (vec (map + shape delta)))

(defmethod translate :line
  [{{start :start end :end} :geometry :as shape} delta]
  (-> shape
      (assoc-in [:geometry :start] (translate start delta))
      (assoc-in [:geometry :end] (translate end delta))))

(defmethod translate :rectangle
  [{{position :position} :geometry :as shape} delta]
  (assoc-in shape [:geometry :position] (translate position delta)))

(defmethod translate :ellipse
  [{{position :position} :geometry :as shape} delta]
  (assoc-in shape [:geometry :position] (translate position delta)))

(defmethod translate :circle
  [{{c :center} :geometry :as shape} delta]
  (assoc-in shape [:geometry :center] (translate c delta)))

(defmethod translate :polygon
  [{{points :points} :geometry :as shape} delta]
  (assoc-in shape [:geometry :points] (map #(translate % delta) points)))

(defmethod translate :polyline
  [{{points :points} :geometry :as shape} delta]
  (assoc-in shape [:geometry :points] (map #(translate % delta) points)))

(defmethod translate :curve
  [{geometry :geometry :as shape} delta]
  (assoc shape :geometry
         (zipmap (keys geometry) (map #(translate % delta) (vals geometry)))))

(defmethod translate :quad-curve
  [{geometry :geometry :as shape} delta]
  (assoc shape :geometry
         (zipmap (keys geometry) (map #(translate % delta) (vals geometry)))))

;;;;;;;; Rotate ;;;;;;;;

(defmulti rotate (fn [shape angle] (shape-type shape)))

(defmethod rotate :point
  [[x y] a]
  [(- (* x (cos a)) (* y (sin a)))
   (+ (* x (sin a)) (* y (cos a)))])

(defmethod rotate :line
  [{{start :start end :end} :geometry :as shape} a]
  (-> shape
      (assoc-in [:geometry :start] (rotate start a))
      (assoc-in [:geometry :end] (rotate end a))))

(defmethod rotate :circle
  [{{c :center} :geometry :as shape} a]
  (assoc-in shape [:geometry :center] (rotate c a)))

;; TODO ellipse????

(defmethod rotate :polygon
  [shape angle]
  (let [{{points :points} :geometry} shape]
   (assoc-in shape [:geometry :points] (map #(rotate % angle) points))))

(defmethod rotate :rectangle
  [shape angle]
  (rotate (rectangle->polygon shape) angle))

(defmethod rotate :polyline
  [shape angle]
  (let [{{points :points} :geometry} shape]
   (assoc-in shape [:geometry :points] (map #(rotate % angle) points))))

(defmethod rotate :curve
  [{geometry :geometry :as shape} angle]
  (assoc shape :geometry
         (zipmap (keys geometry) (map #(rotate % angle) (vals geometry)))))

(defmethod rotate :quad-curve
  [{geometry :geometry :as shape} angle]
  (assoc shape :geometry
         (zipmap (keys geometry) (map #(rotate % angle) (vals geometry)))))

(defn rotate-around
  [shape angle rotation-center]
  (-> shape
      (translate (minus rotation-center))
      (rotate angle)
      (translate rotation-center)))

;;;;;;;; Scale ;;;;;;;;

(defmulti scale (fn [shape factors] (shape-type shape)))

(defmethod scale :point
  [[x y] [xf yf]]
  [(* xf x) (* yf y)])

(defmethod scale :line
  [{{start :start end :end} :geometry :as shape} factors]
  (-> shape
      (assoc-in [:geometry :start] (scale start factors))
      (assoc-in [:geometry :end] (scale end factors))))

(defmethod scale :rectangle
  [{{position :position dimensions :dimensions} :geometry :as shape} factors]
  (-> shape
      (assoc-in [:geometry :position] (scale position factors))
      (assoc-in [:geometry :dimensions] (scale dimensions factors))))

(defmethod scale :ellipse
  [{{position :position dimensions :dimensions} :geometry :as shape} factors]
  (-> shape
      (assoc-in [:geometry :position] (scale position factors))
      (assoc-in [:geometry :dimensions] (scale dimensions factors))))

(defmethod scale :circle
  [{{c :center r :radius} :geometry :as shape} factors]
  (-> shape
      (assoc-in [:geometry :center] (scale c factors))
      (assoc-in [:geometry :radius] (scale r factors))))

;;;;;;;; Center ;;;;;;;;

(defmulti center shape-type)

(defmethod center :point
  [shape] shape)

(defmethod center :line
  [{{[x1 y1] :start [x2 y2] :end} :geometry}]
  [(+ (min x1 x2) (/ (abs (- x1 x2)) 2))
   (+ (min y1 y2) (/ (abs (- y1 y2)) 2))])

(defmethod center :rectangle
  [{{[px py] :position [w h] :dimensions} :geometry}]
  [(+ px (/ w 2)) (+ py (/ h 2))])

(defmethod center :ellipse
  [{{[px py] :position [w h] :dimensions} :geometry}]
  [(+ px (/ w 2)) (+ py (/ h 2))])

(defmethod center :circle
  [shape]
  (get-in :geometry :center))

(defmethod center :polygon
  [{{points :points} :geometry}]
  (let [c (count points)]
   [(/ (apply + (map first points)) c)
    (/ (apply + (map second points)) c)]))

(defmethod center :polyline
  [{{points :points} :geometry}]
  (let [c (count points)]
   [(/ (apply + (map first points)) c)
    (/ (apply + (map second points)) c)]))

(defn circle->ellipse [shape]
  (let [{{c :center r :radius} :geometry} shape]
    (ellipse (translate c [(- r) (- r)])
             [(* 2 r) (* 2 r)])))

;;;;;;;; Transforms ;;;;;;;;
;;
;; [:scale [3 2]
;;  :scale 10
;;  :skew [10 10]
;;  :translate [10 40]
;;  :rotate 45
;;  :rotate [45 [10 10]]
;;  :matrix [1 2 3 4 5 6]]
;;

(let [transform-directives #{:scale :skew :translate :rotate :matrix}]
  (defn validate-transform-spec [spec]
    (let [pairs (partition 2 spec)]
     (and (every? transform-directives (map first pairs))
          (every? (fn [[dir v]]
                    (condp = dir
                      :scale (or (num-or-fn? v) (coll-of-nums-or-fns? 2 v))
                      :skew (coll-of-nums-or-fns? 2 v)
                      :translate (coll-of-nums-or-fns? 2 v)
                      :rotate (or (num-or-fn? v)
                                  (and
                                   (num-or-fn? (first v))
                                   (function? (second v)))
                                  (let [[a [x y]] v]
                                    (coll-of-nums-or-fns? 3 [a x y])))
                      :matrix (coll-of-nums-or-fns? 6 v))) pairs))))
  
  (defn transform-spec
    "This function validates the transform spec and returns it. If the
    transform is not valid, it throws an Exception. You can write a
    transform as a vector literal in order to avoid the cost of
    validation."
    [& spec]
    (if-not (validate-transform-spec spec)
      (throw (Exception. (str "Transform spec " spec " is not valid.")))
      spec)))

;;;;;;;; Geometry ;;;;;;;;

(defn angle
  ([{{start :start end :end} :geometry}] (angle start end))
  ([[x1 y1] [x2 y2]]
     (polar-angle (abs (- x2 x1)) (abs (- y2 y1)))))

(defn parallel
  [shape delta direction]
  (let [a (angle shape)
        c (center shape)
        delta (if (= direction :left) (- delta) delta)]
    (-> shape
        (rotate-around a c) ;;make it horizontal
        (translate [delta 0])   ;;move it a bit
        (rotate-around (- a) c)))) ;;back to the original angle (using the same center of rotation!)

(defn interpolate
  [[x1 y1] [x2 y2] delta]
  [(+ x1 (* delta (- x2 x1)))
   (+ y1 (* delta (- y2 y1)))])

(defn distance
  [[x1 y1] [x2 y2]]
  (sqrt (+ (* (- x1 x2) (- x1 x2))
           (* (- y1 y2) (- y1 y2)))))

(defn interpolate-distance
  "Interpolate between two points by moving a certain distance from
  the starting point towards the ending point."
  [start end dist]
  (let [total-distance (distance start end)]
    (interpolate start end (/ dist total-distance))))

(defn line-equation [{{[x1 y1] :start [x2 y2] :end} :geometry}]
  (if (= x1 x2)
    {:type :vertical
     :slope :infinity
     :offset x1}
    (let [{:keys [x y]} (solve-2x2-system [x1 1 y1]
                                          [x2 1 y2])]
      {:type :normal
       :slope x
       :offset y})))

(defn line-projection-intersection
  "The point at which the extensions of the passed lines may
  intersect. nil if no such point exists (parallel lines)."
  [line1 line2]
  (let [{type1 :type, slope1 :slope, offset1 :offset, :as eq1} (line-equation line1)
        {type2 :type, slope2 :slope, offset2 :offset, :as eq2} (line-equation line2)]
    (cond (and (= :vertical type1) (= :vertical type2))
            nil
          (or (= :vertical type1) (= :vertical type2))
            (let [[vertical non-vertical]
                  (match-non-match #(= :vertical (:type %)) eq1 eq2)
                  x (:offset vertical)]
              [x (linear-equation-at-x non-vertical x)])
          :else
            (if-let [solution (solve-2x2-system [slope1 -1 (- offset1)]
                                                [slope2 -1 (- offset2)])]
              [(:x solution) (:y solution)]))))

(defn line-intersection
  "The point at which the passed lines intersect. nil if they don't."
  [{{[_ line1-y1] :start [_ line1-y2] :end} :geometry, :as line1}
   {{[_ line2-y1] :start [_ line2-y2] :end} :geometry, :as line2}]
  (let [[x y :as point] (line-projection-intersection line1 line2)]
    (when (and (within y [line1-y1 line1-y2] 2)
               (within y [line2-y1 line2-y2] 2))
      point)))

(defn line-polygon-intersection
  "The points at which the line and the polygon intersect. Can be an
  empty list."
  [line polygon]
  (remove nil? (map (partial line-intersection line) (polygon->lines polygon))))

(defn line-rectangle-intersection
  "The points at which the rectangle and the polygon intersect. Can be
  an empty list."
  [line rectangle]
  (line-polygon-intersection line (rectangle->polygon rectangle)))

;;;;;;;; Advanced shapes ;;;;;;;;

(defn rounded-rect
  ([[px py] [w h] r] (rounded-rect {} [px py] [w h] r))
  ([attr-map [px py] [w h] r]
     (let [internal-w (- w (* 2 r))
           internal-h (- h (* 2 r))]
       (path attr-map
             :move-to [(+ px r) py]
             :quad-by [0 (- r)] [r (- r)]
             :line-by [internal-w 0]
             :quad-by [r 0] [r r]
             :line-by [0 internal-h]
             :quad-by [0 r] [(- r) r]
             :line-by [(- internal-w) 0]
             :quad-by [(- r) 0] [(- r) (- r)]
             :close))))

(defn arrow
  ([start end] (arrow start end 15 30 40))
  ([start end stem-thickness head-spread head-height]
     (let [length (distance start end)
           stem-length (- length head-height)
           stem-line (line start (interpolate-distance start end stem-length))
           stem-line-r (parallel stem-line (/ stem-thickness 2) :right)
           stem-line-l (parallel stem-line (/ stem-thickness 2) :left)
           head-line-r (parallel stem-line (/ head-spread 2) :right)
           head-line-l (parallel stem-line (/ head-spread 2) :left)]
       (path :move-to end
             :line-to (line-end head-line-r)
             :line-to (line-end stem-line-r)
             :line-to (line-start stem-line-r)
             :line-to (line-start stem-line-l)
             :line-to (line-end stem-line-l)
             :line-to (line-end head-line-l)
             :close))))

