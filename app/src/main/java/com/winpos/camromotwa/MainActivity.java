package com.winpos.camromotwa;

import android.Manifest;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.TextInputEditText;
import com.google.common.util.concurrent.ListenableFuture;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public class MainActivity extends AppCompatActivity {

    private static final long FRAME_INTERVAL_MS = 1000L;

    private final Handler frameHandler = new Handler(Looper.getMainLooper());
    private final FrameRelayClient frameRelayClient = new FrameRelayClient();
    private final AtomicBoolean uploadInFlight = new AtomicBoolean(false);
    private final AtomicBoolean downloadInFlight = new AtomicBoolean(false);
    private final String participantId = UUID.randomUUID().toString();

    private TextView statusText;
    private TextView remoteFeedText;
    private TextView remoteFeedSubtext;
    private TextView localFeedText;
    private View remotePlaceholder;
    private ImageView remoteImageView;
    private ImageView localSnapshotView;
    private PreviewView localPreviewView;
    private TextInputEditText nameInput;
    private TextInputEditText roomInput;
    private TextInputEditText serverInput;
    private MaterialButton connectButton;
    private MaterialButton permissionButton;

    private ActivityResultLauncher<String[]> permissionLauncher;
    private ExecutorService cameraExecutor;
    private ProcessCameraProvider cameraProvider;
    private boolean previewReady;
    private boolean sharingActive;

    private final Runnable frameLoop = new Runnable() {
        @Override
        public void run() {
            if (!sharingActive) {
                return;
            }

            captureAndSendFrame();
            pollRemoteFrame();
            frameHandler.postDelayed(this, FRAME_INTERVAL_MS);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);

        cameraExecutor = Executors.newSingleThreadExecutor();

        bindViews();
        setupPermissionLauncher();
        setupActions();
        serverInput.setText("http://10.0.2.2:8080");
        refreshUi();
    }

    @Override
    protected void onDestroy() {
        stopSharing();
        if (cameraProvider != null) {
            cameraProvider.unbindAll();
        }
        cameraExecutor.shutdown();
        super.onDestroy();
    }

    private void bindViews() {
        statusText = findViewById(R.id.statusText);
        remoteFeedText = findViewById(R.id.remoteFeedText);
        remoteFeedSubtext = findViewById(R.id.remoteFeedSubtext);
        localFeedText = findViewById(R.id.localFeedText);
        remotePlaceholder = findViewById(R.id.remotePlaceholder);
        remoteImageView = findViewById(R.id.remoteImageView);
        localSnapshotView = findViewById(R.id.localSnapshotView);
        localPreviewView = findViewById(R.id.localPreviewView);
        nameInput = findViewById(R.id.nameInput);
        roomInput = findViewById(R.id.roomInput);
        serverInput = findViewById(R.id.serverInput);
        connectButton = findViewById(R.id.connectButton);
        permissionButton = findViewById(R.id.permissionButton);
    }

    private void setupPermissionLauncher() {
        permissionLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestMultiplePermissions(),
                this::handlePermissionResult
        );
    }

    private void setupActions() {
        permissionButton.setOnClickListener(v -> permissionLauncher.launch(new String[]{
                Manifest.permission.CAMERA,
                Manifest.permission.RECORD_AUDIO
        }));

        connectButton.setOnClickListener(v -> {
            if (sharingActive) {
                stopSharing();
            } else {
                startSharing();
            }
        });
    }

    private void handlePermissionResult(Map<String, Boolean> result) {
        boolean cameraGranted = Boolean.TRUE.equals(result.get(Manifest.permission.CAMERA));
        boolean audioGranted = Boolean.TRUE.equals(result.get(Manifest.permission.RECORD_AUDIO));

        if (cameraGranted && audioGranted) {
            statusText.setText(R.string.status_camera_starting);
            startCameraPreview();
        } else {
            Toast.makeText(this, R.string.status_permissions_missing, Toast.LENGTH_SHORT).show();
        }

        refreshUi();
    }

    private void refreshUi() {
        boolean permissionsGranted = hasRequiredPermissions();
        permissionButton.setEnabled(!permissionsGranted);
        connectButton.setEnabled(permissionsGranted && previewReady);
        connectButton.setText(sharingActive ? R.string.stop_button : R.string.connect_button);

        if (!permissionsGranted) {
            statusText.setText(R.string.status_idle);
            localFeedText.setText(R.string.local_waiting);
            return;
        }

        if (!previewReady) {
            statusText.setText(R.string.status_camera_starting);
            localFeedText.setText(R.string.local_waiting);
            startCameraPreview();
            return;
        }

        if (!sharingActive) {
            statusText.setText(R.string.status_permissions_ready);
            localFeedText.setText(R.string.local_ready);
        }
    }

    private void startCameraPreview() {
        if (!hasRequiredPermissions()) {
            return;
        }

        ListenableFuture<ProcessCameraProvider> cameraProviderFuture = ProcessCameraProvider.getInstance(this);
        cameraProviderFuture.addListener(() -> {
            try {
                cameraProvider = cameraProviderFuture.get();
                Preview preview = new Preview.Builder().build();
                preview.setSurfaceProvider(localPreviewView.getSurfaceProvider());

                cameraProvider.unbindAll();
                cameraProvider.bindToLifecycle(
                        this,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview
                );

                previewReady = true;
                refreshUi();
            } catch (ExecutionException | InterruptedException e) {
                Thread.currentThread().interrupt();
                statusText.setText(R.string.status_camera_error);
            } catch (Exception e) {
                statusText.setText(R.string.status_camera_error);
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void startSharing() {
        if (!hasRequiredPermissions()) {
            statusText.setText(R.string.status_permissions_missing);
            return;
        }

        if (!previewReady) {
            statusText.setText(R.string.status_camera_starting);
            return;
        }

        String displayName = getText(nameInput);
        String roomCode = getText(roomInput);
        String serverUrl = getText(serverInput);

        if (TextUtils.isEmpty(displayName) || TextUtils.isEmpty(roomCode)) {
            statusText.setText(R.string.status_missing_info);
            return;
        }

        if (TextUtils.isEmpty(serverUrl)) {
            statusText.setText(R.string.status_server_missing);
            return;
        }

        sharingActive = true;
        statusText.setText(getString(R.string.status_session_live, roomCode));
        localFeedText.setText(R.string.local_sending);
        frameHandler.removeCallbacks(frameLoop);
        frameHandler.post(frameLoop);
        refreshUi();
    }

    private void stopSharing() {
        sharingActive = false;
        frameHandler.removeCallbacks(frameLoop);
        uploadInFlight.set(false);
        downloadInFlight.set(false);
        if (previewReady && hasRequiredPermissions()) {
            statusText.setText(R.string.status_session_stopped);
            localFeedText.setText(R.string.local_ready);
        }
        connectButton.setText(R.string.connect_button);
    }

    private void captureAndSendFrame() {
        if (uploadInFlight.getAndSet(true)) {
            return;
        }

        Bitmap previewBitmap = localPreviewView.getBitmap();
        if (previewBitmap == null) {
            uploadInFlight.set(false);
            return;
        }

        Bitmap scaledBitmap = Bitmap.createScaledBitmap(previewBitmap, 720, 1280, true);
        previewBitmap.recycle();

        runOnUiThread(() -> localSnapshotView.setImageBitmap(scaledBitmap));

        String roomCode = getText(roomInput);
        String displayName = getText(nameInput);
        String serverUrl = getText(serverInput);

        frameRelayClient.uploadFrame(serverUrl, roomCode, participantId, displayName, scaledBitmap, new FrameRelayClient.UploadCallback() {
            @Override
            public void onSuccess() {
                uploadInFlight.set(false);
            }

            @Override
            public void onFailure() {
                uploadInFlight.set(false);
                runOnUiThread(() -> statusText.setText(R.string.status_network_error));
            }
        });
    }

    private void pollRemoteFrame() {
        if (downloadInFlight.getAndSet(true)) {
            return;
        }

        String roomCode = getText(roomInput);
        String serverUrl = getText(serverInput);

        frameRelayClient.downloadLatestFrame(serverUrl, roomCode, participantId, new FrameRelayClient.DownloadCallback() {
            @Override
            public void onFrameReceived(@NonNull Bitmap bitmap, String senderName) {
                downloadInFlight.set(false);
                runOnUiThread(() -> {
                    remoteImageView.setImageBitmap(bitmap);
                    remotePlaceholder.setVisibility(View.GONE);
                    String label = TextUtils.isEmpty(senderName)
                            ? getString(R.string.remote_connected_unknown)
                            : getString(R.string.remote_connected, senderName);
                    remoteFeedText.setText(label);
                    remoteFeedSubtext.setText(R.string.remote_connected_subtext);
                });
            }

            @Override
            public void onNoFrame() {
                downloadInFlight.set(false);
            }

            @Override
            public void onFailure() {
                downloadInFlight.set(false);
                runOnUiThread(() -> statusText.setText(R.string.status_network_error));
            }
        });
    }

    private boolean hasRequiredPermissions() {
        return isGranted(Manifest.permission.CAMERA) && isGranted(Manifest.permission.RECORD_AUDIO);
    }

    private boolean isGranted(@NonNull String permission) {
        return ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED;
    }

    @NonNull
    private String getText(@NonNull TextInputEditText input) {
        return input.getText() == null ? "" : input.getText().toString().trim();
    }
}
