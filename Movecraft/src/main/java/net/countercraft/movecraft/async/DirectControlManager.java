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

    // Mappe per l'Inerzia e i Residui Decimali
    private final Map<Craft, Vector> currentVelocity = new HashMap<>();
    private final Map<Craft, Double> currentSpeedFactor = new HashMap<>();
    private final Map<Craft, Vector> residualMovements = new HashMap<>();

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerMove(PlayerMoveEvent event) {
        Player p = event.getPlayer();
        PlayerCraft pCraft = playerToCraft.get(p);
        if (pCraft == null) return;

        Location to = event.getTo();
        // Intercettiamo lo spostamento WASD del giocatore sul blocco
        pendingMovements.put(p, new double[]{
            to.getX() - pCraft.getPilotLockedX(),
            to.getY() - pCraft.getPilotLockedY(),
            to.getZ() - pCraft.getPilotLockedZ()
        });

        // Blocchiamo il movimento fisico del giocatore per tenerlo ancorato alla cabina
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
            // LOGICA COMBAT AIRCRAFT (Fighter / Bomber) - CONTROLLI WASD REALI
            // =================================================================
            if (craftName.contains("Fighter") || craftName.contains("Bomber")) {

                // FORZATURA REALE DEI 10 TICK (2 movimenti al secondo netti)
                if (cooldowns.containsKey(pCraft)) {
                    if (cooldowns.get(pCraft) > System.currentTimeMillis()) {
                        continue; // Salta il movimento finché non sono passati 500ms
                    } else {
                        cooldowns.remove(pCraft);
                    }
                }

                PlayerInventory inv = player.getInventory();
                int currentSlot = inv.getHeldItemSlot();

                // SWAP SICURO DELL'OROLOGIO (Nessun oggetto o bastone scompare)
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

                // GESTIONE MANETTA (Slot hotbar 1-9)
                ItemStack activeHand = inv.getItemInMainHand();
                if (activeHand != null && activeHand.getType() == Material.CLOCK) {
                    int targetGear = currentSlot + 1; 

                    if (pCraft.getCurrentGear() != targetGear) {
                        pCraft.setCurrentGear(targetGear);
                        String msg = "§e§lMANETTA: Gear " + targetGear + "/9";
                        player.spigot().sendMessage(net.md_5.bungee.api.ChatMessageType.ACTION_BAR, 
                            net.md_5.bungee.api.chat.TextComponent.fromLegacyText(msg)
                        );
                    }
                }

                // REGOLA SNEAK CCNet: Tenere premuto lo Shift ferma temporaneamente l'aereo
                if (player.isSneaking()) {
                    pCraft.setCruising(false); // Sgancia il cruise automatico di Movecraft
                    currentSpeedFactor.put(pCraft, 0.0); // Annulla l'inerzia per lo stop immediato
                    pendingMovements.remove(player);
                    continue; 
                }

                // Quando NON si è in Shift, l'aereo vola sempre verso la direzione cardinale dello sguardo
                float yaw = player.getLocation().getYaw();
                CruiseDirection cruiseDir = pCraft.getCruiseDirection();
                
                // Ricalcola la direzione cardinale fissa basata sullo sguardo (solo se non si è in Shift)
                CruiseDirection newDir = CruiseDirection.NORTH;
                if (yaw < 0) yaw += 360;
                if (yaw >= 315 || yaw < 45) newDir = CruiseDirection.SOUTH;
                else if (yaw >= 45 && yaw < 135) newDir = CruiseDirection.WEST;
                else if (yaw >= 135 && yaw < 225) newDir = CruiseDirection.NORTH;
                else if (yaw >= 225 && yaw < 315) newDir = CruiseDirection.EAST;

                if (cruiseDir != newDir) {
                    pCraft.setCruiseDirection(newDir);
                    cruiseDir = newDir;
                }
                pCraft.setCruising(true);

                // LETTURA DELL'INPUT WASD DAL PLAYER_MOVE_EVENT
                double[] delta = pendingMovements.remove(player);
                double movedX = 0, movedY = 0, movedZ = 0;

                if (delta != null && (Math.abs(delta[0]) > 0.01 || Math.abs(delta[1]) > 0.01 || Math.abs(delta[2]) > 0.01)) {
                    movedX = delta[0];
                    movedY = delta[2]; // Usiamo Z del movimento reale
                    lastInput.put(player, delta);
                    lastInputTime.put(player, System.currentTimeMillis());
                } else {
                    Long t = lastInputTime.get(player);
                    double[] last = lastInput.get(player);
                    // Mantiene vivo l'input per un brevissimo lasso di tempo (150ms) per evitare scatti
                    if (last != null && t != null && System.currentTimeMillis() - t < 150) {
                        movedX = last[0];
                        movedY = last[2];
                    } else {
                        lastInput.remove(player);
                        lastInputTime.remove(player);
                    }
                }

                // LETTURA DEI BLOCCHI AL SECONDO DALLA CONFIGURAZIONE
                int currentGear = pCraft.getCurrentGear();
                String gearPropertyName = "cruiseSkipBlocksGear" + currentGear;
                NamespacedKey gearKey = NamespacedKey.fromString("movecraft:" + gearPropertyName.toLowerCase());
                int blocksPerSecond = 0;
                if (gearKey != null) {
                    blocksPerSecond = pCraft.getType().getIntProperty(gearKey);
                }
                if (blocksPerSecond <= 0) {
                    blocksPerSecond = currentGear * 3; // Fallback
                }

                // ORIENTAMENTO CARDINALE DI BASE
                Vector targetDirectionVector = new Vector(0, 0, 0);
                if (cruiseDir == CruiseDirection.NORTH) targetDirectionVector.setZ(-1);
                else if (cruiseDir == CruiseDirection.SOUTH) targetDirectionVector.setZ(1);
                else if (cruiseDir == CruiseDirection.EAST) targetDirectionVector.setX(1);
                else if (cruiseDir == CruiseDirection.WEST) targetDirectionVector.setX(-1);

                // LOGICA DI INERZIA E DERAPATA IN CURVA
                Vector currentVelVec = currentVelocity.getOrDefault(pCraft, targetDirectionVector.clone());
                double speedFactor = currentSpeedFactor.getOrDefault(pCraft, 0.0);

                double angleDifference = currentVelVec.angle(targetDirectionVector);
                if (angleDifference > 0.1) {
                    speedFactor = Math.max(0.3, speedFactor * 0.6); // Derapata: perde velocità virando
                } else {
                    speedFactor = Math.min(1.0, speedFactor + 0.20); // Accelerazione in rettilineo
                }
                currentSpeedFactor.put(pCraft, speedFactor);

                currentVelVec.multiply(0.60).add(targetDirectionVector.multiply(0.40));
                if (currentVelVec.lengthSquared() > 0) currentVelVec.normalize();
                currentVelocity.put(pCraft, currentVelVec.clone());

                // FORMULA CORRETTA: Dividiamo la velocità totale al secondo per 2 (10 tick cooldown)
                double finalSpeedPerMovement = (blocksPerSecond * speedFactor) / 2.0;

                // TRADUZIONE DEI TASTI WASD REALI RISPETTO ALLA VISUALE
                Location eyeLoc = player.getLocation();
                Vector facingDir = eyeLoc.getDirection().setY(0).normalize();
                Vector inputDir = new Vector(movedX, 0, movedY);

                boolean movingForward = false;
                boolean movingBackward = false;
                boolean strafeLeft = false;
                boolean strafeRight = false;

                if (inputDir.lengthSquared() > 0.001) {
                    inputDir.normalize();
                    double dotForward = facingDir.dot(inputDir);
                    
                    if (dotForward > 0.3) movingForward = true;       // Premuto W
                    else if (dotForward < -0.3) movingBackward = true; // Premuto S
                    
                    Vector rightDir = new Vector(-facingDir.getZ(), 0, facingDir.getX());
                    double dotRight = rightDir.dot(inputDir);
                    if (dotRight > 0.3) strafeRight = true;           // Premuto D
                    else if (dotRight < -0.3) strafeLeft = true;      // Premuto A
                }

                int dy = 0;
                // REGOLA CCNet: W = Scende diagonalmente (-1 Y)
                if (movingForward) dy = -1;
                // REGOLA CCNet: S = Sale diagonalmente (+1 Y)
                if (movingBackward) dy = 1;

                // Calcolo della spinta base lungo la traiettoria d'inerzia
                double targetDx = currentVelVec.getX() * finalSpeedPerMovement;
                double targetDz = currentVelVec.getZ() * finalSpeedPerMovement;

                // Vettori di sbandamento laterale (A / D) relativi alla rotta cardinale
                int strafeX = 0, strafeZ = 0;
                int forwardZ = (int) targetDirectionVector.getZ();
                int forwardX = (int) targetDirectionVector.getX();
                if (cruiseDir == CruiseDirection.NORTH) { strafeX = -1; }
                else if (cruiseDir == CruiseDirection.SOUTH) { strafeX = 1; }
                else if (cruiseDir == CruiseDirection.EAST) { strafeZ = -1; }
                else if (cruiseDir == CruiseDirection.WEST) { strafeZ = 1; }

                // REGOLA CCNet: A o D muovono l'aereo diagonalmente rispetto alla rotta cardinale
                if (strafeLeft) {
                    targetDx += strafeX * finalSpeedPerMovement;
                    targetDz += (cruiseDir == CruiseDirection.NORTH || cruiseDir == CruiseDirection.SOUTH ? -forwardZ : forwardX) * finalSpeedPerMovement;
                }
                if (strafeRight) {
                    targetDx -= strafeX * finalSpeedPerMovement;
                    targetDz -= (cruiseDir == CruiseDirection.NORTH || cruiseDir == CruiseDirection.SOUTH ? -forwardZ : forwardX) * finalSpeedPerMovement;
                }

                // GESTIONE DEI RESIDUI DECIMALI PER LE MARCE DISPARI
                Vector residual = residualMovements.getOrDefault(pCraft, new Vector(0, 0, 0));
                double preciseDx = targetDx + residual.getX();
                double preciseDz = targetDz + residual.getZ();

                int dx = (int) (preciseDx >= 0 ? Math.floor(preciseDx) : Math.ceil(preciseDx));
                int dz = (int) (preciseDz >= 0 ? Math.floor(preciseDz) : Math.ceil(preciseDz));

                residual.setX(preciseDx - dx);
                residual.setZ(preciseDz - dz);
                residualMovements.put(pCraft, residual);

                // ESECUZIONE FORZATA DELLO SPOSTAMENTO FISICO AD OGNI TICK DEL COOLDOWN
                if (dx != 0 || dy != 0 || dz != 0) {
                    pCraft.translate(pCraft.getWorld(), dx, dy, dz);
                    
                    // Impone il blocco di 500ms (10 tick esatti) escludendo i limiti nativi di Movecraft
                    cooldowns.put(pCraft, System.currentTimeMillis() + 500L);
                }

            } else {
                // ==========================================================
                // LOGICA VEICOLI STANDARD (Navi / Tank / Submarines)
                // ==========================================================
                if (cooldowns.containsKey(pCraft)) {
                    if (cooldowns.get(pCraft) > System.currentTimeMillis()) continue;
                    else cooldowns.remove(pCraft);
                }

                double[] delta = pendingMovements.remove(player);
                double movedX = 0, movedY = 0, movedZ = 0;
                if (delta != null) {
                    movedX = delta[0]; movedY = delta[1]; movedZ = delta[2];
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
                else if (zDir != CruiseDirection.NONE) cd = zDir
