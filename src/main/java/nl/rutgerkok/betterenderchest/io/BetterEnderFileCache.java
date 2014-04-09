package nl.rutgerkok.betterenderchest.io;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Map.Entry;

import nl.rutgerkok.betterenderchest.BetterEnderChest;
import nl.rutgerkok.betterenderchest.BetterEnderChestPlugin.AutoSave;
import nl.rutgerkok.betterenderchest.BetterEnderInventoryHolder;
import nl.rutgerkok.betterenderchest.WorldGroup;
import nl.rutgerkok.betterenderchest.chestowner.ChestOwner;
import nl.rutgerkok.betterenderchest.uuidconversion.BetterEnderUUIDConverter;
import nl.rutgerkok.betterenderchest.uuidconversion.FileUUIDConverter;

import org.bukkit.Bukkit;
import org.bukkit.inventory.Inventory;
import org.bukkit.scheduler.BukkitTask;

/**
 * It is expensive to read from the file system, so files are usually kept in
 * memory for a long time. Inventories are loaded when requested, they are
 * unloaded when the owner has logged out and no one is viewing the inventory
 * anymore.
 * 
 */
public class BetterEnderFileCache extends AbstractEnderCache {
    private static class SaveQueueEntry {
        private final ChestOwner chestOwner;
        private final WorldGroup group;

        private SaveQueueEntry(ChestOwner chestOwner, WorldGroup group) {
            this.chestOwner = chestOwner;
            this.group = group;
        }

        public ChestOwner getChestOwner() {
            return chestOwner;
        }

        public WorldGroup getWorldGroup() {
            return group;
        }
    }

    private BukkitTask autoSaveTask;
    private BukkitTask autoSaveTickTask;
    private Map<WorldGroup, Map<ChestOwner, Inventory>> inventories;

    private ArrayList<SaveQueueEntry> saveQueue;

    public BetterEnderFileCache(BetterEnderChest thePlugin) {
        super(thePlugin);
        inventories = new HashMap<WorldGroup, Map<ChestOwner, Inventory>>();
        saveQueue = new ArrayList<SaveQueueEntry>();

        // AutoSave (adds things to the save queue)
        autoSaveTask = Bukkit.getScheduler().runTaskTimer(plugin.getPlugin(), new Runnable() {
            @Override
            public void run() {
                plugin.debug("Autosaving...");
                autoSave();
            }
        }, AutoSave.autoSaveIntervalTicks, AutoSave.autoSaveIntervalTicks);

        // AutoSaveTick
        autoSaveTickTask = Bukkit.getScheduler().runTaskTimer(plugin.getPlugin(), new Runnable() {
            @Override
            public void run() {
                autoSaveTick();
            }
        }, 60, AutoSave.saveTickInterval);
    }

    private void autoSave() {
        if (!plugin.canSaveAndLoad()) {
            return;
        }
        if (!saveQueue.isEmpty()) {
            plugin.warning("Saving is so slow, that the save queue of the previous autosave wasn't empty during the next one!");
            plugin.warning("Please reconsider your autosave settings.");
            plugin.warning("Skipping this autosave.");
            return;
        }
        for (Iterator<WorldGroup> outerIterator = inventories.keySet().iterator(); outerIterator.hasNext();) {
            WorldGroup group = outerIterator.next();
            Map<ChestOwner, Inventory> inGroup = inventories.get(group);
            for (Iterator<Entry<ChestOwner, Inventory>> it = inGroup.entrySet().iterator(); it.hasNext();) {
                Entry<ChestOwner, Inventory> inventoryEntry = it.next();
                // Add to save queue, but only if there are unsaved changes
                saveQueue.add(new SaveQueueEntry(inventoryEntry.getKey(), group));
            }
        }
    }

    private void autoSaveTick() {
        for (int i = 0; i < AutoSave.chestsPerSaveTick; i++) {
            while (true) {
                if (saveQueue.isEmpty()) {
                    return; // Nothing to save
                }

                SaveQueueEntry toSave = saveQueue.get(saveQueue.size() - 1);
                ChestOwner chestOwner = toSave.getChestOwner();
                WorldGroup group = toSave.getWorldGroup();
                Inventory inventory = getInventory(chestOwner, group);
                boolean needsSave = ((BetterEnderInventoryHolder) inventory.getHolder()).hasUnsavedChanges();

                // Saving
                if (needsSave) {
                    saveInventory(chestOwner, group);
                } else {
                    plugin.debug("Not saving " + chestOwner + ", because it appears to be unchanged.");
                }

                // Unloading
                if (!chestOwner.isOwnerOnline() && inventory.getViewers().size() == 0) {
                    // The owner is NOT online and NO ONE is viewing it
                    // So unload it
                    unloadInventory(chestOwner, group);
                }

                // Remove it from the save queue
                saveQueue.remove(saveQueue.size() - 1);

                // Break out the while loop if chest was saved,
                // otherwise continue immediately with the next chest
                if (needsSave) {
                    break;
                }
            }
        }

    }

