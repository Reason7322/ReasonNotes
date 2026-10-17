/* ext-background-effect-v1 connector for Qt Quick.
 * All calls on Qt GUI thread, with Qt-owned wl_display, compositor, surface.
 */
#include <stdlib.h>
#include <string.h>
#include <stdint.h>
#include <wayland-client.h>
#include "ext-background-effect-v1-client-protocol.h"

struct rn_blur {
    struct wl_display *display;
    struct wl_surface *surface;
    struct wl_event_queue *queue;
    struct wl_registry *registry;
    struct ext_background_effect_manager_v1 *manager;
    struct ext_background_effect_surface_v1 *effect;
    uint32_t capabilities;
};

static void added(void *data, struct wl_registry *registry,
                  uint32_t name, const char *interface, uint32_t version) {
    struct rn_blur *state = data;
    if (strcmp(interface, ext_background_effect_manager_v1_interface.name) == 0
            && version >= 1 && !state->manager) {
        state->manager = wl_registry_bind(
            registry, name, &ext_background_effect_manager_v1_interface, 1);
        if (state->manager)
            wl_proxy_set_queue((struct wl_proxy *)state->manager, state->queue);
    }
}
static void removed(void *data, struct wl_registry *registry, uint32_t name) {
    (void)data; (void)registry; (void)name;
}
static const struct wl_registry_listener registry_events = { added, removed };
static void capabilities(void *data,
        struct ext_background_effect_manager_v1 *manager, uint32_t flags) {
    (void)manager;
    ((struct rn_blur *)data)->capabilities = flags;
}
static const struct ext_background_effect_manager_v1_listener manager_events = {
    capabilities
};

static void free_blur(struct rn_blur *state) {
    if (!state) return;
    if (state->effect) {
        ext_background_effect_surface_v1_destroy(state->effect);
        /* Effect removal is double-buffered, just like activation.
         * Commit after destroy while Qt's wl_surface is still alive. */
        if (state->surface)
            wl_surface_commit(state->surface);
    }
    if (state->manager)
        ext_background_effect_manager_v1_destroy(state->manager);
    if (state->registry)
        wl_registry_destroy(state->registry);
    if (state->queue)
        wl_event_queue_destroy(state->queue);
    if (state->display)
        wl_display_flush(state->display);
    free(state);
}

/* NULL means unsupported/failure. status: -1 bad arg, -2 no interface,
 * -3 compositor says blur unavailable, -4 transport/allocation failure. */
void *rn_blur_enable(void *display, void *compositor, void *surface, int *status_out) {
    int status = -1;
    struct rn_blur *state = NULL;
    if (!display || !compositor || !surface) goto fail;
    state = calloc(1, sizeof *state);
    if (!state) { status = -4; goto fail; }
    state->display = display;
    state->surface = surface;
    state->queue = wl_display_create_queue(state->display);
    if (!state->queue) { status = -4; goto fail; }
    state->registry = wl_display_get_registry(state->display);
    if (!state->registry) { status = -4; goto fail; }
    wl_proxy_set_queue((struct wl_proxy *)state->registry, state->queue);
    if (wl_registry_add_listener(state->registry, &registry_events, state) != 0) {
        status = -4; goto fail;
    }
    if (wl_display_roundtrip_queue(state->display, state->queue) < 0) {
        status = -4; goto fail;
    }
    if (!state->manager) { status = -2; goto fail; }
    if (ext_background_effect_manager_v1_add_listener(
            state->manager, &manager_events, state) != 0) {
        status = -4; goto fail;
    }
    if (wl_display_roundtrip_queue(state->display, state->queue) < 0) {
        status = -4; goto fail;
    }
    if (!(state->capabilities & EXT_BACKGROUND_EFFECT_MANAGER_V1_CAPABILITY_BLUR)) {
        status = -3; goto fail;
    }
    state->effect = ext_background_effect_manager_v1_get_background_effect(
        state->manager, surface);
    if (!state->effect) { status = -4; goto fail; }
    struct wl_region *region = wl_compositor_create_region(compositor);
    if (!region) { status = -4; goto fail; }
    wl_region_add(region, 0, 0, 32000, 32000);
    ext_background_effect_surface_v1_set_blur_region(state->effect, region);
    wl_region_destroy(region);
    /* Wayland spec: the blur region is pending/double-buffered state, and
     * is applied ONLY on wl_surface.commit. A flush or Qt UpdateRequest
     * alone cannot activate it. Make the effect live immediately rather
     * than relying on a manual window resize to trigger Qt's next commit.
     * No buffer attach is changed: this commit only applies pending state.
     * All calls occur on Qt's GUI thread using Qt's existing wl_display.
     */
    wl_surface_commit(state->surface);
    wl_display_flush(state->display);
    if (status_out) *status_out = 0;
    return state;
fail:
    if (status_out) *status_out = status;
    free_blur(state);
    return NULL;
}
void rn_blur_disable(void *handle) { free_blur((struct rn_blur *)handle); }
