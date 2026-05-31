package net.countercraft.movecraft.listener;

import net.countercraft.movecraft.Movecraft;
import net.countercraft.movecraft.MovecraftLocation;
import net.countercraft.movecraft.craft.Craft;
import net.countercraft.movecraft.craft.PlayerCraft;
import net.countercraft.movecraft.craft.PilotedCraft;
import net.countercraft.movecraft.events.CraftRotateEvent;
import net.countercraft.movecraft.util.MathUtils;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.scheduler.BukkitRunnable;
import org.jetbrains.annotations.NotNull;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class RotateListener implements Listener {

    // Tracciamento delle barre di rotazione attive per ogni giocatore
    private final Map<UUID, BossBar> rotationBars = new HashMap<>();

    @EventHandler(priority = EventPriority.LOWEST)
    public void onCraftRotate(@NotNull CraftRotateEvent e) {
        Craft craft = e.getCraft();
        
        // Calcolo della fine del cooldown (500ms da adesso)
        long duration = 500; 
        long endTime = System.currentTimeMillis() + duration;
        Movecraft.getInstance().getDirectControlManager().addOrSetCooldown(craft, endTime);
        
        // --- GESTIONE DELLA BOSSBAR DI ROTAZIONE ---
        if (craft instanceof PilotedCraft pilotedCraft) {
            Player player = pilotedCraft.getPilot();
            if (player != null) {
                UUID pUUID = player.getUniqueId();
                
                // Se c'era una vecchia barra di rotazione attiva, la rimuoviamo subito
                BossBar oldBar = rotationBars.remove(pUUID);
                if (oldBar != null) {
                    oldBar.removeAll();
                }

                // Creazione della barra Blu Scuro (BarColor.BLUE) in grassetto (§l§9)
                final BossBar rotationBar = Bukkit.createBossBar("§9§lRotazione", BarColor.BLUE, BarStyle.SOLID);
                // La barra parte vuota (0.0) e si riempirà man mano che il tempo scade
                rotationBar.setProgress(0.0);
                rotationBar.addPlayer(player);
                rotationBars.put(pUUID, rotationBar);

                // Task periodico asincrono/sincrono ogni tick (50ms) per aggiornare l'avanzamento
                new BukkitRunnable() {
                    @Override
                    public void run() {
                        long remaining = endTime - System.currentTimeMillis();
                        
                        if (remaining <= 0 || !player.isOnline() || craft.getDisabled()) {
                            rotationBar.removeAll();
                            rotationBars.remove(pUUID);
                            cancel();
                            return;
                        }

                        // Calcolo della percentuale di completamento (da 0.0 a 1.0)
                        // All'inizio (remaining = 500), progress = 0.0. Alla fine (remaining = 0), progress = 1.0
                        double progress = 1.0 - ((double) remaining / (double) duration);
                        if (progress < 0.0) progress = 0.0;
                        if (progress > 1.0) progress = 1.0;

                        rotationBar.setProgress(progress);
                    }
                }.runTaskTimer(Movecraft.getInstance(), 0L, 1L); // Esegue ogni singolo tick di gioco
            }
        }
        // --------------------------------------------

        MovecraftLocation originPoint = e.getOriginPoint();
        Location tOP = new Location(craft.getWorld(), originPoint.getX(), originPoint.getY(), originPoint.getZ());
        tOP.setX(tOP.getBlockX() + 0.5);
        tOP.setZ(tOP.getBlockZ() + 0.5);
        PlayerCraft pCraft = (PlayerCraft) e.getCraft();
        Location pilotLockedLoc = new Location(
                craft.getWorld(),
                pCraft.getPilotLockedX(),
                pCraft.getPilotLockedY(),
                pCraft.getPilotLockedZ()
        );

        pilotLockedLoc.subtract(tOP);
        double[] rotatedPilotLoc = MathUtils.rotateVecNoRound(e.getRotation(), pilotLockedLoc.getX(), pilotLockedLoc.getZ());
        pCraft.setPilotLockedX(rotatedPilotLoc[0] + tOP.getX());
        pCraft.setPilotLockedZ(rotatedPilotLoc[1] + tOP.getZ());
    }
}
