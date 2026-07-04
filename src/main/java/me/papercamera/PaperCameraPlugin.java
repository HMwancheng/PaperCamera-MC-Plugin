package me.papercamera;

import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

public final class PaperCameraPlugin extends JavaPlugin {

    private CameraManager cameraManager;
    private CameraConfig cameraConfig;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        cameraConfig = new CameraConfig(this);
        cameraManager = new CameraManager(this, cameraConfig);

        CameraCommand cmd = new CameraCommand(cameraManager, cameraConfig);
        getCommand("camera").setExecutor(cmd);
        getCommand("camera").setTabCompleter(cmd);

        getServer().getPluginManager().registerEvents(new PlayerListener(cameraManager, cameraConfig), this);

        getLogger().info("PaperCamera enabled! Camera player: " + cameraConfig.getCameraPlayerName());

        // Auto-discover spawn points if a spawn-command is configured and camera is online
        // Delay 2 seconds to allow the server to finish loading
        if (cameraConfig.getSpawnCommand() != null && !cameraConfig.getSpawnCommand().isEmpty()) {
            Bukkit.getScheduler().runTaskLater(this, () -> {
                if (cameraManager.getCameraPlayer() != null) {
                    getLogger().info("Auto-discovering spawn points on startup...");
                    cameraManager.discoverSpawnPoints();
                } else {
                    getLogger().info("Camera player not online, skipping auto-discover. Run /camera discoverspawn later.");
                }
            }, 40L); // 2 seconds delay
        }
    }

    @Override
    public void onDisable() {
        if (cameraManager != null) {
            cameraManager.stop();
        }
        getLogger().info("PaperCamera disabled!");
    }
}