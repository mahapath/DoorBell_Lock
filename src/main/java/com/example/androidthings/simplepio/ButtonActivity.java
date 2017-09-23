/*
 * Copyright 2016, The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.example.androidthings.simplepio;

import android.Manifest;
import android.app.Activity;
import com.google.android.things.pio.Gpio;
import com.google.android.things.pio.GpioCallback;
import com.google.android.things.pio.PeripheralManagerService;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ServerValue;
import com.google.firebase.database.ValueEventListener;

import android.content.pm.PackageManager;
import android.media.Image;
import android.media.ImageReader;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Base64;
import android.util.Log;

import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * Sample usage of the Gpio API that logs when a button is pressed.
 *
 */
public class ButtonActivity extends Activity {
    private static final String TAG = ButtonActivity.class.getSimpleName();
    private FirebaseDatabase mDatabase;
    private DatabaseReference dbr;

    private DoorbellCamera mCamera;

    private Gpio mButtonGpio;
    private Gpio mDoorGpio1;
    private Gpio mDoorGpio2;

    private Handler mCameraHandler;
    private HandlerThread mCameraThread;




    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Log.i(TAG, "Doorbell Acitivy created!");
        if(checkSelfPermission(Manifest.permission.CAMERA)!= PackageManager.PERMISSION_GRANTED){
            Log.d(TAG,"No permission");
            return;
        }
        mDatabase=FirebaseDatabase.getInstance();

        mCameraThread=new HandlerThread("CameraBackground");
        mCameraThread.start();
        mCameraHandler=new Handler(mCameraThread.getLooper());

        PeripheralManagerService service = new PeripheralManagerService();
        initPIO(service);
        initPIO2(service);
        Firebaseinit();
        mCamera=DoorbellCamera.getInstance();
        mCamera.initializeCamera(this,mCameraHandler,mOnImageAvailableListener);

    }
    private void initPIO(PeripheralManagerService service){
        try {
            mButtonGpio = service.openGpio("BCM22");
            mButtonGpio.setDirection(Gpio.DIRECTION_IN);
            mButtonGpio.setEdgeTriggerType(Gpio.EDGE_FALLING);
            mButtonGpio.registerGpioCallback(new GpioCallback() {
                @Override
                public boolean onGpioEdge(Gpio gpio) {
                    Log.i(TAG, "GPIO changed, button pressed");
                    mCamera.takePicture();
                    return true;
                }
            });
        } catch (IOException e) {
            Log.e(TAG, "Error on PeripheralIO API", e);
        }
    }
    private void initPIO2(PeripheralManagerService service){
        try{
            mDoorGpio1=service.openGpio("BCM26");
            mDoorGpio1.setDirection(Gpio.DIRECTION_OUT_INITIALLY_LOW);
            mDoorGpio2=service.openGpio("BCM19");
            mDoorGpio2.setDirection(Gpio.DIRECTION_OUT_INITIALLY_LOW);
            mDoorGpio1.setValue(false);
            mDoorGpio2.setValue(false);
        }catch (IOException e){
            Log.e(TAG,"Error on PeripherallIO API",e);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        mCamera.shutDown();
        mCameraThread.quitSafely();

        if (mButtonGpio != null) {
            // Close the Gpio pin
            Log.i(TAG, "Closing Button GPIO pin");
            try {
                mButtonGpio.close();
                mDoorGpio1.close();
                mDoorGpio2.close();
            } catch (IOException e) {
                Log.e(TAG, "Error on PeripheralIO API", e);
            } finally {
                mButtonGpio = null;
            }
        }
    }
    private ImageReader.OnImageAvailableListener mOnImageAvailableListener =
            new ImageReader.OnImageAvailableListener() {
                @Override
                public void onImageAvailable(ImageReader reader) {
                    Image image = reader.acquireLatestImage();
                    // get image bytes
                    ByteBuffer imageBuf = image.getPlanes()[0].getBuffer();
                    final byte[] imageBytes = new byte[imageBuf.remaining()];
                    imageBuf.get(imageBytes);
                    image.close();

                    onPictureTaken(imageBytes);
                }
            };

    /**
     * Handle image processing in Firebase and Cloud Vision.
     */
    private void onPictureTaken(final byte[] imageBytes) {
        if (imageBytes != null) {
            final DatabaseReference log = mDatabase.getReference("logs").push();
            String imageStr = Base64.encodeToString(imageBytes, Base64.NO_WRAP | Base64.URL_SAFE);
            // upload image to firebase
            log.child("timestamp").setValue(ServerValue.TIMESTAMP);
            log.child("image").setValue(imageStr);

        }
    }
    private void Firebaseinit(){
        dbr=mDatabase.getReference().child("lock").child("open");
        ValueEventListener lock=new ValueEventListener() {
            @Override
            public void onDataChange(DataSnapshot dataSnapshot) {
                long data=(long)dataSnapshot.getValue();
                if(data==1){
                    Dooropen();
                    dbr.setValue(0);//firebase에서 1로 값이 세팅되어 있으면, 문 열었다 닫고 firebase를 0으로 세팅한다.
                }else{
                }
            }

            @Override
            public void onCancelled(DatabaseError databaseError) {

            }
        };
        dbr.addValueEventListener(lock);
    }
    private void Dooropen(){
        eleccon(mDoorGpio1,true);//한쪽 방향으로 돌려서 연다
        sleep(2);//돌리는 시간
        off();//열고 멈춤.
        sleep(40);//4초간 열려있음
        eleccon(mDoorGpio2,true);
        sleep(2);//다시 2초간 돌려서 닫는다.
        off();

    }
    public void sleep(int t){
        try{
            Thread.sleep(t*10);
        }catch (InterruptedException e){
            Log.e(TAG,"error");
        }
    }
    public void eleccon(Gpio g,boolean b){
        try{
            g.setValue(b);
        }catch(IOException e){
            Log.e("TAG","error 생김");
        }
    }
    public void off(){
        try {
            mDoorGpio1.setValue(false);
            mDoorGpio2.setValue(false);
        }catch (IOException e){
            Log.e("TAG","msg");
        }
    }
}