    @Override
    public void disable() {
        this.autoSaveTask.cancel();
        this.autoSaveTickTask.cancel();
        this.saveAllInventories();
        this.unloadAllInventories();
    }

    private Inventory getInventory(ChestOwner chestOwner, WorldGroup worldGroup) {
        // Don't try to load when it is disabled
        if (!plugin.canSaveAndLoad()) {
            return plugin.getEmptyInventoryProvider().loadEmptyInventory(chestOwner, worldGroup);
        }

        // Check if loaded
        if (inventories.containsKey(worldGroup) && inventories.get(worldGroup).containsKey(chestOwner)) {
            // Already loaded, return it
            return inventories.get(worldGroup).get(chestOwner);
        } else {
            // Inventory has to be loaded
            Inventory enderInventory = plugin.getFileHandler().loadInventory(chestOwner, worldGroup);
            // Check if something from that group has been loaded
            if (!inventories.containsKey(worldGroup)) {
                // If not, create the group first
                inventories.put(worldGroup, new HashMap<ChestOwner, Inventory>());
            }
            // Put in cache
            inventories.get(worldGroup).put(chestOwner, enderInventory);
            return enderInventory;
        }
    }

    @Override
    public void getInventory(ChestOwner chestOwner, WorldGroup worldGroup, Consumer<Inventory> callback) {
        // We're not async, so return immediatly.
        callback.consume(getInventory(chestOwner, worldGroup));
    }

    @Override
    public BetterEnderUUIDConverter getUUIDConverter() {
        return new FileUUIDConverter(plugin);
    }

    @Override
    public void saveAllInventories() {
        if (!plugin.canSaveAndLoad()) {
            // Can't do anything
            return;
        }

        // Clear the save queue. We are saving ALL chests!
        saveQueue.clear();

        try {
            for (Iterator<WorldGroup> outerIterator = inventories.keySet().iterator(); outerIterator.hasNext();) {
                WorldGroup group = outerIterator.next();
                Map<ChestOwner, Inventory> chestsInGroup = inventories.get(group);
                for (Entry<ChestOwner, Inventory> entryInGroup : chestsInGroup.entrySet()) {
                    ChestOwner chestOwner = entryInGroup.getKey();
                    Inventory inventory = entryInGroup.getValue();
                    plugin.getFileHandler().saveInventory(inventory, chestOwner, group);
                }
            }
        } catch (IOException e) {
            plugin.severe("Failed to save a chest", e);
            plugin.disableSaveAndLoad("Failed to save a chest when trying to save all chests", e);
        }
    }

    @Override
    public void saveInventory(ChestOwner chestOwner, WorldGroup group) {
        if (!plugin.canSaveAndLoad()) {
            return;
        }

        if (!inventories.containsKey(group) || !inventories.get(group).containsKey(chestOwner)) {
            // Oops! Inventory hasn't been loaded. Nothing to save.
            return;
        }
        // Save the inventory to disk and mark as saved
        Inventory inventory = inventories.get(group).get(chestOwner);
        try {
            plugin.getFileHandler().saveInventory(inventory, chestOwner, group);
            ((BetterEnderInventoryHolder) inventory.getHolder()).setHasUnsavedChanges(false);
        } catch (IOException e) {
            plugin.severe("Failed to save chest of " + chestOwner.getDisplayName(), e);
            plugin.disableSaveAndLoad("Failed to save chest of " + chestOwner.getDisplayName(), e);
        }

    }

    @Override
    public void setInventory(Inventory enderInventory) {
        BetterEnderInventoryHolder holder = BetterEnderInventoryHolder.of(enderInventory);
        ChestOwner chestOwner = holder.getChestOwner();
        WorldGroup group = holder.getWorldGroup();

        // Check if something from that group has been loaded
        if (!inventories.containsKey(group)) {
            // If not, create the group first
            inventories.put(group, new HashMap<ChestOwner, Inventory>());
        }
        // Put in cache
        inventories.get(group).put(chestOwner, enderInventory);
    }

    @Override
    public String toString() {
        StringBuilder builder = new StringBuilder();
        for (WorldGroup group : inventories.keySet()) {
            Map<ChestOwner, Inventory> inGroup = inventories.get(group);
            if (inGroup.size() > 0) {
                builder.append("Chests in group " + group.getGroupName() + ":");
                for (ChestOwner chestOwner : inGroup.keySet()) {
                    builder.append(chestOwner.getDisplayName());
                    builder.append(',');
                }
            }
        }

        if (builder.length() == 0) {
            builder.append("No inventories loaded.");
        }
        return builder.toString();
    }

    @Override
    public void unloadAllInventories() {
        saveQueue.clear();
        inventories.clear();
    }

    @Override
    public void unloadInventory(ChestOwner chestOwner, WorldGroup group) {
        // Remove it from the list
        if (inventories.containsKey(group)) {
            inventories.get(group).remove(chestOwner);
        }
    }
}
