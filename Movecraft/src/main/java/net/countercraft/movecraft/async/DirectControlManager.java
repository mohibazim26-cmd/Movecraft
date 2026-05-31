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

    // --- STRUTTURE DATI FISICHE (INERZIA, DERAPATA E RESIDUI) ---
    private final Map<Craft, Vector> currentVelocity = new HashMap<>();
    private final Map<Craft, Double> currentSpeedFactor = new HashMap<>();
    private final Map<Craft, Vector> residualMovements = new HashMap<>();

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

        // Blocca la posizione fisica del player permettendo la rotazione della testa
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

            // =================================================================
            // LOGICA COMBAT AIRCRAFT (Fighter / Bomber) - Specifiche Richieste
            // =================================================================
            if (craftName.contains("Fighter") || craftName.contains("Bomber")) {

                // 1. IL FRENO D'EMERGENZA (Tasto Shift / Sneak)
                if (player.isSneaking()) {
                    if (pCraft.getCruising()) {
                        pCraft.setCruising(false); // Disattiva il movimento
                    }
                    // Congela l'inerzia azzerando la velocità accumulata
                    currentSpeedFactor.put(pCraft, 0.0);
                    currentVelocity.remove(pCraft);
                    residualMovements.remove(pCraft);
                    pendingMovements.remove(player);
                    continue; 
                } else {
                    // Rilasciando Shift, rileva istantaneamente il nuovo sguardo e blocca la rotta cardinale
                    if (!pCraft.getCruising()) {
                        float yaw = player.getLocation().getYaw();
                        if (yaw < 0) yaw += 360;
                        CruiseDirection newDir = CruiseDirection.NORTH;
                        if (yaw >= 315 || yaw < 45) newDir = CruiseDirection.SOUTH;
                        else if (yaw >= 45 && yaw < 135) newDir = CruiseDirection.WEST;
                        else if (yaw >= 135 && yaw < 225) newDir = CruiseDirection.NORTH;
                        else if (yaw >= 225 && yaw < 315) newDir = CruiseDirection.EAST;

                        pCraft.setCruiseDirection(newDir);
                        pCraft.setCruising(true); 
                    }
                }

                // GESTIONE COOLDOWN (Garantisce i 2 aggiornamenti precisi al secondo = 10 tick)
                if (cooldowns.containsKey(pCraft)) {
                    if (cooldowns.get(pCraft) > System.currentTimeMillis()) continue;
                    else cooldowns.remove(pCraft);
                }

                // 2. GESTIONE MANETTA (Hotbar Slot 1-9 con Orologio in mano)
                PlayerInventory inv = player.getInventory();
                int currentSlot = inv.getHeldItemSlot();
                ItemStack handItem = inv.getItemInMainHand();

                // Swap automatico e sicuro dell'orologio se presente nell'inventario
                if (handItem == null || handItem.getType() != Material.CLOCK) {
                    for (int i = 0; i < 36; i++) {
                        ItemStack item = inv.getItem(i);
                        if (item != null && item.getType() == Material.CLOCK) {
                            inv.setItem(i, handItem); 
                            inv.setItem(currentSlot, item); 
                            break;
                        }
                    }
                }

                // Lettura del Gear e calcolo della velocità massima teorica
                int targetGear = currentSlot + 1; 
                if (pCraft.getCurrentGear() != targetGear) {
                    pCraft.setCurrentGear(targetGear);
                    String msg = "§e§lMANETTA: Gear " + targetGear + "/9";
                    player.spigot().sendMessage(net.md_5.bungee.api.ChatMessageType.ACTION_BAR, 
                        net.md_5.bungee.api.chat.TextComponent.fromLegacyText(msg)
                    );
                }

                // Recupero sicuro delle proprietà di velocità senza generare crash (Usa try-catch di fallback)
                int blocksPerSecond = 0;
                try {
                    // Cerca nel file .craft (es: cruiseSkipBlocksGear1)
                    String gearPropertyName = "cruiseSkipBlocksGear" + targetGear;
                    blocksPerSecond = pCraft.getType().getIntProperty(gearPropertyName);
                } catch (Exception e) {
                    // Fallback matematico sicuro se la proprietà manca nella config del veicolo
                    blocksPerSecond = targetGear * 3; 
                }
                if (blocksPerSecond <= 0) blocksPerSecond = targetGear * 3;

                // 3. LETTURA INPUT TASTIERA DIRETTA (WASD)
                double[] delta = pendingMovements.remove(player);
                double movedX = 0, movedY = 0;

                if (delta != null && (Math.abs(delta[0]) > 0.05 || Math.abs(delta[2]) > 0.05)) {
                    movedX = delta[0];
                    movedY = delta[2];
                    lastInput.put(player, delta);
                    lastInputTime.put(player, System.currentTimeMillis());
                } else {
                    Long t = lastInputTime.get(player);
                    double[] last = lastInput.get(player);
                    if (last != null && t != null && System.currentTimeMillis() - t < 150) {
                        movedX = last[0];
                        movedY = last[2];
                    } else {
                        lastInput.remove(player);
                        lastInputTime.remove(player);
                    }
                }

                // 4. FISICA: CALCOLO INERZIA E DERAPATA VETTORIALE
                CruiseDirection cruiseDir = pCraft.getCruiseDirection();
                Vector targetDirectionVector = new Vector(0, 0, 0);

                if (cruiseDir == CruiseDirection.NORTH) targetDirectionVector.setZ(-1);
                else if (cruiseDir == CruiseDirection.SOUTH) targetDirectionVector.setZ(1);
                else if (cruiseDir == CruiseDirection.EAST) targetDirectionVector.setX(1);
                else if (cruiseDir == CruiseDirection.WEST) targetDirectionVector.setX(-1);

                Vector currentVelVec = currentVelocity.getOrDefault(pCraft, targetDirectionVector.clone());
                double speedFactor = currentSpeedFactor.getOrDefault(pCraft, 0.0);

                double angleDifference = 0.0;
                if (currentVelVec.lengthSquared() > 0 && targetDirectionVector.lengthSquared() > 0) {
                    angleDifference = currentVelVec.angle(targetDirectionVector);
                }

                // Derapata: Se l'angolo cambia bruscamente (curva), perde velocità e scivola lateralmente
                if (angleDifference > 0.1) {
                    speedFactor = Math.max(0.2, speedFactor * 0.6); // Taglio istantaneo al 60%
                } else {
                    speedFactor = Math.min(1.0, speedFactor + 0.20); // Accelerazione progressiva rettilinea (+20% a movimento)
                }
                currentSpeedFactor.put(pCraft, speedFactor);

                // Conservazione della traiettoria (60% vecchio vettore d'inerzia, 40% nuova direzione)
                currentVelVec.multiply(0.60).add(targetDirectionVector.multiply(0.40));
                if (currentVelVec.lengthSquared() > 0) {
                    currentVelVec.normalize();
                }
                currentVelocity.put(pCraft, currentVelVec.clone());

                // Spinta base risultante dall'inerzia dimezzata (poiché eseguiamo 2 cicli al secondo)
                double finalSpeedPerMovement = (blocksPerSecond * speedFactor) / 2.0;
                double targetDx = currentVelVec.getX() * finalSpeedPerMovement;
                double targetDz = currentVelVec.getZ() * finalSpeedPerMovement;

                // 5. TRADUZIONE DIREZIONALE SGUARDO PER TRADURRE WASD IN MANOVRE
                Location eyeLoc = player.getLocation();
                Vector facingDir = eyeLoc.getDirection().setY(0).normalize();
                Vector inputDir = new Vector(movedX, 0, movedY);

                boolean movingForward = false;
                boolean movingBackward = false;
                boolean strafeLeft = false;
                boolean strafeRight = false;

                if (inputDir.lengthSquared() > 0.002) {
                    inputDir.normalize();
                    double dotForward = facingDir.dot(inputDir);
                    
                    if (dotForward > 0.4) movingForward = true;
                    else if (dotForward < -0.4) movingBackward = true;
                    
                    Vector rightDir = new Vector(-facingDir.getZ(), 0, facingDir.getX());
                    double dotRight = rightDir.dot(inputDir);
                    if (dotRight > 0.4) strafeRight = true;
                    else if (dotRight < -0.4) strafeLeft = true;
                }

                // Spostamento Verticale (W = Scende di 1 blocco obliquo, S = Sale di 1 blocco obliquo)
                int dy = 0;
                if (movingForward) dy = -1;
                if (movingBackward) dy = 1;

                // Calcolo vettori ortogonali di sbandamento laterale (Strafe) basati sulla rotta fissa
                int strafeX = 0, strafeZ = 0;
                int forwardX = (int) targetDirectionVector.getX();
                int forwardZ = (int) targetDirectionVector.getZ();
                
                if (cruiseDir == CruiseDirection.NORTH) strafeX = -1;
                else if (cruiseDir == CruiseDirection.SOUTH) strafeX = 1;
                else if (cruiseDir == CruiseDirection.EAST) strafeZ = -1;
                else if (cruiseDir == CruiseDirection.WEST) strafeZ = 1;

                // Applicazione dello Strafe laterale diagonalmente alla velocità corrente
                if (strafeLeft) {
                    targetDx += strafeX * finalSpeedPerMovement;
                    targetDz += (cruiseDir == CruiseDirection.NORTH || cruiseDir == CruiseDirection.SOUTH ? -forwardZ : forwardX) * finalSpeedPerMovement;
                }
                if (strafeRight) {
                    targetDx -= strafeX * finalSpeedPerMovement;
                    targetDz -= (cruiseDir == CruiseDirection.NORTH || cruiseDir == CruiseDirection.SOUTH ? -forwardZ : forwardX) * finalSpeedPerMovement;
                }

                // 6. ACCUMULO DEI RESIDUI DECIMALI
                Vector residual = residualMovements.getOrDefault(pCraft, new Vector(0, 0, 0));
                double preciseDx = targetDx + residual.getX();
                double preciseDz = targetDz + residual.getZ();

                int dx = (int) (preciseDx >= 0 ? Math.floor(preciseDx) : Math.ceil(preciseDx));
                int dz = (int) (preciseDz >= 0 ? Math.floor(preciseDz) : Math.ceil(preciseDz));

                residual.setX(preciseDx - dx);
                residual.setZ(preciseDz - dz);
                residualMovements.put(pCraft, residual);

                // 7. INIEZIONE FLUIDA SUL THREAD PRINCIPALE (Previene i crash e attiva i sensori Movecraft)
                if (dx != 0 || dy != 0 || dz != 0) {
                    final int finalDx = dx;
                    final int finalDy = dy;
                    final int finalDz = dz;
                    
                    // Sincronizziamo lo spostamento dei blocchi sul thread di Minecraft per una totale stabilità
                    org.bukkit.Bukkit.getScheduler().runTask(org.bukkit.Bukkit.getPluginManager().getPlugin("Movecraft"), () -> {
                        if (!pCraft.getDisabled() && pCraft.getCruising()) {
                            pCraft.translate(pCraft.getWorld(), finalDx, finalDy, finalDz);
                        }
                    });
                }

                // Setta il cooldown preciso a 10 tick (500ms)
                cooldowns.put(pCraft, System.currentTimeMillis() + 500L);

            } else {
                // ==========================================================
                // LOGICA VEICOLI STANDARD (Rimasta originale e intatta)
                // ==========================================================
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
            currentVelocity.remove(c);
            currentSpeedFactor.remove(c);
            residualMovements.remove(c);
        }
    }

    public void addOrSetCooldown(Craft c, Long endTime) { cooldowns.put(c, endTime); }
}
