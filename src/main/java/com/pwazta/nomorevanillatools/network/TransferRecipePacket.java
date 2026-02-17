package com.pwazta.nomorevanillatools.network;

import com.mojang.logging.LogUtils;
import com.pwazta.nomorevanillatools.compat.jei.CraftingContainerConfig;
import com.pwazta.nomorevanillatools.compat.jei.CraftingContainerRegistry;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.network.NetworkEvent;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Packet for transferring items from inventory to crafting grid.
 * Sent from client (JEI handler) to server to actually move items.
 * Supports multiple container types via config ID lookup.
 */
public class TransferRecipePacket {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** A single slot transfer: source inventory slot → target crafting slot. */
    public record SlotTransfer(int sourceSlot, int targetSlot) {
        public void encode(FriendlyByteBuf buf) {
            buf.writeVarInt(sourceSlot);
            buf.writeVarInt(targetSlot);
        }

        public static SlotTransfer decode(FriendlyByteBuf buf) { return new SlotTransfer(buf.readVarInt(), buf.readVarInt()); }
    }

    private final List<SlotTransfer> transfers;
    private final boolean clearFirst;
    private final String configId;

    public TransferRecipePacket(List<SlotTransfer> transfers, boolean clearFirst, String configId) {
        this.transfers = transfers;
        this.clearFirst = clearFirst;
        this.configId = configId;
    }

    public static void encode(TransferRecipePacket packet, FriendlyByteBuf buf) {
        buf.writeBoolean(packet.clearFirst);
        buf.writeUtf(packet.configId);
        buf.writeVarInt(packet.transfers.size());
        for (SlotTransfer transfer : packet.transfers) {
            transfer.encode(buf);
        }
    }

    public static TransferRecipePacket decode(FriendlyByteBuf buf) {
        boolean clearFirst = buf.readBoolean();
        String configId = buf.readUtf();
        int size = buf.readVarInt();
        List<SlotTransfer> transfers = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            transfers.add(SlotTransfer.decode(buf));
        }
        return new TransferRecipePacket(transfers, clearFirst, configId);
    }

    public static void handle(TransferRecipePacket packet, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player == null) return;

            // Lookup config by ID
            CraftingContainerConfig config = CraftingContainerRegistry.getById(packet.configId);
            if (config == null) {
                LOGGER.warn("Player {} sent transfer packet with unknown config ID: {}",
                        player.getName().getString(), packet.configId);
                return;
            }

            AbstractContainerMenu container = player.containerMenu;

            // Validate container type matches the claimed config
            if (!config.matchesContainer(container)) {
                LOGGER.warn("Player {} sent transfer packet for {} but has {} open",
                        player.getName().getString(),
                        packet.configId,
                        container.getClass().getSimpleName());
                return;
            }

            // Clear crafting slots first if requested (return items to inventory)
            if (packet.clearFirst) {
                clearCraftingSlots(container, player, config);
            }

            // Perform transfers with validation
            int inventoryEnd = config.getInventorySlotEnd(container.slots.size());
            for (SlotTransfer transfer : packet.transfers) {
                // Validate source slot is within allowed inventory range
                if (transfer.sourceSlot < config.inventorySlotStart() || transfer.sourceSlot >= inventoryEnd) {
                    LOGGER.warn("Player {} sent invalid source slot {} for config {} (expected {}-{})",
                            player.getName().getString(), transfer.sourceSlot, packet.configId,
                            config.inventorySlotStart(), inventoryEnd - 1);
                    continue;
                }
                // Validate target slot is within allowed crafting range
                if (!config.isValidCraftingSlot(transfer.targetSlot)) {
                    LOGGER.warn("Player {} sent invalid target slot {} for config {}",
                            player.getName().getString(), transfer.targetSlot, packet.configId);
                    continue;
                }
                performTransfer(container, transfer.sourceSlot, transfer.targetSlot);
            }

            // Sync container state
            container.broadcastChanges();
        });
        context.setPacketHandled(true);
    }

    /** Clears crafting grid slots back to player inventory. */
    private static void clearCraftingSlots(AbstractContainerMenu container, ServerPlayer player, CraftingContainerConfig config) {
        for (int i = config.craftingSlotStart(); i < config.craftingSlotEnd(); i++) {
            if (i >= container.slots.size()) break;

            Slot slot = container.slots.get(i);
            if (!slot.hasItem()) continue;

            ItemStack stack = slot.getItem().copy();
            slot.set(ItemStack.EMPTY);

            // Try to add back to player inventory
            if (!player.getInventory().add(stack)) {
                // If inventory full, drop on ground
                player.drop(stack, false);
            }
        }
    }

    /**
     * Transfers one item from source slot to target slot.
     * Assumes slot indices are pre-validated by handle() loop.
     */
    private static void performTransfer(AbstractContainerMenu container, int sourceSlot, int targetSlot) {
        Slot source = container.slots.get(sourceSlot);
        Slot target = container.slots.get(targetSlot);

        if (!source.hasItem()) return;

        ItemStack sourceStack = source.getItem();
        ItemStack targetStack = target.getItem();

        // If target already has an item, check if we can stack
        if (!targetStack.isEmpty()) {
            if (ItemStack.isSameItemSameTags(sourceStack, targetStack)) {
                int canAdd = targetStack.getMaxStackSize() - targetStack.getCount();
                if (canAdd > 0) {
                    int toAdd = Math.min(canAdd, 1); // Only transfer 1 for crafting
                    targetStack.grow(toAdd);
                    sourceStack.shrink(toAdd);
                    // Clear source slot if stack is now empty
                    if (sourceStack.isEmpty()) {
                        source.set(ItemStack.EMPTY);
                    }
                }
            }
            return; // Can't place different item in occupied slot
        }

        // Target is empty - transfer one item
        ItemStack toPlace = sourceStack.copyWithCount(1);
        target.set(toPlace);
        sourceStack.shrink(1);

        // Update source slot
        if (sourceStack.isEmpty()) {
            source.set(ItemStack.EMPTY);
        }
    }
}
