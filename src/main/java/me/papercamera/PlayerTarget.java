package me.papercamera;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.util.UUID;

/**
 * A camera target that wraps an online player.
 */
public class PlayerTarget implements CameraTarget {

    private final UUID playerId;
    private final String playerName;

    public PlayerTarget(Player player) {
        this.playerId = player.getUniqueId();
        this.playerName = player.getName();
    }

    public UUID getPlayerId() {
        return playerId;
    }

    private Player getPlayer() {
        return org.bukkit.Bukkit.getPlayer(playerId);
    }

    @Override
    public Location getLocation() {
        Player p = getPlayer();
        return p != null ? p.getLocation() : null;
    }

    @Override
    public String getName() {
        return playerName;
    }

    @Override
    public World getWorld() {
        Player p = getPlayer();
        return p != null ? p.getWorld() : null;
    }

    @Override
    public boolean isValid() {
        Player p = getPlayer();
        return p != null && p.isOnline();
    }

    @Override
    public boolean equals(Object obj) {
        if (obj instanceof PlayerTarget other) {
            return playerId.equals(other.playerId);
        }
        return false;
    }

    @Override
    public int hashCode() {
        return playerId.hashCode();
    }
}