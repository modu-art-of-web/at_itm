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
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.media.Image;
import android.media.ImageReader;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.os.Handler;
import android.os.HandlerThread;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.support.annotation.NonNull;
import android.text.TextPaint;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.RelativeLayout;
import android.widget.TextView;

import com.example.androidthings.imageclassifier.classifier.TensorFlowImageClassifier;
import com.github.ybq.android.spinkit.SpinKitView;
import com.google.android.gms.tasks.OnCompleteListener;
import com.google.android.gms.tasks.Task;
import com.google.android.things.contrib.driver.button.Button;
import com.google.android.things.contrib.driver.button.ButtonInputDriver;
import com.google.android.things.pio.Gpio;
import com.google.android.things.pio.PeripheralManagerService;
import com.google.firebase.auth.AuthResult;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;

import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.client.ClientProtocolException;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.mime.MultipartEntity;
import org.apache.http.entity.mime.content.ByteArrayBody;
import org.apache.http.entity.mime.content.StringBody;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.util.EntityUtils;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.util.Locale;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.example.androidthings.imageclassifier.R.id.results;


public class ImageClassifierActivity extends Activity implements ImageReader.OnImageAvailableListener {

    private static final String TAG = "ImageClassifierActivity";

    private ImagePreprocessor mImagePreprocessor;
    private static TextToSpeech mTtsEngine;
    private static TtsSpeaker mTtsSpeaker;
    private static CameraHandler mCameraHandler;
    private MqttHandler MqttHandler;
    private TensorFlowImageClassifier mTensorFlowClassifier;

    private HandlerThread mBackgroundThread;
    private static Handler mBackgroundHandler;

    private RelativeLayout mText_layout;
    private TextView explain_text;
    private ImageView mImage;
    private ImageView mBgimg;
    private SpinKitView mMainAni;
    private SpinKitView mSendingAni;
    CircularCountdown circleView;

    private AtomicBoolean mReady = new AtomicBoolean(false);
    private ButtonInputDriver mButtonDriver;
    private Gpio mReadyLED;

    private FirebaseAuth mAuth;
    private FirebaseAuth.AuthStateListener mAuthListener;

    private TimerTask mTask;
    private Timer mTimer;

    private String styleType = "0";
    private int cnt;

    @Override
    protected void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        setContentView(R.layout.layout_main);
        thread_init();
        ui_init();
        initPIO();
        layoutSettingBeforeTakePic();

        MqttHandler = MqttHandler.getInstance();
        MqttHandler.startMqtt();

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

    private void thread_init() {
        mBackgroundThread = new HandlerThread("BackgroundThread");
        mBackgroundThread.start();
        mBackgroundHandler = new Handler(mBackgroundThread.getLooper());
        mBackgroundHandler.post(mInitializeOnBackground);
    }

    private void ui_init() {
        mText_layout = (RelativeLayout)findViewById(R.id.text_layout);
        explain_text = (TextView) findViewById(R.id.explain_text);
        mBgimg = (ImageView) findViewById(R.id.bg_img);
        mImage = (ImageView) findViewById(R.id.taken_picture);
        mMainAni = (SpinKitView)findViewById(R.id.main_ani);
        mSendingAni = (SpinKitView) findViewById(R.id.sending_ani);
    }

    private void layoutSettingBeforeTakePic() {
        mText_layout.setVisibility(View.VISIBLE);
        explain_text.setText(R.string.choose_style_msg);

        mBgimg.setVisibility(View.VISIBLE);
        mImage.setVisibility(View.GONE);

        mMainAni.setVisibility(View.VISIBLE);
        mSendingAni.setVisibility(View.INVISIBLE);
    }

    private void layoutSettingAfterTakePic() {
        mText_layout.setVisibility(View.INVISIBLE);
        explain_text.setText("");

        mBgimg.setVisibility(View.INVISIBLE);
        mImage.setVisibility(View.VISIBLE);

        mMainAni.setVisibility(View.INVISIBLE);
        mSendingAni.setVisibility(View.INVISIBLE);
    }

