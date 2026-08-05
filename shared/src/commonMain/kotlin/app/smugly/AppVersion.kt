package app.smugly

/**
 * App identity shown in the drawer footer (and elsewhere).
 * Keep in sync with Android `versionName` in app/build.gradle.kts.
 */
object AppVersion {
    const val name = "0.1.0"
    /** ISO date this build was compiled — generated, never hand-edited (see BuildStamp.kt). */
    val buildDate: String get() = BUILD_STAMP_DATE

    val display: String get() = "$name · $buildDate"
}
