// SPDX-License-Identifier: MIT
package nthrc.rtg;

import java.awt.geom.Point2D;
import java.util.LinkedHashMap;
import java.util.Map;

import gambiarra.common.ValidationLog;
import nthrc.WorldgenConfig;
import nthrc.WorldgenStatus;

/**
 * Optimization V (RTG Continued): skip the Voronoi noise cells that cannot change the result.
 *
 * SpacedCellularNoise.eval2D evaluates the point's cell and the 8 neighbours (25 points each) with VoronoiResult.evaluate, which keeps the smallest and second smallest squared distances with STRICT comparisons. The
 * code tries to skip distant cells, but compares a linear distance with a squared one (with !=) and in practice never skips.
 *
 * A cell's points lie in the square [cx, cx+1] x [cz, cz+1] (a point in [0,1) added to the cell's integer corner; rounding can, at most, reach the opposite corner). If the smallest possible squared distance to that
 * square is already >= the second smallest so far, no point there changes anything: the evaluation can be skipped with the SAME result, bit for bit. The math uses the same subtractions as Point2D.distanceSq; floating
 * point rounding is monotonic, so the limit never exceeds the distance the original would compute.
 */
public final class VoronoiCells {

    /** Test (development build only): evaluates the skipped cells anyway and checks that nothing changed. */
    public static final boolean VALIDATE = validateRequested();

    private static long cells;
    private static long skipped;
    private static long validated;
    private static long mismatches;

    private VoronoiCells() {
    }

    private static boolean validateRequested() {
        return WorldgenConfig.validate;
    }

    /** true = no point of this cell is at distance^2 &lt; next from (x, z). */
    public static boolean useless(Point2D.Double[] points, double x, double z, double next) {
        cells++;
        if (points.length == 0) {
            return false;
        }
        double cx = Math.floor(points[0].x);
        double cz = Math.floor(points[0].y);
        // An exactly integer corner may be the upward rounding of the opposite corner: with no way of knowing which cell it belongs to, the square considered covers both (smaller limit, never wrong).
        double lowX = points[0].x == cx ? cx - 1.0 : cx;
        double lowZ = points[0].y == cz ? cz - 1.0 : cz;
        double dx = x < lowX ? lowX - x : (x > cx + 1.0 ? x - (cx + 1.0) : 0.0);
        double dz = z < lowZ ? lowZ - z : (z > cz + 1.0 ? z - (cz + 1.0) : 0.0);
        return dx * dx + dz * dz >= next;
    }

    public static void skipped() {
        skipped++;
    }

    /** Validation: a cell that would be skipped was evaluated; changed = it changed the result (it must not). */
    public static void validated(boolean changed) {
        skipped++;
        validated++;
        ValidationLog.checked("rtg.skipUselessVoronoiCells");
        if (changed) {
            mismatches++;
            ValidationLog.diff("rtg.skipUselessVoronoiCells", "a skippable cell changed the result");
            if (mismatches <= 10) {
                WorldgenStatus.LOG.error("[gambiarra] VALIDATION rtg.skipUselessVoronoiCells: a skippable cell changed the result");
            }
        }
    }

    public static Map<String, Object> describe() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("cells", cells);
        m.put("skipped", skipped);
        m.put("skippedPct", cells > 0 ? Math.round(1000.0 * skipped / cells) / 10.0 : 0);
        if (VALIDATE) {
            m.put("checked", validated);
            m.put("differences", mismatches);
        }
        return m;
    }

}
