package com.openminis.app.accessibility

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [momoding-a11y-grant-vs-binding] The grant question is answered by parsing
 * ENABLED_ACCESSIBILITY_SERVICES, so a false negative here shows the user "the
 * accessibility service is off" while the switch is on — the reported bug.
 * Pure, so it runs on the JVM without a Context.
 */
class AccessibilityStateTest {

    private val PKG = "app.momoding"
    private val CLS = "com.openminis.app.accessibility.MinisAccessibilityService"

    private fun listed(value: String?) = AccessibilityState.isListed(value, PKG, CLS)

    @Test
    fun `null and blank are not listed`() {
        assertFalse(listed(null))
        assertFalse(listed(""))
        assertFalse(listed("   "))
    }

    @Test
    fun `fully qualified entry is listed`() {
        assertTrue(listed("$PKG/$CLS"))
    }

    @Test
    fun `abbreviated entry is listed`() {
        assertTrue(listed("$PKG/.accessibility.MinisAccessibilityService"))
    }

    @Test
    fun `case and whitespace are tolerated`() {
        assertTrue(listed("  ${"$PKG/$CLS".uppercase()}  "))
    }

    @Test
    fun `entry with trailing attributes is listed`() {
        // Some ROMs append `;key=value` to an entry.
        assertTrue(listed("$PKG/$CLS;flag=1"))
    }

    @Test
    fun `listed among other services`() {
        val talkback = "com.google.android.marvin.talkback/.TalkBackService"
        assertTrue(listed("$talkback:$PKG/$CLS"))
    }

    @Test
    fun `other services without ours is not listed`() {
        assertFalse(listed("com.google.android.marvin.talkback/.TalkBackService"))
    }

    @Test
    fun `the upstream package id is not mistaken for ours`() {
        // The class namespace is com.openminis.app while the installed id is
        // app.momoding; an upstream id in the list must not read as granted.
        assertFalse(listed("com.openminis.app/$CLS"))
    }

    @Test
    fun `a same-named class in another package is not listed`() {
        assertFalse(listed("app.momoding/other.MinisAccessibilityService"))
    }
}
