package org.witness.proofmode.service;

import android.annotation.TargetApi;
import android.content.Intent;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

import org.witness.proofmode.MainActivity;

/**
 * Created by n8fr8 on 2/23/17.
 */
@TargetApi(24)
public class ProofModeTileService extends TileService {

    private SharedPreferences mPrefs;
    private boolean isActive = false;

    @Override
    public void onCreate() {
        super.onCreate();

        mPrefs = PreferenceManager.getDefaultSharedPreferences(this);

    }

    @Override
    public void onDestroy() {
        super.onDestroy();
    }

    @Override
    public void onTileAdded() {
        super.onTileAdded();
    }

    @Override
    public void onTileRemoved() {
        super.onTileRemoved();
    }

    @Override
    public void onStartListening() {
        super.onStartListening();

        isActive = mPrefs.getBoolean("doProof",false);

        Tile tile = getQsTile();
        tile.setState(isActive? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        tile.updateTile();

    }

    @Override
    public void onStopListening() {
        super.onStopListening();


    }

    @Override
    public void onClick() {
        super.onClick();

        //Start main activity
        //startActivity(new Intent(this, MainActivity.class));
        isActive = !isActive;
        mPrefs.edit().putBoolean("doProof",isActive).commit();

        Tile tile = getQsTile();
        tile.setState(isActive? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        tile.updateTile();
    }


}