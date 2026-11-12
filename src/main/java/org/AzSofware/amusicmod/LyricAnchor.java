package org.AzSofware.amusicmod;

import org.joml.Quaternionfc;
import org.joml.Quaterniond;
import org.joml.Vector3d;

/** Camera-relative comfort constraints applied to a world anchor, without screen-edge culling. */
final class LyricAnchor {
    final Vector3d position = new Vector3d();
    private final Vector3d local = new Vector3d();
    private final Vector3d target = new Vector3d();
    private final Vector3d lastCamera = new Vector3d();
    private final Quaterniond orientation = new Quaterniond();
    private final Quaterniond inverse = new Quaterniond();
    private boolean initialized;

    void spawn(double cx, double cy, double cz, Quaternionfc rotation, double distance, int seed) {
        // Alternate sides, with a little deterministic variation in height and spread.
        double side = (seed & 1) == 0 ? -1 : 1;
        orientation.set(rotation);
        local.set(side * distance * (.12 + Math.floorMod(seed, 7) * .015),
                distance * (.10 + Math.floorMod(seed, 5) * .015), -distance).rotate(orientation);
        position.set(cx + local.x, cy + local.y, cz + local.z);
        lastCamera.set(cx, cy, cz);
        initialized = true;
    }

    void update(double cx, double cy, double cz, Quaternionfc rotation, double dt,
                double distance, double verticalFov, double aspect, double halfWidth, double halfHeight,
                boolean stayOnScreen) {
        if (!initialized) return;
        dt = Math.clamp(dt, 0, .1);
        double moved = lastCamera.distance(cx, cy, cz);
        if (moved > distance * 3) {
            // Teleport: preserve the relative placement instead of leaving lyrics in another chunk.
            position.add(cx - lastCamera.x, cy - lastCamera.y, cz - lastCamera.z);
        } else if (dt > 0) {
            double carry = Math.clamp((moved / dt - 5) / 6, 0, 1);
            position.add((cx - lastCamera.x) * carry, (cy - lastCamera.y) * carry, (cz - lastCamera.z) * carry);
        }
        lastCamera.set(cx, cy, cz);
        orientation.set(rotation);
        inverse.set(orientation).conjugate();
        local.set(position).sub(cx, cy, cz).rotate(inverse);
        target.set(local);
        if (stayOnScreen) {
            target.z = Math.min(-distance * .7, target.z);
            target.z = Math.max(-distance * 1.35, target.z);
            double tan = Math.tan(Math.toRadians(Math.clamp(verticalFov, 30, 120)) / 2);
            double horizontalLimit = Math.max(0, -target.z * tan * aspect * .78 - halfWidth);
            double upperLimit = Math.max(.1, -target.z * tan * .75 - halfHeight);
            target.x = Math.clamp(target.x, -horizontalLimit, horizontalLimit);
            target.y = Math.clamp(target.y, Math.min(.4, upperLimit), upperLimit);
        } else if (target.length() < distance * .7) {
            // Free world mode only keeps a minimum radial distance; turning can leave text behind.
            if (target.lengthSquared() < .0001) target.set(0, 0, -distance);
            else target.mul(distance * .7 / target.length());
        }
        target.rotate(orientation).add(cx, cy, cz);
        double follow = 1 - Math.exp(-dt / .16);
        position.lerp(target, follow);
        // The normal interpolation is gentle; crossing the camera plane must recover immediately.
        if (stayOnScreen && local.z > -1) position.set(target);
    }
}
