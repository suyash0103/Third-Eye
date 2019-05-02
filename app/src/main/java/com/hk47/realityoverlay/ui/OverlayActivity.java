package com.hk47.realityoverlay.ui;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.Location;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v4.app.ActivityCompat;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.widget.FrameLayout;
import android.widget.Toast;

import com.android.volley.NetworkError;
import com.android.volley.NoConnectionError;
import com.android.volley.Request;
import com.android.volley.Response;
import com.android.volley.TimeoutError;
import com.android.volley.VolleyError;
import com.android.volley.toolbox.JsonObjectRequest;
import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.location.LocationListener;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationServices;
import com.hk47.realityoverlay.R;
import com.hk47.realityoverlay.data.Constants;
import com.hk47.realityoverlay.data.NearbyPlace;
import com.hk47.realityoverlay.utils.PlacesUtilities;
import com.hk47.realityoverlay.utils.SensorUtilities;
import com.hk47.realityoverlay.utils.VolleySingleton;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.lang.reflect.Array;
import java.util.ArrayList;

import static com.hk47.realityoverlay.data.Constants.LOCATION_UPDATE_DISPLACEMENT;
import static com.hk47.realityoverlay.data.Constants.LOCATION_UPDATE_INTERVAL;
import static com.hk47.realityoverlay.data.Constants.REFINE_SEARCH_INTENT;

