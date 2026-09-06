package net.primal.android.navigation

import androidx.lifecycle.SavedStateHandle
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavDestination
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import net.primal.android.core.compose.PrimalTopLevelDestination
import org.junit.Test

/**
 * Home/Messages/Notifications/Settings are the four bottom-nav destinations this app currently
 * gives a consistent, position-aware slide direction to (left-to-right visual order: Home,
 * Messages, Notifications, Settings) — see slidesRightToLeft's doc for why Profile is excluded.
 */
class NavigationTransitionsTest {

    @Test
    fun `moving from main(Home) to messages slides right-to-left`() {
        val from = entryFor(route = "main")
        val to = entryFor(route = "messages")
        slidesRightToLeft(from, to) shouldBe true
    }

    @Test
    fun `moving from messages back to main(Home) slides left-to-right`() {
        val from = entryFor(route = "messages")
        val to = entryFor(route = "main")
        slidesRightToLeft(from, to) shouldBe false
    }

    @Test
    fun `moving from main(Notifications) to messages slides left-to-right`() {
        val from = entryFor(route = "main", currentMainTab = PrimalTopLevelDestination.Alerts)
        val to = entryFor(route = "messages")
        slidesRightToLeft(from, to) shouldBe false
    }

    @Test
    fun `moving from messages to main(Notifications) slides right-to-left`() {
        val from = entryFor(route = "messages")
        val to = entryFor(route = "main", currentMainTab = PrimalTopLevelDestination.Alerts)
        slidesRightToLeft(from, to) shouldBe true
    }

    @Test
    fun `moving from messages to settings slides right-to-left`() {
        val from = entryFor(route = "messages")
        val to = entryFor(route = "home_settings")
        slidesRightToLeft(from, to) shouldBe true
    }

    @Test
    fun `moving from settings to main(Home) slides left-to-right`() {
        val from = entryFor(route = "home_settings")
        val to = entryFor(route = "main")
        slidesRightToLeft(from, to) shouldBe false
    }

    @Test
    fun `a route outside the tab set returns null, deferring to the caller's fallback`() {
        val from = entryFor(route = "main")
        val to = entryFor(route = "profile")
        slidesRightToLeft(from, to).shouldBeNull()
    }

    @Test
    fun `enterTransitionFor and exitTransitionFor fall back when direction is unknown`() {
        enterTransitionFor(rightToLeft = null).shouldBeNull()
        enterTransitionFor(rightToLeft = null, fallback = primalScaleIn) shouldBe primalScaleIn
        exitTransitionFor(rightToLeft = null).shouldBeNull()
        exitTransitionFor(rightToLeft = null, fallback = primalScaleOut) shouldBe primalScaleOut
    }

    @Test
    fun `enterTransitionFor and exitTransitionFor pick the matching slide for a known direction`() {
        enterTransitionFor(rightToLeft = true) shouldBe primalSlideInHorizontallyFromEnd
        enterTransitionFor(rightToLeft = false) shouldBe primalSlideInHorizontallyFromStart
        exitTransitionFor(rightToLeft = true) shouldBe primalSlideOutHorizontallyToStart
        exitTransitionFor(rightToLeft = false) shouldBe primalSlideOutHorizontallyToEnd
    }

    private fun entryFor(route: String, currentMainTab: PrimalTopLevelDestination? = null): NavBackStackEntry {
        val handle = SavedStateHandle()
        if (currentMainTab != null) {
            handle[CURRENT_MAIN_TAB_KEY] = currentMainTab.name
        }
        val destination = mockk<NavDestination> { every { this@mockk.route } returns route }
        return mockk<NavBackStackEntry> {
            every { this@mockk.destination } returns destination
            every { savedStateHandle } returns handle
        }
    }
}
