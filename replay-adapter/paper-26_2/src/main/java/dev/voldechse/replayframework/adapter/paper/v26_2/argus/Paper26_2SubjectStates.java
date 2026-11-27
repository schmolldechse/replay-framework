package dev.voldechse.replayframework.adapter.paper.v26_2.argus;

import dev.voldechse.replayframework.adapter.CaptureSubject;
import dev.voldechse.replayframework.adapter.paper.argus.SubjectSample;
import dev.voldechse.replayframework.format.TrackSubject;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundContainerSetContentPacket;
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket;
import net.minecraft.network.protocol.game.ClientboundSetExperiencePacket;
import net.minecraft.network.protocol.game.ClientboundSetHealthPacket;
import net.minecraft.network.protocol.game.ClientboundSetHeldSlotPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.equine.AbstractChestedHorse;
import net.minecraft.world.entity.npc.InventoryCarrier;
import net.minecraft.world.entity.vehicle.ContainerEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;

/**
 * Reads the state of the subjects of a Paper 26.2 level and builds the
 * packets that carry it.
 *
 * <p>A player's state is what the server sends a joining player about
 * itself: the content of its inventory window, the selected slot, health and
 * food, and experience. An entity's state is the content of an inventory no
 * client receives without opening it: villagers, wandering traders, piglins
 * and allays carry one, chest and hopper minecarts and chest boats are one,
 * and donkeys, mules and llamas have one once a chest is attached. It is
 * carried by the packet the server sends when the inventory is opened.</p>
 *
 * <p>A player's inventory and selected slot are looked at every tick. The
 * server does not tell a player about every change of its own inventory: in
 * creative mode the client sets its slots itself, and while a container is
 * open the inventory is updated through that container's window. Reading the
 * inventory itself records every change. Health, food and experience are
 * always sent to the player and are recorded from its connection.</p>
 *
 * <p>Called on the server thread. The packets hold copies of the items, so
 * they can be encoded later on another thread.</p>
 */
public final class Paper26_2SubjectStates {

    /** Above this many changed slots, one packet with all slots is smaller than one per slot. */
    static final int MAX_SLOT_CHANGES = 12;

    private Paper26_2SubjectStates() {
    }

    /** Looks at the subjects of a level inside the areas that are due at a cadence, for Argus. */
    public static List<SubjectSample> samples(ServerLevel level, List<AABB> areas, SubjectSample.Cadence cadence) {
        List<SubjectSample> samples = new ArrayList<>();
        if (cadence == SubjectSample.Cadence.EVERY_TICK) {
            for (ServerPlayer player : level.players()) {
                if (inside(areas, player)) {
                    List<ItemStack> items = player.inventoryMenu.getItems();
                    int selected = player.getInventory().getSelectedSlot();
                    samples.add(new SubjectSample(
                            subject(player), cadence, 31L * signature(items) + selected,
                            () -> PlayerReading.of(player)));
                }
            }
            return samples;
        }
        for (AABB area : areas) {
            for (Entity entity : level.getEntities((Entity) null, area, entity -> container(entity).isPresent())) {
                List<ItemStack> items = container(entity).orElseThrow();
                samples.add(new SubjectSample(
                        subject(entity), cadence, signature(items),
                        () -> {
                            List<Object> packets = List.copyOf(entityState(items));
                            return () -> packets;
                        }));
            }
        }
        return samples;
    }

