package me.papercamera;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.util.Iterator;
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

    public CameraManager(JavaPlugin plugin, CameraConfig config) {
        this.plugin = plugin;
        this.config = config;
        this.targetManager = new TargetManager(config);
    }

    /**
     * Start the camera system. Returns false if camera player is not online.
     */
    public boolean start() {
        if (running) return false;

        Player camera = getCameraPlayer();
        if (camera == null) {
            plugin.getLogger().warning("Cannot start: camera player '" + config.getCameraPlayerName() + "' is not online!");
            return false;
        }

        // Ensure camera is in spectator mode
        if (camera.getGameMode() != GameMode.SPECTATOR) {
            camera.setGameMode(GameMode.SPECTATOR);
        }

        // Ensure camera is in a valid world
        if (!config.getIdleWorlds().contains(camera.getWorld().getName())) {
            Location rescue = findRescueLocation();
            if (rescue != null) {
                ensureChunkLoaded(rescue);
                camera.teleport(rescue);
                plugin.getLogger().info("Camera was in invalid world, teleported to rescue location.");
            }
        }

        cameraTask = new CameraTask(this, config, targetManager);
        bukkitTask = Bukkit.getScheduler().runTaskTimer(plugin, cameraTask, 0L, 1L);
        running = true;
        plugin.getLogger().info("Camera system started!");
        return true;
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
     */
    public void discoverSpawnPoints() {
        String spawnCmd = config.getSpawnCommand();
        if (spawnCmd == null || spawnCmd.isEmpty()) {
            return;
        }

        Player camera = getCameraPlayer();
        if (camera == null) {
            plugin.getLogger().warning("Cannot discover spawn points: camera player is offline.");
            return;
        }

        Iterator<String> worldIter = config.getIdleWorlds().iterator();
        if (!worldIter.hasNext()) return;

        new BukkitRunnable() {
            @Override
            public void run() {
                if (!worldIter.hasNext()) {
                    plugin.getLogger().info("Spawn point discovery complete!");
                    targetManager.loadSpawnTargets();
                    cancel();
                    return;
                }

                String worldName = worldIter.next();
                String fullCmd = spawnCmd + " " + worldName;
                plugin.getLogger().info("Discovering spawn for '" + worldName + "': executing /" + fullCmd);

                // Execute the command on the camera player
                Bukkit.dispatchCommand(camera, fullCmd);

                // Schedule a delayed check to capture the location after teleport
                new BukkitRunnable() {
                    @Override
                    public void run() {
                        Location loc = camera.getLocation();
                        if (loc != null && loc.getWorld() != null) {
                            config.getSpawnPoints().put(worldName, loc.clone());
                            plugin.getLogger().info("Recorded spawn for '" + worldName
                                    + "': " + loc.getWorld().getName()
                                    + " (" + (int) loc.getX() + ", " + (int) loc.getY() + ", " + (int) loc.getZ() + ")");
                        }
                        targetManager.loadSpawnTargets();
                    }
                }.runTaskLater(plugin, 10L); // wait 10 ticks (0.5s) for teleport to complete
            }
        }.runTaskTimer(plugin, 0L, 20L); // process one world per second
    }

    /**
     * Handle the camera player joining.
     */
    public void handleCameraPlayerJoin(Player player) {
        player.setGameMode(GameMode.SPECTATOR);

        if (config.isAutoStart() && !running) {
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
            if ((current == null || current instanceof LocationTarget)
                    && config.getIdleWorlds().contains(player.getWorld().getName())) {
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
            // Camera itself changed world — check if it landed in a valid world
            if (running && !config.getIdleWorlds().contains(player.getWorld().getName())) {
                Location rescue = findRescueLocation();
                if (rescue != null) {
                    ensureChunkLoaded(rescue);
                    player.teleport(rescue);
                    plugin.getLogger().warning("Camera entered invalid world, teleported back.");
                }
            }
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
     * Find a rescue location (first available spawn point in a valid world).
     */
    private Location findRescueLocation() {
        for (Location loc : config.getSpawnPoints().values()) {
            if (loc.getWorld() != null && config.getIdleWorlds().contains(loc.getWorld().getName())) {
                return loc.clone();
            }
        }
        // Last resort: first loaded world
        for (World world : Bukkit.getWorlds()) {
            if (config.getIdleWorlds().contains(world.getName())) {
                return world.getSpawnLocation();
            }
        }
        return null;
    }
}