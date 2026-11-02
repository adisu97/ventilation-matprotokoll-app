package fi.nyja.ventapp;

import android.Manifest;
import android.app.Activity;
import android.content.ClipData;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity {
    private static final int REQ_PDF = 1;
    private static final int REQ_CAMERA = 2;
    private static final int REQ_CAMERA_PERMISSION = 3;
    private static final long PER_FILE_MS = 1000L;

    private final ArrayList<DrawingEntry> entries = new ArrayList<DrawingEntry>();
    private final PlaceholderExtraction extraction = new PlaceholderExtraction();

    private EditText excelName;
    private LinearLayout fileList;
    private TextView emptyList;
    private TextView percentView;
    private ProgressBar progressBar;
    private TextView statusView;
    private TextView etaView;
    private TextView formError;
    private Button startButton;
    private Button downloadButton;

    private int nextPhoto = 1;
    private boolean done;
    private int runTotal;
    private Uri pendingPhotoUri;
    private String pendingPhotoName;
    private String pendingPhotoPath;

    private final PlaceholderExtraction.Listener progressListener = new PlaceholderExtraction.Listener() {
        @Override
        public void onProgress(ProgressSnapshot snapshot) {
            showProgress(snapshot);
        }

        @Override
        public void onComplete(ProgressSnapshot snapshot) {
            done = true;
            startButton.setEnabled(true);
            downloadButton.setEnabled(true);
            showProgress(snapshot);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        excelName = findViewById(R.id.excelName);
        fileList = findViewById(R.id.fileList);
        emptyList = findViewById(R.id.emptyList);
        percentView = findViewById(R.id.percent);
        progressBar = findViewById(R.id.progress);
        statusView = findViewById(R.id.status);
        etaView = findViewById(R.id.eta);
        formError = findViewById(R.id.formError);
        startButton = findViewById(R.id.start);
        downloadButton = findViewById(R.id.download);

        findViewById(R.id.pickPdfs).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pickPdfs();
            }
        });
        findViewById(R.id.takePhoto).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                takePhoto();
            }
        });
        startButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                start();
            }
        });
        downloadButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Toast.makeText(MainActivity.this, R.string.download_later, Toast.LENGTH_LONG).show();
            }
        });

        if (savedInstanceState != null) {
            ArrayList<DrawingEntry> saved = savedInstanceState.getParcelableArrayList("entries");
            if (saved != null) {
                entries.addAll(saved);
            }
            nextPhoto = savedInstanceState.getInt("nextPhoto", 1);
            done = savedInstanceState.getBoolean("done", false);
            runTotal = savedInstanceState.getInt("runTotal", entries.size());
            pendingPhotoUri = savedInstanceState.getParcelable("pendingPhoto");
            pendingPhotoName = savedInstanceState.getString("pendingName");
            pendingPhotoPath = savedInstanceState.getString("pendingPath");
        }
        renderList();
        if (done && !entries.isEmpty()) {
            showProgress(ProgressSnapshot.fromElapsed((long) entries.size() * PER_FILE_MS, entries.size(), PER_FILE_MS));
            downloadButton.setEnabled(true);
        }
        if (savedInstanceState != null && savedInstanceState.getBoolean("running", false)) {
            long elapsed = savedInstanceState.getLong("elapsed", 0L);
            runTotal = savedInstanceState.getInt("runTotal", entries.size());
            if (runTotal > 0) {
                startButton.setEnabled(false);
                downloadButton.setEnabled(false);
                extraction.start(runTotal, PER_FILE_MS, elapsed, progressListener);
            }
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putParcelableArrayList("entries", entries);
        outState.putInt("nextPhoto", nextPhoto);
        outState.putBoolean("done", done);
        outState.putBoolean("running", extraction.isRunning());
        outState.putLong("elapsed", extraction.elapsedMs());
        outState.putInt("runTotal", runTotal);
        if (pendingPhotoUri != null) {
            outState.putParcelable("pendingPhoto", pendingPhotoUri);
        }
        outState.putString("pendingName", pendingPhotoName);
        outState.putString("pendingPath", pendingPhotoPath);
    }

    @Override
    protected void onDestroy() {
        extraction.cancel();
        super.onDestroy();
    }

    private void pickPdfs() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/pdf");
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(intent, REQ_PDF);
    }

    private void takePhoto() {
        if (Build.VERSION.SDK_INT >= 23
                && checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_CAMERA_PERMISSION);
            return;
        }
        launchCamera();
    }

    private void launchCamera() {
        File dir = new File(getFilesDir(), "photos");
        if (!dir.exists() && !dir.mkdirs()) {
            Toast.makeText(this, R.string.photo_failed, Toast.LENGTH_LONG).show();
            return;
        }
        File photo = new File(dir, "foto-" + System.currentTimeMillis() + ".jpg");
        pendingPhotoPath = photo.getAbsolutePath();
        pendingPhotoName = getString(R.string.photo_name, nextPhoto);
        pendingPhotoUri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", photo);

        Intent intent = new Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE);
        intent.putExtra(android.provider.MediaStore.EXTRA_OUTPUT, pendingPhotoUri);
        intent.setClipData(ClipData.newRawUri("photo", pendingPhotoUri));
        intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);
        List<ResolveInfo> cameras = getPackageManager().queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY);
        for (int i = 0; i < cameras.size(); i++) {
            String packageName = cameras.get(i).activityInfo.packageName;
            grantUriPermission(packageName, pendingPhotoUri,
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);
        }
        if (intent.resolveActivity(getPackageManager()) == null) {
            clearPendingPhoto(true);
            Toast.makeText(this, R.string.no_camera, Toast.LENGTH_LONG).show();
            return;
        }
        startActivityForResult(intent, REQ_CAMERA);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_PDF && resultCode == RESULT_OK && data != null) {
            addPickedPdfs(data);
        } else if (requestCode == REQ_CAMERA) {
            if (resultCode == RESULT_OK && pendingPhotoUri != null && photoFileHasBytes()) {
                entries.add(new DrawingEntry(
                        pendingPhotoName,
                        pendingPhotoUri.toString(),
                        DrawingEntry.KIND_PHOTO,
                        DrawingEntry.MODE_SHEET,
                        pendingPhotoPath));
                nextPhoto += 1;
                onDrawingsChanged();
                Toast.makeText(this, R.string.photo_added, Toast.LENGTH_SHORT).show();
                clearPendingPhoto(false);
            } else {
                clearPendingPhoto(true);
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQ_CAMERA_PERMISSION) {
            return;
        }
        if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            launchCamera();
        } else {
            Toast.makeText(this, R.string.camera_denied, Toast.LENGTH_LONG).show();
        }
    }

    private void addPickedPdfs(Intent data) {
        int before = entries.size();
        ClipData clip = data.getClipData();
        if (clip != null) {
            for (int i = 0; i < clip.getItemCount(); i++) {
                addPdf(clip.getItemAt(i).getUri());
            }
        } else if (data.getData() != null) {
            addPdf(data.getData());
        }
        if (entries.size() != before) {
            onDrawingsChanged();
        }
    }

    private void addPdf(Uri uri) {
        if (uri == null) {
            return;
        }
        try {
            getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (SecurityException ignored) {
            // Some pickers only grant temporary access. The name can still be shown.
        }
        entries.add(new DrawingEntry(
                displayName(uri),
                uri.toString(),
                DrawingEntry.KIND_PDF,
                DrawingEntry.MODE_SHEET,
                null));
    }

    private String displayName(Uri uri) {
        Cursor cursor = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null);
        if (cursor != null) {
            try {
                if (cursor.moveToFirst()) {
                    String name = cursor.getString(0);
                    if (name != null && name.trim().length() > 0) {
                        return name.trim();
                    }
                }
            } finally {
                cursor.close();
            }
        }
        String last = uri.getLastPathSegment();
        return last == null ? "ritning.pdf" : last;
    }

    private boolean photoFileHasBytes() {
        if (pendingPhotoPath == null) {
            return false;
        }
        File file = new File(pendingPhotoPath);
        return file.isFile() && file.length() > 0L;
    }

    private void clearPendingPhoto(boolean deleteFile) {
        if (deleteFile && pendingPhotoPath != null) {
            File file = new File(pendingPhotoPath);
            if (file.exists() && !file.delete()) {
                file.deleteOnExit();
            }
        }
        pendingPhotoUri = null;
        pendingPhotoName = null;
        pendingPhotoPath = null;
    }

    private void onDrawingsChanged() {
        extraction.cancel();
        done = false;
        startButton.setEnabled(true);
        downloadButton.setEnabled(false);
        formError.setText("");
        percentView.setText(R.string.percent_zero);
        progressBar.setProgress(0);
        statusView.setText(R.string.status_idle);
        etaView.setText(R.string.eta_idle);
        renderList();
    }

    private void renderList() {
        fileList.removeAllViews();
        emptyList.setVisibility(entries.isEmpty() ? View.VISIBLE : View.GONE);
        for (int i = 0; i < entries.size(); i++) {
            fileList.addView(createRow(entries.get(i)));
        }
    }

    private View createRow(final DrawingEntry entry) {
        View row = getLayoutInflater().inflate(R.layout.item_drawing, fileList, false);
        TextView name = row.findViewById(R.id.drawingName);
        name.setText(entry.name);
        RadioGroup group = row.findViewById(R.id.modeGroup);
        RadioButton sheet = row.findViewById(R.id.modeSheet);
        RadioButton file = row.findViewById(R.id.modeFile);
        if (DrawingEntry.MODE_FILE.equals(entry.mode)) {
            file.setChecked(true);
        } else {
            sheet.setChecked(true);
        }
        group.setOnCheckedChangeListener(new RadioGroup.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(RadioGroup radioGroup, int checkedId) {
                entry.mode = checkedId == R.id.modeFile ? DrawingEntry.MODE_FILE : DrawingEntry.MODE_SHEET;
                if (done || extraction.isRunning()) {
                    markNotDone();
                }
            }
        });
        row.findViewById(R.id.remove).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                entries.remove(entry);
                if (DrawingEntry.KIND_PHOTO.equals(entry.kind) && entry.photoPath != null) {
                    File photo = new File(entry.photoPath);
                    if (photo.exists() && !photo.delete()) {
                        photo.deleteOnExit();
                    }
                }
                onDrawingsChanged();
            }
        });
        return row;
    }

    private void start() {
        String name = excelName.getText().toString().trim();
        if (name.length() == 0) {
            excelName.setError(getString(R.string.name_required));
            excelName.requestFocus();
            formError.setText(R.string.name_required);
            return;
        }
        if (entries.isEmpty()) {
            formError.setText(R.string.drawings_required);
            return;
        }
        excelName.setError(null);
        formError.setText("");
        done = false;
        downloadButton.setEnabled(false);
        startButton.setEnabled(false);
        runTotal = entries.size();
        extraction.start(runTotal, PER_FILE_MS, 0L, progressListener);
    }

    private void markNotDone() {
        extraction.cancel();
        done = false;
        startButton.setEnabled(true);
        downloadButton.setEnabled(false);
        percentView.setText(R.string.percent_zero);
        progressBar.setProgress(0);
        statusView.setText(R.string.status_idle);
        etaView.setText(R.string.eta_idle);
    }

    private void showProgress(ProgressSnapshot snapshot) {
        percentView.setText(snapshot.percent + " %");
        progressBar.setProgress(snapshot.percent);
        statusView.setText(snapshot.statusText());
        etaView.setText(snapshot.remainingLabel());
    }
}
