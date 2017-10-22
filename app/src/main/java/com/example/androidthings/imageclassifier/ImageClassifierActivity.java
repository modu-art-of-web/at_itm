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
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.os.Handler;
import android.os.HandlerThread;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
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
import com.google.android.things.contrib.driver.button.Button;
import com.google.android.things.contrib.driver.button.ButtonInputDriver;
import com.google.android.things.pio.Gpio;
import com.google.android.things.pio.PeripheralManagerService;

import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.client.ClientProtocolException;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.mime.MultipartEntity;
import org.apache.http.entity.mime.content.ByteArrayBody;
import org.apache.http.entity.mime.content.StringBody;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.util.EntityUtils;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.util.Locale;
import java.util.Random;
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
//    private SpinKitView mMainAni;
    private SpinKitView mSendingAni;
    CircularCountdown circleView;
    private android.widget.Button mRandomBtn;

    private AtomicBoolean mReady = new AtomicBoolean(false);
    private ButtonInputDriver mButtonDriver;
    private Gpio mReadyLED;

    private String styleType = "-1";
    private String mBgImageName = "bg_full";
    private int cnt;

    @Override
    protected void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        setContentView(R.layout.layout_main);
        thread_init();
        ui_init();

        setBg(mBgImageName, styleType);
//        mBgimg.setImageDrawable(getResources().getDrawable(R.drawable.starry_night));
        initPIO();
        layoutSettingBeforeTakePic();

