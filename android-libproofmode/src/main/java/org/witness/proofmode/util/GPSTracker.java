package org.witness.proofmode.util;


import android.Manifest;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.SystemClock;
import android.provider.Settings;

import androidx.core.app.ActivityCompat;

import timber.log.Timber;

public final class GPSTracker implements LocationListener {

    private final Context mContext;

    // flag for GPS status
    public boolean isGPSEnabled = false;

    // flag for network status
    boolean isNetworkEnabled = false;

    // flag for GPS status
    boolean canGetLocation = false;

    Location location; // location
    double latitude; // latitude
    double longitude; // longitude

    // Minimum interval and displacement for location updates.
    private static final long MIN_TIME_BW_UPDATES_MS = 60_000;
    private static final float MIN_DISTANCE_CHANGE_FOR_UPDATES_M = 0f;

    // Declaring a Location Manager
    protected LocationManager locationManager;

    public GPSTracker(Context context) {
        mContext = context;

        locationManager = (LocationManager) mContext
                .getSystemService(Context.LOCATION_SERVICE);

        getLocation();

    }

    public void updateLocation () {

        boolean hasFine = ActivityCompat.checkSelfPermission(mContext, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        boolean hasCoarse = ActivityCompat.checkSelfPermission(mContext, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        if (!hasFine && !hasCoarse) {
            Timber.d("permission not granted for location check");
            return;
        }

        Timber.d("enabling location listener updates");

        isGPSEnabled = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER);
        isNetworkEnabled = locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER);

        // GPS_PROVIDER requires ACCESS_FINE_LOCATION; subscribing without it throws SecurityException.
        if (hasFine && isGPSEnabled) {
            locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER,
                    MIN_TIME_BW_UPDATES_MS, MIN_DISTANCE_CHANGE_FOR_UPDATES_M, this);
        }
        if (isNetworkEnabled) {
            locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER,
                    MIN_TIME_BW_UPDATES_MS, MIN_DISTANCE_CHANGE_FOR_UPDATES_M, this);
        }
    }

    public void stopUpdateLocation () {
        locationManager.removeUpdates(this);
    }
    /**
     * Function to get the user's current location
     *
     * @return
     */
    public Location getLocation() {

        boolean hasFine = ActivityCompat.checkSelfPermission(mContext, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        boolean hasCoarse = ActivityCompat.checkSelfPermission(mContext, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        if (!hasFine && !hasCoarse) {
            Timber.d("permission not granted for location check");
            return null;
        }

        isGPSEnabled = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER);
        isNetworkEnabled = locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER);

        if ((isGPSEnabled || isNetworkEnabled) && locationManager != null) {
            this.canGetLocation = true;

            // Start from whatever the listener has already cached, then merge in
            // any last-known fixes that are better. Don't unconditionally overwrite
            // — that would discard fresh callback-delivered fixes.
            Location best = location;

            if (isNetworkEnabled) {
                best = pickBetter(best, locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER));
            }
            // GPS_PROVIDER requires ACCESS_FINE_LOCATION.
            if (hasFine && isGPSEnabled) {
                best = pickBetter(best, locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER));
            }

            location = best;
            if (location != null) {
                latitude = location.getLatitude();
                longitude = location.getLongitude();
            }
        }

        return location;
    }

    // Picks the "better" of two locations, preferring a significantly fresher fix
    // and otherwise the more accurate one. Modeled on the standard Android
    // isBetterLocation pattern.
    private static Location pickBetter(Location a, Location b) {
        if (a == null) return b;
        if (b == null) return a;
        long nowNanos = SystemClock.elapsedRealtimeNanos();
        long ageA = nowNanos - a.getElapsedRealtimeNanos();
        long ageB = nowNanos - b.getElapsedRealtimeNanos();
        long twoMinNanos = 2L * 60L * 1_000_000_000L;
        if (ageB - ageA > twoMinNanos) return a;
        if (ageA - ageB > twoMinNanos) return b;
        if (a.hasAccuracy() && b.hasAccuracy()) {
            return a.getAccuracy() <= b.getAccuracy() ? a : b;
        }
        if (a.hasAccuracy()) return a;
        if (b.hasAccuracy()) return b;
        return ageA <= ageB ? a : b;
    }

    /**
     * Stop using GPS listener Calling this function will stop using GPS in your
     * app
     * */
    public void stopUsingGPS() throws SecurityException {
        if (locationManager != null) {
            locationManager.removeUpdates(GPSTracker.this);
        }
    }

    /**
     * Function to get latitude
     * */
    public double getLatitude() {
        if (location != null) {
            latitude = location.getLatitude();
        }

        // return latitude
        return latitude;
    }

    /**
     * Function to get longitude
     * */
    public double getLongitude() {
        if (location != null) {
            longitude = location.getLongitude();
        }

        // return longitude
        return longitude;
    }

    /**
     * Function to check GPS/wifi enabled
     *
     * @return boolean
     * */
    public boolean canGetLocation() {

        return canGetLocation;

    }

    /**
     * Function to show settings alert dialog On pressing Settings button will
     * lauch Settings Options
     * */
    public void showSettingsAlert() {
        AlertDialog.Builder alertDialog = new AlertDialog.Builder(mContext);

        // Setting Dialog Title
        alertDialog.setTitle("GPS is settings");

        // Setting Dialog Message
        alertDialog
                .setMessage("GPS is not enabled. Do you want to go to settings menu?");

        // On pressing Settings button
        alertDialog.setPositiveButton("Settings",
                new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int which) {
                        Intent intent = new Intent(
                                Settings.ACTION_LOCATION_SOURCE_SETTINGS);
                        mContext.startActivity(intent);
                    }
                });

        // on pressing cancel button
        alertDialog.setNegativeButton("Cancel",
                new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int which) {
                        dialog.cancel();
                    }
                });

        // Showing Alert Message
        alertDialog.show();
    }

    @Override
    public void onLocationChanged(Location location) {
        if (location == null) return;
        // Merge against any existing cached fix so a worse provider can't
        // displace a better one just because it fired more recently.
        this.location = pickBetter(this.location, location);
        if (this.location != null) {
            this.latitude = this.location.getLatitude();
            this.longitude = this.location.getLongitude();
        }
    }

    @Override
    public void onProviderDisabled(String provider) {
    }

    @Override
    public void onProviderEnabled(String provider) {
    }

    @Override
    public void onStatusChanged(String provider, int status, Bundle extras) {
    }

}
