package fi.nyja.ventapp;

import android.os.Parcel;
import android.os.Parcelable;

/**
 * One drawing. Each entry becomes one new sheet.
 * The workbook is chosen once for the whole run, not per row.
 */
public final class DrawingEntry implements Parcelable {
    public static final String KIND_PDF = "pdf";
    public static final String KIND_IMAGE = "image";
    public static final String KIND_PHOTO = "photo";

    public final String name;
    public final String uri;
    public final String kind;
    public final String photoPath;

    public DrawingEntry(String name, String uri, String kind, String photoPath) {
        this.name = name;
        this.uri = uri;
        this.kind = kind;
        this.photoPath = photoPath;
    }

    private DrawingEntry(Parcel in) {
        name = in.readString();
        uri = in.readString();
        kind = in.readString();
        photoPath = in.readString();
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(name);
        dest.writeString(uri);
        dest.writeString(kind);
        dest.writeString(photoPath);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    public static final Creator<DrawingEntry> CREATOR = new Creator<DrawingEntry>() {
        @Override
        public DrawingEntry createFromParcel(Parcel in) {
            return new DrawingEntry(in);
        }

        @Override
        public DrawingEntry[] newArray(int size) {
            return new DrawingEntry[size];
        }
    };
}
