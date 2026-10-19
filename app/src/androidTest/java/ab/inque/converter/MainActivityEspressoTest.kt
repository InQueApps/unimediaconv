package ab.inque.converter

import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainActivityEspressoTest {

    @get:Rule
    val activityRule = ActivityScenarioRule(MainActivity::class.java)

    @Test
    fun mainScreen_showsCoreCards() {
        onView(withId(R.id.fileSelectionCard)).check(matches(isDisplayed()))
        onView(withId(R.id.convertToCard)).check(matches(isDisplayed()))
        onView(withId(R.id.selectFileButton)).check(matches(isDisplayed()))
    }

    @Test
    fun livePreviewSwitch_exists() {
        onView(withId(R.id.livePreviewSwitch)).check(matches(isDisplayed()))
    }
}
