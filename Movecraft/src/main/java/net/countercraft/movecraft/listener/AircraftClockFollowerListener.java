package net.countercraft.movecraft.listener;

import net.countercraft.movecraft.craft.Craft;
import net.countercraft.movecraft.craft.CraftManager;
import net.countercraft.movecraft.craft.type.CraftType;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

public class AircraftClockFollowerListener implements Listener {

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerChangeSlot(PlayerItemHeldEvent event) {
        Player player = event.getPlayer();
        Craft craft = CraftManager.getInstance().getCraftByPlayer(player);
        
        // 1. Controllo base: il giocatore sta pilotando qualcosa?
        if (craft == null) return;

        // 2. Controllo tipo: è un Fighter o un Bomber?
        String craftName = craft.getType().getStringProperty(CraftType.NAME).toLowerCase();
        if (!craftName.contains("Fighter") && !craftName.contains("Bomber")) return;

        PlayerInventory inv = player.getInventory();
        int oldSlot = event.getPreviousSlot();
        int newSlot = event.getNewSlot();

        ItemStack itemInOldSlot = inv.getItem(oldSlot);

        // 3. SE l'orologio era nello slot da cui il giocatore si è appena spostato...
        if (itemInOldSlot != null && itemInOldSlot.getType() == Material.CLOCK) {
            ItemStack itemInNewSlot = inv.getItem(newSlot);
            
            // ...lo scambiamo di posto con l'oggetto presente nel nuovo slot
            inv.setItem(oldSlot, itemInNewSlot);
            inv.setItem(newSlot, itemInOldSlot);
            
            // Forza il client ad aggiornare visivamente l'inventario per evitare glitch
            player.updateInventory();
        } 
        // 4. SE l'orologio non era nel vecchio slot (es. era in un altro slot dell'inventario)...
        else {
            moveClockToHand(player, newSlot);
        }
    }

    public static void moveClockToHand(Player player, int targetSlot) {
        PlayerInventory inv = player.getInventory();
        ItemStack currentItem = inv.getItem(targetSlot);
        
        // Se c'è già un orologio nel target, non fare nulla
        if (currentItem != null && currentItem.getType() == Material.CLOCK) return;

        // Cerca l'orologio in tutto l'inventario (Hotbar + inventario grande)
        int clockSlot = -1;
        for (int i = 0; i < inv.getSize(); i++) {
            ItemStack item = inv.getItem(i);
            if (item != null && item.getType() == Material.CLOCK) {
                clockSlot = i;
                break;
            }
        }

        // Se lo trova da qualche parte, lo sposta nella mano corrente
        if (clockSlot != -1) {
            ItemStack clockItem = inv.getItem(clockSlot);
            inv.setItem(clockSlot, currentItem);
            inv.setItem(targetSlot, clockItem);
            player.updateInventory();
        }
    }
}
