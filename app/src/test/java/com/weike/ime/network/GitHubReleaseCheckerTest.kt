package com.weike.ime.network

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubReleaseCheckerTest {
    @Test
    fun `version comparison handles tags and multi digit components`() {
        assertTrue(GitHubReleaseChecker.isNewer("v1.4.10", "1.4.2"))
        assertTrue(GitHubReleaseChecker.isNewer("1.5.0", "1.4.99"))
        assertFalse(GitHubReleaseChecker.isNewer("v1.4.2", "1.4.2"))
        assertFalse(GitHubReleaseChecker.isNewer("1.4.1", "1.4.2"))
    }
}
