#include "ristnav.h"
#include <math.h>

/* Strict C99 does not define M_PI. */
#ifndef M_PI
#define M_PI 3.14159265358979323846
#endif

#define RN_EARTH_R      6371000.0
#define RN_DEG2RAD      (M_PI / 180.0)
#define RN_M_PER_DEG_LAT 110574.0
#define RN_M_PER_DEG_LON 111320.0

double rn_haversine_m(double lat1, double lon1, double lat2, double lon2)
{
    double p1 = lat1 * RN_DEG2RAD, p2 = lat2 * RN_DEG2RAD;
    double dp = (lat2 - lat1) * RN_DEG2RAD, dl = (lon2 - lon1) * RN_DEG2RAD;
    double a = sin(dp / 2) * sin(dp / 2) + cos(p1) * cos(p2) * sin(dl / 2) * sin(dl / 2);
    if (a < 0.0) a = 0.0;
    if (a > 1.0) a = 1.0;
    return 2.0 * RN_EARTH_R * asin(sqrt(a));
}

/* Local flat-earth projection about ref_lat, in metres. */
static void rn_to_local(double lat, double lon, double ref_lat,
                        double *east_m, double *north_m)
{
    *east_m  = lon * RN_M_PER_DEG_LON * cos(ref_lat * RN_DEG2RAD);
    *north_m = lat * RN_M_PER_DEG_LAT;
}

int rn_decode_polyline(const char *enc, int precision, rn_point *out, int max_pts)
{
    if (!enc || !out || max_pts <= 0) return -1;
    double factor = pow(10.0, (double)precision);
    long lat = 0, lon = 0;
    int i = 0, n = 0;
    while (enc[i] != '\0' && n < max_pts) {
        long delta[2];
        for (int k = 0; k < 2; k++) {
            int shift = 0; long result = 0; int b;
            do {
                if (enc[i] == '\0') return n;
                if (shift >= 32) return n;
                b = (int)enc[i++] - 63;
                if (b < 0 || b > 0x3f) return n;
                result |= (long)(b & 0x1f) << shift;
                shift += 5;
            } while (b >= 0x20);
            delta[k] = (result & 1) ? ~(result >> 1) : (result >> 1);
        }
        lat += delta[0];
        lon += delta[1];
        out[n].lat = (double)lat / factor;
        out[n].lon = (double)lon / factor;
        n++;
    }
    return n;
}

void rn_route_init(rn_route *r)
{
    if (!r || !r->cum_m || r->n_pts <= 0) return;
    r->cum_m[0] = 0.0;
    for (int i = 1; i < r->n_pts; i++)
        r->cum_m[i] = r->cum_m[i - 1] + rn_haversine_m(r->pts[i - 1].lat, r->pts[i - 1].lon,
                                                       r->pts[i].lat, r->pts[i].lon);
    r->total_m = r->cum_m[r->n_pts - 1];
}

/* Closest point on segment a->b to p, local metres; returns the fraction along the segment. */
static double rn_project_seg(double px, double py, double ax, double ay, double bx, double by,
                             double *cx, double *cy)
{
    double vx = bx - ax, vy = by - ay;
    double len2 = vx * vx + vy * vy;
    double t = 0.0;
    if (len2 > 0.0) {
        t = ((px - ax) * vx + (py - ay) * vy) / len2;
        if (t < 0.0) t = 0.0;
        if (t > 1.0) t = 1.0;
    }
    *cx = ax + t * vx;
    *cy = ay + t * vy;
    return t;
}

static double rn_bearing(double lat1, double lon1, double lat2, double lon2)
{
    double p1 = lat1 * RN_DEG2RAD, p2 = lat2 * RN_DEG2RAD, dl = (lon2 - lon1) * RN_DEG2RAD;
    double y = sin(dl) * cos(p2);
    double x = cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dl);
    double d = atan2(y, x) / RN_DEG2RAD;
    return d < 0.0 ? d + 360.0 : d;
}

