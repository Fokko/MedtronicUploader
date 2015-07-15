package com.nightscout.android.upload;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.preference.PreferenceManager;
import android.util.Log;

import com.mongodb.BasicDBObject;
import com.mongodb.DBObject;
import com.mongodb.MongoException;
import com.nightscout.android.MongoConnection;
import com.nightscout.android.medtronic.MedtronicActivity;
import com.nightscout.android.medtronic.MedtronicConstants;
import com.nightscout.android.medtronic.MedtronicReader;

import org.apache.commons.lang3.exception.ExceptionUtils;
import org.apache.http.client.ResponseHandler;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.BasicResponseHandler;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.params.BasicHttpParams;
import org.apache.http.params.HttpConnectionParams;
import org.apache.http.params.HttpParams;
import org.fusesource.mqtt.client.FutureConnection;
import org.fusesource.mqtt.client.MQTT;
import org.fusesource.mqtt.client.QoS;
import org.json.JSONObject;
import org.slf4j.LoggerFactory;

import java.net.URISyntaxException;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;

import ch.qos.logback.classic.Logger;

// TODO: Split REST, MQTT and MongoDB
public class UploadHelper extends AsyncTask<Record, Integer, Long> {

    private static final String TAG = UploadHelper.class.getSimpleName();
    private static final SimpleDateFormat DATE_FORMAT = new SimpleDateFormat("MM/dd/yyyy hh:mm:ss aa", Locale.getDefault());
    private static final int SOCKET_TIMEOUT = 60 * 1000;
    private static final int CONNECTION_TIMEOUT = 30 * 1000;
    public static Boolean isModifyingRecords = false;

    private static final String DEVICE_NAME = "Medtronic_CGM";

    Context context;
    private Logger log = (Logger) LoggerFactory.getLogger(MedtronicReader.class.getName());
    private SharedPreferences settings = null;// common application preferences
    private ArrayList<Messenger> mClients;

    public UploadHelper(Context context) {
        this.context = context;
        this.mClients = null;
        settings = context.getSharedPreferences(MedtronicConstants.PREFS_NAME, 0);
    }

    public UploadHelper(Context context, ArrayList<Messenger> mClients) {
        this(context);
        this.mClients = mClients;
    }

    /**
     * Sends a message to be printed in the display (DEBUG)
     *
     * @param valuetosend
     * @param clear,      if true, the display is cleared before printing "valuetosend"
     */
    private void sendMessageToUI(String valuetosend, boolean clear) {
        if (mClients != null && mClients.size() > 0) {
            for (int i = mClients.size() - 1; i >= 0; i--) {
                try {
                    Message mSend = null;
                    if (clear) {
                        mSend = Message.obtain(null, MedtronicConstants.MSG_MEDTRONIC_CGM_CLEAR_DISPLAY);
                        mClients.get(i).send(mSend);
                        continue;
                    }
                    mSend = Message.obtain(null, MedtronicConstants.MSG_MEDTRONIC_CGM_MESSAGE_RECEIVED);
                    Bundle b = new Bundle();
                    b.putString("data", valuetosend);
                    mSend.setData(b);
                    mClients.get(i).send(mSend);

                } catch (RemoteException e) {
                    // The client is dead. Remove it from the list; we are going through the list from back to front so this is safe to do inside the loop.
                    mClients.remove(i);
                }
            }
        }
    }

    /**
     * doInBackground
     */
    protected Long doInBackground(Record[] records) {

        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this.context);
        Boolean enableRESTUpload = prefs.getBoolean("EnableRESTUpload", false);
        Boolean enableMQTTUpload = prefs.getBoolean("EnableMQTTUpload", false);
        Boolean enableMongoUpload = prefs.getBoolean("EnableMongoUpload", false);

