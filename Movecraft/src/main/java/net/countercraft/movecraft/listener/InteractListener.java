/*
 * This file is part of Movecraft.
 *
 *     Movecraft is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 *
 *     Movecraft is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with Movecraft.  If not, see <http://www.gnu.org/licenses/>.
 */

package net.countercraft.movecraft.listener;

import net.countercraft.movecraft.Movecraft;
import net.countercraft.movecraft.MovecraftRotation;
import net.countercraft.movecraft.config.Settings;
import net.countercraft.movecraft.craft.Craft;
import net.countercraft.movecraft.craft.CraftManager;
import net.countercraft.movecraft.craft.PlayerCraft;
import net.countercraft.movecraft.craft.type.CraftType;
import net.countercraft.movecraft.localisation.I18nSupport;
import net.countercraft.movecraft.util.MathUtils;
import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.type.Switch;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.jetbrains.annotations.NotNull;

import java.util.HashMap;
import java.util.Map;
import java.util.WeakHashMap;

public final class InteractListener implements Listener {
    private final Map<Player, Long> timeMap = new WeakHashMap<>();
    private final Map<Player, Long> droppedMap = new HashMap<>();

    @EventHandler(priority = EventPriority.LOWEST) // LOWEST so that it runs before the other events
    public void onPlayerInteract(@NotNull PlayerInteractEvent e) {
        if (e.getAction() == Action.LEFT_CLICK_BLOCK || e.getAction() == Action.LEFT_CLICK_AIR) {
            if (droppedMap.containsKey(e.getPlayer()) && System.currentTimeMillis() < droppedMap.get(e.getPlayer())) {
                return;
            }

            if (e.getItem() != null && e.getItem().getType() == Settings.PilotTool) {
                final PlayerCraft craft = CraftManager.getInstance().getCraftByPlayer(e.getPlayer());
                if (craft != null) {
                    final Player player = e.getPlayer();
                    e.setCancelled(true);
                    // Delay activation by 2 ticks so a concurrent compass-drop rotation can
                    // populate droppedMap first.
                    Bukkit.getScheduler().runTaskLater(Movecraft.getInstance(), () -> {
                        if (droppedMap.containsKey(player) && System.currentTimeMillis() < droppedMap.get(player)) {
                            return;
                        }

                        if (!craft.getPilotLocked()) {
                            if (player.hasPermission("movecraft." + craft.getType().getStringProperty(CraftType.NAME) + ".move")
                                    && craft.getType().getBoolProperty(CraftType.CAN_DIRECT_CONTROL)) {
                                craft.setPilotLocked(true);
                                Movecraft.getInstance().getDirectControlManager().addControlledCraft(craft, player);
                                Movecraft.getInstance().getDirectControlManager().addOrSetCooldown(craft, System.currentTimeMillis() + 500);
                                craft.setPilotLockedX(player.getLocation().getBlockX() + 0.5);
                                craft.setPilotLockedY(player.getLocation().getY());
                                craft.setPilotLockedZ(player.getLocation().getBlockZ() + 0.5);
                                player.sendMessage(String.format(I18nSupport.getInternationalisedString("Entering Direct Control Mode")));
                            } else {
                                player.sendMessage(String.format(I18nSupport.getInternationalisedString("Insufficient Permissions")));
                            }
                        } else {
                            craft.setPilotLocked(false);
                            Movecraft.getInstance().getDirectControlManager().removeControlledCraft(craft);
                            player.sendMessage(String.format(I18nSupport.getInternationalisedString("Leaving Direct Control Mode")));
                        }
                    }, 2L);
                    return;
                }
            } else if (e.getAction() == Action.LEFT_CLICK_BLOCK) {
                BlockState state = e.getClickedBlock().getState();
                if (!(state instanceof Switch)) {
                    return;
                }

                Switch data = (Switch) state.getBlockData();
                if (data.isPowered()) {
                    data.setPowered(false);
                    e.getClickedBlock().setBlockData(data);
                    e.setCancelled(true);
                }
            }
        } else if (e.getAction() == Action.RIGHT_CLICK_BLOCK || e.getAction() == Action.RIGHT_CLICK_AIR) {
            if (e.getItem() == null || e.getItem().getType() != Settings.PilotTool) {
                return;
            }

            Player player = e.getPlayer();
            PlayerCraft craft = CraftManager.getInstance().getCraftByPlayer(player);
            if (craft == null) {
                return;
            }

            e.setCancelled(true);

            CraftType type = craft.getType();
            int currentGear = craft.getCurrentGear();
            int tickCooldown = (int) craft.getType().getPerWorldProperty(
                    CraftType.PER_WORLD_TICK_COOLDOWN, craft.getWorld());
            if (type.getBoolProperty(CraftType.GEAR_SHIFTS_AFFECT_DIRECT_MOVEMENT)
                    && type.getBoolProperty(CraftType.GEAR_SHIFTS_AFFECT_TICK_COOLDOWN)) {
                tickCooldown *= currentGear;
            }

            Long lastTime = timeMap.get(player);
            if (lastTime != null) {
                long ticksElapsed = (System.currentTimeMillis() - lastTime) / 50;

                if (craft.getType().getBoolProperty(CraftType.HALF_SPEED_UNDERWATER)
                        && craft.getHitBox().getMinY() < craft.getWorld().getSeaLevel()) {
                    ticksElapsed /= 2;
                }

                if (ticksElapsed < tickCooldown) {
                    return;
                }
            }

            if (!player.hasPermission("movecraft." + craft.getType().getStringProperty(CraftType.NAME) + ".move")) {
                player.sendMessage(I18nSupport.getInternationalisedString("Insufficient Permissions"));
                return;
            }

            if (!MathUtils.locationNearHitBox(craft.getHitBox(), player.getLocation(), 2)) {
                return;
            }

            if (craft.getPilotLocked()) {
                int dy = player.isSneaking() ? -1 : 1;
                if (craft.getType().getBoolProperty(CraftType.GEAR_SHIFTS_AFFECT_DIRECT_MOVEMENT)) {
                    dy *= currentGear;
                }

                craft.translate(craft.getWorld(), 0, dy, 0);
                timeMap.put(player, System.currentTimeMillis());
                craft.setLastCruiseUpdate(System.currentTimeMillis());
                return;
            }

            double rotation = player.getLocation().getYaw() * Math.PI / 180.0;
            float nx = -(float) Math.sin(rotation);
            float nz = (float) Math.cos(rotation);
            int dx = (Math.abs(nx) >= 0.5 ? 1 : 0) * (int) Math.signum(nx);
            int dz = (Math.abs(nz) > 0.5 ? 1 : 0) * (int) Math.signum(nz);

            float pitch = player.getLocation().getPitch();
            int dy = -(Math.abs(pitch) >= 25 ? 1 : 0) * (int) Math.signum(pitch);
            if (Math.abs(pitch) >= 75) {
                dx = 0;
                dz = 0;
            }

            craft.translate(craft.getWorld(), dx, dy, dz);
            timeMap.put(player, System.currentTimeMillis());
            craft.setLastCruiseUpdate(System.currentTimeMillis());
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onAircraftThrottleScroll(@NotNull PlayerItemHeldEvent e) {
        Player player = e.getPlayer();
        Craft craft = CraftManager.getInstance().getCraftByPlayer(player);
        if (craft == null || !craft.getPilotLocked() || !isCombatAircraft(craft)) {
            return;
        }

        PlayerInventory inventory = player.getInventory();
        int newSlot = e.getNewSlot();
        int clockSlot = findClockSlot(inventory, e.getPreviousSlot());
        if (clockSlot < 0) {
            return;
        }

        if (clockSlot != newSlot) {
            ItemStack clock = inventory.getItem(clockSlot);
            ItemStack displaced = inventory.getItem(newSlot);
            inventory.setItem(clockSlot, displaced);
            inventory.setItem(newSlot, clock);
        }

        int gear = newSlot + 1;
        craft.setCurrentGear(gear);

        double blocksPerSecond = 4.0 + ((gear - 1) * 26.0 / 8.0);
        player.spigot().sendMessage(
                ChatMessageType.ACTION_BAR,
                new TextComponent(ChatColor.YELLOW + "Throttle " + gear + "/9 "
                        + ChatColor.GRAY + String.format("(%.1f blocks/s)", blocksPerSecond))
        );
    }

    @EventHandler(priority = EventPriority.LOWEST) // LOWEST so that it runs before the other events
    public void onPlayerDropItem(@NotNull PlayerDropItemEvent e) {
        Material itemType = e.getItemDrop().getItemStack().getType();
        if (itemType != Material.COMPASS && itemType != Material.CLOCK) {
            return;
        }

        PlayerCraft craft = CraftManager.getInstance().getCraftByPlayer(e.getPlayer());
        if (craft == null) {
            return;
        }

        if (itemType == Material.CLOCK && !isCombatAircraft(craft)) {
            return;
        }

        droppedMap.put(e.getPlayer(), System.currentTimeMillis() + 1200);

        if (!MathUtils.locIsNearCraftFast(craft, MathUtils.bukkit2MovecraftLoc(e.getPlayer().getLocation()))) {
            return;
        }

        rotateCraftFromControlKey(e.getPlayer(), craft, MovecraftRotation.ANTICLOCKWISE);
        e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST) // LOWEST so that it runs before the other events
    public void onPlayerSwapitem(@NotNull PlayerSwapHandItemsEvent e) {
        ItemStack offHandItem = e.getOffHandItem();
        if (offHandItem == null) {
            return;
        }

        Material itemType = offHandItem.getType();
        if (itemType != Material.COMPASS && itemType != Material.CLOCK) {
            return;
        }

        PlayerCraft craft = CraftManager.getInstance().getCraftByPlayer(e.getPlayer());
        if (craft == null) {
            return;
        }

        if (itemType == Material.CLOCK && !isCombatAircraft(craft)) {
            return;
        }

        if (!MathUtils.locIsNearCraftFast(craft, MathUtils.bukkit2MovecraftLoc(e.getPlayer().getLocation()))) {
            return;
        }

        rotateCraftFromControlKey(e.getPlayer(), craft, MovecraftRotation.CLOCKWISE);
        e.setCancelled(true);
    }

    private int findClockSlot(PlayerInventory inventory, int preferredSlot) {
        ItemStack preferred = inventory.getItem(preferredSlot);
        if (preferred != null && preferred.getType() == Material.CLOCK) {
            return preferredSlot;
        }

        for (int i = 0; i < 36; i++) {
            ItemStack item = inventory.getItem(i);
            if (item != null && item.getType() == Material.CLOCK) {
                return i;
            }
        }
        return -1;
    }

    private boolean isCombatAircraft(Craft craft) {
        String craftName = craft.getType().getStringProperty(CraftType.NAME).toLowerCase();
        return craftName.contains("fighter") || craftName.contains("bomber");
    }

    private void rotateCraftFromControlKey(Player player, PlayerCraft craft, MovecraftRotation rotation) {
        boolean combatAircraftDirectControl = isCombatAircraft(craft) && craft.getPilotLocked();
        craft.rotate(
                rotation,
                combatAircraftDirectControl
                        ? MathUtils.bukkit2MovecraftLoc(player.getLocation())
                        : craft.getHitBox().getMidPoint()
        );

        if (!combatAircraftDirectControl) {
            return;
        }

        Movecraft.getInstance().getDirectControlManager().rotateAircraftCruiseDirection(craft, rotation);

        Location location = player.getLocation();
        location.setYaw(location.getYaw() + (rotation == MovecraftRotation.CLOCKWISE ? 90 : -90));
        player.teleport(location);
    }
}
