package ab.inque.converter

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ConversionPrefsTest {

    private val context get() = ConversionTestHelper.appContext()

    @Before
    fun resetPrefs() {
        context.getSharedPreferences("conversion_prefs", android.content.Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    @Test
    fun getPoints_defaultBalance() {
        assertEquals(10, ConversionPrefs.getPoints(context))
    }

    @Test
    fun spendPoints_reducesBalance() {
        ConversionPrefs.spendPoints(context, 3)
        assertEquals(7, ConversionPrefs.getPoints(context))
    }

    @Test
    fun addPoints_increasesBalance() {
        ConversionPrefs.addPoints(context, 5)
        assertEquals(15, ConversionPrefs.getPoints(context))
    }

    @Test
    fun ttsSpeechRate_roundTrip() {
        ConversionPrefs.setTtsSpeechRate(context, 1.5f)
        assertEquals(1.5f, ConversionPrefs.getTtsSpeechRate(context))
    }

    @Test
    fun ttsVoiceName_roundTrip() {
        ConversionPrefs.setTtsVoiceName(context, "en-US-test")
        assertEquals("en-US-test", ConversionPrefs.getTtsVoiceName(context))
    }

    @Test
    fun isUnlocked_debugBuild() {
        // Debug APK: unlimited unless override forces regular tier.
        assertTrue(ConversionPrefs.isUnlocked(context))
    }
}
