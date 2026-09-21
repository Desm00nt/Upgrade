package io.github.desm00nt.kustik;

import io.github.desm00nt.kustik.core.ConversationKey;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Requires the real Forge/Minecraft classpath (./gradlew test), but does not launch a game or use an API. */
class KustikRewardsTest {
    private final ConversationKey key = new ConversationKey(UUID.randomUUID(), "minecraft:overworld", 123L);

    @Test
    void canClaimOnlyOnceAndMarksSavedDataDirty() {
        var data = new KustikRewards();
        assertFalse(data.claimed(key));
        assertTrue(data.claim(key));
        assertTrue(data.claimed(key));
        assertFalse(data.claim(key));
        assertTrue(data.isDirty());
    }

    @Test
    void failedItemDeliveryCanReleaseTheClaim() {
        var data = new KustikRewards();
        data.claim(key);
        data.undoClaim(key);
        assertFalse(data.claimed(key));
        assertTrue(data.claim(key));
    }

    @Test
    void distinguishesPlayersDimensionsAndLocations() {
        var data = new KustikRewards();
        data.claim(key);
        assertTrue(data.claim(new ConversationKey(UUID.randomUUID(), key.dimension(), key.blockPosition())));
        assertTrue(data.claim(new ConversationKey(key.playerId(), "minecraft:the_nether", key.blockPosition())));
        assertTrue(data.claim(new ConversationKey(key.playerId(), key.dimension(), key.blockPosition() + 1)));
    }

    @Test
    void claimsSurviveARealCompressedNbtRoundTrip() throws Exception {
        var data = new KustikRewards();
        data.claim(key);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        NbtIo.writeCompressed(data.save(new CompoundTag()), bytes);
        var loaded = KustikRewards.load(NbtIo.readCompressed(new ByteArrayInputStream(bytes.toByteArray())));
        assertTrue(loaded.claimed(key));
        assertFalse(loaded.claim(key));
    }

    @Test
    void emptyOrMalformedEntriesDoNotCreateClaims() {
        var tag = new CompoundTag();
        var list = new ListTag();
        list.add(new CompoundTag());
        CompoundTag missingPosition = new CompoundTag();
        missingPosition.putUUID("Player", key.playerId());
        missingPosition.putString("Dimension", key.dimension());
        list.add(missingPosition);
        tag.put("Claims", list);
        assertFalse(KustikRewards.load(tag).claimed(key));
        assertTrue(KustikRewards.load(new CompoundTag()).claim(key));
    }
}
