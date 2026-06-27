package app.vaydns

import android.content.Context

data class Config(
    val domain: String,
    val resolverHost: String,
    val resolverPort: Int,
    val resolverMode: ResolverMode,
    val listenPort: Int,
    val mode: Mode,
    val authMode: AuthMode,
    val username: String,
    val password: String
) {
    enum class Mode { PROXY, VPN }
    enum class AuthMode { NO_AUTH, LOGIN_PASSWORD }
    enum class ResolverMode { MANUAL, AUTO }
}

object ConfigStore {
    private const val PREFS = "config"

    fun load(context: Context): Config {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return Config(
            domain = p.getString("domain", "") ?: "",
            resolverHost = p.getString("resolverHost", "") ?: "",
            resolverPort = p.getInt("resolverPort", 53),
            resolverMode = Config.ResolverMode.valueOf(
                p.getString("resolverMode", Config.ResolverMode.MANUAL.name)
                    ?: Config.ResolverMode.MANUAL.name
            ),
            listenPort = p.getInt("listenPort", 1080),
            mode = Config.Mode.valueOf(p.getString("mode", Config.Mode.PROXY.name) ?: Config.Mode.PROXY.name),
            authMode = Config.AuthMode.valueOf(p.getString("authMode", Config.AuthMode.NO_AUTH.name) ?: Config.AuthMode.NO_AUTH.name),
            username = p.getString("username", "") ?: "",
            password = p.getString("password", "") ?: ""
        )
    }

    fun save(context: Context, config: Config) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("domain", config.domain.trim())
            .putString("resolverHost", config.resolverHost.trim())
            .putInt("resolverPort", config.resolverPort)
            .putString("resolverMode", config.resolverMode.name)
            .putInt("listenPort", config.listenPort)
            .putString("mode", config.mode.name)
            .putString("authMode", config.authMode.name)
            .putString("username", config.username)
            .putString("password", config.password)
            .apply()
    }
}
