package com.monoid911.cam2ff;

import android.app.Activity;
import android.hardware.Camera;
import android.os.Bundle;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

public class MainActivity extends Activity {
    Camera mCamera;
    SurfaceView mSurfaceView;
    private SurfaceHolder.Callback callback = new SurfaceHolder.Callback() {
        @Override
        public void surfaceCreated(SurfaceHolder surfaceHolder) {

            //CameraOpen
            mCamera = Camera.open(1);
            setCameraDisplayOrientation(1, mCamera);
            //出力をSurfaceViewに設定
            try {
                mCamera.setPreviewDisplay(surfaceHolder);
            } catch (Exception e) {
                e.printStackTrace();
            }

        }

        @Override
        public void surfaceChanged(SurfaceHolder surfaceHolder, int i, int i2, int i3) {
            setCameraDisplayOrientation(1, mCamera);
            //プレビュースタート（Changedは最初にも1度は呼ばれる）
            mCamera.startPreview();
        }

        @Override
        public void surfaceDestroyed(SurfaceHolder surfaceHolder) {

            //片付け
            mCamera.release();
            mCamera = null;
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(com.monoid911.cam2ff.R.layout.activity_main);
    }

    @Override
    protected void onResume() {
        super.onResume();

        mSurfaceView = (SurfaceView) findViewById(com.monoid911.cam2ff.R.id.surfaceView);
        SurfaceHolder holder = mSurfaceView.getHolder();
        holder.addCallback(callback);
    }

    public void setCameraDisplayOrientation(int cameraId, android.hardware.Camera camera) {
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
}
