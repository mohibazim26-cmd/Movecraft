package net.countercraft.movecraft.async;

import net.countercraft.movecraft.CruiseDirection;
import net.countercraft.movecraft.craft.Craft;
import net.countercraft.movecraft.craft.PlayerCraft;
import net.countercraft.movecraft.craft.type.CraftType;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
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
    private final Map<Craft, Vector> residualMovements = new HashMap<>();

    // --- STRUTTURE PER FISICA E INERZIA ---
    private final Map<Craft, Vector> currentVelocity = new HashMap<>();
    private final Map<Craft, Double> currentSpeedFactor = new HashMap<>();
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

            String craftName = pCraft.getType().getStringProperty(CraftType.NAME).toLowerCase();

            // =================================================================
            // LOGICA COMBAT AIRCRAFT (Fighter / Bomber) - Bilanciata e Inerziale
            // =================================================================
            if (craftName.contains("fighter") || craftName.contains("bomber")) {

                PlayerInventory inv = player.getInventory();
                int currentSlot = inv.getHeldItemSlot();

                // SWAP AUTOMATICO MANETTA (Usa l'orologio, cambialo in COMPASS se usi la bussola)
                ItemStack handItem = inv.getItemInMainHand();
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

                // SINCRO MARCE CON L'HOTBAR
                ItemStack activeHand = inv.getItemInMainHand();
                if (activeHand != null && activeHand.getType() == Material.CLOCK) {
                    int targetGear = currentSlot + 1; 

                    if (pCraft.getCurrentGear() != targetGear) {
                        pCraft.setCurrentGear(targetGear);
                        
                        double bps = 2.0 + ((double) currentSlot * (13.0 / 8.0));
                        String msg = "§e§lMANETTA: Gear " + targetGear + "/9 §7(" + String.format("%.1f", bps) + " Blocs/s)";
                        player.spigot().sendMessage(net.md_5.bungee.api.ChatMessageType.ACTION_BAR, 
                            net.md_5.bungee.api.chat.TextComponent.fromLegacyText(msg)
                        );
                    }
                }

                // FRENO E AUTOCRUISE (Preso dal tuo sistema di isolamento totale)
                boolean isSneaking = player.isSneaking();
                boolean wasSneaking = lastSneakState.getOrDefault(player, false);
                lastSneakState.put(player, isSneaking);

                if (isSneaking) {
                    if (pCraft.getCruising()) pCraft.setCruising(false);
                    currentSpeedFactor.put(pCraft, 0.0);
                    currentVelocity.remove(pCraft);
                    residualMovements.remove(pCraft);
                    pendingMovements.remove(player);
                    continue; 
                }

                if (!pCraft.getCruising() || (!isSneaking && wasSneaking)) {
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

                // LETTURA INPUT MOVIMENTO TASTIERA
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

                // NESSUN COOLDOWN INTERNO RIGIDO: Lasciamo che la config .craft o il tick rate nativo gestiscano il refresh.

                // LETTURA VELOCITÀ MASSIMA DALLA CONFIG CONFIGURATA (.craft)
                int currentGear = pCraft.getCurrentGear();
                String gearPropertyName = "cruiseSkipBlocksGear" + currentGear;
                NamespacedKey gearKey = NamespacedKey.fromString("movecraft:" + gearPropertyName.toLowerCase());
                
                int blocksPerSecond = 0;
                if (gearKey != null) {
                    blocksPerSecond = pCraft.getType().getIntProperty(gearKey);
                }
                if (blocksPerSecond <= 0) {
                    blocksPerSecond = currentGear * 2; // Fallback lineare di sicurezza
                }

                // CALCOLO MATEMATICO DINAMICO DELL'INERZIA (Mantiene la fluidità impedendo scatti)
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

                // Forza centrifuga: riduce la velocità nelle virate per simulare la derapata aerodinamica
                if (angleDifference > 0.1) {
                    speedFactor = Math.max(0.4, speedFactor * 0.65); 
                } else {
                    speedFactor = Math.min(1.0, speedFactor + 0.20); // Progressione morbida in rettilineo
                }
                currentSpeedFactor.put(pCraft, speedFactor);

                // Conservazione della traiettoria precedente (60% vecchio vettore, 40% nuovo input)
                currentVelVec.multiply(0.60).add(targetDirectionVector.multiply(0.40));
                if (currentVelVec.lengthSquared() > 0) {
                    currentVelVec.normalize();
                }
                currentVelocity.put(pCraft, currentVelVec.clone());

                // 🟢 CORREZIONE QUI: Calcolo dello spazio basato su 20 tick al secondo
                double finalSpeedPerMovement = (blocksPerSecond * speedFactor) / 20.0;
                double targetDx = currentVelVec.getX() * finalSpeedPerMovement;
                double targetDz = currentVelVec.getZ() * finalSpeedPerMovement;

                // TRADUZIONE INPUT DELLO SGUARDO (WASD)
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

                // Controllo asse verticale (W = Scende, S = Sale)
                int dy = 0;
                if (movingForward) dy = -1;
                if (movingBackward) dy = 1;

                // Calcolo dello strafe laterale ortogonale (A / D)
                int strafeX = 0, strafeZ = 0;
                int forwardX = (int) targetDirectionVector.getX();
                int forwardZ = (int) targetDirectionVector.getZ();

                if (cruiseDir == CruiseDirection.NORTH) strafeX = -1;
                else if (cruiseDir == CruiseDirection.SOUTH) strafeX = 1;
                else if (cruiseDir == CruiseDirection.EAST) strafeZ = -1;
                else if (cruiseDir == CruiseDirection.WEST) strafeZ = 1;

                if (strafeLeft) {
                    targetDx += strafeX * finalSpeedPerMovement;
                    targetDz += (cruiseDir == CruiseDirection.NORTH || cruiseDir == CruiseDirection.SOUTH ? -forwardZ : forwardX) * finalSpeedPerMovement;
                }
                if (strafeRight) {
                    targetDx -= strafeX * finalSpeedPerMovement;
                    targetDz -= (cruiseDir == CruiseDirection.NORTH || cruiseDir == CruiseDirection.SOUTH ? -forwardZ : forwardX) * finalSpeedPerMovement;
                }

                // Gestione dei decimali residui per calcolare con precisione i movimenti frazionari
                Vector residual = residualMovements.getOrDefault(pCraft, new Vector(0, 0, 0));
                double preciseDx = targetDx + residual.getX();
                double preciseDz = targetDz + residual.getZ();

                int dx = (int) (preciseDx >= 0 ? Math.floor(preciseDx) : Math.ceil(preciseDx));
                int dz = (int) (preciseDz >= 0 ? Math.floor(preciseDz) : Math.ceil(preciseDz));

                residual.setX(preciseDx - dx);
                residual.setZ(preciseDz - dz);
                residualMovements.put(pCraft, residual);

                // ESECUZIONE DELLA TRASLAZIONE DEI BLOCCHI SUL THREAD PRINCIPALE
                if (dx != 0 || dy != 0 || dz != 0) {
                    final int finalDx = dx;
                    final int finalDy = dy;
                    final int finalDz = dz;
                    
                    org.bukkit.Bukkit.getScheduler().runTask(org.bukkit.Bukkit.getPluginManager().getPlugin("Movecraft"), () -> {
                        if (!pCraft.getDisabled() && pCraft.getCruising()) {
                            pCraft.translate(pCraft.getWorld(), finalDx, finalDy, finalDz);
                        }
                    });
                }

            } else {
                // ==========================================================
                // LOGICA VEICOLI STANDARD (Navi / Tank / Ecc. - Intatta)
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
                
                // Cooldown standard solo per i veicoli non-aerei
                NamespacedKey cooldownKey = NamespacedKey.fromString("movecraft:tickcooldown");
                int tickCooldown = 10;
                if (cooldownKey != null) {
                    tickCooldown = pCraft.getType().getIntProperty(cooldownKey);
                }
                cooldowns.put(pCraft, System.currentTimeMillis() + (tickCooldown * 50L));
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
            lastSneakState.remove(oldPlayer);
        }
        playerToCraft.put(p, (PlayerCraft) c);

        String craftName = c.getType().getStringProperty(CraftType.NAME).toLowerCase();
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
            lastSneakState.remove(p);
            residualMovements.remove(c);
            currentVelocity.remove(c);
            currentSpeedFactor.remove(c);
        }
    }

    public void addOrSetCooldown(Craft c, Long endTime) { cooldowns.put(c, endTime); }
}
