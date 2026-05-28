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

            // =========================================================================
            // DA QUI IN POI CONTINUA IL COMINCIO DEL TUO LOOP RUN()
            // =========================================================================

            String craftType = pCraft.getType().getOrigName(); // Usiamo getOrigName() o getName() a seconda del tuo fork
            if (craftType == null) craftType = pCraft.getType().toString();

            // Riconoscimento del caccia ignorando maiuscole/minuscole
            if (craftType.equalsIgnoreCase("Fighter")) {
                processAircraftTick(player, pCraft, movedX, movedZ);
                continue; 
            }

            // Movimento standard per tutti gli altri veicoli (Navi, dirigibili, ecc.)
            CruiseDirection xDir = CruiseDirection.NONE;
            CruiseDirection zDir = CruiseDirection.NONE;

            if (movedX > 0.05) xDir = CruiseDirection.EAST;
            else if (movedX < -0.05) xDir = CruiseDirection.WEST;
            if (movedZ > 0.05) zDir = CruiseDirection.SOUTH;
            else if (movedZ < -0.05) zDir = CruiseDirection.NORTH;

            if (Math.abs(movedX) > 0 && !pCraft.getCruising() || Math.abs(movedZ) > 0 && !pCraft.getCruising() || movedY > 0 && !pCraft.getCruising())
                pCraft.setCruising(true);

            CruiseDirection cd = pCraft.getCruiseDirection();
            if (xDir != CruiseDirection.NONE && zDir != CruiseDirection.NONE) {
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

            if (movedY > 0.15) cd = CruiseDirection.UP;

            if (player.isSneaking()) {
                if (!sneakTimes.containsKey(player))
                    sneakTimes.put(player, System.currentTimeMillis() + 250);
                else if (sneakTimes.containsKey(player) && System.currentTimeMillis() > sneakTimes.get(player)) {
                    cd = CruiseDirection.DOWN;
                    if (!pCraft.getCruising()) pCraft.setCruising(true);
                }
            }
            else {
                if (sneakTimes.containsKey(player)) {
                    if (System.currentTimeMillis() < sneakTimes.get(player)) {
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

    // =========================================================================
    // GESTIONE MOVIMENTO PILOTAGGIO CACCIA (FIGHTER)
    // =========================================================================
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

        // Rotazione del caccia (A e D ruotano il muso del veicolo)
        if (inputA || inputD) {
            long ora = System.currentTimeMillis();
            long ultimoRuotato = lastRotateMs.getOrDefault(player, 0L);
            if (ora - ultimoRuotato > 600) { // Cooldown rotazione per non far impazzire i blocchi
                MovecraftRotation rot = inputA ? MovecraftRotation.ANTICLOCKWISE : MovecraftRotation.CLOCKWISE;
                pCraft.rotate(rot, pCraft.getWorld());
                lastRotateMs.put(player, ora);
                return;
            }
        }

        if (dx != 0 || dy != 0 || dz != 0) {
            pCraft.translate(pCraft.getWorld(), dx, dy, dz);
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

    // =========================================================================
    // EVENTO MANETTA TRAMITE HOTBAR (Inserito in fondo alla classe)
    // =========================================================================
    @org.bukkit.event.EventHandler
    public void onAircraftThrottle(org.bukkit.event.player.PlayerItemHeldEvent event) {
        Player player = event.getPlayer();
        PlayerCraft pCraft = playerToCraft.get(player);
        if (pCraft == null) return;

        String craftType = pCraft.getType().getOrigName();
        if (craftType == null) craftType = pCraft.getType().toString();
        if (!craftType.equalsIgnoreCase("Fighter")) return;

        int newSlot = event.getNewSlot();
        updateCraftGearFromSlot(player, pCraft, newSlot);
    }

    private void updateCraftGearFromSlot(Player player, PlayerCraft pCraft, int slot) {
        // Fallback sicuro se il metodo nativo fallisce: leggiamo 5 marce
        int gearShifts = 5; 
        try {
            // Cerchiamo di usare il metodo nativo delle marce (usato anche dallo SpeedSign)
            // Se il tuo fork sposta la costante in un altro oggetto, usiamo la mappa interna di ripiego
            int nativeGears = pCraft.getType().getIntProperty(net.countercraft.movecraft.craft.type.CraftType.GEAR_SHIFTS);
            if (nativeGears > 1) gearShifts = nativeGears;
        } catch (Throwable e) {
            // Se fallisce, usiamo la costante 5 inserita nel passthrough properties
            gearShifts = 5;
        }

        // LOGICA INVERTITA RICHIESTA: 
        // Slot 0 (Hotbar 1) -> Marcia Massima Interna (Gear 5 = Vel Minima)
        // Slot 8 (Hotbar 9) -> Marcia Minima Interna (Gear 1 = Vel Massima/Afterburner)
        int targetGear = gearShifts - (int) Math.round(((double) slot / 8.0) * (gearShifts - 1));
        if (targetGear > gearShifts) targetGear = gearShifts;
        if (targetGear < 1) targetGear = 1;

        pCraft.setCurrentGear(targetGear);
        internalAircraftGears.put(player, targetGear);

        // Feedback immediato visibile sopra l'inventario del giocatore
        net.kyori.adventure.text.Component message;
        if (targetGear == 1) {
            message = net.kyori.adventure.text.Component.text("MANETTA: MASSIMA POTENZA [Slot " + (slot + 1) + " -> Gear " + targetGear + "]", net.kyori.adventure.text.format.NamedTextColor.RED);
        } else if (targetGear == gearShifts) {
            message = net.kyori.adventure.text.Component.text("MANETTA: MINIMA / MANOVRA [Slot " + (slot + 1) + " -> Gear " + targetGear + "]", net.kyori.adventure.text.format.NamedTextColor.GREEN);
        } else {
            message = net.kyori.adventure.text.Component.text("Manetta: ", net.kyori.adventure.text.format.NamedTextColor.AQUA)
                    .append(net.kyori.adventure.text.Component.text("Potenza Media [Slot " + (slot + 1) + " -> Gear " + targetGear + "]", net.kyori.adventure.text.format.NamedTextColor.YELLOW));
        }
        player.sendActionBar(message);
    }

    public void addControlledCraft(Craft c, Player p) {
        if (!(c instanceof PlayerCraft)) return;
        controlledCrafts.put(c, p);
        playerToCraft.put(p, (PlayerCraft) c);
        updateCraftGearFromSlot(p, (PlayerCraft) c, p.getInventory().getHeldItemSlot());
    }

    public void removeControlledCraft(Craft c) {
        Player p = controlledCrafts.remove(c);
        if (p != null) {
            playerToCraft.remove(p);
            internalAircraftGears.remove(p);
        }
    }

    public void addOrSetCooldown(Craft c, Long endTime) { 
        cooldowns.put(c, endTime); 
    }
}
