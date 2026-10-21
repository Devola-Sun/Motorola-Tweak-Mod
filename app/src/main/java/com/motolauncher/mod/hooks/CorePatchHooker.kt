package com.motolauncher.mod.hooks

import android.content.pm.ApplicationInfo
import android.os.Binder
import android.os.IBinder
import android.os.Process
import android.util.Log
import com.highcapable.yukihookapi.hook.entity.YukiBaseHooker
import com.highcapable.yukihookapi.hook.param.PackageParam
import com.motolauncher.mod.ErrorLogger
import com.motolauncher.mod.HookConfig
import java.lang.reflect.Method
import java.security.cert.X509Certificate
import java.util.concurrent.ConcurrentHashMap

/**
 * CorePatch-style package installer policy bypass for the system framework
 * ("android" / system_server) process.
 *
 * Safety rules (the first two versions caused a system_server crash / LSPosed
 * safe mode):
 * - hooks never take effect before `sys.boot_completed`, so the boot-time
 *   package/APEX scan can never be disturbed (no boot loop)
 * - return values are replaced ONLY when the original return value has the
 *   same type - a wrong value can never cause a ClassCastException
 * - "throw to block" methods are only skipped when reflection proves that
 *   every overload returns void
 * - the known crash site (KeySetManagerService.removeAppKeySetDataLPw, an NPE
 *   inside the keyset cleanup) is skipped as a permanent safety net because it
 *   is only a cleanup routine
 * - calls coming from core platform UIDs (system_server itself, the Bluetooth
 *   stack, Wi-Fi, ...) or from system apps are never rewritten: they are
 *   passed straight through to the original implementation. Without this
 *   guard the boot-time package re-scan after a soft reboot could rewrite the
 *   signature/capability bookkeeping of platform apps (the Bluetooth app lost
 *   INTERACT_ACROSS_USERS and could not turn the adapter on)
 * - platform packages are excluded by name: an argument that belongs to
 *   "android", "com.android.bluetooth", "com.android.systemui" or a
 *   "com.motorola." package is never rewritten either
 * - every lookup and callback body is wrapped in runCatching, and every
 *   failure falls back to "pass the original through"
 */
object CorePatchHooker : YukiBaseHooker() {

    /** Entry used by HookEntry to install this hooker. */
    fun hook(scope: PackageParam) = scope.loadHooker(this)

    private const val TAG = "MotoLauncherMod"

    /**
     * Subject name markers of the platform / vendor signing key. A comparison
     * that involves one of these certificates belongs to the platform itself
     * and is never rewritten.
     */
    private val PLATFORM_CERT_MARKERS = listOf(
        "O=Android",
        "OU=Android",
        "CN=Android",
        "O=Motorola",
        "CN=Motorola"
    )

    /**
     * Packages that must never be touched by the policy bypass. A package is
     * protected when it matches one of these names exactly or starts with one
     * of them.
     */
    private val PROTECTED_PACKAGES = listOf(
        "android",
        "com.android.bluetooth",
        "com.android.systemui",
        "com.motorola."
    )

    private val bypassSignatures: Boolean
        get() = bootCompleted && readFlag(
            HookConfig.KEY_COREPATCH_BYPASS_SIGNATURES,
            HookConfig.DEFAULT_COREPATCH_BYPASS_SIGNATURES
        )

    private val allowDowngrade: Boolean
        get() = bootCompleted && readFlag(
            HookConfig.KEY_COREPATCH_ALLOW_DOWNGRADE,
            HookConfig.DEFAULT_COREPATCH_ALLOW_DOWNGRADE
        )

    private val bypassDigest: Boolean
        get() = bootCompleted && readFlag(
            HookConfig.KEY_COREPATCH_BYPASS_DIGEST,
            HookConfig.DEFAULT_COREPATCH_BYPASS_DIGEST
        )

    /**
     * True once the framework finished booting.
     *
     * While the framework is still starting, the whole CorePatch processing is
     * skipped: the package / APEX re-scan that runs after a soft reboot must
     * see the unmodified platform behaviour, otherwise the capability and
     * permission bookkeeping of the platform apps (the Bluetooth app losing
     * INTERACT_ACROSS_USERS) gets corrupted. Package installs and downgrades
     * only happen after the boot has finished, so nothing is lost here.
     */
    private val bootCompleted: Boolean
        get() = runCatching {
            Class.forName("android.os.SystemProperties")
                .getMethod(
                    "getBoolean",
                    String::class.java,
                    java.lang.Boolean.TYPE
                )
                .invoke(null, "sys.boot_completed", false) as? Boolean ?: false
        }.getOrDefault(false)

