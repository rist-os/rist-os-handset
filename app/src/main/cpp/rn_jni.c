#include <jni.h>
#include <stdlib.h>
#include <stdint.h>
#include "ristnav.h"

#define RN_MAX_PTS   4096
#define RN_MAX_TURNS 128
#define RN_MAX_FRAMES 64

typedef struct {
    rn_point    pts[RN_MAX_PTS];
    double      cum_m[RN_MAX_PTS];
    rn_maneuver turns[RN_MAX_TURNS];
    rn_route    route;
    rn_state    state;
} rn_ctx;

#define JNIFN(n) Java_watch_rist_assistant_Ristnav_##n

/* out = {px, py} in frame pixels. */
JNIEXPORT void JNICALL JNIFN(nProject)(JNIEnv *e, jclass c,
        jdouble bearing, jdouble cLat, jdouble cLon, jdouble mPerPx,
        jdouble minLat, jdouble minLon, jdouble maxLat, jdouble maxLon,
        jint w, jint h, jdouble lat, jdouble lon, jdoubleArray out) {
    (void)c;
    rn_frame f;
    f.role = RN_FRAME_FOLLOW; f.segment_index = -1; f.turn_index = -1;
    f.center_lat = cLat; f.center_lon = cLon; f.bearing_deg = bearing; f.m_per_px = mPerPx;
    f.min_lat = minLat; f.min_lon = minLon; f.max_lat = maxLat; f.max_lon = maxLon;
    f.width_px = w; f.height_px = h;
    double px = 0, py = 0;
    rn_project(&f, lat, lon, &px, &py);
    jdouble b[2] = { px, py };
    (*e)->SetDoubleArrayRegion(e, out, 0, 2, b);
}

JNIEXPORT jdouble JNICALL JNIFN(nHaversine)(JNIEnv *e, jclass c,
        jdouble la1, jdouble lo1, jdouble la2, jdouble lo2) {
    (void)e; (void)c; return rn_haversine_m(la1, lo1, la2, lo2);
}

JNIEXPORT jlong JNICALL JNIFN(nCreate)(JNIEnv *e, jclass c) {
    (void)e; (void)c; return (jlong)(intptr_t)calloc(1, sizeof(rn_ctx));
}
JNIEXPORT void JNICALL JNIFN(nDestroy)(JNIEnv *e, jclass c, jlong h) {
    (void)e; (void)c; if (h) free((void *)(intptr_t)h);
}

/* Returns point count (<0 on malformed shape). */
JNIEXPORT jint JNICALL JNIFN(nSetRoute)(JNIEnv *e, jclass c, jlong h, jstring shape, jint precision,
        jdoubleArray tlat, jdoubleArray tlon, jintArray ttype, jintArray tdist) {
    (void)c;
    rn_ctx * x = (rn_ctx *)(intptr_t)h; if (!x) return -1;
    const char *enc = (*e)->GetStringUTFChars(e, shape, 0);
    int n = rn_decode_polyline(enc, precision, x->pts, RN_MAX_PTS);
    (*e)->ReleaseStringUTFChars(e, shape, enc);
    if (n < 0) return n;
    int nt = (*e)->GetArrayLength(e, tlat);
    if (nt > RN_MAX_TURNS) nt = RN_MAX_TURNS;
    jdouble *la = (*e)->GetDoubleArrayElements(e, tlat, 0);
    jdouble *lo = (*e)->GetDoubleArrayElements(e, tlon, 0);
    jint *ty = (*e)->GetIntArrayElements(e, ttype, 0);
    jint *di = (*e)->GetIntArrayElements(e, tdist, 0);
    for (int i = 0; i < nt; i++) {
        x->turns[i].lat = la[i]; x->turns[i].lon = lo[i];
        x->turns[i].type = ty[i]; x->turns[i].distance_m = di[i];
    }
    (*e)->ReleaseDoubleArrayElements(e, tlat, la, JNI_ABORT);
    (*e)->ReleaseDoubleArrayElements(e, tlon, lo, JNI_ABORT);
    (*e)->ReleaseIntArrayElements(e, ttype, ty, JNI_ABORT);
    (*e)->ReleaseIntArrayElements(e, tdist, di, JNI_ABORT);
    x->route.pts = x->pts; x->route.n_pts = n;
    x->route.turns = x->turns; x->route.n_turns = nt;
    x->route.cum_m = x->cum_m;
    rn_route_init(&x->route);
    for (int i = 0; i < (int)sizeof(rn_state); i++) ((char *)&x->state)[i] = 0;
    return n;
}

