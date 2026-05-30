package net.countercraft.movecraft.listener;

import net.countercraft.movecraft.Movecraft;
import net.countercraft.movecraft.MovecraftLocation;
import net.countercraft.movecraft.craft.Craft;
import net.countercraft.movecraft.craft.PilotedCraft; // Aggiunto import
import net.countercraft.movecraft.events.CraftReleaseEvent;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Sign;
import org.bukkit.entity.Player; // Aggiunto import
import org.bukkit.boss.BossBar; // Aggiunto import
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.jetbrains.annotations.NotNull;

public class CraftReleaseListener implements Listener {

    @EventHandler
    public void onDisassembly(@NotNull CraftReleaseEvent event) {
        // Walk through all signs and set a UUID in there
        final Craft craft = event.getCraft();

        Movecraft.getInstance().getDirectControlManager().removeControlledCraft(craft);

        // --- RIMOZIONE DELLA BOSSBAR AL RILASCIO GLOBALE ---
        if (craft instanceof PilotedCraft pilotedCraft) {
            Player player = pilotedCraft.getPilot();
            if (player != null) {
                // Rimuoviamo la barra dalla mappa statica situata in CraftPilotListener
                BossBar bossBar = CraftPilotListener.craftBossBars.remove(player.getUniqueId());
                if (bossBar != null) {
                    bossBar.removeAll();
                }
            }
        }
        // ----------------------------------------------------

        // Now, find all signs on the craft...
        for (MovecraftLocation mLoc : craft.getHitBox()) {
            Block block = mLoc.toBukkit(craft.getWorld()).getBlock();
            if (!Tag.SIGNS.isTagged(block.getType()))
                continue;
            BlockState state = block.getState();
            if (!(state instanceof Sign))
                continue;
            Sign tile = (Sign) state;
            craft.removeUUIDMarkFromTile(tile);
            tile.update();
        }
    }
}