    private void displaySendingAni() {
        mText_layout.setVisibility(View.VISIBLE);
        explain_text.setText(R.string.sending);

        mMainAni.setVisibility(View.INVISIBLE);
        mSendingAni.setVisibility(View.VISIBLE);
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
                //mBackgroundHandler.post(mBackgroundClickHandler);
                setContentView(new CircularCountdown(this));
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

    public HttpEntity postRequest(Bitmap imageBitmap) {
        byte[] data = null;
        try {
//            String url = "http://andapp.freetings.in/testbyme.php?";
            String url = "http://172.20.10.9:5000/send_image";
            //String url = "http://192.168.43.209:5000/send-image";
            HttpClient httpclient = new DefaultHttpClient();
            HttpPost httppost = new HttpPost(url);
            MultipartEntity entity = new MultipartEntity();

            if(imageBitmap!=null){
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                imageBitmap.compress(Bitmap.CompressFormat.JPEG, 100, bos);
                data = bos.toByteArray();
                entity.addPart("photo", new ByteArrayBody(data,"image/jpeg", "test2.jpg"));
            }
            styleType = MqttHandler.getStyleType();
            if(styleType != "0"){
                Log.e(TAG, "postRequest styleType :" + styleType);
                entity.addPart("styleType", new StringBody(styleType,"text/plain", Charset.forName("UTF-8")));
            }

//            entity.addPart("category", new StringBody(catid,"text/plain", Charset.forName("UTF-8")));
//            entity.addPart("your_contact_no", new  StringBody(phone,"text/plain",Charset.forName("UTF-8")));
//            entity.addPart("your_emailid", new StringBody(email,"text/plain",Charset.forName("UTF-8")));
//            entity.addPart("your_name", new StringBody(name,"text/plain",Charset.forName("UTF-8")));

            httppost.setEntity(entity);
            HttpResponse resp = httpclient.execute(httppost);
            HttpEntity resEntity = resp.getEntity();
            String string= EntityUtils.toString(resEntity);
            //  Log.e("sdjkfkhk", string);

            Log.e(TAG, "postRequest resEntity :" + resEntity);
            return resEntity;
        } catch (ClientProtocolException e) {
            e.printStackTrace();
            Log.e(TAG, "postRequest ClientProtocolException :" + e);
        } catch (IOException e) {
            Log.e(TAG, "postRequest IOException :" + e);
            e.printStackTrace();
        }
        return null;
    }
    public void savePicture(Bitmap bitmap){
        postRequest(bitmap);
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

//        setReady(true);


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
            if (MqttHandler != null) MqttHandler.destroyMqtt();
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


    private class CircularCountdown extends View {
        private final Paint backgroudFill;
        private final Paint backgroundPaint;
        private final Paint progressPaint;
        private final Paint textPaint;

        private long startTime;
        private long currentTime;
        private long maxTime;

        private double progressMillisecond;
        private double progress;

        private RectF circleBounds;
        private float radius;
        private float handleRadius;
        private float textHeight;
        private float textOffset;

        private final Handler viewHandler;
        private final Runnable updateView;

        public CircularCountdown(Context context) {
            super(context);

            // used to fit the circle into
            circleBounds = new RectF();

            // size of circle and handle
            radius = 200;
            handleRadius = 10;
            cnt = 6;

            // limit the counter to go up to maxTime ms
            maxTime = 3000;

            // start and current time
            startTime = System.currentTimeMillis();
            currentTime = startTime;

            backgroudFill = new Paint();
            backgroudFill.setColor(Color.BLACK);
            backgroudFill.setStyle(Paint.Style.FILL);

            // the style of the background
            backgroundPaint = new Paint();
            backgroundPaint.setStyle(Paint.Style.STROKE);
            backgroundPaint.setAntiAlias(true);
            backgroundPaint.setStrokeWidth(20);
            backgroundPaint.setStrokeCap(Paint.Cap.SQUARE);
            backgroundPaint.setColor(Color.parseColor("#4D4D4D"));  // dark gray

            // the style of the 'progress'
            progressPaint = new Paint();
            progressPaint.setStyle(Paint.Style.STROKE);
            progressPaint.setAntiAlias(true);
            progressPaint.setStrokeWidth(20);
            progressPaint.setStrokeCap(Paint.Cap.SQUARE);
            progressPaint.setColor(Color.parseColor("#22741C"));    // light blue

            // the style for the text in the middle
            textPaint = new TextPaint();
            textPaint.setTextSize(radius / 2);
            textPaint.setColor(Color.WHITE);
            textPaint.setTextAlign(Paint.Align.CENTER);

            // text attributes
            textHeight = textPaint.descent() - textPaint.ascent();
            textOffset = (textHeight / 2) - textPaint.descent();

            final CountDownTimer showPicTimer = new CountDownTimer(3000, 1000) {
                @Override
                public void onTick(long l) {

                }
                @Override
                public void onFinish() {
                    ui_init();
                    displaySendingAni();
                    CountDownTimer sedingtimer = new CountDownTimer(5000, 1000) {
                        int cnt = 5;
                        @Override
                        public void onTick(long l) {
                            cnt--;
                            if(cnt==0) {
                                explain_text.setText("Complete!");
                            }
                        }
                        @Override
                        public void onFinish() {
                            thread_init();
                            ui_init();
                            layoutSettingBeforeTakePic();
                        }
                    };
                    sedingtimer.start();
                }
            };

            CountDownTimer timer = new CountDownTimer(5000, 1000) {
                @Override
                public void onTick(long l) {
                    cnt--;
                }
                @Override
                public void onFinish() {
                    cnt = -1;
                    viewHandler.removeCallbacks(updateView);
                    setContentView(R.layout.layout_main);
                    ui_init();
                    layoutSettingAfterTakePic();
                    showPicTimer.start();
                    mBackgroundHandler.post(mBackgroundClickHandler);
                }
            };

            // This will ensure the animation will run periodically
            viewHandler = new Handler();
            updateView = new Runnable() {
                    @Override
                    public void run() {
                        // update current time
                    currentTime = System.currentTimeMillis();

                    // get elapsed time in milliseconds and clamp between <0, maxTime>
                    progressMillisecond = ((currentTime - startTime) % 1000); //maxTime);

                    // get current progress on a range <0, 1>
                    progress =  progressMillisecond / 1000;//maxTime;

                    if (cnt != -1) {
                        ImageClassifierActivity.CircularCountdown.this.invalidate();
                    }

                    if (cnt == -1) {
                        viewHandler.removeCallbacks(this);
                    } else {
                        viewHandler.postDelayed(updateView, 1000 / 60);
                    }
                }
            };
            viewHandler.post(updateView);
            timer.start();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            // get the center of the view
            float centerWidth = canvas.getWidth() / 2;
            float centerHeight = canvas.getHeight() / 2;


            // set bound of our circle in the middle of the view
            circleBounds.set(centerWidth - radius,
                    centerHeight - radius,
                    centerWidth + radius,
                    centerHeight + radius);

            canvas.drawPaint(backgroudFill);

            // we want to start at -90°, 0° is pointing to the right
            canvas.drawArc(circleBounds, -90, (float) (progress * 360), false, progressPaint);

            // display text inside the circle
            //canvas.drawText((double) (progressMillisecond / 100) / 10 + "s",
            canvas.drawText(cnt  + "s",
                    centerWidth,
                    centerHeight + textOffset,
                    textPaint);

            // draw handle or the circle
            canvas.drawCircle((float) (centerWidth + (Math.sin(progress * 2 * Math.PI) * radius)),
                    (float) (centerHeight - (Math.cos(progress * 2 * Math.PI) * radius)),
                    handleRadius,
                    progressPaint);
        }
    }

}
