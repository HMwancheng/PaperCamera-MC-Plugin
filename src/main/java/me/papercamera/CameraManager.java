package me.papercamera;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.util.Iterator;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Central manager for the camera system.
 * Handles start/stop, player join/quit/world-change, and coordinates with CameraTask and TargetManager.
 */
public class CameraManager {

    private final JavaPlugin plugin;
    private final CameraConfig config;
    private final TargetManager targetManager;
    private CameraTask cameraTask;
    private BukkitTask bukkitTask;
    private boolean running = false;
    private boolean spawnPointsDiscovered = false;

    public CameraManager(JavaPlugin plugin, CameraConfig config) {
        this.plugin = plugin;
        this.config = config;
        this.targetManager = new TargetManager(config);
    }

    /**
     * Start the camera system. Returns false if camera player is not online.
     * If spawn-command is configured and not yet discovered, discovers spawn points first.
     */
    public boolean start() {
        return start(false);
    }

    /**
     * Start the camera system, optionally skipping spawn point discovery.
     */
    private boolean start(boolean skipDiscover) {
        if (running) return false;

        Player camera = getCameraPlayer();
        if (camera == null) {
            plugin.getLogger().warning("Cannot start: camera player '" + config.getCameraPlayerName() + "' is not online!");
            return false;
        }

        // Auto-discover spawn points before starting (only once, only if spawn-command is set)
        String spawnCmd = config.getSpawnCommand();
        if (!skipDiscover && !spawnPointsDiscovered && spawnCmd != null && !spawnCmd.isEmpty()) {
            plugin.getLogger().info("Auto-discovering spawn points before start...");
            discoverSpawnPoints(() -> {
                spawnPointsDiscovered = true;
                doStart();
            });
            return true;
        }

        doStart();
        return true;
    }

    private void doStart() {
        Player camera = getCameraPlayer();
        if (camera == null) return;

        // Ensure camera is in spectator mode
        if (camera.getGameMode() != GameMode.SPECTATOR) {
            camera.setGameMode(GameMode.SPECTATOR);
        }

        cameraTask = new CameraTask(this, config, targetManager);
        bukkitTask = Bukkit.getScheduler().runTaskTimer(plugin, cameraTask, 0L, 1L);
        running = true;
        plugin.getLogger().info("Camera system started!");
    }

    /**
     * Stop the camera system.
     */
    public boolean stop() {
        if (!running) return false;

        if (bukkitTask != null) {
            bukkitTask.cancel();
            bukkitTask = null;
        }
        cameraTask = null;
        running = false;
        plugin.getLogger().info("Camera system stopped!");
        return true;
    }

    public boolean isRunning() {
        return running;
    }

    /**
     * Reset the spawn discovery flag so the next start() will re-discover.
     */
    public void resetSpawnDiscovery() {
        spawnPointsDiscovered = false;
    }

    /**
     * Mark spawn points as discovered (call after manual discoverspawn).
     */
    public void markSpawnDiscovered() {
        spawnPointsDiscovered = true;
    }

    /**
     * Get the camera player entity, or null if not online.
     */
    public Player getCameraPlayer() {
        return Bukkit.getPlayer(config.getCameraPlayerName());
    }

    public TargetManager getTargetManager() {
        return targetManager;
    }

    public CameraTask getCameraTask() {
        return cameraTask;
    }

    public Logger getLogger() {
        return plugin.getLogger();
    }

    /**
     * Skip to the next target (for /camera skip command).
     */
    public void skipTarget() {
        if (cameraTask != null) {
            Player camera = getCameraPlayer();
            if (camera != null) {
                cameraTask.forceNextTarget(camera);
            }
        }
    }

    /**
     * Discover spawn points by executing the configured spawn command for each idle world.
     * The camera will be teleported via the command, and the resulting location is recorded.
     * Runs the callback on completion (null-safe).
     */
    public void discoverSpawnPoints() {
        discoverSpawnPoints(null);
    }

    /**
     * Discover spawn points with a callback that runs after all worlds are processed.
     */
    public void discoverSpawnPoints(Runnable onComplete) {
        String spawnCmd = config.getSpawnCommand();
        if (spawnCmd == null || spawnCmd.isEmpty()) {
            if (onComplete != null) onComplete.run();
            return;
        }

        Player camera = getCameraPlayer();
        if (camera == null) {
            plugin.getLogger().warning("Cannot discover spawn points: camera player is offline.");
            if (onComplete != null) onComplete.run();
            return;
        }

        Iterator<String> worldIter = config.getIdleWorlds().iterator();
        if (!worldIter.hasNext()) {
            if (onComplete != null) onComplete.run();
            return;
        }

        new BukkitRunnable() {
            @Override
            public void run() {
                if (!worldIter.hasNext()) {
                    plugin.getLogger().info("Spawn point discovery complete!");
                    targetManager.loadSpawnTargets();
                    cancel();
                    if (onComplete != null) onComplete.run();
                    return;
                }

                String worldName = worldIter.next();
                String fullCmd = spawnCmd + " " + worldName;
                plugin.getLogger().info("Discovering spawn for '" + worldName + "': executing /" + fullCmd);

                Bukkit.dispatchCommand(camera, fullCmd);

                // Wait for teleport to complete, then capture with retry
                new BukkitRunnable() {
                    int retries = 0;

                    @Override
                    public void run() {
                        Location loc = camera.getLocation();
                        if (loc != null && loc.getWorld() != null
                                && loc.getWorld().getName().equalsIgnoreCase(worldName)) {
                            // Successfully teleported to the target world
                            config.getSpawnPoints().put(worldName, loc.clone());
                            plugin.getLogger().info("Recorded spawn for '" + worldName
                                    + "': " + loc.getWorld().getName()
                                    + " (" + (int) loc.getX() + ", " + (int) loc.getY() + ", " + (int) loc.getZ() + ")");
                            saveSpawnPointsToConfig();
                            targetManager.loadSpawnTargets();
                            cancel();
                        } else if (retries < 5) {
                            retries++;
                            if (retries == 1) {
                                plugin.getLogger().info("Waiting for teleport to '" + worldName + "' to complete...");
                            }
                        } else {
                            // Max retries exceeded — record whatever we have
                            plugin.getLogger().warning("Teleport to '" + worldName
                                    + "' may not have completed. Current world: "
                                    + (loc != null && loc.getWorld() != null ? loc.getWorld().getName() : "unknown"));
                            if (loc != null && loc.getWorld() != null) {
                                config.getSpawnPoints().put(worldName, loc.clone());
                                saveSpawnPointsToConfig();
                            }
                            targetManager.loadSpawnTargets();
                            cancel();
                        }
                    }
                }.runTaskTimer(plugin, 20L, 20L); // check every 1 second, up to 5 seconds
            }
        }.runTaskTimer(plugin, 0L, 60L); // process one world every 3 seconds
    }