        try {

            long start = System.currentTimeMillis();
            if (enableMQTTUpload) {
                Log.i(TAG, String.format("Starting upload of %s record using MQTT", records.length));
                log.info(String.format("Starting upload of %s record using MQTT", records.length));
                doMQTTUpload(prefs, records);
                Log.i(TAG, String.format("Finished upload of %s record using a MQTT in %s ms", records.length, System.currentTimeMillis() - start));
                log.info(String.format("Finished upload of %s record using a MQTT in %s ms", records.length, System.currentTimeMillis() - start));
            } else if (enableRESTUpload) {
                Log.i(TAG, String.format("Starting upload of %s record using a REST API", records.length));
                log.info(String.format("Starting upload of %s record using a REST API", records.length));
                doRESTUpload(prefs, records);
                Log.i(TAG, String.format("Finished upload of %s record using a REST API in %s ms", records.length, System.currentTimeMillis() - start));
                log.info(String.format("Finished upload of %s record using a REST API in %s ms", records.length, System.currentTimeMillis() - start));
            } else if (enableMongoUpload) {
                Log.i(TAG, String.format("Starting upload of %s record using Mongo", records.length));
                log.info(String.format("Starting upload of %s record using Mongo, mongo location: " + settings.getString(MongoConnection.MONGO_URI_KEY, "UNKNOWN"), records.length));
                doMongoUpload(prefs, records);
                Log.i(TAG, String.format("Finished upload of %s record using a Mongo in %s ms", records.length, System.currentTimeMillis() - start));
                log.info(String.format("Finished upload of %s record using a Mongo in %s ms", records.length, System.currentTimeMillis() - start));
            }

        } catch (MongoException e){
            Log.e(TAG, "Mongo-related error: " + ExceptionUtils.getStackTrace(e));
            log.error("Mongo-related error: " + ExceptionUtils.getStackTrace(e));
        } catch (Exception e) {
            Log.e(TAG, "Unable to upload record: " + ExceptionUtils.getStackTrace(e));
            log.error("Unable to upload record: " + ExceptionUtils.getStackTrace(e));
        }


