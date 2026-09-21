package io.github.desm00nt.kustik;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

import java.util.function.Supplier;

final class BushCommands {
    private BushCommands() {}

    static void register(CommandDispatcher<CommandSourceStack> dispatcher,
                         Supplier<BushConversationService> service) {
        for (String name : new String[]{"кустик", "kustik"}) {
            dispatcher.register(Commands.literal(name)
                    .executes(context -> help(context.getSource(), service.get()))
                    .then(Commands.argument("message", StringArgumentType.greedyString())
                            .executes(context -> {
                                BushConversationService current = service.get();
                                if (current == null) {
                                    context.getSource().sendFailure(Component.literal(
                                            "Кустик ещё не готов. Проверь настройки мода и перезапусти мир."));
                                    return 0;
                                }
                                return current.speak(context.getSource(),
                                        StringArgumentType.getString(context, "message"));
                            })));
        }
        // A separate root avoids accidentally forwarding a key to the greedy conversation argument.
        dispatcher.register(ApiKeyCommands.key("кустикключ", service));
        dispatcher.register(Commands.literal("kustikadmin")
                .requires(source -> source.hasPermission(2) || ApiKeyCommands.isWorldOwner(source))
                .then(Commands.literal("status").executes(context -> {
                    BushConversationService current = service.get();
                    String status = current == null ? "Кустик не запущен: проверь конфигурацию."
                            : current.status();
                    context.getSource().sendSuccess(() -> Component.literal(status), false);
                    return 1;
                }))
                .then(ApiKeyCommands.key("key", service))
                .then(ApiKeyCommands.clear(service)));
    }

    private static int help(CommandSourceStack source, BushConversationService service) {
        int radius = service == null ? 4 : service.radius();
        source.sendSuccess(() -> Component.literal("Кустик • Atria Dawn").withStyle(ChatFormatting.GOLD), false);
        source.sendSuccess(() -> Component.literal("Подойди к сухому кусту (не дальше " + radius
                + " блоков, без стены между вами) и напиши /кустик <сообщение>. Работает и /kustik."), false);
        source.sendSuccess(() -> Component.literal("Уговори его поделиться — получишь одну палку. "
                + "Сообщения этой команды и недавний диалог отправляются в Atria; не вводи секреты.")
                .withStyle(ChatFormatting.GRAY), false);
        return 1;
    }
}
