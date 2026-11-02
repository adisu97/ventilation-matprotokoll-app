package fi.nyja.ventapp;

import android.content.ClipData;
import android.content.Intent;
import android.net.Uri;

import java.util.ArrayList;

/** Every content URI returned by ACTION_OPEN_DOCUMENT, including multi-select. */
public final class OpenDocumentUris {
    private OpenDocumentUris() {
    }

    /**
     * Reads all ClipData items. getData() is only the first URI on some pickers,
     * so it is added as well when it is not already in the clip.
     */
    public static ArrayList<Uri> collect(Intent data) {
        ArrayList<Uri> uris = new ArrayList<Uri>();
        ArrayList<String> seen = new ArrayList<String>();
        if (data == null) {
            return uris;
        }
        ClipData clip = data.getClipData();
        if (clip != null) {
            for (int i = 0; i < clip.getItemCount(); i++) {
                ClipData.Item item = clip.getItemAt(i);
                if (item != null) {
                    addUnique(uris, seen, item.getUri());
                }
            }
        }
        addUnique(uris, seen, data.getData());
        return uris;
    }

    private static void addUnique(ArrayList<Uri> uris, ArrayList<String> seen, Uri uri) {
        if (uri == null) {
            return;
        }
        String key = uri.toString();
        if (seen.contains(key)) {
            return;
        }
        seen.add(key);
        uris.add(uri);
    }
}
