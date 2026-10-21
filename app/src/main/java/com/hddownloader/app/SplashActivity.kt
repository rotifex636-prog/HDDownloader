package com.hddownloader.app

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.appcompat.app.AppCompatActivity

/**
 * App khulte hi pyara sa branding preview — sirf ~1.6 second, phir main screen.
 * (Kul waqt splash samet 2 second se zyada nahi.)
 */
class SplashActivity : AppCompatActivity() {

    private val handler = Handler(Looper.getMainLooper())
    private val goMain = Runnable {
        if (!isFinishing) {
            startActivity(Intent(this, MainActivity::class.java))
            finish()
            @Suppress("DEPRECATION")
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Agar share intent se aya hai to splash mat dikhao, seedha main par jao
        if (intent?.action == Intent.ACTION_SEND) {
            val i = Intent(this, MainActivity::class.java).apply {
                action = intent.action
                type = intent.type
                putExtras(intent)
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            startActivity(i)
            finish()
            return
        }
        setContentView(R.layout.activity_splash)
        handler.postDelayed(goMain, 1600)
    }

    override fun onDestroy() {
        handler.removeCallbacks(goMain)
        super.onDestroy()
    }
}