    override fun onHook() {
        runCatching { hookSafetyNet() }
            .onFailure { ErrorLogger.log(TAG, "CorePatch: safety net failed", it) }
        runCatching { hookSignatureBypass() }
            .onFailure { ErrorLogger.log(TAG, "CorePatch: signature hooks failed", it) }
        runCatching { hookDowngradeBypass() }
            .onFailure { ErrorLogger.log(TAG, "CorePatch: downgrade hooks failed", it) }
        runCatching { hookDigestBypass() }
            .onFailure { ErrorLogger.log(TAG, "CorePatch: digest hooks failed", it) }

        Log.i(
            TAG,
            "CorePatch hooks installed: bootCompleted=$bootCompleted " +
                "bypassSignatures=${readFlag(HookConfig.KEY_COREPATCH_BYPASS_SIGNATURES, HookConfig.DEFAULT_COREPATCH_BYPASS_SIGNATURES)} " +
                "allowDowngrade=${readFlag(HookConfig.KEY_COREPATCH_ALLOW_DOWNGRADE, HookConfig.DEFAULT_COREPATCH_ALLOW_DOWNGRADE)} " +
                "bypassDigest=${readFlag(HookConfig.KEY_COREPATCH_BYPASS_DIGEST, HookConfig.DEFAULT_COREPATCH_BYPASS_DIGEST)}"
        )
    }

    private fun readFlag(key: String, default: Boolean): Boolean {
        if (!bootCompleted) return default
        return HookConfig.safeGetBoolean(modulePrefs, key, default)
    }

    private fun findMethods(className: String, methodName: String): List<Method> = runCatching {
        Class.forName(className, false, appClassLoader)
            .declaredMethods
            .filter { it.name == methodName }
    }.getOrDefault(emptyList())

    /**
     * Core platform UIDs. Every UID below [FIRST_APP_UID] is a shared
     * platform UID (root, system_server, radio, bluetooth, wifi, adb, ...).
     */
    private val CORE_SYSTEM_UIDS = setOf(
        0,                       // root
        Process.SYSTEM_UID,      // 1000 - system_server
        1001,                    // AID_RADIO
        Process.BLUETOOTH_UID,   // 1002 - com.android.bluetooth
        1009,                    // AID_MOUNT
        Process.WIFI_UID,        // 1010
        1011,                    // AID_ADB
        1012,                    // AID_INSTALL
        1017                     // AID_KEYSTORE
    )

    /** First UID that belongs to a regular (non shared) application. */
    private const val FIRST_APP_UID = 10000

    /** Cached "does this UID belong to a system app" answers. */
    private val systemAppCache = ConcurrentHashMap<Int, Boolean>()

    /**
     * True when the current caller must never be affected by the policy
     * bypass.
     *
     * [Binder.getCallingUid] reports the caller of the binder transaction that
     * is currently being processed. Inside system_server that is:
     * - the installer UID for a real install request (shell, package
     *   installer, ...) - those calls are the ones the bypass is meant for
     * - the system UID (1000) for every internal operation, e.g. the
     *   boot-time package / APEX re-scan after a soft reboot
     * - the UID of a platform app, e.g. the Bluetooth stack (1002)
     *
     * Only the first case may be rewritten. Everything else is passed
     * through untouched so the platform keeps its own bookkeeping.
     */
    private fun isCoreSystemCaller(): Boolean {
        val uid = runCatching { Binder.getCallingUid() }.getOrDefault(-1)
        if (uid < 0) return false
        if (uid == 0) return true
        if (uid in CORE_SYSTEM_UIDS) return true
        if (uid < FIRST_APP_UID) return true
        return systemAppCache.getOrPut(uid) { isSystemAppUid(uid) }
    }