    /**
     * Handle the camera player joining.
     */
    public void handleCameraPlayerJoin(Player player) {
        player.setGameMode(GameMode.SPECTATOR);

        if (config.isAutoStart() && !running) {
            // Will auto-discover spawn points before starting if needed
            start();
        }
    }

    /**
     * Handle a regular player joining.
     */
    public void handlePlayerJoin(Player player) {
        if (config.isCameraPlayer(player.getName())) {
            handleCameraPlayerJoin(player);
            return;
        }

        targetManager.addPlayer(player);

        // If camera is running and currently on a spawn point, switch to the new player immediately
        if (running && cameraTask != null) {
            CameraTarget current = cameraTask.getCurrentTarget();
            if (current == null || current instanceof LocationTarget) {
                Player camera = getCameraPlayer();
                if (camera != null && camera.isOnline()) {
                    cameraTask.forceSwitch(camera, new PlayerTarget(player));
                    plugin.getLogger().info("Switching to newly joined player: " + player.getName());
                }
            }
        }
    }

    /**
     * Handle a player (regular or camera) quitting.
     */
    public void handlePlayerQuit(Player player) {
        if (config.isCameraPlayer(player.getName())) {
            if (running) {
                stop();
                plugin.getLogger().warning("Camera player disconnected! Camera stopped.");
            }
            return;
        }

        targetManager.removePlayer(player);
    }

    /**
     * Handle a player changing worlds.
     */
    public void handlePlayerWorldChange(Player player) {
        if (config.isCameraPlayer(player.getName())) {
            // Camera itself changed world — no restriction, allow following players anywhere
            return;
        }

        targetManager.handlePlayerWorldChange(player);
    }

    /**
     * Ensure the chunk at the given location is loaded before teleporting.
     */
    public void ensureChunkLoaded(Location location) {
        World world = location.getWorld();
        if (world == null) return;
        int cx = location.getBlockX() >> 4;
        int cz = location.getBlockZ() >> 4;
        if (!world.isChunkLoaded(cx, cz)) {
            world.getChunkAt(cx, cz); // synchronous load
        }
    }

    /**
     * Teleport the camera to a target world using spawn-command if configured,
     * falling back to Bukkit teleport. Call this when switching to a spawn point
     * in a different world.
     */
    public void teleportCameraToWorld(String worldName) {
        Player camera = getCameraPlayer();
        if (camera == null) return;

        // Already in the target world
        if (camera.getWorld().getName().equalsIgnoreCase(worldName)) return;

        String spawnCmd = config.getSpawnCommand();
        if (spawnCmd != null && !spawnCmd.isEmpty()) {
            // Use spawn-command (e.g., "mv tp world")
            String fullCmd = spawnCmd + " " + worldName;
            plugin.getLogger().info("Teleporting camera to '" + worldName + "' via /" + fullCmd);
            Bukkit.dispatchCommand(camera, fullCmd);
        } else {
            // Fallback: use Bukkit teleport
            World world = Bukkit.getWorld(worldName);
            if (world != null) {
                ensureChunkLoaded(world.getSpawnLocation());
                try { camera.teleport(world.getSpawnLocation()); } catch (Exception ignored) {}
            }
        }
    }

    /**
     * Persist discovered spawn points to the config.yml file.
     */
    private void saveSpawnPointsToConfig() {
        File configFile = new File(plugin.getDataFolder(), "config.yml");
        FileConfiguration cfg = plugin.getConfig();

        for (Map.Entry<String, Location> entry : config.getSpawnPoints().entrySet()) {
            String key = entry.getKey();
            Location loc = entry.getValue();
            cfg.set("spawn-points." + key + ".world", loc.getWorld().getName());
            cfg.set("spawn-points." + key + ".x", loc.getX());
            cfg.set("spawn-points." + key + ".y", loc.getY());
            cfg.set("spawn-points." + key + ".z", loc.getZ());
        }

        try {
            cfg.save(configFile);
        } catch (IOException e) {
            plugin.getLogger().warning("Failed to save spawn points to config: " + e.getMessage());
        }
    }

}