package com.geely.lib.oneosapi.mediacenter;

import com.geely.lib.oneosapi.mediacenter.listener.IMusicStateListener;

interface IMusicManager {
    // Transaction ID: 1
    void addMusicStateListener(int source, IMusicStateListener listener);
}
