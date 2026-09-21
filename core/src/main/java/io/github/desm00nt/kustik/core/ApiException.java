package io.github.desm00nt.kustik.core;

/** Safe, fixed diagnostics only: no upstream response body, URL, prompt or credentials. */
public final class ApiException extends Exception {
    private static final long serialVersionUID = 1L;
    public enum Kind {
        AUTH, RATE_LIMIT, UNAVAILABLE, BAD_REQUEST, NETWORK, TIMEOUT, INVALID_RESPONSE, TOO_LARGE
    }

    private final Kind kind;

    public ApiException(Kind kind) {
        super(kind.name());
        this.kind = kind;
    }

    public Kind kind() {
        return kind;
    }

    public String playerMessage() {
        return switch (kind) {
            case AUTH -> "Atria не приняла API-ключ. Попроси владельца сервера проверить настройки.";
            case RATE_LIMIT -> "Atria ограничила запросы. Дай кустику немного отдохнуть и попробуй позже.";
            case UNAVAILABLE -> "Atria сейчас недоступна. Кустик просит заглянуть попозже.";
            case BAD_REQUEST -> "Atria отклонила запрос. Владельцу сервера нужно проверить настройки API.";
            case NETWORK -> "Ветер заглушил кустик: не удалось связаться с Atria. Попробуй позже.";
            case TIMEOUT -> "Кустик задумался слишком надолго. Попробуй поговорить с ним ещё раз.";
            case INVALID_RESPONSE, TOO_LARGE -> "Кустик невнятно прошуршал. Ответ не засчитан — попробуй ещё раз.";
        };
    }
}