    /**
     * A player's inventory window, selected slot, health and experience at one
     * moment. Its changes are the slots that differ and the selected slot.
     */
    record PlayerReading(
            int stateId,
            List<ItemStack> items,
            int selected,
            float health,
            int food,
            float saturation,
            float experienceProgress,
            int totalExperience,
            int experienceLevel) implements SubjectSample.Reading {

        static PlayerReading of(ServerPlayer player) {
            var menu = player.inventoryMenu;
            return new PlayerReading(
                    menu.getStateId(),
                    copies(menu.getItems()),
                    player.getInventory().getSelectedSlot(),
                    player.getHealth(),
                    player.getFoodData().getFoodLevel(),
                    player.getFoodData().getSaturationLevel(),
                    player.experienceProgress,
                    player.totalExperience,
                    player.experienceLevel);
        }

        @Override
        public List<Object> packets() {
            return List.of(
                    new ClientboundContainerSetContentPacket(0, stateId, copies(items), ItemStack.EMPTY),
                    new ClientboundSetHeldSlotPacket(selected),
                    new ClientboundSetHealthPacket(health, food, saturation),
                    new ClientboundSetExperiencePacket(experienceProgress, totalExperience, experienceLevel));
        }

        @Override
        public List<Object> changesSince(SubjectSample.Reading previous) {
            if (!(previous instanceof PlayerReading before) || before.items.size() != items.size()) {
                return packets();
            }
            List<Object> changes = new ArrayList<>();
            for (int slot = 0; slot < items.size(); slot++) {
                if (!ItemStack.matches(items.get(slot), before.items.get(slot))) {
                    changes.add(new ClientboundContainerSetSlotPacket(0, stateId, slot, items.get(slot).copy()));
                }
            }
            if (changes.size() > MAX_SLOT_CHANGES) {
                changes.clear();
                changes.add(new ClientboundContainerSetContentPacket(0, stateId, copies(items), ItemStack.EMPTY));
            }
            if (selected != before.selected) {
                changes.add(new ClientboundSetHeldSlotPacket(selected));
            }
            return changes;
        }
    }

    /** The complete state of every subject of a level that the scope contains, for a checkpoint. */
    public static List<Subject> snapshot(ServerLevel level, Predicate<Entity> inScope) {
        List<Subject> subjects = new ArrayList<>();
        for (ServerPlayer player : level.players()) {
            if (inScope.test(player)) {
                subjects.add(new Subject(subject(player), playerState(player)));
            }
        }
        for (Entity entity : level.getAllEntities()) {
            Optional<List<ItemStack>> items = container(entity);
            if (items.isPresent() && inScope.test(entity)) {
                subjects.add(new Subject(subject(entity), entityState(items.get())));
            }
        }
        return subjects;
    }

    /** The packets the server sends a joining player about itself. */
    static List<Packet<?>> playerState(ServerPlayer player) {
        List<Packet<?>> packets = new ArrayList<>();
        for (Object packet : PlayerReading.of(player).packets()) {
            packets.add((Packet<?>) packet);
        }
        return packets;
    }

    /** The packet the server sends when the entity's inventory is opened. */
    static List<Packet<?>> entityState(List<ItemStack> items) {
        return List.of(new ClientboundContainerSetContentPacket(0, 0, copies(items), ItemStack.EMPTY));
    }

    /** The inventory of an entity that has state of its own. */
    static Optional<List<ItemStack>> container(Entity entity) {
        if (entity instanceof ServerPlayer) {
            return Optional.empty();
        }
        if (entity instanceof AbstractChestedHorse horse) {
            return horse.hasChest() ? Optional.of(horse.inventory.getItems()) : Optional.empty();
        }
        if (entity instanceof ContainerEntity container) {
            return Optional.of(container.getItemStacks());
        }
        if (entity instanceof InventoryCarrier carrier) {
            return Optional.of(carrier.getInventory().getItems());
        }
        return Optional.empty();
    }

    private static CaptureSubject subject(ServerPlayer player) {
        return new CaptureSubject(TrackSubject.player(player.getUUID()), player.getGameProfile().name());
    }

    private static CaptureSubject subject(Entity entity) {
        String label = entity.getCustomName() != null
                ? entity.getCustomName().getString()
                : BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString();
        return new CaptureSubject(TrackSubject.entity(entity.getUUID()), label);
    }

    private static long signature(List<ItemStack> items) {
        long signature = 1L;
        for (ItemStack item : items) {
            signature = 31L * signature + ItemStack.hashItemAndComponents(item);
            signature = 31L * signature + item.getCount();
        }
        return signature;
    }

    private static List<ItemStack> copies(List<ItemStack> items) {
        List<ItemStack> copies = new ArrayList<>(items.size());
        items.forEach(item -> copies.add(item.copy()));
        return copies;
    }

    private static boolean inside(List<AABB> areas, Entity entity) {
        for (AABB area : areas) {
            if (area.contains(entity.position())) {
                return true;
            }
        }
        return false;
    }

    /**
     * The complete state of one subject.
     *
     * @param subject the subject
     * @param packets its native packets
     */
    public record Subject(CaptureSubject subject, List<Packet<?>> packets) {
        public Subject {
            packets = List.copyOf(packets);
        }
    }
}
