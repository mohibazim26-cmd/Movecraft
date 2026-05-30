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
import org.bukkit.inventory.PlayerInventory;

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

            String craftName = pCraft.getType().getStringProperty(CraftType.NAME);

            // ==========================================
            // LOGICA COMBAT AIRCRAFT (Fighter / Bomber)
            // ==========================================
            if (craftName.contains("Fighter") || craftName.contains("Bomber")) {

                PlayerInventory inv = player.getInventory();
                int currentSlot = inv.getHeldItemSlot();

                // Ripristino automatico dell'orologio nello slot corrente
                ItemStack handItem = inv.getItemInMainHand();
                if (handItem == null || handItem.getType() != Material.CLOCK) {
                    for (int i = 0; i < 36; i++) {
                        ItemStack item = inv.getItem(i);
                        if (item != null && item.getType() == Material.CLOCK) {
                            inv.setItem(i, null); 
                            inv.setItem(currentSlot, item); 
                            break;
                        }
                    }
                }

                // GESTIONE MANETTA (Cambio Marcia)
                ItemStack activeHand = inv.getItemInMainHand();
                if (activeHand != null && activeHand.getType() == Material.CLOCK) {
                    int targetGear = currentSlot + 1; 

                    if (pCraft.getCurrentGear() != targetGear) {
                        pCraft.setCurrentGear(targetGear);
                        
                        // Calcola la velocità teorica da mostrare in actionbar
                        double blocksPerThreeSeconds = 5.0 + ((double) currentSlot * (10.0 / 8.0));
                        String msg = "§e§lMANETTA: Gear " + targetGear + "/9 §7(" + String.format("%.1f", blocksPerThreeSeconds) + " Blocs/3s)";
                        
                        player.spigot().sendMessage(net.md_5.bungee.api.ChatMessageType.ACTION_BAR, 
                            net.md_5.bungee.api.chat.TextComponent.fromLegacyText(msg)
                        );
                    }
                }

                // GESTIONE AUTOCRUISE
                if (player.isSneaking()) {
                    if (pCraft.getCruising()) {
                        pCraft.setCruising(false); 
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
                        pCraft.setCruising(true); 
                    }
                }

                // LETTURA INPUT MOVIMENTO
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

                // Controllo del Cooldown nativo di Movecraft (Usa i tickCooldown del file .craft)
                if (cooldowns.containsKey(pCraft)) {
                    if (cooldowns.get(pCraft) > System.currentTimeMillis()) continue;
                    else cooldowns.remove(pCraft);
                }

                // --- CALCOLO VELOCITÀ BASATO SUL FILE .CRAFT (Risolto il bug della super velocità) ---
                int currentGear = pCraft.getCurrentGear();
                // Prende lo skip dei blocchi impostato nel file .craft per la marcia attuale (es: cruiseSkipBlocksGear3)
                int speedMultiplier = pCraft.getType().getIntProperty(CraftType.getPerGearProperty("cruiseSkipBlocks", currentGear));
                if (speedMultiplier <= 0) {
                    speedMultiplier = 1; // Sicurezza se non trova la marcia
                }

                CruiseDirection cruiseDir = pCraft.getCruiseDirection();
                int forwardX = 0;
                int forwardZ = 0;

                if (cruiseDir == CruiseDirection.NORTH) forwardZ = -1;
                else if (cruiseDir == CruiseDirection.SOUTH) forwardZ = 1;
                else if (cruiseDir == CruiseDirection.EAST) forwardX = 1;
                else if (cruiseDir == CruiseDirection.WEST) forwardX = -1;

                int rightX = -forwardZ;
                int rightZ = forwardX;

                // MODIFICATO: Moltiplica per lo speedMultiplier dinamico preso dal file .craft
                int dx = forwardX * speedMultiplier;
                int dy = 0;
                int dz = forwardZ * speedMultiplier;

                Location eyeLoc = player.getLocation();
                Vector facingDir = eyeLoc.getDirection().setY(0).normalize();
                Vector inputDir = new Vector(movedX, 0, movedZ);

                if (inputDir.lengthSquared() > 0.002) {
                    inputDir.normalize();
                    double dotProduct = facingDir.dot(inputDir);
                    double crossProduct = facingDir.getX() * inputDir.getZ() - facingDir.getZ() * inputDir.getX();

                    if (dotProduct > 0.5) {
                        dy = -1; 
                    } else if (dotProduct < -0.5) {
                        dy = 1;  
                    }
                    
                    if (crossProduct > 0.5) {
                        dx += rightX * speedMultiplier; 
                        dz += rightZ * speedMultiplier;
                    } else if (crossProduct < -0.5) {
                        dx -= rightX * speedMultiplier; 
                        dz -= rightZ * speedMultiplier;
                    }
                }

                if (dx != 0 || dy != 0 || dz != 0) {
                    pCraft.translate(pCraft.getWorld(), dx, dy, dz);
                    // Applica il cooldown corretto salvando il tempo di blocco (evita lo spam continuo)
                    long cooldownMs = pCraft.getType().getIntProperty(CraftType.TICK_COOLDOWN) * 50L;
                    cooldowns.put(pCraft, System.currentTimeMillis() + cooldownMs);
                }

            } else {
                // ==========================================
                // LOGICA VEICOLI STANDARD (Rimasta invariata)
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
