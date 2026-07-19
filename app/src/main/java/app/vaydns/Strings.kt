package app.vaydns

import android.content.Context
import java.util.Locale

enum class AppLanguage { SYSTEM, EN, RU }

/**
 * Lightweight in-app localization. The whole UI is built programmatically (no Android string
 * resources), so this keeps translation lookups uniform across Activity/Service call sites
 * without needing a Configuration-wrapped Context everywhere -- [current] just holds the
 * resolved effective language (never SYSTEM) and every call site reads it via [t].
 */
object Strings {
    @Volatile
    var current: AppLanguage = AppLanguage.EN
        private set

    fun init(context: Context) {
        current = resolve(ConfigStore.loadGlobalSettings(context).language)
    }

    fun set(preference: AppLanguage) {
        current = resolve(preference)
    }

    private fun resolve(preference: AppLanguage): AppLanguage = when (preference) {
        AppLanguage.SYSTEM -> if (Locale.getDefault().language.equals("ru", ignoreCase = true)) {
            AppLanguage.RU
        } else {
            AppLanguage.EN
        }
        else -> preference
    }
}

fun t(s: S): String = if (Strings.current == AppLanguage.RU) s.ru else s.en

fun speedProbingText(current: Int, total: Int): String =
    if (Strings.current == AppLanguage.RU) "Проверка скорости $current/$total" else "Speed probing $current/$total"

fun dnsProbingText(current: Int, total: Int): String =
    if (Strings.current == AppLanguage.RU) "Поиск DNS $current/$total" else "DNS probing $current/$total"

enum class S(val en: String, val ru: String) {
    // Screens / drawer / section titles
    HOME("Home", "Главная"),
    DIAGNOSTICS("Diagnostics", "Диагностика"),
    SETTINGS("Settings", "Настройки"),
    NEW_PROFILE_TITLE("New Profile", "Новый профиль"),
    EDIT_PROFILE_TITLE("Edit Profile", "Редактирование профиля"),
    DNS_RESOLVER("DNS Resolver", "DNS-резолвер"),
    AUTHENTICATION("Authentication", "Аутентификация"),
    ADVANCED_CLIENT_ONLY("Advanced (client-only)", "Дополнительно (только клиент)"),
    ACTIONS("Actions", "Действия"),

    // Field labels
    LOCAL_PORT("Local port", "Локальный порт"),
    CONNECTION_MODE("Connection mode", "Режим подключения"),
    LANGUAGE("Language", "Язык"),
    LANGUAGE_SYSTEM("System", "Системный"),
    PROFILE_NAME("Profile name", "Название профиля"),
    DOMAIN("Domain", "Домен"),
    DNS_MODE("DNS mode", "Режим DNS"),
    RESOLVER_HOST("Resolver host", "Хост резолвера"),
    TRANSPORT("Transport", "Транспорт"),
    RESOLVER_PORT("Resolver port", "Порт резолвера"),
    DNS_QUERY_TYPE("DNS query type", "Тип DNS-запроса"),
    DNS_PATH_MODE("DNS path mode", "Режим пути DNS"),
    AUTH_MODE("Auth mode", "Режим аутентификации"),
    USERNAME("Username", "Логин"),
    PASSWORD("Password", "Пароль"),
    DNS_LABEL_LENGTH("DNS label length", "Длина DNS-метки"),
    MAX_POLL_RATE("Max poll rate (queries/sec)", "Макс. частота опроса (запросов/сек)"),
    MAX_ACTIVE_CONNECTIONS("Max active connections", "Макс. активных соединений"),
    SOCKS_USERNAME("SOCKS username", "Логин SOCKS"),
    SOCKS_PASSWORD("SOCKS password", "Пароль SOCKS"),
    DNS_RESOLVER_POOL("DNS resolver pool", "Пул DNS-резолверов"),
    HINT_DNS_RESOLVER_POOL(
        "One per line. \"(local)\" = the current connection's own operator/DHCP DNS servers.",
        "По одному на строку. «(local)» = операторские/DHCP DNS-серверы текущего подключения."
    ),

