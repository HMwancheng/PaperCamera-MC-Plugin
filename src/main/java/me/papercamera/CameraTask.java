package me.papercamera;

import org.bukkit.FluidCollisionMode;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.util.Random;

/**
 * The main per-tick camera logic.
 * Runs every server tick (50ms) to orbit, ray-trace, and teleport the camera.
 */
public class CameraTask implements Runnable {

    private static final Vector[] SURROUND_DIRECTIONS = {
            new Vector(1, 0, 0), new Vector(-1, 0, 0),
            new Vector(0, 0, 1), new Vector(0, 0, -1),
            new Vector(0, 1, 0), new Vector(0, -1, 0)
    };

    private final CameraManager manager;
    private final CameraConfig config;
    private final TargetManager targetManager;
    private final Random random = new Random();

    private CameraTarget currentTarget;
    private int targetDurationTicks;
    private int elapsedTicks;
    private Location orbitCenter;
    private double orbitAngle;
    private float lastStableYaw;

    // --- Camera smoothing (second-layer lerp) ---
    private Location cameraPos;

    // --- Direction smoothing (yaw/pitch) ---
    private float smoothedYaw;
    private float smoothedPitch;
    private boolean directionInitialized;

    // --- Occlusion state machine ---
    private int occlusionTicks;
    private boolean isTransitioning;
    private Location transitionTarget;

    public CameraTask(CameraManager manager, CameraConfig config, TargetManager targetManager) {
        this.manager = manager;
        this.config = config;
        this.targetManager = targetManager;
    }

    @Override
    public void run() {
        Player camera = manager.getCameraPlayer();
        if (camera == null || !camera.isOnline()) {
            return;
        }

        // --- Enforce spectator mode ---
        if (camera.getGameMode() != GameMode.SPECTATOR) {
            camera.setGameMode(GameMode.SPECTATOR);
        }

        // --- Initialize camera position for smoothing ---
        if (cameraPos == null || !cameraPos.getWorld().equals(camera.getWorld())) {
            cameraPos = camera.getLocation().clone();
        }

        // --- Check if need to switch target ---
        if (currentTarget == null || !currentTarget.isValid() || elapsedTicks >= targetDurationTicks) {
            switchTarget(camera);
        }

        if (currentTarget == null || !currentTarget.isValid()) {
            if (orbitCenter != null) {
                doOrbit(camera, orbitCenter, orbitCenter);
            }
            return;
        }

        elapsedTicks++;

        Location targetLoc = currentTarget.getLocation();
        if (targetLoc == null) return;

        // --- Cross-world check ---
        if (!camera.getWorld().equals(targetLoc.getWorld())) {
            manager.ensureChunkLoaded(targetLoc);
            try { camera.teleport(targetLoc); } catch (Exception ignored) {}
            orbitCenter = targetLoc.clone();
            cameraPos = targetLoc.clone();
            return;
        }

        // --- Init orbit center ---
        if (orbitCenter == null || !orbitCenter.getWorld().equals(targetLoc.getWorld())) {
            orbitCenter = targetLoc.clone();
        }

        // --- Lerp orbit center toward target (with speed cap) ---
        lerpOrbitCenter(targetLoc);

        doOrbit(camera, orbitCenter, targetLoc);
    }

    // ==================== Orbit & Teleport ====================

