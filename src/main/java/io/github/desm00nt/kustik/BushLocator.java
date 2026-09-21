package io.github.desm00nt.kustik;

import io.github.desm00nt.kustik.core.ConversationKey;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.UUID;

final class BushLocator {
    private BushLocator() {}

    static Target nearest(ServerPlayer player, int radius) {
        ServerLevel level = player.serverLevel();
        BlockPos origin = player.blockPosition();
        Target nearest = null;
        double bestDistance = radius * radius;
        for (BlockPos pos : BlockPos.betweenClosed(origin.offset(-radius, -radius, -radius),
                origin.offset(radius, radius, radius))) {
            double distance = player.position().distanceToSqr(Vec3.atCenterOf(pos));
            if (distance <= bestDistance && level.hasChunkAt(pos)
                    && level.getBlockState(pos).is(Blocks.DEAD_BUSH) && visible(player, pos)) {
                nearest = new Target(level.dimension(), pos.immutable());
                bestDistance = distance;
            }
        }
        return nearest;
    }

    static boolean canStillTalk(ServerPlayer player, Target target, int radius) {
        ServerLevel level = player.serverLevel();
        return player.isAlive() && !player.isSpectator() && level.dimension().equals(target.dimension())
                && player.position().distanceToSqr(Vec3.atCenterOf(target.pos())) <= radius * radius
                && level.hasChunkAt(target.pos()) && level.getBlockState(target.pos()).is(Blocks.DEAD_BUSH)
                && visible(player, target.pos());
    }

    private static boolean visible(ServerPlayer player, BlockPos pos) {
        BlockHitResult hit = player.serverLevel().clip(new ClipContext(player.getEyePosition(),
                Vec3.atCenterOf(pos), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
        return hit.getType() == HitResult.Type.MISS || hit.getBlockPos().equals(pos);
    }

    record Target(ResourceKey<Level> dimension, BlockPos pos) {
        ConversationKey key(UUID player) {
            return new ConversationKey(player, dimension.location().toString(), pos.asLong());
        }
    }
}
