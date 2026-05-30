package net.countercraft.movecraft.async;

import net.countercraft.movecraft.CruiseDirection;
import net.countercraft.movecraft.craft.Craft;
import net.countercraft.movecraft.craft.PlayerCraft;
import net.countercraft.movecraft.craft.type.CraftType;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.inventory.ItemStack;

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
        
        for (Map.Entry<Craft, Player> controlledCraft : controlledCrafts.entrySet()) {
            if (controlledCraft.getKey() == null || controlledCraft.getValue() == null) {
                toRemove.add(controlledCraft.getKey());
                continue;
            }
            
            Player player = controlledCraft.getValue();
            PlayerCraft pCraft = (PlayerCraft) controlledCraft.getKey();

            if (pCraft.getDisabled()) {
                toRemove.add(pCraft);
                continue;
            }

            // Manteniamo Fighter e Bomber esattamente con il case-sensitive originale
            String craftName = pCraft.getType().getStringProperty(CraftType.NAME);

            // ==========================================
            // LOGICA COMBAT AIRCRAFT (Fighter / Bomber)
            // ==========================================
            if (craftName.contains("Fighter") || craftName.contains("Bomber")) {

                // --- 1. GESTIONE MANETTA (THROTTLE) ---
                ItemStack mainHand = player.getInventory().getItemInMainHand();
                if (mainHand != null && mainHand.getType() == Material.CLOCK) {
                    int currentSlot = player.getInventory().getHeldItemSlot(); 
                    int targetGear = currentSlot + 1; 

                    if (pCraft.getCurrentGear() != targetGear) {
                        pCraft.setCurrentGear(targetGear);
                        
                        // Cooldown dinamico inverso (Slot 9 = 2 tick, Slot 1 = 15 tick)
                        int calculatedCooldown = 15 - (int) Math.round(((double) currentSlot / 8.0) * 13.0);
                        pCraft.setTickCooldown(calculatedCooldown);

                        double bps = (20.0 / calculatedCooldown) * 3.0;
                        String msg = "§e§lMANETTA: Gear " + targetGear + "/9 §7(" + String.format("%.1f", bps) + " Blocs/s)";
                        
                        // Alternativa nativa super-compatibile che non richiede import md_5/bungee
                        player.spigot().sendMessage(net.md_5.bungee.api.ChatMessageType.ACTION_BAR, 
                            net.md_5.bungee.api.chat.TextComponent.fromLegacyText(msg)
                        );
                    }
                }

                // --- 2. GESTIONE AUTOCRUISE E SHIFT CONTRO LO SPAM DI BASECRAFT ---
                if (player.isSneaking()) {
                    if (pCraft.getCruising()) {
                        pCraft.setCruising(false); // Eseguito solo UNA volta all'aggancio dello sneak
                    }
                    continue; 
                } else {
                    if (!pCraft.getCruising()) {
                        float yaw = player.getLocation().getYaw();
                        CruiseDirection newDir = CruiseDirection.NORTH;
                        if (yaw < 0) yaw += 360;
                        if (yaw >= 315 || yaw < 45) newDir = CruiseDirection.SOUTH;
                        else if (yaw >= 45 && yaw < 135) newDir = CruiseDirection.WEST;
                        else if (yaw >= 135 && yaw < 225) newDir = CruiseDirection.NORTH;
                        else if (yaw >= 225 && yaw < 315) newDir = CruiseDirection.EAST;

                        pCraft.setCruiseDirection(newDir);
                        pCraft.setCruising(true); // Eseguito solo UNA volta al rilascio dello sneak
                    }
                }

                // --- 3. LETTURA INPUT MOVIMENTO ---
                double[] delta = pendingMovements.remove(player);
                double movedX = 0, movedY = 0, movedZ = 0;

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
                        lastInput.remove(player);
                        lastInputTime.remove(player);
                    }
                }

                if (cooldowns.containsKey(pCraft)) {
                    if (cooldowns.get(pCraft) > System.currentTimeMillis()) continue;
                    else cooldowns.remove(pCraft);
                }

                // --- 4. CALCOLO DIREZIONALE AEREI ---
                CruiseDirection cruiseDir = pCraft.getCruiseDirection();
                int forwardX = 0;
                int forwardZ = 0;

                if (cruiseDir == CruiseDirection.NORTH) forwardZ = -1;
                else if (cruiseDir == CruiseDirection.SOUTH) forwardZ = 1;
                else if (cruiseDir == CruiseDirection.EAST) forwardX = 1;
                else if (cruiseDir == CruiseDirection.WEST) forwardX = -1;

                int rightX = -forwardZ;
                int rightZ = forwardX;

                int dx = forwardX * 3;
                int dy = 0;
                int dz = forwardZ * 3;

                Location eyeLoc = player.getLocation();
                Vector facingDir = eyeLoc.getDirection().setY(0).normalize();
                Vector inputDir = new Vector(movedX, 0, movedZ);

                if (inputDir.lengthSquared() > 0.002) {
                    inputDir.normalize();
                    double dotProduct = facingDir.dot(inputDir);
                    double crossProduct = facingDir.getX() * inputDir.getZ() - facingDir.getZ() * inputDir.getX();

                    if (dotProduct > 0.5) {
                        dy = -1; // W: Picchiata
                    } else if (dotProduct < -0.5) {
                        dy = 1;  // S: Cabrata
                    }
                    
                    if (crossProduct > 0.5) {
                        dx += rightX * 3; // D: Strafe Destra
                        dz += rightZ * 3;
                    } else if (crossProduct < -0.5) {
                        dx -= rightX * 3; // A: Strafe Sinistra
                        dz -= rightZ * 3;
                    }
                }

                if (dx != 0 || dy != 0 || dz != 0) {
                    pCraft.translate(pCraft.getWorld(), dx, dy, dz);
                }

            } else {
                // ==========================================
                // LOGICA VEICOLI STANDARD (Navi / Tank / Ecc)
                // ==========================================
                double[] delta = pendingMovements.remove(player);
                double movedX, movedY, movedZ;
                if (delta != null && (Math.abs(delta[0]) > 0.05 || Math.abs(delta[1]) > 0.05 || Math.abs(delta[2]) > 0.05)) {
                    movedX = delta[0]; movedY = delta[1]; movedZ = delta[2];
                    lastInput.put(player, delta);
                    lastInputTime.put(player, System.currentTimeMillis());
                } else {
                    Long t = lastInputTime.get(player);
                    double[] last = lastInput.get(player);
                    if (last != null && t != null && System.currentTimeMillis() - t < 150) {
                        movedX = last[0]; movedY = last[1]; movedZ = last[2];
                    } else {
                        movedX = 0; movedY = 0; movedZ = 0;
                        lastInput.remove(player); lastInputTime.remove(player);
                    }
                }

                if (cooldowns.containsKey(pCraft)) {
                    if (cooldowns.get(pCraft) > System.currentTimeMillis()) continue;
                    else cooldowns.remove(pCraft);
                }

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
                } else if (xDir != CruiseDirection.NONE) cd = xDir;
                else if (zDir != CruiseDirection.NONE) cd = zDir;

                if (movedY > 0.15) cd = CruiseDirection.UP;

                if (player.isSneaking()) {
                    if (!sneakTimes.containsKey(player))
                        sneakTimes.put(player, System.currentTimeMillis() + 250);
                    else if (sneakTimes.containsKey(player) && System.currentTimeMillis() > sneakTimes.get(player)) {
                        cd = CruiseDirection.DOWN;
                        if (!pCraft.getCruising()) pCraft.setCruising(true);
                    }
                } else {
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
        }
        playerToCraft.put(p, (PlayerCraft) c);

        String craftName = c.getType().getStringProperty(CraftType.NAME);
        if (craftName.contains("Fighter") || craftName.contains("Bomber")) {
            float yaw = p.getLocation().getYaw();
            CruiseDirection initialDir = CruiseDirection.NORTH;
            
            if (yaw < 0) yaw += 360;
            if (yaw >= 315 || yaw < 45) initialDir = CruiseDirection.SOUTH;
            else if (yaw >= 45 && yaw < 135) initialDir = CruiseDirection.WEST;
            else if (yaw >= 135 && yaw < 225) initialDir = CruiseDirection.NORTH;
            else if (yaw >= 225 && yaw < 315) initialDir = CruiseDirection.EAST;

            c.setCruiseDirection(initialDir);
            c.setCruising(true); 

            net.countercraft.movecraft.listener.AircraftClockFollowerListener.moveClockToHand(p, p.getInventory().getHeldItemSlot());
        }
    }

    public void removeControlledCraft(Craft c) {
        Player p = controlledCrafts.remove(c);
        if (p != null) {
            playerToCraft.remove(p);
            pendingMovements.remove(p);
            lastInput.remove(p);
            lastInputTime.remove(p);
            sneakTimes.remove(p);
        }
    }

    public void addOrSetCooldown(Craft c, Long endTime) { cooldowns.put(c, endTime); }
}
