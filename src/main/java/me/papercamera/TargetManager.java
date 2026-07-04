package me.papercamera;

import org.bukkit.entity.Player;

import java.util.*;

/**
 * Manages the pool of camera targets (players + spawn points).
 * Provides random selection with no-back-to-back repeats.
 */
public class TargetManager {

    private final CameraConfig config;
    private final Set<PlayerTarget> playerTargets = new LinkedHashSet<>();
    private final List<LocationTarget> spawnTargets = new ArrayList<>();
    private final List<CameraTarget> shufflePool = new ArrayList<>();
    private int shuffleIndex = 0;
    private final Random random = new Random();

    public TargetManager(CameraConfig config) {
        this.config = config;
        loadSpawnTargets();
    }

    public void loadSpawnTargets() {
        spawnTargets.clear();
        for (Map.Entry<String, org.bukkit.Location> entry : config.getSpawnPoints().entrySet()) {
            spawnTargets.add(new LocationTarget(entry.getValue(), entry.getKey()));
        }
    }

    /**
     * Add a player to the target pool (any world, including nether/end).
     */
    public void addPlayer(Player player) {
        if (config.isCameraPlayer(player.getName())) return;
        playerTargets.add(new PlayerTarget(player));
        rebuildShufflePool();
    }

    /**
     * Remove a player from the target pool by UUID.
     */
    public void removePlayer(Player player) {
        if (config.isCameraPlayer(player.getName())) return;
        playerTargets.removeIf(pt -> pt.getPlayerId().equals(player.getUniqueId()));
        rebuildShufflePool();
    }

    /**
     * Handle a player changing worlds.
     * Players are always kept in the pool regardless of world.
     */
    public void handlePlayerWorldChange(Player player) {
        if (config.isCameraPlayer(player.getName())) return;
        // Always keep the player in the pool — they can be followed anywhere
        // No need to add/remove based on world
    }

    /**
     * Get the next target in the shuffled pool.
     * Returns null if no valid targets exist.
     */
    public CameraTarget getNextTarget() {
        // Clean up invalid targets
        playerTargets.removeIf(pt -> !pt.isValid());

        List<CameraTarget> valid = buildValidTargets();
        if (valid.isEmpty()) return null;

        if (shufflePool.isEmpty() || shuffleIndex >= shufflePool.size()) {
            rebuildShufflePool();
        }

        if (shufflePool.isEmpty()) return null;

        CameraTarget target = shufflePool.get(shuffleIndex++);

        // Verify it's still valid
        if (!target.isValid()) {
            return getNextTarget(); // recurse
        }

        return target;
    }

    /**
     * Check if there are any valid targets in the pool.
     */
    public boolean hasTargets() {
        return !buildValidTargets().isEmpty();
    }

    /**
     * Get the number of valid online player targets.
     */
    public int getPlayerCount() {
        playerTargets.removeIf(pt -> !pt.isValid());
        return playerTargets.size();
    }

    private List<CameraTarget> buildValidTargets() {
        List<CameraTarget> targets = new ArrayList<>();

        // Add all valid player targets (any world, including nether/end)
        for (PlayerTarget pt : playerTargets) {
            if (pt.isValid()) {
                targets.add(pt);
            }
        }

        // Add spawn targets (always valid if world is loaded)
        targets.addAll(spawnTargets);

        return targets;
    }

    private void rebuildShufflePool() {
        shufflePool.clear();
        shufflePool.addAll(buildValidTargets());
        Collections.shuffle(shufflePool, random);
        shuffleIndex = 0;
    }
}