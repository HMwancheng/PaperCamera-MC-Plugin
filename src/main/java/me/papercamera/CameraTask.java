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

    private final CameraManager manager;
    private final CameraConfig config;
    private final TargetManager targetManager;
    private final Random random = new Random();

    private CameraTarget currentTarget;
    private int targetDurationTicks;
    private int elapsedTicks;
    private Location orbitCenter;
    private double orbitAngle;
    /** Last stable yaw, used to prevent flipping when camera is nearly vertical. */
    private float lastStableYaw;

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

        // --- Enforce spectator mode (in case another plugin changed it) ---
        if (camera.getGameMode() != GameMode.SPECTATOR) {
            camera.setGameMode(GameMode.SPECTATOR);
        }

        // --- Enforce valid world (in case camera was teleported elsewhere) ---
        if (!config.getIdleWorlds().contains(camera.getWorld().getName())) {
            Location rescue = findRescueLocation();
            if (rescue != null) {
                manager.ensureChunkLoaded(rescue);
                try {
                    camera.teleport(rescue);
                } catch (Exception ignored) {}
            }
            return;
        }

        // --- Check if need to switch target ---
        if (currentTarget == null || !currentTarget.isValid() || elapsedTicks >= targetDurationTicks) {
            switchTarget(camera);
        }

        // --- If no target, orbit at last known position ---
        if (currentTarget == null || !currentTarget.isValid()) {
            if (orbitCenter != null) {
                doOrbit(camera, orbitCenter, orbitCenter);
            }
            return;
        }

        elapsedTicks++;

        Location targetLoc = currentTarget.getLocation();
        if (targetLoc == null) return;

        // --- Check world mismatch ---
        if (!camera.getWorld().equals(targetLoc.getWorld())) {
            manager.ensureChunkLoaded(targetLoc);
            try {
                camera.teleport(targetLoc);
            } catch (Exception ignored) {}
            orbitCenter = targetLoc.clone();
            return;
        }

        // --- Ensure target is in a configured world ---
        if (currentTarget instanceof PlayerTarget
                && !config.getIdleWorlds().contains(currentTarget.getWorld().getName())) {
            switchTarget(camera);
            return;
        }

        // --- Init orbit center on first run ---
        if (orbitCenter == null || !orbitCenter.getWorld().equals(targetLoc.getWorld())) {
            orbitCenter = targetLoc.clone();
        }

        // --- Lerp orbit center toward target (with speed cap) ---
        double lerp = config.getLerpFactor();
        double maxMove = config.getMaxMovePerTick();

        double dx = (targetLoc.getX() - orbitCenter.getX()) * lerp;
        double dy = (targetLoc.getY() - orbitCenter.getY()) * lerp;
        double dz = (targetLoc.getZ() - orbitCenter.getZ()) * lerp;

        double moveDist = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (moveDist > maxMove) {
            double scale = maxMove / moveDist;
            dx *= scale;
            dy *= scale;
            dz *= scale;
        }

        orbitCenter.setX(orbitCenter.getX() + dx);
        orbitCenter.setY(orbitCenter.getY() + dy);
        orbitCenter.setZ(orbitCenter.getZ() + dz);

        doOrbit(camera, orbitCenter, targetLoc);
    }

    /**
     * Calculate orbit position, ray-trace for occlusion with iterative search, and teleport camera.
     */
    private void doOrbit(Player camera, Location center, Location targetLoc) {
        World world = center.getWorld();
        if (world == null) return;

        orbitAngle += config.getOrbitSpeed();
        if (orbitAngle > Math.PI * 2) {
            orbitAngle -= Math.PI * 2;
        }

        double radius = config.getOrbitRadius();
        double height = config.getOrbitHeight();

        double camX = center.getX() + radius * Math.cos(orbitAngle);
        double camY = center.getY() + height;
        double camZ = center.getZ() + radius * Math.sin(orbitAngle);

        Location idealPos = new Location(world, camX, camY, camZ);

        // Target eye position (slightly above feet)
        Location targetEye = targetLoc.clone();
        targetEye.add(0, 1.6, 0);

        // Ensure camera is always above the target's eye level
        if (idealPos.getY() <= targetEye.getY() + 0.5) {
            idealPos.setY(targetEye.getY() + 0.5);
        }

        Location adjustedPos = adjustForOcclusion(idealPos, targetEye, world);

        // --- Calculate stable look direction ---
        setStableDirection(adjustedPos, targetEye);

        // --- Teleport ---
        try {
            camera.teleport(adjustedPos);
        } catch (Exception e) {
            // teleport failed silently (e.g., world unloaded mid-tick)
        }
    }

    /**
     * Set the camera's yaw/pitch to look at the target, with yaw stability
     * to prevent flipping when the camera is nearly directly above/below.
     */
    private void setStableDirection(Location cameraPos, Location targetEye) {
        Vector toTarget = targetEye.toVector().subtract(cameraPos.toVector());
        if (toTarget.lengthSquared() < 0.0001) return;

        double x = toTarget.getX();
        double y = toTarget.getY();
        double z = toTarget.getZ();

        double horizontalDist = Math.sqrt(x * x + z * z);
        double pitch = Math.toDegrees(-Math.atan2(y, horizontalDist));

        // When the camera is nearly vertical (|pitch| > 80°), yaw is unstable.
        // Use the last stable yaw to prevent rapid flipping.
        if (Math.abs(pitch) > 80.0) {
            cameraPos.setYaw(lastStableYaw);
        } else {
            double yaw = Math.toDegrees(Math.atan2(-x, z));
            lastStableYaw = (float) yaw;
            cameraPos.setYaw((float) yaw);
        }

        cameraPos.setPitch((float) pitch);
    }

    /**
     * Adjust camera position to avoid blocks between camera and target.
     * Uses iterative ray-tracing to find a position with clear line of sight.
     *
     * Algorithm:
     * 1. Start at the ideal orbit position.
     * 2. Ray-trace from the test position to the target.
     * 3. If no occlusion → return the position (clamped to maxDistance).
     * 4. If occluded → move the test position PAST the hit block toward target.
     * 5. Repeat until clear OR distance drops below minDistance.
     * 6. If below minDistance → try fallback positions (above, then sides).
     */
    private Location adjustForOcclusion(Location idealPos, Location targetEye, World world) {
        double minDist = config.getMinDistance();
        double maxDist = config.getMaxDistance();

        Location testPos = idealPos.clone();
        int maxIterations = 10;

        for (int iter = 0; iter < maxIterations; iter++) {
            double distToTarget = testPos.distance(targetEye);
            if (distToTarget < 0.01) return testPos;

            if (distToTarget < minDist) {
                // Too close to target — try fallback
                break;
            }

            Vector direction = targetEye.toVector().subtract(testPos.toVector()).normalize();

            RayTraceResult result = world.rayTraceBlocks(
                    testPos, direction, distToTarget,
                    FluidCollisionMode.NEVER, true
            );

            if (result == null || result.getHitBlock() == null) {
                // No occlusion! Clamp to max distance if needed
                if (distToTarget > maxDist) {
                    return targetEye.clone().add(direction.clone().multiply(-maxDist));
                }
                return testPos;
            }

            Location hitPos = result.getHitPosition().toLocation(world);
            double hitDist = testPos.distance(hitPos);

            if (hitDist < minDist) {
                // The blocking block is too close — try fallback
                break;
            }

            // Move camera PAST the hit block toward the target (0.5 blocks past)
            testPos = hitPos.clone().add(direction.clone().multiply(0.5));
        }

        // --- All attempts failed, try fallback positions ---

        // Fallback 1: above the target
        Location above = targetEye.clone();
        above.setY(targetEye.getY() + config.getOrbitHeight() + 2.0);

        if (above.getBlock().isPassable()) {
            Vector downDir = targetEye.toVector().subtract(above.toVector()).normalize();
            double aboveDist = above.distance(targetEye);
            RayTraceResult aboveResult = world.rayTraceBlocks(
                    above, downDir, aboveDist,
                    FluidCollisionMode.NEVER, true
            );
            if (aboveResult == null || aboveResult.getHitBlock() == null) {
                return above;
            }
        }

        // Fallback 2: try a few different angles (rotate 90°, 180°, 270°)
        for (int i = 1; i <= 3; i++) {
            double testAngle = orbitAngle + (Math.PI / 2.0) * i;
            double r = config.getOrbitRadius();
            double h = config.getOrbitHeight();
            double altX = orbitCenter.getX() + r * Math.cos(testAngle);
            double altY = orbitCenter.getY() + h;
            double altZ = orbitCenter.getZ() + r * Math.sin(testAngle);

            Location altPos = new Location(world, altX, altY, altZ);
            Vector altDir = targetEye.toVector().subtract(altPos.toVector()).normalize();
            double altDist = altPos.distance(targetEye);

            RayTraceResult altResult = world.rayTraceBlocks(
                    altPos, altDir, altDist,
                    FluidCollisionMode.NEVER, true
            );
            if (altResult == null || altResult.getHitBlock() == null) {
                if (altDist > maxDist) {
                    return targetEye.clone().add(altDir.clone().multiply(-maxDist));
                }
                return altPos;
            }
        }

        // Last resort: return the closest position we found (testPos)
        return testPos;
    }

    /**
     * Switch to the next target in the shuffled pool.
     */
    private void switchTarget(Player camera) {
        CameraTarget newTarget = targetManager.getNextTarget();
        if (newTarget == null) {
            if (currentTarget != null) {
                currentTarget = null;
            }
            return;
        }

        applyTarget(camera, newTarget);
        manager.getLogger().info("Camera → " + newTarget.getName()
                + " (" + (targetDurationTicks / 20) + "s)");
    }

    /**
     * Force-switch to a specific target (e.g., when a player joins).
     */
    public void forceSwitch(Player camera, CameraTarget target) {
        applyTarget(camera, target);
    }

    /**
     * Force immediate switch to the next target (for /camera skip).
     */
    public void forceNextTarget(Player camera) {
        switchTarget(camera);
    }

    /**
     * Common logic to apply a new target.
     */
    private void applyTarget(Player camera, CameraTarget target) {
        currentTarget = target;
        elapsedTicks = 0;

        int minTicks = config.getMinDuration() * 20;
        int maxTicks = config.getMaxDuration() * 20;
        targetDurationTicks = minTicks + random.nextInt(Math.max(1, maxTicks - minTicks + 1));

        Location targetLoc = target.getLocation();
        if (targetLoc != null) {
            orbitCenter = targetLoc.clone();
        }
        orbitAngle = random.nextDouble() * Math.PI * 2;
        lastStableYaw = (float) (random.nextDouble() * 360.0);

        // Cross-world teleport with chunk loading
        if (targetLoc != null && !camera.getWorld().equals(targetLoc.getWorld())) {
            manager.ensureChunkLoaded(targetLoc);
            try {
                camera.teleport(targetLoc);
            } catch (Exception ignored) {}
        }
    }

    public CameraTarget getCurrentTarget() {
        return currentTarget;
    }

    private Location findRescueLocation() {
        for (Location loc : config.getSpawnPoints().values()) {
            if (loc.getWorld() != null && config.getIdleWorlds().contains(loc.getWorld().getName())) {
                return loc.clone();
            }
        }
        for (World world : org.bukkit.Bukkit.getWorlds()) {
            if (config.getIdleWorlds().contains(world.getName())) {
                return world.getSpawnLocation();
            }
        }
        return null;
    }
}