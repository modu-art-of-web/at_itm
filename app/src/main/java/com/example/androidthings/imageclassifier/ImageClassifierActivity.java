/*
 * Copyright 2017 The Android Things Samples Authors.
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
package com.example.androidthings.imageclassifier;

import android.app.Activity;
import android.graphics.Bitmap;
import android.media.Image;
import android.media.ImageReader;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.support.annotation.NonNull;
import android.util.Log;
import android.view.KeyEvent;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.TextView;

import com.example.androidthings.imageclassifier.classifier.TensorFlowImageClassifier;
import com.google.android.gms.tasks.OnCompleteListener;
import com.google.android.gms.tasks.Task;
import com.google.android.things.contrib.driver.button.Button;
import com.google.android.things.contrib.driver.button.ButtonInputDriver;
import com.google.android.things.pio.Gpio;
import com.google.android.things.pio.PeripheralManagerService;
import com.google.firebase.auth.AuthResult;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;

import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.impl.client.DefaultHttpClient;

import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.example.androidthings.imageclassifier.R.id.imageView;
import static com.example.androidthings.imageclassifier.R.id.results;


public class ImageClassifierActivity extends Activity implements ImageReader.OnImageAvailableListener {
    private static final String TAG = "ImageClassifierActivity";

    private ImagePreprocessor mImagePreprocessor;
    private TextToSpeech mTtsEngine;
    private TtsSpeaker mTtsSpeaker;
    private CameraHandler mCameraHandler;
    private TensorFlowImageClassifier mTensorFlowClassifier;

    private HandlerThread mBackgroundThread;
    private Handler mBackgroundHandler;

    private ImageView mImage;
    private TextView[] mResultViews;

    private AtomicBoolean mReady = new AtomicBoolean(false);
    private ButtonInputDriver mButtonDriver;
    private Gpio mReadyLED;

    private FirebaseAuth mAuth;
    private FirebaseAuth.AuthStateListener mAuthListener;

    private TimerTask mTask;
    private Timer mTimer;

    @Override
    protected void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        setContentView(R.layout.activity_camera);
        mImage = (ImageView) findViewById(imageView);
        mResultViews = new TextView[3];
        mResultViews[0] = (TextView) findViewById(R.id.result1);
        mResultViews[1] = (TextView) findViewById(R.id.result2);
        mResultViews[2] = (TextView) findViewById(R.id.result3);

        init();

        mAuth = FirebaseAuth.getInstance();
        mAuthListener = new FirebaseAuth.AuthStateListener() {
            @Override
            public void onAuthStateChanged(@NonNull FirebaseAuth firebaseAuth) {
                FirebaseUser user = firebaseAuth.getCurrentUser();
                if (user != null) {
                    // User is signed in
                    Log.d(TAG, "onAuthStateChanged:signed_in:" + user.getUid());
                } else {
                    // User is signed out
                    Log.d(TAG, "onAuthStateChanged:signed_out");
                }
                // ...
            }
        };
    }

    @Override
    public void onStart() {
        super.onStart();
        mAuth.addAuthStateListener(mAuthListener);
    }

    @Override
    public void onStop() {
        super.onStop();
        if (mAuthListener != null) {
            mAuth.removeAuthStateListener(mAuthListener);
        }
    }



    @Override
    protected void onResume() {
        super.onResume();
        //add listener
        mAuth.addAuthStateListener(mAuthListener);
        mAuth.signInWithEmailAndPassword("hantaejae@daum.net", "htj9023727")
                .addOnCompleteListener(this, new OnCompleteListener<AuthResult>() {
                    @Override
                    public void onComplete(@NonNull Task<AuthResult> task) {
                        Log.d(TAG, "signInWithEmail:onComplete:" + task.isSuccessful());

                        // If sign in fails, display a message to the user. If sign in succeeds
                        // the auth state listener will be notified and logic to handle the
                        // signed in user can be handled in the listener.
                        if (!task.isSuccessful()) {
                            Log.w(TAG, "signInWithEmail:failed", task.getException());
//                            Toast.makeText(EmailPasswordActivity.this, R.string.auth_failed,
//                                    Toast.LENGTH_SHORT).show();
                        }
                    }
                });

//        mTask = new TimerTask() {
//            @Override
//            public void run() {
//
//                if (mReady.get()) {
//                    setReady(false);
//                    mTimer.cancel();
//                    mBackgroundHandler.post(mBackgroundClickHandler);
//                } else {
//                    Log.i(TAG, "Sorry, NEXT TIME!");
//                }
//            }
//        };


    }

    private void init() {
        initPIO();

        mBackgroundThread = new HandlerThread("BackgroundThread");
        mBackgroundThread.start();
        mBackgroundHandler = new Handler(mBackgroundThread.getLooper());
        mBackgroundHandler.post(mInitializeOnBackground);
    }

    private void initPIO() {
        PeripheralManagerService pioService = new PeripheralManagerService();
        try {
            mReadyLED = pioService.openGpio(BoardDefaults.getGPIOForLED());
            mReadyLED.setDirection(Gpio.DIRECTION_OUT_INITIALLY_LOW);
            mButtonDriver = new ButtonInputDriver(
                    BoardDefaults.getGPIOForButton(),
                    Button.LogicState.PRESSED_WHEN_LOW,
                    KeyEvent.KEYCODE_ENTER);
            mButtonDriver.register();
        } catch (IOException e) {
            mButtonDriver = null;
            Log.w(TAG, "Could not open GPIO pins", e);
        }
    }

    private Runnable mInitializeOnBackground = new Runnable() {
        @Override
        public void run() {
            mImagePreprocessor = new ImagePreprocessor();

            mTtsSpeaker = new TtsSpeaker();
            mTtsSpeaker.setHasSenseOfHumor(true);
            mTtsEngine = new TextToSpeech(ImageClassifierActivity.this,
                    new TextToSpeech.OnInitListener() {
                        @Override
                        public void onInit(int status) {
                            if (status == TextToSpeech.SUCCESS) {
                                mTtsEngine.setLanguage(Locale.US);
                                mTtsEngine.setOnUtteranceProgressListener(utteranceListener);
                                mTtsSpeaker.speakReady(mTtsEngine);
                            } else {
                                Log.w(TAG, "Could not open TTS Engine (onInit status=" + status
                                        + "). Ignoring text to speech");
                                mTtsEngine = null;
                            }
                        }
                    });
            mCameraHandler = CameraHandler.getInstance();
            mCameraHandler.initializeCamera(
                    ImageClassifierActivity.this, mBackgroundHandler,
                    ImageClassifierActivity.this);

            mTensorFlowClassifier = new TensorFlowImageClassifier(ImageClassifierActivity.this);

            setReady(true);
        }
    };

    private Runnable mBackgroundClickHandler = new Runnable() {
        @Override
        public void run() {
            if (mTtsEngine != null) {
                mTtsSpeaker.speakShutterSound(mTtsEngine);
            }
            mCameraHandler.takePicture();
        }
    };

    private UtteranceProgressListener utteranceListener = new UtteranceProgressListener() {
        @Override
        public void onStart(String utteranceId) {
            setReady(false);
        }

        @Override
        public void onDone(String utteranceId) {
            setReady(true);
        }

        @Override
        public void onError(String utteranceId) {
            setReady(true);
        }
    };


    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        Log.d(TAG, "Received key up: " + keyCode + ". Ready = " + mReady.get());
        if (keyCode == KeyEvent.KEYCODE_ENTER) {
            if (mReady.get()) {
                setReady(false);
                mBackgroundHandler.post(mBackgroundClickHandler);
            } else {
                Log.i(TAG, "Sorry, processing hasn't finished. Try again in a few seconds");
            }
            return true;
        }
        return super.onKeyUp(keyCode, event);
    }

    private void setReady(boolean ready) {
        mReady.set(ready);
        if (mReadyLED != null) {
            try {
                mReadyLED.setValue(ready);
            } catch (IOException e) {
                Log.w(TAG, "Could not set LED", e);
            }
        }

//        if (ready) {
//            mTimer = new Timer();
//            mTimer.schedule(mTask, 5000, 10000);
//        }
    }


    private static final String GOOGLE_APPLICATION_CREDENTIALS = "ras-pic-9cfde668dbc3.json";

    public String loadJSONFromAsset() {
        String json = null;
        try {
            InputStream is = this.getAssets().open(GOOGLE_APPLICATION_CREDENTIALS);
            int size = is.available();
            byte[] buffer = new byte[size];
            is.read(buffer);
            is.close();
            json = new String(buffer, "UTF-8");
        } catch (IOException ex) {
            ex.printStackTrace();
            return null;
        }
        return json;
    }



    public void savePicture(Bitmap bitmap){

        HttpPost httppost = new HttpPost("http://www.사이트주소.com/script.php");

//        Date from = new Date();
//        SimpleDateFormat transFormat = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss");
//        String picName = transFormat.format(from);
//
//        FirebaseStorage storage = FirebaseStorage.getInstance();
//        StorageReference storageRef = storage.getReferenceFromUrl("gs://ras-pic.appspot.com/input/raspberry_pic");
//
//        // Create a reference to "mountains.jpg"
//        StorageReference mountainsRef = storageRef.child("c_"+picName+".jpg");
//
//
//        // Get the data from an ImageView as bytes
////        mImage.setDrawingCacheEnabled(true);
////        mImage.buildDrawingCache();
////        Bitmap bitmap = mImage.getDrawingCache();
//        ByteArrayOutputStream baos = new ByteArrayOutputStream();
//        bitmap.compress(Bitmap.CompressFormat.JPEG, 100, baos);
//        byte[] data = baos.toByteArray();
//
//        UploadTask uploadTask = mountainsRef.putBytes(data);
//        uploadTask.addOnFailureListener(new OnFailureListener() {
//            @Override
//            public void onFailure(@NonNull Exception exception) {
//                Log.e(TAG, "uploadTask:onFailure:" + exception);
//                setReady(true);
//                mResultViews[0].setText("uploadTask:onFailure exception :" + exception);
//                // Handle unsuccessful uploads
//            }
//        }).addOnSuccessListener(new OnSuccessListener<UploadTask.TaskSnapshot>() {
//            @Override
//            public void onSuccess(UploadTask.TaskSnapshot taskSnapshot) {
//                // taskSnapshot.getMetadata() contains file metadata such as size, content-type, and download URL.
//                Uri downloadUrl = taskSnapshot.getDownloadUrl();
//                Log.d(TAG, "uploadTask:onSuccess taskSnapshot :" + taskSnapshot);
//                Log.d(TAG, "uploadTask:onSuccess downloadUrl :" + downloadUrl);
//                setReady(true);
//                mResultViews[0].setText("uploadTask:onSuccess downloadUrl :" + downloadUrl);
//            }
//        });


//        InputStream stream;
//        File localFile;
//        try{
//            AssetManager assetMgr = getAssets();
//            stream = assetMgr.open("rpi3.png");
////            localFile = createFileFromInputStream(stream);
//        } catch (IOException ex) {
//            throw new IllegalStateException("IOException " + ex);
//        };
//
//        FirebaseStorage storage = FirebaseStorage.getInstance();
//        StorageReference storageRef = storage.getReferenceFromUrl("gs://ras-pic.appspot.com");
//
//        // Create a reference to "mountains.jpg"
//        StorageReference mountainsRef = storageRef.child("rpi3.png");
//
//        Log.d(TAG, "'mountainsRef : ':" + mountainsRef);
//        mountainsRef.putStream(stream).addOnFailureListener(new OnFailureListener() {
//            @Override
//            public void onFailure(@NonNull Exception exception) {
//                Log.e(TAG, "putStream:onFailure:" + exception);
//                // Handle unsuccessful uploads
//            }
//        }).addOnSuccessListener(new OnSuccessListener<UploadTask.TaskSnapshot>() {
//
//            @Override
//            public void onSuccess(UploadTask.TaskSnapshot taskSnapshot) {
//                Log.d(TAG, "putStream:onSuccess:" + taskSnapshot);
//                // taskSnapshot.getMetadata() contains file metadata such as size, content-type, and download URL.
//                Uri downloadUrl = taskSnapshot.getDownloadUrl();
//            }
//        });
    }




    @Override
    public void onImageAvailable(ImageReader reader) {
        final Bitmap bitmap;
        try (Image image = reader.acquireNextImage()) {
            bitmap = mImagePreprocessor.preprocessImage(image);
        }

        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                mImage.setImageBitmap(bitmap);
            }
        });

//        final List<Classifier.Recognition> results = mTensorFlowClassifier.doRecognize(bitmap);

        Log.d(TAG, "Got the following results from Tensorflow: " + results);

        getAssets();
        savePicture(bitmap);

//        if (mTtsEngine != null) {
//            // speak out loud the result of the image recognition
//            mTtsSpeaker.speakResults(mTtsEngine, results);
//        } else {
//            // if theres no TTS, we don't need to wait until the utterance is spoken, so we set
//            // to ready right away.
////            setReady(true);
//        }

        setReady(true);


//        runOnUiThread(new Runnable() {
//            @Override
//            public void run() {
//                for (int i = 0; i < mResultViews.length; i++) {
//                    if (results.size() > i) {
//                        Classifier.Recognition r = results.get(i);
//                        mResultViews[i].setText(r.getTitle() + " : " + r.getConfidence().toString());
//                    } else {
//                        mResultViews[i].setText(null);
//                    }
//                }
//            }
//        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        try {
            if (mBackgroundThread != null) mBackgroundThread.quit();
        } catch (Throwable t) {
            // close quietly
        }
        mBackgroundThread = null;
        mBackgroundHandler = null;

        try {
            if (mCameraHandler != null) mCameraHandler.shutDown();
        } catch (Throwable t) {
            // close quietly
        }
        try {
            if (mTensorFlowClassifier != null) mTensorFlowClassifier.destroyClassifier();
        } catch (Throwable t) {
            // close quietly
        }
        try {
            if (mButtonDriver != null) mButtonDriver.close();
        } catch (Throwable t) {
            // close quietly
        }

        mTimer.cancel();
        if (mTtsEngine != null) {
            mTtsEngine.stop();
            mTtsEngine.shutdown();
        }
    }

}
