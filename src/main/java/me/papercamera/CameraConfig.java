package me.papercamera;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.*;

public class CameraConfig {

    private final JavaPlugin plugin;

    private String cameraPlayerName;
    private boolean autoStart;
    private double orbitRadius;
    private double orbitHeight;
    private double orbitSpeed;
    private int minDuration;
    private int maxDuration;
    private double lerpFactor;
    private double maxMovePerTick;
    private double minDistance;
    private double maxDistance;
    private int occlusionTimeoutTicks;
    private double transitionLerp;
    private boolean cameraSmoothingEnabled;
    private double cameraSmoothingLerp;
    private List<String> idleWorlds;
    private String spawnCommand;
    private final Map<String, Location> spawnPoints = new LinkedHashMap<>();

    public CameraConfig(JavaPlugin plugin) {
        this.plugin = plugin;
        load();
    }

    public void load() {
        plugin.reloadConfig();
        FileConfiguration cfg = plugin.getConfig();

        cameraPlayerName = cfg.getString("camera-player-name", "camera");
        autoStart = cfg.getBoolean("auto-start", true);
        orbitRadius = cfg.getDouble("orbit.radius", 8.0);
        orbitHeight = cfg.getDouble("orbit.height", 2.5);
        orbitSpeed = cfg.getDouble("orbit.speed", 0.005);
        minDuration = cfg.getInt("follow.min-duration", 15);
        maxDuration = cfg.getInt("follow.max-duration", 30);
        lerpFactor = Math.max(0.0, Math.min(1.0, cfg.getDouble("follow.lerp-factor", 0.05)));
        maxMovePerTick = Math.max(0.01, cfg.getDouble("follow.max-move-per-tick", 0.25));
        minDistance = cfg.getDouble("occlusion.min-distance", 5.0);
        maxDistance = cfg.getDouble("occlusion.max-distance", 32.0);
        occlusionTimeoutTicks = Math.max(0, cfg.getInt("occlusion.timeout-ticks", 40));
        transitionLerp = Math.max(0.01, Math.min(1.0, cfg.getDouble("occlusion.transition-lerp", 0.15)));
        cameraSmoothingEnabled = cfg.getBoolean("camera-smoothing.enabled", true);
        cameraSmoothingLerp = Math.max(0.01, Math.min(1.0, cfg.getDouble("camera-smoothing.lerp-factor", 0.35)));
        idleWorlds = cfg.getStringList("idle-worlds");
        spawnCommand = cfg.getString("spawn-command", "");

        // Validate durations
        if (minDuration > maxDuration) {
            plugin.getLogger().warning("follow.min-duration (" + minDuration
                    + ") > follow.max-duration (" + maxDuration + "), swapping them.");
            int tmp = minDuration;
            minDuration = maxDuration;
            maxDuration = tmp;
        }

        // Validate distances
        if (minDistance >= maxDistance) {
            plugin.getLogger().warning("occlusion.min-distance (" + minDistance
                    + ") >= occlusion.max-distance (" + maxDistance
                    + "), adjusting min-distance to " + (maxDistance - 1.0));
            minDistance = Math.max(1.0, maxDistance - 1.0);
        }

        spawnPoints.clear();
        if (cfg.isConfigurationSection("spawn-points")) {
            for (String key : cfg.getConfigurationSection("spawn-points").getKeys(false)) {
                String worldName = cfg.getString("spawn-points." + key + ".world");
                if (worldName == null) {
                    plugin.getLogger().warning("Spawn point '" + key + "' missing world name, skipping.");
                    continue;
                }
                World world = Bukkit.getWorld(worldName);
                if (world == null) {
                    plugin.getLogger().warning("World '" + worldName + "' not found for spawn point '" + key + "', skipping.");
                    continue;
                }
                double x = cfg.getDouble("spawn-points." + key + ".x", 0.0);
                double y = cfg.getDouble("spawn-points." + key + ".y", 64.0);
                double z = cfg.getDouble("spawn-points." + key + ".z", 0.0);
                spawnPoints.put(key, new Location(world, x, y, z));
            }
        }

        plugin.getLogger().info("Loaded " + spawnPoints.size() + " spawn points across " + idleWorlds.size() + " configured worlds.");
    }

    /** Check if a player name matches the camera player name (case-insensitive). */
    public boolean isCameraPlayer(String playerName) {
        return playerName.equalsIgnoreCase(cameraPlayerName);
    }

    public String getCameraPlayerName() { return cameraPlayerName; }
    public boolean isAutoStart() { return autoStart; }
    public double getOrbitRadius() { return orbitRadius; }
    public double getOrbitHeight() { return orbitHeight; }
    public double getOrbitSpeed() { return orbitSpeed; }
    public int getMinDuration() { return minDuration; }
    public int getMaxDuration() { return maxDuration; }
    public double getLerpFactor() { return lerpFactor; }
    public double getMaxMovePerTick() { return maxMovePerTick; }
    public double getMinDistance() { return minDistance; }
    public double getMaxDistance() { return maxDistance; }
    public int getOcclusionTimeoutTicks() { return occlusionTimeoutTicks; }
    public double getTransitionLerp() { return transitionLerp; }
    public boolean isCameraSmoothingEnabled() { return cameraSmoothingEnabled; }
    public double getCameraSmoothingLerp() { return cameraSmoothingLerp; }
    public List<String> getIdleWorlds() { return idleWorlds; }
    public String getSpawnCommand() { return spawnCommand; }
    public Map<String, Location> getSpawnPoints() { return spawnPoints; }
}