package me.papercamera;

import org.bukkit.Location;
import org.bukkit.World;

/**
 * Represents a target that the camera can orbit around.
 */
public interface CameraTarget {

    /** Get the current location of the target. */
    Location getLocation();

    /** A human-readable name for display. */
    String getName();

    /** The world the target is in. */
    World getWorld();

    /** Whether this target is still valid (e.g., player still online). */
    boolean isValid();
}