    /**
     * Best effort check whether [uid] belongs to a system app. The package
     * manager is queried through the hidden IPackageManager interface and the
     * answer is cached, because the policy hooks run very often.
     */
    private fun isSystemAppUid(uid: Int): Boolean = runCatching {
        val binder = Class.forName("android.os.ServiceManager")
            .getMethod("getService", String::class.java)
            .invoke(null, "package") as? IBinder ?: return@runCatching false
        val pm = Class.forName("android.content.pm.IPackageManager\$Stub")
            .getMethod("asInterface", IBinder::class.java)
            .invoke(null, binder) ?: return@runCatching false
        val packages = pm.javaClass
            .getMethod("getPackagesForUid", Int::class.javaPrimitiveType)
            .invoke(pm, uid) as? Array<*> ?: return@runCatching false
        val appInfo = pm.javaClass.getMethod(
            "getApplicationInfo",
            String::class.java,
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType
        )
        packages.filterIsInstance<String>().any { name ->
            val info = appInfo.invoke(pm, name, 0, 0) as? ApplicationInfo
            info != null && (info.flags and ApplicationInfo.FLAG_SYSTEM != 0 ||
                info.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP != 0)
        }
    }.getOrDefault(false)

    /**
     * True when [name] is one of the protected platform packages (exact match
     * or prefix match).
     */
    private fun isProtectedPackage(name: String?): Boolean {
        if (name.isNullOrEmpty()) return false
        return PROTECTED_PACKAGES.any { name == it || name.startsWith(it) }
    }

    /**
     * The value that means "the signatures match" on this firmware.
     *
     * `PackageManagerServiceUtils.compareSignatures` returns its own int
     * triple (unknown / mismatch / match) which is NOT the same as
     * `PackageManager.SIGNATURE_MATCH`. Forcing a hard coded value used to
     * report "unknown" during the boot-time package reconciliation, which
     * made the platform drop the granted state of the platform apps (the
     * Bluetooth app lost INTERACT_ACROSS_USERS and could not turn the
     * adapter on). The constant is therefore read from the framework itself
     * and the hook is skipped when it cannot be determined.
     */
    private fun signatureMatchValue(): Int? = runCatching {
        val utils = Class.forName(
            "com.android.server.pm.PackageManagerServiceUtils",
            false,
            appClassLoader
        )
        val field = utils.getDeclaredField("SIGNATURE_MATCH")
        field.isAccessible = true
        field.get(null) as? Int
    }.getOrNull() ?: runCatching {
        val pm = Class.forName("android.content.pm.PackageManager")
        pm.getDeclaredField("SIGNATURE_MATCH").get(null) as? Int
    }.getOrNull()

    /**
     * True when one of the hook arguments carries the platform / vendor
     * signing certificate. Such a comparison is part of the platform's own
     * bookkeeping and is never rewritten.
     */
    private fun hasPlatformCertificate(args: Any?): Boolean =
        argumentsOf(args).any { isPlatformSignedCertificate(it) }

    /** True when [value] carries a platform / vendor signed certificate. */
    private fun isPlatformSignedCertificate(value: Any?): Boolean {
        val certificates = extractCertificates(value) ?: return false
        return certificates.any { certificate ->
            val dn = runCatching { certificate.subjectX500Principal.name }.getOrDefault("")
            PLATFORM_CERT_MARKERS.any { dn.contains(it, ignoreCase = true) }
        }
    }

    /**
     * Digs the X509 certificates out of a SigningDetails / certificate
     * collection. Returns null when nothing could be read.
     */
    private fun extractCertificates(value: Any?): List<X509Certificate>? = runCatching {
        if (value == null) return@runCatching null
        if (value is X509Certificate) return@runCatching listOf(value)
        if (value is Array<*>) {
            val list = value.filterIsInstance<X509Certificate>()
            if (list.isNotEmpty()) return@runCatching list
        }
        var clazz: Class<*>? = value.javaClass
        while (clazz != null) {
            val field = runCatching {
                clazz!!.declaredFields.firstOrNull {
                    it.name.contains("SigningCertificateLineage", ignoreCase = true) ||
                        it.name.contains("Certificates", ignoreCase = true) ||
                        it.name == "mCerts"
                }
            }.getOrNull()
            if (field != null) {
                val inner = runCatching {
                    field.isAccessible = true
                    field.get(value)
                }.getOrNull()
                val certificates = when (inner) {
                    is Array<*> -> inner.filterIsInstance<X509Certificate>()
                    null -> emptyList()
                    else -> runCatching {
                        inner.javaClass.getMethod("getCertificates").invoke(inner) as? Array<*>
                    }.getOrNull()?.filterIsInstance<X509Certificate>() ?: emptyList()
                }
                if (certificates.isNotEmpty()) return@runCatching certificates
            }
            clazz = clazz.superclass
        }
        null
    }.getOrNull()

