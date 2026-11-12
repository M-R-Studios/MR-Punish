package com.mrstudios.punish.staff;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.bukkit.GameMode;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

/**
 * Everything staff mode changes, saved before it changes it (DESIGN.md 11.1). Stored as bytes in the
 * player's PDC ({@code mrpunish:staffmode}), so it is saved with the player's own data file: after a
 * crash the file has either the original inventory and no state, or the staff inventory and this
 * state. A version byte comes first so later versions can read older states.
 *
 * @param contents the whole inventory: storage, armour and off-hand ({@code getContents()})
 * @param world    the world's UUID, or null
 */
public record StaffModeState(ItemStack[] contents, GameMode gameMode, boolean allowFlight, boolean flying,
        UUID world, double x, double y, double z, float yaw, float pitch, double health, int food, float saturation,
        float exhaustion, int level, float exp, int totalExperience, List<Effect> effects, int fireTicks,
        float fallDistance, int remainingAir, boolean invulnerable, boolean wasVanished, boolean staffChatOn,
        long savedAt) {

    static final byte VERSION = 1;

    /** One potion effect, by key. */
    public record Effect(String type, int duration, int amplifier, boolean ambient, boolean particles, boolean icon) {
    }

    public StaffModeState {
        contents = contents.clone();
        effects = List.copyOf(effects);
    }

    @Override
    public ItemStack[] contents() {
        return contents.clone();
    }

    /** Reads everything from {@code p} now. */
    public static StaffModeState capture(Player p, boolean wasVanished, boolean staffChatOn, long now) {
        List<Effect> effects = new ArrayList<>();
        for (PotionEffect e : p.getActivePotionEffects()) {
            effects.add(new Effect(e.getType().getKey().toString(), e.getDuration(), e.getAmplifier(), e.isAmbient(),
                    e.hasParticles(), e.hasIcon()));
        }
        var at = p.getLocation();
        return new StaffModeState(p.getInventory().getContents(), p.getGameMode(), p.getAllowFlight(), p.isFlying(),
                at.getWorld() == null ? null : at.getWorld().getUID(), at.getX(), at.getY(), at.getZ(), at.getYaw(),
                at.getPitch(), p.getHealth(), p.getFoodLevel(), p.getSaturation(), p.getExhaustion(), p.getLevel(),
                p.getExp(), p.getTotalExperience(), effects, p.getFireTicks(), p.getFallDistance(), p.getRemainingAir(),
                p.isInvulnerable(), wasVanished, staffChatOn, now);
    }

    public byte[] toBytes() {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeByte(VERSION);
            ItemStack[] items = contents.clone();
            for (int i = 0; i < items.length; i++) {
                if (items[i] == null) {
                    items[i] = ItemStack.empty();
                }
            }
            byte[] inventory = ItemStack.serializeItemsAsBytes(items);
            out.writeInt(inventory.length);
            out.write(inventory);
            out.writeUTF(gameMode.name());
            out.writeBoolean(allowFlight);
            out.writeBoolean(flying);
            out.writeBoolean(world != null);
            if (world != null) {
                out.writeLong(world.getMostSignificantBits());
                out.writeLong(world.getLeastSignificantBits());
            }
            out.writeDouble(x);
            out.writeDouble(y);
            out.writeDouble(z);
            out.writeFloat(yaw);
            out.writeFloat(pitch);
            out.writeDouble(health);
            out.writeInt(food);
            out.writeFloat(saturation);
            out.writeFloat(exhaustion);
            out.writeInt(level);
            out.writeFloat(exp);
            out.writeInt(totalExperience);
            out.writeInt(effects.size());
            for (Effect e : effects) {
                out.writeUTF(e.type());
                out.writeInt(e.duration());
                out.writeInt(e.amplifier());
                out.writeBoolean(e.ambient());
                out.writeBoolean(e.particles());
                out.writeBoolean(e.icon());
            }
            out.writeInt(fireTicks);
            out.writeFloat(fallDistance);
            out.writeInt(remainingAir);
            out.writeBoolean(invulnerable);
            out.writeBoolean(wasVanished);
            out.writeBoolean(staffChatOn);
            out.writeLong(savedAt);
            out.flush();
            return bytes.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("couldn't write the staff mode state", e);
        }
    }

    /** @throws IllegalArgumentException if the bytes aren't a state this version can read */
    public static StaffModeState fromBytes(byte[] data) {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(data))) {
            byte version = in.readByte();
            if (version != VERSION) {
                throw new IllegalArgumentException("staff mode state version " + version + " is not supported");
            }
            byte[] inventory = new byte[in.readInt()];
            in.readFully(inventory);
            ItemStack[] items = ItemStack.deserializeItemsFromBytes(inventory);
            for (int i = 0; i < items.length; i++) {
                if (items[i] != null && items[i].getType().isAir()) {
                    items[i] = null;
                }
            }
            GameMode gameMode = GameMode.valueOf(in.readUTF());
            boolean allowFlight = in.readBoolean();
            boolean flying = in.readBoolean();
            UUID world = in.readBoolean() ? new UUID(in.readLong(), in.readLong()) : null;
            double x = in.readDouble();
            double y = in.readDouble();
            double z = in.readDouble();
            float yaw = in.readFloat();
            float pitch = in.readFloat();
            double health = in.readDouble();
            int food = in.readInt();
            float saturation = in.readFloat();
            float exhaustion = in.readFloat();
            int level = in.readInt();
            float exp = in.readFloat();
            int total = in.readInt();
            int count = in.readInt();
            List<Effect> effects = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                effects.add(new Effect(in.readUTF(), in.readInt(), in.readInt(), in.readBoolean(), in.readBoolean(),
                        in.readBoolean()));
            }
            return new StaffModeState(items, gameMode, allowFlight, flying, world, x, y, z, yaw, pitch, health, food,
                    saturation, exhaustion, level, exp, total, effects, in.readInt(), in.readFloat(), in.readInt(),
                    in.readBoolean(), in.readBoolean(), in.readBoolean(), in.readLong());
        } catch (IOException | RuntimeException e) {
            throw new IllegalArgumentException("unreadable staff mode state: " + e.getMessage(), e);
        }
    }

    /** The potion effects back as Bukkit effects (unknown types skipped). */
    public List<PotionEffect> potionEffects() {
        List<PotionEffect> out = new ArrayList<>();
        for (Effect e : effects) {
            NamespacedKey key = NamespacedKey.fromString(e.type());
            PotionEffectType type = key == null ? null : Registry.EFFECT.get(key);
            if (type != null) {
                out.add(new PotionEffect(type, e.duration(), e.amplifier(), e.ambient(), e.particles(), e.icon()));
            }
        }
        return out;
    }
}
