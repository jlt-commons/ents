(ns ents.core-test
  (:require [clojure.test :refer [deftest is testing]]
            [ents.core :as ec]
            [ents.ffi :as f]))

(def Position (ec/component Position [:x :float] [:y :float]))
(def Velocity (ec/component Velocity [:x :float] [:y :float]))
(def Health   (ec/component Health [:hp :int32]))

(deftest world-lifecycle
  (ec/with-world w
    (is (number? w))))

(deftest entity-naming
  (ec/with-world w
    (let [e (ec/entity! w :player)]
      (is (pos? e))
      (is (= e (ec/ent w :player)))
      (is (= "player" (ec/ent-name-of w :player))))
    (is (zero? (ec/ent w :missing)))))

(deftest tags-and-components
  (ec/with-world w
    (let [e (ec/entity! w :player :tagged Position {:x 1.5 :y 2.5})]
      (is (ec/has? w e :tagged))
      (is (ec/has? w e Position))
      (is (= {:x 1.5 :y 2.5} (ec/get-c w e Position))))
    (ec/remove! w :player :tagged)
    (is (not (ec/has? w :player :tagged)))))

(deftest set-roundtrip
  (ec/with-world w
    (ec/entity! w :e Position {:x 0 :y 0})
    (ec/set-c! w :e Position {:x 10.5 :y -2.25})
    (is (= {:x 10.5 :y -2.25} (ec/get-c w :e Position)))
    (ec/set-c! w :e Health {:hp 42})
    (is (= {:hp 42} (ec/get-c w :e Health)))))

(deftest pairs
  (ec/with-world w
    (ec/entity! w :parent)
    (ec/entity! w :child [:child-of :parent])
    (is (ec/has? w :child [:child-of :parent]))
    (is (= (ec/ent w :parent) (ec/target w :child :child-of)))))

(deftest anon-entities
  (ec/with-world w
    (let [a (ec/anon! w Position {:x 3 :y 4})]
      (is (pos? a))
      (is (= {:x 3.0 :y 4.0} (ec/get-c w a Position))))))

(deftest query-basic
  (ec/with-world w
    (ec/entity! w :a Position {:x 1 :y 1} Velocity {:x 1 :y 0})
    (ec/entity! w :b Position {:x 2 :y 2})
    (is (= 2 (ec/count-q w "Position")))
    (is (= 1 (ec/count-q w "Position, Velocity")))
    (let [rows (ec/query w [Position Velocity])]
      (is (= 1 (count rows)))
      (is (= (ec/ent w :a) (:e (first rows))))
      (is (= 1.0 (get-in (first rows) [:Position :x]))))))

