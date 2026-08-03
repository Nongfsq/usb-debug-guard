package io.github.nongfsq.usbdebugguard

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateCheckerTest {
    @Test
    fun newerReleaseIsAvailable() {
        val result = checker("0.1.3", """{"tag_name":"v0.1.4"}""").check()

        assertEquals(
            UpdateCheckResult.Available(
                version = "0.1.4",
                releaseUrl = GitHubReleaseUpdateChecker.LATEST_RELEASE_PAGE,
            ),
            result,
        )
    }

    @Test
    fun equalReleaseIsUpToDate() {
        assertEquals(
            UpdateCheckResult.UpToDate("0.1.3"),
            checker("0.1.3", """{"tag_name": "v0.1.3"}""").check(),
        )
    }

    @Test
    fun olderReleaseDoesNotOfferDowngrade() {
        assertEquals(
            UpdateCheckResult.UpToDate("0.1.2"),
            checker("0.1.3", """{"tag_name":"v0.1.2"}""").check(),
        )
    }

    @Test
    fun stableReleaseSupersedesPrerelease() {
        assertTrue(
            checker("0.2.0-beta.1", """{"tag_name":"v0.2.0"}""").check()
                is UpdateCheckResult.Available,
        )
    }

    @Test
    fun malformedResponseFailsClosed() {
        assertEquals(UpdateCheckResult.Failed, checker("0.1.3", "{}").check())
        assertEquals(
            UpdateCheckResult.Failed,
            checker("0.1.3", """{"tag_name":"latest"}""").check(),
        )
    }

    @Test
    fun networkExceptionReturnsGenericFailure() {
        val checker = GitHubReleaseUpdateChecker(
            feedClient = ReleaseFeedClient { throw IOException("offline") },
            currentVersion = "0.1.3",
        )

        assertEquals(UpdateCheckResult.Failed, checker.check())
    }

    private fun checker(current: String, response: String) = GitHubReleaseUpdateChecker(
        feedClient = ReleaseFeedClient { response },
        currentVersion = current,
    )
}