//        MqttHandler = MqttHandler.getInstance();
//        MqttHandler.startMqtt(ImageClassifierActivity.this);

    }

    public void setBg(String imageName, String style){

        if(style != "-1"){
            new Thread(new Runnable() {
                @Override
                public void run() {
                    runOnUiThread(new Runnable(){
                        @Override
                        public void run() {
                            explain_text.setText(R.string.take_pic_msg);
                        }
                    });
                }
            }).start();

        }else{
            new Thread(new Runnable() {
                @Override
                public void run() {
                    runOnUiThread(new Runnable(){
                        @Override
                        public void run() {
                            explain_text.setText(R.string.choose_style_msg);
                        }
                    });
                }
            }).start();

        }
        mBgImageName = imageName;
        styleType = style;

        new Thread(new Runnable() {
            @Override
            public void run() {
                runOnUiThread(new Runnable(){
                    @Override
                    public void run() {
                        int resID = getResources().getIdentifier(mBgImageName , "drawable", getPackageName());
//        mBgimg.setImageDrawable(getResources().getDrawable(R.drawable.starry_night));
                        mBgimg.setImageResource(resID);
                    }
                });
            }
        }).start();

    }
    @Override
    public void onStart() {
        super.onStart();
    }

    @Override
    public void onStop() {
        super.onStop();
    }

    @Override
    protected void onResume() {

        super.onResume();
//        if (MqttHandler == null){
//            MqttHandler = MqttHandler.getInstance();
//            MqttHandler.startMqtt(ImageClassifierActivity.this);
//        }
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
//        mMainAni = (SpinKitView)findViewById(R.id.main_ani);
        mSendingAni = (SpinKitView) findViewById(R.id.sending_ani);
        mRandomBtn = (android.widget.Button) findViewById(R.id.btn_random);

    }

    private void layoutSettingBeforeTakePic() {
        mText_layout.setVisibility(View.VISIBLE);
        explain_text.setText(R.string.choose_style_msg);

        mBgimg.setVisibility(View.VISIBLE);
        mImage.setVisibility(View.GONE);

//        mMainAni.setVisibility(View.VISIBLE);
        mSendingAni.setVisibility(View.INVISIBLE);

        styleType = "-1";
        mBgImageName = "bg_full";
        setBg(mBgImageName, styleType);

        mRandomBtn.setVisibility(View.VISIBLE);
        mRandomBtn.setOnClickListener(new View.OnClickListener(){
            @Override
            public void onClick(View v) {
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        runOnUiThread(new Runnable(){
                            @Override
                            public void run() {
                                try {

                                    Random random = new Random();
                                    String rnd = ((int) (Math.random() * 6)) + "" ;
                                    Log.e("carol", "num2 :" + rnd);
                                    styleType = rnd;
                                    //takePicture(rnd);
                                    new CallTakePic().execute();
                                }catch(Exception e){
                                    e.printStackTrace();
                                }
                            }
                        });
                    }
                }).start();

            }


        });

    }

    private class CallTakePic extends AsyncTask{
        @Override
        protected Object doInBackground(Object[] params) {
            Log.e("carol","doinBack");
            takePicture(styleType);
            return null;
        }
    }


    private void layoutSettingAfterTakePic() {
        mText_layout.setVisibility(View.INVISIBLE);
        explain_text.setText("");

        mBgimg.setVisibility(View.INVISIBLE);
        mImage.setVisibility(View.VISIBLE);

//        mMainAni.setVisibility(View.INVISIBLE);
        mSendingAni.setVisibility(View.INVISIBLE);
        mRandomBtn.setVisibility(View.INVISIBLE);
    }

    private void displaySendingAni() {
        mText_layout.setVisibility(View.VISIBLE);
        explain_text.setText(R.string.send_ongoing);

//        mMainAni.setVisibility(View.INVISIBLE);
        mSendingAni.setVisibility(View.VISIBLE);
        mRandomBtn.setVisibility(View.INVISIBLE);
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
        if(styleType == "-1"){
            Log.i(TAG, "Select style first!!!");
            styleType = "-1";
        }else{
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

    }

    public HttpEntity postRequest(Bitmap imageBitmap) {
        byte[] data = null;
        try {
//            String url = "http://andapp.freetings.in/testbyme.php?";
            String url = "http://192.168.255.214:5000/send-image";
            HttpClient httpclient = new DefaultHttpClient();
            HttpPost httppost = new HttpPost(url);
            MultipartEntity entity = new MultipartEntity();

            if(imageBitmap!=null){
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                imageBitmap.compress(Bitmap.CompressFormat.JPEG, 100, bos);
                data = bos.toByteArray();
                entity.addPart("photo", new ByteArrayBody(data,"image/jpeg","wave.jpg"));
            }

            if(styleType != "-1"){
                Log.e(TAG, "postRequest styleType :" + styleType);
                entity.addPart("styleType", new StringBody(styleType,"text/plain", Charset.forName("UTF-8")));
            }

            httppost.setEntity(entity);
            HttpResponse resp = httpclient.execute(httppost);
            HttpEntity resEntity = resp.getEntity();
            String string= EntityUtils.toString(resEntity);
            Log.i(TAG, "postRequest resEntity :" + resEntity);
            Log.i(TAG, "postRequest resp.getStatusLine() : " + resp.getStatusLine());
            Log.i(TAG, "postRequest resp.getStatusLine().getStatusCode : " + resp.getStatusLine().getStatusCode());
            if(resp.getStatusLine().getStatusCode() == 200){
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        runOnUiThread(new Runnable(){
                            @Override
                            public void run() {
                                explain_text.setText(R.string.send_complete);
                                CountDownTimer sedingtimer = new CountDownTimer(3000, 1000) {
                                    @Override
                                    public void onTick(long l) {

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
                        });
                    }
                }).start();
            }else{
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        runOnUiThread(new Runnable(){
                            @Override
                            public void run() {
                                explain_text.setText(R.string.send_err);
                                CountDownTimer sedingtimer = new CountDownTimer(3000, 1000) {
                                    @Override
                                    public void onTick(long l) {

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
                        });
                    }
                }).start();
            }

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

    // convert inputstream to String
    private static String convertInputStreamToString(InputStream inputStream) throws IOException{
        BufferedReader bufferedReader = new BufferedReader( new InputStreamReader(inputStream));
        String line = "";
        String result = "";
        while((line = bufferedReader.readLine()) != null)
            result += line;

        inputStream.close();
        return result;

    }

    public void takePicture(String styleType){
        sendReadyReq();
        Log.d(TAG, "Received takePicture MQTT - Ready = " + mReady.get());
        if(styleType == "-1"){
            Log.i(TAG, "Select style first!!!");
        }else{
            Log.i(TAG, "mReady.get() : " +  mReady.get());
                if (mReady.get()) {
                    new Thread(new Runnable() {
                        @Override
                        public void run() {
                            runOnUiThread(new Runnable(){
                                @Override
                                public void run() {
                                    setReady(false);
                                    setContentView(new CircularCountdown(ImageClassifierActivity.this));
                                }
                            });
                        }
                    }).start();
                } else {
                    Log.i(TAG, "Sorry, processing hasn't finished. Try again in a few seconds");
                }
        }
    }
    public void sendReadyReq(){
        InputStream inputStream = null;
        String result = "";
        try {
            Log.e(TAG, "sendReadyReq styleType :" + styleType);

            String url = "http://192.168.255.214:5000/selected-style?styleId="+styleType;
            HttpClient httpclient = new DefaultHttpClient();
            HttpGet httpget = new HttpGet(url);
            MultipartEntity entity = new MultipartEntity();

            HttpResponse httpResponse = httpclient.execute(httpget);
            inputStream = httpResponse.getEntity().getContent();
            int statusCode = httpResponse.getStatusLine().getStatusCode();
            if(inputStream != null)
                result = convertInputStreamToString(inputStream);
            else
                result = "Did not work!";

            Log.i(TAG, "getRequest statusCode :" + statusCode);
            Log.i(TAG, "getRequest httpResponse.getStatusLine() : " + httpResponse.getStatusLine());
            Log.i(TAG, "getRequest result :" + result);

        } catch (ClientProtocolException e) {
            e.printStackTrace();
            Log.e(TAG, "getRequest ClientProtocolException :" + e);
        } catch (IOException e) {
            Log.e(TAG, "getRequest IOException :" + e);
            e.printStackTrace();
        }
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
        postRequest(bitmap);
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
//                    CountDownTimer sedingtimer = new CountDownTimer(5000, 1000) {
//                        int cnt = 5;
//                        @Override
//                        public void onTick(long l) {
//                            cnt--;
//                            if(cnt==0) {
//                                explain_text.setText("Complete!");
//                            }
//                        }
//                        @Override
//                        public void onFinish() {
//                            thread_init();
//                            ui_init();
//                            layoutSettingBeforeTakePic();
//                        }
//                    };
//                    sedingtimer.start();
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
