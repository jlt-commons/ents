#include "flecs_shim.h"

#include <stdio.h>
#include <string.h>
#include <stdlib.h>

ecs_world_t* fj_init(void) { return ecs_init(); }

void fj_fini(ecs_world_t *w) { ecs_fini(w); }

void fj_progress(ecs_world_t *w, float dt) { ecs_progress(w, dt); }

ecs_entity_t fj_entity(ecs_world_t *w, const char *name) {
  ecs_entity_desc_t d = {0};
  d.name = name;
  return ecs_entity_init(w, &d);
}

ecs_entity_t fj_anon(ecs_world_t *w) { return ecs_new(w); }

ecs_entity_t fj_lookup(ecs_world_t *w, const char *name) {
  return ecs_lookup(w, name);
}

const char* fj_name(ecs_world_t *w, ecs_entity_t e) {
  return ecs_get_name(w, e);
}

void fj_delete(ecs_world_t *w, ecs_entity_t e) { ecs_delete(w, e); }

bool fj_is_valid(ecs_world_t *w, ecs_entity_t e) { return ecs_is_valid(w, e); }

bool fj_is_alive(ecs_world_t *w, ecs_entity_t e) { return ecs_is_alive(w, e); }

/* flecs leaves memory of a newly added component uninitialized when the type
   has no ctor, so a partial set would expose whatever the column held before */
static void fj_zero_ctor(void *ptr, int32_t count, const ecs_type_info_t *ti) {
  memset(ptr, 0, (size_t)ti->size * (size_t)count);
}

ecs_entity_t fj_component(ecs_world_t *w, const char *name, int64_t size, int64_t align) {
  ecs_entity_desc_t ed = {0};
  ed.name = name;
  ed.symbol = name;
  ecs_entity_t e = ecs_entity_init(w, &ed);
  if (!e) return 0;
  ecs_component_desc_t cd = {0};
  cd.entity = e;
  cd.type.size = (size_t)size;
  cd.type.alignment = (size_t)align;
  if (!ecs_component_init(w, &cd)) return 0;
  ecs_type_hooks_t h = {0};
  h.ctor = fj_zero_ctor;
  ecs_set_hooks_id(w, e, &h);
  return e;
}

void fj_add_id(ecs_world_t *w, ecs_entity_t e, ecs_id_t id) { ecs_add_id(w, e, id); }

void fj_remove_id(ecs_world_t *w, ecs_entity_t e, ecs_id_t id) { ecs_remove_id(w, e, id); }

bool fj_has_id(ecs_world_t *w, ecs_entity_t e, ecs_id_t id) { return ecs_has_id(w, e, id); }

ecs_id_t fj_pair(ecs_entity_t rel, ecs_entity_t tgt) { return ecs_pair(rel, tgt); }

bool fj_is_pair(ecs_id_t id) { return ecs_id_is_pair(id); }

ecs_entity_t fj_pair_first(ecs_world_t *w, ecs_id_t id) { return ecs_pair_first(w, id); }

ecs_entity_t fj_pair_second(ecs_world_t *w, ecs_id_t id) { return ecs_pair_second(w, id); }

ecs_entity_t fj_get_target(ecs_world_t *w, ecs_entity_t e, ecs_entity_t rel, int32_t idx) {
  return ecs_get_target(w, e, rel, idx);
}

void* fj_get_ptr(ecs_world_t *w, ecs_entity_t e, ecs_id_t id) {
  return (void*)ecs_get_id(w, e, id);
}

void fj_set(ecs_world_t *w, ecs_entity_t e, ecs_id_t id, const void *value, int64_t size) {
  ecs_set_id(w, e, id, (size_t)size, value);
}

void* fj_ensure_ptr(ecs_world_t *w, ecs_entity_t e, ecs_id_t id, int64_t size) {
  return ecs_ensure_id(w, e, id, (size_t)size);
}

void fj_modified(ecs_world_t *w, ecs_entity_t e, ecs_id_t id) { ecs_modified_id(w, e, id); }

void* fj_query(ecs_world_t *w, const char *expr) {
  ecs_query_desc_t d = {0};
  d.expr = expr;
  return ecs_query_init(w, &d);
}

void fj_query_fini(void *q) { ecs_query_fini(q); }

int32_t fj_count(ecs_world_t *w, const char *expr) {
  ecs_query_t *q = fj_query(w, expr);
  if (!q) return -1;
  ecs_iter_t it = ecs_query_iter(w, q);
  int32_t n = ecs_iter_count(&it);
  ecs_query_fini(q);
  return n;
}

