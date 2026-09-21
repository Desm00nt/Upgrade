package io.github.desm00nt.kustik;

import io.github.desm00nt.kustik.core.ApiException;
import io.github.desm00nt.kustik.core.ApiSettings;
import io.github.desm00nt.kustik.core.AtriaClient;
import io.github.desm00nt.kustik.core.BushPrompt;
import io.github.desm00nt.kustik.core.BushReply;
import io.github.desm00nt.kustik.core.ChatMessage;
import io.github.desm00nt.kustik.core.ChatText;
import io.github.desm00nt.kustik.core.ConversationKey;
import io.github.desm00nt.kustik.core.ConversationMemory;
import io.github.desm00nt.kustik.core.RequestLimiter;
import io.github.desm00nt.kustik.core.RewardPolicy;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** All game state / maps are server-thread-confined. Workers receive only immutable API messages. */
final class BushConversationService implements AutoCloseable {
    private final MinecraftServer server;
    private KustikConfig.Settings settings; // Only replaced on the server thread; workers get immutable snapshots.
    private final KustikRewards rewards;
    private final ConversationMemory memory;
    private final RequestLimiter limiter;
    private final AtriaClient client = new AtriaClient();
    private final ThreadPoolExecutor workers;
    private final Map<UUID, Pending> pending = new HashMap<>();
    private final AtomicBoolean closed = new AtomicBoolean();
    private int ticks;

    BushConversationService(MinecraftServer server, KustikConfig.Settings settings) {
        this.server = server;
        this.settings = settings;
        this.rewards = KustikRewards.get(server);
        this.memory = new ConversationMemory(settings.maxSessions(), settings.historyTurns(), settings.memoryDuration());
        this.limiter = new RequestLimiter(settings.cooldown(), settings.maxConcurrent(), settings.requestsPerMinute());
        AtomicInteger threadNumber = new AtomicInteger();
        this.workers = new ThreadPoolExecutor(settings.maxConcurrent(), settings.maxConcurrent(),
                30, TimeUnit.SECONDS, new SynchronousQueue<>(), task -> {
                    Thread thread = new Thread(task, "kustik-atria-" + threadNumber.incrementAndGet());
                    thread.setDaemon(true);
                    return thread;
                }, new ThreadPoolExecutor.AbortPolicy());
        workers.allowCoreThreadTimeOut(true);
    }

