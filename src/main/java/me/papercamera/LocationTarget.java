package me.papercamera;

import org.bukkit.Location;
import org.bukkit.World;

/**
 * A static camera target at a fixed location (e.g., world spawn, main city).
 */
public class LocationTarget implements CameraTarget {

    private final Location location;
    private final String name;

    public LocationTarget(Location location, String name) {
        this.location = location.clone();
        this.name = name;
    }

    @Override
    public Location getLocation() {
        return location.clone();
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public World getWorld() {
        return location.getWorld();
    }

    @Override
    public boolean isValid() {
        return location.getWorld() != null;
    }

    @Override
    public boolean equals(Object obj) {
        if (obj instanceof LocationTarget other) {
            return name.equals(other.name);
        }
        return false;
    }

    @Override
    public int hashCode() {
        return name.hashCode();
    }
}