package com.nightscout.android;

import android.content.SharedPreferences;
import android.util.Log;

import com.mongodb.BasicDBObject;
import com.mongodb.DB;
import com.mongodb.DBCollection;
import com.mongodb.DBCursor;
import com.mongodb.DBObject;
import com.mongodb.MongoClient;
import com.mongodb.MongoClientOptions;
import com.mongodb.MongoCredential;
import com.mongodb.ServerAddress;
import com.mongodb.WriteConcern;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/**
 * Created by fokko on 13-7-15.
 */
public class MongoConnection {
    private static final String TAG = MongoConnection.class.getSimpleName();

    private DB db = null;

    public static final String MONGO_URI_KEY = "MongoDB URI";
    public static final String MONGO_USERNAME_KEY = "MongoDB Username";
    public static final String MONGO_PASSWORD_KEY = "MongoDB Password";
    public static final String MONGO_DATABASE_KEY = "MongoDB Database";

    private static final WriteConcern generalConcern = WriteConcern.UNACKNOWLEDGED;

    private static final String NAME_DEVICE_COLLECTION = "device";
    private static final String NAME_DEVICE_STATUS_COLLECTION = "devicestatus";
    private static final String NAME_ENTRIES_COLLECTION = "entries";
    private static final String NAME_GLUCOSE_COLLECTION = "gcdCollectionName";

    private DBCollection deviceCollection = null;
    private DBCollection deviceStatusCollection = null;
    private DBCollection entriesCollection = null;
    private DBCollection glucoseCollection = null;

    // Lazy Initialization (If required then only)
    public static MongoConnection getInstance(SharedPreferences prefs) {
        if (instance == null) {
            // Thread Safe. Might be costly operation in some case
            synchronized (MongoConnection.class) {
                if (instance == null) {
                    instance = new MongoConnection(prefs);
                }
            }
        }

        return instance;
    }

    private static MongoConnection instance = null;

    protected MongoConnection() {
        this(null);
    }

    private static final int TIMEOUT = 60000;

    protected MongoConnection(SharedPreferences prefs) {
        String dbURI = prefs.getString(MONGO_URI_KEY, null);
        String dbUsername = prefs.getString(MONGO_USERNAME_KEY, null);
        String dbPassword = prefs.getString(MONGO_PASSWORD_KEY, null);
        String dbDatabase = prefs.getString(MONGO_DATABASE_KEY, null);

        MongoClientOptions.Builder options = MongoClientOptions.builder();
        options.heartbeatConnectTimeout(TIMEOUT);
        options.heartbeatSocketTimeout(TIMEOUT);
        options.maxWaitTime(TIMEOUT);

        MongoCredential credential = MongoCredential.createMongoCRCredential(dbUsername, dbURI, dbPassword.toCharArray());
        ServerAddress address = new ServerAddress(dbURI);

        List<MongoCredential> credentials = new ArrayList<MongoCredential>();
        credentials.add(credential);

        MongoClient client = new MongoClient(address, credentials, options.build());

        client.setWriteConcern(this.generalConcern);

        // TODO: Do something smart
        this.db = client.getDB(dbDatabase);

        this.deviceCollection = db.getCollection(NAME_DEVICE_COLLECTION);
        this.deviceStatusCollection = db.getCollection(NAME_DEVICE_STATUS_COLLECTION);
        this.entriesCollection = db.getCollection(NAME_ENTRIES_COLLECTION);
        this.glucoseCollection = db.getCollection(NAME_GLUCOSE_COLLECTION);
    }

    public void writeDeviceCollection(DBObject obj) {
        Log.e(TAG, " Writing to " + NAME_DEVICE_COLLECTION);

        this.deviceCollection.save(obj, generalConcern);
    }

    public void writeDeviceStatusCollection(DBObject obj) {
        Log.e(TAG, " Writing to " + NAME_DEVICE_STATUS_COLLECTION);

        this.deviceStatusCollection.save(obj, generalConcern);
    }

    public void writeEntriesCollection(DBObject obj) {
        Log.e(TAG, " Writing to " + NAME_ENTRIES_COLLECTION);

        this.entriesCollection.save(obj, generalConcern);
    }

    public void writeGlucoseCollection(DBObject obj) {
        Log.e(TAG, " Writing to " + NAME_GLUCOSE_COLLECTION);

        this.glucoseCollection.save(obj, generalConcern);
    }

    public DBObject getDevice(HashMap<String, Object> query) {
        DBCursor cursor = this.deviceCollection.find(new BasicDBObject(query));

        final DBObject obj;
        if (cursor.hasNext()) {
            obj = cursor.next();
        } else {
            obj = null;
        }

        cursor.close();
        return obj;
    }

}
