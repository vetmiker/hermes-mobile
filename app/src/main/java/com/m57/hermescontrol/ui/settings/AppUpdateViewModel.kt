package com.m57.hermescontrol.ui.settings

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.m57.hermescontrol.BuildConfig
import com.m57.hermescontrol.data.local.AuthManager
import com.m57.hermescontrol.data.update.AppUpdateCache
import com.m57.hermescontrol.data.update.AppUpdateChecker
import com.m57.hermescontrol.data.update.AppUpdateState
import com.m57.hermescontrol.data.update.isNewerVersion
import com.m57.hermescontrol.data.update.releaseTag
import com.m57.hermescontrol.data.update.upstreamReleaseUrl
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.IOException

/**
 * Drives the personal fork's upstream rebase notice. It checks Hy4ri release
 * metadata and can open release notes, but it never downloads an APK or
 * invokes Android's package installer because the upstream signing lineage is
 * intentionally different.
 */
class AppUpdateViewModel(
    application: Application,
    private val checker: AppUpdateChecker = AppUpdateChecker(),
    private val currentVersion: String = BuildConfig.VERSION_NAME,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val releaseNotesIntentFactory: (Uri) -> Intent = ::buildReleaseNotesIntent,
) : AndroidViewModel(application) {
    private val _state = MutableStateFlow<AppUpdateState>(AppUpdateState.Idle)
    val state: StateFlow<AppUpdateState> = _state.asStateFlow()

    init {
        // Silent first-launch check: once per installed version. Marked done
        // after the attempt completes (success or failure) so a failed check
        // can retry on the next open, but a dead network can't spam the API
        // on every visit.
        if (AuthManager.getUpdateCheckDoneForVersion() != currentVersion) {
            checkForUpdate()
        } else {
            // Issue #890: the launch check (UpdateNoticeManager) already ran
            // for this version — adopt its result instead of pinging GitHub
            // again, so the About tab agrees with the chat banner.
            val cached = AppUpdateCache.state.value
            if (cached is AppUpdateState.UpdateAvailable || cached is AppUpdateState.UpToDate) {
                _state.value = cached
            }
        }
    }

    /** Manual check from the About row. */
    fun checkForUpdate() {
        if (_state.value is AppUpdateState.Checking) return
        _state.value = AppUpdateState.Checking
        viewModelScope.launch(ioDispatcher) {
            AuthManager.setUpdateCheckDoneForVersion(currentVersion)
            val result =
                try {
                    checker.fetchLatestRelease()
                } catch (e: IOException) {
                    _state.value = AppUpdateState.Error(NETWORK_ERROR)
                    return@launch
                } catch (e: Exception) {
                    _state.value = AppUpdateState.Error(GENERIC_CHECK_ERROR)
                    return@launch
                }
            val info =
                result ?: run {
                    _state.value = AppUpdateState.Error(NO_RELEASE_ERROR)
                    return@launch
                }
            _state.value =
                if (isNewerVersion(info.tagName, currentVersion)) {
                    AppUpdateState.UpdateAvailable(
                        latestTag = info.tagName,
                        releaseNotesUrl = info.htmlUrl.ifBlank { upstreamReleaseUrl(info.tagName) },
                    )
                } else {
                    AppUpdateState.UpToDate(latestTag = info.tagName)
                }
            // Keep the launch notice (issue #890) in sync with manual checks.
            AppUpdateCache.update(_state.value)
            _state.value.releaseTag()?.let { AuthManager.setLastKnownLatestTag(it) }
        }
    }

    /** Open upstream release notes so the user can plan a rebase/build/sign smoke. */
    fun openReleaseNotes() {
        val available = _state.value as? AppUpdateState.UpdateAvailable ?: return
        try {
            getApplication<Application>().startActivity(
                releaseNotesIntentFactory(Uri.parse(available.releaseNotesUrl)),
            )
        } catch (e: Exception) {
            _state.value = AppUpdateState.Error(RELEASE_NOTES_ERROR)
        }
    }

    private companion object {
        val NETWORK_ERROR = "Network error — check your connection"
        val GENERIC_CHECK_ERROR = "Couldn't check for updates"
        val NO_RELEASE_ERROR = "No release found yet"
        val RELEASE_NOTES_ERROR = "Couldn't open release notes"
    }
}

internal fun buildReleaseNotesIntent(uri: Uri): Intent =
    Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
