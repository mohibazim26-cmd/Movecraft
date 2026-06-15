package net.countercraft.movecraft.async;

import net.countercraft.movecraft.craft.Craft;
import net.countercraft.movecraft.craft.CraftManager;
import net.countercraft.movecraft.craft.PlayerCraft;
import net.countercraft.movecraft.craft.type.CraftType;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.jetbrains.annotations.NotNull;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class DirectControlInputProvider implements Listener {
    private final Map<UUID, DirectControlInput> inputs = new HashMap<>();
    private final Map<UUID, Long> lastInputTime = new HashMap<>();

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerMove(@NotNull PlayerMoveEvent event) {
        Player player = event.getPlayer();
        Craft craft = CraftManager.getInstance().getCraftByPlayer(player);

        if (!(craft instanceof PlayerCraft playerCraft) || !playerCraft.getPilotLocked()) {
            clear(player);
            return;
        }

        if (!"wasd".equalsIgnoreCase(playerCraft.getType().getStringProperty(CraftType.DIRECT_CONTROL_INPUT_MODE))) {
            clear(player);
            return;
        }

        Location from = event.getFrom();
        Location to = event.getTo();
        if (to == null) {
            return;
        }

        double dx = to.getX() - from.getX();
        double dy = to.getY() - from.getY();
        double dz = to.getZ() - from.getZ();

        double deadzone = playerCraft.getType().getDoubleProperty(CraftType.DIRECT_CONTROL_INPUT_DEADZONE);

        DirectControlInput input = inputFromDelta(player, dx, dy, dz, deadzone);

        if (hasAnyInput(input)) {
            inputs.put(player.getUniqueId(), input);
            lastInputTime.put(player.getUniqueId(), System.currentTimeMillis());
        }

     
    }

    public DirectControlInput getInput(@NotNull Player player, @NotNull PlayerCraft craft) {
        UUID uuid = player.getUniqueId();
        DirectControlInput input = inputs.get(uuid);
        Long time = lastInputTime.get(uuid);

        if (input == null || time == null) {
            return new DirectControlInput(false, false, false, false, false, player.isSneaking());
        }

        int memoryMs = craft.getType().getIntProperty(CraftType.DIRECT_CONTROL_INPUT_MEMORY_MS);
        if (System.currentTimeMillis() - time > memoryMs) {
            clear(player);
            return new DirectControlInput(false, false, false, false, false, player.isSneaking());
        }

        // Sneak is read live because sneaking alone may not always fire PlayerMoveEvent.
        return new DirectControlInput(
                input.forward(),
                input.backward(),
                input.left(),
                input.right(),
                input.jump(),
                player.isSneaking()
        );
    }

    public void clear(@NotNull Player player) {
        UUID uuid = player.getUniqueId();
        inputs.remove(uuid);
        lastInputTime.remove(uuid);
    }

    private DirectControlInput inputFromDelta(@NotNull Player player, double dx, double dy, double dz, double deadzone) {
        float yaw = player.getLocation().getYaw();
        double radians = Math.toRadians(yaw);

        double forwardX = -Math.sin(radians);
        double forwardZ = Math.cos(radians);
        double rightX = Math.cos(radians);
        double rightZ = Math.sin(radians);

        double forwardAmount = dx * forwardX + dz * forwardZ;
        double rightAmount = dx * rightX + dz * rightZ;

        boolean forward = forwardAmount > deadzone;
        boolean backward = forwardAmount < -deadzone;
        boolean right = rightAmount > deadzone;
        boolean left = rightAmount < -deadzone;
        boolean jump = dy > 0.08;
        boolean sneak = player.isSneaking();

        return new DirectControlInput(forward, backward, left, right, jump, sneak);
    }

    private boolean hasAnyInput(@NotNull DirectControlInput input) {
        return input.forward()
                || input.backward()
                || input.left()
                || input.right()
                || input.jump()
                || input.sneak();
    }
}
