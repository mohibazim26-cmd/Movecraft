package net.countercraft.movecraft.async;

import net.countercraft.movecraft.CruiseDirection;
import net.countercraft.movecraft.craft.Craft;
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
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class DirectControlManager extends BukkitRunnable implements Listener {
    private final Map<Craft, Player> controlledCrafts = new HashMap<>();
    private final Map<Player, Craft> playerToCraft = new HashMap<>();
    private final Map<Craft, Long> cooldowns = new HashMap<>();
    private final Map<Player, Long> sneakTimes = new HashMap<>();
    private final Map<Player, double[]> pendingMovements = new HashMap<>();
    private final Map<Player, double[]> lastInput = new HashMap<>();
    private final Map<Player, Long> lastInputTime = new HashMap<>();
    private final Map<Player, Boolean> lastSneakState = new HashMap<>();
    private final Map<Player, Location> pilotLockedLocations = new HashMap<>();

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerMove(PlayerMoveEvent event) {
        Player p = event.getPlayer();
        Craft pCraft = playerToCraft.get(p);
        if (pCraft == null) return;

        Location lockedLoc = pilotLockedLocations.get(p);
        if (lockedLoc == null) {
            lockedLoc = p.getLocation();
            pilotLockedLocations.put(p, lockedLoc);
        }

        Location to = event.getTo();
        pendingMovements.put(p, new double[]{
            to.getX() - lockedLoc.getX(),
            to.getY() - lockedLoc.getY(),
            to.getZ() - lockedLoc.getZ()
        });

        Vector vel = p.getVelocity();
        p.setVelocity(new Vector(vel.getX(), 0, vel.getZ()));
        event.setTo(new Location(to.getWorld(),
            lockedLoc.getX(), lockedLoc.getY(), lockedLoc.getZ(),
            to.getYaw(), to.getPitch()));
    }

    @Override
    public void run() {
        if (controlledCrafts.isEmpty()) return;
        List<Craft> toRemove = new ArrayList<>();
        for (Map.Entry<Craft, Player> controlledCraft : controlledCrafts.entrySet()) {
            if(controlledCraft.getKey() == null || controlledCraft.getValue() == null) {
                toRemove.add(controlledCraft.getKey());
                continue;
            }
            Player player = controlledCraft.getValue();
            Craft pCraft = controlledCraft.getKey();

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

            if(cooldowns.containsKey(pCraft)) {
                if(cooldowns.get(pCraft) > System.currentTimeMillis()) continue;
                else cooldowns.remove(pCraft);
            }

            String craftType = pCraft.getType().getStringProperty(CraftType.NAME);
            if (craftType != null && craftType.equalsIgnoreCase("Fighter")) {
                processAircraftTick(player, pCraft, movedX, movedZ);
                continue; 
            }

            // Movimento standard per gli altri veicoli
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

    private void processAircraftTick(Player player, Craft pCraft, double movedX, double movedZ) {
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
            pCraft.translate(pCraft.getWorld(), dx, dy, dz);
            Location lockedLoc = pilotLockedLocations.get(player);
            if (lockedLoc != null) {
                pilotLockedLocations.put(player, lockedLoc.clone().add(dx, dy, dz));
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
        Craft pCraft = playerToCraft.get(player);
        if (pCraft == null) return;

        String craftType = pCraft.getType().getStringProperty(CraftType.NAME);
        if (craftType == null || !craftType.equalsIgnoreCase("Fighter")) return;

        int newSlot = event.getNewSlot();
        updateCraftGearFromSlot(player, pCraft, newSlot);
    }

    private void updateCraftGearFromSlot(Player player, Craft pCraft, int slot) {
        int gearShifts = pCraft.getType().getIntProperty(CraftType.GEAR_SHIFTS);
        if (gearShifts <= 1) return;

        // MATEMATICA INVERTITA: Ora calcoliamo la marcia al contrario rispetto allo slot.
        // Slot 0 (Tasto 1) -> targetGear = gearShifts (Velocità Minima)
        // Slot 8 (Tasto 9) -> targetGear = 1 (Velocità Massima / Afterburners)
        int targetGear = gearShifts - (int) Math.round(((double) slot / 8.0) * (gearShifts - 1));
        if (targetGear > gearShifts) targetGear = gearShifts;
        if (targetGear < 1) targetGear = 1;

        pCraft.setCurrentGear(targetGear);

        // Aggiorniamo anche i messaggi della Action Bar per riflettere il nuovo comportamento intuitivo
        Component message;
        if (targetGear == 1) {
            message = Component.text("MANETTA: MASSIMA POTENZA [Slot " + (slot + 1) + " -> Gear " + targetGear + "]", NamedTextColor.RED);
        } else if (targetGear == gearShifts) {
            message = Component.text("MANETTA: MINIMA / MANOVRA [Slot " + (slot + 1) + " -> Gear " + targetGear + "]", NamedTextColor.GREEN);
        } else {
            message = Component.text("Manetta: ", NamedTextColor.AQUA)
                    .append(Component.text("Potenza Media [Slot " + (slot + 1) + " -> Gear " + targetGear + "]", NamedTextColor.YELLOW));
        }
        player.sendActionBar(message);
    }

    public void addControlledCraft(Craft c, Player p) {
        controlledCrafts.put(c, p);
        playerToCraft.put(p, c);
        pilotLockedLocations.put(p, p.getLocation());
        updateCraftGearFromSlot(p, c, p.getInventory().getHeldItemSlot());
    }

    public void removeControlledCraft(Craft c) {
        Player p = controlledCrafts.remove(c);
        if (p != null) {
            playerToCraft.remove(p);
            pilotLockedLocations.remove(p);
        }
    }

    public void addOrSetCooldown(Craft c, Long endTime) { 
        cooldowns.put(c, endTime); 
    }
}
