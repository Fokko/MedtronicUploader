package com.nightscout.android;

import android.content.SharedPreferences;

import com.mongodb.BasicDBObject;
import com.mongodb.DB;
import com.mongodb.DBCollection;
import com.mongodb.MongoClient;
import com.mongodb.MongoClientOptions;
import com.mongodb.MongoClientURI;
import com.mongodb.WriteConcern;

/**
 * Created by fokko on 13-7-15.
 */
public class MongoConnection {
    private DB db = null;

    public static final String MONGO_URI_KEY = "MongoDB URI";

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
    public static MongoConnection getInstance() {
        if (instance == null) {
            // Thread Safe. Might be costly operation in some case
            synchronized (MongoConnection.class) {
                if (instance == null) {
                    instance = new MongoConnection();
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

        MongoClientURI uri = new MongoClientURI(dbURI.trim());

        MongoClientOptions.Builder options = MongoClientOptions.builder();
        options.heartbeatConnectTimeout(TIMEOUT);
        options.heartbeatSocketTimeout(TIMEOUT);
        options.maxWaitTime(TIMEOUT);

        MongoClient client = new MongoClient(dbURI, options.build());

        // TODO: Do something smart
        this.db = client.getDB(uri.getDatabase());

        this.deviceCollection = db.getCollection(NAME_DEVICE_COLLECTION);
        this.deviceStatusCollection = db.getCollection(NAME_DEVICE_STATUS_COLLECTION);
        this.entriesCollection = db.getCollection(NAME_ENTRIES_COLLECTION);
        this.glucoseCollection = db.getCollection(NAME_GLUCOSE_COLLECTION);
    }

    public void writeDeviceCollection(BasicDBObject obj) {
        this.deviceCollection.save(obj, generalConcern);
    }

    public void writeDeviceStatusCollection(BasicDBObject obj) {
        this.deviceStatusCollection.save(obj, generalConcern);;
    }

    public void writeEntriesCollection(BasicDBObject obj) {
        this.entriesCollection.save(obj, generalConcern);;
    }

    public void writeGlucoseCollection(BasicDBObject obj) {
        this.glucoseCollection.save(obj, generalConcern);;
    }


}
