package net.countercraft.movecraft.async;

import net.countercraft.movecraft.CruiseDirection;
import net.countercraft.movecraft.MovecraftRotation;
import net.countercraft.movecraft.craft.Craft;
import net.countercraft.movecraft.craft.PlayerCraft;
import net.countercraft.movecraft.craft.type.CraftType;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.scheduler.BukkitRunnable;
import net.kyori.adventure.text.Component; // Usiamo Adventure nativo di Paper
import net.kyori.adventure.text.format.NamedTextColor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class DirectControlManager extends BukkitRunnable implements Listener {
    private final Map<Craft, Player> controlledCrafts = new HashMap<>();
    private final Map<Player, PlayerCraft> playerToCraft = new HashMap<>();
    private final Map<Craft, Long> cooldowns = new HashMap<>();
    private final Map<Player, Long> sneakTimes = new HashMap<>();
    private final Map<Player, double[]> pendingMovements = new HashMap<>();
    private final Map<Player, double[]> lastInput = new HashMap<>();
    private final Map<Player, Long> lastInputTime = new HashMap<>();
    private final Map<Player, Boolean> lastSneakState = new HashMap<>();

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerMove(PlayerMoveEvent event) {
        Player p = event.getPlayer();
        PlayerCraft pCraft = playerToCraft.get(p);
        if (pCraft == null) return;

        Location to = event.getTo();
        pendingMovements.put(p, new double[]{
            to.getX() - pCraft.getPilotLockedX(),
            to.getY() - pCraft.getPilotLockedY(),
            to.getZ() - pCraft.getPilotLockedZ()
        });

        Vector vel = p.getVelocity();
        p.setVelocity(new Vector(vel.getX(), 0, vel.getZ()));
        event.setTo(new Location(to.getWorld(),
            pCraft.getPilotLockedX(), pCraft.getPilotLockedY(), pCraft.getPilotLockedZ(),
            to.getYaw(), to.getPitch()));
    }

    @Override
    public void run() {
        if (controlledCrafts.isEmpty()) return;
        List<Craft> toRemove = new ArrayList<>();
        for (Map.Entry<Craft, Player> controlledCraft : controlledCrafts.entrySet())
        {
            if(controlledCraft.getKey() == null || controlledCraft.getValue() == null) {
                toRemove.add(controlledCraft.getKey());
                continue;
            }
            Player player = controlledCraft.getValue();
            PlayerCraft pCraft = (PlayerCraft)controlledCraft.getKey();

            double[] delta = pendingMovements.remove(player);
            double movedX, movedY, movedZ;
            if (delta != null && (Math.abs(delta[0]) > 0.05 || Math.abs(delta[1]) > 0.05 || Math.abs(delta[2]) > 0.05)) {
                movedX = delta[0];
                movedY = delta[1];
                movedZ = delta[2];
                lastInput.put(player, delta);
                lastInputTime.put(player, System.currentTimeMillis());
            } else {
                Long t = lastInputTime.get(player);
                double[] last = lastInput.get(player);
                if (last != null && t != null && System.currentTimeMillis() - t < 150) {
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

            if(cooldowns.containsKey(pCraft))
            {
                if(cooldowns.get(pCraft) > System.currentTimeMillis()) continue;
                else cooldowns.remove(pCraft);
            }

            // =========================================================================
            // DEVIAZIONE COMBAT AIRCRAFT: Volo e Direzione Pura
            // =========================================================================
            String craftType = pCraft.getType().getStringProperty(CraftType.NAME).toLowerCase();
            if (craftType.equals("fighter") || craftType.equals("bomber")) {
                processAircraftTick(player, pCraft, movedX, movedZ);
                continue; 
            }
            // =========================================================================

            CruiseDirection xDir = CruiseDirection.NONE;
            CruiseDirection zDir = CruiseDirection.NONE;

            if (movedX > 0.05) xDir = CruiseDirection.EAST;
            else if (movedX < -0.05) xDir = CruiseDirection.WEST;
            if (movedZ > 0.05) zDir = CruiseDirection.SOUTH;
            else if (movedZ < -0.05) zDir = CruiseDirection.NORTH;

            if(Math.abs(movedX) > 0 && !pCraft.getCruising()|| Math.abs(movedZ) > 0 && !pCraft.getCruising() || movedY > 0 && !pCraft.getCruising())
                pCraft.setCruising(true);

            CruiseDirection cd = pCraft.getCruiseDirection();
            if(xDir != CruiseDirection.NONE && zDir != CruiseDirection.NONE) {
                if (xDir == CruiseDirection.EAST) {
                    if (zDir == CruiseDirection.NORTH) cd = CruiseDirection.NORTHEAST;
                    else cd = CruiseDirection.SOUTHEAST;
                } else {
                    if (zDir == CruiseDirection.NORTH) cd = CruiseDirection.NORTHWEST;
                    else cd = CruiseDirection.SOUTHWEST;
                }
            }
            else if (xDir != CruiseDirection.NONE) cd = xDir;
            else if (zDir != CruiseDirection.NONE) cd = zDir;

            if(movedY > 0.15) cd = CruiseDirection.UP;

            if(player.isSneaking()) {
                if(!sneakTimes.containsKey(player))
                    sneakTimes.put(player, System.currentTimeMillis() + 250);
                else if(sneakTimes.containsKey(player) && System.currentTimeMillis() > sneakTimes.get(player)){
                    cd = CruiseDirection.DOWN;
                    if(!pCraft.getCruising()) pCraft.setCruising(true);
                }
            }
            else {
                if(sneakTimes.containsKey(player)) {
                    if(System.currentTimeMillis() < sneakTimes.get(player)) {
                        pCraft.setCruising(false);
                    }
                    sneakTimes.remove(player);
                }
            }

            if (cd != pCraft.getCruiseDirection())
                pCraft.setCruiseDirection(cd);
        }
        toRemove.forEach(controlledCrafts::remove);
    }

    public void addControlledCraft(Craft c, Player p) {
        Player oldPlayer = controlledCrafts.put(c, p);
        if (oldPlayer != null && !oldPlayer.equals(p)) {
            playerToCraft.remove(oldPlayer);
            pendingMovements.remove(oldPlayer);
            lastInput.remove(oldPlayer);
            lastInputTime.remove(oldPlayer);
            sneakTimes.remove(oldPlayer);
            lastSneakState.remove(oldPlayer);
        }
        playerToCraft.put(p, (PlayerCraft) c);
        
        updateCraftGearFromSlot(p, (PlayerCraft) c, p.getInventory().getHeldItemSlot());
    }

    public void removeControlledCraft(Craft c) {
        Player p = controlledCrafts.remove(c);
        if (p != null) {
            playerToCraft.remove(p);
            pendingMovements.remove(p);
            lastInput.remove(p);
            lastInputTime.remove(p);
            sneakTimes.remove(p);
            lastSneakState.remove(p);
        }
    }

    public void addOrSetCooldown(Craft c, Long endTime) { cooldowns.put(c, endTime); }

    private void processAircraftTick(Player player, PlayerCraft pCraft, double movedX, double movedZ) {
        boolean isSneaking = player.isSneaking();
        boolean wasSneaking = lastSneakState.getOrDefault(player, false);
        lastSneakState.put(player, isSneaking);

        if (isSneaking) {
            if (pCraft.getCruising()) pCraft.setCruising(false);
            return;
        }

        if (!isSneaking && wasSneaking) {
            pCraft.setCruiseDirection(getAircraftCardinalDirection(player.getLocation().getYaw()));
            pCraft.setCruising(true);
        }

        if (!pCraft.getCruising()) {
            pCraft.setCruiseDirection(getAircraftCardinalDirection(player.getLocation().getYaw()));
            pCraft.setCruising(true);
        }

        CruiseDirection baseDir = pCraft.getCruiseDirection();
        int dx = 0, dy = 0, dz = 0;

        switch (baseDir) {
            case NORTH: dz = -1; break;
            case SOUTH: dz = 1; break;
            case EAST:  dx = 1; break;
            case WEST:  dx = -1; break;
            default: break;
        }

        boolean inputW = false, inputS = false, inputA = false, inputD = false;
        if (baseDir == CruiseDirection.NORTH) {
            if (movedZ < -0.05) inputW = true; if (movedZ > 0.05) inputS = true;
            if (movedX < -0.05) inputA = true; if (movedX > 0.05) inputD = true;
        } else if (baseDir == CruiseDirection.SOUTH) {
            if (movedZ > 0.05) inputW = true;  if (movedZ < -0.05) inputS = true;
            if (movedX > 0.05) inputA = true;  if (movedX < -0.05) inputD = true;
        } else if (baseDir == CruiseDirection.EAST) {
            if (movedX > 0.05) inputW = true;  if (movedX < -0.05) inputS = true;
            if (movedZ < -0.05) inputA = true; if (movedZ > 0.05) inputD = true;
        } else if (baseDir == CruiseDirection.WEST) {
            if (movedX < -0.05) inputW = true; if (movedX > 0.05) inputS = true;
            if (movedZ > 0.05) inputA = true;  if (movedZ < -0.05) inputD = true;
        }

        if (inputW) dy = -1;
        else if (inputS) dy = 1;

        if (inputA) {
            if (baseDir == CruiseDirection.NORTH) dx = -1; if (baseDir == CruiseDirection.SOUTH) dx = 1;
            if (baseDir == CruiseDirection.EAST)  dz = -1; if (baseDir == CruiseDirection.WEST)  dz = 1;
        } else if (inputD) {
            if (baseDir == CruiseDirection.NORTH) dx = 1;  if (baseDir == CruiseDirection.SOUTH) dx = -1;
            if (baseDir == CruiseDirection.EAST)  dz = 1;  if (baseDir == CruiseDirection.WEST)  dz = -1;
        }

        if (dx != 0 || dy != 0 || dz != 0) {
            try {
                Method moveMethod = pCraft.getClass().getMethod("move", int.class, int.class, int.class);
                moveMethod.invoke(pCraft, dx, dy, dz);
            } catch (Exception e1) {
                try {
                    Method translateMethod = pCraft.getClass().getMethod("translate", org.bukkit.World.class, int.class, int.class, int.class);
                    translateMethod.invoke(pCraft, pCraft.getWorld(), dx, dy, dz);
                } catch (Exception e2) {
                    pCraft.setCruiseDirection(baseDir);
                }
            }
        }
    }

    private CruiseDirection getAircraftCardinalDirection(float yaw) {
        double rotation = (yaw - 90) % 360;
        if (rotation < 0) rotation += 360;
        if (0 <= rotation && rotation < 45) return CruiseDirection.WEST;
        if (45 <= rotation && rotation < 135) return CruiseDirection.NORTH;
        if (135 <= rotation && rotation < 225) return CruiseDirection.EAST;
        if (225 <= rotation && rotation < 315) return CruiseDirection.SOUTH;
        return CruiseDirection.WEST;
    }

    @EventHandler
    public void onAircraftThrottle(PlayerItemHeldEvent event) {
        Player player = event.getPlayer();
        PlayerCraft pCraft = playerToCraft.get(player);
        if (pCraft == null) return;

        String craftType = pCraft.getType().getStringProperty(CraftType.NAME).toLowerCase();
        if (!craftType.equals("fighter") && !craftType.equals("bomber")) return;

        int newSlot = event.getNewSlot();
        int compassSlot = -1;
        for (int i = 0; i < 9; i++) {
            org.bukkit.inventory.ItemStack item = player.getInventory().getItem(i);
            if (item != null && item.getType() == org.bukkit.Material.COMPASS) {
                compassSlot = i;
                break;
            }
        }
        if (compassSlot != -1 && compassSlot != newSlot) {
            org.bukkit.inventory.ItemStack compassItem = player.getInventory().getItem(compassSlot);
            org.bukkit.inventory.ItemStack targetSlotItem = player.getInventory().getItem(newSlot);
            player.getInventory().setItem(newSlot, compassItem);
            player.getInventory().setItem(compassSlot, targetSlotItem);
        }

        updateCraftGearFromSlot(player, pCraft, newSlot);
    }

    private void updateCraftGearFromSlot(Player player, PlayerCraft pCraft, int slot) {
        int maxGears = pCraft.getType().getIntProperty(CraftType.GEAR_SHIFTS);
        if (maxGears <= 1) return;

        int targetGear = 1 + (int) Math.round(((double) slot / 8.0) * (maxGears - 1));
        
        if (targetGear > maxGears) targetGear = maxGears;
        if (targetGear < 1) targetGear = 1;

        pCraft.setCurrentGear(targetGear);

        // Invio Action Bar compatibile al 100% con Paper/Adventure nativo del plugin
        Component message;
        if (targetGear == maxGears) {
            message = Component.text("AFTERBURNERS ACTIVE [Gear " + targetGear + "/" + maxGears + "]", NamedTextColor.DARK_PURPLE);
        } else {
            message = Component.text("Manetta: ", NamedTextColor.AQUA)
                    .append(Component.text("Gear " + targetGear + " / " + maxGears, NamedTextColor.YELLOW));
        }
        player.sendActionBar(message);
    }
}
