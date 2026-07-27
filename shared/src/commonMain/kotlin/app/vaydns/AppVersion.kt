package app.vaydns

/**
 * App identity shown in the drawer footer (and elsewhere).
 * Keep in sync with Android `versionName` in app/build.gradle.kts.
 */
object AppVersion {
    const val name = "0.1.0"
    /** ISO date of this desktop/shared build stamp (update when shipping). */
    const val buildDate = "2026-07-27"

    val display: String get() = "$name · $buildDate"
}