    /**
     * Collects the package names that are visible in the hook arguments or in
     * the hook instance.
     *
     * Returns `null` when the extraction itself failed, so the caller can fall
     * back to "pass the original through".
     */
    private fun collectPackageNames(args: Any?, instance: Any?): List<String>? = runCatching {
        val names = LinkedHashSet<String>()
        argumentsOf(args).forEach { collectPackageName(it, names) }
        collectPackageName(instance, names)
        names.toList()
    }.getOrNull()

    /** Yuki exposes the hook arguments as an array; keep this tolerant. */
    private fun argumentsOf(args: Any?): List<Any?> = when (args) {
        is Array<*> -> args.toList()
        is List<*> -> args
        is Collection<*> -> args.toList()
        else -> emptyList()
    }

    /**
     * Reads a package name from a hook argument: either the argument is the
     * package name itself, or it carries one in a "packageName" style field.
     */
    private fun collectPackageName(value: Any?, out: MutableSet<String>) {
        if (value == null) return
        if (value is String) {
            if (looksLikePackageName(value)) out += value
            return
        }
        runCatching {
            var clazz: Class<*>? = value.javaClass
            while (clazz != null) {
                val field = PACKAGE_NAME_FIELDS
                    .mapNotNull { name -> runCatching { clazz!!.getDeclaredField(name) }.getOrNull() }
                    .firstOrNull()
                if (field != null) {
                    field.isAccessible = true
                    val name = field.get(value) as? String
                    if (name != null && looksLikePackageName(name)) out += name
                    return
                }
                clazz = clazz.superclass
            }
        }
    }

    /** Field names that hold a package name on the PMS helper classes. */
    private val PACKAGE_NAME_FIELDS = listOf("packageName", "mPackageName", "packageFullName")

    /** Loose package-name filter: no path separators, no whitespace. */
    private fun looksLikePackageName(value: String): Boolean {
        if (value.length < 3 || value.length > 255) return false
        if (value.any { it.isWhitespace() || it == '/' || it == '\\' }) return false
        return value.all { it.isLetterOrDigit() || it == '.' || it == '_' || it == '-' }
    }

    /**
     * Decides whether the current call has to be passed through untouched.
     *
     * A call is passed through when
     * - the framework is still booting (the PMS re-scan must not be touched)
     * - the caller is a core platform UID or a system app
     * - one of the arguments belongs to a protected platform package
     *
     * Any error while collecting the information also leads to a pass-through,
     * so a failure can never change the platform behaviour.
     */
    private fun shouldPassThrough(args: Any?, instance: Any?): Boolean {
        if (!bootCompleted) return true
        if (isCoreSystemCaller()) return true
        val packages = collectPackageNames(args, instance) ?: return true
        return packages.any { isProtectedPackage(it) }
    }

    /**
     * Permanent safety net: the keyset cleanup routine threw the NPE that took
     * down system_server. It only removes cached keyset data, so skipping it
     * can never break an install - it just keeps some cache entries.
     *
     * The net only runs after the boot has finished (it protects package
     * installs) and it is not restricted to non-core callers, because this
     * method is called by system_server itself.
     */
    private fun hookSafetyNet() {
        skipVoidMethod(
            "com.android.server.pm.KeySetManagerService",
            "removeAppKeySetDataLPw",
            { bootCompleted },
            respectCoreCallers = false,
            respectProtectedPackages = false
        )
    }

    /**
     * Replaces the return value of [methodName] with [value] while [enabled] is
     * true - but only when the original return value has the same type. If the
     * firmware returns a different type the original result is kept, so the
     * hook can never crash the host process.
     *
     * Calls from core platform UIDs / system apps and calls that belong to a
     * protected platform package are never rewritten; they go straight to the
     * original implementation.
     */
    private fun forceValue(
        className: String,
        methodName: String,
        enabled: () -> Boolean,
        value: Any?
    ) {
        runCatching {
            if (findMethods(className, methodName).isEmpty()) {
                ErrorLogger.log(TAG, "CorePatch: $className#$methodName not found on this firmware, skipped")
                return@runCatching
            }
            findClass(className).hook {
                injectMember {
                    method {
                        name = methodName
                        paramCount(0..12)
                    }.all()
                    afterHook {
                        runCatching {
                            // Guard: platform callers, protected packages and
                            // the boot scan keep the original result.
                            if (shouldPassThrough(args, instance)) return@runCatching
                            // Guard: a comparison that involves the platform
                            // signing key is the platform's own bookkeeping.
                            if (hasPlatformCertificate(args)) return@runCatching
                            if (!enabled()) return@runCatching
                            val current = result
                            val sameType = (value is Boolean && current is Boolean) ||
                                (value is Int && current is Int) ||
                                (value is Long && current is Long)
                            if (sameType) result = value
                        }
                    }
                }
            }
        }.onFailure { ErrorLogger.log(TAG, "CorePatch: could not hook $className#$methodName", it) }
    }

