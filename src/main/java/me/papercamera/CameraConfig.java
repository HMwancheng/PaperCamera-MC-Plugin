package me.papercamera;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.*;
import java.util.*;

public class CameraConfig {

    private static final int CURRENT_CONFIG_VERSION = 4;

    private final JavaPlugin plugin;

    private String cameraPlayerName;
    private boolean autoStart;
    private int spawnDiscoveryDelay;
    private double orbitRadius;
    private double orbitHeight;
    private double orbitSpeed;
    private int minDuration;
    private int maxDuration;
    private double lerpFactor;
    private double maxMovePerTick;
    private double teleportThreshold;
    private double minDistance;
    private double maxDistance;
    private int occlusionTimeoutTicks;
    private double transitionLerp;
    private boolean cameraSmoothingEnabled;
    private double cameraSmoothingLerp;
    private double directionLerpFactor;
    private List<String> idleWorlds;
    private String primaryWorld;
    private double spawnWeight;
    private String spawnCommand;
    private final Map<String, Location> spawnPoints = new LinkedHashMap<>();

    public CameraConfig(JavaPlugin plugin) {
        this.plugin = plugin;
        load();
    }

    public void load() {
        plugin.reloadConfig();
        FileConfiguration cfg = plugin.getConfig();

        // --- Config migration: merge new keys from default config ---
        int userVersion = cfg.getInt("config-version", 0);
        if (userVersion < CURRENT_CONFIG_VERSION) {
            mergeNewConfigKeys();
            plugin.reloadConfig();
            cfg = plugin.getConfig();
            plugin.getLogger().info("Config migrated from version " + userVersion + " to " + CURRENT_CONFIG_VERSION);
        }

        cameraPlayerName = cfg.getString("camera-player-name", "camera");
        autoStart = cfg.getBoolean("auto-start", true);
        spawnDiscoveryDelay = Math.max(0, cfg.getInt("spawn-discovery-delay", 5));
        orbitRadius = cfg.getDouble("orbit.radius", 8.0);
        orbitHeight = cfg.getDouble("orbit.height", 2.5);
        orbitSpeed = cfg.getDouble("orbit.speed", 0.005);
        minDuration = cfg.getInt("follow.min-duration", 15);
        maxDuration = cfg.getInt("follow.max-duration", 30);
        lerpFactor = Math.max(0.0, Math.min(1.0, cfg.getDouble("follow.lerp-factor", 0.05)));
        maxMovePerTick = Math.max(0.01, cfg.getDouble("follow.max-move-per-tick", 0.25));
        teleportThreshold = Math.max(1.0, cfg.getDouble("follow.teleport-threshold", 60.0));
        minDistance = cfg.getDouble("occlusion.min-distance", 3.5);
        maxDistance = cfg.getDouble("occlusion.max-distance", 24.0);
        occlusionTimeoutTicks = Math.max(0, cfg.getInt("occlusion.timeout-ticks", 40));
        transitionLerp = Math.max(0.01, Math.min(1.0, cfg.getDouble("occlusion.transition-lerp", 0.15)));
        cameraSmoothingEnabled = cfg.getBoolean("camera-smoothing.enabled", true);
        cameraSmoothingLerp = Math.max(0.01, Math.min(1.0, cfg.getDouble("camera-smoothing.lerp-factor", 0.35)));
        directionLerpFactor = Math.max(0.01, Math.min(1.0, cfg.getDouble("camera-smoothing.direction-lerp-factor", 0.10)));
        idleWorlds = cfg.getStringList("idle-worlds");
        // Filter out empty strings from idle-worlds
        idleWorlds.removeIf(String::isEmpty);
        primaryWorld = cfg.getString("primary-world", "");
        if (primaryWorld.isEmpty() && !idleWorlds.isEmpty()) {
            primaryWorld = idleWorlds.get(0);
        }
        spawnWeight = Math.max(0.0, Math.min(1.0, cfg.getDouble("spawn-weight", 0.3)));
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
                // Only load spawn points for worlds in the idle-worlds list
                if (!idleWorlds.contains(worldName)) {
                    plugin.getLogger().info("Spawn point '" + key + "' world '" + worldName + "' not in idle-worlds, skipping.");
                    continue;
                }
                World world = Bukkit.getWorld(worldName);
                if (world == null) {
                    plugin.getLogger().warning("World '" + worldName + "' not loaded yet for spawn point '" + key + "'. It will be available when the world loads.");
                    continue;
                }
                double x = cfg.getDouble("spawn-points." + key + ".x", 0.0);
                double y = cfg.getDouble("spawn-points." + key + ".y", 64.0);
                double z = cfg.getDouble("spawn-points." + key + ".z", 0.0);
                spawnPoints.put(key, new Location(world, x, y, z));
            }
        }

        plugin.getLogger().info("Loaded " + spawnPoints.size() + " spawn points. Idle worlds: " + idleWorlds);
    }

    /**
     * Merge new keys from the default config (inside the jar) into the user's existing config.yml.
     * Existing user values are preserved; only missing keys are added.
     * Then the config-version is updated to the current version.
     */
    private void mergeNewConfigKeys() {
        File configFile = new File(plugin.getDataFolder(), "config.yml");
        if (!configFile.exists()) return;

        try (InputStream defaultStream = plugin.getResource("config.yml")) {
            if (defaultStream == null) return;

            YamlConfiguration defaultCfg = YamlConfiguration.loadConfiguration(new InputStreamReader(defaultStream));
            YamlConfiguration userCfg = YamlConfiguration.loadConfiguration(configFile);

            boolean changed = false;
            changed |= mergeSection(userCfg, defaultCfg);

            // Update the version
            userCfg.set("config-version", CURRENT_CONFIG_VERSION);
            changed = true;

            if (changed) {
                userCfg.save(configFile);
                plugin.getLogger().info("config.yml has been updated with new options.");
            }
        } catch (IOException e) {
            plugin.getLogger().warning("Failed to merge config: " + e.getMessage());
        }
    }

    /**
     * Recursively merge default keys into user config.
     */
    private boolean mergeSection(ConfigurationSection user, ConfigurationSection defaults) {
        boolean changed = false;
        for (String key : defaults.getKeys(false)) {
            if (!user.contains(key)) {
                // Key doesn't exist in user config — add it
                user.set(key, defaults.get(key));
                changed = true;
            } else if (defaults.isConfigurationSection(key) && user.isConfigurationSection(key)) {
                // Both are sections — recurse
                changed |= mergeSection(user.getConfigurationSection(key), defaults.getConfigurationSection(key));
            }
            // If key exists in user but is a different type, keep user's value (don't overwrite)
        }
        return changed;
    }

    /** Check if a player name matches the camera player name (case-insensitive). */
    public boolean isCameraPlayer(String playerName) {
        return playerName.equalsIgnoreCase(cameraPlayerName);
    }

    public String getCameraPlayerName() { return cameraPlayerName; }
    public boolean isAutoStart() { return autoStart; }
    public int getSpawnDiscoveryDelay() { return spawnDiscoveryDelay; }
    public double getOrbitRadius() { return orbitRadius; }
    public double getOrbitHeight() { return orbitHeight; }
    public double getOrbitSpeed() { return orbitSpeed; }
    public int getMinDuration() { return minDuration; }
    public int getMaxDuration() { return maxDuration; }
    public double getLerpFactor() { return lerpFactor; }
    public double getMaxMovePerTick() { return maxMovePerTick; }
    public double getTeleportThreshold() { return teleportThreshold; }
    public double getMinDistance() { return minDistance; }
    public double getMaxDistance() { return maxDistance; }
    public int getOcclusionTimeoutTicks() { return occlusionTimeoutTicks; }
    public double getTransitionLerp() { return transitionLerp; }
    public boolean isCameraSmoothingEnabled() { return cameraSmoothingEnabled; }
    public double getCameraSmoothingLerp() { return cameraSmoothingLerp; }
    public double getDirectionLerpFactor() { return directionLerpFactor; }
    public List<String> getIdleWorlds() { return idleWorlds; }
    public String getPrimaryWorld() { return primaryWorld; }
    public double getSpawnWeight() { return spawnWeight; }
    public String getSpawnCommand() { return spawnCommand; }
    public Map<String, Location> getSpawnPoints() { return spawnPoints; }
}