/* out = {snap_lat, snap_lon, along_m, remaining_m, cross_track_m, off_route, next_turn,
 * dist_to_turn_m, bearing_deg}. Returns next_turn (-1 = finished). */
JNIEXPORT jint JNICALL JNIFN(nUpdate)(JNIEnv *e, jclass c, jlong h, jdouble lat, jdouble lon, jdoubleArray out) {
    (void)c;
    rn_ctx *x = (rn_ctx *)(intptr_t)h; if (!x) return -1;
    rn_update(&x->route, lat, lon, &x->state);
    jdouble b[9] = { x->state.snap_lat, x->state.snap_lon, x->state.along_m, x->state.remaining_m,
        x->state.cross_track_m, (double)x->state.off_route, (double)x->state.next_turn,
        x->state.dist_to_turn_m, x->state.bearing_deg };
    (*e)->SetDoubleArrayRegion(e, out, 0, 9, b);
    return x->state.next_turn;
}

/* frames = 13 doubles per frame: {role, seg, turn, cLat, cLon, bearing, mPerPx, minLat, minLon,
 * maxLat, maxLon, w, h}. Returns index or -1. */
JNIEXPORT jint JNICALL JNIFN(nSelectFrame)(JNIEnv *e, jclass c, jlong h, jdoubleArray frames, jint nFrames,
        jdouble lat, jdouble lon) {
    (void)c;
    rn_ctx *x = (rn_ctx *)(intptr_t)h; if (!x) return -1;
    if (nFrames > RN_MAX_FRAMES) nFrames = RN_MAX_FRAMES;
    rn_frame fr[RN_MAX_FRAMES];
    jdouble *a = (*e)->GetDoubleArrayElements(e, frames, 0);
    for (int i = 0; i < nFrames; i++) {
        double *d = &a[i * 13];
        fr[i].role = (rn_frame_role)(int)d[0]; fr[i].segment_index = (int)d[1]; fr[i].turn_index = (int)d[2];
        fr[i].center_lat = d[3]; fr[i].center_lon = d[4]; fr[i].bearing_deg = d[5]; fr[i].m_per_px = d[6];
        fr[i].min_lat = d[7]; fr[i].min_lon = d[8]; fr[i].max_lat = d[9]; fr[i].max_lon = d[10];
        fr[i].width_px = (int)d[11]; fr[i].height_px = (int)d[12];
    }
    (*e)->ReleaseDoubleArrayElements(e, frames, a, JNI_ABORT);
    return rn_select_frame(fr, nFrames, &x->state, lat, lon);
}

/* out = interleaved lat,lon pairs. */
JNIEXPORT jint JNICALL JNIFN(nGetRoutePoints)(JNIEnv *e, jclass c, jlong h, jdoubleArray out) {
    (void)c;
    rn_ctx *x = (rn_ctx *)(intptr_t)h; if (!x) return 0;
    int n = x->route.n_pts;
    int cap = (*e)->GetArrayLength(e, out) / 2;
    if (n > cap) n = cap;
    jdouble *a = (*e)->GetDoubleArrayElements(e, out, 0);
    for (int i = 0; i < n; i++) { a[i * 2] = x->route.pts[i].lat; a[i * 2 + 1] = x->route.pts[i].lon; }
    (*e)->ReleaseDoubleArrayElements(e, out, a, 0);
    return n;
}
