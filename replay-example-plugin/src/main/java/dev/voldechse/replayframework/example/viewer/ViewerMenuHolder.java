package dev.voldechse.replayframework.example.viewer;

import org.bukkit.inventory.InventoryHolder;

/**
 * Marks the holder of a menu the Example plugin opens for a protected viewer
 * on purpose. Viewer protection lets such inventories open; clicks in them
 * stay cancelled and are handled by the menu itself.
 */
public interface ViewerMenuHolder extends InventoryHolder {
}
