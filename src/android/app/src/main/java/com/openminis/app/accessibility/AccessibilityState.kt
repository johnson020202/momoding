// momoding: one place that answers "is the accessibility service on?", so the
// answer cannot differ between the Hands page, the permission pages and the
// hands operator.
package com.openminis.app.accessibility

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import android.view.accessibility.AccessibilityManager

/**
 * Whether the user has granted the accessibility service, and whether this
 * process currently holds it.
 *
 * ## Why two answers
 *
 * The grant lives in `Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES`; the
 * binding lives in whoever called `onServiceConnected`. Checking only
 * `MinisAccessibilityService.getInstance() != null` conflates them and gets
 * the grant question wrong in ways users notice as "it says the permission is
 * off but I just turned it on":
 *
 *  - Right after a cold start (or a force-stop restart) the framework rebinds
 *    the service in a *later* moment; the Settings page is drawn from a
 *    process where the instance is still null while the grant is intact.
 *  - On Xiaomi/MIUI a force-stop leaves the grant in place but the rebind can
 *    take a while or the process can be recycled; the toggle stays on while
 *    `getInstance()` is null.
 *  - Managed profiles / secondary users: Settings.Secure is read per user, an
 *    instance in another process tells us nothing.
 *
 * So [isGranted] answers the question the user is being asked ("did I give the
 * permission?"), and [isConnected] answers the question the operator needs
 * ("can I touch the screen right now?"). UI shows [isGranted]; the operator
 * fails only when the grant is real but no instance exists yet, and says so.
 */
object AccessibilityState {

    /** The `pkg/cls` string the framework keeps in ENABLED_ACCESSIBILITY_SERVICES. */
    fun componentId(context: Context): String =
        "${context.packageName}/${MinisAccessibilityService::class.java.name}"

    /**
     * Parsing half, pure so it can be exercised on the JVM.
     *
     * Accepts the fully-qualified `pkg/pkg.Class` form and the abbreviated
     * `pkg/.Class` form the framework normalizes to, case-insensitively, and
     * also matches when the entry carries extra `;`-separated attributes
     * (MIUI/HyperOS rewrites some entries that way).
     */
    fun isListed(enabledValue: String?, pkg: String, serviceClass: String): Boolean {
        if (enabledValue.isNullOrBlank()) return false
        val fq = "$pkg/$serviceClass"
        // The framework may shorten the class to `.<nested.Part>` (dropping
        // `com.openminis.app.`) or to `.Class`; accept every suffix form as
        // long as what remains is still a dotted tail of our own class name.
        fun matches(entry: String): Boolean {
            if (entry.equals(fq, ignoreCase = true)) return true
            val slash = entry.indexOf('/')
            if (slash <= 0 || !entry.substring(0, slash).equals(pkg, ignoreCase = true)) return false
            val cls = entry.substring(slash + 1)
            if (!cls.startsWith('.')) return false
            val tail = cls.removePrefix(".")
            return serviceClass.length > tail.length &&
                serviceClass.endsWith(".$tail", ignoreCase = true)
        }
        return enabledValue.split(':').any { entry ->
            matches(entry.trim().substringBefore(';').trim())
        }
    }

    /**
     * True when the user has granted the service: our component is listed in
     * ENABLED_ACCESSIBILITY_SERVICES and the master switch is on, or the
     * framework already bound us in this process.
     *
     * Read through [AccessibilityManager.getEnabledAccessibilityServiceList]
     * first — it is the framework's own view, is already reflected in the
     * process, and cannot lag behind a Settings.Secure write — then fall back
     * to the raw setting for the case where the manager was queried before the
     * change was delivered.
     */
    fun isGranted(context: Context): Boolean {
        if (MinisAccessibilityService.getInstance() != null) return true
        val pkg = context.packageName
        val cls = MinisAccessibilityService::class.java.name
        val viaManager = runCatching {
            val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
            am?.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
                ?.any { info ->
                    val ri = info.resolveInfo?.serviceInfo ?: return@any false
                    ri.packageName.equals(pkg, ignoreCase = true) &&
                        (ri.name == cls || ri.name == cls.removePrefix("$pkg."))
                } == true
        }.getOrDefault(false)
        if (viaManager) return true
        return runCatching {
            listedInSettings(context)
        }.getOrDefault(false)
    }

    /** Raw Settings.Secure read; separated so a failure there cannot be an exception upstream. */
    fun listedInSettings(context: Context): Boolean {
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        )
        if (!isListed(enabled, context.packageName, MinisAccessibilityService::class.java.name)) {
            return false
        }
        // A listed component with the master toggle off is not usable and the
        // framework will not bind it; treat it as not granted (the user has to
        // do something in Settings either way).
        val master = Settings.Secure.getInt(
            context.contentResolver,
            Settings.Secure.ACCESSIBILITY_ENABLED,
            0,
        )
        return master == 1 || MinisAccessibilityService.getInstance() != null
    }

    /** True when this process currently holds a bound service instance. */
    fun isConnected(): Boolean = MinisAccessibilityService.getInstance() != null

    /**
     * The service is declared with our manifest; a ROM that stripped it (or a
     * clone without it) must not be told "go turn it on".
     */
    fun isDeclaredOnDevice(context: Context): Boolean = runCatching {
        val pm = context.packageManager
        pm.queryIntentServices(
            android.content.Intent("android.accessibilityservice.AccessibilityService").setPackage(context.packageName),
            PackageManager.GET_META_DATA,
        ).any { it.serviceInfo?.name == MinisAccessibilityService::class.java.name }
    }.getOrDefault(true)
}