public class OverlayActivity extends AppCompatActivity implements
        SensorEventListener,
        GoogleApiClient.ConnectionCallbacks,
        GoogleApiClient.OnConnectionFailedListener,
        LocationListener,
        OverlayDisplayView.OnPlaceSelectedListener {

    // Permissions
    private boolean mHaveAllPermissions;

    // Network
    private BroadcastReceiver mReceiver;
    private boolean mIsFirstConnect = true;

    // Sensors
    private SensorManager mSensorManager;
    private Sensor mAccelSensor;
    private Sensor mCompassSensor;
    private float[] mAcceleromterReading;
    private float[] mMagnetometerReading;

    // Location
    private Location mCurrentLocation;
    private GoogleApiClient mGoogleApiClient;
    private LocationRequest mLocationRequest;

    // Display
    private FrameLayout mAugmentedRealityContainer;
    private CameraDisplayView mCameraDisplayView;
    private OverlayDisplayView mOverlayDisplayView;

    private String mCurrentTypesString;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_overlay);

        checkPermissions();
        if (!mHaveAllPermissions) {
            Intent permissionsIntent = new Intent(this, PermissionsActivity.class);
            permissionsIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            startActivity(permissionsIntent);
        } else {
            initDisplay();
        }

        mSensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);

        buildGoogleApiClient();

        SharedPreferences sharedPreferences =
                getSharedPreferences(getString(R.string.preferences_types_key), MODE_PRIVATE);
        mCurrentTypesString = PlacesUtilities.getTypesString(this, sharedPreferences);

    }

    private void initDisplay() {
        mAugmentedRealityContainer = (FrameLayout) findViewById(R.id.augmented_reality_container);
        mCameraDisplayView = new CameraDisplayView(this, this);
        mAugmentedRealityContainer.addView(mCameraDisplayView);
        mOverlayDisplayView = new OverlayDisplayView(this);
        mAugmentedRealityContainer.addView(mOverlayDisplayView);
    }

    private void initReceiver() {
        if (mReceiver == null) {
            mReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    ConnectivityManager connectivityManager = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
                    NetworkInfo activeNetwork = connectivityManager.getActiveNetworkInfo();
                    if (activeNetwork != null && activeNetwork.isConnected()) {
                        if (mIsFirstConnect) {
                            if (mHaveAllPermissions) {
                                if (mCurrentLocation != null) {
                                    getNearbyPlaces(mCurrentLocation);
                                }
                            }
                            mIsFirstConnect = false;
                        }
                    } else {
                        mIsFirstConnect = true;
                    }
                }
            };
            registerReceiver(mReceiver, new IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION));
        }
    }

    private void checkPermissions() {
        if (checkCameraPermission() && checkLocationPermission()) {
            mHaveAllPermissions = true;
        } else {
            mHaveAllPermissions = false;
        }
    }

    private boolean checkCameraPermission() {
        return ((ActivityCompat.checkSelfPermission(this,
                Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED));
    }

    private boolean checkLocationPermission() {
        return ((ActivityCompat.checkSelfPermission(this,
                Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED));
    }

    // Retrieves a new list of nearby places when search preferences have changed
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (resultCode == Constants.SEARCH_RESULT_CODE) {
            mCurrentTypesString = data.getStringExtra(Constants.SEARCH_RETURN_INTENT);
            getNearbyPlaces(mCurrentLocation);
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        mGoogleApiClient.connect();
    }

    @Override
    protected void onResume() {
        super.onResume();

        initReceiver();
        registerSensors();
    }

    @Override
    protected void onPause() {
        super.onPause();

        if (mReceiver != null) {
            unregisterReceiver(mReceiver);
            mReceiver = null;
        }
        unregisterSensors();
    }

    @Override
    protected void onStop() {
        mGoogleApiClient.disconnect();
        super.onStop();
    }

    private void registerSensors() {
        mAccelSensor = mSensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        mCompassSensor = mSensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD);
        mSensorManager.registerListener(this, mAccelSensor, SensorManager.SENSOR_DELAY_NORMAL);
        mSensorManager.registerListener(this, mCompassSensor, SensorManager.SENSOR_DELAY_NORMAL);
    }

    private void unregisterSensors() {
        mSensorManager.unregisterListener(this, mAccelSensor);
        mSensorManager.unregisterListener(this, mCompassSensor);
    }

    // Updates the OverlayDisplayView when sensor data changes.
    @Override
    public void onSensorChanged(SensorEvent event) {

        switch (event.sensor.getType()) {
            case Sensor.TYPE_ACCELEROMETER:
                mAcceleromterReading =
                        SensorUtilities.filterSensors(event.values, mAcceleromterReading);
                break;
            case Sensor.TYPE_MAGNETIC_FIELD:
                mMagnetometerReading =
                        SensorUtilities.filterSensors(event.values, mMagnetometerReading);
                break;
        }

        float[] orientation =
                SensorUtilities.computeDeviceOrientation(mAcceleromterReading, mMagnetometerReading);
        if (orientation != null) {

            // Convert azimuth relative to magnetic north from radians to degrees
            float azimuth = (float) Math.toDegrees(orientation[0]);
            if (azimuth < 0) {
                azimuth += 360f;
            }

            // Convert pitch and roll from radians to degrees
            float pitch = (float) Math.toDegrees(orientation[1]);
            float roll = (float) Math.toDegrees(orientation[2]);

            if (mCurrentLocation != null) {
                mOverlayDisplayView.setHorizontalFOV(mCameraDisplayView.getHorizontalFOV());
                mOverlayDisplayView.setVerticalFOV(mCameraDisplayView.getVerticalFOV());
                mOverlayDisplayView.setAzimuth(azimuth);
                mOverlayDisplayView.setPitch(pitch);
                mOverlayDisplayView.setRoll(roll);
            }

            // Update the OverlayDisplayView to redraw when sensor data changes,
            // redrawing only when the camera is not pointing straight up or down
            if (pitch <= 75 && pitch >= -75) {
                mOverlayDisplayView.invalidate();
            }
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {

    }

    // Updates the OverlayDisplayView when the location changes.
    @Override
    public void onLocationChanged(Location currentLocation) {
        mCurrentLocation = currentLocation;

        getNearbyPlaces(currentLocation);
    }

    private void getLocation() {
        if (checkLocationPermission()) {
            mLocationRequest = LocationRequest.create();
            mLocationRequest.setPriority(LocationRequest.PRIORITY_HIGH_ACCURACY);
            mLocationRequest.setInterval(LOCATION_UPDATE_INTERVAL);
            mLocationRequest.setSmallestDisplacement(LOCATION_UPDATE_DISPLACEMENT);
            LocationServices.FusedLocationApi.requestLocationUpdates(
                    mGoogleApiClient, mLocationRequest, this);
        }
    }

    private void getNearbyPlaces(final Location currentLocation) {
        String userLocation = currentLocation.getLatitude() + "," + currentLocation.getLongitude();

        JsonObjectRequest jsonObjectRequest = new JsonObjectRequest(
                Request.Method.GET,
                PlacesUtilities.getPlacesUrlString(userLocation, mCurrentTypesString),
                null,
                new Response.Listener<JSONObject>() {
                    @Override
                    public void onResponse(JSONObject response) {

                        try {
                            SharedPreferences sharedPreferences = getSharedPreferences(getString(R.string.preferences_types_key), MODE_PRIVATE);;
                            boolean cafesAreActivated = sharedPreferences.getBoolean(
                                    OverlayActivity.this.getString(R.string.preferences_type_cafe), true);
                            if (cafesAreActivated) {
                                JSONArray results = response.getJSONArray("results");
                                JSONObject obj1 = results.getJSONObject(0);
                                JSONObject geometry = obj1.getJSONObject("geometry");
                                JSONObject location = geometry.getJSONObject("location");
                                String[] type = new String[4];
//                                obj1 = results.getJSONObject(2);
//                                geometry = obj1.getJSONObject("geometry");
                                location.put("lat", 13.006807);
                                location.put("lng", 74.796521);
                                obj1.put("name", "3rd Block NC");
                                obj1.put("place_id", "ChIJL98eQBpSozsRRRwWtAtndyM");
//                            String[] type = new String[4];
                                type[0] = "restaurant";
                                type[1] = "point_of_interest";
                                type[2] = "food";
                                type[3] = "establishment";
                                obj1.put("type", type);

                                obj1 = results.getJSONObject(1);
                                geometry = obj1.getJSONObject("geometry");
                                location = geometry.getJSONObject("location");
                                location.put("lat", 13.007914);
                                location.put("lng", 74.796272);
                                obj1.put("name", "7th Block NC");
                                obj1.put("place_id", "ChIJL_n5nhBSozsRMUFhnjwNtjg");
//                            String[] type = new String[4];
                                type[0] = "restaurant";
                                type[1] = "point_of_interest";
                                type[2] = "food";
                                type[3] = "establishment";
                                obj1.put("type", type);

                                obj1 = results.getJSONObject(2);
                                geometry = obj1.getJSONObject("geometry");
                                location = geometry.getJSONObject("location");
                                location.put("lat", 13.013729);
                                location.put("lng", 74.796305);
                                obj1.put("name", "Girls Block NC");
                                obj1.put("place_id", "ChIJD2rlYRFSozsRLX3e7UtezGI");
//                            String[] type = new String[4];
                                type[0] = "restaurant";
                                type[1] = "point_of_interest";
                                type[2] = "food";
                                type[3] = "establishment";
                                obj1.put("type", type);
                            }
                            boolean transitIsActivated = sharedPreferences.getBoolean(
                                    OverlayActivity.this.getString(R.string.preferences_type_transit), false);
                            if (transitIsActivated) {
                                JSONArray results = response.getJSONArray("results");
                                JSONObject obj1 = results.getJSONObject(0);
                                JSONObject geometry = obj1.getJSONObject("geometry");
                                JSONObject location = geometry.getJSONObject("location");
                                location.put("lat", 13.0064237);
                                location.put("lng", 74.7944927);
                                obj1.put("name", "MT-3 Bus Stop");
                                obj1.put("place_id", "ChIJU1dqjRpSozsRBJXLV4Ss9ww");
                                String[] type = new String[4];
                                type[0] = "bus_station";
                                type[1] = "transit_station";
                                type[2] = "point_of_interest";
                                type[3] = "establishment";
                                obj1.put("type", type);

                                obj1 = results.getJSONObject(1);
                                geometry = obj1.getJSONObject("geometry");
                                location = geometry.getJSONObject("location");
                                location.put("lat", 13.010728);
                                location.put("lng", 74.792433);
                                obj1.put("name", "LHC-C Bus stop");
                                obj1.put("place_id", "ChIJ7XrVKhBSozsR6WZncNE-PEA");
//                            String[] type = new String[4];
                                type[0] = "bus_station";
                                type[1] = "transit_station";
                                type[2] = "point_of_interest";
                                type[3] = "establishment";
                                obj1.put("type", type);

                            }
                            //                            type[4] = "bus_station";



//                            type = ["bus_station","transit_station","point_of_interest","establishment"];
//                            obj1.put("type", type);
                            Log.v("RESPONSES", response.toString());
//                            int len = results.length();
//
//                            String resp = "{\"geometry\":{\"location\":{\"lat\":13.0064237,\"lng\" : 74.7944927},\"viewport\":{\"northeast\":{\"lat\":12.9820206802915,\"lng\":74.80794058029151},\"southwest\":{\"lat\":12.9793227197085,\"lng\":74.80524261970851}}},\"icon\":\"https://maps.gstatic.com/mapfiles/place_api/icons/cafe-71.png\",\"id\":\"9949673ad65d8b1e66be2387c929416ee313f82b\",\"name\":\"MT3 Bus Stop\",\"opening_hours\":{\"open_now\":true},\"photos\":[{\"height\":2448,\"html_attributions\":[\"\\u003ca href=\\\"https://maps.google.com/maps/contrib/101999008965873948763/photos\\\"\\u003eSudarshan bhat\\u003c/a\\u003e\"],\"photo_reference\":\"CmRZAAAABp2HcOh28zNm9yfGV4ym-kIhaJAj3u7QdnVxeg4h6cf3vgPnHX5fQDUNaVsEtXJA0fBNQxQeLs7RjS6YI2WG_NO5z_ztCJXzq_Dj9_1B716npbw9dHFPUmxps4x8xVqtEhBDjCeonrad4z05BVyKXE3FGhSWaE4dc_KTbxGM1a-9FWJh7KG0vg\",\"width\":3264}],\"place_id\":\"ChIJU1dqjRpSozsRBJXLV4Ss9ww\",\"plus_code\":{\"compound_code\":\"XRJ4+7J Surathkal, Mangaluru, Karnataka, India\",\"global_code\":\"7J4PXRJ4+7J\"},\"price_level\":1,\"rating\":4,\"reference\":\"ChIJU1dqjRpSozsRBJXLV4Ss9ww\",\"scope\":\"GOOGLE\",\"types\":[\"bus_station\",\"transit_station\",\"point_of_interest\",\"establishment\"],\"user_ratings_total\":15,\"vicinity\":\"Kadambody Road, Hosabettu, Surathkal\"}";
//                            JSONObject json = new JSONObject(resp);
//                            Log.v("JSON", json.toString());
//                            results.put(len, )
                        } catch (JSONException e) {
                            e.printStackTrace();
                        }
                        ArrayList<NearbyPlace> nearbyPlaces =
                                PlacesUtilities.processPlacesJson(
                                        getApplicationContext(),
                                        currentLocation,
                                        response,
                                        mCurrentTypesString);

                        // Update the OverlayDisplayView when the user's location changes
                        mOverlayDisplayView.setNearbyPlaces(nearbyPlaces);



                    }
                },
                new Response.ErrorListener() {
                    @Override
                    public void onErrorResponse(VolleyError error) {

                        if (error instanceof NoConnectionError ||
                                error instanceof NetworkError ||
                                error instanceof TimeoutError) {
                            Toast.makeText(
                                    getApplicationContext(),
                                    getString(R.string.connection_unavailable),
                                    Toast.LENGTH_SHORT).show();
                        } else {
                            Toast.makeText(
                                    getApplicationContext(),
                                    getString(R.string.service_unavailable),
                                    Toast.LENGTH_SHORT).show();
                        }
                    }
                }
        );

        // Get nearby places from Google Places API
        VolleySingleton.getInstance(getApplicationContext()).addToRequestQueue(jsonObjectRequest);
    }

    @Override
    public void onConnected(@Nullable Bundle bundle) {
        getLocation();
    }

    @Override
    public void onConnectionSuspended(int i) {

    }

    @Override
    public void onConnectionFailed(@NonNull ConnectionResult connectionResult) {

    }

    protected synchronized void buildGoogleApiClient() {
        mGoogleApiClient = new GoogleApiClient.Builder(this)
                .addApi(LocationServices.API)
                .addConnectionCallbacks(this)
                .addOnConnectionFailedListener(this)
                .build();
    }

    @Override
    public void onPlaceSelected(String place_id, int icon_id) {
        Intent detailsIntent = new Intent(this, PlaceDetailsActivity.class);
        detailsIntent.putExtra(Constants.DETAILS_INTENT_PLACE_ID, place_id);
        detailsIntent.putExtra(Constants.DETAILS_INTENT_ICON_ID, icon_id);
        startActivity(detailsIntent);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        MenuInflater inflater = getMenuInflater();
        inflater.inflate(R.menu.menu, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.menu_refine_search:
                Intent searchIntent = new Intent(this, SearchActivity.class);
                searchIntent.putExtra(REFINE_SEARCH_INTENT, mCurrentTypesString);
                startActivityForResult(searchIntent, Constants.SEARCH_RESULT_CODE);
                return true;
            default:
                return super.onOptionsItemSelected(item);
        }
    }
}
