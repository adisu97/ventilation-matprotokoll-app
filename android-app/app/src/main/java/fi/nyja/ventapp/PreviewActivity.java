package fi.nyja.ventapp;

import android.app.Activity;
import android.content.ClipData;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.pdf.PdfRenderer;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;

/** Full-screen preview. Falls back to another app when the file cannot be drawn here. */
public class PreviewActivity extends Activity {
    public static final String EXTRA_URI = "uri";
    public static final String EXTRA_KIND = "kind";
    public static final String EXTRA_PATH = "path";
    public static final String EXTRA_NAME = "name";

    private ZoomImageView image;
    private LinearLayout pageBar;
    private TextView pageLabel;
    private Button prevPage;
    private Button nextPage;
    private ParcelFileDescriptor descriptor;
    private PdfRenderer renderer;
    private int pageIndex;
    private int pageCount;
    private boolean pdf;
    private Uri uri;
    private String mime = "application/octet-stream";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_preview);
        SystemBarInsets.apply(this, R.id.previewRoot);
        image = findViewById(R.id.previewImage);
        pageBar = findViewById(R.id.pageBar);
        pageLabel = findViewById(R.id.pageLabel);
        prevPage = findViewById(R.id.prevPage);
        nextPage = findViewById(R.id.nextPage);
        TextView title = findViewById(R.id.previewTitle);
        String name = getIntent().getStringExtra(EXTRA_NAME);
        title.setText(name == null ? "" : name);
        findViewById(R.id.closePreview).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        prevPage.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showPage(pageIndex - 1);
            }
        });
        nextPage.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showPage(pageIndex + 1);
            }
        });
        String raw = getIntent().getStringExtra(EXTRA_URI);
        uri = raw == null ? null : Uri.parse(raw);
        String kind = getIntent().getStringExtra(EXTRA_KIND);
        String path = getIntent().getStringExtra(EXTRA_PATH);
        if (savedInstanceState != null) {
            pageIndex = savedInstanceState.getInt("page", 0);
        }
        try {
            open(kind, path);
        } catch (Exception error) {
            giveUp();
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt("page", pageIndex);
    }

    @Override
    protected void onDestroy() {
        closePdf();
        super.onDestroy();
    }

    private void open(String kind, String path) throws Exception {
        boolean photo = DrawingEntry.KIND_PHOTO.equals(kind);
        boolean picture = photo || DrawingEntry.KIND_IMAGE.equals(kind);
        if (picture) {
            mime = "image/*";
            Bitmap bitmap = path != null && new File(path).isFile()
                    ? decodeFile(path)
                    : decodeUri(uri);
            if (bitmap == null) {
                throw new IllegalStateException("empty");
            }
            image.setImageBitmap(bitmap);
            pageBar.setVisibility(View.GONE);
            return;
        }
        mime = "application/pdf";
        pdf = true;
        descriptor = openPdf(path);
        renderer = new PdfRenderer(descriptor);
        pageCount = renderer.getPageCount();
        if (pageCount < 1) {
            throw new IllegalStateException("empty pdf");
        }
        pageBar.setVisibility(View.VISIBLE);
        if (pageIndex >= pageCount) {
            pageIndex = 0;
        }
        showPage(pageIndex);
    }

    private void showPage(int index) {
        if (!pdf || renderer == null || index < 0 || index >= pageCount) {
            return;
        }
        pageIndex = index;
        PdfRenderer.Page page = renderer.openPage(index);
        try {
            int width = Math.max(1, page.getWidth());
            int height = Math.max(1, page.getHeight());
            float scale = 1400f / Math.max(width, height);
            int bitmapWidth = Math.max(1, Math.round(width * scale));
            int bitmapHeight = Math.max(1, Math.round(height * scale));
            Bitmap bitmap = Bitmap.createBitmap(bitmapWidth, bitmapHeight, Bitmap.Config.ARGB_8888);
            bitmap.eraseColor(Color.WHITE);
            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
            image.setImageBitmap(bitmap);
        } finally {
            page.close();
        }
        pageLabel.setText(getString(R.string.page_label, index + 1, pageCount));
        prevPage.setEnabled(index > 0);
        nextPage.setEnabled(index + 1 < pageCount);
    }

    private void giveUp() {
        if (!openInOtherApp()) {
            Toast.makeText(this, R.string.no_viewer, Toast.LENGTH_LONG).show();
        }
        finish();
    }

    private boolean openInOtherApp() {
        if (uri == null) {
            return false;
        }
        Intent view = new Intent(Intent.ACTION_VIEW);
        view.setDataAndType(uri, mime);
        view.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        view.setClipData(ClipData.newRawUri("fil", uri));
        if (view.resolveActivity(getPackageManager()) == null) {
            return false;
        }
        Intent chooser = Intent.createChooser(view, getString(R.string.view_file));
        chooser.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(chooser);
        return true;
    }

    private ParcelFileDescriptor openPdf(String path) throws Exception {
        if (path != null && new File(path).isFile()) {
            return ParcelFileDescriptor.open(new File(path), ParcelFileDescriptor.MODE_READ_ONLY);
        }
        if (uri == null) {
            throw new IllegalStateException("missing uri");
        }
        ParcelFileDescriptor fd = getContentResolver().openFileDescriptor(uri, "r");
        if (fd == null) {
            throw new IllegalStateException("unreadable");
        }
        return fd;
    }

    private Bitmap decodeFile(String path) throws Exception {
        FileInputStream in = new FileInputStream(path);
        try {
            return decode(readAll(in));
        } finally {
            in.close();
        }
    }

    private Bitmap decodeUri(Uri source) throws Exception {
        if (source == null) {
            return null;
        }
        InputStream in = getContentResolver().openInputStream(source);
        if (in == null) {
            return null;
        }
        try {
            return decode(readAll(in));
        } finally {
            in.close();
        }
    }

    private static Bitmap decode(byte[] bytes) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
        int sample = 1;
        while (bounds.outWidth / sample > 2000 || bounds.outHeight / sample > 2000) {
            sample *= 2;
        }
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = Math.max(1, sample);
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
    }

    private static byte[] readAll(InputStream in) throws Exception {
        java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
        byte[] tmp = new byte[8192];
        int n;
        while ((n = in.read(tmp)) > 0) {
            buf.write(tmp, 0, n);
        }
        return buf.toByteArray();
    }

    private void closePdf() {
        if (renderer != null) {
            renderer.close();
            renderer = null;
        }
        if (descriptor != null) {
            try {
                descriptor.close();
            } catch (Exception ignored) {
                // Already closed.
            }
            descriptor = null;
        }
    }
}
