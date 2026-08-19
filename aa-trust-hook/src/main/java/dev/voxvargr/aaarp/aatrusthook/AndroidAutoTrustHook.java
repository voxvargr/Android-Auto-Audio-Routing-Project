package dev.voxvargr.aaarp.aatrusthook;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.InstallSourceInfo;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.View;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import io.github.libxposed.api.XposedInterface.ExceptionMode;
import io.github.libxposed.api.XposedModule;

/**
 * Admits AAARP's two volume shortcuts and replaces Android Auto's final two dock callbacks.
 *
 * <p>The module is statically scoped to Gearhead. It records only InstallSourceInfo instances
 * returned for the exact helper package IDs and overrides only those instances' initiating package
 * name. Dock callbacks synchronously enqueue a command through the already-protected main AAARP
 * provider instead of cold-starting either helper or opening a car-app screen. Any lookup or hook
 * failure leaves the platform result unchanged.</p>
 */
public final class AndroidAutoTrustHook extends XposedModule {
    private static final String TAG = "AAARP-TrustHook";
    private static final String PLAY_STORE_PACKAGE = "com.android.vending";
    private static final String APPLICATION_PACKAGE_MANAGER =
            "android.app.ApplicationPackageManager";
    // JADX renders Android Auto's default-package classes under a synthetic "defpackage"
    // directory. Their real DEX descriptors are Lmkx;, Lnhc;, Lnhr;, Lnkn;, and Lnhl;.
    private static final String GEARHEAD_DOCK_OBSERVER = "mkx";
    private static final String GEARHEAD_RAIL_FRAGMENT = "nhc";
    private static final String GEARHEAD_RAIL_VIEW_MODEL = "nhr";
    private static final String GEARHEAD_HOTSEAT_ITEM = "nkn";
    private static final String GEARHEAD_HOTSEAT_ROLE = "nhl";
    private static final String GEARHEAD_DOCK_CLICK_LISTENER = "nkt";

    private final AtomicBoolean installAttempted = new AtomicBoolean();
    private final Map<InstallSourceInfo, String> markedInstallSources =
            Collections.synchronizedMap(new WeakHashMap<>());
    private final Set<String> loggedSpoofs = ConcurrentHashMap.newKeySet();
    private final Set<Integer> loggedDockFeeds = ConcurrentHashMap.newKeySet();
    private final Set<Integer> loggedDockFailures = ConcurrentHashMap.newKeySet();
    private final Set<String> loggedDirectClicks = ConcurrentHashMap.newKeySet();
    private final Set<String> loggedDirectClickFailures = ConcurrentHashMap.newKeySet();
    private volatile boolean gearheadProcess;

    @Override
    public void onModuleLoaded(ModuleLoadedParam param) {
        gearheadProcess = !param.isSystemServer()
                && TrustPolicy.isGearheadProcess(param.getProcessName());
        if (gearheadProcess) {
            report(Log.INFO, "Module loaded in " + param.getProcessName());
        }
    }

    @Override
    public void onPackageReady(PackageReadyParam param) {
        if (!gearheadProcess
                || !TrustPolicy.GEARHEAD_PACKAGE.equals(param.getPackageName())
                || !installAttempted.compareAndSet(false, true)) {
            return;
        }

        try {
            installTrustHooks();
            report(Log.INFO, "Gearhead-only install-source hooks active");
        } catch (Throwable error) {
            // A partially installed first hook can only mark objects; without the second hook it
            // cannot alter any platform result. Keep failures visible and otherwise fail closed.
            reportError("Hooks inactive; package trust left unchanged", error);
        }

        try {
            installFixedDockHook(param.getClassLoader());
            report(Log.INFO, "Gearhead 17.3 fixed Volume+ / Volume- dock hook active");
        } catch (Throwable error) {
            // Android Auto obfuscates these names and may change them in a future update. Keep
            // the stable trust hooks active if this version-specific dock hook no longer matches.
            reportError("Fixed dock hook inactive; Android Auto dock left unchanged", error);
        }

        try {
            installDirectDockClickHook(param.getClassLoader());
            report(Log.INFO, "Gearhead 17.3 no-navigation dock click hook active");
        } catch (Throwable error) {
            // Leave the ordinary helper launch intact if this version-specific click seam moves.
            reportError("Direct dock click hook inactive; normal helper launch retained", error);
        }
    }