void rn_update(const rn_route *r, double lat, double lon, rn_state *st)
{
    if (!r || !st || r->n_pts < 2) return;

    double px, py;
    rn_to_local(lat, lon, lat, &px, &py);

    double best_d2 = -1.0, best_cx = 0, best_cy = 0, best_t = 0;
    int best_seg = 0;
    for (int i = 0; i + 1 < r->n_pts; i++) {
        double ax, ay, bx, by, cx, cy;
        rn_to_local(r->pts[i].lat,     r->pts[i].lon,     lat, &ax, &ay);
        rn_to_local(r->pts[i + 1].lat, r->pts[i + 1].lon, lat, &bx, &by);
        double t = rn_project_seg(px, py, ax, ay, bx, by, &cx, &cy);
        double dx = px - cx, dy = py - cy;
        double d2 = dx * dx + dy * dy;
        if (best_d2 < 0.0 || d2 < best_d2) {
            best_d2 = d2; best_seg = i; best_t = t; best_cx = cx; best_cy = cy;
        }
    }
    (void)best_cx; (void)best_cy;

    st->seg_index    = best_seg;
    st->cross_track_m = sqrt(best_d2);
    st->snap_lat = r->pts[best_seg].lat + best_t * (r->pts[best_seg + 1].lat - r->pts[best_seg].lat);
    st->snap_lon = r->pts[best_seg].lon + best_t * (r->pts[best_seg + 1].lon - r->pts[best_seg].lon);

    double seg_len = r->cum_m[best_seg + 1] - r->cum_m[best_seg];
    st->along_m     = r->cum_m[best_seg] + best_t * seg_len;
    st->remaining_m = r->total_m - st->along_m;
    if (st->remaining_m < 0.0) st->remaining_m = 0.0;

    st->bearing_deg = rn_bearing(r->pts[best_seg].lat, r->pts[best_seg].lon,
                                 r->pts[best_seg + 1].lat, r->pts[best_seg + 1].lon);

    if (st->cross_track_m > RN_OFFROUTE_M) {
        if (st->off_streak < RN_OFFROUTE_STREAK) st->off_streak++;
    } else {
        st->off_streak = 0;
    }
    st->off_route = (st->off_streak >= RN_OFFROUTE_STREAK) ? 1 : 0;

    st->next_turn = -1;
    st->dist_to_turn_m = 0.0;
    for (int m = 0; m < r->n_turns; m++) {
        double bd2 = -1.0, malong = 0.0;
        for (int i = 0; i + 1 < r->n_pts; i++) {
            double ax, ay, bx, by, cx, cy, mx, my;
            rn_to_local(r->turns[m].lat, r->turns[m].lon, r->turns[m].lat, &mx, &my);
            rn_to_local(r->pts[i].lat,     r->pts[i].lon,     r->turns[m].lat, &ax, &ay);
            rn_to_local(r->pts[i + 1].lat, r->pts[i + 1].lon, r->turns[m].lat, &bx, &by);
            double t = rn_project_seg(mx, my, ax, ay, bx, by, &cx, &cy);
            double dx = mx - cx, dy = my - cy, d2 = dx * dx + dy * dy;
            if (bd2 < 0.0 || d2 < bd2) {
                bd2 = d2;
                malong = r->cum_m[i] + t * (r->cum_m[i + 1] - r->cum_m[i]);
            }
        }
        if (malong >= st->along_m - 5.0) {          /* 5 m slack */
            st->next_turn = m;
            st->dist_to_turn_m = malong - st->along_m;
            if (st->dist_to_turn_m < 0.0) st->dist_to_turn_m = 0.0;
            break;
        }
    }
}

void rn_project(const rn_frame *f, double lat, double lon, double *px, double *py)
{
    if (!f || !px || !py) return;
    double w = (double)f->width_px, h = (double)f->height_px;

    /* m_per_px, not bearing, selects the georeference: a track-up frame heading north has bearing 0. */
    if (f->m_per_px <= 0.0) {
        if (f->max_lon == f->min_lon || f->max_lat == f->min_lat) { *px = w / 2.0; *py = h / 2.0; return; }
        double ymin = log(tan(M_PI / 4.0 + f->min_lat * RN_DEG2RAD / 2.0));
        double ymax = log(tan(M_PI / 4.0 + f->max_lat * RN_DEG2RAD / 2.0));
        double y    = log(tan(M_PI / 4.0 + lat * RN_DEG2RAD / 2.0));
        *px = (lon - f->min_lon) / (f->max_lon - f->min_lon) * w;
        *py = (ymax - y) / (ymax - ymin) * h;
        return;
    }
    double dx = (lon - f->center_lon) * RN_M_PER_DEG_LON * cos(f->center_lat * RN_DEG2RAD);
    double dy = (lat - f->center_lat) * RN_M_PER_DEG_LAT;
    /* Rotate by +bearing, not -bearing: travel direction must land on screen-up. */
    double b  = f->bearing_deg * RN_DEG2RAD;
    double rx = dx * cos(b) - dy * sin(b);
    double ry = dx * sin(b) + dy * cos(b);
    double mpp = f->m_per_px > 0.0 ? f->m_per_px : 1.0;
    *px = w / 2.0 + rx / mpp;
    *py = h / 2.0 - ry / mpp;
}

int rn_select_frame(const rn_frame *frames, int n_frames, const rn_state *st,
                    double lat, double lon)
{
    if (!frames || !st || n_frames <= 0) return -1;

    if (st->next_turn >= 0 && st->dist_to_turn_m <= RN_MANEUVER_TRIGGER_M) {
        for (int i = 0; i < n_frames; i++)
            if (frames[i].role == RN_FRAME_MANEUVER && frames[i].turn_index == st->next_turn)
                return i;
    }
    int best = -1;
    double best_slack = -1.0;
    for (int i = 0; i < n_frames; i++) {
        if (frames[i].role != RN_FRAME_FOLLOW) continue;
        double px, py;
        rn_project(&frames[i], lat, lon, &px, &py);
        double w = (double)frames[i].width_px, h = (double)frames[i].height_px;
        double mx = w * RN_FRAME_EDGE_MARGIN, my = h * RN_FRAME_EDGE_MARGIN;
        if (px < mx || px > w - mx || py < my || py > h - my) continue;
        double slack = fmin(fmin(px, w - px), fmin(py, h - py));
        if (slack > best_slack) { best_slack = slack; best = i; }
    }
    if (best >= 0) return best;

    for (int i = 0; i < n_frames; i++)
        if (frames[i].role == RN_FRAME_OVERVIEW) return i;
    return -1;
}
