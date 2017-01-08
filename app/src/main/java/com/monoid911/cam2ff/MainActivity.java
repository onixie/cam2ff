package com.monoid911.cam2ff;

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.YuvImage;
import android.hardware.Camera;
import android.os.Bundle;
import android.os.Handler;
import android.preference.PreferenceManager;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;

import static java.util.concurrent.Executors.newFixedThreadPool;

public class MainActivity extends Activity {
    private final Handler handler = new Handler();
    private final int delay = 1000; //milliseconds
    private Camera mCamera;
    private SurfaceView mSurfaceView;
    private Process mffmpegProc;
    private boolean mffmpegProcExit;
    private SurfaceHolder.Callback mSHCallback;
    private TextView mFFmpegOutput;
    private String ffmpegOutput;
    private ExecutorService mPool = newFixedThreadPool(1);
    private SharedPreferences mPreferences;
    private Boolean showOutput;
    private String serverAddress;
    private String serverPort;
    private Size resolution = new Size();
    private Integer jpegQuality;

    public MainActivity() {
    }

    private Camera.PreviewCallback newPreviewCallback() {
        return new Camera.PreviewCallback() {
            public OutputStream output;
            private int format;
            private Rect rect;
            private long last;
            @Override
            public void onPreviewFrame(byte[] bytes, Camera camera) {
                if (rect == null) {
                    Camera.Parameters param = camera.getParameters();
                    Camera.Size size = param.getPreviewSize();
                    format = param.getPreviewFormat();
                    int bufferSize = size.width * size.height * ImageFormat.getBitsPerPixel(format);
                    rect = new Rect(0, 0, size.width, size.height);
                    output = mffmpegProc.getOutputStream();
                }

                // JPEGに変換してffmpegに転送する
                new YuvImage(bytes, format, rect.width(), rect.height(), null)
                        .compressToJpeg(rect, jpegQuality, output);
                if (mffmpegProcExit) {
                    android.os.Process.killProcess(android.os.Process.myPid());
                }
            }
        };
    }

    private SurfaceHolder.Callback newSurfaceHolderCallback() {
        return new SurfaceHolder.Callback() {
            @Override
            public void surfaceCreated(SurfaceHolder surfaceHolder) {
                startFFmpeg();
                startCamera(surfaceHolder);
            }

            @Override
            public void surfaceChanged(SurfaceHolder surfaceHolder, int i, int i2, int i3) {
                setCameraDisplayOrientation(1, mCamera);
                mCamera.startPreview();
            }

            @Override
            public void surfaceDestroyed(SurfaceHolder surfaceHolder) {
                stopFFmpeg();
                stopCamera();
                removeSurfaceHolderCallback(surfaceHolder);
            }
        };
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(com.monoid911.cam2ff.R.layout.main);
        PreferenceManager.setDefaultValues(this, R.xml.preferences, false);

        updatePreferences();
        hookFFmpegOutput();
    }

    @Override
    protected void onResume() {
        super.onResume();

        showCaptureView();
    }

    @Override
    protected void onPause() {
        super.onPause();

        stopFFmpeg();
        stopCamera();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        MenuInflater inflater = getMenuInflater();
        inflater.inflate(R.menu.menu, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.settings:
                showSettingsView();
                return true;
            default:
                return super.onOptionsItemSelected(item);
        }
    }

    public void onBack(View v) {
        showCaptureView();
    }

    /*  View */
    private void showSettingsView() {
        final ViewGroup viewGroup = (ViewGroup) findViewById(R.id.group);
        viewGroup.removeAllViews();
        viewGroup.addView(View.inflate(this, R.layout.settings, null));

        SettingsFragment settings = new SettingsFragment();
        getFragmentManager().beginTransaction().replace(R.id.settings, settings).commit();
    }

    private void showCaptureView() {
        updatePreferences();
        if (serverAddress == null || serverAddress.split("\\.").length != 4) {
            showSettingsView();
            return;
        }

        final ViewGroup viewGroup = (ViewGroup) findViewById(R.id.group);
        viewGroup.removeAllViews();
        viewGroup.addView(View.inflate(this, R.layout.capture, null));

        addSurfaceHolderCallback();
    }

    private void updatePreferences() {
        Map<String, ?> pref = PreferenceManager.getDefaultSharedPreferences(this).getAll();

        serverAddress = (String) pref.get("serverAddress");
        serverPort = (String) pref.get("serverPort");
        showOutput = (Boolean) pref.get("showOutput");

        String[] wh = ((String) pref.get("resolution")).split("x");
        Integer width = null, height = null;
        if (wh.length == 2) {
            width = Integer.getInteger(wh[0]);
            height = Integer.getInteger(wh[1]);
        }

        if (width != null && height != null) {
            resolution.width = width;
            resolution.height = height;
        } else {
            resolution.width = 320;
            resolution.height = 240;
        }

        jpegQuality = Integer.getInteger((String) pref.get("jpegQuality"));
        if (jpegQuality == null) {
            jpegQuality = 100;
        }
    }