        return 1L;
    }

    private void doMQTTUpload(SharedPreferences prefs, Record[] records) {
        String baseURLSetting = prefs.getString("MQTT Broker location", "");
        try {
            doMQTTUploadTo(baseURLSetting, records);
        } catch (Exception e) {
            Log.e(TAG, "Unable to do REST API Upload to: " + baseURLSetting, e);
            log.error("Unable to do REST API Upload to: " + baseURLSetting, e);
        }
    }

    private void doMQTTUploadTo(String baseURI, Record[] records) {

        MQTT mqtt = new MQTT();
        try {
            mqtt.setHost(baseURI);
            FutureConnection connection = mqtt.futureConnection();

            for (Record record : records) {

                final String topic;
                if (record instanceof GlucometerRecord) {

                    topic = "/downloads/protobuf";
                } else if (record instanceof MedtronicPumpRecord) {
                    topic = "/downloads/protobuf";
                } else {
                    topic = "/downloads/protobuf";
                }

                JSONObject json = new JSONObject();
                try {
                    populateV1APIEntry(json, record);
                } catch (Exception e) {
                    Log.w(TAG, "Unable to populate entry: " + ExceptionUtils.getStackTrace(e));
                    continue;
                }

                String jsonString = json.toString();

                Log.i(TAG, "JSON: " + jsonString);
                log.info("JSON: " + jsonString);

                try {
                    connection.publish(topic, jsonString.getBytes(), QoS.AT_LEAST_ONCE, false);

                } catch (Exception e) {
                    Log.w(TAG, "Unable to publish to MQTT: " + ExceptionUtils.getStackTrace(e));
                    log.warn("Unable to publish to MQTT: " + ExceptionUtils.getStackTrace(e));
                }
            }
        } catch (URISyntaxException e) {
            String err = "Unable to send the data using MQTT: " + ExceptionUtils.getStackTrace(e);
            Log.e(TAG, err);
            log.error(err);
        }
    }

    protected void onPostExecute(Long result) {
        super.onPostExecute(result);
        Log.i(TAG, "Post execute, Result: " + result + ", Status: FINISHED");
        log.info("Post execute, Result: " + result + ", Status: FINISHED");

    }

    private void doRESTUpload(SharedPreferences prefs, Record[] records) {
        String baseURLSettings = prefs.getString("API Base URL", "");
        ArrayList<String> baseURIs = new ArrayList<String>();

        try {
            for (String baseURLSetting : baseURLSettings.split(" ")) {
                String baseURL = baseURLSetting.trim();
                if (baseURL.isEmpty()) {
                    baseURIs.add(baseURL + (baseURL.endsWith("/") ? "" : "/"));
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Unable to process API Base URL setting: " + baseURLSettings, e);
            log.error("Unable to process API Base URL setting: " + baseURLSettings, e);
            return;
        }

        for (String baseURI : baseURIs) {
            try {
                doRESTUploadTo(baseURI, records);
            } catch (Exception e) {
                Log.e(TAG, "Unable to do REST API Upload to: " + baseURI, e);
                log.error("Unable to do REST API Upload to: " + baseURI, e);
            }
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void doRESTUploadTo(String baseURI, Record[] records) {

        try {
            int apiVersion = 0;
            if (baseURI.endsWith("/v1/")) apiVersion = 1;

            String baseURL = null;
            String secret = null;
            String[] uriParts = baseURI.split("@");

            if (uriParts.length == 1 && apiVersion == 0) {
                baseURL = uriParts[0];
            } else if (uriParts.length == 2 && apiVersion > 0) {
                secret = uriParts[0];
                baseURL = uriParts[1];
            } else {
                throw new Exception(String.format("Unexpected baseURI: %s, uriParts.length: %s, apiVersion: %s", baseURI, uriParts.length, apiVersion));
            }


            HttpParams params = new BasicHttpParams();
            HttpConnectionParams.setSoTimeout(params, SOCKET_TIMEOUT);
            HttpConnectionParams.setConnectionTimeout(params, CONNECTION_TIMEOUT);

            DefaultHttpClient httpclient = new DefaultHttpClient(params);

            for (Record record : records) {
                String postURL = baseURL;
                if (record instanceof GlucometerRecord) {
                    postURL += "gdentries";
                } else if (record instanceof MedtronicPumpRecord) {
                    postURL += "deviceentries";
                } else {

                    postURL += "entries";
                }
                Log.i(TAG, "postURL: " + postURL);
                log.info("postURL: " + postURL);
                HttpPost post = new HttpPost(postURL);

                if (apiVersion > 0) {
                    if (secret == null || secret.isEmpty()) {
                        MessageDigest digest = MessageDigest.getInstance("SHA-1");
                        byte[] bytes = secret.getBytes("UTF-8");
                        digest.update(bytes, 0, bytes.length);
                        bytes = digest.digest();
                        StringBuilder sb = new StringBuilder(bytes.length * 2);
                        for (byte b : bytes) {
                            sb.append(String.format("%02x", b & 0xff));
                        }
                        String token = sb.toString();
                        post.setHeader("api-secret", token);
                    }
                }

                JSONObject json = new JSONObject();

                try {
                    populateV1APIEntry(json, record);
                } catch (Exception e) {
                    Log.w(TAG, "Unable to populate entry, apiVersion: " + apiVersion, e);
                    log.warn("Unable to populate entry, apiVersion: " + apiVersion, e);
                    continue;
                }

                String jsonString = json.toString();

                Log.i(TAG, "JSON: " + jsonString);
                log.info("JSON: " + jsonString);

                try {
                    StringEntity se = new StringEntity(jsonString);
                    post.setEntity(se);
                    post.setHeader("Accept", "application/json");
                    post.setHeader("Content-type", "application/json");

                    ResponseHandler responseHandler = new BasicResponseHandler();
                    httpclient.execute(post, responseHandler);
                } catch (Exception e) {
                    Log.w(TAG, "Unable to post data to: '" + post.getURI().toString() + "' " + ExceptionUtils.getStackTrace(e));
                    log.warn("Unable to post data to: '" + post.getURI().toString() + "' " + ExceptionUtils.getStackTrace(e));
                }
            }
            postDeviceStatus(baseURL, httpclient);
        } catch (Exception e) {
            Log.e(TAG, "Unable to post data", e);
            log.error("Unable to post data", e);
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void postDeviceStatus(String baseURL, DefaultHttpClient httpclient) throws Exception {
        String devicestatusURL = baseURL + "devicestatus";
        Log.i(TAG, "devicestatusURL: " + devicestatusURL);
        log.info("devicestatusURL: " + devicestatusURL);

        JSONObject json = new JSONObject();
        json.put("uploaderBattery", MedtronicActivity.batLevel);
        String jsonString = json.toString();

        HttpPost post = new HttpPost(devicestatusURL);
        StringEntity se = new StringEntity(jsonString);
        post.setEntity(se);
        post.setHeader("Accept", "application/json");
        post.setHeader("Content-type", "application/json");

        ResponseHandler responseHandler = new BasicResponseHandler();
        httpclient.execute(post, responseHandler);
    }

    private void populateV1APIEntry(JSONObject json, Record oRecord) throws Exception {
        Date date = DATE_FORMAT.parse(oRecord.displayTime);
        json.put("date", date.getTime());

        if (oRecord instanceof GlucometerRecord) {
            json.put("gdValue", ((GlucometerRecord) oRecord).numGlucometerValue);
        } else if (oRecord instanceof MedtronicSensorRecord) {
            MedtronicSensorRecord record = (MedtronicSensorRecord) oRecord;
            json.put("device", DEVICE_NAME);
            json.put("sgv", Integer.parseInt(record.bGValue));
            json.put("direction", record.trend);
            json.put("isig", record.isig);
            json.put("calibrationFactor", record.calibrationFactor);
            json.put("calibrationStatus", record.calibrationStatus);
            json.put("unfilteredGlucose", record.unfilteredGlucose);
            json.put("isCalibrating", record.isCalibrating);

        } else if (oRecord instanceof MedtronicPumpRecord) {
            MedtronicPumpRecord pumpRecord = (MedtronicPumpRecord) oRecord;
            json.put("name", pumpRecord.getDeviceName());
            json.put("deviceId", pumpRecord.deviceId);
            json.put("insulinLeft", pumpRecord.insulinLeft);
            json.put("alarm", pumpRecord.alarm);
            json.put("status", pumpRecord.status);
            json.put("temporaryBasal", pumpRecord.temporaryBasal);
            json.put("batteryStatus", pumpRecord.batteryStatus);
            json.put("batteryVoltage", pumpRecord.batteryVoltage);
            json.put("isWarmingUp", pumpRecord.isWarmingUp);
        }

    }


    private void doMongoUpload(SharedPreferences prefs, Record[] records) {

        Log.i(TAG, "The number of Records being sent to MongoDB is " + records.length);
        log.info("The number of Records being sent to MongoDB is " + records.length);
        Boolean isWarmingUp = false;
        for (Record oRecord : records) {
            try {
                BasicDBObject testData = new BasicDBObject();
                Date date = DATE_FORMAT.parse(oRecord.displayTime);
                testData.put("date", date.getTime());
                testData.put("dateString", oRecord.displayTime);

                if (oRecord instanceof MedtronicSensorRecord) {
                    MedtronicSensorRecord record = (MedtronicSensorRecord) oRecord;
                    // make db object
                    testData.put("device", DEVICE_NAME);
                    testData.put("sgv", record.bGValue);
                    testData.put("type", "sgv");
                    testData.put("direction", record.trend);

                    testData.put("isig", record.isig);
                    testData.put("calibrationFactor", record.calibrationFactor);
                    testData.put("calibrationStatus", record.calibrationStatus);
                    testData.put("unfilteredGlucose", record.unfilteredGlucose);
                    testData.put("isCalibrating", record.isCalibrating);
                    log.info("Testing isCheckedWUP -->", prefs.getBoolean("isCheckedWUP", false));
                    if (!prefs.getBoolean("isCheckedWUP", false)) {
                        log.info("Testing isCheckedWUP -->GET INTO");
                        HashMap<String, Object> filter = new HashMap<String, Object>();
                        filter.put("deviceId", prefs.getString("medtronic_cgm_id", ""));

                        DBObject previousRecord = MongoConnection.getInstance(prefs).getDevice(filter);

                        if (previousRecord != null) {
                            previousRecord.put("date", testData.get("date"));
                            previousRecord.put("dateString", testData.get("dateString"));
                            JSONObject job = new JSONObject(previousRecord.toMap());
                            isWarmingUp = job.getBoolean("isWarmingUp");
                            log.info("Testing isCheckedWUP -->NEXT -->ISWUP?? " + isWarmingUp);

                            if (isWarmingUp) {
                                log.info("Uploading a DeviceRecord");
                                MongoConnection.getInstance(prefs).writeDeviceCollection(previousRecord);
                                prefs.edit().putBoolean("isCheckedWUP", true).commit();
                            }
                        }
                    }
                    log.info("Uploading a  Record");

                    MongoConnection.getInstance(prefs).writeEntriesCollection(testData);
                } else if (oRecord instanceof GlucometerRecord) {
                    GlucometerRecord gdRecord = (GlucometerRecord) oRecord;

                    testData.put("device", DEVICE_NAME);
                    testData.put("mbg", gdRecord.numGlucometerValue);
                    log.info("Uploading a Glucometer Record!");

                    MongoConnection.getInstance(prefs).writeGlucoseCollection(testData);
                } else if (oRecord instanceof MedtronicPumpRecord) {
                    MedtronicPumpRecord pumpRecord = (MedtronicPumpRecord) oRecord;
                    HashMap<String, Object> filter = new HashMap<String, Object>();
                    filter.put("deviceId", pumpRecord.deviceId);

                    DBObject previousRecord = MongoConnection.getInstance(prefs).getDevice(filter);

                    if (previousRecord == null) {
                        previousRecord.put("date", testData.get("date"));
                        previousRecord.put("dateString", testData.get("dateString"));
                        pumpRecord.mergeCurrentWithDBObject(previousRecord);
                        log.info("Uploading a DeviceRecord");
                        MongoConnection.getInstance(prefs).writeDeviceCollection(previousRecord);
                    } else {
                        testData.put("name", pumpRecord.getDeviceName());
                        testData.put("deviceId", pumpRecord.deviceId);
                        testData.put("insulinLeft", pumpRecord.insulinLeft);
                        testData.put("alarm", pumpRecord.alarm);
                        testData.put("status", pumpRecord.status);
                        testData.put("temporaryBasal", pumpRecord.temporaryBasal);
                        testData.put("batteryStatus", pumpRecord.batteryStatus);
                        testData.put("batteryVoltage", pumpRecord.batteryVoltage);
                        testData.put("isWarmingUp", pumpRecord.isWarmingUp);
                        log.info("Uploading a DeviceRecord");

                        MongoConnection.getInstance(prefs).writeDeviceCollection(testData);

                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "Unable to upload data to mongo in loop " + ExceptionUtils.getStackTrace(e));
                log.warn("Unable to upload data to mongo in loop" + ExceptionUtils.getStackTrace(e));
            }
        }

        //Uploading devicestatus
        boolean update = true;
        if (prefs.contains("lastBatteryUpdated")) {
            long lastTimeUpdated = prefs.getLong("lastBatteryUpdated", 0);
            if (lastTimeUpdated > 0) {
                long current = System.currentTimeMillis();
                long diff = current - lastTimeUpdated;
                if (diff < MedtronicConstants.TIME_5_MIN_IN_MS)
                    update = false;
                else {
                    SharedPreferences.Editor editor = prefs.edit();
                    editor.putLong("lastBatteryUpdated", current);
                    editor.commit();
                }
            } else {
                SharedPreferences.Editor editor = prefs.edit();
                editor.putLong("lastBatteryUpdated", System.currentTimeMillis());
                editor.commit();
            }
        } else {
            SharedPreferences.Editor editor = prefs.edit();
            editor.putLong("lastBatteryUpdated", System.currentTimeMillis());
            editor.commit();
        }
        if (update) {
            BasicDBObject devicestatus = new BasicDBObject();
            devicestatus.put("uploaderBattery", MedtronicActivity.batLevel);
            devicestatus.put("created_at", new Date());
            log.debug("Update Battery");

            MongoConnection.getInstance(prefs).writeDeviceStatusCollection(devicestatus);
        }
    }
}
