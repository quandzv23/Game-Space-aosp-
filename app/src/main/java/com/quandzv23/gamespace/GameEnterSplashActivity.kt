package com.quandzv23.gamespace

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewAnimationUtils
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import androidx.appcompat.app.AppCompatActivity
import kotlin.math.hypot

/**
 * Splash động lúc vừa vào game, kiểu "sóng xung + khép tròn (iris)" —
 * gần với cảm giác của Xiaomi Game Turbo: logo bụp vào dứt khoát, sóng xung
 * bung ra quanh logo, sau đó màn hình khép tròn lại từ ngoài vào tâm để lộ
 * game ra, thay vì chỉ fade thường.
 */
class GameEnterSplashActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setWindowAnimations(0)
        setContentView(R.layout.activity_game_splash)

        val root = findViewById<View>(R.id.splash_root)
        val badge = findViewById<View>(R.id.logo_badge)
        val title = findViewById<View>(R.id.logo_title)
        val subtitle = findViewById<View>(R.id.logo_subtitle)
        val rings = listOf(
            findViewById<View>(R.id.ring1),
            findViewById<View>(R.id.ring2),
            findViewById<View>(R.id.ring3)
        )

        // Logo "bụp" vào dứt khoát: từ to hơn 1.3x co lại 1x, không bounce
        val badgeScaleX = ObjectAnimator.ofFloat(badge, "scaleX", 1.3f, 1f)
        val badgeScaleY = ObjectAnimator.ofFloat(badge, "scaleY", 1.3f, 1f)
        val badgeAlpha = ObjectAnimator.ofFloat(badge, "alpha", 0f, 1f)
        val badgeSet = AnimatorSet().apply {
            playTogether(badgeScaleX, badgeScaleY, badgeAlpha)
            duration = 220
            interpolator = DecelerateInterpolator(2.2f)
        }

        // Text hiện ngay sau khi logo "chạm đất"
        val titleAlpha = ObjectAnimator.ofFloat(title, "alpha", 0f, 1f).apply { duration = 200 }
        val subAlpha = ObjectAnimator.ofFloat(subtitle, "alpha", 0f, 1f).apply {
            duration = 200
            startDelay = 60
        }

        // 3 vòng sóng xung bung ra lệch nhịp quanh logo
        val ringAnims = rings.mapIndexed { index, ring ->
            val scaleX = ObjectAnimator.ofFloat(ring, "scaleX", 0.3f, 2.6f)
            val scaleY = ObjectAnimator.ofFloat(ring, "scaleY", 0.3f, 2.6f)
            val alpha = ObjectAnimator.ofFloat(ring, "alpha", 0.7f, 0f)
            AnimatorSet().apply {
                playTogether(scaleX, scaleY, alpha)
                duration = 620
                startDelay = 100L + index * 150L
                interpolator = DecelerateInterpolator()
            }
        }

        val masterSet = AnimatorSet()
        val allAnims = mutableListOf<Animator>(badgeSet, titleAlpha, subAlpha)
        allAnims.addAll(ringAnims)
        masterSet.playTogether(allAnims)
        masterSet.start()

        // Sau khi hiệu ứng chạy xong, khép tròn (iris) lộ game ra rồi đóng lại
        Handler(Looper.getMainLooper()).postDelayed({ revealGame(root) }, 850)
    }

    private fun revealGame(root: View) {
        val cx = root.width / 2
        val cy = root.height / 2
        val startRadius = hypot(cx.toDouble(), cy.toDouble()).toFloat()
        val anim = ViewAnimationUtils.createCircularReveal(root, cx, cy, startRadius, 0f)
        anim.duration = 360
        anim.interpolator = AccelerateInterpolator()
        anim.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                finish()
                overridePendingTransition(0, 0)
            }
        })
        anim.start()
    }

    override fun onBackPressed() {
        // Không cho người dùng thao tác gì trong lúc splash chạy
    }
}
