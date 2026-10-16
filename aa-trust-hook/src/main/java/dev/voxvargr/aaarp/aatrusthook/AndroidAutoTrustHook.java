package dev.voxvargr.aaarp.aatrusthook;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.InstallSourceInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
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
 * failure leaves the platform result unchanged. Named hotseat views provide an adaptive path,
 * with structurally checked profiles for Android Auto 17.3 and 17.4 as a fallback.</p>
 */
public final class AndroidAutoTrustHook extends XposedModule {
    private static final String TAG = "AAARP-TrustHook";
    private static final String PLAY_STORE_PACKAGE = "com.android.vending";
    private static final String APPLICATION_PACKAGE_MANAGER =
            "android.app.ApplicationPackageManager";
    private static final String RAIL_HOTSEAT_ITEM_VIEW =
            "com.google.android.apps.auto.components.system.facetbar.hotseat.RailHotseatItemView";
    private static final String LARGE_ICON_RESOURCE = "large_icon";
    // Android Auto's obfuscated classes live in the default package, despite JADX's synthetic
    // "defpackage" directory. Validate their shapes before installing a matching profile.
    private static final DockProfile[] KNOWN_DOCK_PROFILES = {
            new DockProfile("17.4", "mle", "cE", "nhe", "nhs", "nkp", "nhn", "nkv", false),
            new DockProfile("17.3", "mkx", "eB", "nhc", "nhr", "nkn", "nhl", "nkt", true)
    };

