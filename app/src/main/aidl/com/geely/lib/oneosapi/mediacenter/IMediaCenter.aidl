package com.geely.lib.oneosapi.mediacenter;

import com.geely.lib.oneosapi.mediacenter.IMusicManager;
import com.geely.lib.oneosapi.mediacenter.listener.ISourceStateListener;

interface IMediaCenter {
    // Transaction ID: 1
    int getCurrentAudioSource();
    
    // Transaction ID: 2
    int getCurrentAppSource();
    
    // Transaction ID: 3
    void requestAudioSource(int source, int app);
    
    // Transaction ID: 4
    void addSourceStateListener(ISourceStateListener listener);
    
    // Transaction ID: 5
    IMusicManager getMusicManager();
}
