(ns ents.core
  "A vybe-flavored flecs API for jolt.

  - components are runtime-registered records with a declared field layout
  - entities are named by keywords, referenced by raw uint64 ids
  - pairs are vectors [rel tgt] (relationships, tags)
  - queries are the flecs DSL driven through let-style binding macros

  The world is a plain pointer number; keep it in a var or a local.

      (def Position (component Position [:x :float] [:y :float]))
      (def w (make-world))
      (entity! w :player Position {:x 0 :y 0})
      (with-query w [p Position e :ent] [e (:x p)])
      (system! w :move [p Position v Velocity e :ent] ...)"
  (:require [ents.ffi :as f]
            [jolt.ffi :as ffi]
            [clojure.string :as str]))

;; --- components ----------------------------------------------------------------

(defrecord Component [name layout size fields])

(defmacro component
  "Define a component spec: (component Position [:x :float] [:y :float]).
  Returns a Component record; registration with a world happens on first use."
  [sym & fields]
  (let [flds (vec fields)]
    `(let [layout# (ffi/layout [:struct ~flds])]
       (->Component ~(str sym) layout# (ffi/layout-size layout#) ~flds))))

(def ^:private align-of
  {:float 4 :int32 4 :uint32 4 :double 8 :int64 8 :uint64 8
   :int16 2 :uint16 2 :int8 1 :uint8 1})

(defn- component-align
  [c]
  (transduce (map align-of) max 1 (map second (:fields c))))

;; component ids per world: {world-pointer {name id}}
(def ^:private *comp-ids (atom {}))

(defn comp-id
  "The entity id of `c` in `w`, registering it on first use."
  [w ^Component c]
  (or (some-> (get-in @*comp-ids [w (:name c)])
              (as-> id (when (f/is-alive? w id) id)))
      (let [id (f/component* w (:name c) (:size c) (component-align c))]
        (when (zero? id)
          (throw (ex-info "component registration failed" {:component (:name c)})))
        (swap! *comp-ids assoc-in [w (:name c)] id)
        id)))

(defn- clear-comp-cache! [w] (swap! *comp-ids dissoc w))

;; --- names / ids ----------------------------------------------------------------

(def ^:private reserved-names
  "Names flecs resolves to its query builtins: Wildcard, Any, Variable, This."
  #{"*" "_" "$" "this"})

(defn ent-name
  "The flecs entity name for a designator. A keyword names its entity literally
  (:a.b is \"a.b\", :ns/x is \"ns/x\"), with dots escaped so flecs does not
  read them as a path; a string is passed through as a flecs path.

  Throws for names flecs can't use as plain entities: the empty name (flecs
  would create an anonymous entity), a leading # (read as a raw id, so :#5 is
  builtin entity 5), and * _ $ this (query wildcards and variables)."
  [k]
  (let [n (if (keyword? k) (subs (str k) 1) (str k))]
    (cond
      (empty? n)
      (throw (ex-info "empty entity name" {:name k}))

      (or (str/starts-with? n "#") (and (keyword? k) (reserved-names n)))
      (throw (ex-info (str "reserved entity name: " n
                           " (flecs reads a leading # as an id, and * _ $ this"
                           " as query builtins); use a numeric id or another name")
                      {:name k}))

      (keyword? k) (str/replace n "." "\\.")
      :else n)))

(defn ent
  "Resolve an entity designator (keyword/string name, numeric id, Component,
  or pair vector) to its raw id. Never creates; returns 0 when not found."
  [w e]
  (cond
    (number? e) e
    (instance? Component e) (comp-id w e)
    (vector? e) (f/pair-id (ent w (first e)) (ent w (second e)))
    :else (f/lookup w (ent-name e))))

(defn- live
  "The id of an existing, alive entity designated by `e`; throws otherwise.
  flecs asserts (aborting the process) on dead or zero ids, so every op that
  takes an entity resolves it through here."
  [w e]
  (let [id (when-not (vector? e) (ent w e))]
    (if (and id (not (zero? id)) (or (f/pair? id) (f/is-alive? w id)))
      id
      (throw (ex-info "no such entity" {:entity e})))))

(defn- known-id
  "The id `x` designates if it already exists (pairs: both elements), else nil."
  [w x]
  (cond
    (instance? Component x) (comp-id w x)
    (vector? x) (let [r (known-id w (first x))
                      t (known-id w (second x))]
                  (when (and r t) (f/pair-id r t)))
    :else (let [id (ent w x)]
            (when (and (not (zero? id)) (or (f/pair? id) (f/is-alive? w id)))
              id))))

(defn eid
  "Like `ent` but creates missing named entities and pair elements."
  [w e]
  (cond
    (number? e) (live w e)
    (instance? Component e) (ent w e)
    (vector? e) (f/pair-id (eid w (first e)) (eid w (second e)))
    :else (let [n (ent-name e)
                id (f/lookup w n)]
            (if (pos? id) id (f/entity* w n)))))

;; --- world ----------------------------------------------------------------------

;; An exception thrown by a system/observer body must not unwind through
;; flecs' C frames: that leaves the world mid-frame and read-only, and the next
;; progress aborts. Callbacks catch it here instead, skip any further rows of
;; that world while it is pending, and the op that drove flecs rethrows it on
;; return. Errors are held per world, so worlds driven from different threads
;; don't see each other's.
(def ^:private *cb-errors (atom {}))

(defn- rethrow-cb-error! [w]
  (when-let [t (get @*cb-errors w)]
    (swap! *cb-errors dissoc w)
    (throw t)))

(defmacro ^:private guarded
  "Run body (a call into world w that may fire callbacks), then rethrow any
  exception one of w's callbacks caught."
  [w & body]
  `(let [r# (do ~@body)]
     (rethrow-cb-error! ~w)
     r#))

;; foreign-callables per world: {world-pointer {system/observer-id address}}.
;; Each is locked in memory until freed, so they are released when their
;; system is replaced and when the world is destroyed.
(def ^:private *callables (atom {}))

(defn- track-callable! [w id cb]
  (swap! *callables assoc-in [w id] cb))

(defn- free-callable! [w id]
  (when-let [cb (get-in @*callables [w id])]
    (swap! *callables update w dissoc id)
    (ffi/free-callable cb)))

(defn- free-callables! [w]
  (let [cbs (vals (get @*callables w))]
    (swap! *callables dissoc w)
    (run! ffi/free-callable cbs)))

(defn make-world [] (f/init))

(defn destroy!
  "Release the world, then the callbacks its systems and observers used."
  [w]
  (try
    (guarded w (f/fini w))
    (finally
      (clear-comp-cache! w)
      (free-callables! w))))

(defmacro with-world
  "Bind a fresh world to `w-sym`, releasing it after body even on throw."
  [w-sym & body]
  `(let [~w-sym (make-world)]
     (try ~@body
          (finally (destroy! ~w-sym)))))

(defn progress
  "Advance the world's pipeline `dt` seconds, running registered systems."
  ([w] (progress w 0.0))
  ([w dt] (guarded w (f/progress w (double dt)))))

;; --- entity/component ops ---------------------------------------------------------

(defn- coerce
  "Coerce a field value to its scalar type: jolt.ffi rejects ints for :float
  fields and non-integers for integer fields. Integers pass as-is so unsigned
  fields take their full range (jolt.ffi range-checks the write)."
  [v t]
  (case t
    (:float :double) (double v)
    (:int8 :uint8 :int16 :uint16 :int32 :uint32 :int64 :uint64)
    (if (integer? v) v (long v))
    v))

(defn- write-component!
  "Write component values: ensure (adds a missing component zeroed, emitting
  OnAdd), merge `m` over it in place, then mark it modified so OnSet observers
  fire with the final values. Fields not in `m` keep their current value."
  [w e ^Component c m]
  (let [id (comp-id w c)
        p (guarded w (f/ensure-ptr w e id (:size c)))
        types (into {} (:fields c))]
    (doseq [[k v] m] (ffi/write-field p (:layout c) k (coerce v (get types k))))
    (guarded w (f/modified! w e id))
    e))

(defn- apply-entity-args!
  [w id args]
  (loop [[arg & more] args]
    (when (some? arg)
      (cond
        (and (instance? Component arg) (map? (first more)))
        (do (write-component! w id arg (first more))
            (recur (next more)))

        :else (do (guarded w (f/add-id! w id (eid w arg))) (recur more)))))
  id)

(defn entity!
  "Create (or find) an entity by name. Extra args add tags/pairs/components:
  :tag adds the tag, [Rel Tgt] adds a pair, Position adds the component,
  Position {:x 1} sets component values. Returns the entity id."
  [w e & args]
  (apply-entity-args! w (live w (eid w e)) args))

(defn anon!
  "A new anonymous entity. Same arg conventions as `entity!`."
  [w & args]
  (apply entity! w (f/anon* w) args))

(defn get-c
  "Read a component's value as a map: (get-c w :player Position). nil when the
  entity lacks the component."
  [w e c]
  (let [p (f/get-ptr w (live w e) (comp-id w c))]
    (when-not (ffi/null? p)
      (into {} (map (fn [k] [k (ffi/read-field p (:layout c) k)]) (map first (:fields c)))))))

(defn set-c!
  "Merge values into a component (added first if missing). Returns the entity id."
  [w e c m] (write-component! w (live w e) c m))

;; Entity ops throw on a missing or dead entity. An id argument that was never
;; created can't be on the entity, so has? answers false and remove! no-ops.

(defn add!
  "Add an id: tag keyword, Component, or pair vector [Rel Tgt]. Tags are
  created on first use."
  [w e x] (let [id (live w e)] (guarded w (f/add-id! w id (eid w x)))))

(defn remove!
  "Remove an id: tag keyword, Component, or pair vector."
  [w e x]
  (let [id (live w e)]
    (when-let [x-id (known-id w x)]
      (guarded w (f/remove-id! w id x-id)))))

(defn has?
  "True when the entity has the id."
  [w e x]
  (let [id (live w e)]
    (boolean (some->> (known-id w x) (f/has-id? w id)))))

(defn delete! [w e] (let [id (live w e)] (guarded w (f/delete! w id))))

(defn alive? [w e] (some? (known-id w e)))
(defn valid? [w e]
  (let [id (when-not (vector? e) (ent w e))]
    (boolean (and id (not (zero? id)) (f/is-valid? w id)))))

(defn ent-name-of
  "The entity's flecs name (or its id as a string when anonymous)."
  [w e]
  (let [id (live w e)
        n (f/name* w id)]
    (if (empty? n) (str id) n)))

(defn target
  "The idx-th target of `e` for relationship `rel` (default idx 0), or nil."
  ([w e rel] (target w e rel 0))
  ([w e rel idx]
   (let [id (live w e)]
     (when-let [r (known-id w rel)]
       (let [t (f/get-target w id r (int idx))]
         (when (pos? t) t))))))

;; --- binding specs -----------------------------------------------------------------

;; A binding vector [p Position e :ent] is a seq of specs evaluated at RUNTIME:
;; Component records become query terms (in order) and bind field maps; the
;; keywords :ent/:dt/:event bind the row's entity id / system dt / observer
;; event. One decode path serves with-query, query, system! and observer!.

(defn binding-expr
  "The flecs DSL expr for a seq of specs: components become comma terms."
  [specs]
  (->> specs
       (filter (partial instance? Component))
       (map :name)
       (str/join ", ")))

(defn- term-indexes
  "For each spec, its query term index when it is a Component, else nil."
  [specs]
  (first
   (reduce (fn [[idxs n] s]
             (if (instance? Component s)
               [(conj idxs n) (inc n)]
               [(conj idxs nil) n]))
           [[] 0] specs)))

(defn- field-plans
  "Per table result: for each Component spec, [base-pointer stride]. A field
  matched on another entity (inherited, singleton) is one shared value, so its
  stride is 0 rather than the component size."
  [it specs tis]
  (mapv (fn [spec ti]
          (when ti
            [(f/it-field it (:size spec) ti)
             (if (f/it-self? it ti) (:size spec) 0)]))
        specs tis))

(defn- decode-row
  "Decode one row of a positioned iterator into the arg vector for `specs`."
  [it ents row specs plans]
  (mapv (fn [spec plan]
          (cond
            (instance? Component spec)
            (let [[p stride] plan
                  at (+ p (* row stride))]
              (into {} (map (fn [k] [k (ffi/read-field at (:layout spec) k)])
                            (map first (:fields spec)))))

            (= spec :ent) (ffi/read ents :uint64 (* 8 row))
            (= spec :dt) (f/it-dt it)
            (= spec :event) (f/it-event it)
            :else nil))
        specs plans))

(defn register-specs!
  "Register any Component specs with the world (comp-id side effect) and
  return the specs. Query exprs reference components by name, so every
  component must exist in the world before the expr is parsed. Public for
  macroexpansion in user namespaces."
  [w specs]
  (doseq [s specs :when (instance? Component s)]
    (comp-id w s))
  specs)

(defn iter-rows
  "Drive a fresh query iterator over `expr`, calling (f args) per row with
  args decoded from `specs`. Returns a vector of f's results."
  [w expr specs f]
  (register-specs! w specs)
  (let [it (f/it-new w expr)]
    (when (ffi/null? it)
      (throw (ex-info "invalid query" {:expr expr})))
    (try
      (let [tis (term-indexes specs)]
        (loop [out []]
          (if-not (f/it-next it)
            out
            (let [n (f/it-count it)
                  ents (f/it-entities it)
                  plans (field-plans it specs tis)]
              (recur (into out
                           (map (fn [row] (f (decode-row it ents row specs plans))))
                           (range n)))))))
      (finally (f/it-fini it)))))

(defn each-row
  "Call (f args) for every row of a callback's already-positioned iterator.
  Public for macroexpansion in user namespaces."
  [it specs f]
  (let [n (f/it-count it)
        ents (f/it-entities it)
        plans (field-plans it specs (term-indexes specs))]
    (dotimes [row n]
      (f (decode-row it ents row specs plans)))))

;; --- queries ----------------------------------------------------------------------

(defn query
  "One map per matched entity, {:e id :Position {...} ...} for the components:

      (query w [Position Velocity])"
  [w comps]
  (let [ks (mapv (fn [c] (keyword (:name c))) comps)]
    (iter-rows w (binding-expr comps) (conj (vec comps) :ent)
               (fn [args]
                 (into {:e (peek args)}
                       (map (fn [k v] [k v]) ks args))))))

(defn count-q
  "Number of entities matching the flecs DSL expr."
  [w expr]
  (let [n (f/count w expr)]
    (when (neg? n) (throw (ex-info "invalid query" {:expr expr})))
    n))

(defmacro with-query
  "Query iteration macro, vybe-style:

      (with-query w [p Position v Velocity e :ent]
        [e (:x p) (:x v)])

  Component bindings become query terms in order; :ent/:dt/:event bind the
  row's entity id / system dt / observer event. Returns a vector of the
  body's value per row."
  [w bindings & body]
  (let [pairs (vec (map vec (partition 2 bindings)))
        syms (mapv first pairs)
        specs (mapv second pairs)]
    `(iter-rows ~w (binding-expr [~@specs]) [~@specs]
                (fn [~syms] ~@body))))

;; --- systems ----------------------------------------------------------------------

(defn- phase-id
  "The builtin pipeline phase entity id for a phase keyword."
  [phase]
  (case phase
    :on-load (f/on-load-id)
    :post-load (f/post-load-id)
    :pre-update (f/pre-update-id)
    :on-update (f/on-update-id)
    :post-update (f/post-update-id)
    :on-store (f/on-store-id)
    (throw (ex-info "unknown phase" {:phase phase}))))

(defn- row-callback
  "A C-callable iterator callback running (f args) per row of world w.
  Exceptions are held for the driving op to rethrow (see *cb-errors)."
  [w specs f]
  (ffi/foreign-callable
   (fn [it]
     (when-not (contains? @*cb-errors w)
       (try (each-row it specs f)
            (catch Throwable t (swap! *cb-errors assoc w t)))))
   [:pointer] :void))

(defn- register-poly!
  "Create a system/observer through `make` (given the callback address),
  replacing any prior one of the same name and releasing its callback."
  [w n specs f make]
  (register-specs! w specs)
  (let [old (f/lookup w n)
        cb (row-callback w specs f)
        id (make cb)]
    (when (zero? id)
      (ffi/free-callable cb)
      (throw (ex-info "flecs rejected the system/observer" {:name n})))
    (when (and (pos? old) (not= old id)) (free-callable! w old))
    (track-callable! w id cb)
    id))

(defn register-system!
  "Function form of system!: `f` takes the arg vector for `specs`. Public for
  macroexpansion in user namespaces."
  [w n specs phase f]
  (let [ph (phase-id phase)]
    (register-poly! w n specs f
                    #(f/system* w n (binding-expr specs) ph %))))

(defmacro system!
  "Register a system that runs on every (progress w dt), body per row:

      (system! w :move [p Position v Velocity e :ent]
        (set-c! w e Position {:x (+ (:x p) (:x v))}))

  Same binding conventions as with-query. Optional :phase keyword binds the
  pipeline phase (:on-update default; :on-load/:pre-update/:post-update/
  :on-store/:post-load). Redefining a system by name replaces it. Returns the
  system's entity id."
  [w name bindings & body]
  (let [pairs (vec (map vec (partition 2 bindings)))
        opts (into {} (filter (comp keyword? first) pairs))
        binds (vec (remove (comp keyword? first) pairs))
        syms (mapv first binds)
        specs (mapv second binds)]
    `(register-system! ~w (ent-name ~name) [~@specs] ~(:phase opts :on-update)
                       (fn [~syms] ~@body))))

(defn run-system!
  "Run one system immediately with `dt` (default 0), outside progress."
  ([w sys] (run-system! w sys 0))
  ([w sys dt] (let [id (live w sys)] (guarded w (f/run! w id (double dt))))))

;; --- observers --------------------------------------------------------------------

(def ^:private observer-events #{:add :set :remove})

(defn register-observer!
  "Function form of observer!: `events` is a keyword or set of :add/:set/
  :remove. Public for macroexpansion in user namespaces."
  [w n specs events f]
  (let [evs (if (keyword? events) #{events} (set events))]
    (when (or (empty? evs) (not-every? observer-events evs))
      (throw (ex-info "observer events must be :add, :set or :remove"
                      {:events events})))
    (register-poly! w n specs f
                    #(f/observer* w n (binding-expr specs)
                                  (str/join "," (map clojure.core/name evs)) %))))

(defmacro observer!
  "Register an observer whose body runs per row when an event fires:

      (observer! w :saw-health [h Health :events #{:add}]
        (swap! seen conj (:hp h)))

  Binding conventions as with-query (:event binds the triggering event id).
  `:events` is an option pair inside the bindings, default #{:set}.
  Redefining an observer by name replaces it."
  [w obs-name bindings & body]
  (let [pairs (vec (map vec (partition 2 bindings)))
        opts (into {} (filter (comp keyword? first) pairs))
        binds (vec (remove (comp keyword? first) pairs))
        syms (mapv first binds)
        specs (mapv second binds)]
    `(register-observer! ~w (ent-name ~obs-name) [~@specs] ~(:events opts :set)
                         (fn [~syms] ~@body))))