    /**
     * Skips a "throw to block" method while [enabled] is true. Installed only
     * when every overload is verified (by reflection) to return void, so
     * setting a null result can never break a primitive return value.
     *
     * [respectCoreCallers] and [respectProtectedPackages] are only turned off
     * for the crash safety net: that method is called by system_server itself,
     * so the guards would disable the net and bring the crash back.
     */
    private fun skipVoidMethod(
        className: String,
        methodName: String,
        enabled: () -> Boolean,
        respectCoreCallers: Boolean = true,
        respectProtectedPackages: Boolean = true
    ) {
        runCatching {
            val targets = findMethods(className, methodName)
            if (targets.isEmpty() || targets.any { it.returnType != Void.TYPE }) {
                ErrorLogger.log(TAG, "CorePatch: skip $className#$methodName (not a verified void method)")
                return@runCatching
            }
            findClass(className).hook {
                injectMember {
                    method {
                        name = methodName
                        paramCount(0..12)
                    }.all()
                    beforeHook {
                        runCatching {
                            // Guard: platform callers, protected packages and
                            // the boot scan keep the original path.
                            if (respectCoreCallers && isCoreSystemCaller()) return@runCatching
                            if (respectProtectedPackages &&
                                shouldPassThrough(args, instance)
                            ) {
                                return@runCatching
                            }
                            if (enabled()) result = null
                        }
                    }
                }
            }
        }.onFailure { ErrorLogger.log(TAG, "CorePatch: could not hook $className#$methodName", it) }
    }

    /** 1. 署名不一致バイパス: always report "signatures match". */
    private fun hookSignatureBypass() {
        val utils = "com.android.server.pm.PackageManagerServiceUtils"

        // The "match" constant is firmware specific. When it cannot be read
        // the int based hooks are skipped instead of forcing a wrong value
        // (a wrong value corrupts the package bookkeeping of the platform).
        val match = signatureMatchValue()
        if (match != null) {
            forceValue(utils, "compareSignatures", { bypassSignatures }, match)
            forceValue(utils, "matchSignaturesCompat", { bypassSignatures }, match)
        } else {
            ErrorLogger.log(TAG, "CorePatch: signature match constant not found, int hooks skipped")
        }

        // boolean helper.
        forceValue(utils, "signaturesMatchExactly", { bypassSignatures }, true)

        listOf(
            "com.android.server.pm.SigningDetails",
            "com.android.server.pm.PackageParser\$SigningDetails",
            "android.content.pm.PackageParser\$SigningDetails"
        ).forEach { className ->
            listOf(
                "checkCapability",
                "checkCapabilityOrCollect",
                "hasAncestorOrSelf",
                "hasCommonAncestor",
                "signaturesMatchExactly"
            ).forEach { name -> forceValue(className, name, { bypassSignatures }, true) }
        }
    }

    /** 2. ダウングレード制限バイパス: never fail the version check. */
    private fun hookDowngradeBypass() {
        forceValue(
            "com.android.server.pm.InstallPackageHelper",
            "doesVersionCheckFail",
            { allowDowngrade },
            false
        )

        // Throws when the incoming package is older; only skipped when the
        // method is verified to return void on this firmware.
        skipVoidMethod(
            "com.android.server.pm.PackageManagerServiceUtils",
            "checkDowngrade",
            { allowDowngrade }
        )
        skipVoidMethod(
            "com.android.server.pm.PackageManagerService",
            "checkDowngrade",
            { allowDowngrade }
        )
    }

    /** 3. 破損/テスト署名チェックのスキップ: ignore digest verification errors. */
    private fun hookDigestBypass() {
        val utils = "com.android.server.pm.PackageManagerServiceUtils"
        forceValue(utils, "verifySignatures", { bypassDigest || bypassSignatures }, true)
    }
}
