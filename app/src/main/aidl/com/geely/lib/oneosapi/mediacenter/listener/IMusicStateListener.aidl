package com.geely.lib.oneosapi.mediacenter.listener;

import com.geely.lib.oneosapi.mediacenter.bean.MediaData;

interface IMusicStateListener {
    // Transaction ID: 1
    void onMediaDataChanged(int source, in MediaData mediaData);
    
    // Transaction ID: 2
    void onPlayPositionChanged(int source, long current, long total);
    
    // Transaction ID: 3
    void onPlayStateChanged(int source, int state);
    
    // Transaction ID: 4
    void onPlayListChanged(int source, in List<MediaData> list);
    
    // Transaction ID: 5
    void onFavorStateChanged(int source, in MediaData mediaData);
    
    // Transaction ID: 6
    void onLrcLoad(int source, String lrc, long time);
    
    // Transaction ID: 7
    void onPlayModeChange(int source, int mode);
}