    // Pill / spinner option labels
    DNS_MODE_MANUAL("manual dns", "ручной DNS"),
    DNS_MODE_AUTO("auto dns", "авто DNS"),
    PATH_MODE_RECURSIVE("recursive", "рекурсивный"),
    PATH_MODE_AUTHORITATIVE("authoritative", "авторитетный"),
    AUTH_NO_AUTH("no-auth", "без пароля"),
    AUTH_LOGIN_PASSWORD("login/password", "логин/пароль"),
    CONNECTION_MODE_PROXY("proxy", "прокси"),
    CONNECTION_MODE_VPN("vpn", "VPN"),

    // Checkboxes
    USE_BASE64U_ENCODING("Use base64u encoding", "Использовать кодировку base64u"),
    SHOW_TRAFFIC_NOTIFICATION("Show traffic notification", "Показывать уведомление о трафике"),
    PROTECT_LOCAL_SOCKS("Protect local SOCKS", "Защитить локальный SOCKS"),
    ENABLE_DEBUG_MODE("Enable debug mode", "Включить режим отладки"),

    // Buttons
    LOCAL_BTN("LOCAL", "ЛОКАЛЬНЫЙ"),
    CONNECT_BTN("CONNECT", "ПОДКЛЮЧИТЬ"),
    SHARE_LOG_BTN("SHARE LOG", "ПОДЕЛИТЬСЯ ЛОГОМ"),
    CRASH_REPORT_BTN("CRASH REPORT", "ОТЧЁТ О СБОЕ"),
    DELETE_PROFILE_BTN("DELETE PROFILE", "УДАЛИТЬ ПРОФИЛЬ"),
    CREATE_PROFILE_BTN("CREATE PROFILE", "СОЗДАТЬ ПРОФИЛЬ"),
    SAVE_PROFILE_BTN("SAVE PROFILE", "СОХРАНИТЬ ПРОФИЛЬ"),
    BACK_BTN("BACK", "НАЗАД"),

    // Hint texts
    HINT_DNS_QUERY_TYPE(
        "Some resolvers filter specific answer types; null is less likely to be blocked but carries less data per round trip. The server must accept the same type.",
        "Некоторые резолверы фильтруют определённые типы ответов; null реже блокируется, но несёт меньше данных за один цикл обмена. Сервер должен принимать тот же тип."
    ),
    HINT_ADVANCED_CLIENT_ONLY(
        "These only shape this device's own traffic; the server does not need to match them.",
        "Эти параметры влияют только на трафик этого устройства; сервер не обязан их повторять."
    ),
    HINT_DNS_LABEL_LENGTH(
        "1-63, default 57. Length of each DNS label in the encoded query.",
        "1-63, по умолчанию 57. Длина каждой DNS-метки в закодированном запросе."
    ),
    HINT_MAX_POLL_QPS(
        "0 = unlimited (default). Caps how many DNS queries/sec this device sends.",
        "0 = без ограничений (по умолчанию). Ограничивает число DNS-запросов в секунду с этого устройства."
    ),
    HINT_MAX_ACTIVE_CLIENTS(
        "Default 48. Lower it (e.g. 4-6) on operators that hard-limit DNS query rate, so the query budget isn't split across too many connections.",
        "По умолчанию 48. Уменьшите (например, до 4-6) у операторов с жёстким лимитом на частоту DNS-запросов, чтобы бюджет запросов не дробился между слишком многими соединениями."
    ),
    HINT_BASE64U(
        "~20% denser than the default base32, so fewer round trips per byte -- but case-sensitive. Only enable once you've confirmed your resolver path preserves label case; a resolver that lowercases/uppercases names will silently corrupt the tunnel instead of just failing.",
        "Примерно на 20% плотнее стандартного base32, то есть меньше циклов обмена на байт -- но чувствительно к регистру. Включайте только убедившись, что путь резолвера сохраняет регистр меток; резолвер, приводящий имена к одному регистру, незаметно повредит туннель вместо явной ошибки."
    ),