    private void doOrbit(Player camera, Location center, Location targetLoc) {
        World world = center.getWorld();
        if (world == null) return;

        // --- Advance orbit angle ---
        orbitAngle += config.getOrbitSpeed();
        if (orbitAngle > Math.PI * 2) orbitAngle -= Math.PI * 2;

        double radius = config.getOrbitRadius();
        double height = config.getOrbitHeight();

        double camX = center.getX() + radius * Math.cos(orbitAngle);
        double camY = center.getY() + height;
        double camZ = center.getZ() + radius * Math.sin(orbitAngle);

        Location idealPos = new Location(world, camX, camY, camZ);

        Location targetEye = targetLoc.clone();
        targetEye.add(0, 1.6, 0);

        // Ensure camera is not below or too close to target
        if (idealPos.getY() <= targetEye.getY() + 0.5) {
            idealPos.setY(targetEye.getY() + 0.5);
        }
        // Ensure minimum horizontal distance
        double horizDist = horizontalDistance(idealPos, targetEye);
        if (horizDist < config.getMinDistance()) {
            Vector pushDir = idealPos.toVector().subtract(targetEye.toVector());
            if (pushDir.lengthSquared() > 0.0001) {
                pushDir.setY(0).normalize();
                idealPos.setX(targetEye.getX() + pushDir.getX() * config.getMinDistance());
                idealPos.setZ(targetEye.getZ() + pushDir.getZ() * config.getMinDistance());
            } else {
                // Directly above — push to a random horizontal direction
                double randAngle = orbitAngle;
                idealPos.setX(targetEye.getX() + Math.cos(randAngle) * config.getMinDistance());
                idealPos.setZ(targetEye.getZ() + Math.sin(randAngle) * config.getMinDistance());
            }
        }

        // --- Occlusion-adjusted position ---
        Location adjustedPos = adjustForOcclusion(idealPos, targetEye, world);

        // --- Check if the adjusted position has clear line of sight ---
        boolean hasClearView = hasClearLineOfSight(adjustedPos, targetEye, world);

        // --- Occlusion state machine ---
        Location desiredPos;
        if (hasClearView) {
            occlusionTicks = 0;
            isTransitioning = false;
            desiredPos = adjustedPos;
        } else {
            occlusionTicks++;

            int timeout = config.getOcclusionTimeoutTicks();
            if (occlusionTicks >= timeout) {
                if (isTargetEnclosed(targetLoc, world)) {
                    manager.getLogger().info("Target '" + currentTarget.getName() + "' is fully enclosed, skipping.");
                    switchTarget(camera);
                    return;
                }

                if (!isTransitioning) {
                    Location clearPos = findClearPositionAround(targetEye, world);
                    if (clearPos != null) {
                        transitionTarget = clearPos;
                        isTransitioning = true;
                        manager.getLogger().info("Occlusion timeout — transitioning to clear position.");
                    } else {
                        manager.getLogger().info("No clear position around '" + currentTarget.getName() + "', skipping.");
                        switchTarget(camera);
                        return;
                    }
                }

                if (isTransitioning && transitionTarget != null) {
                    desiredPos = lerpPosition(cameraPos, transitionTarget, config.getTransitionLerp());
                    if (cameraPos.distance(transitionTarget) < 0.5) {
                        isTransitioning = false;
                        occlusionTicks = 0;
                    }
                } else {
                    desiredPos = adjustedPos;
                }
            } else {
                desiredPos = adjustedPos;
            }
        }

        // --- Second-layer camera smoothing ---
        if (config.isCameraSmoothingEnabled()) {
            desiredPos = lerpPosition(cameraPos, desiredPos, config.getCameraSmoothingLerp());
        }

        // --- Set direction and teleport ---
        setStableDirection(desiredPos, targetEye);

        try {
            camera.teleport(desiredPos);
            cameraPos = desiredPos.clone();
        } catch (Exception e) {
            // world unloaded mid-tick
        }
    }

    // ==================== Occlusion Logic ====================

    private boolean hasClearLineOfSight(Location from, Location to, World world) {
        Vector dir = to.toVector().subtract(from.toVector());
        double dist = dir.length();
        if (dist < 0.01) return true;
        RayTraceResult result = world.rayTraceBlocks(from, dir.normalize(), dist,
                FluidCollisionMode.NEVER, true);
        return result == null || result.getHitBlock() == null;
    }

    private boolean isTargetEnclosed(Location targetLoc, World world) {
        Location checkOrigin = targetLoc.clone().add(0, 1.0, 0);
        for (Vector dir : SURROUND_DIRECTIONS) {
            RayTraceResult result = world.rayTraceBlocks(checkOrigin, dir, 5.0,
                    FluidCollisionMode.NEVER, true);
            if (result == null || result.getHitBlock() == null) {
                return false;
            }
        }
        return true;
    }

