package ab.inque.converter

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton

class LandingActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_landing)
        handleUnlockUriIntent(intent)
        findViewById<MaterialButton>(R.id.getStartedButton).setOnClickListener {
            startActivity(Intent(this, MainActivity::class.java))
            finish()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleUnlockUriIntent(intent)
    }

    private fun handleUnlockUriIntent(intent: Intent?) {
        val data = intent?.data ?: return
        val uriString = data.toString()
        if (PremiumUnlockUri.isValidUnlockUri(uriString)) {
            ConversionPrefs.setUnlocked(this, true)
            startActivity(Intent(this, MainActivity::class.java))
            finish()
        }
    }
}
