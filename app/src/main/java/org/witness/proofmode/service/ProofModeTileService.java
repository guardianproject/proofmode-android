package org.witness.proofmode.service;

import android.annotation.TargetApi;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.preference.PreferenceManager;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

import org.witness.proofmode.MainActivity;
import org.witness.proofmode.R;

/**
 * Created by n8fr8 on 2/23/17.
 */
@TargetApi(Build.VERSION_CODES.N)
public class ProofModeTileService extends TileService {

    private SharedPreferences mPrefs;

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

        changeTileState(mPrefs.getBoolean("doProof",true)?Tile.STATE_ACTIVE:Tile.STATE_INACTIVE);
        
    }

    @Override
    public void onClick() {

        if (!isLocked()) {
            updateTile();
        } else {
            unlockAndRun(new Runnable() {
                @Override
                public void run() {
                    updateTile();
                }
            });
        }
    }

    @Override
    public void onStartListening() {
        super.onStartListening();
        changeTileState(getQsTile().getState());
    }


    private void updateTile() {
        if (Tile.STATE_ACTIVE == getQsTile().getState()) {
            changeTileState(Tile.STATE_INACTIVE);
            mPrefs.edit().putBoolean("doProof",false).commit();

        } else if (Tile.STATE_INACTIVE == getQsTile().getState()) {
            changeTileState(Tile.STATE_ACTIVE);
            mPrefs.edit().putBoolean("doProof",true).commit();
        }
    }

    private void changeTileState(int newState) {
        getQsTile().setIcon(Icon.createWithResource(this, newState == Tile.STATE_INACTIVE ? R.drawable.proofmodegrey : R.drawable.proofmodewhite));
        getQsTile().setState(newState);
        getQsTile().updateTile();
    }



}