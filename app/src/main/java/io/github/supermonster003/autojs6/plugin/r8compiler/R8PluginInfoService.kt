package io.github.supermonster003.autojs6.plugin.r8compiler

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.IBinder
import org.autojs.plugin.common.api.IPluginInfoProvider
import org.autojs.plugin.common.api.PluginCapabilityKeys
import org.autojs.plugin.common.api.PluginInfo

/** The common discovery endpoint does not change the published R8 wire protocol. */
class R8PluginInfoService : Service() {
    private val binder = object : IPluginInfoProvider.Stub() {
        override fun getInfo(): PluginInfo {
            val info = R8CompilerRuntime.info(this@R8PluginInfoService)
            return PluginInfo(
                name = getString(R.string.app_name),
                description = getString(R.string.plugin_description),
                instruction = null,
                author = getString(R.string.plugin_author),
                collaborators = null,
                versionName = info.providerVersionName,
                versionCode = info.providerVersionCode,
                versionDate = getString(R.string.plugin_version_date),
                id = getString(R.string.plugin_id),
                engine = getString(R.string.plugin_engine),
                variant = getString(R.string.plugin_variant),
                supportedAbis = emptyArray(),
                capabilities = Bundle().apply {
                    putLong(PluginCapabilityKeys.REQUIRES_HOST_VERSION, R8CompilerRuntime.REQUIRED_HOST_VERSION)
                    putString("r8CompilerProtocolMin", info.protocolMin.toString())
                    putString("r8CompilerProtocolMax", info.protocolMax.toString())
                },
            )
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder
}
