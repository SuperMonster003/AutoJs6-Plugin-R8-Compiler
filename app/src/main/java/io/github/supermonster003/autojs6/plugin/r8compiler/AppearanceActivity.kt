package io.github.supermonster003.autojs6.plugin.r8compiler

import android.content.Context
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowInsetsController
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate

abstract class AppearanceActivity : AppCompatActivity() {
    internal lateinit var resolvedAppearance: ResolvedAppearance
        private set
    internal val settingsPalette get() = SettingsPalette(resolvedAppearance)
    private var generation = 0
    private var userInteracted = false
    protected open val hasUnconfirmedDialog: Boolean get() = false

    override fun onUserInteraction() { userInteracted=true; super.onUserInteraction() }
    protected open val recreateForHostChanges = true

    override fun attachBaseContext(newBase: Context) {
        resolvedAppearance = AppearanceSource.resolve(newBase)
        delegate.localNightMode = if (resolvedAppearance.night) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO
        super.attachBaseContext(resolvedAppearance.wrap(newBase))
    }

    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        LauncherIcons.normalizeAsync(this)
        val palette = settingsPalette
        val decor = window.decorView
        if (Build.VERSION.SDK_INT >= 29) decor.isForceDarkAllowed = false
        window.setBackgroundDrawable(ColorDrawable(palette.background))
        window.statusBarColor = palette.background
        window.navigationBarColor = if (Build.VERSION.SDK_INT >= 26) palette.background else 0xff121212.toInt()
        if (Build.VERSION.SDK_INT >= 30) window.insetsController?.setSystemBarsAppearance(
            if (resolvedAppearance.night) 0 else WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
            WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS)
        else decor.systemUiVisibility = if (resolvedAppearance.night) 0 else View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or
            if (Build.VERSION.SDK_INT >= 26) View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR else 0
    }

    override fun onStart() {
        super.onStart()
        userInteracted=false
        if (!recreateForHostChanges) return
        val expected = ++generation
        AppearanceSource.worker.execute {
            val snapshot = AppearanceSource.readHost(applicationContext)
            runOnUiThread {
                if (expected != generation || isFinishing || isDestroyed) return@runOnUiThread
                AppearanceSource.host = snapshot
                if (!userInteracted && !hasUnconfirmedDialog && AppearanceSource.resolve(applicationContext, snapshot) != resolvedAppearance) recreate()
            }
        }
    }

    override fun onStop() { generation++; super.onStop() }
}
