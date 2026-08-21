package com.m57.hermescontrol.ui.settings

import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import com.m57.hermescontrol.data.local.AuthManager
import com.m57.hermescontrol.data.update.AppUpdateCache
import com.m57.hermescontrol.data.update.AppUpdateChecker
import com.m57.hermescontrol.data.update.AppUpdateState
import com.m57.hermescontrol.data.update.UpdateInfo
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.IOException

/**
 * AppUpdateViewModel state machine (issue #867): silent first-launch check,
 * check/update flows, the unknown-sources gate, and the installer launch.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AppUpdateViewModelTest {
    private val testDispatcher = StandardTestDispatcher()
    private lateinit var app: Application
    private lateinit var packageManager: PackageManager
    private lateinit var checker: AppUpdateChecker

    private val currentVersion = "1.21.0"

    private fun updateInfo(
        tag: String = "v1.22.0",
        releaseUrl: String = "https://github.com/Hy4ri/hermes-mobile/releases/tag/v1.22.0",
    ): UpdateInfo =
        UpdateInfo(
            tagName = tag,
            htmlUrl = releaseUrl,
        )

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        AppUpdateCache.reset()
        mockkObject(AuthManager)
        every { AuthManager.getUpdateCheckDoneForVersion() } returns null
        every { AuthManager.setUpdateCheckDoneForVersion(any()) } returns Unit
        every { AuthManager.setLastKnownLatestTag(any()) } returns Unit

        app = mockk(relaxed = true)
        every { app.cacheDir } returns File(System.getProperty("java.io.tmpdir"))
        every { app.packageName } returns "com.m57.hermescontrol"
        packageManager = mockk(relaxed = true)
        every { app.packageManager } returns packageManager

        checker = mockk()
        coEvery { checker.fetchLatestRelease() } returns updateInfo()
    }

    @After
    fun tearDown() {
        unmockkAll()
        Dispatchers.resetMain()
    }

    private fun createViewModel(): AppUpdateViewModel = AppUpdateViewModel(app, checker, currentVersion, testDispatcher)

    // ── Silent first-launch check ───────────────────────────────────────

    @Test
    fun init_runsSilentCheckWhenNeverChecked() =
        runTest {
            val vm = createViewModel()
            advanceUntilIdle()

            coVerify(exactly = 1) { checker.fetchLatestRelease() }
            assertEquals(
                AppUpdateState.UpdateAvailable(
                    "v1.22.0",
                    "https://github.com/Hy4ri/hermes-mobile/releases/tag/v1.22.0",
                ),
                vm.state.value,
            )
            coVerify(exactly = 1) { AuthManager.setUpdateCheckDoneForVersion(currentVersion) }
        }

    @Test
    fun init_skipsCheckWhenAlreadyCheckedForThisVersion() =
        runTest {
            every { AuthManager.getUpdateCheckDoneForVersion() } returns currentVersion

            val vm = createViewModel()
            advanceUntilIdle()

            coVerify(exactly = 0) { checker.fetchLatestRelease() }
            assertEquals(AppUpdateState.Idle, vm.state.value)
        }

    @Test
    fun init_rechecksOnVersionBump() =
        runTest {
            every { AuthManager.getUpdateCheckDoneForVersion() } returns "1.20.0"

            val vm = createViewModel()
            advanceUntilIdle()

            coVerify(exactly = 1) { checker.fetchLatestRelease() }
        }

    @Test
    fun init_adoptsLaunchCheckResultWithoutNetwork() =
        runTest {
            // Issue #890: the launch check already ran for this version and
            // found an update — the About tab must adopt it, not ping GitHub.
            every { AuthManager.getUpdateCheckDoneForVersion() } returns currentVersion
            AppUpdateCache.update(
                AppUpdateState.UpdateAvailable(
                    latestTag = "v1.22.0",
                    releaseNotesUrl = "https://github.com/Hy4ri/hermes-mobile/releases/tag/v1.22.0",
                ),
            )

            val vm = createViewModel()
            advanceUntilIdle()

            coVerify(exactly = 0) { checker.fetchLatestRelease() }
            assertEquals(
                AppUpdateState.UpdateAvailable(
                    "v1.22.0",
                    "https://github.com/Hy4ri/hermes-mobile/releases/tag/v1.22.0",
                ),
                vm.state.value,
            )
        }

    // ── Manual check ────────────────────────────────────────────────────

    @Test
    fun checkForUpdate_upToDateWhenSameVersion() =
        runTest {
            coEvery { checker.fetchLatestRelease() } returns updateInfo(tag = "v1.21.0")

            val vm = createViewModel()
            advanceUntilIdle()
            assertEquals(AppUpdateState.UpToDate("v1.21.0"), vm.state.value)
        }

    @Test
    fun checkForUpdate_networkFailure_surfacesError() =
        runTest {
            coEvery { checker.fetchLatestRelease() } throws IOException("boom")

            val vm = createViewModel()
            advanceUntilIdle()

            val state = vm.state.value
            assertTrue(state is AppUpdateState.Error)
            assertEquals("Network error — check your connection", (state as AppUpdateState.Error).message)
        }

    @Test
    fun checkForUpdate_noReleaseYet_surfacesError() =
        runTest {
            coEvery { checker.fetchLatestRelease() } returns null

            val vm = createViewModel()
            advanceUntilIdle()

            val state = vm.state.value
            assertTrue(state is AppUpdateState.Error)
            assertEquals("No release found yet", (state as AppUpdateState.Error).message)
        }

    @Test
    fun checkForUpdate_releaseWithoutAssets_reportsRebaseNotice() =
        runTest {
            coEvery {
                checker.fetchLatestRelease()
            } returns
                UpdateInfo(
                    tagName = "v1.22.0",
                    htmlUrl = "https://github.com/Hy4ri/hermes-mobile/releases/tag/v1.22.0",
                )

            val vm = createViewModel()
            advanceUntilIdle()

            assertTrue(vm.state.value is AppUpdateState.UpdateAvailable)
        }

    @Test
    fun checkForUpdate_marksDoneEvenWhenCheckFails() =
        runTest {
            coEvery { checker.fetchLatestRelease() } throws IOException("boom")

            val vm = createViewModel()
            advanceUntilIdle()

            coVerify(exactly = 1) { AuthManager.setUpdateCheckDoneForVersion(currentVersion) }
        }

    // ── Rebase-notice flow ───────────────────────────────────────────────

    @Test
    fun openReleaseNotes_opensTheUpstreamReleaseWithoutDownloadingAnApk() =
        runTest {
            val uri = mockk<Uri>()
            mockkStatic(Uri::class)
            every { Uri.parse("https://github.com/Hy4ri/hermes-mobile/releases/tag/v1.22.0") } returns uri
            val intentMock = mockk<Intent>()
            val capturedIntents = mutableListOf<Intent>()
            every { app.startActivity(capture(capturedIntents)) } returns Unit

            val vm =
                AppUpdateViewModel(app, checker, currentVersion, testDispatcher) { openedUri ->
                    assertTrue(openedUri === uri)
                    intentMock
                }
            advanceUntilIdle()

            vm.openReleaseNotes()

            assertEquals(
                AppUpdateState.UpdateAvailable(
                    "v1.22.0",
                    "https://github.com/Hy4ri/hermes-mobile/releases/tag/v1.22.0",
                ),
                vm.state.value,
            )
            assertTrue("release notes must open", capturedIntents.single() === intentMock)
        }

    @Test
    fun openReleaseNotes_isIgnoredWhenNoUpdateIsAvailable() =
        runTest {
            every { AuthManager.getUpdateCheckDoneForVersion() } returns currentVersion

            val vm = createViewModel()
            advanceUntilIdle()
            assertEquals(AppUpdateState.Idle, vm.state.value)

            vm.openReleaseNotes()

            verify(exactly = 0) { app.startActivity(any()) }
        }
}
