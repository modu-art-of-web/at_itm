package com.example.androidthings.imageclassifier;

import android.util.Log;

import com.google.android.things.pio.Gpio;
import com.google.android.things.pio.PeripheralManagerService;

import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttCallback;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;

import java.io.IOException;

/**
 * Created by TaejaeHan on 01/10/2017.
 */

public class MqttHandler implements MqttCallback {
    private static final String TAG = "MqttCallback";

    public static final String LED_PIN = "BCM13"; //physical pin #33
    private Gpio ledPin;
    private String styleType = "0";
    // Lazy-loaded singleton, so only one instance of the camera is created.
    private MqttHandler() {
    }
    private static class InstanceHolder {
        private static MqttHandler mMqtt = new MqttHandler();
    }
    public static MqttHandler getInstance() {
        return InstanceHolder.mMqtt;
    }

    public void startMqtt() {
        Log.d(TAG, "startMqtt..... MQTT LED");
        try {
            MqttClient client = new MqttClient("tcp://172.20.10.5:1883", "AndroidThingSub", new MemoryPersistence());
            client.setCallback(this);
            client.connect();

            String topic = "topic/led";
            client.subscribe(topic);

        } catch (MqttException e) {
            e.printStackTrace();
        }

        PeripheralManagerService service = new PeripheralManagerService();
        try {
            // Create GPIO connection for LED.
            ledPin = service.openGpio(LED_PIN);
            // Configure as an output.
            ledPin.setDirection(Gpio.DIRECTION_OUT_INITIALLY_LOW);
        } catch (IOException e) {
            Log.e(TAG, "Error on PeripheralIO API", e);
        }
    }

    public void destroyMqtt() {
        Log.d(TAG, "destroyMqtt");

        if (ledPin != null) {
            try {
                ledPin.close();
            } catch (IOException e) {
                Log.e(TAG, "Error on PeripheralIO API", e);
            }
        }
    }

    public String getStyleType() {
        return styleType;
    }

    @Override
    public void connectionLost(Throwable cause) {
        Log.d(TAG, "connectionLost....");
    }

    @Override
    public void messageArrived(String topic, MqttMessage message) throws Exception {
        String payload = new String(message.getPayload());
        Log.d(TAG, payload);
        switch (payload) {
//            case "ON":
//                Log.d(TAG, "LED ON");
//                ledPin.setValue(true);
//                break;
//            case "OFF":
//                Log.d(TAG, "LED OFF");
//                ledPin.setValue(false);
//                break;
            case "1":
                Log.d(TAG, "STYLE 1");
                styleType = "1";
                break;
            case "2":
                Log.d(TAG, "STYLE 2");
                styleType = "2";
                break;
            case "3":
                Log.d(TAG, "STYLE 3");
                styleType = "3";
                break;
            case "4":
                Log.d(TAG, "STYLE 4");
                styleType = "4";
                break;
            case "5":
                Log.d(TAG, "STYLE 5");
                styleType = "5";
                break;
            default:
                Log.d(TAG, "Message not supported!");
                break;
        }
    }

    @Override
    public void deliveryComplete(IMqttDeliveryToken token) {
        Log.d(TAG, "deliveryComplete....");
    }
}