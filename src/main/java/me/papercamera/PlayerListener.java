package me.papercamera;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * Listens for player join/quit/world-change events to update the target pool and camera state.
 */
public class PlayerListener implements Listener {

    private final CameraManager cameraManager;
    private final CameraConfig config;

    public PlayerListener(CameraManager cameraManager, CameraConfig config) {
        this.cameraManager = cameraManager;
        this.config = config;
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        cameraManager.handlePlayerJoin(event.getPlayer());
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        cameraManager.handlePlayerQuit(event.getPlayer());
    }

    @EventHandler
    public void onPlayerChangedWorld(PlayerChangedWorldEvent event) {
        cameraManager.handlePlayerWorldChange(event.getPlayer());
    }
}