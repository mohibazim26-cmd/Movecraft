package net.countercraft.movecraft.async;

import net.countercraft.movecraft.CruiseDirection;
import net.countercraft.movecraft.Movecraft;
import net.countercraft.movecraft.MovecraftRotation;
import net.countercraft.movecraft.craft.Craft;
import net.countercraft.movecraft.craft.PlayerCraft;
import net.countercraft.movecraft.craft.type.CraftType;
import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class DirectControlManager extends BukkitRunnable implements Listener {
    private static final long AIRCRAFT_IMPULSE_MS = 500L;
    private static final double AIRCRAFT_MIN_BLOCKS_PER_SECOND = 4.0;
    private static final double AIRCRAFT_MAX_BLOCKS_PER_SECOND = 30.0;
    private static final double AIRCRAFT_ACCEL_PER_IMPULSE = 1.5;
    private static final double AIRCRAFT_DECEL_PER_IMPULSE = 2.5;
    private static final double AIRCRAFT_OLD_VECTOR_WEIGHT = 0.55;
    private static final double STANDARD_OLD_VECTOR_WEIGHT = 0.50;
    private static final double STANDARD_STOP_EPSILON = 0.03;

    private final Map<Craft, Player> controlledCrafts = new HashMap<>();
    private final Map<Player, PlayerCraft> playerToCraft = new HashMap<>();
    private final Map<Craft, Long> cooldowns = new HashMap<>();
    private final Map<Player, Long> sneakTimes = new HashMap<>();
    private final Map<Player, double[]> pendingMovements = new HashMap<>();
    private final Map<Player, double[]> lastInput = new HashMap<>();
    private final Map<Player, Long> lastInputTime = new HashMap<>();

    private final Map<Craft, Long> aircraftLastImpulse = new HashMap<>();
    private final Map<Craft, Double> aircraftCurrentSkip = new HashMap<>();
    private final Map<Craft, Vector> aircraftVelocity = new HashMap<>();
    private final Map<Craft, Vector> aircraftResidual = new HashMap<>();
    private final Map<Player, Boolean> lastSneakState = new HashMap<>();
    private final Map<Craft, Long> standardLastImpulse = new HashMap<>();
    private final Map<Craft, Vector> standardVelocity = new HashMap<>();
    private final Map<Craft, Vector> standardResidual = new HashMap<>();
    private final Map<Craft, Boolean> standardCruising = new HashMap<>();
    private final Map<Craft, Long> standardPausedUntil = new HashMap<>();

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        PlayerCraft craft = playerToCraft.get(player);
        if (craft == null) {
            return;
        }

        Location to = event.getTo();
        pendingMovements.put(player, new double[] {
                to.getX() - craft.getPilotLockedX(),
                to.getY() - craft.getPilotLockedY(),
                to.getZ() - craft.getPilotLockedZ()
        });

        Vector velocity = player.getVelocity();
        player.setVelocity(new Vector(velocity.getX(), 0, velocity.getZ()));
        event.setTo(new Location(
                to.getWorld(),
                craft.getPilotLockedX(),
                craft.getPilotLockedY(),
                craft.getPilotLockedZ(),
                to.getYaw(),
                to.getPitch()
        ));
    }

    @Override
    public void run() {
        if (controlledCrafts.isEmpty()) {
            return;
        }

        List<Craft> toRemove = new ArrayList<>();
        for (Map.Entry<Craft, Player> entry : controlledCrafts.entrySet()) {
            Craft craft = entry.getKey();
            Player player = entry.getValue();
            if (!(craft instanceof PlayerCraft) || player == null) {
                toRemove.add(craft);
                continue;
            }

            PlayerCraft playerCraft = (PlayerCraft) craft;
            if (isCombatAircraft(playerCraft)) {
                runCombatAircraft(playerCraft, player);
            } else {
                runStandardDirectControl(playerCraft, player);
            }
        }

        toRemove.forEach(this::removeControlledCraft);
    }

    private void runCombatAircraft(PlayerCraft craft, Player player) {
        long now = System.currentTimeMillis();
        boolean sneaking = player.isSneaking();
        boolean wasSneaking = lastSneakState.getOrDefault(player, false);
        lastSneakState.put(player, sneaking);

        if (sneaking) {
            if (craft.getCruising()) {
                craft.setCruising(false);
            }
            aircraftCurrentSkip.put(craft, 0.0);
            aircraftVelocity.put(craft, new Vector(0, 0, 0));
            aircraftResidual.put(craft, new Vector(0, 0, 0));
            aircraftLastImpulse.put(craft, now);
            pendingMovements.remove(player);
            return;
        }

        if (wasSneaking) {
            craft.setCruiseDirection(cardinalDirectionFromYaw(player.getLocation().getYaw()));
        }

        Long lastImpulse = aircraftLastImpulse.get(craft);
        if (lastImpulse != null && now - lastImpulse < AIRCRAFT_IMPULSE_MS) {
            return;
        }
        aircraftLastImpulse.put(craft, now);

        InputState input = readInput(player);
        CruiseDirection cruiseDirection = craft.getCruiseDirection();
        Vector forward = directionToVector(cruiseDirection);
        if (forward.lengthSquared() == 0) {
            forward = directionToVector(cardinalDirectionFromYaw(player.getLocation().getYaw()));
            craft.setCruiseDirection(cardinalDirectionFromYaw(player.getLocation().getYaw()));
        }

        int gear = clamp(craft.getCurrentGear(), 1, 9);
        double targetSkip = blocksPerImpulseForGear(gear);
        double currentSkip = aircraftCurrentSkip.getOrDefault(craft, 0.0);
        currentSkip = approach(currentSkip, targetSkip, AIRCRAFT_ACCEL_PER_IMPULSE, AIRCRAFT_DECEL_PER_IMPULSE);
        aircraftCurrentSkip.put(craft, currentSkip);
        sendThrottleActionBar(player, currentSkip, targetSkip);

        Vector targetVelocity = forward.clone().multiply(currentSkip);
        Vector left = leftOf(forward);
        if (input.strafeLeft) {
            targetVelocity.add(left.clone().multiply(currentSkip));
        }
        if (input.strafeRight) {
            targetVelocity.subtract(left.clone().multiply(currentSkip));
        }
        if (input.forward) {
            targetVelocity.setY(targetVelocity.getY() - currentSkip);
        }
        if (input.backward) {
            targetVelocity.setY(targetVelocity.getY() + currentSkip);
        }

        Vector currentVelocity = aircraftVelocity.getOrDefault(craft, new Vector(0, 0, 0));
        currentVelocity.multiply(AIRCRAFT_OLD_VECTOR_WEIGHT)
                .add(targetVelocity.clone().multiply(1.0 - AIRCRAFT_OLD_VECTOR_WEIGHT));
        aircraftVelocity.put(craft, currentVelocity.clone());

        Vector residual = aircraftResidual.getOrDefault(craft, new Vector(0, 0, 0));
        double preciseX = currentVelocity.getX() + residual.getX();
        double preciseY = currentVelocity.getY() + residual.getY();
        double preciseZ = currentVelocity.getZ() + residual.getZ();

        int dx = truncateTowardZero(preciseX);
        int dy = truncateTowardZero(preciseY);
        int dz = truncateTowardZero(preciseZ);

        residual.setX(preciseX - dx);
        residual.setY(preciseY - dy);
        residual.setZ(preciseZ - dz);
        aircraftResidual.put(craft, residual);

        if (dx == 0 && dy == 0 && dz == 0) {
            return;
        }

        craft.setLastCruiseUpdate(now);
        Movecraft.getInstance().getServer().getScheduler().runTask(Movecraft.getInstance(), () -> {
            if (controlledCrafts.containsKey(craft) && craft.getPilotLocked()) {
                craft.translate(craft.getWorld(), dx, dy, dz);
            }
        });
    }

    private void runStandardDirectControl(PlayerCraft craft, Player player) {
        double[] delta = pendingMovements.remove(player);
        double movedX;
        double movedY;
        double movedZ;

        if (delta != null && (Math.abs(delta[0]) > 0.05 || Math.abs(delta[1]) > 0.05 || Math.abs(delta[2]) > 0.05)) {
            movedX = delta[0];
            movedY = delta[1];
            movedZ = delta[2];
            lastInput.put(player, delta);
            lastInputTime.put(player, System.currentTimeMillis());
        } else {
            Long inputTime = lastInputTime.get(player);
            double[] last = lastInput.get(player);
            if (last != null && inputTime != null && System.currentTimeMillis() - inputTime < 150) {
                movedX = last[0];
                movedY = last[1];
                movedZ = last[2];
            } else {
                movedX = 0;
                movedY = 0;
                movedZ = 0;
                lastInput.remove(player);
                lastInputTime.remove(player);
            }
        }

        Long cooldown = cooldowns.get(craft);
        if (cooldown != null) {
            if (cooldown > System.currentTimeMillis()) {
                return;
            }
            cooldowns.remove(craft);
        }

        CruiseDirection xDirection = CruiseDirection.NONE;
        CruiseDirection zDirection = CruiseDirection.NONE;

        if (movedX > 0.05) {
            xDirection = CruiseDirection.EAST;
        } else if (movedX < -0.05) {
            xDirection = CruiseDirection.WEST;
        }
        if (movedZ > 0.05) {
            zDirection = CruiseDirection.SOUTH;
        } else if (movedZ < -0.05) {
            zDirection = CruiseDirection.NORTH;
        }

        CruiseDirection direction = craft.getCruiseDirection();
        boolean shouldCruise = standardCruising.getOrDefault(craft, false);
        boolean hasMovementInput = Math.abs(movedX) > 0 || Math.abs(movedZ) > 0 || movedY > 0;
        if (hasMovementInput) {
            shouldCruise = true;
        }

        if (xDirection != CruiseDirection.NONE && zDirection != CruiseDirection.NONE) {
            if (xDirection == CruiseDirection.EAST) {
                direction = zDirection == CruiseDirection.NORTH ? CruiseDirection.NORTHEAST : CruiseDirection.SOUTHEAST;
            } else {
                direction = zDirection == CruiseDirection.NORTH ? CruiseDirection.NORTHWEST : CruiseDirection.SOUTHWEST;
            }
        } else if (xDirection != CruiseDirection.NONE) {
            direction = xDirection;
        } else if (zDirection != CruiseDirection.NONE) {
            direction = zDirection;
        }

        if (movedY > 0.15) {
            direction = CruiseDirection.UP;
        }

        if (player.isSneaking()) {
            if (!sneakTimes.containsKey(player)) {
                sneakTimes.put(player, System.currentTimeMillis() + 250);
            } else if (System.currentTimeMillis() > sneakTimes.get(player)) {
                direction = CruiseDirection.DOWN;
                shouldCruise = true;
            }
        } else if (sneakTimes.containsKey(player)) {
            if (System.currentTimeMillis() < sneakTimes.get(player)) {
                shouldCruise = false;
                craft.setCruising(false);
            }
            sneakTimes.remove(player);
        }

        if (direction != craft.getCruiseDirection()) {
            craft.setCruiseDirection(direction);
        }
        standardCruising.put(craft, shouldCruise);

        long now = System.currentTimeMillis();
        long impulseMs = getStandardImpulseMs(craft);
        Long pausedUntil = standardPausedUntil.get(craft);
        if (pausedUntil != null) {
            if (now < pausedUntil) {
                return;
            }
            standardPausedUntil.remove(craft);
        }

        Long lastImpulse = standardLastImpulse.get(craft);
        if (lastImpulse != null && now - lastImpulse < impulseMs) {
            return;
        }
        standardLastImpulse.put(craft, now);

        Vector targetDirection = new Vector(0, 0, 0);
        if (shouldCruise) {
            targetDirection = directionToVector(direction);
            if (targetDirection.lengthSquared() > 0) {
                targetDirection.normalize();
            }
        }

        Vector currentVelocity = standardVelocity.getOrDefault(craft, new Vector(0, 0, 0));
        currentVelocity.multiply(STANDARD_OLD_VECTOR_WEIGHT)
                .add(targetDirection.clone().multiply(1.0 - STANDARD_OLD_VECTOR_WEIGHT));
        if (currentVelocity.lengthSquared() < STANDARD_STOP_EPSILON * STANDARD_STOP_EPSILON) {
            currentVelocity.zero();
        }
        standardVelocity.put(craft, currentVelocity.clone());

        double maxBlocksPerSecond = 1000.0 / impulseMs;
        double currentBlocksPerSecond = targetDirection.lengthSquared() > 0
                ? Math.max(0.0, currentVelocity.dot(targetDirection)) * maxBlocksPerSecond
                : currentVelocity.length() * maxBlocksPerSecond;
        if (shouldCruise || currentVelocity.lengthSquared() > 0) {
            sendSpeedActionBar(player, currentBlocksPerSecond, maxBlocksPerSecond);
        }

        Vector residual = standardResidual.getOrDefault(craft, new Vector(0, 0, 0));
        double preciseX = currentVelocity.getX() + residual.getX();
        double preciseY = currentVelocity.getY() + residual.getY();
        double preciseZ = currentVelocity.getZ() + residual.getZ();

        int dx = truncateTowardZero(preciseX);
        int dy = truncateTowardZero(preciseY);
        int dz = truncateTowardZero(preciseZ);

        residual.setX(preciseX - dx);
        residual.setY(preciseY - dy);
        residual.setZ(preciseZ - dz);
        standardResidual.put(craft, residual);

        if (dx == 0 && dy == 0 && dz == 0) {
            return;
        }

        craft.setLastCruiseUpdate(now);
        Movecraft.getInstance().getServer().getScheduler().runTask(Movecraft.getInstance(), () -> {
            if (controlledCrafts.containsKey(craft) && craft.getPilotLocked()) {
                craft.translate(craft.getWorld(), dx, dy, dz);
            }
        });
    }

    public void addControlledCraft(Craft craft, Player player) {
        Player oldPlayer = controlledCrafts.put(craft, player);
        if (oldPlayer != null && !oldPlayer.equals(player)) {
            clearPlayerState(oldPlayer);
        }
        PlayerCraft playerCraft = (PlayerCraft) craft;
        playerToCraft.put(player, playerCraft);

        if (isCombatAircraft(craft)) {
            playerCraft.setCruiseDirection(cardinalDirectionFromYaw(player.getLocation().getYaw()));
            if (playerCraft.getCruising()) {
                playerCraft.setCruising(false);
            }
            aircraftCurrentSkip.put(craft, 0.0);
            aircraftVelocity.put(craft, new Vector(0, 0, 0));
            aircraftResidual.put(craft, new Vector(0, 0, 0));
            aircraftLastImpulse.put(craft, 0L);
        } else {
            standardCruising.put(craft, false);
            standardVelocity.put(craft, new Vector(0, 0, 0));
            standardResidual.put(craft, new Vector(0, 0, 0));
            standardLastImpulse.put(craft, 0L);
        }
    }

    public void removeControlledCraft(Craft craft) {
        Player player = controlledCrafts.remove(craft);
        if (player != null) {
            clearPlayerState(player);
        }
        aircraftLastImpulse.remove(craft);
        aircraftCurrentSkip.remove(craft);
        aircraftVelocity.remove(craft);
        aircraftResidual.remove(craft);
        standardLastImpulse.remove(craft);
        standardVelocity.remove(craft);
        standardResidual.remove(craft);
        standardCruising.remove(craft);
        standardPausedUntil.remove(craft);
    }

    public void addOrSetCooldown(Craft craft, Long endTime) {
        cooldowns.put(craft, endTime);
    }

    public void rotateAircraftCruiseDirection(Craft craft, MovecraftRotation rotation) {
        if (!isCombatAircraft(craft)) {
            return;
        }
        craft.setCruiseDirection(rotateCardinal(craft.getCruiseDirection(), rotation));
    }

    public void pauseStandardDirectControlMovement(Craft craft, long millis) {
        if (isCombatAircraft(craft)) {
            return;
        }
        standardPausedUntil.put(craft, System.currentTimeMillis() + millis);
        standardResidual.put(craft, new Vector(0, 0, 0));
    }

    private void clearPlayerState(Player player) {
        playerToCraft.remove(player);
        pendingMovements.remove(player);
        lastInput.remove(player);
        lastInputTime.remove(player);
        sneakTimes.remove(player);
        lastSneakState.remove(player);
    }

    private InputState readInput(Player player) {
        double[] delta = pendingMovements.remove(player);
        if (delta != null && (Math.abs(delta[0]) > 0.05 || Math.abs(delta[2]) > 0.05)) {
            lastInput.put(player, delta);
            lastInputTime.put(player, System.currentTimeMillis());
        } else {
            Long inputTime = lastInputTime.get(player);
            double[] last = lastInput.get(player);
            if (last != null && inputTime != null && System.currentTimeMillis() - inputTime < 150) {
                delta = last;
            } else {
                lastInput.remove(player);
                lastInputTime.remove(player);
                delta = new double[] {0, 0, 0};
            }
        }

        Vector input = new Vector(delta[0], 0, delta[2]);
        if (input.lengthSquared() < 0.0025) {
            return new InputState(false, false, false, false);
        }
        input.normalize();

        Vector facing = player.getLocation().getDirection();
        facing.setY(0);
        if (facing.lengthSquared() < 0.0025) {
            return new InputState(false, false, false, false);
        }
        facing.normalize();

        Vector right = new Vector(-facing.getZ(), 0, facing.getX());
        double forwardDot = facing.dot(input);
        double rightDot = right.dot(input);

        return new InputState(
                forwardDot > 0.40,
                forwardDot < -0.40,
                rightDot < -0.40,
                rightDot > 0.40
        );
    }

    private static boolean isCombatAircraft(Craft craft) {
        String craftName = craft.getType().getStringProperty(CraftType.NAME).toLowerCase();
        return craftName.contains("fighter") || craftName.contains("bomber");
    }

    private static double blocksPerImpulseForGear(int gear) {
        double blocksPerSecond = AIRCRAFT_MIN_BLOCKS_PER_SECOND
                + ((gear - 1) * (AIRCRAFT_MAX_BLOCKS_PER_SECOND - AIRCRAFT_MIN_BLOCKS_PER_SECOND) / 8.0);
        return blocksPerSecond / 2.0;
    }

    private static void sendThrottleActionBar(Player player, double currentSkip, double targetSkip) {
        double currentBlocksPerSecond = Math.max(0.0, currentSkip * 2.0);
        double targetBlocksPerSecond = Math.max(0.1, targetSkip * 2.0);
        double percent = currentBlocksPerSecond / targetBlocksPerSecond;

        ChatColor speedColor;
        if (percent <= 0.40) {
            speedColor = ChatColor.RED;
        } else if (percent < 0.70) {
            speedColor = ChatColor.YELLOW;
        } else {
            speedColor = ChatColor.GREEN;
        }

        String message = ChatColor.YELLOW.toString() + ChatColor.BOLD + "Manetta: "
                + speedColor + String.format("%.1f blocchi/s", currentBlocksPerSecond);
        player.spigot().sendMessage(ChatMessageType.ACTION_BAR, TextComponent.fromLegacyText(message));
    }

    private static void sendSpeedActionBar(Player player, double currentBlocksPerSecond, double maxBlocksPerSecond) {
        double percent = currentBlocksPerSecond / Math.max(0.1, maxBlocksPerSecond);

        ChatColor speedColor;
        if (percent <= 0.40) {
            speedColor = ChatColor.RED;
        } else if (percent < 0.70) {
            speedColor = ChatColor.YELLOW;
        } else {
            speedColor = ChatColor.GREEN;
        }

        String message = ChatColor.YELLOW.toString() + ChatColor.BOLD + "Velocit\u00e0: "
                + speedColor + String.format("%.1f blocchi/s", Math.max(0.0, currentBlocksPerSecond));
        player.spigot().sendMessage(ChatMessageType.ACTION_BAR, TextComponent.fromLegacyText(message));
    }

    private static long getStandardImpulseMs(PlayerCraft craft) {
        int tickCooldown = (int) craft.getType().getPerWorldProperty(
                CraftType.PER_WORLD_TICK_COOLDOWN, craft.getWorld());
        tickCooldown = Math.max(1, tickCooldown);

        long impulseMs = tickCooldown * 50L;
        if (craft.getType().getBoolProperty(CraftType.HALF_SPEED_UNDERWATER)
                && craft.getHitBox().getMinY() < craft.getWorld().getSeaLevel()) {
            impulseMs *= 2;
        }
        return Math.max(50L, impulseMs);
    }

    private static double approach(double current, double target, double accelStep, double decelStep) {
        double step = target > current ? accelStep : decelStep;
        if (Math.abs(target - current) <= step) {
            return target;
        }
        return current + Math.signum(target - current) * step;
    }

    private static int truncateTowardZero(double value) {
        return value >= 0 ? (int) Math.floor(value) : (int) Math.ceil(value);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static CruiseDirection cardinalDirectionFromYaw(float yaw) {
        yaw = yaw % 360;
        if (yaw < 0) {
            yaw += 360;
        }
        if (yaw >= 315 || yaw < 45) {
            return CruiseDirection.SOUTH;
        }
        if (yaw >= 45 && yaw < 135) {
            return CruiseDirection.WEST;
        }
        if (yaw >= 135 && yaw < 225) {
            return CruiseDirection.NORTH;
        }
        return CruiseDirection.EAST;
    }

    private static Vector directionToVector(CruiseDirection direction) {
        switch (direction) {
            case NORTH:
                return new Vector(0, 0, -1);
            case SOUTH:
                return new Vector(0, 0, 1);
            case EAST:
                return new Vector(1, 0, 0);
            case WEST:
                return new Vector(-1, 0, 0);
            case NORTHEAST:
                return new Vector(1, 0, -1);
            case NORTHWEST:
                return new Vector(-1, 0, -1);
            case SOUTHEAST:
                return new Vector(1, 0, 1);
            case SOUTHWEST:
                return new Vector(-1, 0, 1);
            case UP:
                return new Vector(0, 1, 0);
            case DOWN:
                return new Vector(0, -1, 0);
            default:
                return new Vector(0, 0, 0);
        }
    }

    private static Vector leftOf(Vector forward) {
        return new Vector(forward.getZ(), 0, -forward.getX());
    }

    private static CruiseDirection rotateCardinal(CruiseDirection direction, MovecraftRotation rotation) {
        boolean clockwise = rotation == MovecraftRotation.CLOCKWISE;
        switch (direction) {
            case NORTH:
                return clockwise ? CruiseDirection.EAST : CruiseDirection.WEST;
            case EAST:
                return clockwise ? CruiseDirection.SOUTH : CruiseDirection.NORTH;
            case SOUTH:
                return clockwise ? CruiseDirection.WEST : CruiseDirection.EAST;
            case WEST:
                return clockwise ? CruiseDirection.NORTH : CruiseDirection.SOUTH;
            default:
                return direction;
        }
    }

    private static final class InputState {
        private final boolean forward;
        private final boolean backward;
        private final boolean strafeLeft;
        private final boolean strafeRight;

        private InputState(boolean forward, boolean backward, boolean strafeLeft, boolean strafeRight) {
            this.forward = forward;
            this.backward = backward;
            this.strafeLeft = strafeLeft;
            this.strafeRight = strafeRight;
        }
    }
}
