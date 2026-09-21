package io.github.desm00nt.kustik;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import io.github.desm00nt.kustik.core.ApiKeyManager;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.function.Consumer;
import java.util.function.Supplier;

/** Separate from the greedy /кустик conversation: a credential must never fall through to an AI message. */
final class ApiKeyCommands {
    private ApiKeyCommands() {}

    static LiteralArgumentBuilder<CommandSourceStack> key(String name, Supplier<BushConversationService> service) {
        return Commands.literal(name).requires(ApiKeyCommands::mayManage)
                .executes(context -> help(context.getSource()))
                // Consume all text: invalid tokens get our fixed error, not a syntax error quoting the key.
                .then(Commands.argument("api_key", StringArgumentType.greedyString())
                        .executes(context -> change(context.getSource(),
                                StringArgumentType.getString(context, "api_key"), false, service)));
    }

    static LiteralArgumentBuilder<CommandSourceStack> clear(Supplier<BushConversationService> service) {
        return Commands.literal("clearkey").requires(ApiKeyCommands::mayManage)
                .executes(context -> change(context.getSource(), "", true, service));
    }

    static boolean isWorldOwner(CommandSourceStack source) {
        // Forge exposes CommandSourceStack.source via its access transformer. It is the ORIGINAL sender:
        // getEntity() can be replaced with the host by an OP-2 /execute as command and is not an identity check.
        return source.source instanceof ServerPlayer sender
                && !source.getServer().isDedicatedServer()
                && source.getServer().isSingleplayerOwner(sender.getGameProfile());
    }

    static boolean mayManage(CommandSourceStack source) {
        // Ordinary OP level 2 (and command blocks) must not replace server credentials.
        // The integrated server's actual owner may configure the mod even with cheats disabled, not LAN guests.
        return source.hasPermission(4) || isWorldOwner(source);
    }

    private static int help(CommandSourceStack source) {
        source.sendSuccess(() -> Component.literal("Настройка: /кустикключ <API-ключ> или /kustikadmin key <API-ключ>. "
                + "Вставь ключ без кавычек. Для удаления: /kustikadmin clearkey.")
                .withStyle(ChatFormatting.GOLD), false);
        source.sendSuccess(() -> Component.literal("Ключ сохраняется в config/kustik-common.toml и применяется сразу. "
                + "Внимание: команда может остаться в истории ввода и сторонних логах. "
                + "Файл или ATRIA_API_KEY безопаснее; заданная переменная окружения имеет приоритет.")
                .withStyle(ChatFormatting.YELLOW), false);
        return 1;
    }

    private static int change(CommandSourceStack source, String input, boolean clear,
                              Supplier<BushConversationService> service) {
        ApiKeyManager.Result result;
        try {
            Consumer<String> activate = key -> {
                BushConversationService current = service.get();
                if (current == null) {
                    throw new IllegalStateException("Service unavailable");
                }
                current.replaceApiKey(key);
            };
            result = clear
                    ? ApiKeyManager.clear(mayManage(source), KustikConfig.hasEnvironmentKey(), KustikConfig::saveApiKey, activate)
                    : ApiKeyManager.set(input, mayManage(source), KustikConfig.hasEnvironmentKey(), KustikConfig::saveApiKey, activate);
        } catch (RuntimeException ignored) {
            // Never propagate a secret-bearing error to the command dispatcher (which can log the full command).
            source.sendFailure(Component.literal("Не удалось изменить настройку ключа. Проверь конфигурацию мода."));
            return 0;
        }
        String message = switch (result) {
            case UPDATED -> "Ключ сохранён и применён без перезапуска. Можно говорить с кустиком. "
                    + "Работоспособность ключа проверится при следующем запросе к Atria. "
                    + "Команда может остаться в истории ввода и сторонних логах.";
            case CLEARED -> "Ключ удалён из настроек. Новые запросы к Atria отключены; ожидающие ответы отменены. "
                    + "Это не отзывает ключ у провайдера и не очищает историю команд или резервные копии.";
            case DENIED -> "Менять ключ может только владелец одиночного мира, оператор уровня 4 или консоль сервера.";
            case ENVIRONMENT_OVERRIDE -> "Ключ задан переменной ATRIA_API_KEY. Команда ничего не изменила: "
                    + "измени или удали переменную и перезапусти сервер/лаунчер, чтобы использовать ключ из команды.";
            case INVALID_KEY -> "Вставь ключ одним токеном без кавычек, внутренних пробелов и управляющих символов "
                    + "(не более 512 ASCII-символов). Для удаления используй /kustikadmin clearkey.";
            case SAVE_FAILED -> "Не удалось сохранить ключ. Активный ключ не изменён. "
                    + "Проверь доступ на запись и содержимое config/kustik-common.toml.";
            case APPLY_FAILED -> "Настройка ключа сохранена, но не применена к работающему моду. "
                    + "Проверь остальные настройки и перезапусти мир/сервер.";
        };
        if (result == ApiKeyManager.Result.UPDATED || result == ApiKeyManager.Result.CLEARED) {
            source.sendSuccess(() -> Component.literal("[Кустик] " + message).withStyle(ChatFormatting.GREEN), false);
            return 1;
        }
        source.sendFailure(Component.literal("[Кустик] " + message));
        return 0;
    }
}
