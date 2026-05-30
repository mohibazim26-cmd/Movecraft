package net.countercraft.movecraft.listener;

import com.google.common.base.Predicates;
import net.countercraft.movecraft.MovecraftLocation;
import net.countercraft.movecraft.TrackedLocation;
import net.countercraft.movecraft.craft.Craft;
import net.countercraft.movecraft.craft.PilotedCraft; // Aggiunto import
import net.countercraft.movecraft.craft.SubCraft;
import net.countercraft.movecraft.events.CraftPilotEvent;
import net.countercraft.movecraft.events.CraftReleaseEvent;
import net.countercraft.movecraft.util.hitboxes.HitBox;
import org.bukkit.NamespacedKey;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Sign;
import org.bukkit.entity.Player; // Aggiunto import
import org.bukkit.boss.BossBar; // Aggiunto import
import org.bukkit.boss.Bukkit; // Aggiunto import
import org.bukkit.boss.BarColor; // Aggiunto import
import org.bukkit.boss.BarStyle; // Aggiunto import
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.jetbrains.annotations.NotNull;

import java.util.*;
import java.util.function.Predicate;

public class CraftPilotListener implements Listener {

    // --- AGGIUNTA AGGANCIO BOSSBAR GLOBALE ---
    public static final Map<UUID, BossBar> craftBossBars = new HashMap<>();

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCraftPilot(@NotNull CraftPilotEvent event) {
        // Walk through all signs and set a UUID in there
        final Craft craft = event.getCraft();

        // Now, find all signs on the craft...
        for (MovecraftLocation mLoc : craft.getHitBox()) {
            Block block = mLoc.toBukkit(craft.getWorld()).getBlock();
            if (!Tag.SIGNS.isTagged(block.getType()))
                continue;
            BlockState state = block.getState();
            if (!(state instanceof Sign))
                continue;
            Sign tile = (Sign) state;
            craft.markTileStateWithUUID(tile);
            tile.update();
        }

        // --- CREAZIONE DELLA BOSSBAR PER IL PILOTA ---
        if (craft instanceof PilotedCraft pilotedCraft) {
            Player player = pilotedCraft.getPilot();
            if (player != null) {
                BossBar oldBar = craftBossBars.remove(player.getUniqueId());
                if (oldBar != null) {
                    oldBar.removeAll();
                }

                BossBar bossBar = Bukkit.createBossBar("Integrità: Calcolo... || Benzina: Calcolo...", BarColor.GREEN, BarStyle.SOLID);
                bossBar.addPlayer(player);
                craftBossBars.put(player.getUniqueId(), bossBar);
            }
        }

        // Tracked locations => Modify correctly with subcrafts
        if (craft instanceof SubCraft subCraft && subCraft.getParent() != null) {
            final Craft parent = subCraft.getParent();
            if (parent.getWorld() != subCraft.getWorld()) {
                return;
            }
            transferTrackedLocations(parent, subCraft, (trackedLocation) -> subCraft.getHitBox().inBounds(trackedLocation.getAbsoluteLocation()) && subCraft.getHitBox().contains(trackedLocation.getAbsoluteLocation()), true);
        }
    }

        if (event.getReason() != CraftReleaseEvent.Reason.SUB_CRAFT) {
            return;
        }
        Craft released = event.getCraft();
        if (!(released instanceof SubCraft)) {
            return;
        }
        SubCraft subCraft = (SubCraft) released;
        if (subCraft.getParent() == null) {
            return;
        }

        // Attention: SquadronCrafts are also subcrafts! We need to make sure that they are at least intersecting the parent when we add back the tracked locations
        final HitBox parentHitBox = subCraft.getParent().getHitBox();
        final HitBox subCraftHitBox = subCraft.getHitBox();

        if (!parentHitBox.inBounds(subCraftHitBox.getMinX(), subCraftHitBox.getMinY(), subCraftHitBox.getMinZ())) {
            if (!parentHitBox.inBounds(subCraftHitBox.getMaxX(), subCraftHitBox.getMaxY(), subCraftHitBox.getMaxZ())) {
                // Subcraft cant possible be within its parent anymore
                return;
            }
        }

        transferTrackedLocations(subCraft, subCraft.getParent(), Predicates.alwaysTrue(), true);
    }

    /*
     * Transfers TrackedLocations from a craft A to a craft B with a optional filter.
     * This MOVES the tracked locations, so keep that in mind
     */
    private static void transferTrackedLocations(final Craft a, final Craft b, Predicate<TrackedLocation> filterArgument, boolean move) {
        final MovecraftLocation bMidPoint = b.getHitBox().getMidPoint();

        for (Map.Entry<NamespacedKey, Set<TrackedLocation>> entry : a.getTrackedLocations().entrySet()) {
            final Set<TrackedLocation> bTrackedLocations = b.getTrackedLocations().computeIfAbsent(entry.getKey(), k -> new HashSet<>());
            final Set<TrackedLocation> aTrackedLocations = entry.getValue();

            if (aTrackedLocations.isEmpty()) {
                continue;
            }

            List<TrackedLocation> transferred = new ArrayList<>();
            aTrackedLocations.forEach(trackedLocation -> {
                if (filterArgument.test(trackedLocation)) {
                    if (move) {
                        final MovecraftLocation absoluteLocation = trackedLocation.getAbsoluteLocation();
                        trackedLocation.reset(b, absoluteLocation);
                        if (!(bTrackedLocations.add(trackedLocation))) {
                            trackedLocation.reset(a, absoluteLocation);
                        } else {
                            transferred.add(trackedLocation);
                        }
                        if (!absoluteLocation.equals(trackedLocation.getAbsoluteLocation())) {
                            throw new IllegalStateException("Somehow the previous and transferred absolute locations are NOT the same! This should NEVER happen!");
                        }
                    } else {
                        bTrackedLocations.add(trackedLocation);
                    }
                }
            });
            aTrackedLocations.removeAll(transferred);
        }
    }
}
