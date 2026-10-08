#ifndef FLECS_ENTS_SHIM_H
#define FLECS_ENTS_SHIM_H

#include "flecs.h"

/* Flat C ABI over the flecs pieces ents needs. Entity ids cross as uint64
 * (pair ids use the high bits), pointers as void*. Everything that would need
 * a descriptor struct with embedded arrays or callbacks is done here in C. */

/* world */
ecs_world_t* fj_init(void);
void fj_fini(ecs_world_t *w);
void fj_progress(ecs_world_t *w, float dt);

/* entities */
ecs_entity_t fj_entity(ecs_world_t *w, const char *name); /* NULL name = anonymous */
ecs_entity_t fj_anon(ecs_world_t *w);
ecs_entity_t fj_lookup(ecs_world_t *w, const char *name);
const char* fj_name(ecs_world_t *w, ecs_entity_t e);
void fj_delete(ecs_world_t *w, ecs_entity_t e);
bool fj_is_valid(ecs_world_t *w, ecs_entity_t e);
bool fj_is_alive(ecs_world_t *w, ecs_entity_t e);

/* components (runtime-registered, like vybe's) */
ecs_entity_t fj_component(ecs_world_t *w, const char *name, int64_t size, int64_t align);

/* ids: add/remove/has, pairs */
void fj_add_id(ecs_world_t *w, ecs_entity_t e, ecs_id_t id);
void fj_remove_id(ecs_world_t *w, ecs_entity_t e, ecs_id_t id);
bool fj_has_id(ecs_world_t *w, ecs_entity_t e, ecs_id_t id);
ecs_id_t fj_pair(ecs_entity_t rel, ecs_entity_t tgt);
bool fj_is_pair(ecs_id_t id);
ecs_entity_t fj_pair_first(ecs_world_t *w, ecs_id_t id);
ecs_entity_t fj_pair_second(ecs_world_t *w, ecs_id_t id);
ecs_entity_t fj_get_target(ecs_world_t *w, ecs_entity_t e, ecs_entity_t rel, int32_t idx);

/* component data: ptr is valid until the next world mutation-ish op */
void* fj_get_ptr(ecs_world_t *w, ecs_entity_t e, ecs_id_t id);
void fj_set(ecs_world_t *w, ecs_entity_t e, ecs_id_t id, const void *value, int64_t size);
void* fj_ensure_ptr(ecs_world_t *w, ecs_entity_t e, ecs_id_t id, int64_t size); /* adds if missing (zeroed) */
void fj_modified(ecs_world_t *w, ecs_entity_t e, ecs_id_t id); /* emits OnSet after writing through ensure */

/* queries (expr is the flecs DSL string) */
void* fj_query(ecs_world_t *w, const char *expr);
void fj_query_fini(void *q);
int32_t fj_count(ecs_world_t *w, const char *expr);

/* standalone query iterator */
void* fj_it_new(ecs_world_t *w, const char *expr);
bool fj_it_next(void *it);
void fj_it_fini(void *it);

/* iterator fields, shared by query its and system/observer callbacks.
 * In a callback the iterator arrives positioned: call count/entities/field
 * directly, never next(). */
int32_t fj_it_count(void *it);
uint64_t* fj_it_entities(void *it);
void* fj_it_field(void *it, int64_t size, int8_t index);
bool fj_it_field_is_set(void *it, int8_t index);
bool fj_it_field_is_self(void *it, int8_t index); /* false: one shared value, not one per row */
float fj_it_dt(void *it);
ecs_entity_t fj_it_event(void *it); /* observers: which event fired */
ecs_entity_t fj_on_add(void);
ecs_entity_t fj_on_set(void);
ecs_entity_t fj_on_remove(void);
ecs_entity_t fj_on_load(void);
ecs_entity_t fj_post_load(void);
ecs_entity_t fj_pre_update(void);
ecs_entity_t fj_on_update(void);
ecs_entity_t fj_post_update(void);
ecs_entity_t fj_on_store(void);

/* systems / observers. Redefining one by name replaces it. fj_observer
 * returns 0 for an unknown or empty event list. */
ecs_entity_t fj_system(ecs_world_t *w, const char *name, const char *expr,
                       ecs_entity_t phase, void (*callback)(ecs_iter_t *it));
void fj_run(ecs_world_t *w, ecs_entity_t system, float dt);
ecs_entity_t fj_observer(ecs_world_t *w, const char *name, const char *expr,
                         const char *events_csv, /* "add,set,remove" */
                         void (*callback)(ecs_iter_t *it));

#endif