    private void installTrustHooks() throws ReflectiveOperationException {
        Class<?> packageManagerClass = Class.forName(
                APPLICATION_PACKAGE_MANAGER,
                false,
                null);
        Method getInstallSourceInfo = packageManagerClass.getDeclaredMethod(
                "getInstallSourceInfo",
                String.class);
        Method getInitiatingPackageName = InstallSourceInfo.class.getDeclaredMethod(
                "getInitiatingPackageName");

        hook(getInstallSourceInfo)
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .intercept(chain -> {
                    Object packageArgument = chain.getArg(0);
                    Object result = chain.proceed();

                    if (packageArgument instanceof String
                            && result instanceof InstallSourceInfo
                            && TrustPolicy.shouldSpoofInstallSource((String) packageArgument)) {
                        markedInstallSources.put(
                                (InstallSourceInfo) result,
                                (String) packageArgument);
                    }
                    return result;
                });

        hook(getInitiatingPackageName)
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .intercept(chain -> {
                    Object source = chain.getThisObject();
                    String packageName = source instanceof InstallSourceInfo
                            ? markedInstallSources.get((InstallSourceInfo) source)
                            : null;

                    if (!TrustPolicy.shouldSpoofInstallSource(packageName)) {
                        return chain.proceed();
                    }

                    if (loggedSpoofs.add(packageName)) {
                        report(Log.INFO, "Using Play install source for " + packageName);
                    }
                    return PLAY_STORE_PACKAGE;
                });
    }

    private void installFixedDockHook(ClassLoader gearheadClassLoader)
            throws ReflectiveOperationException {
        Class<?> dockObserverClass = Class.forName(
                GEARHEAD_DOCK_OBSERVER,
                false,
                gearheadClassLoader);
        Class<?> railFragmentClass = Class.forName(
                GEARHEAD_RAIL_FRAGMENT,
                false,
                gearheadClassLoader);
        Class<?> railViewModelClass = Class.forName(
                GEARHEAD_RAIL_VIEW_MODEL,
                false,
                gearheadClassLoader);
        Class<?> hotseatItemClass = Class.forName(
                GEARHEAD_HOTSEAT_ITEM,
                false,
                gearheadClassLoader);
        Class<?> hotseatRoleClass = Class.forName(
                GEARHEAD_HOTSEAT_ROLE,
                false,
                gearheadClassLoader);

        Field dockFeedField = dockObserverClass.getDeclaredField("b");
        dockFeedField.setAccessible(true);
        Field observerOwnerField = dockObserverClass.getDeclaredField("a");
        observerOwnerField.setAccessible(true);
        Field railViewModelField = railFragmentClass.getDeclaredField("Q");
        railViewModelField.setAccessible(true);
        Field phoneRoleField = hotseatRoleClass.getDeclaredField("a");
        phoneRoleField.setAccessible(true);
        Object phoneRole = phoneRoleField.get(null);
        Field recentRoleField = hotseatRoleClass.getDeclaredField("g");
        recentRoleField.setAccessible(true);
        Object recentRole = recentRoleField.get(null);

        Method createHotseatItem = railViewModelClass.getDeclaredMethod(
                "k",
                railViewModelClass,
                ComponentName.class,
                hotseatRoleClass,
                boolean.class,
                boolean.class,
                boolean.class,
                int.class);
        createHotseatItem.setAccessible(true);
        Method bindDockFeed = dockObserverClass.getDeclaredMethod("eB", Object.class);
        bindDockFeed.setAccessible(true);

        hook(bindDockFeed)
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .intercept(chain -> {
                    Object observer = chain.getThisObject();
                    Object originalItem = chain.getArg(0);
                    int dockFeed = dockFeedField.getInt(observer);
                    String shortcutPackage = TrustPolicy.shortcutPackageForDockFeed(dockFeed);
                    if (shortcutPackage == null
                            || !hotseatItemClass.isInstance(originalItem)) {
                        return chain.proceed();
                    }

                    Object fixedItem;
                    try {
                        Object fragment = observerOwnerField.get(observer);
                        if (!railFragmentClass.isInstance(fragment)) {
                            return chain.proceed();
                        }
                        Object railViewModel = railViewModelField.get(fragment);
                        Object role = dockFeed == TrustPolicy.PHONE_DOCK_FEED
                                ? phoneRole
                                : recentRole;
                        fixedItem = createHotseatItem.invoke(
                                null,
                                railViewModel,
                                new ComponentName(
                                        shortcutPackage,
                                        TrustPolicy.VOLUME_SHORTCUT_SERVICE),
                                role,
                                false,
                                false,
                                false,
                                28);
                    } catch (Throwable error) {
                        if (loggedDockFailures.add(dockFeed)) {
                            reportError(
                                    "Unable to build fixed dock item for feed " + dockFeed,
                                    error);
                        }
                        return chain.proceed();
                    }

                    if (loggedDockFeeds.add(dockFeed)) {
                        report(
                                Log.INFO,
                                "Fixed dock feed " + dockFeed + " to " + shortcutPackage);
                    }
                    return chain.proceed(new Object[]{fixedItem});
                });
    }