    // Content descriptions (accessibility)
    CD_MENU("Menu", "Меню"),
    CD_NEW_PROFILE("New profile", "Новый профиль"),
    CD_ADD_PROFILE_MENU("Add profile", "Добавить профиль"),
    CD_BACK("Back", "Назад"),
    CD_EDIT_PROFILE("Edit profile", "Редактировать профиль"),
    CD_DELETE_PROFILE("Delete profile", "Удалить профиль"),

    // Profile list
    PROFILE_NAME_FALLBACK("Manual", "Ручной"),

    // Add-profile overflow menu (plus button)
    MENU_NEW_PROFILE("New profile", "Новый профиль"),
    MENU_IMPORT_CLIPBOARD("Import from clipboard", "Импорт из буфера обмена"),
    MENU_IMPORT_FILE("Import from file", "Импорт из файла"),

    // Dialogs
    DELETE_PROFILE_TITLE("Delete profile", "Удалить профиль"),
    DELETE_BTN("Delete", "Удалить"),
    CANCEL_BTN("Cancel", "Отмена"),
    CRASH_REPORT_TITLE("Crash report", "Отчёт о сбое"),
    NO_CRASH_REPORT("No crash report saved yet.", "Отчёт о сбое пока не сохранён."),
    COPY_BTN("Copy", "Копировать"),
    SHARE_BTN("Share", "Поделиться"),
    CLOSE_BTN("Close", "Закрыть"),
    BACKGROUND_WORK_TITLE("Background work", "Работа в фоне"),
    BACKGROUND_WORK_MESSAGE(
        "Allow Slipstream CLI to keep working in the background if your Android skin shows such an option.",
        "Разрешите Slipstream CLI работать в фоне, если ваша оболочка Android показывает такую опцию."
    ),
    OPEN_SETTINGS_BTN("Open settings", "Открыть настройки"),
    LATER_BTN("Later", "Позже"),

    // Toasts
    TOAST_VPN_PERMISSION_REQUIRED("VPN permission is required", "Требуется разрешение на VPN"),
    TOAST_SWITCHING_PROFILE("switching profile…", "переключение профиля…"),
    TOAST_CANNOT_DELETE_LAST_PROFILE("cannot delete last profile", "нельзя удалить последний профиль"),
    TOAST_PROFILE_DELETED("profile deleted", "профиль удалён"),
    TOAST_PROFILE_CREATED("profile created", "профиль создан"),
    TOAST_PROFILE_SAVED("profile saved", "профиль сохранён"),
    TOAST_START_FAILED("start failed", "не удалось запустить"),
    TOAST_VPN_START_FAILED("vpn start failed", "не удалось запустить VPN"),
    TOAST_NO_LOCAL_DNS("no local DNS", "нет локального DNS"),
    TOAST_INVALID_SLIPSTREAM_LINK("invalid slipstream link", "неверная ссылка slipstream"),
    TOAST_PROFILE_IMPORTED("profile imported", "профиль импортирован"),
    TOAST_CLIPBOARD_EMPTY("clipboard is empty", "буфер обмена пуст"),
    TOAST_IMPORT_FILE_FAILED("could not read import file", "не удалось прочитать файл импорта"),
    TOAST_FILE_LOGGING_DISABLED("file logging is disabled", "логирование в файл отключено"),
    TOAST_CRASH_REPORT_COPIED("crash report copied", "отчёт о сбое скопирован"),

    // Share chooser titles
    SHARE_LOG_CHOOSER("Share log", "Поделиться логом"),
    SHARE_CRASH_REPORT_CHOOSER("Share crash report", "Поделиться отчётом о сбое"),

    // Connect status
    STATUS_DISCONNECTING("Disconnecting", "Отключение"),
    STATUS_CONNECTING("Connecting", "Подключение"),
    STATUS_CONNECTED("Connected", "Подключено"),
    STATUS_NOT_CONNECTED("Not connected", "Не подключено"),
    STATUS_SPEED_PROBING("Speed probing", "Проверка скорости"),
    STATUS_DNS_PROBING("DNS probing", "Поиск DNS"),
    STATUS_STARTING("Starting", "Запуск"),

    // Default profile names
    PROFILE_NAME_DEFAULT_IMPORTED("Profile", "Профиль"),
    PROFILE_NAME_DEFAULT("Slipstream profile", "Профиль Slipstream"),
}
