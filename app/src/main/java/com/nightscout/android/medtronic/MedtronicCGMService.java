package com.nightscout.android.medtronic;

import android.app.NotificationManager;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.SharedPreferences.OnSharedPreferenceChangeListener;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.wifi.WifiManager;
import android.os.AsyncTask.Status;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.os.StrictMode;
import android.preference.PreferenceManager;
import android.util.Log;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.mongodb.DBCollection;
import com.nightscout.android.USB.HexDump;
import com.nightscout.android.USB.USBPower;
import com.nightscout.android.upload.Record;
import com.nightscout.android.upload.UploadHelper;
import com.physicaloid.lib.Physicaloid;
import com.physicaloid.lib.usb.driver.uart.ReadLisener;
import com.physicaloid.lib.usb.driver.uart.UartConfig;

import org.apache.commons.lang3.exception.ExceptionUtils;
import org.json.JSONArray;
import org.json.JSONException;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;

/**
 * This class is the service responsible of manage correctly the interface with the enlite.
 *
 * @author lmmarguenda
 */
public class MedtronicCGMService extends Service implements
        OnSharedPreferenceChangeListener {

    private static final String TAG = MedtronicCGMService.class.getSimpleName();
    private final Messenger mMessenger = new Messenger(new IncomingHandler()); // Target we publish for clients to send messages to IncomingHandler.
    public UsbManager mUsbManager;
    private Logger log = (Logger) LoggerFactory.getLogger(MedtronicReader.class.getName());
    private NotificationManager NM;
    private boolean listenerAttached = false;
    private UploadHelper uploader;

    private DBCollection dsCollection = null;
    private Physicaloid mSerial;
    private Handler mHandlerCheckSerial = new Handler();// This handler runs readAndUpload Runnable which checks the USB device and NET connection.
    private Handler mHandler2CheckDevice = new Handler(); // this Handler is used to read the device info each thirty minutes
    private Handler mHandler3ActivatePump = new Handler();// this Handler is used to execute commands after changing the pump ID
    private Handler mHandlerReadFromHistoric = new Handler();// this Handler is used to read data from pump log file.
    private Handler mHandlerRead = new Handler();// this Handler is used to read and parse the messages received from the USB, It is only activated after a Read.
    private Handler mHandlerProcessRead = new Handler();// this Handler is used to process the messages parsed.
    private Handler mHandlerReviewParameters = new Handler();
    private Handler mHandlerCheckLastRead = new Handler();
    private boolean mHandlerActive = false;
    private SharedPreferences settings = null;// Here I store the settings needed to store the status of the service.
    private Runnable checker = null;
    private WifiManager wifiManager;
    private MedtronicReader medtronicReader = null;//Medtronic Reader
    private BufferedMessagesProcessor processBufferedMessages = new BufferedMessagesProcessor();// Runnable which manages the message processing;
    private ArrayList<Messenger> mClients = new ArrayList<Messenger>(); // clients subscribed;
    private CommandSenderThread cMThread = null;// Thread to process a set of commands
    private SharedPreferences prefs = null;// common application preferences
    private int calibrationSelected = MedtronicConstants.CALIBRATION_GLUCOMETER;//calibration Selected
    private Handler mHandlerSensorCalibration = new Handler();// this Handler is used to ask for SensorCalibration.
    private Handler mHandlerReloadLost = new Handler();// this Handler is used to upload records which upload failed due to a network error.
    private long pumpPeriod = MedtronicConstants.TIME_30_MIN_IN_MS;
    private boolean connectedSent = false;
    private boolean isDestroying = false;
    private Boolean isUploading = false;

    private HistoricGetterThread hGetter = null;//Medtronic Historic Log retriever
    private long historicLogPeriod = 0;
    private ReadByListener readByListener = new ReadByListener();//Listener to read data
    /**
     * Listener which throws a handler that manages the reading from the serial buffer, when a read happens
     */
    private ReadLisener readListener = new ReadLisener() {

        @Override
        public void onRead(int size) {
            synchronized (readByListener.size) {
                if (readByListener.size > -1)
                    readByListener.size += size;
                else
                    readByListener.size = size;
            }
            mHandlerRead.post(readByListener);

        }

    };
    /**
     * class This class process all the messages received after being correctly
     * parsed.
     */
    private Runnable reloadLostRecords = new Runnable() {
        public void run() {
            log.info("Reloading Lost Records from medtronic service");

            JSONArray recordsNotUploadedJson;
            JSONArray recordsNotUploaded;
            try {
                recordsNotUploaded = new JSONArray(settings.getString("recordsNotUploaded", "[]"));
                recordsNotUploadedJson = new JSONArray(settings.getString("recordsNotUploadedJson", "[]"));
                synchronized (mHandlerReloadLost) {
                    if (isOnline()) {
                        log.info("reloadnotupload is online " + recordsNotUploaded.length() + " -> " + recordsNotUploadedJson.length() + " " + !isDestroying);
                        if ((recordsNotUploaded.length() > 0 || recordsNotUploadedJson.length() > 0) && !isDestroying) {
                            log.info("to upload old records");
                            uploader = new UploadHelper(getApplicationContext());

                            Record[] params = new Record[0];
                            log.info("calling uploader");
                            uploader.execute(params);
                            log.info("uploader called");
                        }
                    } else {
                        if (!isDestroying)
                            mHandlerReloadLost.postDelayed(reloadLostRecords, 60000);
                    }
                }

            } catch (JSONException e) {
                log.error("Error Reloading Lost Records " + ExceptionUtils.getStackTrace(e));
                e.printStackTrace();
            }

            if (!isDestroying) {
                mHandlerReloadLost.postDelayed(reloadLostRecords, 60000);
            }
            log.info("Lost records reloaded from medtronic service");
        }
    };
    /**
     * Runnable,
     * If there is a serial device connected.
     * This process wakes it up, and gather some information from it to upload to the cloud.
     */
    private Runnable activateNewPump = new Runnable() {
        public void run() {
            boolean executed = false;
            try {
                synchronized (mSerial) {

                    if (mSerial.isOpened()) {
                        synchronized (medtronicReader.processingCommand) {
                            if (medtronicReader.processingCommand) {
                                while (medtronicReader.processingCommand) {
                                    if (medtronicReader.processingCommand) {
                                        //if (pumpPeriod > -1){
                                        mHandler3ActivatePump.postDelayed(activateNewPump,
                                                MedtronicConstants.TIMEOUT);
                                        //}
                                        return;
                                    }
                                    medtronicReader.processingCommand = true;
                                }
                            } else {
                                medtronicReader.processingCommand = true;
                            }
                        }
                        executed = true;
                        byte[] initProcess = {
                                MedtronicConstants.MEDTRONIC_WAKE_UP,
                                MedtronicConstants.MEDTRONIC_GET_REMOTE_CONTROL_IDS,
                                MedtronicConstants.MEDTRONIC_GET_PARADIGM_LINK_IDS,
                                MedtronicConstants.MEDTRONIC_GET_SENSORID};

                        cMThread = new CommandSenderThread(medtronicReader,
                                medtronicReader.idPump, mSerial, mHandler2CheckDevice);
                        if (hGetter == null) {
                            hGetter = new HistoricGetterThread(mClients, medtronicReader,
                                    medtronicReader.idPump, mSerial, mHandlerReadFromHistoric);

                        }
                        medtronicReader.hGetter = hGetter;
                        cMThread.setCommandList(initProcess);
                        cMThread.setmClients(mClients);
                        mHandler2CheckDevice.post(cMThread);
                        SharedPreferences.Editor editor = prefs.edit();
                        editor.putLong("lastPumpAwake", System.currentTimeMillis());
                        editor.commit();
                    }
                }
            } catch (Exception e) {
                sendMessageToUI(ExceptionUtils.getStackTrace(e), false);
                synchronized (medtronicReader.processingCommand) {
                    medtronicReader.processingCommand = false;
                }
            } finally {
                if (!executed) {
                    mHandler3ActivatePump.removeCallbacks(activateNewPump);
                    mHandler3ActivatePump.postDelayed(activateNewPump, MedtronicConstants.TIME_5_MIN_IN_MS);
                }
            }

        }
    };
    /**
     * Check last Historic Log read. (This will not be possible in newer Medtronic Pumps)
     */
    private Runnable checkLastRead = new Runnable() {
        public void run() {
            if (prefs.getString("glucSrcTypes", "1").equals("3")) {

                String type = prefs.getString("historicMixPeriod", "1");
                if ("2".equalsIgnoreCase(type))
                    historicLogPeriod = MedtronicConstants.TIME_15_MIN_IN_MS;
                else if ("3".equalsIgnoreCase(type))
                    historicLogPeriod = MedtronicConstants.TIME_20_MIN_IN_MS;
                else if ("4".equalsIgnoreCase(type))
                    historicLogPeriod = MedtronicConstants.TIME_20_MIN_IN_MS + MedtronicConstants.TIME_5_MIN_IN_MS;
                else if ("5".equalsIgnoreCase(type))
                    historicLogPeriod = MedtronicConstants.TIME_30_MIN_IN_MS;
                else if ("6".equalsIgnoreCase(type))
                    historicLogPeriod = MedtronicConstants.TIME_30_MIN_IN_MS + MedtronicConstants.TIME_5_MIN_IN_MS;
                else if ("7".equalsIgnoreCase(type))
                    historicLogPeriod = MedtronicConstants.TIME_30_MIN_IN_MS + MedtronicConstants.TIME_10_MIN_IN_MS;
                else if ("8".equalsIgnoreCase(type))
                    historicLogPeriod = MedtronicConstants.TIME_30_MIN_IN_MS + MedtronicConstants.TIME_15_MIN_IN_MS;
                else if ("9".equalsIgnoreCase(type))
                    historicLogPeriod = MedtronicConstants.TIME_30_MIN_IN_MS + MedtronicConstants.TIME_20_MIN_IN_MS;
                else if ("10".equalsIgnoreCase(type))
                    historicLogPeriod = MedtronicConstants.TIME_60_MIN_IN_MS - MedtronicConstants.TIME_5_MIN_IN_MS;
                else if ("11".equalsIgnoreCase(type))
                    historicLogPeriod = MedtronicConstants.TIME_60_MIN_IN_MS;
                else
                    historicLogPeriod = MedtronicConstants.TIME_10_MIN_IN_MS;

                if (medtronicReader != null && medtronicReader.lastSensorValueDate > 0) {
                    if ((System.currentTimeMillis() - medtronicReader.lastSensorValueDate) >= historicLogPeriod) {
                        if (settings.getLong("lastHistoricRead", 0) != 0) {
                            if ((System.currentTimeMillis() - settings.getLong("lastHistoricRead", 0)) >= historicLogPeriod) {
                                mHandlerReadFromHistoric.post(readDataFromHistoric);
                                return;
                            }
                        } else {
                            mHandlerReadFromHistoric.post(readDataFromHistoric);
                            return;
                        }
                    }
                } else {
                    if (settings.getLong("lastHistoricRead", 0) != 0) {
                        if ((System.currentTimeMillis() - settings.getLong("lastHistoricRead", 0)) >= historicLogPeriod) {
                            mHandlerReadFromHistoric.post(readDataFromHistoric);
                            return;
                        }
                    } else {
                        mHandlerReadFromHistoric.post(readDataFromHistoric);
                        return;
                    }
                }
                mHandlerCheckLastRead.postDelayed(checkLastRead, MedtronicConstants.TIME_10_MIN_IN_MS);
            }

        }
    };
    /**
     * Runnable,
     * If my value source is Medtronic Historic Log (not available in newer Pump versions).
     * This process wakes it up, and retrieve last historic pages from it to upload last records to the cloud.
     */
    private Runnable readDataFromHistoric = new Runnable() {
        public void run() {
            boolean bAfterPeriod = false;
            try {
                log.info("Read data from Historic");
                synchronized (mSerial) {
                    if (mSerial.isOpened()) {
                        log.info("mserial open");
                        synchronized (medtronicReader.processingCommand) {
                            if (medtronicReader.processingCommand) {
                                while (medtronicReader.processingCommand) {
                                    if (medtronicReader.processingCommand) {
                                        if (!prefs.getString("glucSrcTypes", "1").equals("1")) {
                                            mHandlerReadFromHistoric.postDelayed(readDataFromHistoric,
                                                    MedtronicConstants.TIMEOUT);
                                            log.info("TIMEOUT");
                                        }
                                        bAfterPeriod = true;
                                        return;

                                    }
                                    medtronicReader.processingCommand = true;
                                }
                            } else {
                                medtronicReader.processingCommand = true;
                            }
                        }


                        if (prefs.getString("glucSrcTypes", "1").equals("2")) {
                            log.info("EQUALS 2");
                            medtronicReader.mHandlerCheckLastRead = null;
                            medtronicReader.checkLastRead = null;
                            String type = prefs.getString("historicPeriod", "1");
                            if ("2".equalsIgnoreCase(type))
                                historicLogPeriod = MedtronicConstants.TIME_10_MIN_IN_MS;
                            else if ("3".equalsIgnoreCase(type))
                                historicLogPeriod = MedtronicConstants.TIME_15_MIN_IN_MS;
                            else if ("4".equalsIgnoreCase(type))
                                historicLogPeriod = MedtronicConstants.TIME_15_MIN_IN_MS;
                            else
                                historicLogPeriod = MedtronicConstants.TIME_5_MIN_IN_MS;

                            if (settings.getLong("lastHistoricRead", 0) != 0) {
                                log.info("PREVIOUS READ");
                                if ((System.currentTimeMillis() - settings.getLong("lastHistoricRead", 0)) >= historicLogPeriod) {
                                    if (hGetter == null) {
                                        hGetter = new HistoricGetterThread(mClients, medtronicReader,
                                                medtronicReader.idPump, mSerial, mHandlerReadFromHistoric);

                                    } else {
                                        hGetter.init();
                                        synchronized (medtronicReader.processingCommand) {
                                            medtronicReader.processingCommand = true;
                                        }
                                        medtronicReader.historicPageIndex = -1;
                                        medtronicReader.historicPageShift = 0;
                                        medtronicReader.datalog = new DataLog();
                                    }
                                    medtronicReader.hGetter = hGetter;
                                    log.info("SEND GETTER!!!!!");
                                    mHandlerReadFromHistoric.post(hGetter);
                                    SharedPreferences.Editor editor = settings.edit();
                                    editor.putLong("lastHistoricRead", System.currentTimeMillis());
                                    editor.commit();
                                } else {
                                    log.info("Read after delay");
                                    bAfterPeriod = true;
                                    mHandlerReadFromHistoric.postDelayed(readDataFromHistoric, historicLogPeriod);
                                    synchronized (medtronicReader.processingCommand) {
                                        medtronicReader.processingCommand = false;
                                    }
                                    return;
                                }
                            } else {
                                log.info("OTHER ELSE");
                                if (hGetter == null) {
                                    hGetter = new HistoricGetterThread(mClients, medtronicReader,
                                            medtronicReader.idPump, mSerial, mHandlerReadFromHistoric);

                                } else {
                                    hGetter.init();
                                    synchronized (medtronicReader.processingCommand) {
                                        medtronicReader.processingCommand = true;
                                    }
                                    medtronicReader.historicPageIndex = -1;
                                    medtronicReader.historicPageShift = 0;
                                    medtronicReader.datalog = new DataLog();
                                }
                                medtronicReader.hGetter = hGetter;
                                log.info("SEND GETTER!!!!!");
                                mHandlerReadFromHistoric.post(hGetter);
                                SharedPreferences.Editor editor = settings.edit();
                                editor.putLong("lastHistoricRead", System.currentTimeMillis());
                                editor.commit();
                            }

                        } else if (prefs.getString("glucSrcTypes", "1").equals("3")) {
                            medtronicReader.mHandlerCheckLastRead = mHandlerCheckLastRead;
                            medtronicReader.checkLastRead = checkLastRead;
                            if (hGetter == null) {
                                hGetter = new HistoricGetterThread(mClients, medtronicReader,
                                        medtronicReader.idPump, mSerial, mHandlerReadFromHistoric);

                            } else {
                                hGetter.init();
                                synchronized (medtronicReader.processingCommand) {
                                    medtronicReader.processingCommand = true;
                                }
                                medtronicReader.historicPageIndex = -1;
                                medtronicReader.historicPageShift = 0;
                                medtronicReader.datalog = new DataLog();
                            }
                            medtronicReader.hGetter = hGetter;
                            mHandlerReadFromHistoric.post(hGetter);
                            log.info("SEND GETTER!!!!!");
                            mHandlerCheckLastRead.postDelayed(checkLastRead, MedtronicConstants.TIME_30_MIN_IN_MS);
                        }


                    }
                }
            } catch (Exception e) {
                sendMessageToUI(ExceptionUtils.getStackTrace(e), false);
                synchronized (medtronicReader.processingCommand) {
                    medtronicReader.processingCommand = false;
                }
            } finally {
                log.info("Executing read_Historic finally");
                if (prefs.getString("glucSrcTypes", "1").equals("2")) {

                    String type = prefs.getString("historicPeriod", "1");
                    if ("2".equalsIgnoreCase(type))
                        historicLogPeriod = MedtronicConstants.TIME_10_MIN_IN_MS;
                    else if ("3".equalsIgnoreCase(type))
                        historicLogPeriod = MedtronicConstants.TIME_15_MIN_IN_MS;
                    else if ("4".equalsIgnoreCase(type))
                        historicLogPeriod = MedtronicConstants.TIME_15_MIN_IN_MS;
                    else
                        historicLogPeriod = MedtronicConstants.TIME_5_MIN_IN_MS;

                    if (settings.getLong("lastHistoricRead", 0) != 0) {
                        if ((System.currentTimeMillis() - settings.getLong("lastHistoricRead", 0)) >= historicLogPeriod) {
                            SharedPreferences.Editor editor = settings.edit();
                            editor.putLong("lastHistoricRead", System.currentTimeMillis());
                            editor.commit();
                            mHandlerReadFromHistoric.postDelayed(readDataFromHistoric, historicLogPeriod);
                        } else {
                            mHandlerReadFromHistoric.removeCallbacks(readDataFromHistoric);
                            mHandlerReadFromHistoric.postDelayed(readDataFromHistoric, historicLogPeriod);
                        }
                    } else {
                        mHandlerReadFromHistoric.post(readDataFromHistoric);
                        if (!bAfterPeriod) {
                            SharedPreferences.Editor editor = settings.edit();
                            editor.putLong("lastHistoricRead", System.currentTimeMillis());
                            editor.commit();
                        }

                    }

                }
            }

        }
    };
    /**
     * Runnable,
     * If there is a serial device connected.
     * This process wakes it up, asks for its calibration value.
     */
    private Runnable getCalibrationFromSensor = new Runnable() {
        public void run() {
            try {
                log.info("getting Calibration factor!!");
                synchronized (mSerial) {
                    log.info("mSerial synchronized!");
                    if (mSerial.isOpened()) {
                        log.info("mSerial open!!");
                        if (calibrationSelected == MedtronicConstants.CALIBRATION_SENSOR) {
                            synchronized (medtronicReader.processingCommand) {
                                if (medtronicReader.processingCommand) {
                                    while (medtronicReader.processingCommand) {
                                        if (medtronicReader.processingCommand) {
                                            log.info("is processing other commands wait 3 secs.");
                                            mHandlerSensorCalibration.postDelayed(getCalibrationFromSensor,
                                                    MedtronicConstants.TIMEOUT);
                                            return;
                                        }
                                        medtronicReader.processingCommand = true;
                                    }
                                } else {
                                    medtronicReader.processingCommand = true;
                                }
                            }
                            byte[] initProcess = {
                                    MedtronicConstants.MEDTRONIC_WAKE_UP,
                                    MedtronicConstants.MEDTRONIC_GET_CALIBRATION_FACTOR,
                                    MedtronicConstants.MEDTRONIC_GET_REMAINING_INSULIN,
                                    MedtronicConstants.MEDTRONIC_GET_BATTERY_STATUS};
                            log.info("get Cal Factor and other info!!");

                            //mHandler2CheckDevice.removeCallbacks(cMThread);
                            cMThread = new CommandSenderThread(medtronicReader,
                                    medtronicReader.idPump, mSerial, mHandler2CheckDevice);
                            cMThread.setCommandList(initProcess);
                            cMThread.setmClients(mClients);
                            if (hGetter == null) {
                                hGetter = new HistoricGetterThread(mClients, medtronicReader,
                                        medtronicReader.idPump, mSerial, mHandlerReadFromHistoric);

                            }
                            medtronicReader.hGetter = hGetter;
                            mHandler2CheckDevice.post(cMThread);
                        }
                    }
                }
            } catch (Exception e) {
                sendMessageToUI(ExceptionUtils.getStackTrace(e), false);
                synchronized (medtronicReader.processingCommand) {
                    medtronicReader.processingCommand = false;
                }
            } finally {
                if (pumpPeriod > -1) {
                    mHandlerSensorCalibration.removeCallbacks(getCalibrationFromSensor);
                    mHandlerSensorCalibration.postDelayed(getCalibrationFromSensor, pumpPeriod);
                }
            }

        }
    };
    /**
     * BroadcastReceiver when insert/remove the device USB plug into/from a USB port
     */
    BroadcastReceiver mUsbReceiver = new BroadcastReceiver() {
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(action)) {
                sendMessageDisconnectedToUI();
                closeUsbSerial();
            }
        }
    };
    /**
     * Runnable.
     * It checks that it is a serial device available, and there is Internet connection.
     * It also binds readByListener with the serial device and execute it the first time;
     */
    private Runnable readAndUpload = new Runnable() {
        public void run() {
            log.info("run readAndUpload");
            try {
                UsbManager usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);
                boolean hasPermission = false;
                for (final UsbDevice usbDevice : usbManager.getDeviceList()
                        .values()) {
                    if (!usbManager.hasPermission(usbDevice)) {
                        continue;
                    } else {
                        hasPermission = true;
                        // sendMessageConnectedToUI();
                    }
                }
                if (!hasPermission) {
                    synchronized (mHandlerCheckSerial) {
                        log.info("I have lost usb permission changing listener attached to false...");
                        listenerAttached = false;
                        mSerial.clearReadListener();
                        mHandlerRead.removeCallbacks(readByListener);
                        sendMessageDisconnectedToUI();
                        if (!mHandlerActive || isDestroying) {
                            log.info("destroy readAnd Upload " + mHandlerActive + " isDes " + isDestroying);
                            return;
                        }
                        mHandlerCheckSerial.removeCallbacks(readAndUpload);
                        mHandlerCheckSerial.postDelayed(readAndUpload, MedtronicConstants.FIVE_SECONDS__MS);
                        return;
                    }
                } else
                    sendMessageConnectedToUI();
                boolean connected = false;
                synchronized (mSerial) {
                    connected = isConnected();
                }
                if (connected) {
                    if (!isOnline())
                        sendErrorMessageToUI("NET connection error");
                    if (!listenerAttached) {
                        log.info("!listener attached readByListener triggered");
                        mSerial.clearReadListener();
                        mHandlerRead.removeCallbacks(readByListener);
                        mSerial.addReadListener(readListener);
                        mHandlerRead.post(readByListener);
                        listenerAttached = true;

                        if (calibrationSelected == MedtronicConstants.CALIBRATION_SENSOR) {
                            mHandlerSensorCalibration.removeCallbacks(getCalibrationFromSensor);
                            mHandlerSensorCalibration.postDelayed(getCalibrationFromSensor, MedtronicConstants.FIVE_SECONDS__MS);
                        }
                        /*long timeToAwake = pumpPeriod;
                        if (timeToAwake > -1){
							if (prefs != null){
								if (prefs.contains("wasProcessing") && prefs.getBoolean("wasProcessing", true))
									timeToAwake = 6*MedtronicConstants.FIVE_SECONDS__MS;
								else if (prefs.contains("lastPumpAwake")){
									long diff = System.currentTimeMillis() - prefs.getLong("lastPumpAwake", 0);
									if (diff >= pumpPeriod)
										timeToAwake = 6*MedtronicConstants.FIVE_SECONDS__MS;
									else
										timeToAwake = diff;
								}else
									timeToAwake = 6*MedtronicConstants.FIVE_SECONDS__MS;
							}else
								timeToAwake = 2*MedtronicConstants.FIVE_SECONDS__MS;
							if (timeToAwake < 0)
								timeToAwake = 2*MedtronicConstants.FIVE_SECONDS__MS;
							mHandler3ActivatePump.removeCallbacks(activateNewPump);
							mHandler3ActivatePump.postDelayed(activateNewPump, timeToAwake);
						}else
							mHandler3ActivatePump.post(activateNewPump);*/
                    }

                } else {

                    if (!connected)
                        openUsbSerial();
                    connected = isConnected();

                    if (!connected)
                        sendErrorMessageToUI("Receptor connection error");
                    else if (!isOnline())
                        sendErrorMessageToUI("NET connection error");
                    else {
                        sendMessageConnectedToUI();
                        sendMessageToUI("connected", false);
                    }
                }

            } catch (Exception e) {
                sendMessageToUI(ExceptionUtils.getStackTrace(e), false);

                Log.e(TAG, "Unable to read from receptor or upload", e);
                log.error("Unable to read from receptor or upload \n" + e.toString());
            }
            synchronized (mHandlerCheckSerial) {
                if (!mHandlerActive || isDestroying) {
                    log.info("destroy readAnd Upload2 " + mHandlerActive + " isDes " + isDestroying);
                    return;
                }
                mHandlerCheckSerial.removeCallbacks(readAndUpload);
                mHandlerCheckSerial.postDelayed(readAndUpload, MedtronicConstants.FIVE_SECONDS__MS);
            }
        }
    };

    @Override
    public IBinder onBind(Intent intent) {
        return mMessenger.getBinder();

    }

    /**
     * Sends a message to be printed in the display (DEBUG) or launches a pop-up message.
     *
     * @param valuetosend
     * @param clear,      if true, the display is cleared before printing "valuetosend"
     */
    private void sendMessageToUI(String valuetosend, boolean clear) {
        Log.i("medtronicCGMService", valuetosend);
        log.info("send Message To UI -> " + valuetosend);
        if (mClients != null && mClients.size() > 0) {
            for (int i = mClients.size() - 1; i >= 0; i--) {
                try {
                    Message mSend = null;
                    if (clear) {
                        mSend = Message
                                .obtain(null,
                                        MedtronicConstants.MSG_MEDTRONIC_CGM_CLEAR_DISPLAY);
                        mClients.get(i).send(mSend);
                        continue;
                    }
                    mSend = Message
                            .obtain(null,
                                    MedtronicConstants.MSG_MEDTRONIC_CGM_MESSAGE_RECEIVED);
                    Bundle b = new Bundle();
                    b.putString("data", valuetosend);
                    mSend.setData(b);
                    mClients.get(i).send(mSend);

                } catch (RemoteException e) {
                    // The client is dead. Remove it from the list; we are going
                    // through the list from back to front so this is safe to do
                    // inside the loop.
                    mClients.remove(i);
                }
            }
        } else {
            displayMessage(valuetosend);
        }
    }

    /**
     * Sends an error message to be printed in the display (DEBUG) if it is repeated, It is not printed again. If UI is not visible, It will launch a pop-up message.
     *
     * @param valuetosend
     */
    private void sendErrorMessageToUI(String valuetosend) {
        Log.e("medtronicCGMService", valuetosend);
        log.error("Send Error Message to UI " + valuetosend);
        if (mClients != null && mClients.size() > 0) {
            for (int i = mClients.size() - 1; i >= 0; i--) {
                try {
                    Message mSend = null;
                    mSend = Message
                            .obtain(null,
                                    MedtronicConstants.MSG_MEDTRONIC_CGM_ERROR_RECEIVED);
                    Bundle b = new Bundle();
                    b.putString("data", valuetosend);
                    mSend.setData(b);
                    mClients.get(i).send(mSend);

                } catch (RemoteException e) {
                    // The client is dead. Remove it from the list; we are going
                    // through the list from back to front so this is safe to do
                    // inside the loop.
                    mClients.remove(i);
                }
            }
        } else {
            displayMessage(valuetosend);
        }
    }

    /**
     * Sends message to the UI to indicate that the device is connected.
     */
    private void sendMessageConnectedToUI() {
        Log.i("medtronicCGMService", "Connected");
        if (!connectedSent) {
            log.info("Send Message Connected to UI");
            connectedSent = true;
        }
        for (int i = mClients.size() - 1; i >= 0; i--) {
            try {
                Message mSend = null;
                mSend = Message.obtain(null,
                        MedtronicConstants.MSG_MEDTRONIC_CGM_USB_GRANTED);
                mClients.get(i).send(mSend);

            } catch (RemoteException e) {
                // The client is dead. Remove it from the list; we are going
                // through the list from back to front so this is safe to do
                // inside the loop.
                mClients.remove(i);
            }
        }
    }

    /**
     * Sends message to the UI to indicate that a calibration has been made.
     */
    private void sendMessageCalibrationDoneToUI() {
        Log.i("medtronicCGMService", "Calibration done");
        for (int i = mClients.size() - 1; i >= 0; i--) {
            try {
                Message mSend = null;
                mSend = Message.obtain(null,
                        MedtronicConstants.MSG_MEDTRONIC_CALIBRATION_DONE);
                mClients.get(i).send(mSend);

            } catch (RemoteException e) {
                // The client is dead. Remove it from the list; we are going
                // through the list from back to front so this is safe to do
                // inside the loop.
                mClients.remove(i);
            }
        }
    }

    /**
     * Sends message to the UI to indicate that the device is disconnected.
     */
    private void sendMessageDisconnectedToUI() {
        Log.i("medtronicCGMService", "Disconnected");
        if (connectedSent)
            log.info("Send Message Disconnected to UI");
        connectedSent = false;
        for (int i = mClients.size() - 1; i >= 0; i--) {
            try {
                Message mSend = null;
                mSend = Message.obtain(null,
                        MedtronicConstants.MSG_MEDTRONIC_CGM_NO_PERMISSION);
                mClients.get(i).send(mSend);

            } catch (RemoteException e) {
                // The client is dead. Remove it from the list; we are going
                // through the list from back to front so this is safe to do
                // inside the loop.
                mClients.remove(i);
            }
        }
    }

    @Override
    public void onCreate() {
        //Debug.startMethodTracing();
        log.info("medCGM onCreate!");
        super.onCreate();

        if (android.os.Build.VERSION.SDK_INT > 9) {
            StrictMode.ThreadPolicy policy = new StrictMode.ThreadPolicy.Builder().permitAll().build();
            StrictMode.setThreadPolicy(policy);
        }

        settings = getSharedPreferences(MedtronicConstants.PREFS_NAME, 0);
        prefs = PreferenceManager.getDefaultSharedPreferences(getBaseContext());
        prefs.edit().remove("isCheckedWUP").commit();
        prefs.registerOnSharedPreferenceChangeListener(this);

        String level = prefs.getString("logLevel", "1");
        if ("2".equalsIgnoreCase(level))
            log.setLevel(Level.INFO);
        else if ("3".equalsIgnoreCase(level))
            log.setLevel(Level.DEBUG);
        else
            log.setLevel(Level.ERROR);

        if (prefs.contains("pumpPeriod")) {
            String type = prefs.getString("pumpPeriod", "1");
            if ("2".equalsIgnoreCase(type))
                pumpPeriod = MedtronicConstants.TIME_60_MIN_IN_MS;
            else if ("3".equalsIgnoreCase(type))
                pumpPeriod = MedtronicConstants.TIME_90_MIN_IN_MS;
            else if ("4".equalsIgnoreCase(type))
                pumpPeriod = MedtronicConstants.TIME_60_MIN_IN_MS + MedtronicConstants.TIME_60_MIN_IN_MS;
            else
                pumpPeriod = MedtronicConstants.TIME_30_MIN_IN_MS;
        }
        if (prefs.contains("monitor_type")) {
            String type = prefs.getString("monitor_type", "1");
            if ("2".equalsIgnoreCase(type)) {
                if (prefs.contains("calibrationType")) {
                    type = prefs.getString("calibrationType", "3");
                    if ("3".equalsIgnoreCase(type))
                        calibrationSelected = MedtronicConstants.CALIBRATION_MANUAL;
                    else if ("2".equalsIgnoreCase(type)) {
                        calibrationSelected = MedtronicConstants.CALIBRATION_SENSOR;
                        // start handler to ask for sensor calibration value
                        mHandlerSensorCalibration.postDelayed(getCalibrationFromSensor, pumpPeriod);
                    } else
                        calibrationSelected = MedtronicConstants.CALIBRATION_GLUCOMETER;
                }
            }
        }

        wifiManager = (WifiManager) this.getSystemService(Context.WIFI_SERVICE);
        mUsbManager = (UsbManager) getSystemService(Context.USB_SERVICE);
        mSerial = new Physicaloid(this);
        medtronicReader = new MedtronicReader(mSerial, getBaseContext(),
                mClients, null);
        medtronicReader.mHandlerSensorCalibration = mHandlerSensorCalibration;
        medtronicReader.getCalibrationFromSensor = getCalibrationFromSensor;
        checker = medtronicReader.new CalibrationStatusChecker(mHandlerReviewParameters);
        mHandlerReviewParameters.postDelayed(checker, MedtronicConstants.TIME_5_MIN_IN_MS);
        IntentFilter filter = new IntentFilter();
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        registerReceiver(mUsbReceiver, filter);
        mHandlerCheckSerial.removeCallbacks(readAndUpload);
        mHandlerCheckSerial.post(readAndUpload);
        mHandlerReloadLost.postDelayed(reloadLostRecords, 60000);
        mHandlerActive = true;
        long currentTime = System.currentTimeMillis();
        long diff = currentTime - settings.getLong("lastDestroy", 0);
        if (diff == currentTime || diff > (2 * MedtronicConstants.TIME_12_HOURS_IN_MS)) {
            if (isConnected())
                mHandler3ActivatePump.post(activateNewPump);
            else
                mHandler3ActivatePump.postDelayed(activateNewPump, MedtronicConstants.TIME_5_MIN_IN_MS);
        }
        //if I have selected "historic log read" then ...
        if (prefs.getString("glucSrcTypes", "1").equals("2")) {
            medtronicReader.mHandlerCheckLastRead = null;
            medtronicReader.checkLastRead = null;
            log.info("LOG READ ON CREATE");
            String type = prefs.getString("historicPeriod", "1");
            if ("2".equalsIgnoreCase(type))
                historicLogPeriod = MedtronicConstants.TIME_10_MIN_IN_MS;
            else if ("3".equalsIgnoreCase(type))
                historicLogPeriod = MedtronicConstants.TIME_15_MIN_IN_MS;
            else if ("4".equalsIgnoreCase(type))
                historicLogPeriod = MedtronicConstants.TIME_15_MIN_IN_MS;
            else
                historicLogPeriod = MedtronicConstants.TIME_5_MIN_IN_MS;

            if (settings.getLong("lastHistoricRead", 0) != 0) {
                log.info("PREVIOUS READ");
                if ((System.currentTimeMillis() - settings.getLong("lastHistoricRead", 0)) >= historicLogPeriod) {
                    log.info("periodRead " + (System.currentTimeMillis() - settings.getLong("lastHistoricRead", 0)) + " >= " + historicLogPeriod);
                    mHandlerReadFromHistoric.post(readDataFromHistoric);
                    SharedPreferences.Editor editor = settings.edit();
                    editor.putLong("lastHistoricRead", System.currentTimeMillis());
                    editor.commit();
                } else {
                    log.info("Read after delay");
                    mHandlerReadFromHistoric.postDelayed(readDataFromHistoric, historicLogPeriod);
                }
            } else {
                log.info("Read log immediatly");
                mHandlerReadFromHistoric.post(readDataFromHistoric);
                SharedPreferences.Editor editor = settings.edit();
                editor.putLong("lastHistoricRead", System.currentTimeMillis());
                editor.commit();
            }

        } else if (prefs.getString("glucSrcTypes", "1").equals("3")) {
            medtronicReader.mHandlerCheckLastRead = mHandlerCheckLastRead;
            medtronicReader.checkLastRead = checkLastRead;

            String type = prefs.getString("historicMixPeriod", "1");
            if ("2".equalsIgnoreCase(type))
                historicLogPeriod = MedtronicConstants.TIME_15_MIN_IN_MS;
            else if ("3".equalsIgnoreCase(type))
                historicLogPeriod = MedtronicConstants.TIME_20_MIN_IN_MS;
            else if ("4".equalsIgnoreCase(type))
                historicLogPeriod = MedtronicConstants.TIME_20_MIN_IN_MS + MedtronicConstants.TIME_5_MIN_IN_MS;
            else if ("5".equalsIgnoreCase(type))
                historicLogPeriod = MedtronicConstants.TIME_30_MIN_IN_MS;
            else if ("6".equalsIgnoreCase(type))
                historicLogPeriod = MedtronicConstants.TIME_30_MIN_IN_MS + MedtronicConstants.TIME_5_MIN_IN_MS;
            else if ("7".equalsIgnoreCase(type))
                historicLogPeriod = MedtronicConstants.TIME_30_MIN_IN_MS + MedtronicConstants.TIME_10_MIN_IN_MS;
            else if ("8".equalsIgnoreCase(type))
                historicLogPeriod = MedtronicConstants.TIME_30_MIN_IN_MS + MedtronicConstants.TIME_15_MIN_IN_MS;
            else if ("9".equalsIgnoreCase(type))
                historicLogPeriod = MedtronicConstants.TIME_30_MIN_IN_MS + MedtronicConstants.TIME_20_MIN_IN_MS;
            else if ("10".equalsIgnoreCase(type))
                historicLogPeriod = MedtronicConstants.TIME_60_MIN_IN_MS - MedtronicConstants.TIME_5_MIN_IN_MS;
            else if ("11".equalsIgnoreCase(type))
                historicLogPeriod = MedtronicConstants.TIME_60_MIN_IN_MS;
            else
                historicLogPeriod = MedtronicConstants.TIME_10_MIN_IN_MS;
            mHandlerCheckLastRead.post(checkLastRead);

        }

    }

    @Override
    public void onDestroy() {
        //Debug.stopMethodTracing();
        log.info("medCGM onDestroy!");
        isDestroying = true;
        prefs.unregisterOnSharedPreferenceChangeListener(this);
        synchronized (medtronicReader.processingCommand) {
            SharedPreferences.Editor editor = prefs.edit();

            editor.putBoolean("wasProcessing", medtronicReader.processingCommand);
            editor.commit();
        }
        synchronized (mHandlerReloadLost) {
            mHandlerReloadLost.removeCallbacks(reloadLostRecords);
        }
        synchronized (mHandlerCheckSerial) {
            Log.i(TAG, "onDestroy called");
            log.info("Medtronic Service onDestroy called");
            mHandlerCheckSerial.removeCallbacks(readAndUpload);

            if (NM != null) {
                NM.cancelAll();
                NM = null;
            }
            SharedPreferences.Editor editor = settings.edit();
            editor.putLong("lastDestroy", System.currentTimeMillis());
            editor.commit();
            closeUsbSerial();
            mHandlerActive = false;
            unregisterReceiver(mUsbReceiver);
        }
        mHandlerReadFromHistoric.removeCallbacks(readDataFromHistoric);
        mHandlerCheckLastRead.removeCallbacks(checkLastRead);
        synchronized (isUploading) {
            super.onDestroy();
        }
    }

    ;

    /**
     * Process all the parsed messages, checks if there is Records to upload and executes the uploader if necessary.
     */
    protected void doReadAndUpload(int size) {
        try {
            synchronized (mSerial) {
                if (mSerial.isOpened() && !isDestroying) {

                    log.info("doREadAndUpload");
                    ArrayList<byte[]> bufferedMessages = medtronicReader
                            .readFromReceiver(getApplicationContext(), size);
                    log.info("Stream Received--> READED");
                    if (bufferedMessages != null && bufferedMessages.size() > 0) {
                        log.info("Stream Received--> There are " + bufferedMessages.size() + " to process ");
                        synchronized (processBufferedMessages.bufferedMessages) {
                            processBufferedMessages.bufferedMessages
                                    .addAll(bufferedMessages);
                        }
                        if (!isDestroying) {
                            log.info("Stream Received--> order process bufferedMessages ");
                            mHandlerProcessRead.post(processBufferedMessages);
                        }
                    } else {
                        log.info("NULL doReadAndUpload");
                    }

                }

            }
        } catch (Exception e) {
            sendMessageToUI(ExceptionUtils.getStackTrace(e), false);
        }
    }

    private void doWifiHack() {
        Handler handler = new Handler();
        handler.postDelayed(new Runnable() {
            @Override
            // Interesting case: location with lousy wifi
            // toggle it off to use cellular
            // toggle back on for next try
            public void run() {
                Status dataUp = uploader.getStatus();
                if (dataUp == Status.RUNNING) {
                    uploader.cancel(true);

                    if (wifiManager.isWifiEnabled()) {
                        wifiManager.setWifiEnabled(false);
                        try {
                            Thread.sleep(2500);
                        } catch (InterruptedException e) {
                            Log.e(TAG, "Sleep after setWifiEnabled(false) interrupted: " + ExceptionUtils.getStackTrace(e));
                        }
                        wifiManager.setWifiEnabled(true);
                        try {
                            Thread.sleep(2500);
                        } catch (InterruptedException e) {
                            Log.e(TAG, "Sleep after setWifiEnabled(true) interrupted: " + ExceptionUtils.getStackTrace(e));
                        }
                    }
                }

            }
        }, 22500);
    }

    private boolean isConnected() {
        return mSerial.isOpened();
    }

    private boolean isOnline() {
        ConnectivityManager connectivity = (ConnectivityManager) getBaseContext().getSystemService(Context.CONNECTIVITY_SERVICE);
        boolean isOnline = false;

        if (connectivity != null) {
            NetworkInfo[] info = connectivity.getAllNetworkInfo();
            if (info != null) {
                for (int i = 0; (i < info.length) && !isOnline; i++) {
                    log.info("INTERNET: " + String.valueOf(i));
                    if (info[i].getState() == NetworkInfo.State.CONNECTED) {
                        log.info("INTERNET: connected!");
                        return true;
                    }
                }
            }
        } else
            return false;
        return isOnline;
    }

    /**
     * Launches a pop up message
     *
     * @param message
     */
    private void displayMessage(String message) {
        Toast toast = Toast.makeText(getBaseContext(), message,
                Toast.LENGTH_LONG);
        toast.setGravity(Gravity.CENTER, 0, 0);
        LinearLayout toastLayout = (LinearLayout) toast.getView();
        TextView toastTV = (TextView) toastLayout.getChildAt(0);
        if (toastTV != null) {
            toastTV.setTextSize(20);
            toastTV.setGravity(Gravity.CENTER_VERTICAL
                    | Gravity.CENTER_HORIZONTAL);
        }
        toast.show();

    }

    private void openUsbSerial() {
        if (mSerial == null) {
            Toast.makeText(this, "Cannot open connection to USB", Toast.LENGTH_SHORT).show();
        } else {
            synchronized (mSerial) {
                if (!mSerial.isOpened()) {
                    if (!mSerial.open()) {
                        USBPower.PowerOff();
                        try {
                            Thread.sleep(2500);
                        } catch (InterruptedException e) {
                            Log.w(TAG, "Interrupted during sleep after Power On", e);
                        }
                        USBPower.PowerOn();
                        try {
                            Thread.sleep(2500);
                        } catch (InterruptedException e) {
                            Log.w(TAG, "Interrupted during sleep after Power On", e);
                        }
                        Toast.makeText(this, "cannot open", Toast.LENGTH_SHORT)
                                .show();
                        return;
                    } else {
                        boolean dtrOn = true;
                        boolean rtsOn = false;
                        mSerial.setConfig(new UartConfig(57600, 8, 1, 0, dtrOn,
                                rtsOn));
                        Toast.makeText(this, "connected", Toast.LENGTH_SHORT)
                                .show();
                    }
                }
            }
        }
    }

    private void closeUsbSerial() {
        mSerial.clearReadListener();
        mHandlerRead.removeCallbacks(readByListener);
        mHandlerProcessRead.removeCallbacks(processBufferedMessages);
        mHandler2CheckDevice.removeCallbacks(cMThread);
        mHandler3ActivatePump.removeCallbacks(activateNewPump);
        mHandlerSensorCalibration.removeCallbacks(getCalibrationFromSensor);
        mHandlerReviewParameters.removeCallbacks(checker);
        listenerAttached = false;
        mSerial.close();
    }

    /**
     * Method inherited from "OnSharedPreferenceChangeListener"
     * Here we listen to the change of some preferences of interest to keep or remove the status
     * of our application.
     */
    @Override
    public void onSharedPreferenceChanged(SharedPreferences sharedPreferences,
                                          String key) {
        try {
            if (key.equalsIgnoreCase("logLevel")) {
                String level = sharedPreferences.getString("logLevel", "1");
                if ("2".equalsIgnoreCase(level))
                    log.setLevel(Level.INFO);
                else if ("3".equalsIgnoreCase(level))
                    log.setLevel(Level.DEBUG);
                else
                    log.setLevel(Level.ERROR);
            }
            if (sharedPreferences.contains("monitor_type") && key.equalsIgnoreCase("monitor_type")) {
                String type = sharedPreferences.getString("monitor_type", "1");
                if ("2".equalsIgnoreCase(type)) {
                    if (sharedPreferences.contains("calibrationType")) {
                        type = sharedPreferences.getString("calibrationType", "3");
                        if ("3".equalsIgnoreCase(type)) {
                            calibrationSelected = MedtronicConstants.CALIBRATION_MANUAL;
                            mHandlerSensorCalibration.removeCallbacks(getCalibrationFromSensor);
                        } else if ("2".equalsIgnoreCase(type)) {
                            type = sharedPreferences.getString("pumpPeriod", "1");
                            if ("2".equalsIgnoreCase(type))
                                pumpPeriod = MedtronicConstants.TIME_60_MIN_IN_MS;
                            else if ("3".equalsIgnoreCase(type))
                                pumpPeriod = MedtronicConstants.TIME_90_MIN_IN_MS;
                            else if ("4".equalsIgnoreCase(type))
                                pumpPeriod = MedtronicConstants.TIME_60_MIN_IN_MS + MedtronicConstants.TIME_60_MIN_IN_MS;
                            else
                                pumpPeriod = MedtronicConstants.TIME_30_MIN_IN_MS;
                            calibrationSelected = MedtronicConstants.CALIBRATION_SENSOR;
                            //start handler to ask for sensor calibration value
                            mHandlerSensorCalibration.removeCallbacks(getCalibrationFromSensor);
                            mHandlerSensorCalibration.postDelayed(getCalibrationFromSensor, pumpPeriod);
                        } else {
                            calibrationSelected = MedtronicConstants.CALIBRATION_GLUCOMETER;
                            mHandlerSensorCalibration.removeCallbacks(getCalibrationFromSensor);
                        }
                        synchronized (medtronicReader.calibrationSelected) {
                            medtronicReader.calibrationSelected = calibrationSelected;
                        }

                    }
                }
            }

            if (sharedPreferences.contains("calibrationType") && key.equalsIgnoreCase("calibrationType")) {

                String type = sharedPreferences.getString("pumpPeriod", "1");
                if ("2".equalsIgnoreCase(type))
                    pumpPeriod = MedtronicConstants.TIME_60_MIN_IN_MS;
                else if ("3".equalsIgnoreCase(type))
                    pumpPeriod = MedtronicConstants.TIME_90_MIN_IN_MS;
                else if ("4".equalsIgnoreCase(type))
                    pumpPeriod = MedtronicConstants.TIME_60_MIN_IN_MS + MedtronicConstants.TIME_60_MIN_IN_MS;
                else
                    pumpPeriod = MedtronicConstants.TIME_30_MIN_IN_MS;

                type = sharedPreferences.getString("calibrationType", "3");
                if ("3".equalsIgnoreCase(type)) {
                    calibrationSelected = MedtronicConstants.CALIBRATION_MANUAL;
                    mHandlerSensorCalibration.removeCallbacks(getCalibrationFromSensor);
                } else if ("2".equalsIgnoreCase(type)) {
                    calibrationSelected = MedtronicConstants.CALIBRATION_SENSOR;
                    //start handler to ask for sensor calibration value
                    mHandlerSensorCalibration.removeCallbacks(getCalibrationFromSensor);
                    mHandlerSensorCalibration.postDelayed(getCalibrationFromSensor, pumpPeriod);
                } else {
                    calibrationSelected = MedtronicConstants.CALIBRATION_GLUCOMETER;
                    mHandlerSensorCalibration.removeCallbacks(getCalibrationFromSensor);
                }
                synchronized (medtronicReader.calibrationSelected) {
                    medtronicReader.calibrationSelected = calibrationSelected;
                }

            }
            if (key.equalsIgnoreCase("pumpPeriod")) {
                if (sharedPreferences.contains("pumpPeriod")) {
                    String type = sharedPreferences.getString("pumpPeriod", "1");
                    if ("2".equalsIgnoreCase(type))
                        pumpPeriod = MedtronicConstants.TIME_60_MIN_IN_MS;
                    else if ("3".equalsIgnoreCase(type))
                        pumpPeriod = MedtronicConstants.TIME_90_MIN_IN_MS;
                    else if ("4".equalsIgnoreCase(type))
                        pumpPeriod = MedtronicConstants.TIME_60_MIN_IN_MS + MedtronicConstants.TIME_60_MIN_IN_MS;
                    else
                        pumpPeriod = MedtronicConstants.TIME_30_MIN_IN_MS;
                }
                if (medtronicReader != null) {
                    mHandler3ActivatePump.removeCallbacks(getCalibrationFromSensor);
                    if (pumpPeriod > -1)
                        mHandler3ActivatePump.postDelayed(getCalibrationFromSensor, pumpPeriod);
                    else
                        mHandler3ActivatePump.post(getCalibrationFromSensor);
                }
            }
            if (key.equalsIgnoreCase("glucSrcTypes")) {

                String type1 = sharedPreferences.getString("glucSrcTypes", "1");
                if (type1.equals("2")) {
                    medtronicReader.mHandlerCheckLastRead = null;
                    medtronicReader.checkLastRead = null;
                    mHandlerReadFromHistoric.removeCallbacks(readDataFromHistoric);

                    String type = prefs.getString("historicPeriod", "1");
                    if ("2".equalsIgnoreCase(type))
                        historicLogPeriod = MedtronicConstants.TIME_10_MIN_IN_MS;
                    else if ("3".equalsIgnoreCase(type))
                        historicLogPeriod = MedtronicConstants.TIME_15_MIN_IN_MS;
                    else if ("4".equalsIgnoreCase(type))
                        historicLogPeriod = MedtronicConstants.TIME_15_MIN_IN_MS;
                    else
                        historicLogPeriod = MedtronicConstants.TIME_5_MIN_IN_MS;

                    if (settings.getLong("lastHistoricRead", 0) != 0) {
                        if ((System.currentTimeMillis() - settings.getLong("lastHistoricRead", 0)) >= historicLogPeriod) {
                            mHandlerReadFromHistoric.post(readDataFromHistoric);
                            SharedPreferences.Editor editor = settings.edit();
                            editor.putLong("lastHistoricRead", System.currentTimeMillis());
                            editor.commit();
                        }
                    } else {
                        mHandlerReadFromHistoric.post(readDataFromHistoric);
                        SharedPreferences.Editor editor = settings.edit();
                        editor.putLong("lastHistoricRead", System.currentTimeMillis());
                        editor.commit();
                    }

                } else if (type1.equals("1")) {
                    medtronicReader.mHandlerCheckLastRead = null;
                    medtronicReader.checkLastRead = null;
                    mHandlerReadFromHistoric.removeCallbacks(readDataFromHistoric);
                    mHandlerCheckLastRead.removeCallbacks(checkLastRead);
                } else if (type1.equals("3")) {
                    medtronicReader.mHandlerCheckLastRead = mHandlerCheckLastRead;
                    medtronicReader.checkLastRead = checkLastRead;
                    mHandlerCheckLastRead.removeCallbacks(checkLastRead);
                    String type = prefs.getString("historicMixPeriod", "1");
                    if ("2".equalsIgnoreCase(type))
                        historicLogPeriod = MedtronicConstants.TIME_15_MIN_IN_MS;
                    else if ("3".equalsIgnoreCase(type))
                        historicLogPeriod = MedtronicConstants.TIME_20_MIN_IN_MS;
                    else if ("4".equalsIgnoreCase(type))
                        historicLogPeriod = MedtronicConstants.TIME_20_MIN_IN_MS + MedtronicConstants.TIME_5_MIN_IN_MS;
                    else if ("5".equalsIgnoreCase(type))
                        historicLogPeriod = MedtronicConstants.TIME_30_MIN_IN_MS;
                    else if ("6".equalsIgnoreCase(type))
                        historicLogPeriod = MedtronicConstants.TIME_30_MIN_IN_MS + MedtronicConstants.TIME_5_MIN_IN_MS;
                    else if ("7".equalsIgnoreCase(type))
                        historicLogPeriod = MedtronicConstants.TIME_30_MIN_IN_MS + MedtronicConstants.TIME_10_MIN_IN_MS;
                    else if ("8".equalsIgnoreCase(type))
                        historicLogPeriod = MedtronicConstants.TIME_30_MIN_IN_MS + MedtronicConstants.TIME_15_MIN_IN_MS;
                    else if ("9".equalsIgnoreCase(type))
                        historicLogPeriod = MedtronicConstants.TIME_30_MIN_IN_MS + MedtronicConstants.TIME_20_MIN_IN_MS;
                    else if ("10".equalsIgnoreCase(type))
                        historicLogPeriod = MedtronicConstants.TIME_60_MIN_IN_MS - MedtronicConstants.TIME_5_MIN_IN_MS;
                    else if ("11".equalsIgnoreCase(type))
                        historicLogPeriod = MedtronicConstants.TIME_60_MIN_IN_MS;
                    else
                        historicLogPeriod = MedtronicConstants.TIME_10_MIN_IN_MS;
                    mHandlerCheckLastRead.post(checkLastRead);

                }


            }
            if (key.equalsIgnoreCase("historicPeriod")) {
                mHandlerReadFromHistoric.removeCallbacks(readDataFromHistoric);
                String type = sharedPreferences.getString("historicPeriod", "1");
                if ("2".equalsIgnoreCase(type))
                    historicLogPeriod = MedtronicConstants.TIME_10_MIN_IN_MS;
                else if ("3".equalsIgnoreCase(type))
                    historicLogPeriod = MedtronicConstants.TIME_15_MIN_IN_MS;
                else if ("4".equalsIgnoreCase(type))
                    historicLogPeriod = MedtronicConstants.TIME_15_MIN_IN_MS;
                else
                    historicLogPeriod = MedtronicConstants.TIME_5_MIN_IN_MS;

                mHandlerReadFromHistoric.post(readDataFromHistoric);
                SharedPreferences.Editor editor = settings.edit();
                editor.putLong("lastHistoricRead", System.currentTimeMillis());
                editor.commit();
            }
            if (key.equals("medtronic_cgm_id") || key.equals("glucometer_cgm_id") || key.equals("sensor_cgm_id")) {
                String newID = sharedPreferences.getString("medtronic_cgm_id", "");
                if (newID != null && !"".equals(newID.replaceAll(" ", ""))) {
                    mHandlerCheckSerial.removeCallbacks(readAndUpload);
                    byte[] newIdPump = HexDump.hexStringToByteArray(newID);
                    if (!Arrays.equals(newIdPump, medtronicReader.idPump)) {
                        SharedPreferences.Editor editor = settings.edit();
                        editor.remove("lastGlucometerMessage");
                        editor.remove("previousValue");
                        editor.remove("expectedSensorSortNumber");
                        editor.remove("knownDevices");
                        editor.remove("isCalibrating");
                        editor.remove("previousValue");
                        editor.remove("expectedSensorSortNumber");
                        editor.remove("lastGlucometerValue");
                        editor.remove("lastGlucometerDate");
                        editor.remove("expectedSensorSortNumberForCalibration0");
                        editor.remove("expectedSensorSortNumberForCalibration1");
                        editor.remove("lastPumpAwake");
                        editor.commit();
                        synchronized (mHandlerCheckSerial) {

                            mHandlerCheckSerial.removeCallbacks(readAndUpload);

                            mHandlerActive = false;

                        }
                        medtronicReader = new MedtronicReader(mSerial,
                                getBaseContext(), mClients, null);
                        medtronicReader.idPump = newIdPump;
                        synchronized (mHandlerCheckSerial) {
                            mHandlerCheckSerial.post(readAndUpload);
                            mHandlerActive = true;
                            if (medtronicReader != null) {
                                mHandler3ActivatePump.removeCallbacks(activateNewPump);
                                mHandler3ActivatePump.post(activateNewPump);
                                mHandlerReadFromHistoric.removeCallbacks(readDataFromHistoric);
                                if (hGetter == null) {
                                    hGetter = new HistoricGetterThread(mClients, medtronicReader,
                                            medtronicReader.idPump, mSerial, mHandlerReadFromHistoric);

                                } else
                                    hGetter.init();
                                medtronicReader.historicPageIndex = -1;
                                medtronicReader.historicPageShift = 0;
                                medtronicReader.datalog = new DataLog();
                                String type1 = sharedPreferences.getString("glucSrcTypes", "1");
                                if (type1.equals("2")) {
                                    medtronicReader.mHandlerCheckLastRead = null;
                                    medtronicReader.checkLastRead = null;
                                    mHandlerReadFromHistoric.removeCallbacks(readDataFromHistoric);
                                    if (prefs.contains("historicPeriod")) {
                                        String type = prefs.getString("historicPeriod", "1");
                                        if ("2".equalsIgnoreCase(type))
                                            historicLogPeriod = MedtronicConstants.TIME_10_MIN_IN_MS;
                                        else if ("3".equalsIgnoreCase(type))
                                            historicLogPeriod = MedtronicConstants.TIME_15_MIN_IN_MS;
                                        else if ("4".equalsIgnoreCase(type))
                                            historicLogPeriod = MedtronicConstants.TIME_15_MIN_IN_MS;
                                        else
                                            historicLogPeriod = MedtronicConstants.TIME_5_MIN_IN_MS;
                                    }
                                    if (settings.getLong("lastHistoricRead", 0) != 0) {
                                        if ((System.currentTimeMillis() - settings.getLong("lastHistoricRead", 0)) >= historicLogPeriod) {
                                            mHandlerReadFromHistoric.post(readDataFromHistoric);
                                            SharedPreferences.Editor editor2 = settings.edit();
                                            editor2.putLong("lastHistoricRead", System.currentTimeMillis());
                                            editor2.commit();
                                        }
                                    } else {
                                        mHandlerReadFromHistoric.post(readDataFromHistoric);
                                        SharedPreferences.Editor editor2 = settings.edit();
                                        editor2.putLong("lastHistoricRead", System.currentTimeMillis());
                                        editor2.commit();
                                    }

                                } else if (type1.equals("1")) {
                                    medtronicReader.mHandlerCheckLastRead = null;
                                    medtronicReader.checkLastRead = null;
                                    mHandlerReadFromHistoric.removeCallbacks(readDataFromHistoric);
                                    mHandlerCheckLastRead.removeCallbacks(checkLastRead);
                                } else if (type1.equals("3")) {
                                    medtronicReader.mHandlerCheckLastRead = mHandlerCheckLastRead;
                                    medtronicReader.checkLastRead = checkLastRead;
                                    mHandlerCheckLastRead.removeCallbacks(checkLastRead);
                                    String type = prefs.getString("historicMixPeriod", "1");
                                    if ("2".equalsIgnoreCase(type))
                                        historicLogPeriod = MedtronicConstants.TIME_15_MIN_IN_MS;
                                    else if ("3".equalsIgnoreCase(type))
                                        historicLogPeriod = MedtronicConstants.TIME_20_MIN_IN_MS;
                                    else if ("4".equalsIgnoreCase(type))
                                        historicLogPeriod = MedtronicConstants.TIME_20_MIN_IN_MS + MedtronicConstants.TIME_5_MIN_IN_MS;
                                    else if ("5".equalsIgnoreCase(type))
                                        historicLogPeriod = MedtronicConstants.TIME_30_MIN_IN_MS;
                                    else if ("6".equalsIgnoreCase(type))
                                        historicLogPeriod = MedtronicConstants.TIME_30_MIN_IN_MS + MedtronicConstants.TIME_5_MIN_IN_MS;
                                    else if ("7".equalsIgnoreCase(type))
                                        historicLogPeriod = MedtronicConstants.TIME_30_MIN_IN_MS + MedtronicConstants.TIME_10_MIN_IN_MS;
                                    else if ("8".equalsIgnoreCase(type))
                                        historicLogPeriod = MedtronicConstants.TIME_30_MIN_IN_MS + MedtronicConstants.TIME_15_MIN_IN_MS;
                                    else if ("9".equalsIgnoreCase(type))
                                        historicLogPeriod = MedtronicConstants.TIME_30_MIN_IN_MS + MedtronicConstants.TIME_20_MIN_IN_MS;
                                    else if ("10".equalsIgnoreCase(type))
                                        historicLogPeriod = MedtronicConstants.TIME_60_MIN_IN_MS - MedtronicConstants.TIME_5_MIN_IN_MS;
                                    else if ("11".equalsIgnoreCase(type))
                                        historicLogPeriod = MedtronicConstants.TIME_60_MIN_IN_MS;
                                    else
                                        historicLogPeriod = MedtronicConstants.TIME_10_MIN_IN_MS;
                                    mHandlerCheckLastRead.post(checkLastRead);

                                }

                            }

                        }
                    }
                }
            }

        } catch (Exception e) {
            sendMessageToUI(ExceptionUtils.getStackTrace(e), false);
        }
    }

    /**
     * Handler of incoming messages from clients.
     *
     * @author lmmarguenda
     */
    class IncomingHandler extends Handler {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MedtronicConstants.MSG_REGISTER_CLIENT:
                    mClients.add(msg.replyTo);
                    break;
                case MedtronicConstants.MSG_UNREGISTER_CLIENT:
                    mClients.remove(msg.replyTo);
                    break;
                case MedtronicConstants.MSG_MEDTRONIC_SEND_MANUAL_CALIB_VALUE:
                    String value = msg.getData().getString("sgv");
                    if (value == null || value.equals("")) {
                        value = prefs.getString("manual_sgv", "");
                    }
                    log.info("Manual Calibration Received SGV " + value);
                    try {
                        Float val = null;
                        if (medtronicReader != null && value != null && !value.equals("")) {
                            if (prefs.getBoolean("mmolxl", false)) {
                                try {
                                    if (value.indexOf(".") > -1) {
                                        val = Float.parseFloat(value);
                                        medtronicReader.processManualCalibrationDataMessage(val, false);
                                    } else {
                                        medtronicReader.processManualCalibrationDataMessage(Integer.parseInt(value), false);
                                    }
                                    sendMessageCalibrationDoneToUI();
                                } catch (Exception e) {
                                    sendErrorMessageToUI("Error parsing Calibration");
                                    sendMessageToUI(ExceptionUtils.getStackTrace(e), false);
                                }
                            } else {
                                if (value.indexOf(".") > -1) {
                                    val = Float.parseFloat(value);
                                    medtronicReader.processManualCalibrationDataMessage(val.intValue(), false);
                                } else {
                                    medtronicReader.processManualCalibrationDataMessage(Integer.parseInt(value), false);
                                }
                                sendMessageCalibrationDoneToUI();
                            }
                        }
                    } catch (Exception e) {
                        sendErrorMessageToUI("Error parsing Calibration");
                        sendMessageToUI(ExceptionUtils.getStackTrace(e), false);
                    }
                    break;
                case MedtronicConstants.MSG_MEDTRONIC_SEND_INSTANT_CALIB_VALUE:
                    value = msg.getData().getString("sgv");
                    if (value == null || value.equals("")) {
                        value = prefs.getString("instant_sgv", "");
                    }
                    log.info("Instant Calibration received SGV " + value);
                    try {
                        Float val = null;
                        if (medtronicReader != null && value != null && !value.equals("")) {
                            if (prefs.getBoolean("mmolxl", false)) {
                                try {
                                    if (value.indexOf(".") > -1) {
                                        val = Float.parseFloat(value);
                                        medtronicReader.calculateInstantCalibration(val * 18f);
                                    } else {
                                        medtronicReader.calculateInstantCalibration(Integer.parseInt(value) * 18f);
                                    }
                                    sendMessageCalibrationDoneToUI();
                                } catch (Exception e) {
                                    sendErrorMessageToUI("Error parsing Calibration");
                                    sendMessageToUI(ExceptionUtils.getStackTrace(e), false);
                                }
                            } else {
                                if (value.indexOf(".") > -1) {
                                    val = Float.parseFloat(value);
                                    medtronicReader.calculateInstantCalibration(val.intValue());
                                } else {
                                    medtronicReader.calculateInstantCalibration(Integer.parseInt(value));
                                }
                                sendMessageCalibrationDoneToUI();
                            }
                        }
                    } catch (Exception e) {
                        sendErrorMessageToUI("Error parsing Calibration");
                        sendMessageToUI(ExceptionUtils.getStackTrace(e), false);
                    }
                    break;
                case MedtronicConstants.MSG_MEDTRONIC_SEND_GET_PUMP_INFO:
                    sendMessageToUI("Retrieving Pump info...", false);
                    log.info("Retrieving Pump info...");
                    mHandler3ActivatePump.removeCallbacks(activateNewPump);
                    mHandler3ActivatePump.post(activateNewPump);
                    break;
                case MedtronicConstants.MSG_MEDTRONIC_SEND_GET_SENSORCAL_FACTOR:
                    sendMessageToUI("Retrieve calibration factor...Now!", false);
                    log.info("Retrieve calibration factor...Now!");
                    mHandlerSensorCalibration.removeCallbacks(getCalibrationFromSensor);
                    mHandlerSensorCalibration.post(getCalibrationFromSensor);
                    break;
                case MedtronicConstants.MSG_MEDTRONIC_CGM_REQUEST_PERMISSION:
                    openUsbSerial();
                    break;
                default:
                    super.handleMessage(msg);
            }
        }
    }

    /**
     * Runnable.
     * Executes doReadAndUploadFunction;
     */
    private class ReadByListener implements Runnable {
        public Integer size = -1;

        public void run() {
            int auxSize = 0;
            synchronized (size) {
                auxSize = size;
                size = -1;
            }
            if (auxSize >= 0) {
                log.info("Quiero leer " + auxSize + " bytes");
                doReadAndUpload(auxSize);
            } else {
                log.info("ReadByListener NO TIENE NADA QUE SUBIR");
            }
        }
    }

    /**
     * class This class process all the messages received after being correctly
     * parsed.
     */
    private class BufferedMessagesProcessor implements Runnable {
        public ArrayList<byte[]> bufferedMessages = new ArrayList<byte[]>();
        public String sResult = "";

        public void run() {
            log.info("Processing bufferedMessages ");
            synchronized (isUploading) {
                log.info("I am Not Uploading ");

                try {
                    ArrayList<byte[]> bufferedMessages2Process = new ArrayList<byte[]>();
                    synchronized (sResult) {
                        sResult = "";
                    }
                    synchronized (bufferedMessages) {
                        bufferedMessages2Process.addAll(bufferedMessages);
                        bufferedMessages.clear();
                    }
                    log.info("I am going to process " + bufferedMessages2Process.size() + " Messages");
                    synchronized (sResult) {
                        sResult = medtronicReader
                                .processBufferedMessages(bufferedMessages2Process);
                    }
                    // ONLY FOR DEBUG PURPOUSES
                    // if (!"".equals(sResult))
                    // sendMessageToUI(sResult,false);
                    // execute uploader
                    List<Record> listToUpload = new ArrayList<Record>();
                    // upload sensor values if available
                    if (medtronicReader.lastElementsAdded > 0
                            && medtronicReader.lastRecordsInMemory != null
                            && medtronicReader.lastRecordsInMemory.size() >= medtronicReader.lastElementsAdded) {
                        listToUpload
                                .addAll(medtronicReader.lastRecordsInMemory
                                        .getListFromTail(medtronicReader.lastElementsAdded));// most
                        // recent
                        // First
                        medtronicReader.lastElementsAdded = 0;
                    }
                    // upload glucometer value if available
                    if (medtronicReader.lastGlucometerRecord != null) {
                        listToUpload.add(medtronicReader.lastGlucometerRecord);
                        medtronicReader.lastGlucometerRecord = null;
                    }
                    // upload device info if available
                    if (medtronicReader.lastMedtronicPumpRecord != null) {
                        listToUpload.add(medtronicReader.lastMedtronicPumpRecord);
                        medtronicReader.lastMedtronicPumpRecord = null;
                    }

                    Record[] params = new Record[listToUpload.size()];
                    for (int i = listToUpload.size() - 1; i >= 0; i--) {
                        Record record = listToUpload.get(i);
                        params[listToUpload.size() - 1 - i] = record;
                    }
                    if (params.length > 0) {
                        synchronized (mHandlerReloadLost) {
                            uploader = new UploadHelper(getApplicationContext());
                            uploader.execute(params);
                        }
                    }

                    listToUpload.clear();
                    if (prefs.getBoolean("EnableWifiHack", false)) {
                        doWifiHack();
                    }
                } catch (Exception e) {
                    sendMessageToUI(ExceptionUtils.getStackTrace(e) + "\n " + sResult, false);
                }
            }
            log.info("Buffered Messages Processed ");
        }

    }
}
