package net.countercraft.movecraft.async;

import net.countercraft.movecraft.CruiseDirection;
import net.countercraft.movecraft.craft.Craft;
import net.countercraft.movecraft.craft.PlayerCraft;
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
import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.TextComponent;

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

            // Sicurezza: Se Movecraft ha disabilitato il mezzo internamente, esce dal loop
            if (pCraft.getDisabled()) {
                toRemove.add(pCraft);
                continue;
            }

            String craftName = pCraft.getType().getStringProperty(net.countercraft.movecraft.craft.type.CraftType.NAME).toLowerCase();

            // ==========================================
            // CONDIZIONE COMBAT AIRCRAFT (Fighter / Bomber)
            // ==========================================
            if (craftName.contains("fighter") || craftName.contains("bomber")) {

                // --- 1. GESTIONE MANETTA DI PRECISIONE CORRETTA ---
                ItemStack mainHand = player.getInventory().getItemInMainHand();
                if (mainHand != null && mainHand.getType() == Material.CLOCK) {
                    int currentSlot = player.getInventory().getHeldItemSlot(); // 0 a 8
                    int maxGearShifts = 9;

                    // CCNet: Scrolling right aumenta velocità. Slot 0 (Tasto 1) = Marcia Lenta, Slot 8 (Tasto 9) = Marcia Rapida
                    // Invertiamo l'indice matematico per fare in modo che Gear 1 sia il più lento e Gear 9 il più veloce
                    int targetGear = currentSlot + 1; 

                    if (pCraft.getCurrentGear() != targetGear) {
                        pCraft.setCurrentGear(targetGear);
                        
                        // Calcolo Cooldown lineare inverso per agganciare le velocità reali:
                        // Slot 8 (Gear 9) -> 2 tick di cooldown (30 blocchi/s)
                        // Slot 0 (Gear 1) -> 15 tick di cooldown (4 blocchi/s)
                        int calculatedCooldown = 15 - (int) Math.round(((double) currentSlot / 8.0) * 13.0);
                        pCraft.setTickCooldown(calculatedCooldown);

                        double bps = (20.0 / calculatedCooldown) * 3.0;
                        player.spigot().sendMessage(ChatMessageType.ACTION_BAR, 
                            new TextComponent("§e§lMANETTA: Gear " + targetGear + "/9 §7(" + String.format("%.1f", bps) + " Blocs/s)")
                        );
                    }
                }

                // --- 2. GESTIONE PAUSA ED AUTO-CRUISE CARDINALE (SHIFT) ---
                if (player.isSneaking()) {
                    if (pCraft.getCruising()) {
                        pCraft.setCruising(false);
                    }
                    continue; // In modalità frenata l'aereo non esegue spostamenti
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

                // --- 3. LETTURA DEGLI INPUT DEL PILOTA ---
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

                // --- 4. CALCOLO VETTORIALE DEL VOLO COMBAT AIRCRAFT ---
                CruiseDirection cruiseDir = pCraft.getCruiseDirection();
                int forwardX = 0;
                int forwardZ = 0;

                if (cruiseDir == CruiseDirection.NORTH) forwardZ = -1;
                else if (cruiseDir == CruiseDirection.SOUTH) forwardZ = 1;
                else if (cruiseDir == CruiseDirection.EAST) forwardX = 1;
                else if (cruiseDir == CruiseDirection.WEST) forwardX = -1;

                // Vettore laterale destro rispetto alla direzione di crociera
                int rightX = -forwardZ;
                int rightZ = forwardX;

                int dx = 0;
                int dy = 0;
                int dz = 0;

                // Spostamento base del volo rettilineo uniforme (Usa il valore fisso sbloccato)
                dx += forwardX * 3;
                dz += forwardZ * 3;

                // Mappatura Tasti CCNet tramite coordinate locali del giocatore relative al Pitch/Yaw
                // W (Cammina in avanti -> Riduce la coordinata locale Z del client rispetto allo sguardo)
                // Usiamo un controllo sul Yaw del giocatore confrontato con la direzione del pacchetto di movimento
                Location eyeLoc = player.getLocation();
                Vector facingDir = eyeLoc.getDirection().setY(0).normalize();
                Vector inputDir = new Vector(movedX, 0, movedZ);

                if (inputDir.lengthSquared() > 0.002) {
                    inputDir.normalize();
                    double dotProduct = facingDir.dot(inputDir);
                    double crossProduct = facingDir.getX() * inputDir.getZ() - facingDir.getZ() * inputDir.getX();

                    // W: Picchiata Diagonale
                    if (dotProduct > 0.5) {
                        dy = -1;
                    }
                    // S: Cabrata Diagonale
                    else if (dotProduct < -0.5) {
                        dy = 1;
                    }
                    // D: Strafe Destra
                    if (crossProduct > 0.5) {
                        dx += rightX * 3;
                        dz += rightZ * 3;
                    }
                    // A: Strafe Sinistra
                    else if (crossProduct < -0.5) {
                        dx -= rightX * 3;
                        dz -= rightZ * 3;
                    }
                }

                // Esecuzione fisica dello spostamento aereo
                if (dx != 0 || dy != 0 || dz != 0) {
                    pCraft.translate(pCraft.getWorld(), dx, dy, dz);
                }

            } else {
                // ==========================================
                // LOGICA NATIVA PER TUTTI GLI ALTRI VEHICLES
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

        String craftName = c.getType().getStringProperty(net.countercraft.movecraft.craft.type.CraftType.NAME).toLowerCase();
        if (craftName.contains("fighter") || craftName.contains("bomber")) {
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
