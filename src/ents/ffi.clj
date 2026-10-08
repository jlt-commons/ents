(ns ents.ffi
  "Raw bindings to the flecs shim in native/flecs_shim.c — a thin defcfn
  layer with no logic. See native/flecs_shim.h for the ABI each binding
  mirrors. Entity ids are :uint64 (pair ids use the high bits); world,
  query and iterator handles are :pointer numbers."
  (:require [jolt.ffi :as ffi]))

;; --- world --------------------------------------------------------------------
(ffi/defcfn init     "fj_init"     [] :pointer)
(ffi/defcfn fini     "fj_fini"     [:pointer] :void)
(ffi/defcfn progress "fj_progress" [:pointer :float] :void)

;; --- entities -----------------------------------------------------------------
(ffi/defcfn entity*   "fj_entity"   [:pointer :string] :uint64)
(ffi/defcfn anon*     "fj_anon"     [:pointer] :uint64)
(ffi/defcfn lookup    "fj_lookup"   [:pointer :string] :uint64)
(ffi/defcfn name*     "fj_name"     [:pointer :uint64] :string)
(ffi/defcfn delete!   "fj_delete"   [:pointer :uint64] :void)
(ffi/defcfn is-valid? "fj_is_valid" [:pointer :uint64] :bool)
(ffi/defcfn is-alive? "fj_is_alive" [:pointer :uint64] :bool)

;; --- components / ids ---------------------------------------------------------
(ffi/defcfn component* "fj_component" [:pointer :string :int64 :int64] :uint64)
(ffi/defcfn add-id!    "fj_add_id"    [:pointer :uint64 :uint64] :void)
(ffi/defcfn remove-id! "fj_remove_id" [:pointer :uint64 :uint64] :void)
(ffi/defcfn has-id?    "fj_has_id"    [:pointer :uint64 :uint64] :bool)
(ffi/defcfn pair-id    "fj_pair"      [:uint64 :uint64] :uint64)
(ffi/defcfn pair?      "fj_is_pair"   [:uint64] :bool)
(ffi/defcfn pair-first "fj_pair_first" [:pointer :uint64] :uint64)
(ffi/defcfn pair-second "fj_pair_second" [:pointer :uint64] :uint64)
(ffi/defcfn get-target "fj_get_target" [:pointer :uint64 :uint64 :int32] :uint64)
(ffi/defcfn get-ptr    "fj_get_ptr"    [:pointer :uint64 :uint64] :pointer)
(ffi/defcfn set*       "fj_set"        [:pointer :uint64 :uint64 :pointer :int64] :void)
(ffi/defcfn ensure-ptr "fj_ensure_ptr" [:pointer :uint64 :uint64 :int64] :pointer)
(ffi/defcfn modified!  "fj_modified"   [:pointer :uint64 :uint64] :void)

;; --- queries ------------------------------------------------------------------
(ffi/defcfn query*     "fj_query"      [:pointer :string] :pointer)
(ffi/defcfn query-fini "fj_query_fini" [:pointer] :void)
(ffi/defcfn count      "fj_count"      [:pointer :string] :int32)

;; --- iterators ----------------------------------------------------------------
;; An iterator from it-new is driven with it-next/it-fini; the iterator a
;; system/observer callback receives is already positioned — use the field
;; readers directly and never call it-next on it.
(ffi/defcfn it-new      "fj_it_new"      [:pointer :string] :pointer)
(ffi/defcfn it-next     "fj_it_next"     [:pointer] :bool)
(ffi/defcfn it-fini     "fj_it_fini"     [:pointer] :void)
(ffi/defcfn it-count    "fj_it_count"    [:pointer] :int32)
(ffi/defcfn it-entities "fj_it_entities" [:pointer] :pointer)
(ffi/defcfn it-field    "fj_it_field"    [:pointer :int64 :int8] :pointer)
(ffi/defcfn it-set?     "fj_it_field_is_set" [:pointer :int8] :bool)
(ffi/defcfn it-self?    "fj_it_field_is_self" [:pointer :int8] :bool)
(ffi/defcfn it-dt       "fj_it_dt"       [:pointer] :float)
(ffi/defcfn it-event    "fj_it_event"    [:pointer] :uint64)
(ffi/defcfn on-add-id   "fj_on_add"   [] :uint64)
(ffi/defcfn on-set-id   "fj_on_set"   [] :uint64)
(ffi/defcfn on-remove-id "fj_on_remove" [] :uint64)
(ffi/defcfn on-load-id    "fj_on_load"    [] :uint64)
(ffi/defcfn post-load-id  "fj_post_load"  [] :uint64)
(ffi/defcfn pre-update-id "fj_pre_update" [] :uint64)
(ffi/defcfn on-update-id  "fj_on_update"  [] :uint64)
(ffi/defcfn post-update-id "fj_post_update" [] :uint64)
(ffi/defcfn on-store-id   "fj_on_store"   [] :uint64)

;; --- systems / observers ------------------------------------------------------
(ffi/defcfn system*   "fj_system"   [:pointer :string :string :uint64 :pointer] :uint64)
(ffi/defcfn run!      "fj_run"      [:pointer :uint64 :float] :void)
(ffi/defcfn observer* "fj_observer" [:pointer :string :string :string :pointer] :uint64)
