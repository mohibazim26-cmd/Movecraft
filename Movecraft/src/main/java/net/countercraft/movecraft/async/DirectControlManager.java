package net.countercraft.movecraft.async;

import net.countercraft.movecraft.CruiseDirection;
import net.countercraft.movecraft.craft.Craft;
import net.countercraft.movecraft.craft.CraftManager;
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
        for (Map.Entry<Craft, Player> controlledCraft : controlledCrafts.entrySet())
        {
            if(controlledCraft.getKey() == null || controlledCraft.getValue() == null) {
                toRemove.add(controlledCraft.getKey());
                continue;
            }
            Player player = controlledCraft.getValue();
            PlayerCraft pCraft = (PlayerCraft)controlledCraft.getKey();

            // ==========================================
            // NUOVA LOGICA: MANETTA (THROTTLE) CON OROLOGIO
            // ==========================================
            String craftName = pCraft.getType().getStringProperty(net.countercraft.movecraft.craft.type.CraftType.NAME).toLowerCase();
            if (craftName.contains("Fighter") || craftName.contains("Bomber")) {
                ItemStack mainHand = player.getInventory().getItemInMainHand();
                if (mainHand != null && mainHand.getType() == Material.CLOCK) {
                    int maxGearShifts = pCraft.getType().getIntProperty(net.countercraft.movecraft.craft.type.CraftType.GEAR_SHIFTS);
                    if (maxGearShifts > 1) {
                        int currentSlot = player.getInventory().getHeldItemSlot();
                        // Mappatura lineare: Slot 0 (Hotbar 1) -> Marcia 1 | Slot 8 (Hotbar 9) -> Marcia Max
                        int targetGear = (int) Math.round(((double) currentSlot / 8.0) * (maxGearShifts - 1)) + 1;
                        
                        if (pCraft.getCurrentGear() != targetGear) {
                            pCraft.setCurrentGear(targetGear);
                            player.spigot().sendMessage(ChatMessageType.ACTION_BAR, 
                                new TextComponent("§e§lMANETTA: §fGear " + targetGear + " / " + maxGearShifts + " §7(Slot " + (currentSlot + 1) + ")")
                            );
                        }
                    }
                }
            }
            // ==========================================

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
            CruiseDirection xDir = CruiseDirection.NONE;
            CruiseDirection zDir = CruiseDirection.NONE;

            if (movedX > 0.05)
                xDir = CruiseDirection.EAST;
            else if (movedX < -0.05)
                xDir = CruiseDirection.WEST;
            if (movedZ > 0.05)
                zDir = CruiseDirection.SOUTH;
            else if (movedZ < -0.05)
                zDir = CruiseDirection.NORTH;

            if(Math.abs(movedX) > 0 && !pCraft.getCruising()|| Math.abs(movedZ) > 0 && !pCraft.getCruising() || movedY > 0 && !pCraft.getCruising())
                pCraft.setCruising(true);

            CruiseDirection cd = pCraft.getCruiseDirection();
            if(xDir != CruiseDirection.NONE && zDir != CruiseDirection.NONE)
            {
                if (xDir == CruiseDirection.EAST)
                {
                    if (zDir == CruiseDirection.NORTH) cd = CruiseDirection.NORTHEAST;
                    else cd = CruiseDirection.SOUTHEAST;
                }
                else
                {
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
        }
        playerToCraft.put(p, (PlayerCraft) c);

        // ==========================================
        // NUOVA LOGICA: AUTO-CRUISE CORRENTE AL DECOLLO (/dc)
        // ==========================================
        String craftName = c.getType().getStringProperty(net.countercraft.movecraft.craft.type.CraftType.NAME).toLowerCase();
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
        // ==========================================
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
