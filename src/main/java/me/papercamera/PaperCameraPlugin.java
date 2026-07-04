package me.papercamera;

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
    }

    @Override
    public void onDisable() {
        if (cameraManager != null) {
            cameraManager.stop();
        }
        getLogger().info("PaperCamera disabled!");
    }
}