/* A standalone iterator plus the query it owns. The iterator is the first
   member, so the handle doubles as an ecs_iter_t* for the field readers. The
   query is kept here because finishing an iterator clears it->query. */
typedef struct { ecs_iter_t it; ecs_query_t *q; } fj_iter_t;

void* fj_it_new(ecs_world_t *w, const char *expr) {
  ecs_query_t *q = fj_query(w, expr);
  if (!q) return NULL;
  fj_iter_t *i = malloc(sizeof(fj_iter_t));
  i->it = ecs_query_iter(w, q);
  i->q = q;
  return i;
}

bool fj_it_next(void *it) { return ecs_query_next((ecs_iter_t*)it); }

void fj_it_fini(void *it) {
  fj_iter_t *i = (fj_iter_t*)it;
  /* ecs_query_next already finalized on exhaustion (it cleared the valid
     bit); finalize only when stopping early, else this double-frees */
  if (i->it.flags & EcsIterIsValid) {
    ecs_iter_fini(&i->it);
  }
  ecs_query_fini(i->q);
  free(i);
}

int32_t fj_it_count(void *it) { return ((ecs_iter_t*)it)->count; }

uint64_t* fj_it_entities(void *it) { return (uint64_t*)((ecs_iter_t*)it)->entities; }

void* fj_it_field(void *it, int64_t size, int8_t index) {
  return ecs_field_w_size((ecs_iter_t*)it, (size_t)size, index);
}

bool fj_it_field_is_set(void *it, int8_t index) {
  return ecs_field_is_set((ecs_iter_t*)it, index);
}

bool fj_it_field_is_self(void *it, int8_t index) {
  return ecs_field_is_self((ecs_iter_t*)it, index);
}

float fj_it_dt(void *it) { return ((ecs_iter_t*)it)->delta_system_time; }

ecs_entity_t fj_it_event(void *it) { return ((ecs_iter_t*)it)->event; }

ecs_entity_t fj_on_add(void) { return EcsOnAdd; }
ecs_entity_t fj_on_set(void) { return EcsOnSet; }
ecs_entity_t fj_on_remove(void) { return EcsOnRemove; }
ecs_entity_t fj_on_load(void) { return EcsOnLoad; }
ecs_entity_t fj_post_load(void) { return EcsPostLoad; }
ecs_entity_t fj_pre_update(void) { return EcsPreUpdate; }
ecs_entity_t fj_on_update(void) { return EcsOnUpdate; }
ecs_entity_t fj_post_update(void) { return EcsPostUpdate; }
ecs_entity_t fj_on_store(void) { return EcsOnStore; }

/* A fresh entity for a system/observer called `name`. flecs refuses to init a
   system or observer on an entity that already is one, so an existing one is
   deleted first: redefining by name replaces it. */
static ecs_entity_t fj_fresh_poly(ecs_world_t *w, const char *name) {
  ecs_entity_t old = name ? ecs_lookup(w, name) : 0;
  if (old && (ecs_system_get(w, old) || ecs_observer_get(w, old))) {
    ecs_delete(w, old);
  }
  return fj_entity(w, name);
}

ecs_entity_t fj_system(ecs_world_t *w, const char *name, const char *expr,
                       ecs_entity_t phase, void (*callback)(ecs_iter_t *it)) {
  ecs_entity_t e = fj_fresh_poly(w, name);
  if (!phase) phase = EcsOnUpdate;
  ecs_system_desc_t d = {0};
  d.entity = e;
  d.phase = phase;
  d.query.expr = expr;
  d.callback = callback;
  return ecs_system_init(w, &d);
}

void fj_run(ecs_world_t *w, ecs_entity_t system, float dt) {
  ecs_run(w, system, dt, NULL);
}

ecs_entity_t fj_observer(ecs_world_t *w, const char *name, const char *expr,
                         const char *events_csv, void (*callback)(ecs_iter_t *it)) {
  ecs_observer_desc_t d = {0};
  d.query.expr = expr;
  d.callback = callback;

  char csv[128];
  snprintf(csv, sizeof(csv), "%s", events_csv ? events_csv : "");
  int n = 0;
  for (char *tok = strtok(csv, ","); tok && n < FLECS_EVENT_DESC_MAX; tok = strtok(NULL, ",")) {
    if (!strcmp(tok, "add"))          d.events[n++] = EcsOnAdd;
    else if (!strcmp(tok, "set"))     d.events[n++] = EcsOnSet;
    else if (!strcmp(tok, "remove"))  d.events[n++] = EcsOnRemove;
    else return 0;
  }
  if (!n) return 0;

  d.entity = fj_fresh_poly(w, name);

  return ecs_observer_init(w, &d);
}
