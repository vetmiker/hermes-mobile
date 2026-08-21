package com.m57.hermescontrol.data.update

/**
 * State of the in-app updater (issue #867) — shared by the About tab's
 * [com.m57.hermescontrol.ui.settings.AppUpdateViewModel] and the launch
 * notice (issue #890) via [AppUpdateCache].
 */
sealed interface AppUpdateState {
    /** Nothing shown yet (no check run in this session). */
    data object Idle : AppUpdateState

    /** A check is in flight. */
    data object Checking : AppUpdateState

    /** Latest release equals the installed version. */
    data class UpToDate(val latestTag: String) : AppUpdateState

    /** A newer upstream release is available to review and rebase onto. */
    data class UpdateAvailable(
        val latestTag: String,
        val releaseNotesUrl: String,
    ) : AppUpdateState

    data class Error(val message: String) : AppUpdateState
}

/** The release tag a state carries, when it carries one. */
fun AppUpdateState.releaseTag(): String? =
    when (this) {
        is AppUpdateState.UpToDate -> latestTag
        is AppUpdateState.UpdateAvailable -> latestTag
        else -> null
    }
