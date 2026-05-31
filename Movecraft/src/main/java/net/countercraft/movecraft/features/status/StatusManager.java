package net.countercraft.movecraft.features.status;

import net.countercraft.movecraft.MovecraftLocation;
import net.countercraft.movecraft.config.Settings;
import net.countercraft.movecraft.craft.Craft;
import net.countercraft.movecraft.craft.CraftManager;
import net.countercraft.movecraft.craft.PilotedCraft; // Aggiunto import
import net.countercraft.movecraft.craft.SinkingCraft;
import net.countercraft.movecraft.craft.datatag.CraftDataTagKey;
import net.countercraft.movecraft.craft.datatag.CraftDataTagRegistry;
import net.countercraft.movecraft.craft.type.CraftType;
import net.countercraft.movecraft.craft.type.RequiredBlockEntry;
import net.countercraft.movecraft.features.status.events.CraftStatusUpdateEvent;
import net.countercraft.movecraft.localisation.I18nSupport;
import net.countercraft.movecraft.listener.CraftPilotListener; // Aggiunto import per mappare la BossBar
import net.countercraft.movecraft.processing.WorldManager;
import net.countercraft.movecraft.processing.effects.Effect;
import net.countercraft.movecraft.util.Counter;
import net.countercraft.movecraft.util.Tags;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.boss.BarColor; // Aggiunto import
import org.bukkit.entity.Player; // Aggiunto import
import org.bukkit.boss.BossBar; // Aggiunto import
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitRunnable;
import org.jetbrains.annotations.NotNull;

import java.util.Map;
import java.util.function.Supplier;

public class StatusManager extends BukkitRunnable implements Listener {
    private static final CraftDataTagKey<Long> LAST_STATUS_CHECK = CraftDataTagRegistry.INSTANCE.registerTagKey(new NamespacedKey("movecraft", "last-status-check"), craft -> System.currentTimeMillis());

    @Override
    public void run() {
        for (Craft c : CraftManager.getInstance().getCrafts()) {
            long ticksElapsed = (System.currentTimeMillis() - c.getDataTag(LAST_STATUS_CHECK)) / 50;
            if (ticksElapsed <= Settings.SinkCheckTicks)
                continue;

            c.setDataTag(LAST_STATUS_CHECK, System.currentTimeMillis());
            WorldManager.INSTANCE.submit(new StatusUpdateTask(c));
        }
    }

    private static final class StatusUpdateTask implements Supplier<Effect> {
        private final Craft craft;
        private final Map<Material, Double> fuelTypes;

        private StatusUpdateTask(@NotNull Craft craft) {
            this.craft = craft;

            Object object = craft.getType().getObjectProperty(CraftType.FUEL_TYPES);
            if(!(object instanceof Map<?, ?> map))
                throw new IllegalStateException("FUEL_TYPES must be of type Map");
            for(var e : map.entrySet()) {
                if(!(e.getKey() instanceof Material))
                    throw new IllegalStateException("Keys in FUEL_TYPES must be of type Material");
                if(!(e.getValue() instanceof Double))
                    throw new IllegalStateException("Values in FUEL_TYPES must be of type Double");
            }
            fuelTypes = (Map<Material, Double>) map;
        }

