package com.spressoinsights.spresso;

import android.content.Context;
import android.util.Log;


/**
 * Stores global configuration options for the Spresso library.
 * May be overridden to achieve custom behavior.
 */
/* package */ class SpressoConfig {
    public static final String VERSION = "1.2.0";
    
    // Set to true to see lots of internal debugging logcat output.
    // This should be set to false in production builds
    public static final boolean DEBUG = false;
    
    /* package */ static final String REFERRER_PREFS_NAME = "com.spresso.android.mpmetrics.ReferralInfo";
    
    public static SpressoConfig readConfig(Context context, boolean debug) {
        return new SpressoConfig(debug);
        
    }
    
    public SpressoConfig(boolean debug) {
        mBulkUploadLimit = 40; // 40 records default
        mFlushInterval = 10 * 1000; // one minute default
        mDataExpiration = 1000 * 60 * 60 * 24 * 5; // 5 days default
        mDisableFallback = true;
        
        if (debug) {
            mEventsEndpoint = "https://public-pensieve-stats.us-east4.staging.spresso.com/track";
        } else {
            mEventsEndpoint = "https://public-pensieve-stats.us-east4.prod.spresso.com/track";
        }
        
        mEventsFallbackEndpoint = mEventsEndpoint;
        
        if (debug) {
            Log.d(LOGTAG,
                    "Spresso configured with:\n" +
                            "    BulkUploadLimit " + getBulkUploadLimit() + "\n" +
                            "    FlushInterval " + getFlushInterval() + "\n" +
                            "    DataExpiration " + getDataExpiration() + "\n" +
                            "    DisableFallback " + getDisableFallback() + "\n" +
                            "    EventsEndpoint " + getEventsEndpoint() + "\n" +
                            "    EventsFallbackEndpoint " + getEventsFallbackEndpoint() + "\n"
            );
        }
    }
    
    // Max size of queue before we require a flush. Must be below the limit the service will accept.
    public int getBulkUploadLimit() {
        return mBulkUploadLimit;
    }
    
    // Target max milliseconds between flushes. This is advisory.
    public int getFlushInterval() {
        return mFlushInterval;
    }
    
    // Throw away records that are older than this in milliseconds. Should be below the server side age limit for events.
    public int getDataExpiration() {
        return mDataExpiration;
    }
    
    public boolean getDisableFallback() {
        return mDisableFallback;
    }
    
    // Preferred URL for tracking events
    public String getEventsEndpoint() {
        return mEventsEndpoint;
    }
    
    
    
    // Fallback URL for tracking events if post to preferred URL fails
    public String getEventsFallbackEndpoint() {
        return mEventsFallbackEndpoint;
    }
    
    
    
    
    private final int mBulkUploadLimit;
    private final int mFlushInterval;
    private final int mDataExpiration;
    private final boolean mDisableFallback;
    private final String mEventsEndpoint;
    private final String mEventsFallbackEndpoint;
    
    
    
    
    private static final String LOGTAG = "Spresso.MPConfig";
}