    /*  Preview Surface */
    private void addSurfaceHolderCallback() {
        mSurfaceView = (SurfaceView) findViewById(com.monoid911.cam2ff.R.id.surfaceView);
        if (mSurfaceView != null) {
            mSurfaceView.setKeepScreenOn(true);
            SurfaceHolder holder = mSurfaceView.getHolder();
            if (mSHCallback == null) {
                mSHCallback = newSurfaceHolderCallback();
                holder.addCallback(mSHCallback);
            }
        }
    }

    private void removeSurfaceHolderCallback(SurfaceHolder holder) {
        if (mSHCallback != null) {
            holder.removeCallback(mSHCallback);
            mSHCallback = null;
        }
    }

    /*  Camera */
    private void startCamera(SurfaceHolder surfaceHolder) {
        if (mCamera == null && surfaceHolder != null) {
            // Open camera
            mCamera = Camera.open(1);
            setCameraResolution(mCamera);
            setCameraDisplayOrientation(1, mCamera);
            try {
                mCamera.setPreviewDisplay(surfaceHolder);
                mCamera.setPreviewCallback(newPreviewCallback());
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    private void stopCamera() {
        if (mCamera != null) {
            mCamera.setPreviewCallback(null);
            try {
                mCamera.setPreviewDisplay(null);
            } catch (IOException e) {
                e.printStackTrace();
            }
            mCamera.release();
            mCamera = null;
        }
    }

    private void setCameraResolution(android.hardware.Camera camera) {
        Camera.Parameters params = mCamera.getParameters();
        List<Camera.Size> supported = params.getSupportedPreviewSizes();
        params.setPreviewSize(resolution.width, resolution.height);
        mCamera.setParameters(params);
    }

    private void setCameraDisplayOrientation(int cameraId, android.hardware.Camera camera) {
        android.hardware.Camera.CameraInfo info =
                new android.hardware.Camera.CameraInfo();
        android.hardware.Camera.getCameraInfo(cameraId, info);
        int rotation = this.getWindowManager().getDefaultDisplay()
                .getRotation();
        int degrees = 0;
        switch (rotation) {
            case Surface.ROTATION_0:
                degrees = 0;
                break;
            case Surface.ROTATION_90:
                degrees = 90;
                break;
            case Surface.ROTATION_180:
                degrees = 180;
                break;
            case Surface.ROTATION_270:
                degrees = 270;
                break;
        }

        int result;
        if (info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) {
            result = (info.orientation + degrees) % 360;
            result = (360 - result) % 360;  // compensate the mirror
        } else {  // back-facing
            result = (info.orientation - degrees + 360) % 360;
        }
        camera.setDisplayOrientation(result);
    }

    /*  FFmpeg */
    private void startFFmpeg() {
        if (mffmpegProc == null) {
            // Start ffmpeg
            String cwd = "/data/data/" + getPackageName();
            String ffmpeg = cwd + "/lib/libffmpeg.so"; // lollipopまで*.soはここにインストールするそうだ。
            String output = cwd + "/output.flv";

            try {
                mffmpegProcExit = false;
                mffmpegProc = new ProcessBuilder()
                        .command(ffmpeg, "-f", "image2pipe", "-i", "-", "-f", "ffm", String.format("http://%s:%s/feed", serverAddress, serverPort))
                        .directory(new File(cwd))
                        .redirectErrorStream(true)
                        .start();

                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        BufferedReader br = new BufferedReader(new InputStreamReader(mffmpegProc.getInputStream()));
                        try {
                            while (showOutput) {
                                if (br.ready()) {
                                    ffmpegOutput = br.readLine();
                                    System.out.println(ffmpegOutput);
                                }
                            }
                        } catch (IOException e) {
                            e.printStackTrace();
                        }
                    }
                }).start();

                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            mffmpegProc.waitFor();
                        } catch (InterruptedException e) {
                            e.printStackTrace();
                        }
                        mffmpegProcExit = true;
                    }
                }).start();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    private void stopFFmpeg() {
        if (mffmpegProc != null) {
            mffmpegProc.destroy();
            mffmpegProc = null;
        }
    }

    private void hookFFmpegOutput() {
        handler.postDelayed(new Runnable() {
            public void run() {
                mFFmpegOutput = (TextView) findViewById(com.monoid911.cam2ff.R.id.ffmpegOutput);
                if (mFFmpegOutput != null) {
                    if (showOutput) {
                        mFFmpegOutput.setText(ffmpegOutput);
                    } else {
                        mFFmpegOutput.setText("");
                    }
                }
                handler.postDelayed(this, delay);
            }
        }, delay);
    }

    private class Size {
        int width;
        int height;
    }
}
