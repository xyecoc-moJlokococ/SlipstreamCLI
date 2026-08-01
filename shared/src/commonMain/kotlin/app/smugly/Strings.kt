package app.smugly

/**
 * In-app localization used by Compose Multiplatform UI and Android Views.
 * Resolved language never stays as SYSTEM — [set] expands it.
 */
object Strings {
    var current: AppLanguage = AppLanguage.EN
        private set

    fun set(preference: AppLanguage) {
        current = resolve(preference)
    }

    fun resolve(preference: AppLanguage): AppLanguage = when (preference) {
        AppLanguage.SYSTEM -> {
            val lang = defaultLanguageCode()
            if (lang.equals("ru", ignoreCase = true)) AppLanguage.RU else AppLanguage.EN
        }
        else -> preference
    }
}

expect fun defaultLanguageCode(): String

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

    // s3-fuckup (S3 dead-drop tunnel)
    PROTOCOL("Protocol", "Протокол"),
    PROTOCOL_SLIPSTREAM("Slipstream (DNS)", "Slipstream (DNS)"),
    PROTOCOL_S3FU("S3 (s3-fuckup)", "S3 (s3-fuckup)"),
    PROTOCOL_XRAY("Xray", "Xray"),
    PROTOCOL_CDNFU("CDN (cdn-fuckup)", "CDN (cdn-fuckup)"),
    CDN_URL("CDN URL", "CDN URL"),
    CDN_PSK("PSK", "PSK"),
    CDN_PSK_HINT("passphrase", "парольная фраза"),
    CDN_MIMIC("Path mimicry", "Мимикрия путей"),
    /** Profile-card subtitle: the component actually carrying the traffic. */
    PROTOCOL_XRAY_CORE("Xray-core", "Xray-core"),
    S3_SECTION("S3 (s3-fuckup)", "S3 (s3-fuckup)"),
    S3_ENDPOINT("S3 endpoint", "S3 endpoint"),
    S3_BUCKET("Bucket", "Бакет"),
    S3_BUCKET_HINT("bucket name", "имя бакета"),
    S3_ACCESS_KEY("Access key", "Access key"),
    S3_SECRET_KEY("Secret key", "Secret key"),
    S3_PREFIX("Prefix (server)", "Префикс (сервер)"),
    S3_PREFIX_HINT("s3fu", "s3fu"),
    S3_PSK("PSK", "PSK"),
    S3_PSK_HINT("64 hex chars", "64 hex-символа"),

    // Xray
    XRAY_SECTION("Xray configuration", "Конфигурация Xray"),
    XRAY_CONFIG_HINT("Xray JSON config", "JSON-конфигурация Xray"),
    XRAY_FORMAT_BTN("FORMAT", "ФОРМАТИРОВАТЬ"),
    XRAY_VALIDATE_BTN("CHECK", "ПРОВЕРИТЬ"),
    XRAY_CORE_VERSION("Xray-core", "Xray-core"),

    // Field labels
    LOCAL_PORT("Local port", "Локальный порт"),
    CONNECTION_MODE("Connection mode", "Режим подключения"),
    LANGUAGE("Language", "Язык"),
    LANGUAGE_SYSTEM("System", "Системный"),
    PROFILE_NAME("Profile name", "Название профиля"),
    DOMAIN("Domain", "Домен"),
    DNS_MODE("DNS mode", "Режим DNS"),
    RESOLVER_HOST("Resolver host", "DNS-резолвер"),
    TRANSPORT("Transport", "Транспорт"),
    RESOLVER_PORT("Resolver port", "Порт резолвера"),
    DNS_QUERY_TYPE("DNS query type", "Тип DNS-запроса"),
    DNS_PATH_MODE("DNS path mode", "Режим пути DNS"),
    AUTH_MODE("Auth mode", "Режим аутентификации"),
    USERNAME("Username", "Логин"),
    PASSWORD("Password", "Пароль"),
    DNS_LABEL_LENGTH("DNS label length", "Длина DNS-метки"),
    DNS_LABEL_LENGTH_JITTER("DNS label length jitter", "Джиттер длины DNS-метки"),
    MAX_POLL_RATE("Max download polls (queries/sec)", "Макс. download-опросы (запросов/сек)"),
    MAX_DATA_RATE("Max upload data (queries/sec)", "Макс. upload data (запросов/сек)"),
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
    HINT_DNS_LABEL_LENGTH_JITTER(
        "0 = off. Default 4: randomizes each query's label length (within [len-jitter, len]) so labels aren't all identical -- harder to fingerprint. Small values barely affect speed; higher masks more but slightly lowers MTU.",
        "0 = выкл. По умолчанию 4: случайно меняет длину метки в каждом запросе (в диапазоне [длина-джиттер, длина]), чтобы метки не были одинаковыми — сложнее распознать по сигнатуре. Малые значения почти не влияют на скорость; большие лучше маскируют, но чуть снижают MTU."
    ),
    HINT_MAX_POLL_QPS(
        "0 = unlimited. Empty DNS polls that pull DOWNLOAD (and MAX_STREAM_DATA). Separate from upload. Default 1400; ~400+ kept during upload so download is not starved.",
        "0 = без лимита. Пустые DNS-опросы, которые тянут DOWNLOAD (и MAX_STREAM_DATA). Отдельно от upload. По умолчанию 1400; во время аплоада оставляем ≥~400, чтобы download не умирал."
    ),
    HINT_MAX_DATA_QPS(
        "0 = unlimited. Data-bearing DNS queries = UPLOAD only. Default 800. Lower if chat dies under load; higher for faster files. Does not limit download polls.",
        "0 = без лимита. Data-DNS = только UPLOAD. По умолчанию 800. Меньше — если чат падает; больше — быстрее файлы. Download-опросы не ограничивает."
    ),
    HINT_MAX_ACTIVE_CLIENTS(
        "Default 40. Lower it (e.g. 4-6) on operators that hard-limit DNS query rate, so the query budget isn't split across too many connections.",
        "По умолчанию 40. Уменьшите (например, до 4-6) у операторов с жёстким лимитом на частоту DNS-запросов, чтобы бюджет запросов не дробился между слишком многими соединениями."
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
    CD_EDIT_PROFILE("Edit", "Редактировать"),
    CD_DELETE_PROFILE("Delete profile", "Удалить профиль"),
    CD_PROFILE_MENU("Profile menu", "Меню профиля"),

    // Profile list
    PROFILE_NAME_FALLBACK("Manual", "Ручной"),

    // Add-profile overflow menu (plus button)
    MENU_NEW_PROFILE("New profile", "Новый профиль"),
    MENU_IMPORT_CLIPBOARD("Import from clipboard", "Импорт из буфера обмена"),
    MENU_IMPORT_FILE("Import from file", "Импорт из файла"),

    // Per-profile overflow menu (⋯ button)
    MENU_EXPORT_PROFILE("Export", "Экспортировать"),

    // Dialogs
    DELETE_PROFILE_TITLE("Delete profile?", "Удалить профиль?"),
    DELETE_BTN("Delete", "Удалить"),
    CANCEL_BTN("Cancel", "Отмена"),
    CRASH_REPORT_TITLE("Crash report", "Отчёт о сбое"),
    NO_CRASH_REPORT("No crash report saved yet.", "Отчёт о сбое пока не сохранён."),
    COPY_BTN("Copy", "Копировать"),
    SHARE_BTN("Share", "Поделиться"),
    CLOSE_BTN("Close", "Закрыть"),
    BACKGROUND_WORK_TITLE("Background work", "Работа в фоне"),
    BACKGROUND_WORK_MESSAGE(
        "Allow Smugly to keep working in the background if your Android skin shows such an option.",
        "Разрешите Smugly работать в фоне, если ваша оболочка Android показывает такую опцию."
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
    // Covers every import form: slipstream://, s3fu://, xray://, vless:// and raw JSON.
    TOAST_INVALID_PROFILE_LINK("invalid profile link", "неверная ссылка профиля"),
    TOAST_PROFILE_IMPORTED("profile imported", "профиль импортирован"),
    TOAST_CLIPBOARD_EMPTY("clipboard is empty", "буфер обмена пуст"),
    TOAST_IMPORT_FILE_FAILED("could not read import file", "не удалось прочитать файл импорта"),
    TOAST_PROFILE_LINK_COPIED("profile link copied", "ссылка профиля скопирована"),
    TOAST_XRAY_CONFIG_OK("config is valid", "конфигурация корректна"),
    TOAST_XRAY_CONFIG_EMPTY("config is empty", "конфигурация пуста"),
    TOAST_XRAY_NOT_JSON("not valid JSON", "некорректный JSON"),
    TOAST_FILE_LOGGING_DISABLED("file logging is disabled", "логирование в файл отключено"),
    TOAST_LOG_COPIED("log copied to clipboard", "лог скопирован в буфер"),
    TOAST_LOG_EMPTY("log is empty", "лог пуст"),
    TOAST_CRASH_REPORT_COPIED("crash report copied", "отчёт о сбое скопирован"),
    TOAST_LOG_SAVED("log saved", "лог сохранён"),

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

    NO_PROFILES_HINT(
        "You have no VPN configurations",
        "У вас нет VPN-конфигураций"
    ),
    /** Same thing inside a subscription folder — the user has profiles, just not in this one. */
    NO_PROFILES_IN_FOLDER_HINT(
        "There are no VPN configurations here",
        "Здесь нет VPN-конфигураций"
    ),
    RENAME_FOLDER("Rename", "Переименовать"),
    EDIT_FOLDER("Edit", "Редактировать"),
    FOLDER_NEW_TITLE("New folder", "Новая папка"),
    FOLDER_EDIT_TITLE("Folder settings", "Настройки папки"),
    FOLDER_NAME("Folder name", "Название папки"),
    FOLDER_URL("Subscription link", "Ссылка на подписку"),
    FOLDER_UPDATES_ENABLED("Allow updating", "Разрешить обновление"),
    FOLDER_AUTO_UPDATE("Update automatically", "Обновлять автоматически"),
    FOLDER_ALLOW_REORDER("Allow reordering profiles", "Разрешить перетаскивание профилей"),
    FOLDER_SHOW_INFO("Show info block", "Показывать блок с информацией"),
    MENU_NEW_FOLDER("New folder", "Новая папка"),
    DELETE_FOLDER_TITLE("Delete", "Удалить"),
    DELETE_FOLDER_MESSAGE(
        "The subscription and all its servers will be removed.",
        "Подписка и все её серверы будут удалены."
    ),
    SAVE_BTN("Save", "Сохранить"),

    // Subscriptions (folders)
    HOME_FOLDER("Home", "Главная"),
    SUBSCRIPTION_USED("used", "использовано"),
    SUBSCRIPTION_EXPIRES_IN("Days left:", "Осталось дней:"),
    SUBSCRIPTION_EXPIRED("Expired", "Истекла"),
    SUBSCRIPTION_UPDATED("updated", "обновлено"),
    SUBSCRIPTION_UPDATED_JUST_NOW("updated just now", "обновлено только что"),
    SUBSCRIPTION_AUTO_UPDATE("auto", "автообновление"),
    SUBSCRIPTION_REFRESH("Refresh subscription", "Обновить подписку"),
    /** Blocking overlay while a brand-new subscription is being fetched. */
    SUBSCRIPTION_IMPORTING("Importing...", "Импортируем…"),
    SUBSCRIPTION_DELETE("Delete subscription", "Удалить подписку"),
    TOAST_SUBSCRIPTION_ADDED("Subscription imported", "Подписка импортирована"),
    TOAST_SUBSCRIPTION_UPDATED("Subscription updated", "Подписка обновлена"),
    TOAST_SUBSCRIPTION_FAILED("Subscription failed", "Ошибка подписки"),

    // Default profile names
    PROFILE_NAME_DEFAULT_IMPORTED("Profile", "Профиль"),
    PROFILE_NAME_DEFAULT("Slipstream profile", "Профиль Slipstream"),
    PROFILE_NAME_DEFAULT_S3FU("S3 profile", "S3-профиль"),
    PROFILE_NAME_DEFAULT_XRAY("Xray profile", "Профиль Xray"),
}

/** "N profiles imported" -- pluralized per language. */
fun profilesImportedText(count: Int): String = if (Strings.current == AppLanguage.RU) {
    val plural = when {
        count % 100 in 11..14 -> "профилей"
        count % 10 == 1 -> "профиль"
        count % 10 in 2..4 -> "профиля"
        else -> "профилей"
    }
    "импортировано $count $plural"
} else {
    if (count == 1) "1 profile imported" else "$count profiles imported"
}