    private void installDirectDockClickHook(ClassLoader gearheadClassLoader)
            throws ReflectiveOperationException {
        Class<?> clickListenerClass = Class.forName(
                GEARHEAD_DOCK_CLICK_LISTENER,
                false,
                gearheadClassLoader);
        Class<?> hotseatItemClass = Class.forName(
                GEARHEAD_HOTSEAT_ITEM,
                false,
                gearheadClassLoader);
        Field listenerItemField = clickListenerClass.getDeclaredField("a");
        listenerItemField.setAccessible(true);
        Field itemComponentField = hotseatItemClass.getDeclaredField("b");
        itemComponentField.setAccessible(true);
        Method onClick = clickListenerClass.getDeclaredMethod("onClick", View.class);
        onClick.setAccessible(true);

        hook(onClick)
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .intercept(chain -> {
                    Object item = listenerItemField.get(chain.getThisObject());
                    if (!hotseatItemClass.isInstance(item)) {
                        return chain.proceed();
                    }
                    Object componentValue = itemComponentField.get(item);
                    if (!(componentValue instanceof ComponentName)) {
                        return chain.proceed();
                    }
                    String shortcutPackage = ((ComponentName) componentValue).getPackageName();
                    String providerMethod = TrustPolicy.providerMethodForShortcutPackage(
                            shortcutPackage);
                    if (providerMethod == null) {
                        return chain.proceed();
                    }

                    Object clickedView = chain.getArg(0);
                    if (!(clickedView instanceof View)) {
                        return chain.proceed();
                    }
                    Context context = ((View) clickedView).getContext();
                    Context appContext = context.getApplicationContext();
                    if (appContext == null) {
                        appContext = context;
                    }
                    Uri commandUri = Uri.parse(
                            "content://" + TrustPolicy.VOLUME_SHORTCUT_PROVIDER_AUTHORITY);
                    try {
                        // Make the short Binder enqueue while Android Auto is handling the tap.
                        // Deferring this call to a Gearhead worker can be frozen while the phone is
                        // locked. The provider only validates and sends a foreground broadcast;
                        // AAARP's receiver keeps the root command off this UI thread.
                        callShortcutProvider(
                                appContext,
                                commandUri,
                                providerMethod,
                                shortcutPackage);
                    } catch (Throwable error) {
                        if (loggedDirectClickFailures.add(shortcutPackage)) {
                            reportError(
                                    "Unable to enqueue direct dock callback for " + shortcutPackage,
                                    error);
                        }
                    }

                    // Suppress nkn.f.invoke(), the only operation that launches the helper app.
                    return null;
                });
    }

    private void callShortcutProvider(
            Context context,
            Uri commandUri,
            String providerMethod,
            String shortcutPackage
    ) {
        try {
            Bundle result = context.getContentResolver().call(
                    commandUri,
                    providerMethod,
                    null,
                    null);
            if (result == null || !result.getBoolean("queued", false)) {
                throw new IllegalStateException("Shortcut provider did not queue command");
            }
            if (loggedDirectClicks.add(shortcutPackage)) {
                report(Log.INFO, "Direct dock volume callback active for " + shortcutPackage);
            }
        } catch (Throwable error) {
            if (loggedDirectClickFailures.add(shortcutPackage)) {
                reportError(
                        "Direct dock volume callback failed for " + shortcutPackage,
                        error);
            }
        }
    }

    private void report(int priority, String message) {
        Log.println(priority, TAG, message);
        log(priority, TAG, message);
    }

    private void reportError(String message, Throwable error) {
        Log.e(TAG, message, error);
        log(Log.ERROR, TAG, message, error);
    }
}
