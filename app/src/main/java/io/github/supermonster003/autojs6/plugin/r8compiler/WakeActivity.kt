package io.github.supermonster003.autojs6.plugin.r8compiler

import android.app.Activity
import android.os.Bundle

/** Explicit activation only; compiler initialization remains in the bound service. */
class WakeActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        finish()
    }
}