(deftest with-query-macro
  (ec/with-world w
    (ec/entity! w :a Position {:x 1 :y 1})
    (ec/entity! w :b Position {:x 2 :y 2})
    (let [xs (ec/with-query w [p Position e :ent]
               [e (:x p)])]
      (is (= 2 (count xs)))
      (is (= #{1.0 2.0} (set (map second xs)))))))

(deftest system-progress
  (ec/with-world w
    (ec/entity! w :mover Position {:x 0 :y 0} Velocity {:x 1.5 :y 0})
    (ec/system! w :move [p Position v Velocity e :ent]
      (ec/set-c! w e Position {:x (+ (:x p) (:x v))
                               :y (+ (:y p) (:y v))}))
    (ec/progress w 0.016)
    (is (= {:x 1.5 :y 0.0} (ec/get-c w :mover Position)))))

(deftest system-dt-binding
  (ec/with-world w
    (ec/entity! w :m Position {:x 0 :y 0} Velocity {:x 10 :y 0})
    (ec/system! w :move [p Position v Velocity e :ent dt :dt]
      (ec/set-c! w e Position {:x (+ (:x p) (* (:x v) dt))}))
    (ec/progress w 0.5)
    (is (= 5.0 (:x (ec/get-c w :m Position))))))

(deftest observer-on-add
  (ec/with-world w
    (let [seen (atom [])]
      ;; OnAdd fires when the component is first added; flecs docs note the
      ;; value is not yet assigned there, so we assert on the fact of the
      ;; event, not its payload (OnSet carries the value, see below).
      (ec/observer! w :saw-health [h Health :events #{:add}]
        (swap! seen conj (:hp h)))
      (ec/entity! w :mob Health {:hp 7})
      (is (= 1 (count @seen))))))

(deftest observer-on-set-carries-value
  (ec/with-world w
    (let [seen (atom [])]
      (ec/observer! w :saw-health-set [h Health :events #{:set}]
        (swap! seen conj (:hp h)))
      (ec/entity! w :mob Health {:hp 7})
      (is (= [7] @seen)))))

(deftest observer-event-binding
  (ec/with-world w
    (let [events (atom [])]
      (ec/observer! w :watch
                    [h Health ev :event :events #{:add :remove}]
        (swap! events conj ev))
      (ec/entity! w :mob Health {:hp 1})
      (ec/remove! w :mob Health)
      (is (= 2 (count @events))))))

(deftest delete-entities
  (ec/with-world w
    (ec/entity! w :gone Position {:x 1 :y 1})
    (is (= 1 (ec/count-q w "Position")))
    (ec/delete! w :gone)
    (is (= 0 (ec/count-q w "Position")))
    (is (zero? (ec/ent w :gone)))))

;; --- round 1: misuse must throw, never abort the process ---------------------

(deftest missing-entity-throws
  (ec/with-world w
    (is (thrown? Exception (ec/get-c w :nope Position)))
    (is (thrown? Exception (ec/set-c! w :nope Position {:x 1})))
    (is (thrown? Exception (ec/add! w :nope :tag)))
    (is (thrown? Exception (ec/delete! w :nope)))
    (is (thrown? Exception (ec/target w :nope :rel)))
    (is (thrown? Exception (ec/has? w :nope Position)))
    (is (thrown? Exception (ec/ent-name-of w :nope)))
    (let [e (ec/anon! w)]
      (ec/delete! w e)
      (is (thrown? Exception (ec/get-c w e Position)))
      (is (false? (ec/alive? w e))))
    (is (false? (ec/alive? w :nope)))
    (is (false? (ec/valid? w :nope)))))

(deftest unknown-ids-are-absent
  (ec/with-world w
    (ec/entity! w :e)
    (is (false? (ec/has? w :e :never-made)))
    (is (false? (ec/has? w :e [:never-rel :never-tgt])))
    (is (nil? (ec/remove! w :e :never-made)))
    (is (nil? (ec/target w :e :never-rel)))
    (is (true? (ec/has? w (ec/entity! w :t :tag) :tag)))))

(deftest system-exception-propagates
  (ec/with-world w
    (ec/entity! w :a Position {:x 1 :y 1})
    (let [n (atom 0)]
      (ec/system! w :s [p Position]
        (when (= 1 (swap! n inc)) (throw (ex-info "boom" {}))))
      (is (thrown-with-msg? Exception #"boom" (ec/progress w 0.1)))
      (ec/progress w 0.1)
      (is (= 2 @n)))))

(deftest observer-exception-propagates
  (ec/with-world w
    (ec/observer! w :o [h Health :events #{:set}]
      (when (= 13 (:hp h)) (throw (ex-info "unlucky" {}))))
    (is (thrown-with-msg? Exception #"unlucky" (ec/entity! w :m Health {:hp 13})))
    (ec/set-c! w :m Health {:hp 1})
    (is (= {:hp 1} (ec/get-c w :m Health)))))

(deftest callback-errors-stay-in-their-world
  ;; two worlds driven at once: one whose system always throws must not leak
  ;; its pending error into the other's progress, or make it skip rows
  (let [n 2000
        bad (ec/make-world)
        good (ec/make-world)
        ran (atom 0)]
    (try
      (ec/entity! bad :a Position {:x 1 :y 1})
      (ec/entity! good :a Position {:x 1 :y 1})
      (ec/system! bad :s [p Position] (throw (ex-info "bad world" {})))
      (ec/system! good :s [p Position] (swap! ran inc))
      (let [drive (fn [w] (future
                            (loop [i 0 errs []]
                              (if (< i n)
                                (recur (inc i)
                                       (try (ec/progress w 0.016) errs
                                            (catch Exception e (conj errs (ex-message e)))))
                                errs))))
            fb (drive bad)
            fg (drive good)]
        (is (= n (count @fb)))
        (is (every? #{"bad world"} @fb))
        (is (= [] @fg))
        (is (= n @ran)))
      (finally
        (ec/destroy! bad)
        (ec/destroy! good)))))

(deftest callback-error-cleared-when-driver-throws
  ;; if the call into flecs throws after a callback caught an error, the
  ;; callback's error is the one thrown and it is not left pending, which
  ;; would make every later callback of the world skip its rows
  (ec/with-world w
    (ec/entity! w :a Position {:x 1 :y 1})
    (let [n (atom 0)
          progress f/progress]
      (ec/system! w :s [p Position]
        (when (= 1 (swap! n inc)) (throw (ex-info "boom" {}))))
      (with-redefs [f/progress (fn [w dt] (progress w dt) (throw (ex-info "native" {})))]
        (is (thrown-with-msg? Exception #"boom" (ec/progress w 0.1))))
      (ec/progress w 0.1)
      (is (= 2 @n)))))

(deftest redefine-system
  (ec/with-world w
    (ec/entity! w :a Position {:x 1 :y 1} Velocity {:x 5 :y 5})
    (let [seen (atom [])]
      (ec/system! w :s [p Position] (swap! seen conj :v1))
      (ec/system! w :s [v Velocity] (swap! seen conj (:x v)))
      (ec/progress w 0.1)
      (is (= [5.0] @seen)))))

(deftest redefine-observer
  (ec/with-world w
    (let [seen (atom [])]
      (ec/observer! w :o [h Health] (swap! seen conj :v1))
      (ec/observer! w :o [h Health] (swap! seen conj (:hp h)))
      (ec/entity! w :m Health {:hp 3})
      (is (= [3] @seen)))))

(deftest observer-bad-events
  (ec/with-world w
    (is (thrown? Exception (ec/observer! w :o [h Health :events #{:on-set}] nil)))))

(deftest run-system-default-dt
  (ec/with-world w
    (ec/entity! w :a Position {:x 0 :y 0})
    (let [n (atom 0)]
      (ec/system! w :s [p Position] (swap! n inc))
      (ec/run-system! w :s)
      (ec/run-system! w :s 0.5)
      (is (= 2 @n)))))

;; --- round 2: data correctness ------------------------------------------------

(def Stats (ec/component Stats [:a :uint16] [:b :uint32] [:c :uint8] [:d :int8]))

(deftest partial-set-zeroes-new-component
  (ec/with-world w
    (dotimes [_ 50] (ec/anon! w Position {:x 99999.5 :y 12345.5}))
    (doseq [e (map :e (ec/query w [Position]))] (ec/delete! w e))
    (let [e (ec/anon! w Position {:x 1})]
      (is (= {:x 1.0 :y 0.0} (ec/get-c w e Position))))))

(deftest unsigned-fields
  (ec/with-world w
    (let [e (ec/anon! w Stats {:a 40000 :b 3000000000 :c 200 :d -5})]
      (is (= {:a 40000 :b 3000000000 :c 200 :d -5} (ec/get-c w e Stats))))))

(deftest inherited-fields
  (ec/with-world w
    (let [pos (ec/comp-id w Position)
          isa (f/lookup w "flecs.core.IsA")]
      (f/add-id! w pos (f/pair-id (f/lookup w "flecs.core.OnInstantiate")
                                  (f/lookup w "flecs.core.Inherit")))
      (ec/entity! w :base Position {:x 7 :y 7})
      (dotimes [_ 3] (ec/anon! w [isa :base]))
      (is (= (repeat 4 {:x 7.0 :y 7.0})
             (ec/with-query w [p Position] p))))))

(deftest deferred-add-in-system
  (ec/with-world w
    (ec/entity! w :a Position {:x 1 :y 1})
    (ec/system! w :s [p Position e :ent]
      (when-not (ec/has? w e Velocity)
        (ec/set-c! w e Velocity {:x 2})))
    (ec/progress w 0.1)
    (is (= {:x 2.0 :y 0.0} (ec/get-c w :a Velocity)))))

(deftest on-set-fires-once-per-set
  (ec/with-world w
    (let [seen (atom [])]
      (ec/observer! w :o [h Health] (swap! seen conj (:hp h)))
      (ec/entity! w :m Health {:hp 1})
      (ec/set-c! w :m Health {:hp 2})
      (is (= [1 2] @seen)))))

;; --- round 3: resources and cleanup --------------------------------------------

(defn- callables [w] (get @@#'ec/*callables w))

(deftest callables-released
  (let [w (ec/make-world)]
    (ec/system! w :s [p Position] nil)
    (ec/system! w :s [p Position] nil)
    (ec/observer! w :o [h Health] nil)
    (is (= 2 (count (callables w))))
    (ec/destroy! w)
    (is (nil? (callables w)))))

(deftest count-q-bad-expr
  (ec/with-world w
    (is (thrown? Exception (ec/count-q w "NoSuchThing")))))

(deftest names-are-literal
  (ec/with-world w
    (let [a (ec/entity! w :a.b) b (ec/entity! w :a_b) c (ec/entity! w :a/b)]
      (is (= 3 (count (distinct [a b c]))))
      (is (= "a.b" (ec/ent-name-of w :a.b)))
      (is (= a (ec/ent w :a.b))))))

(deftest pair-removal
  (ec/with-world w
    (ec/entity! w :bob [:likes :alice])
    (is (ec/has? w :bob [:likes :alice]))
    (ec/remove! w :bob [:likes :alice])
    (is (false? (ec/has? w :bob [:likes :alice])))))

(deftest phases-order
  (ec/with-world w
    (ec/entity! w :a Position {:x 0 :y 0})
    (let [order (atom [])]
      (ec/system! w :late [p Position :phase :on-store] (swap! order conj :store))
      (ec/system! w :early [p Position :phase :on-load] (swap! order conj :load))
      (ec/system! w :mid [p Position] (swap! order conj :update))
      (ec/progress w 0.1)
      (is (= [:load :update :store] @order))
      (is (thrown? Exception (ec/system! w :bad [p Position :phase :nope] nil))))))

(deftest reserved-names-rejected
  (ec/with-world w
    (doseq [k [:* :_ :$ :this :#5 :#abc (keyword "")]]
      (is (thrown-with-msg? Exception #"reserved|empty" (ec/entity! w k)) (str k))
      (is (thrown-with-msg? Exception #"reserved|empty" (ec/ent w k)) (str k)))
    (is (thrown-with-msg? Exception #"empty" (ec/entity! w "")))
    (is (thrown-with-msg? Exception #"reserved" (ec/system! w :* [p Position] nil)))
    (testing "digit and punctuated names are ordinary entities"
      (let [a (ec/entity! w :5) b (ec/entity! w :123) c (ec/entity! w :a$b)]
        (is (= 3 (count (distinct [a b c]))))
        (is (not= 5 a))
        (is (= "5" (ec/ent-name-of w :5)))))
    (testing "flecs builtins resolve by name"
      (let [parent (ec/entity! w :parent)
            kid (ec/entity! w :kid [:ChildOf :parent])]
        (is (= parent (ec/target w kid :ChildOf)))
        ;; a child is named by its path from the root
        (is (= kid (ec/ent w "parent.kid")))))))
