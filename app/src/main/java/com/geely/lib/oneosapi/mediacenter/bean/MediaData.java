package com.geely.lib.oneosapi.mediacenter.bean;

import android.graphics.Bitmap;
import android.os.Parcel;
import android.os.Parcelable;

public class MediaData implements Parcelable {
    public String id;
    public String artist;
    public String name;
    public long duration;
    public String uri;
    public Bitmap albumCover;
    public String albumCoverUri;
    public String albumName;
    public int source;
    public boolean isFavored;
    public int mediaType;
    public boolean isLrcSupported;
    public boolean isReplaySupported;
    public boolean isFavorSupported;

    public MediaData() {
    }

    protected MediaData(Parcel in) {
        readFromParcel(in);
    }

    public static final Creator<MediaData> CREATOR = new Creator<MediaData>() {
        @Override
        public MediaData createFromParcel(Parcel in) {
            return new MediaData(in);
        }

        @Override
        public MediaData[] newArray(int size) {
            return new MediaData[size];
        }
    };

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(id);
        dest.writeString(artist);
        dest.writeString(name);
        dest.writeLong(duration);
        dest.writeString(uri);
        dest.writeParcelable(albumCover, flags);
        dest.writeString(albumCoverUri);
        dest.writeString(albumName);
        dest.writeInt(source);
        dest.writeInt(isFavored ? 1 : 0);
        dest.writeInt(mediaType);
        dest.writeInt(isLrcSupported ? 1 : 0);
        dest.writeInt(isReplaySupported ? 1 : 0);
        dest.writeInt(isFavorSupported ? 1 : 0);
    }

    public void readFromParcel(Parcel in) {
        id = in.readString();
        artist = in.readString();
        name = in.readString();
        duration = in.readLong();
        uri = in.readString();
        albumCover = in.readParcelable(Bitmap.class.getClassLoader());
        albumCoverUri = in.readString();
        albumName = in.readString();
        source = in.readInt();
        isFavored = in.readInt() != 0;
        mediaType = in.readInt();
        isLrcSupported = in.readInt() != 0;
        isReplaySupported = in.readInt() != 0;
        isFavorSupported = in.readInt() != 0;
    }

    @Override
    public String toString() {
        return "MediaData{" +
                "id='" + id + '\'' +
                ", artist='" + artist + '\'' +
                ", name='" + name + '\'' +
                ", duration=" + duration +
                ", uri='" + uri + '\'' +
                ", albumName='" + albumName + '\'' +
                ", source=" + source +
                '}';
    }
}