    private Location findClearPositionAround(Location targetEye, World world) {
        double minDist = config.getMinDistance();
        double maxDist = config.getMaxDistance();

        for (int v = -1; v <= 1; v++) {
            double yOff = v * 2.0;
            for (int a = 0; a < 8; a++) {
                double angle = a * Math.PI / 4.0;
                for (double dist = minDist; dist <= maxDist; dist += 2.0) {
                    double x = targetEye.getX() + Math.cos(angle) * dist;
                    double y = targetEye.getY() + yOff;
                    double z = targetEye.getZ() + Math.sin(angle) * dist;

                    if (y < targetEye.getY() - 1.0) y = targetEye.getY() + 0.5;
                    if (y > targetEye.getY() + 10.0) y = targetEye.getY() + 5.0;

                    Location testPos = new Location(world, x, y, z);
                    if (hasClearLineOfSight(testPos, targetEye, world)) {
                        return testPos;
                    }
                }
            }
        }
        return null;
    }

    // ==================== Adjust for Occlusion ====================

    private Location adjustForOcclusion(Location idealPos, Location targetEye, World world) {
        double minDist = config.getMinDistance();
        double maxDist = config.getMaxDistance();

        Location testPos = idealPos.clone();
        int maxIterations = 10;

        for (int iter = 0; iter < maxIterations; iter++) {
            double distToTarget = testPos.distance(targetEye);
            if (distToTarget < 0.01) return testPos;

            if (distToTarget < minDist) break;

            Vector direction = targetEye.toVector().subtract(testPos.toVector()).normalize();

            RayTraceResult result = world.rayTraceBlocks(
                    testPos, direction, distToTarget,
                    FluidCollisionMode.NEVER, true
            );

            if (result == null || result.getHitBlock() == null) {
                if (distToTarget > maxDist) {
                    return targetEye.clone().add(direction.clone().multiply(-maxDist));
                }
                return testPos;
            }

            Location hitPos = result.getHitPosition().toLocation(world);
            double hitDist = testPos.distance(hitPos);

            if (hitDist < minDist) break;

            testPos = hitPos.clone().add(direction.clone().multiply(0.5));
        }

        // --- Fallback: try angled positions with horizontal offset ---
        // Must NOT be directly above the target — always include horizontal offset
        for (int i = 1; i <= 4; i++) {
            double testAngle = orbitAngle + (Math.PI / 2.0) * i;
            double r = config.getOrbitRadius();
            double altX = orbitCenter.getX() + r * Math.cos(testAngle);
            double altY = orbitCenter.getY() + config.getOrbitHeight();
            double altZ = orbitCenter.getZ() + r * Math.sin(testAngle);

            Location altPos = new Location(world, altX, altY, altZ);

            // Ensure horizontal distance from target
            double hDist = horizontalDistance(altPos, targetEye);
            if (hDist < minDist) {
                Vector pushDir = new Vector(altPos.getX() - targetEye.getX(), 0, altPos.getZ() - targetEye.getZ()).normalize();
                altPos.setX(targetEye.getX() + pushDir.getX() * minDist);
                altPos.setZ(targetEye.getZ() + pushDir.getZ() * minDist);
            }

            Vector altDir = targetEye.toVector().subtract(altPos.toVector()).normalize();
            double altDist = altPos.distance(targetEye);

            RayTraceResult altResult = world.rayTraceBlocks(
                    altPos, altDir, altDist, FluidCollisionMode.NEVER, true);
            if (altResult == null || altResult.getHitBlock() == null) {
                if (altDist > maxDist)
                    return targetEye.clone().add(altDir.clone().multiply(-maxDist));
                return altPos;
            }
        }

        return testPos;
    }

    // ==================== Helpers ====================

    private double horizontalDistance(Location a, Location b) {
        double dx = a.getX() - b.getX();
        double dz = a.getZ() - b.getZ();
        return Math.sqrt(dx * dx + dz * dz);
    }

