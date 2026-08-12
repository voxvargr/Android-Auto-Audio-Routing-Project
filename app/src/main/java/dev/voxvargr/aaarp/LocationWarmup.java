package dev.voxvargr.aaarp;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.SystemClock;

final class LocationWarmup {
    private static final long WARMUP_TIMEOUT_MS = 45000L;
    private static final long FRESH_FIX_MAX_AGE_MS = 30000L;

    private final Context context;
    private final Handler handler;
    private final Logger logger;
    private final Runnable timeoutRunnable = new Runnable() {
        @Override
        public void run() {
            finish("gps warmup timed out after " + WARMUP_TIMEOUT_MS + "ms");
        }
    };

    private LocationManager locationManager;
    private LocationListener listener;
    private boolean active;
    private int staleFixCount;
    private Runnable completion;

    LocationWarmup(Context context, Handler handler, Logger logger) {
        this.context = context.getApplicationContext();
        this.handler = handler;
        this.logger = logger;
    }

    static boolean hasPreciseLocationPermission(Context context) {
        return context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    static boolean hasBackgroundLocationPermission(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return true;
        }
        return context.checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    static String permissionSummary(Context context) {
        if (!hasPreciseLocationPermission(context)) {
            return "precise location missing";
        }
        if (!hasBackgroundLocationPermission(context)) {
            return "precise only; allow all the time for phone-in-pocket startup";
        }
        return "ready";
    }

    @SuppressLint("MissingPermission")
    boolean start(String reason, Runnable completion) {
        if (active) {
            log("gps warmup already active");
            return true;
        }
        if (!hasPreciseLocationPermission(context)) {
            log("gps warmup skipped: precise location permission is not granted");
            return false;
        }

        LocationManager manager = context.getSystemService(LocationManager.class);
        if (manager == null) {
            log("gps warmup skipped: location service is unavailable");
            return false;
        }
        if (!locationEnabled(manager)) {
            log("gps warmup skipped: device location is off");
            return false;
        }
        if (!gpsProviderEnabled(manager)) {
            log("gps warmup skipped: GPS provider is off");
            return false;
        }

        locationManager = manager;
        active = true;
        staleFixCount = 0;
        this.completion = completion;
        listener = new LocationListener() {
            @Override
            public void onLocationChanged(Location location) {
                handleLocation(location);
            }

            @SuppressWarnings("deprecation")
            @Override
            public void onStatusChanged(String provider, int status, Bundle extras) {
            }

            @Override
            public void onProviderEnabled(String provider) {
            }

            @Override
            public void onProviderDisabled(String provider) {
                if (LocationManager.GPS_PROVIDER.equals(provider)) {
                    finish("gps warmup stopped: GPS provider was disabled");
                }
            }
        };

        try {
            Location last = manager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
            manager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    0L,
                    0f,
                    listener,
                    handler.getLooper()
            );
            handler.postDelayed(timeoutRunnable, WARMUP_TIMEOUT_MS);
            log("gps warmup started reason=" + clean(reason)
                    + " permission=" + permissionSummary(context)
                    + " lastGps=" + locationSummary(last));
            return true;
        } catch (SecurityException e) {
            resetInactive();
            log("gps warmup blocked by Android permissions: " + e.getMessage());
            return false;
        } catch (RuntimeException e) {
            resetInactive();
            log("gps warmup failed to start: " + e.getMessage());
            return false;
        }
    }

    void stop(String reason) {
        if (!active) {
            return;
        }
        finish("gps warmup stopped: " + clean(reason));
    }

    private void handleLocation(Location location) {
        long ageMs = locationAgeMs(location);
        if (ageMs <= FRESH_FIX_MAX_AGE_MS) {
            finish("gps warmup got fresh fix " + locationSummary(location));
            return;
        }
        staleFixCount++;
        if (staleFixCount <= 3) {
            log("gps warmup ignored stale fix " + locationSummary(location));
        }
    }

    private void finish(String message) {
        if (!active) {
            return;
        }
        LocationManager manager = locationManager;
        LocationListener oldListener = listener;
        Runnable done = completion;
        resetInactive();
        if (manager != null && oldListener != null) {
            try {
                manager.removeUpdates(oldListener);
            } catch (RuntimeException e) {
                log("gps warmup listener cleanup failed: " + e.getMessage());
            }
        }
        log(message);
        if (done != null) {
            done.run();
        }
    }

    private void resetInactive() {
        active = false;
        handler.removeCallbacks(timeoutRunnable);
        locationManager = null;
        listener = null;
        completion = null;
        staleFixCount = 0;
    }

    private boolean locationEnabled(LocationManager manager) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            return manager.isLocationEnabled();
        }
        return gpsProviderEnabled(manager) || providerEnabled(manager, LocationManager.NETWORK_PROVIDER);
    }

    private boolean gpsProviderEnabled(LocationManager manager) {
        return providerEnabled(manager, LocationManager.GPS_PROVIDER);
    }

    private boolean providerEnabled(LocationManager manager, String provider) {
        try {
            return manager.isProviderEnabled(provider);
        } catch (RuntimeException e) {
            return false;
        }
    }

    private String locationSummary(Location location) {
        if (location == null) {
            return "none";
        }
        return "provider=" + location.getProvider()
                + " ageMs=" + locationAgeMs(location)
                + " accuracy=" + accuracySummary(location);
    }

    private long locationAgeMs(Location location) {
        if (location == null) {
            return -1L;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1
                && location.getElapsedRealtimeNanos() > 0L) {
            long ageNanos = SystemClock.elapsedRealtimeNanos() - location.getElapsedRealtimeNanos();
            return Math.max(0L, ageNanos / 1000000L);
        }
        return Math.max(0L, System.currentTimeMillis() - location.getTime());
    }

    private String accuracySummary(Location location) {
        if (location == null || !location.hasAccuracy()) {
            return "unknown";
        }
        return Math.round(location.getAccuracy()) + "m";
    }

    private String clean(String value) {
        if (value == null || value.length() == 0) {
            return "unknown";
        }
        return value.replace('\r', ' ').replace('\n', ' ');
    }

    private void log(String message) {
        if (logger != null) {
            logger.log(message);
        }
    }

    interface Logger {
        void log(String message);
    }
}