    int speak(CommandSourceStack source, String rawMessage) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        if (closed.get()) {
            return fail(player, "Кустик уже засыпает: сервер останавливается.");
        }
        if (!player.isAlive() || player.isSpectator()) {
            return fail(player, "Чтобы говорить с кустиком, нужно быть живым и не в режиме наблюдателя.");
        }
        if (rawMessage.codePointCount(0, rawMessage.length()) > settings.maxMessageLength()) {
            return fail(player, "Кустик любит короткие истории: не больше " + settings.maxMessageLength() + " символов.");
        }
        String message = ChatText.clean(rawMessage, settings.maxMessageLength());
        if (message.isBlank()) {
            return fail(player, "Напиши что-нибудь после /кустик. Например: /кустик Привет, как тебе живётся?");
        }
        BushLocator.Target target = BushLocator.nearest(player, settings.radius());
        if (target == null) {
            return fail(player, "Рядом нет сухого кустика. Подойди на расстояние до " + settings.radius()
                    + " блоков; между вами не должно быть стены.");
        }
        if (!configured()) {
            return fail(player, "Кустик пока без голоса. Владелец мира или администратор может настроить ключ "
                    + "командой /кустикключ, через config/kustik-common.toml или ATRIA_API_KEY. "
                    + "Не отправляй ключ обычным сообщением или командой разговора /кустик!");
        }
        long now = System.nanoTime();
        RequestLimiter.Result limit = limiter.acquire(player.getUUID(), now);
        if (limit != RequestLimiter.Result.ALLOWED) {
            return fail(player, switch (limit) {
                case ALREADY_PENDING -> "Кустик ещё обдумывает твою прошлую реплику. Дождись ответа.";
                case PLAYER_COOLDOWN -> "Не торопи кустик: между репликами нужно " + settings.cooldown().toSeconds() + " сек.";
                case SERVER_BUSY -> "Сейчас все кустики заняты разговорами. Попробуй чуть позже.";
                case GLOBAL_RATE_LIMIT -> "Кустики исчерпали минутный запас слов. Подожди немного.";
                default -> throw new IllegalStateException("Unexpected limiter result");
            });
        }
        ConversationKey key = target.key(player.getUUID());
        ConversationMemory.Session session = memory.get(key, now);
        List<ChatMessage> messages = BushPrompt.messages(session, message, settings.minimumTurns(), rewards.claimed(key));
        Pending request = new Pending(player, target, key, session, message, session.nextTurn());
        pending.put(player.getUUID(), request);
        ApiSettings requestApi = settings.api();
        try {
            request.task = workers.submit(() -> fetch(request, messages, requestApi));
        } catch (RejectedExecutionException e) {
            pending.remove(player.getUUID());
            limiter.release(player.getUUID());
            return fail(player, "Кустик ещё заканчивает прошлый разговор. Попробуй через несколько секунд.");
        }
        player.sendSystemMessage(Component.literal("Ты → Кустик: ").withStyle(ChatFormatting.DARK_GRAY)
                .append(Component.literal(message).withStyle(ChatFormatting.GRAY)));
        notice(player, "Кустик шуршит веточками… Подожди рядом.", ChatFormatting.GOLD);
        return 1;
    }

    private void fetch(Pending request, List<ChatMessage> messages, ApiSettings requestApi) {
        BushReply reply = null;
        ApiException error = null;
        try {
            reply = client.chat(requestApi, messages);
        } catch (ApiException e) {
            error = e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return; // Logout / shutdown / credential rotation has already removed this request on the server thread.
        } catch (RuntimeException e) {
            // No raw exception message: HTTP errors can contain request data.
            error = new ApiException(ApiException.Kind.NETWORK);
        }
        BushReply result = reply;
        ApiException failure = error;
        if (!closed.get()) {
            server.execute(() -> complete(request, result, failure));
        }
    }

    private void complete(Pending request, BushReply reply, ApiException error) {
        UUID id = request.key.playerId();
        // Identity check makes callbacks from a cancelled/previous connection harmless.
        if (closed.get() || pending.get(id) != request) {
            return;
        }
        pending.remove(id);
        limiter.release(id);
        ServerPlayer player = server.getPlayerList().getPlayer(id);
        if (player != request.player) {
            return;
        }
        if (error != null) {
            KustikMod.LOGGER.warn("Atria request failed: {}", error.kind());
            fail(player, error.playerMessage());
            return;
        }
        // Never award remotely, after death, a dimension change, chunk unload or destruction of the bush.
        if (!BushLocator.canStillTalk(player, request.target, settings.radius())) {
            fail(player, "Ты отошёл, кустик исчез или больше тебя не видит. Ответ не засчитан — поговори с ним рядом.");
            return;
        }
        boolean alreadyRewarded = rewards.claimed(request.key);
        boolean eligible = RewardPolicy.mayGive(reply, request.turn, settings.minimumTurns(), alreadyRewarded);
        boolean delivered = false;
        boolean dropped = false;
        if (eligible && rewards.claim(request.key)) {
            // Reserve first: even another mod's synchronous inventory hooks cannot duplicate this reward.
            ItemStack stick = new ItemStack(Items.STICK, 1);
            player.getInventory().add(stick);
            delivered = stick.isEmpty();
            if (!delivered) {
                ItemEntity entity = player.drop(stick, false);
                delivered = entity != null;
                if (entity != null) {
                    entity.setNoPickUpDelay();
                    entity.setTarget(player.getUUID());
                }
                dropped = delivered;
            }
            if (!delivered) {
                rewards.undoClaim(request.key);
            }
            player.containerMenu.broadcastChanges();
        }
        // Store the actual result, not an unauthorized promise of a reward.
        request.session.accept(request.message, new BushReply(reply.text(), delivered), System.nanoTime());
        player.sendSystemMessage(Component.literal("Кустик: ").withStyle(ChatFormatting.GOLD)
                .append(Component.literal(reply.text()).withStyle(ChatFormatting.GREEN)));
        if (delivered) {
            notice(player, dropped ? "Ты уговорил кустик! +1 палка лежит рядом: инвентарь был полон."
                    : "Ты уговорил кустик! +1 палка в инвентаре.", ChatFormatting.YELLOW);
            var pos = request.target.pos();
            player.serverLevel().sendParticles(ParticleTypes.HAPPY_VILLAGER,
                    pos.getX() + 0.5, pos.getY() + 0.7, pos.getZ() + 0.5, 8, 0.25, 0.2, 0.25, 0.02);
        } else if (eligible) {
            notice(player, "Кустик согласен, но выдать палку не получилось. Освободи место и поговори ещё раз.",
                    ChatFormatting.YELLOW);
        } else if (reply.giveStick()) {
            notice(player, alreadyRewarded ? "Этот кустик уже дал тебе палку. Вторую в этом месте получить нельзя."
                    : "Кустик ещё не готов отдать веточку. Продолжи разговор.", ChatFormatting.GRAY);
        }
    }

    void playerLeft(UUID id) {
        Pending request = pending.remove(id);
        if (request != null) {
            request.task.cancel(true);
            limiter.release(id);
        }
        memory.forgetPlayer(id);
    }

    void bushBroken(String dimension, long position) {
        Iterator<Pending> iterator = pending.values().iterator();
        while (iterator.hasNext()) {
            Pending request = iterator.next();
            if (request.key.dimension().equals(dimension) && request.key.blockPosition() == position) {
                iterator.remove();
                request.task.cancel(true);
                limiter.release(request.key.playerId());
                fail(request.player, "Кустик сломан и больше не ответит. Разговор отменён.");
            }
        }
        memory.forgetBush(dimension, position);
        // Claims deliberately survive breaking/replanting at the same coordinates.
    }

    void tick() {
        if (++ticks >= 200) {
            ticks = 0;
            long now = System.nanoTime();
            memory.prune(now);
            limiter.prune(now);
        }
    }

    /** Does not reset conversations, reward claims or rate limits. Old callbacks cannot deliver rewards. */
    void replaceApiKey(String key) {
        if (closed.get()) {
            throw new IllegalStateException("Service is closed");
        }
        settings = settings.withApiKey(key);
        List<Pending> cancelled = List.copyOf(pending.values());
        pending.clear();
        for (Pending request : cancelled) {
            limiter.release(request.key.playerId());
            request.task.cancel(true);
            if (server.getPlayerList().getPlayer(request.key.playerId()) == request.player) {
                notice(request.player, "Настройки подключения Atria изменились. Ожидающий ответ отменён; "
                        + "попробуй поговорить с кустиком ещё раз.", ChatFormatting.YELLOW);
            }
        }
    }

    boolean configured() {
        return settings.api().configured();
    }

    int radius() {
        return settings.radius();
    }

    String status() {
        return "Кустик: ключ " + (configured() ? "настроен" : "не настроен")
                + "; радиус " + radius() + "; запросов в работе " + limiter.activeCount()
                + "/" + settings.maxConcurrent() + "; диалогов в памяти " + memory.size()
                + ". Команда ключа применяется сразу; ручные изменения конфига — после перезапуска мира/сервера.";
    }

    @Override
    public void close() {
        if (!closed.getAndSet(true)) {
            pending.values().forEach(request -> request.task.cancel(true));
            pending.clear();
            memory.clear();
            workers.shutdownNow();
        }
    }

    private static void notice(ServerPlayer player, String text, ChatFormatting color) {
        player.sendSystemMessage(Component.literal("[Кустик] " + text).withStyle(color));
    }

    private static int fail(ServerPlayer player, String text) {
        notice(player, text, ChatFormatting.RED);
        return 0;
    }

    private static final class Pending {
        final ServerPlayer player;
        final BushLocator.Target target;
        final ConversationKey key;
        final ConversationMemory.Session session;
        final String message;
        final int turn;
        Future<?> task;

        Pending(ServerPlayer player, BushLocator.Target target, ConversationKey key,
                ConversationMemory.Session session, String message, int turn) {
            this.player = player;
            this.target = target;
            this.key = key;
            this.session = session;
            this.message = message;
            this.turn = turn;
        }
    }
}
