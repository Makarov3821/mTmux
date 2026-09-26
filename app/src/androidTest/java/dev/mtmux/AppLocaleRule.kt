package dev.mtmux

import android.app.LocaleManager
import android.content.ComponentName
import android.content.pm.PackageManager
import android.os.Build
import android.os.LocaleList
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.rules.ExternalResource

/**
 * Applies a per-app language before the Activity launches and restores "follow system" afterwards.
 * AppCompatDelegate ignores the call on Android 13+ while no AppCompat Activity exists, so use the
 * platform LocaleManager there and wait until the process configuration reflects it.
 */
class AppLocaleRule(private val tag: String) : ExternalResource() {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext

    private fun apply(tags: String) {
        if (Build.VERSION.SDK_INT >= 33) {
            context.getSystemService(LocaleManager::class.java).applicationLocales = LocaleList.forLanguageTags(tags)
        } else InstrumentationRegistry.getInstrumentation().runOnMainSync {
            AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tags))
        }
    }

    override fun before() {
        if (Build.VERSION.SDK_INT >= 33) {
            // AppCompat's one-time "storage -> framework" sync after install is keyed on this
            // component being disabled; left pending, it resets the locale set below on first launch.
            context.packageManager.setComponentEnabledSetting(
                ComponentName(context, "androidx.appcompat.app.AppLocalesMetadataHolderService"),
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP)
        }
        apply(tag)
        if (Build.VERSION.SDK_INT >= 33) {
            val language = tag.substringBefore('-')
            val deadline = System.currentTimeMillis() + 10_000
            while (context.resources.configuration.locales[0].language != language && System.currentTimeMillis() < deadline) Thread.sleep(50)
            check(context.resources.configuration.locales[0].language == language) { "App locale $tag was not applied" }
        }
    }

    override fun after() = apply("")
}