        @Override
        public @NotNull Effect get() {
            Counter<Material> materials = new Counter<>();
            int nonNegligibleBlocks = 0;
            int nonNegligibleSolidBlocks = 0;
            double fuel = 0;
            
            for (MovecraftLocation l : craft.getHitBox()) {
                Material type = craft.getMovecraftWorld().getMaterial(l);
                materials.add(type);

                if (type != Material.FIRE && !type.isAir()) {
                    nonNegligibleBlocks++;
                }
                if (type != Material.FIRE && !type.isAir() && !Tags.FLUID.contains(type)) {
                    nonNegligibleSolidBlocks++;
                }

                if (Tags.FURNACES.contains(type)) {
                    InventoryHolder inventoryHolder = (InventoryHolder) craft.getMovecraftWorld().getState(l);
                    for (ItemStack iStack : inventoryHolder.getInventory()) {
                        if (iStack == null || !fuelTypes.containsKey(iStack.getType()))
                            continue;
                        fuel += iStack.getAmount() * fuelTypes.get(iStack.getType());
                    }
                }
            }

            Counter<RequiredBlockEntry> flyblocks = new Counter<>();
            Counter<RequiredBlockEntry> moveblocks = new Counter<>();
            for(RequiredBlockEntry entry : craft.getType().getRequiredBlockProperty(CraftType.MOVE_BLOCKS)) {
                moveblocks.add(entry, 0);
            }                                           
            for(Material material : materials.getKeySet()) {
                for(RequiredBlockEntry entry : craft.getType().getRequiredBlockProperty(CraftType.FLY_BLOCKS)) {
                    if(entry.contains(material)) {
                        flyblocks.add(entry, materials.get(material) );
                    }
                }

                for(RequiredBlockEntry entry : craft.getType().getRequiredBlockProperty(CraftType.MOVE_BLOCKS)) {
                    if(entry.contains(material)) {
                        moveblocks.add(entry, materials.get(material) );
                    }
                }
            }

            craft.setDataTag(Craft.FUEL, fuel);
            craft.setDataTag(Craft.MATERIALS, materials);
            craft.setDataTag(Craft.FLYBLOCKS, flyblocks);
            craft.setDataTag(Craft.MOVEBLOCKS, moveblocks);
            craft.setDataTag(Craft.NON_NEGLIGIBLE_BLOCKS, nonNegligibleBlocks);
            craft.setDataTag(Craft.NON_NEGLIGIBLE_SOLID_BLOCKS, nonNegligibleSolidBlocks);
            craft.setDataTag(LAST_STATUS_CHECK, System.currentTimeMillis());
            return () -> Bukkit.getPluginManager().callEvent(new CraftStatusUpdateEvent(craft));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCraftStatusUpdate(@NotNull CraftStatusUpdateEvent e) {
        Craft craft = e.getCraft();
        if (craft instanceof SinkingCraft)
            return;
        if (craft.getType().getDoubleProperty(CraftType.SINK_PERCENT) == 0.0)
            return;

        boolean sinking = false;
        boolean disabled = false;
        int nonNegligibleBlocks = craft.getDataTag(Craft.NON_NEGLIGIBLE_BLOCKS);
        int nonNegligibleSolidBlocks = craft.getDataTag(Craft.NON_NEGLIGIBLE_SOLID_BLOCKS);

        Counter<RequiredBlockEntry> flyBlocks = craft.getDataTag(Craft.FLYBLOCKS);
        Counter<RequiredBlockEntry> moveBlocks = craft.getDataTag(Craft.MOVEBLOCKS);

        double sinkPercent = craft.getType().getDoubleProperty(CraftType.SINK_PERCENT) / 100.0;
        for (RequiredBlockEntry entry : flyBlocks.getKeySet()) {
            if(!entry.check(flyBlocks.get(entry), nonNegligibleBlocks, sinkPercent))
                sinking = true;
        }
        if (craft.getType().getRequiredBlockProperty(CraftType.MOVE_BLOCKS).size() > 0) {
            for (RequiredBlockEntry entry : moveBlocks.getKeySet()) {
                if (!entry.check(moveBlocks.get(entry), nonNegligibleBlocks, sinkPercent))                
                    disabled = true;
            }
        }

        // And check the OverallSinkPercent
        double percent;
        if (craft.getType().getBoolProperty(CraftType.BLOCKED_BY_WATER)) {
            percent = (double) nonNegligibleBlocks / (double) craft.getOrigBlockCount();
        }
        else {
            percent = (double) nonNegligibleSolidBlocks / (double) craft.getOrigBlockCount();
        }
        
        double currentIntegrityPercent = percent * 100.0;

        if (craft.getType().getDoubleProperty(CraftType.OVERALL_SINK_PERCENT) != 0.0) {
            if (currentIntegrityPercent < craft.getType().getDoubleProperty(CraftType.OVERALL_SINK_PERCENT))
                sinking = true;
        }

        if (nonNegligibleBlocks == 0)
            sinking = true;

        // --- AGGIORNAMENTO DINAMICO BOSSBAR IN BASE ALLE TUE PERCENTUALI ---
        if (craft instanceof PilotedCraft pilotedCraft) {
            Player player = pilotedCraft.getPilot();
            if (player != null) {
                BossBar bossBar = CraftPilotListener.craftBossBars.get(player.getUniqueId());
                if (bossBar != null) {
                    // Impostiamo il colore in base alle precise fasce richieste
                    BarColor barColor;
                    String integrityColor;
                    
                    if (currentIntegrityPercent >= 80.0) {
                        barColor = BarColor.GREEN;
                        integrityColor = "§a"; // Verde
                    } else if (currentIntegrityPercent > 70.0 && currentIntegrityPercent < 80.0) {
                        barColor = BarColor.YELLOW;
                        integrityColor = "§e"; // Giallo
                    } else {
                        barColor = BarColor.RED;
                        integrityColor = "§c"; // Rosso
                    }

                    bossBar.setColor(barColor);
                    
                    // Calcolo esatto del range di carburante usando la formula ufficiale di Movecraft
                    double fuel = craft.getDataTag(Craft.FUEL);
                    int cruiseSkipBlocks = (int) craft.getType().getPerWorldProperty(CraftType.PER_WORLD_CRUISE_SKIP_BLOCKS, craft.getWorld());
                    cruiseSkipBlocks++;
                    double fuelBurnRate = (double) craft.getType().getPerWorldProperty(CraftType.PER_WORLD_FUEL_BURN_RATE, craft.getWorld());
                    int fuelRange = (int) Math.round((fuel * (1 + cruiseSkipBlocks)) / fuelBurnRate);

                    // Imposta la percentuale visiva di riempimento della barra (da 0.0 a 1.0)
                    bossBar.setProgress(Math.max(0.0, Math.min(1.0, currentIntegrityPercent / 100.0)));

                    // Formattazione titolo: Integrità: (percentuale)% || Benzina: (autonomia blocchi)
                    String title = String.format("%sIntegrità: %.1f%% §7|| §fBenzina: §f%d §fblocchi", 
                            integrityColor, currentIntegrityPercent, fuelRange);
                    bossBar.setTitle(title);
                }
            }
        }
        // -------------------------------------------------------------------

        // If the craft is disabled, play a sound and disable it.
        if (disabled && !craft.getDisabled()) {
            craft.setDisabled(true);
            craft.getAudience().playSound(Sound.sound(Key.key("entity.iron_golem.death"), Sound.Source.NEUTRAL, 5.0f, 5.0f));
        }

        // If the craft is sinking, let the player know and sink the craft.
        if (sinking) {
            craft.getAudience().sendMessage(I18nSupport.getInternationalisedComponent("Player - Craft is sinking"));
            craft.setCruising(false);
            CraftManager.getInstance().sink(craft);
            
            // Pulizia BossBar se sta affondando
            if (craft instanceof PilotedCraft pilotedCraft) {
                Player player = pilotedCraft.getPilot();
                if (player != null) {
                    BossBar bossBar = CraftPilotListener.craftBossBars.remove(player.getUniqueId());
                    if (bossBar != null) {
                        bossBar.removeAll();
                    }
                }
            }
        }
    }
}
