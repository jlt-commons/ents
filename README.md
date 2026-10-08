# ents

A jolt wrapper for [flecs](https://github.com/SanderMertens/flecs), with an
API inspired by [vybe](https://github.com/pfeodrippe/vybe).

```clojure
(require '[ents.core :as ec])

(def Position (ec/component Position [:x :float] [:y :float]))
(def Velocity (ec/component Velocity [:x :float] [:y :float]))

(ec/with-world w
  (ec/entity! w :mover Position {:x 0 :y 0} Velocity {:x 1.5 :y 0})

  (ec/system! w :move [p Position v Velocity e :ent]
    (ec/set-c! w e Position {:x (+ (:x p) (:x v))
                             :y (+ (:y p) (:y v))}))

  (ec/progress w 0.016)

  (ec/get-c w :mover Position)) ;=> {:x 1.5 :y 0.0}
```

## API sketch

- `(component Sym [:field :type] ...)` — define a runtime-registered component
- `(make-world)`, `(with-world w ...)`, `(progress w dt)`
- `(entity! w :name & args)` — args: tag keywords, `[Rel Tgt]` pairs,
  `Component` to add, `Component {:field v}` to set
- `(get-c w e Comp)`, `(set-c! w e Comp m)` — component values as maps; set
  goes through `ecs_set_id`, so OnAdd/OnSet observers fire
- `(has? w e id)`, `(add! w e id)`, `(remove! w e id)`, `(delete! w e)`,
  `(target w e rel)` — ids are keywords, Components, or `[Rel Tgt]` pairs
- `(query w [Position Velocity])` — vector of `{:e id :Position {...}}` maps
- `(with-query w [p Position e :ent] body)` — vybe-style iteration; binding
  specs are Components (field maps) or `:ent`/`:dt`/`:event`
- `(system! w :name [p Position ... e :ent] body)` — runs on `progress`;
  optional `:phase :on-update|:on-load|:post-load|:pre-update|:post-update|:on-store`
- `(observer! w :name [h Health :events #{:add :set :remove}] body)` — fires
  on events; `:event` binding carries the triggering event id
- `(count-q w "Position, Velocity")` — raw flecs DSL count; throws on a bad expr

Keywords name entities literally (`:a.b` is `"a.b"`, not a path); strings are
passed through as flecs paths. Names that match a flecs builtin resolve to it,
so `[:ChildOf :parent]` is a real hierarchy pair (the child is then found by
its path, `"parent.kid"`, not by `:kid`). Names flecs reserves throw:
the empty name, a leading `#` (flecs id syntax), and `* _ $ this` (query
wildcards and variables). Ops on a missing or dead entity throw
`ex-info`, and an id that was never created reads as absent (`has?` is false,
`remove!` is a no-op). New components start zeroed, so a partial `set-c!` on
a fresh component leaves the other fields at 0.

An exception thrown in a system or observer body is caught before it can
unwind through flecs and rethrown from the call that drove it (`progress`,
`set-c!`, `entity!`, ...); the world stays usable. Redefining a system or
observer by name replaces it, and callbacks are released on replacement and on
`destroy!`.

## Build

flecs is compiled from its amalgamated source into one dylib together with a
flat `fj_*` shim (`native/flecs_shim.c`) that spares jolt.ffi the descriptor
structs (32-term arrays, callbacks) and emits OnSet notifications properly.
It is built with `FLECS_SOFT_ASSERT`, so a misuse the wrapper doesn't catch
logs an error instead of aborting the process.
Point `FLECS_HOME` at a flecs checkout (default `~/src/flecs`), then:

    jolt run native
    jolt run test
