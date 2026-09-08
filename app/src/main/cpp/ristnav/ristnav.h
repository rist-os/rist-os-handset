#ifndef RISTNAV_H
#define RISTNAV_H

#include <stddef.h>

#ifdef __cplusplus
extern "C" {
#endif

/* No malloc, no stdlib beyond <math.h>/<stddef.h>, no global mutable state:
 * every buffer is caller-provided and all state lives in rn_route / rn_state. */
#ifndef RN_OFFROUTE_M
#define RN_OFFROUTE_M          50.0  /* metres cross-track that counts as off-route */
#endif
#ifndef RN_OFFROUTE_STREAK
#define RN_OFFROUTE_STREAK     3  /* consecutive off-route fixes before off_route is set */
#endif
#ifndef RN_MANEUVER_TRIGGER_M
#define RN_MANEUVER_TRIGGER_M  250.0  /* metres to the maneuver at which the turn frame is shown */
#endif
#ifndef RN_FRAME_EDGE_MARGIN
#define RN_FRAME_EDGE_MARGIN   0.15  /* fraction of frame edge; must be < half the server frame overlap */
#endif

typedef struct { double lat, lon; } rn_point;

typedef struct {
    double lat, lon;
    int    type;  /* 0 continue,1 left,2 right,3/4 slight,5/6 sharp,7 uturn,8 merge,9 rbt,10 arrive */
    int    distance_m;  /* from the previous maneuver to this one */
} rn_maneuver;

typedef enum { RN_FRAME_OVERVIEW = 0, RN_FRAME_FOLLOW = 1, RN_FRAME_MANEUVER = 2 } rn_frame_role;

typedef struct {
    rn_frame_role role;
    int    segment_index;  /* FOLLOW: which segment; -1 otherwise */
    int    turn_index;  /* MANEUVER: which turn; -1 otherwise */
    double center_lat, center_lon;
    double bearing_deg;
    double m_per_px;
    double min_lat, min_lon, max_lat, max_lon;  /* only meaningful when m_per_px == 0 (north-up) */
    int    width_px, height_px;
} rn_frame;

typedef struct {
    const rn_point   *pts;
    int               n_pts;
    const rn_maneuver*turns;
    int               n_turns;
    double           *cum_m;  /* caller-provided, n_pts entries: cumulative metres */
    double            total_m;
} rn_route;

typedef struct {
    int    seg_index;
    double snap_lat, snap_lon;
    double along_m;
    double remaining_m;
    double cross_track_m;
    int    off_route;
    int    off_streak;  /* internal; zero it on a new route */
    int    next_turn;  /* index into turns[], -1 when finished */
    double dist_to_turn_m;
    double bearing_deg;
} rn_state;

/* Decodes at most max_pts points (Valhalla ships precision 6); returns the count, negative if malformed. */
int rn_decode_polyline(const char *enc, int precision, rn_point *out, int max_pts);

/* Fills cum_m/total_m; call once per route, after decoding. */
void rn_route_init(rn_route *r);

/* st must be zeroed before the first call of a route. */
void rn_update(const rn_route *r, double lat, double lon, rn_state *st);

/* Returns an index into frames[], or -1 if none fit. */
int rn_select_frame(const rn_frame *frames, int n_frames, const rn_state *st,
                    double lat, double lon);

/* m_per_px == 0: bounds interpolation (north-up); m_per_px > 0: centre/rotate/scale (track-up). */
void rn_project(const rn_frame *f, double lat, double lon, double *px, double *py);

/* Great-circle distance, metres. */
double rn_haversine_m(double lat1, double lon1, double lat2, double lon2);

#ifdef __cplusplus
}
#endif
#endif
