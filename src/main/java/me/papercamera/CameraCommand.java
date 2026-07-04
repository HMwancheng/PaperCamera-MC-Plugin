package me.papercamera;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

/**
 * Handles /camera commands with permission check and tab completion.
 */
public class CameraCommand implements CommandExecutor, TabCompleter {

    private static final String PERMISSION = "papercamera.admin";
    private static final List<String> SUBCOMMANDS = List.of("start", "stop", "status", "reload", "skip");

    private final CameraManager cameraManager;
    private final CameraConfig config;

    public CameraCommand(CameraManager cameraManager, CameraConfig config) {
        this.cameraManager = cameraManager;
        this.config = config;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission(PERMISSION)) {
            sender.sendMessage(Component.text("You don't have permission to use this command!", NamedTextColor.RED));
            return true;
        }

        if (args.length == 0) {
            sendUsage(sender);
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "start" -> handleStart(sender);
            case "stop" -> handleStop(sender);
            case "status" -> handleStatus(sender);
            case "reload" -> handleReload(sender);
            case "skip" -> handleSkip(sender);
            default -> sendUsage(sender);
        }

        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission(PERMISSION)) {
            return List.of();
        }

        if (args.length == 1) {
            List<String> matches = new ArrayList<>();
            String prefix = args[0].toLowerCase();
            for (String sub : SUBCOMMANDS) {
                if (sub.startsWith(prefix)) {
                    matches.add(sub);
                }
            }
            return matches;
        }

        return List.of();
    }

    private void handleStart(CommandSender sender) {
        if (cameraManager.isRunning()) {
            sender.sendMessage(Component.text("Camera is already running!", NamedTextColor.YELLOW));
            return;
        }
        if (cameraManager.start()) {
            sender.sendMessage(Component.text("Camera system started!", NamedTextColor.GREEN));
        } else {
            sender.sendMessage(Component.text(
                    "Failed to start! Is '" + config.getCameraPlayerName() + "' online?",
                    NamedTextColor.RED));
        }
    }

    private void handleStop(CommandSender sender) {
        if (!cameraManager.isRunning()) {
            sender.sendMessage(Component.text("Camera is not running!", NamedTextColor.YELLOW));
            return;
        }
        cameraManager.stop();
        sender.sendMessage(Component.text("Camera system stopped!", NamedTextColor.GREEN));
    }

    private void handleStatus(CommandSender sender) {
        sender.sendMessage(Component.text("=== Camera Status ===", NamedTextColor.GOLD));

        boolean running = cameraManager.isRunning();
        sender.sendMessage(Component.text("Running: ", NamedTextColor.WHITE)
                .append(Component.text(running ? "Yes" : "No",
                        running ? NamedTextColor.GREEN : NamedTextColor.RED)));

        sender.sendMessage(Component.text("Camera Player: ", NamedTextColor.WHITE)
                .append(Component.text(config.getCameraPlayerName(), NamedTextColor.AQUA)));

        Player camera = cameraManager.getCameraPlayer();
        sender.sendMessage(Component.text("Camera Online: ", NamedTextColor.WHITE)
                .append(Component.text(
                        (camera != null && camera.isOnline()) ? "Yes" : "No",
                        (camera != null && camera.isOnline()) ? NamedTextColor.GREEN : NamedTextColor.RED)));

        if (running && cameraManager.getCameraTask() != null) {
            CameraTarget target = cameraManager.getCameraTask().getCurrentTarget();
            if (target != null) {
                sender.sendMessage(Component.text("Current Target: ", NamedTextColor.WHITE)
                        .append(Component.text(target.getName(), NamedTextColor.AQUA)));
            } else {
                sender.sendMessage(Component.text("Current Target: ", NamedTextColor.WHITE)
                        .append(Component.text("None", NamedTextColor.GRAY)));
            }
        }

        sender.sendMessage(Component.text("Players in queue: ", NamedTextColor.WHITE)
                .append(Component.text(cameraManager.getTargetManager().getPlayerCount(), NamedTextColor.AQUA)));

        sender.sendMessage(Component.text("Configured worlds: ", NamedTextColor.WHITE)
                .append(Component.text(String.join(", ", config.getIdleWorlds()), NamedTextColor.AQUA)));
    }

    private void handleReload(CommandSender sender) {
        config.load();
        cameraManager.getTargetManager().loadSpawnTargets();

        // Restart the camera task if running so new config values take effect
        if (cameraManager.isRunning()) {
            cameraManager.stop();
            cameraManager.start();
            sender.sendMessage(Component.text("Configuration reloaded and camera restarted!", NamedTextColor.GREEN));
        } else {
            sender.sendMessage(Component.text("Configuration reloaded!", NamedTextColor.GREEN));
        }
    }

    private void handleSkip(CommandSender sender) {
        if (!cameraManager.isRunning()) {
            sender.sendMessage(Component.text("Camera is not running!", NamedTextColor.YELLOW));
            return;
        }
        cameraManager.skipTarget();
        sender.sendMessage(Component.text("Skipped to next target!", NamedTextColor.GREEN));
    }

    private void sendUsage(CommandSender sender) {
        sender.sendMessage(Component.text("=== PaperCamera ===", NamedTextColor.GOLD));
        sender.sendMessage(Component.text("/camera start  ", NamedTextColor.WHITE)
                .append(Component.text("- Start the camera system", NamedTextColor.GRAY)));
        sender.sendMessage(Component.text("/camera stop   ", NamedTextColor.WHITE)
                .append(Component.text("- Stop the camera system", NamedTextColor.GRAY)));
        sender.sendMessage(Component.text("/camera status ", NamedTextColor.WHITE)
                .append(Component.text("- Show camera status", NamedTextColor.GRAY)));
        sender.sendMessage(Component.text("/camera reload ", NamedTextColor.WHITE)
                .append(Component.text("- Reload configuration", NamedTextColor.GRAY)));
        sender.sendMessage(Component.text("/camera skip   ", NamedTextColor.WHITE)
                .append(Component.text("- Skip to next target", NamedTextColor.GRAY)));
    }
}