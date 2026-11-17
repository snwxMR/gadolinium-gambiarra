// SPDX-License-Identifier: MIT
package gambiarra.lod;

import java.util.Locale;

import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityList;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.Vec3d;

/**
 * F3 (StartupNotice): one line on the living entity under the crosshair, or right beside it, at any distance: what the LOD decided for it
 * (model, image or the switch), the size on screen it measured, the distance and why. Excluded entities too, with the exclusion and the last
 * decision from before it. Only runs with F3 open.
 *
 * The game's own crosshair only reaches a few blocks, and an entity under it within 6 blocks is the target (no LOD): aiming just beside an
 * entity shows its LOD as it is, without making it the target.
 */
public final class LodAim {

    /** Up to 2 degrees beside the entity's outline. */
    private static final double BESIDE = Math.toRadians(2);
    private static final double REACH = 512;
    /** Lod.EXCLUSION, in words. */
    private static final String[] EXCLUDED = { "player", "boss", "has a name", "mount or ridden", "target (crosshair, or attacked in the last 5 s)",
            "excludeMods / excludeEntities" };

    private LodAim() {
    }

    public static String line() {
        if (!Lod.managerOk) {
            return null;
        }
        Minecraft mc = Minecraft.getMinecraft();
        Entity camera = mc.getRenderViewEntity();
        if (camera == null || mc.world == null) {
            return null;
        }
        Entity e = pick(mc, camera);
        if (e == null) {
            return "[gambiarra] LOD aim: none (aim at a mob, or just beside it)";
        }
        StringBuilder b = new StringBuilder("[gambiarra] LOD aim: ").append(name(e)).append(" | ");
        String game = gameFlags((EntityLivingBase) e);
        Lod.State s = Lod.stateOf(e.getEntityId());
        int excluded = Lod.exclusion(e, false);
        if (excluded >= 0) {
            b.append("model | excluded: ").append(EXCLUDED[excluded]);
            if (s != null && s.decFrame != Integer.MIN_VALUE) {
                b.append(" | before, ").append(Lod.frame() - s.decFrame).append(" frames ago: ");
                decision(b, s, e);
            }
            return b.append(game).toString();
        }
        if (s == null || Lod.frame() - s.lastSeen > 2) {
            return b.append("not drawn in the last frames").append(game).toString();
        }
        String off = Impostors.inactiveWhy();
        if (off != null || s.decFrame == Integer.MIN_VALUE) {
            double d = Math.sqrt(sq(e.posX - camera.posX) + sq(e.posY + e.height * 0.5 - camera.posY - camera.getEyeHeight())
                    + sq(e.posZ - camera.posZ));
            float px = d > 0.25 ? (float) (sizeOf(e, s) * Lod.lastProj5 * Lod.halfHeight() / d) : Float.MAX_VALUE;
            b.append("model | ").append(String.format(Locale.ROOT, "%.0f px", px)).append(sizes(e, s))
                    .append(String.format(Locale.ROOT, " | %.1f blocks | ", d)).append(off != null ? off : "level 3 has not decided yet");
            return b.append(game).toString();
        }
        decision(b, s, e);
        return b.append(game).toString();
    }

    /** What the game itself has on the mob, whatever the LOD did: invisible (the game draws only the layers), hurt, dying, glowing. */
    private static String gameFlags(EntityLivingBase e) {
        StringBuilder f = new StringBuilder();
        if (e.isInvisible()) {
            f.append(", invisible");
        }
        if (e.hurtTime > 0) {
            f.append(", hurtTime ").append(e.hurtTime);
        }
        if (e.deathTime > 0) {
            f.append(", deathTime ").append(e.deathTime);
        }
        if (e.isGlowing()) {
            f.append(", glowing");
        }
        return f.length() == 0 ? "" : " | game: " + f.substring(2);
    }

    private static void decision(StringBuilder b, Lod.State s, Entity e) {
        b.append(Impostors.MODES[s.decMode]).append(" | ").append(String.format(Locale.ROOT, "%.0f px", s.decPx))
                .append(sizes(e, s)).append(String.format(Locale.ROOT, " | %.1f blocks | ", s.decDist));
        int r = s.decReason;
        if (r == Impostors.LARGE) {
            b.append(String.format(Locale.ROOT, "at or above %.0f px", s.decLimit));
        } else if (r == Impostors.SMALL) {
            b.append(String.format(Locale.ROOT, "below %.0f px", s.decLimit));
        } else if (r == Impostors.LOCK_DISTANCE || r == Impostors.LOCK_PLAYER_HURT) {
            int min = nthcompat.CompatConfig.lodImpostorMinDistance;
            b.append(Impostors.REASONS[r]).append(" (").append(r == Impostors.LOCK_DISTANCE ? min : 2 * min).append(" blocks)");
        } else {
            b.append(Impostors.REASONS[r]);
        }
    }

    /** (box X, model Y): the two sizes the decision takes the larger of. */
    private static String sizes(Entity e, Lod.State s) {
        String model = s.modelBlocks > 0 ? String.format(Locale.ROOT, "%.1f", s.modelBlocks) : "not measured";
        return String.format(Locale.ROOT, " (box %.1f, model %s)", Math.max(e.width, e.height), model);
    }

    private static float sizeOf(Entity e, Lod.State s) {
        return Math.max(Math.max(e.width, e.height), s.modelBlocks);
    }

    /** The living entity whose outline (the larger of box and model) holds the crosshair, the nearest; otherwise the one closest beside it. */
    private static Entity pick(Minecraft mc, Entity camera) {
        Vec3d eye = camera.getPositionEyes(1.0F);
        Vec3d look = camera.getLook(1.0F);
        Entity best = null;
        double bestScore = Double.MAX_VALUE;
        for (Entity e : mc.world.loadedEntityList) {
            if (!(e instanceof EntityLivingBase) || e == camera || e == mc.player) {
                continue;
            }
            double dx = e.posX - eye.x, dy = e.posY + e.height * 0.5 - eye.y, dz = e.posZ - eye.z;
            double d = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (d < 0.01 || d > REACH) {
                continue;
            }
            double cos = (dx * look.x + dy * look.y + dz * look.z) / d;
            if (cos < 0.5) {
                continue;
            }
            Lod.State s = Lod.stateOf(e.getEntityId());
            double r = 0.5 * (s != null ? sizeOf(e, s) : Math.max(e.width, e.height));
            double off = Math.acos(Math.min(1.0, cos)) - Math.atan(r / d);
            if (off > BESIDE) {
                continue;
            }
            // Inside the outline: the nearest wins; beside it: the closest to the crosshair.
            double score = off <= 0 ? d : REACH + off;
            if (score < bestScore) {
                bestScore = score;
                best = e;
            }
        }
        return best;
    }

    private static String name(Entity e) {
        ResourceLocation id = EntityList.getKey(e);
        return id != null ? id.toString() : e.getClass().getSimpleName();
    }

    private static double sq(double v) {
        return v * v;
    }

}
