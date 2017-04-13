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
    private Tile mTile;

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

        updateTileState ();
    }

    private void updateTileState ()
    {
        isActive = mPrefs.getBoolean("doProof",false);

        mTile = getQsTile();
        mTile.setState(isActive? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        mTile.updateTile();

    }

    @Override
    public void onClick() {
        super.onClick();

        isActive = !isActive;
        mPrefs.edit().putBoolean("doProof",isActive).commit();
        updateTileState ();

    }


}