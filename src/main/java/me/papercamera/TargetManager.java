package me.papercamera;

import org.bukkit.entity.Player;

import java.util.*;

/**
 * Manages the pool of camera targets (players + spawn points).
 * Uses pseudo-random shuffling: all targets are visited once before repeating,
 * with no back-to-back repeats across shuffle cycles.
 * When players are online, non-primary spawn points appear with reduced frequency.
 */
public class TargetManager {

    private final CameraConfig config;
    private final Set<PlayerTarget> playerTargets = new LinkedHashSet<>();
    private final List<LocationTarget> spawnTargets = new ArrayList<>();
    private final List<CameraTarget> shufflePool = new ArrayList<>();
    private int shuffleIndex = 0;
    private final Random random = new Random();
    /** Last target that was returned, to avoid back-to-back repeats. */
    private CameraTarget lastTarget;

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
     */
    public void handlePlayerWorldChange(Player player) {
        if (config.isCameraPlayer(player.getName())) return;
    }

    /**
     * Get the next target in the shuffled pool.
     * Ensures all targets are visited once before repeating, and no back-to-back repeats.
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

        if (!target.isValid()) {
            return getNextTarget();
        }

        lastTarget = target;
        return target;
    }

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

        // Add all valid player targets
        for (PlayerTarget pt : playerTargets) {
            if (pt.isValid()) {
                targets.add(pt);
            }
        }

        // Add spawn targets with weighting
        boolean hasPlayers = !targets.isEmpty();
        String primaryWorld = config.getPrimaryWorld();
        double weight = config.getSpawnWeight();

        for (LocationTarget st : spawnTargets) {
            boolean isPrimary = st.getName().equals(primaryWorld)
                    || (st.getWorld() != null && st.getWorld().getName().equals(primaryWorld));

            if (hasPlayers && !isPrimary) {
                if (random.nextDouble() < weight) {
                    targets.add(st);
                }
            } else {
                targets.add(st);
            }
        }

        return targets;
    }

    /**
     * Rebuild the shuffle pool with Fisher-Yates shuffle.
     * Ensures the first element is not the same as lastTarget (no back-to-back repeats).
     * Only enforces this when there are at least 2 targets.
     */
    private void rebuildShufflePool() {
        List<CameraTarget> valid = buildValidTargets();
        int n = valid.size();

        shufflePool.clear();
        shufflePool.addAll(valid);

        if (n <= 1) {
            shuffleIndex = 0;
            return;
        }

        // Fisher-Yates shuffle
        for (int i = n - 1; i > 0; i--) {
            int j = random.nextInt(i + 1);
            Collections.swap(shufflePool, i, j);
        }

        // Avoid back-to-back repeat: if first element matches lastTarget, swap with another
        if (lastTarget != null && n >= 2) {
            CameraTarget first = shufflePool.get(0);
            if (isSameTarget(first, lastTarget)) {
                // Swap with a random position from 1..n-1
                int swapIdx = 1 + random.nextInt(n - 1);
                Collections.swap(shufflePool, 0, swapIdx);
            }
        }

        shuffleIndex = 0;
    }

    /**
     * Check if two targets refer to the same entity.
     */
    private boolean isSameTarget(CameraTarget a, CameraTarget b) {
        if (a == null || b == null) return false;
        if (a instanceof PlayerTarget && b instanceof PlayerTarget) {
            return ((PlayerTarget) a).getPlayerId().equals(((PlayerTarget) b).getPlayerId());
        }
        if (a instanceof LocationTarget && b instanceof LocationTarget) {
            return a.getName().equals(b.getName());
        }
        return false;
    }
}