    private final AtomicBoolean installAttempted = new AtomicBoolean();
    private final Map<InstallSourceInfo, String> markedInstallSources =
            Collections.synchronizedMap(new WeakHashMap<>());
    private final Set<String> loggedSpoofs = ConcurrentHashMap.newKeySet();
    private final Set<Integer> loggedDockFeeds = ConcurrentHashMap.newKeySet();
    private final Set<Integer> loggedDockFailures = ConcurrentHashMap.newKeySet();
    private final Set<String> loggedAdaptiveSlots = ConcurrentHashMap.newKeySet();
    private final Set<String> loggedAdaptiveFailures = ConcurrentHashMap.newKeySet();
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
            installAdaptiveDockViewHook(param.getClassLoader());
            report(Log.INFO, "Self-adapting named-view Volume+ / Volume- dock hook active");
        } catch (Throwable error) {
            reportError("Self-adapting dock hook inactive", error);
        }

        DockProfile activeProfile = null;
        try {
            activeProfile = installDirectDockClickHook(param.getClassLoader());
            report(Log.INFO,
                    "Gearhead " + activeProfile.version + " no-navigation dock click hook active");
        } catch (Throwable error) {
            reportError("Known direct dock click profiles inactive", error);
        }

        // Only substitute the model once its click path is known to keep the car screen open.
        if (activeProfile != null) {
            try {
                installFixedDockHook(param.getClassLoader(), activeProfile);
                report(Log.INFO,
                        "Gearhead " + activeProfile.version
                                + " fixed Volume+ / Volume- dock hook active");
            } catch (Throwable error) {
                reportError("Known fixed-dock profile inactive", error);
            }
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

    private void installAdaptiveDockViewHook(ClassLoader gearheadClassLoader)
            throws ReflectiveOperationException {
        Class<?> hotseatViewClass = Class.forName(
                RAIL_HOTSEAT_ITEM_VIEW,
                false,
                gearheadClassLoader);
        Method bindMethod = findHotseatBindMethod(hotseatViewClass);
        bindMethod.setAccessible(true);
        hook(bindMethod)
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .intercept(chain -> {
                    Object result = chain.proceed();
                    Object target = chain.getThisObject();
                    if (target instanceof View) {
                        applyAdaptiveDockShortcut((View) target);
                    }
                    return result;
                });
    }

    private Method findHotseatBindMethod(Class<?> hotseatViewClass)
            throws NoSuchMethodException {
        Method match = null;
        for (Method method : hotseatViewClass.getDeclaredMethods()) {
            Class<?>[] parameterTypes = method.getParameterTypes();
            if (method.getReturnType() == void.class
                    && parameterTypes.length == 1
                    && declaresComponentNameField(parameterTypes[0])) {
                if (match != null) {
                    throw new NoSuchMethodException("Ambiguous RailHotseatItemView bind method");
                }
                match = method;
            }
        }
        if (match == null) {
            throw new NoSuchMethodException("RailHotseatItemView bind method not found");
        }
        return match;
    }

    private boolean declaresComponentNameField(Class<?> candidate) {
        for (Field field : candidate.getDeclaredFields()) {
            if (field.getType() == ComponentName.class) {
                return true;
            }
        }
        return false;
    }

    private void applyAdaptiveDockShortcut(View hotseatView) {
        try {
            String slot = hotseatView.getResources().getResourceEntryName(hotseatView.getId());
            String shortcutPackage = TrustPolicy.shortcutPackageForDockSlot(slot);
            if (shortcutPackage == null) {
                return;
            }
            String label = TrustPolicy.VOLUME_UP_PACKAGE.equals(shortcutPackage)
                    ? "Volume up"
                    : "Volume down";
            try {
                Context context = hotseatView.getContext();
                int iconId = context.getResources().getIdentifier(
                        LARGE_ICON_RESOURCE,
                        "id",
                        TrustPolicy.GEARHEAD_PACKAGE);
                View iconCandidate = iconId == 0 ? null : hotseatView.findViewById(iconId);
                ImageView iconView = iconCandidate instanceof ImageView
                        ? (ImageView) iconCandidate
                        : findClickableImageView(hotseatView);
                if (iconView == null) {
                    throw new IllegalStateException("large_icon view not found");
                }
                Drawable shortcutIcon = context.getPackageManager()
                        .getApplicationIcon(shortcutPackage);
                iconView.setImageTintList(null);
                iconView.setImageDrawable(shortcutIcon);
                iconView.setContentDescription(label);
                iconView.setVisibility(View.VISIBLE);
                iconView.setOnClickListener(clickedView -> {
                    Context clickContext = clickedView.getContext();
                    Context appContext = clickContext.getApplicationContext();
                    callShortcutProvider(
                            appContext == null ? clickContext : appContext,
                            Uri.parse("content://" + TrustPolicy.VOLUME_SHORTCUT_PROVIDER_AUTHORITY),
                            TrustPolicy.providerMethodForShortcutPackage(shortcutPackage),
                            shortcutPackage);
                });
                if (loggedAdaptiveSlots.add(slot)) {
                    report(Log.INFO, "Self-adapted " + slot + " to " + shortcutPackage);
                }
            } catch (PackageManager.NameNotFoundException error) {
                reportAdaptiveFailure(slot, error);
            } catch (Throwable error) {
                reportAdaptiveFailure(slot, error);
            }
        } catch (Throwable ignored) {
            // Views without the expected resource ID are outside this adaptive hook's scope.
        }
    }

    private ImageView findClickableImageView(View view) {
        if (view instanceof ImageView && view.hasOnClickListeners()) {
            return (ImageView) view;
        }
        if (!(view instanceof ViewGroup)) {
            return null;
        }
        ViewGroup group = (ViewGroup) view;
        for (int index = 0; index < group.getChildCount(); index++) {
            ImageView match = findClickableImageView(group.getChildAt(index));
            if (match != null) {
                return match;
            }
        }
        return null;
    }

    private void reportAdaptiveFailure(String slot, Throwable error) {
        if (loggedAdaptiveFailures.add(slot)) {
            reportError("Unable to self-adapt " + slot, error);
        }
    }

    private DockProfile installDirectDockClickHook(ClassLoader gearheadClassLoader)
            throws ReflectiveOperationException {
        ReflectiveOperationException lastFailure = null;
        for (DockProfile profile : KNOWN_DOCK_PROFILES) {
            try {
                installDirectDockClickHook(gearheadClassLoader, profile);
                return profile;
            } catch (ReflectiveOperationException error) {
                lastFailure = error;
            }
        }
        throw new ReflectiveOperationException(
                "No structurally valid Gearhead click profile",
                lastFailure);
    }

    private void installFixedDockHook(ClassLoader gearheadClassLoader, DockProfile profile)
            throws ReflectiveOperationException {
        Class<?> dockObserverClass = Class.forName(
                profile.observerClass,
                false,
                gearheadClassLoader);
        Class<?> railFragmentClass = Class.forName(
                profile.railFragmentClass,
                false,
                gearheadClassLoader);
        Class<?> railViewModelClass = Class.forName(
                profile.railViewModelClass,
                false,
                gearheadClassLoader);
        Class<?> hotseatItemClass = Class.forName(
                profile.hotseatItemClass,
                false,
                gearheadClassLoader);
        Class<?> hotseatRoleClass = Class.forName(
                profile.hotseatRoleClass,
                false,
                gearheadClassLoader);

        Field dockFeedField = dockObserverClass.getDeclaredField("b");
        dockFeedField.setAccessible(true);
        Field observerOwnerField = dockObserverClass.getDeclaredField("a");
        observerOwnerField.setAccessible(true);
        Field railViewModelField = railFragmentClass.getDeclaredField("Q");
        railViewModelField.setAccessible(true);
        if (!hotseatRoleClass.isEnum()) {
            throw new NoSuchFieldException("Hotseat role is not an enum");
        }
        Object phoneRole = enumConstant(hotseatRoleClass, "PHONE");
        Object recentRole = enumConstant(hotseatRoleClass, "RECENT");

        Method createHotseatItem = profile.staticFactory
                ? railViewModelClass.getDeclaredMethod(
                        "k", railViewModelClass, ComponentName.class, hotseatRoleClass,
                        boolean.class, boolean.class, boolean.class, int.class)
                : railViewModelClass.getDeclaredMethod(
                        "k", ComponentName.class, hotseatRoleClass,
                        boolean.class, boolean.class, boolean.class);
        createHotseatItem.setAccessible(true);
        if (createHotseatItem.getReturnType() != hotseatItemClass
                || Modifier.isStatic(createHotseatItem.getModifiers()) != profile.staticFactory) {
            throw new NoSuchMethodException("Hotseat factory validation failed");
        }
        Method bindDockFeed = dockObserverClass.getDeclaredMethod(profile.observerMethod, Object.class);
        bindDockFeed.setAccessible(true);
        if (bindDockFeed.getReturnType() != void.class
                || dockFeedField.getType() != int.class
                || !observerOwnerField.getType().isAssignableFrom(railFragmentClass)
                || railViewModelField.getType() != railViewModelClass) {
            throw new NoSuchMethodException("Dock observer structure validation failed");
        }

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
                        ComponentName component = new ComponentName(
                                shortcutPackage,
                                TrustPolicy.VOLUME_SHORTCUT_SERVICE);
                        fixedItem = profile.staticFactory
                                ? createHotseatItem.invoke(
                                        null, railViewModel, component, role,
                                        false, false, false, 28)
                                : createHotseatItem.invoke(
                                        railViewModel, component, role, false, false, false);
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

    private void installDirectDockClickHook(ClassLoader gearheadClassLoader, DockProfile profile)
            throws ReflectiveOperationException {
        Class<?> clickListenerClass = Class.forName(
                profile.clickListenerClass,
                false,
                gearheadClassLoader);
        Class<?> hotseatItemClass = Class.forName(
                profile.hotseatItemClass,
                false,
                gearheadClassLoader);
        Field listenerItemField = clickListenerClass.getDeclaredField("a");
        listenerItemField.setAccessible(true);
        Field itemComponentField = hotseatItemClass.getDeclaredField("b");
        itemComponentField.setAccessible(true);
        Method onClick = clickListenerClass.getDeclaredMethod("onClick", View.class);
        onClick.setAccessible(true);
        if (!View.OnClickListener.class.isAssignableFrom(clickListenerClass)
                || onClick.getReturnType() != void.class
                || !listenerItemField.getType().isAssignableFrom(hotseatItemClass)
                || itemComponentField.getType() != ComponentName.class) {
            throw new NoSuchMethodException("Dock click listener structure validation failed");
        }

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
                    ComponentName component = (ComponentName) componentValue;
                    if (!TrustPolicy.VOLUME_SHORTCUT_SERVICE.equals(component.getClassName())) {
                        return chain.proceed();
                    }
                    String shortcutPackage = component.getPackageName();
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

    @SuppressWarnings({"rawtypes", "unchecked"})
    private Object enumConstant(Class<?> enumClass, String name) {
        return Enum.valueOf((Class) enumClass, name);
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

    private static final class DockProfile {
        final String version;
        final String observerClass;
        final String observerMethod;
        final String railFragmentClass;
        final String railViewModelClass;
        final String hotseatItemClass;
        final String hotseatRoleClass;
        final String clickListenerClass;
        final boolean staticFactory;

        DockProfile(
                String version,
                String observerClass,
                String observerMethod,
                String railFragmentClass,
                String railViewModelClass,
                String hotseatItemClass,
                String hotseatRoleClass,
                String clickListenerClass,
                boolean staticFactory
        ) {
            this.version = version;
            this.observerClass = observerClass;
            this.observerMethod = observerMethod;
            this.railFragmentClass = railFragmentClass;
            this.railViewModelClass = railViewModelClass;
            this.hotseatItemClass = hotseatItemClass;
            this.hotseatRoleClass = hotseatRoleClass;
            this.clickListenerClass = clickListenerClass;
            this.staticFactory = staticFactory;
        }
    }
}
