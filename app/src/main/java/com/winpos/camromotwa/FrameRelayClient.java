package com.winpos.camromotwa;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

public class FrameRelayClient {

    private static final MediaType JPEG_MEDIA_TYPE = MediaType.get("image/jpeg");

    private final OkHttpClient httpClient = new OkHttpClient();

    interface UploadCallback {
        void onSuccess();

        void onFailure();
    }

    interface DownloadCallback {
        void onFrameReceived(@NonNull Bitmap bitmap, @Nullable String senderName);

        void onNoFrame();

        void onFailure();
    }

    void uploadFrame(
            @NonNull String baseUrl,
            @NonNull String roomCode,
            @NonNull String participantId,
            @NonNull String participantName,
            @NonNull Bitmap bitmap,
            @NonNull UploadCallback callback
    ) {
        HttpUrl requestUrl = HttpUrl.parse(baseUrl);
        if (requestUrl == null) {
            callback.onFailure();
            return;
        }

        HttpUrl url = requestUrl.newBuilder()
                .addPathSegment("rooms")
                .addPathSegment(roomCode)
                .addPathSegment("frame")
                .build();

        byte[] jpegBytes = compressBitmap(bitmap);
        Request request = new Request.Builder()
                .url(url)
                .header("X-Participant-Id", participantId)
                .header("X-Participant-Name", participantName)
                .post(RequestBody.create(jpegBytes, JPEG_MEDIA_TYPE))
                .build();

        httpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                callback.onFailure();
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) {
                response.close();
                if (response.isSuccessful()) {
                    callback.onSuccess();
                } else {
                    callback.onFailure();
                }
            }
        });
    }

    void downloadLatestFrame(
            @NonNull String baseUrl,
            @NonNull String roomCode,
            @NonNull String participantId,
            @NonNull DownloadCallback callback
    ) {
        HttpUrl requestUrl = HttpUrl.parse(baseUrl);
        if (requestUrl == null) {
            callback.onFailure();
            return;
        }

        HttpUrl url = requestUrl.newBuilder()
                .addPathSegment("rooms")
                .addPathSegment(roomCode)
                .addPathSegment("frame")
                .addQueryParameter("excludeParticipant", participantId)
                .build();

        Request request = new Request.Builder()
                .url(url)
                .get()
                .build();

        httpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                callback.onFailure();
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                try (ResponseBody responseBody = response.body()) {
                    if (response.code() == 204) {
                        callback.onNoFrame();
                        return;
                    }

                    if (!response.isSuccessful() || responseBody == null) {
                        callback.onFailure();
                        return;
                    }

                    byte[] bodyBytes = responseBody.bytes();
                    Bitmap bitmap = BitmapFactory.decodeByteArray(bodyBytes, 0, bodyBytes.length);
                    if (bitmap == null) {
                        callback.onFailure();
                        return;
                    }

                    callback.onFrameReceived(bitmap, response.header("X-Participant-Name"));
                } finally {
                    response.close();
                }
            }
        });
    }

    @NonNull
    private byte[] compressBitmap(@NonNull Bitmap bitmap) {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        bitmap.compress(Bitmap.CompressFormat.JPEG, 60, outputStream);
        return outputStream.toByteArray();
    }
}