    private void lerpOrbitCenter(Location targetLoc) {
        double lerp = config.getLerpFactor();
        double maxMove = config.getMaxMovePerTick();

        double dx = (targetLoc.getX() - orbitCenter.getX()) * lerp;
        double dy = (targetLoc.getY() - orbitCenter.getY()) * lerp;
        double dz = (targetLoc.getZ() - orbitCenter.getZ()) * lerp;

        double moveDist = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (moveDist > maxMove) {
            double scale = maxMove / moveDist;
            dx *= scale; dy *= scale; dz *= scale;
        }

        orbitCenter.setX(orbitCenter.getX() + dx);
        orbitCenter.setY(orbitCenter.getY() + dy);
        orbitCenter.setZ(orbitCenter.getZ() + dz);
    }

    private Location lerpPosition(Location from, Location to, double factor) {
        if (from == null) return to.clone();
        double x = from.getX() + (to.getX() - from.getX()) * factor;
        double y = from.getY() + (to.getY() - from.getY()) * factor;
        double z = from.getZ() + (to.getZ() - from.getZ()) * factor;
        return new Location(to.getWorld(), x, y, z);
    }

    private void setStableDirection(Location cameraPos, Location targetEye) {
        Vector toTarget = targetEye.toVector().subtract(cameraPos.toVector());
        if (toTarget.lengthSquared() < 0.0001) return;

        double x = toTarget.getX();
        double y = toTarget.getY();
        double z = toTarget.getZ();
        double horizontalDist = Math.sqrt(x * x + z * z);
        double targetPitch = Math.toDegrees(-Math.atan2(y, horizontalDist));
        double targetYaw;

        if (Math.abs(targetPitch) > 80.0) {
            targetYaw = lastStableYaw;
        } else {
            targetYaw = Math.toDegrees(Math.atan2(-x, z));
            lastStableYaw = (float) targetYaw;
        }

        if (!directionInitialized) {
            smoothedYaw = (float) targetYaw;
            smoothedPitch = (float) targetPitch;
            directionInitialized = true;
        } else {
            double factor = config.getDirectionLerpFactor();
            smoothedYaw = lerpYaw(smoothedYaw, (float) targetYaw, factor);
            smoothedPitch = (float) (smoothedPitch + (targetPitch - smoothedPitch) * factor);
        }

        cameraPos.setYaw(smoothedYaw);
        cameraPos.setPitch(smoothedPitch);
    }

    private float lerpYaw(float from, float to, double factor) {
        float diff = to - from;
        while (diff > 180) diff -= 360;
        while (diff < -180) diff += 360;
        return from + (float) (diff * factor);
    }

    // ==================== Target Switching ====================

    private void switchTarget(Player camera) {
        CameraTarget newTarget = targetManager.getNextTarget();
        if (newTarget == null) {
            currentTarget = null;
            return;
        }
        applyTarget(camera, newTarget);
        manager.getLogger().info("Camera → " + newTarget.getName()
                + " (" + (targetDurationTicks / 20) + "s)");
    }

    public void forceSwitch(Player camera, CameraTarget target) {
        applyTarget(camera, target);
    }

    public void forceNextTarget(Player camera) {
        switchTarget(camera);
    }

    private void applyTarget(Player camera, CameraTarget target) {
        currentTarget = target;
        elapsedTicks = 0;
        occlusionTicks = 0;
        isTransitioning = false;
        transitionTarget = null;
        directionInitialized = false;

        int minTicks = config.getMinDuration() * 20;
        int maxTicks = config.getMaxDuration() * 20;
        targetDurationTicks = minTicks + random.nextInt(Math.max(1, maxTicks - minTicks + 1));

        Location targetLoc = target.getLocation();
        if (targetLoc != null) {
            orbitCenter = targetLoc.clone();
            cameraPos = targetLoc.clone();
        }
        orbitAngle = random.nextDouble() * Math.PI * 2;
        lastStableYaw = (float) (random.nextDouble() * 360.0);

        if (targetLoc != null && !camera.getWorld().equals(targetLoc.getWorld())) {
            manager.ensureChunkLoaded(targetLoc);
            try { camera.teleport(targetLoc); } catch (Exception ignored) {}
        }
    }

    public CameraTarget getCurrentTarget() {
        return currentTarget;
    }
}