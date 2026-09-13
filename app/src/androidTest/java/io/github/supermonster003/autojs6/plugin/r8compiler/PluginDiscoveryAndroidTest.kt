package io.github.supermonster003.autojs6.plugin.r8compiler

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import androidx.test.platform.app.InstrumentationRegistry
import org.autojs.plugin.common.api.IPluginInfoProvider
import org.autojs.plugin.common.api.PluginCapabilityKeys
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class PluginDiscoveryAndroidTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun wakeIsDiscoverableAndPermissionProtected() {
        val pm = context.packageManager
        val application = pm.getApplicationInfo(context.packageName, PackageManager.GET_META_DATA)
        assertEquals(".WakeActivity", application.metaData.getString("org.autojs.plugin.WAKE_ACTIVITY"))
        val intent = Intent("org.autojs.plugin.action.WAKE")
            .addCategory(Intent.CATEGORY_DEFAULT).setPackage(context.packageName)
        val matches = pm.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
        assertEquals(1, matches.size)
        val activity = matches.single().activityInfo
        assertEquals(WakeActivity::class.java.name, activity.name)
        assertTrue(activity.exported)
        assertEquals("org.autojs.permission.PLUGIN", activity.permission)
        assertEquals(android.R.style.Theme_NoDisplay, activity.theme)
    }

    @Test
    fun commonInfoUsesInstalledVersionAndReportsAbiIndependence() {
        val intent = Intent("org.autojs.plugin.INFO")
            .addCategory("r8-compiler").setPackage(context.packageName)
        val matches = context.packageManager.queryIntentServices(intent, 0)
        assertEquals(1, matches.size)
        val service = matches.single().serviceInfo
        assertEquals(R8PluginInfoService::class.java.name, service.name)
        assertTrue(service.exported)
        assertEquals("org.autojs.permission.PLUGIN", service.permission)
        intent.component = ComponentName(service.packageName, service.name)
        val ready = CountDownLatch(1)
        var remote: IBinder? = null
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                remote = binder
                ready.countDown()
            }
            override fun onServiceDisconnected(name: ComponentName) = Unit
        }
        assertTrue(context.bindService(intent, connection, Context.BIND_AUTO_CREATE))
        try {
            assertTrue("INFO service did not bind", ready.await(10, TimeUnit.SECONDS))
            assertEquals("org.autojs.plugin.common.api.IPluginInfoProvider", remote!!.interfaceDescriptor)
            val info = IPluginInfoProvider.Stub.asInterface(remote).info
            val installed = context.packageManager.getPackageInfo(context.packageName, 0)
            assertEquals(installed.versionName, info.versionName)
            @Suppress("DEPRECATION")
            assertEquals(installed.versionCode.toLong(), info.versionCode)
            assertEquals(context.getString(R.string.app_name), info.name)
            assertEquals(context.getString(R.string.plugin_description), info.description)
            assertEquals("r8-compiler", info.id)
            assertEquals("r8-compiler", info.engine)
            assertEquals("r8", info.variant)
            assertEquals(0, requireNotNull(info.supportedAbis).size)
            assertEquals(R8CompilerRuntime.REQUIRED_HOST_VERSION,
                requireNotNull(info.capabilities).getLong(PluginCapabilityKeys.REQUIRES_HOST_VERSION))
        } finally {
            context.unbindService(connection)
        }
    }
}
