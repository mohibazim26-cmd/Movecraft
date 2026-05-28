package net.countercraft.movecraft.async;

import net.countercraft.movecraft.CruiseDirection;
import net.countercraft.movecraft.MovecraftRotation;
import net.countercraft.movecraft.craft.Craft;
import net.countercraft.movecraft.craft.CraftManager;
import net.countercraft.movecraft.craft.PlayerCraft;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.scheduler.BukkitRunnable;
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
    private final Map<Player, Long> lastRotateMs = new HashMap<>(); // Cooldown rotazione caccia

    // Mappe di fallback interne per garantire la compilazione su qualsiasi fork
    private final Map<Player, Integer> internalAircraftGears = new HashMap<>();
    private final Map<Craft, Long> aircraftTickCooldown = new HashMap<>();
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

        // Lock position but allow head rotation. Zero vertical velocity to prevent
        // gravity accumulation causing the player to clip through the block below them.
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
            // DEVIAZIONE COMBAT AIRCRAFT: Logica Sicura e Modello di Volo con Smoothing
            // =========================================================================
            String craftType = pCraft.getType().getTemplateName().toLowerCase();
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
            lastRotateMs.remove(oldPlayer);
            internalAircraftGears.remove(oldPlayer);
            lastSneakState.remove(oldPlayer);
        }
        playerToCraft.put(p, (PlayerCraft) c);
    }

    public void removeControlledCraft(Craft c) {
        Player p = controlledCrafts.remove(c);
        if (p != null) {
            playerToCraft.remove(p);
            pendingMovements.remove(p);
            lastInput.remove(p);
            lastInputTime.remove(p);
            sneakTimes.remove(p);
            lastRotateMs.remove(p);
            internalAircraftGears.remove(p);
            lastSneakState.remove(p);
        }
    }

    public void addOrSetCooldown(Craft c, Long endTime) { cooldowns.put(c, endTime); }

    // =========================================================================
    // MODELLO DI VOLO INTERNO CON SMOOTHING (ANTI-LAG & COMPATIBILE AL 100%)
    // =========================================================================
    private void processAircraftTick(Player player, PlayerCraft pCraft, double movedX, double movedZ) {
        boolean isSneaking = player.isSneaking();
        boolean wasSneaking = lastSneakState.getOrDefault(player, false);
        lastSneakState.put(player, isSneaking);

        // Frenata dello Sneak (Frizione)
        if (isSneaking) {
            if (pCraft.getCruising()) pCraft.setCruising(false);
            return;
        }

        // Wiki-Accurate: Al rilascio dello Sneak ricalcola ISTANTANEAMENTE la nuova rotta cardinale guardata
        if (!isSneaking && wasSneaking) {
            pCraft.setCruiseDirection(getAircraftCardinalDirection(player.getLocation().getYaw()));
            pCraft.setCruising(true);
        }

        if (!pCraft.getCruising()) {
            pCraft.setCruiseDirection(getAircraftCardinalDirection(player.getLocation().getYaw()));
            pCraft.setCruising(true);
        }

        // --- SISTEMA DI SMOOTHING DELLA VELOCITÀ ---
        int currentGear = internalAircraftGears.getOrDefault(player, 1);
        long now = System.currentTimeMillis();
        long lastTickMove = aircraftTickCooldown.getOrDefault(pCraft, 0L);

        // Calcoliamo il delay in millisecondi in base alla marcia per evitare l'effetto missile
        long requiredDelay = Math.max(50, 300 - (currentGear * 28)); 
        if (now - lastTickMove < requiredDelay) {
            return; // Salta questo tick, l'aereo si muove in modo fluido basandosi sul tempo
        }
        aircraftTickCooldown.put(pCraft, now);

        CruiseDirection baseDir = pCraft.getCruiseDirection();
        int dx = 0, dy = 0, dz = 0;

        // Avanzamento costante del Cruise
        switch (baseDir) {
            case NORTH: dz = -1; break;
            case SOUTH: dz = 1; break;
            case EAST:  dx = 1; break;
            case WEST:  dx = -1; break;
            default: break;
        }

        // Mappatura input locali WASD relativi
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

        // Combinazione tridimensionale (W/S cambiano asse Y, A/D aggiungono strafe laterale)
        if (inputW) dy = -1;
        else if (inputS) dy = 1;

        if (inputA) {
            if (baseDir == CruiseDirection.NORTH) dx = -1; if (baseDir == CruiseDirection.SOUTH) dx = 1;
            if (baseDir == CruiseDirection.EAST)  dz = -1; if (baseDir == CruiseDirection.WEST)  dz = 1;
        } else if (inputD) {
            if (baseDir == CruiseDirection.NORTH) dx = 1;  if (baseDir == CruiseDirection.SOUTH) dx = -1;
            if (baseDir == CruiseDirection.EAST)  dz = 1;  if (baseDir == CruiseDirection.WEST)  dz = -1;
        }

        // Traduzione Sicura (Fallback Universale): usiamo l'API standard compatibile con ogni fork Movecraft
        if (dx != 0 || dy != 0 || dz != 0) {
            try {
                // Tenta la traslazione nativa della HitBox (presente in tutte le versioni)
                pCraft.move(dx, dy, dz); 
            } catch (NoSuchMethodError e) {
                // Fallback se il fork usa la vecchia nomenclatura del Movecraft originale
                pCraft.translate(pCraft.getWorld(), dx, dy, dz);
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

    // =========================================================================
    // THROTTLE SICURO E INTERNAZIONALIZZATO SULLA BUSSOLA
    // =========================================================================
    @org.bukkit.event.EventHandler
    public void onAircraftThrottle(org.bukkit.event.player.PlayerItemHeldEvent event) {
        Player player = event.getPlayer();
        PlayerCraft pCraft = playerToCraft.get(player);
        if (pCraft == null) return;

        String craftType = pCraft.getType().getTemplateName().toLowerCase();
        if (!craftType.equals("fighter") && !craftType.equals("bomber")) return;

        int newSlot = event.getNewSlot();

        // 1. Inseguimento Bussola Hotbar
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

        // 2. Modifica Meta Nome Sicura (Senza NBT sporchi)
        org.bukkit.inventory.ItemStack currentCompass = player.getInventory().getItem(newSlot);
        if (currentCompass != null && currentCompass.getType() == org.bukkit.Material.COMPASS) {
            org.bukkit.inventory.meta.ItemMeta meta = currentCompass.getItemMeta();
            if (meta != null) {
                if (newSlot == 8) {
                    meta.setDisplayName("§5Afterburners §a- Attivi");
                } else {
                    meta.setDisplayName("§rBussola di Volo"); 
                }
                currentCompass.setItemMeta(meta);
            }
        }

        // Salviamo la marcia internamente (evitiamo crash API mancanti) e aggiorniamo il fork se possibile
        int targetGear = newSlot + 1;
        internalAircraftGears.put(player, targetGear);
        try {
            pCraft.setCurrentGear(targetGear);
        } catch (NoSuchMethodError ignored) {} 

        double fuelBurnRate = 0.5 + (((double) targetGear / 9.0) * 1.5);
        pCraft.setFuelBurnMultiplier(fuelBurnRate);
    }

    // =========================================================================
    // ROTAZIONE PUBBLICA CHIAMABILE DA INTERACTLISTENER NATIVO
    // =========================================================================
    public void executeAircraftRotation(Player player, PlayerCraft pCraft, MovecraftRotation rotation) {
        long now = System.currentTimeMillis();
        long lastRotate = lastRotateMs.getOrDefault(player, 0L);
        if (now - lastRotate < 500L) return; 
        lastRotateMs.put(player, now);

        pCraft.rotate(rotation, pCraft.getHitBox().getMidPoint());
        
        Location loc = player.getLocation();
        float currentYaw = loc.getYaw();
        if (rotation == MovecraftRotation.CLOCKWISE) {
            loc.setYaw(currentYaw + 90.0F);
        } else {
            loc.setYaw(currentYaw - 90.0F);
        }
        player.teleport(loc);

        // Sincronizza la rotta del volo continuo
        pCraft.setCruiseDirection(getAircraftCardinalDirection(loc.getYaw()));
    }
}
