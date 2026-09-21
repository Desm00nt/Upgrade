package io.github.desm00nt.kustik;

import io.github.desm00nt.kustik.core.ConversationKey;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.HashSet;
import java.util.Set;

/** One stick per player, dimension and block position. Owned by this save, never by a global static map. */
final class KustikRewards extends SavedData {
    private final Set<ConversationKey> claims = new HashSet<>();

    static KustikRewards get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(KustikRewards::load, KustikRewards::new,
                "kustik_rewards");
    }

    static KustikRewards load(CompoundTag tag) {
        KustikRewards data = new KustikRewards();
        ListTag entries = tag.getList("Claims", Tag.TAG_COMPOUND);
        for (int i = 0; i < entries.size(); i++) {
            CompoundTag entry = entries.getCompound(i);
            if (entry.hasUUID("Player") && entry.contains("Dimension", Tag.TAG_STRING)
                    && entry.contains("Position", Tag.TAG_LONG)) {
                data.claims.add(new ConversationKey(entry.getUUID("Player"), entry.getString("Dimension"),
                        entry.getLong("Position")));
            }
        }
        return data;
    }

    boolean claimed(ConversationKey key) {
        return claims.contains(key);
    }

    boolean claim(ConversationKey key) {
        if (!claims.add(key)) {
            return false;
        }
        setDirty();
        return true;
    }

    void undoClaim(ConversationKey key) {
        if (claims.remove(key)) {
            setDirty();
        }
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        ListTag entries = new ListTag();
        for (ConversationKey key : claims) {
            CompoundTag entry = new CompoundTag();
            entry.putUUID("Player", key.playerId());
            entry.putString("Dimension", key.dimension());
            entry.putLong("Position", key.blockPosition());
            entries.add(entry);
        }
        tag.put("Claims", entries);
        return tag;
    }
}
