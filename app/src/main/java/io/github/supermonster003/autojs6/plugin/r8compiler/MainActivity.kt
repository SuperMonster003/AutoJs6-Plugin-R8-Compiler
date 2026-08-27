package io.github.supermonster003.autojs6.plugin.r8compiler

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Button
import android.widget.TextView

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        findViewById<TextView>(R.id.version_value).text = getString(
            R.string.version_value,
            BuildConfig.VERSION_NAME,
            BuildConfig.R8_COMPILER_VERSION,
        )

        val serviceComponent = ComponentName(this, R8CompilerService::class.java)
        findViewById<TextView>(R.id.service_status_value).text = getString(
            if (isServiceAvailable(serviceComponent)) {
                R.string.service_available
            } else {
                R.string.service_unavailable
            },
        )
        findViewById<TextView>(R.id.service_component_value).text = getString(
            R.string.service_component_value,
            serviceComponent.flattenToString(),
        )

        findViewById<Button>(R.id.open_changelog).setOnClickListener {
            startActivity(Intent(this, ChangelogActivity::class.java))
        }
    }

    @Suppress("DEPRECATION")
    private fun isServiceAvailable(component: ComponentName): Boolean {
        return when (packageManager.getComponentEnabledSetting(component)) {
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER,
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED,
            -> false

            PackageManager.COMPONENT_ENABLED_STATE_DEFAULT,
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
            -> isServiceDeclared(component)

            else -> false
        }
    }

    @Suppress("DEPRECATION")
    private fun isServiceDeclared(component: ComponentName): Boolean = try {
        packageManager.getServiceInfo(component, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }
}
