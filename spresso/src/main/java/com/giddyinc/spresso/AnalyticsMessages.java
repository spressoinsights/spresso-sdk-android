package com.giddyinc.spresso;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.util.DisplayMetrics;
import android.util.Log;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONException;
import org.json.JSONObject;


/**
 * Manage communication of events with the internal database and the Spresso servers.
 *
 * <p>This class straddles the thread boundary between user threads and
 * a logical Spresso thread.
 */
/* package */ class AnalyticsMessages {
    /**
     * Do not call directly. You should call AnalyticsMessages.getInstance()
     */
    /* package */ AnalyticsMessages(Context context, boolean isDebug) {
        mContext = context;
        mConfig = getConfig(context, isDebug);
        mLogSpressoMessages = new AtomicBoolean(false);
        mWorker = new Worker();
    }

    /**
     * Use this to get an instance of AnalyticsMessages instead of creating one directly
     * for yourself.
     *
     * @param messageContext should be the Main Activity of the application
     *     associated with these messages.
     */
    public static AnalyticsMessages getInstance(Context messageContext, boolean isDebug) {
        synchronized (sInstances) {
            final Context appContext = messageContext.getApplicationContext();
            AnalyticsMessages ret;
            if (! sInstances.containsKey(appContext)) {
                if (SpressoConfig.DEBUG) Log.d(LOGTAG, "Constructing new AnalyticsMessages for Context " + appContext);
                ret = new AnalyticsMessages(appContext, isDebug);
                sInstances.put(appContext, ret);
            }
            else {
                if (SpressoConfig.DEBUG) Log.d(LOGTAG, "AnalyticsMessages for Context " + appContext + " already exists- returning");
                ret = sInstances.get(appContext);
            }
            return ret;
        }
    }

    public void logPosts() {
        mLogSpressoMessages.set(true);
    }

    public void eventsMessage(EventDescription eventDescription) {
        final Message m = Message.obtain();
        m.what = ENQUEUE_EVENTS;
        m.obj = eventDescription;
        mWorker.runMessage(m);
    }

    // Must be thread safe.
    public void peopleMessage(JSONObject peopleJson) {
        final Message m = Message.obtain();
        m.what = ENQUEUE_PEOPLE;
        m.obj = peopleJson;

        mWorker.runMessage(m);
    }

    public void postToServer() {
        final Message m = Message.obtain();
        m.what = FLUSH_QUEUE;

        mWorker.runMessage(m);
    }

    /**
     * Remove this when we eliminate the associated deprecated public ifc
     */
    public void setFlushInterval(long milliseconds) {
        final Message m = Message.obtain();
        m.what = SET_FLUSH_INTERVAL;
        m.obj = Long.valueOf(milliseconds);

        mWorker.runMessage(m);
    }

    /**
     * Remove this when we eliminate the associated deprecated public ifc
     */
    public void setDisableFallback(boolean disableIfTrue) {
        final Message m = Message.obtain();
        m.what = SET_DISABLE_FALLBACK;
        m.obj = Boolean.valueOf(disableIfTrue);

        mWorker.runMessage(m);
    }




    public void hardKill() {
        final Message m = Message.obtain();
        m.what = KILL_WORKER;

        mWorker.runMessage(m);
    }

    /////////////////////////////////////////////////////////
    // For testing, to allow for Mocking.

    /* package */ boolean isDead() {
        return mWorker.isDead();
    }

    protected MPDbAdapter makeDbAdapter(Context context) {
        return new MPDbAdapter(context);
    }

    protected SpressoConfig getConfig(Context context, boolean isDebug) {
        return SpressoConfig.readConfig(context, isDebug);
    }

    protected ServerMessage getPoster() {
        return new ServerMessage();
    }

    ////////////////////////////////////////////////////

    static class EventDescription {
        public EventDescription(String eventName, JSONObject properties, String token, long time, String version, String deviceId) {
            this.eventName = eventName;
            this.properties = properties;
            this.token = token;
            this.timeInMs = time;
            this.v = version;
            this.deviceId = deviceId;
        }

        public String getEventName() {
            return eventName;
        }

        public long getTimeInMs() {
			return timeInMs;
		}

		public JSONObject getProperties() {
            return properties;
        }

        public String getToken() {
            return token;
        }
        
        public String getDeviceId() {
			return deviceId;
		}

		public String getV() {
			return v;
		}

		private final String eventName;
        private final JSONObject properties;
        private final String token;
        private final long timeInMs;
        private final String deviceId;
        private final String v;
        
    }

    // Sends a message if and only if we are running with Spresso Message log enabled.
    // Will be called from the Spresso thread.
    private void logAboutMessageToSpresso(String message) {
        if (mLogSpressoMessages.get() || SpressoConfig.DEBUG) {
            Log.i(LOGTAG, message + " (Thread " + Thread.currentThread().getId() + ")");
        }
    }

    // Worker will manage the (at most single) IO thread associated with
    // this AnalyticsMessages instance.
    // XXX: Worker class is unnecessary, should be just a subclass of HandlerThread
    private class Worker {
        public Worker() {
            mHandler = restartWorkerThread();
        }

        public boolean isDead() {
            synchronized(mHandlerLock) {
                return mHandler == null;
            }
        }

        public void runMessage(Message msg) {
            synchronized(mHandlerLock) {
                if (mHandler == null) {
                    // We died under suspicious circumstances. Don't try to send any more events.
                    logAboutMessageToSpresso("Dead spresso worker dropping a message: " + msg);
                } else {
                    mHandler.sendMessage(msg);
                }
            }
        }

        // NOTE that the returned worker will run FOREVER, unless you send a hard kill
        // (which you really shouldn't)
        private Handler restartWorkerThread() {
            final HandlerThread thread = new HandlerThread("com.spresso.android.AnalyticsWorker", Thread.MIN_PRIORITY);
            thread.start();
            final Handler ret = new AnalyticsMessageHandler(thread.getLooper());
            return ret;
        }

        private class AnalyticsMessageHandler extends Handler {
            public AnalyticsMessageHandler(Looper looper) {
                super(looper);
                mDbAdapter = null;
                mSeenSurveys = new HashSet<Integer>();
                mDisableFallback = mConfig.getDisableFallback();
                mFlushInterval = mConfig.getFlushInterval();
                mSystemInformation = new SystemInformation(mContext);
            }

            @Override
            public void handleMessage(Message msg) {
                if (mDbAdapter == null) {
                    mDbAdapter = makeDbAdapter(mContext);
                    mDbAdapter.cleanupEvents(System.currentTimeMillis() - mConfig.getDataExpiration(), MPDbAdapter.Table.EVENTS);
                    mDbAdapter.cleanupEvents(System.currentTimeMillis() - mConfig.getDataExpiration(), MPDbAdapter.Table.PEOPLE);
                }

                try {
                    int queueDepth = -1;

                    if (msg.what == SET_FLUSH_INTERVAL) {
                        final Long newIntervalObj = (Long) msg.obj;
                        logAboutMessageToSpresso("Changing flush interval to " + newIntervalObj);
                        mFlushInterval = newIntervalObj.longValue();
                        removeMessages(FLUSH_QUEUE);
                    }
                    else if (msg.what == SET_DISABLE_FALLBACK) {
                        final Boolean disableState = (Boolean) msg.obj;
                        logAboutMessageToSpresso("Setting fallback to " + disableState);
                        mDisableFallback = disableState.booleanValue();
                    }
                    else if (msg.what == ENQUEUE_PEOPLE) {
                        final JSONObject message = (JSONObject) msg.obj;

                        logAboutMessageToSpresso("Queuing people record for sending later");
                        logAboutMessageToSpresso("    " + message.toString());

                        queueDepth = mDbAdapter.addJSON(message, MPDbAdapter.Table.PEOPLE);
                    }
                    else if (msg.what == ENQUEUE_EVENTS) {
                        final EventDescription eventDescription = (EventDescription) msg.obj;
                        try {
                            final JSONObject message = prepareEventObject(eventDescription);
                            logAboutMessageToSpresso("Queuing event for sending later");
                            logAboutMessageToSpresso("    " + message.toString());
                            queueDepth = mDbAdapter.addJSON(message, MPDbAdapter.Table.EVENTS);
                        } catch (final JSONException e) {
                            Log.e(LOGTAG, "Exception tracking event " + eventDescription.getEventName(), e);
                        }
                    }
                    else if (msg.what == FLUSH_QUEUE) {
                        logAboutMessageToSpresso("Flushing queue due to scheduled or forced flush");
                        updateFlushFrequency();
                        if (!AnalyticsMessages.sendingEnabled) {
                        	Log.i(LOGTAG,"Spresso Sending is Disabled");
                        }
                        else {
                        	sendAllData(mDbAdapter);
                        }
                        
                    }
                    else if (msg.what == KILL_WORKER) {
                        Log.w(LOGTAG, "Worker received a hard kill. Dumping all events and force-killing. Thread id " + Thread.currentThread().getId());
                        synchronized(mHandlerLock) {
                            mDbAdapter.deleteDB();
                            mHandler = null;
                            Looper.myLooper().quit();
                        }
                    } else {
                        Log.e(LOGTAG, "Unexpected message received by Spresso worker: " + msg);
                    }

                    ///////////////////////////

                    if (queueDepth >= mConfig.getBulkUploadLimit()) {
                        logAboutMessageToSpresso("Flushing queue due to bulk upload limit");
                        updateFlushFrequency();
                        sendAllData(mDbAdapter);
                    } else if (queueDepth > 0 && !hasMessages(FLUSH_QUEUE)) {
                        // The !hasMessages(FLUSH_QUEUE) check is a courtesy for the common case
                        // of delayed flushes already enqueued from inside of this thread.
                        // Callers outside of this thread can still send
                        // a flush right here, so we may end up with two flushes
                        // in our queue, but we're OK with that.

                        logAboutMessageToSpresso("Queue depth " + queueDepth + " - Adding flush in " + mFlushInterval);
                        if (mFlushInterval >= 0) {
                            sendEmptyMessageDelayed(FLUSH_QUEUE, mFlushInterval);
                        }
                    }
                } catch (final RuntimeException e) {
                    Log.e(LOGTAG, "Worker threw an unhandled exception", e);
                    synchronized (mHandlerLock) {
                        mHandler = null;
                        try {
                            Looper.myLooper().quit();
                            Log.e(LOGTAG, "Spresso will not process any more analytics messages", e);
                        } catch (final Exception tooLate) {
                            Log.e(LOGTAG, "Could not halt looper", tooLate);
                        }
                    }
                }
            }// handleMessage

  

            private boolean isOnline() {
                boolean isOnline;
                try {
                    final ConnectivityManager cm =
                            (ConnectivityManager) mContext.getSystemService(Context.CONNECTIVITY_SERVICE);
                    final NetworkInfo netInfo = cm.getActiveNetworkInfo();
                    isOnline = netInfo != null && netInfo.isConnectedOrConnecting();
                    if (SpressoConfig.DEBUG) Log.d(LOGTAG, "ConnectivityManager says we " + (isOnline ? "are" : "are not") + " online");
                } catch (final SecurityException e) {
                    isOnline = true;
                    if (SpressoConfig.DEBUG) Log.d(LOGTAG, "Don't have permission to check connectivity, assuming online");
                }
                return isOnline;
            }

            private void sendAllData(MPDbAdapter dbAdapter) {
                if (isOnline()) {
                    logAboutMessageToSpresso("Sending records to Spresso");
                    if (mDisableFallback) {
                        sendData(dbAdapter, MPDbAdapter.Table.EVENTS, mConfig.getEventsEndpoint(), null);
                    } else {
                        sendData(dbAdapter, MPDbAdapter.Table.EVENTS, mConfig.getEventsEndpoint(), mConfig.getEventsFallbackEndpoint());
                    }
                } else {
                    logAboutMessageToSpresso("Can't send data to Spresso, because the device is not connected to the internet");
                }
            }

            private void sendData(MPDbAdapter dbAdapter, MPDbAdapter.Table table, String endpointUrl, String fallbackUrl) {
                final String[] eventsData = dbAdapter.generateDataString(table);

                if (eventsData != null) {
                    final String lastId = eventsData[0];
                    final String rawMessage = eventsData[1];
                    final ServerMessage poster = getPoster();
                    final ServerMessage.Result eventsPosted = poster.postData(rawMessage, endpointUrl, fallbackUrl);
                    final ServerMessage.Status postStatus = eventsPosted.getStatus();

                    if (postStatus == ServerMessage.Status.SUCCEEDED) {
                        logAboutMessageToSpresso("Posted to " + endpointUrl);
                        logAboutMessageToSpresso("Sent Message\n" + rawMessage);
                        dbAdapter.cleanupEvents(lastId, table);
                    }
                    else if (postStatus == ServerMessage.Status.FAILED_RECOVERABLE) {
                        // Try again later
                        if (!hasMessages(FLUSH_QUEUE)) {
                            sendEmptyMessageDelayed(FLUSH_QUEUE, mFlushInterval);
                        }
                    }
                    else { // give up, we have an unrecoverable failure.
                        dbAdapter.cleanupEvents(lastId, table);
                    }
                }
            }

            private JSONObject getDefaultEventProperties()
                    throws JSONException {
                final JSONObject ret = new JSONObject();

                ret.put("libVersion", SpressoConfig.VERSION);

                // For querying together with data from other libraries
                ret.put("os", "Android");
                ret.put("osVersion", Build.VERSION.RELEASE == null ? "UNKNOWN" : Build.VERSION.RELEASE);

                ret.put("manufacturer", Build.MANUFACTURER == null ? "UNKNOWN" : Build.MANUFACTURER);
                ret.put("brand", Build.BRAND == null ? "UNKNOWN" : Build.BRAND);
                ret.put("model", Build.MODEL == null ? "UNKNOWN" : Build.MODEL);

                final DisplayMetrics displayMetrics = mSystemInformation.getDisplayMetrics();
                ret.put("screenDpi", displayMetrics.densityDpi);
                ret.put("screenHeight", displayMetrics.heightPixels);
                ret.put("screenWidth", displayMetrics.widthPixels);

                final String applicationVersionName = mSystemInformation.getAppVersionName();
                if (null != applicationVersionName)
                    ret.put("appVersion", applicationVersionName);

//                final Boolean hasNFC = mSystemInformation.hasNFC();
//                if (null != hasNFC)
//                    ret.put("$has_nfc", hasNFC.booleanValue());
//
//                final Boolean hasTelephony = mSystemInformation.hasTelephony();
//                if (null != hasTelephony)
//                    ret.put("$has_telephone", hasTelephony.booleanValue());

                final String carrier = mSystemInformation.getCurrentNetworkOperator();
                if (null != carrier)
                    ret.put("carrier", carrier);

                final Boolean isWifi = mSystemInformation.isWifiConnected();
                if (null != isWifi)
                    ret.put("wifi", isWifi.booleanValue());

//                final Boolean isBluetoothEnabled = mSystemInformation.isBluetoothEnabled();
//                if (isBluetoothEnabled != null)
//                    ret.put("$bluetooth_enabled", isBluetoothEnabled);
                


                return ret;
            }

            private JSONObject prepareEventObject(EventDescription eventDescription) throws JSONException {
                final JSONObject eventObj = new JSONObject();
                final JSONObject eventProperties = eventDescription.getProperties();
                final JSONObject sendProperties = getDefaultEventProperties();
                sendProperties.put("token", eventDescription.getToken());
                if (eventProperties != null) {
                    for (final Iterator<?> iter = eventProperties.keys(); iter.hasNext();) {
                        final String key = (String) iter.next();
                        sendProperties.put(key, eventProperties.get(key));
                    }
                }
                eventObj.put("event", eventDescription.getEventName());
                eventObj.put("properties", sendProperties);
                eventObj.put("utcTimestampMs", eventDescription.getTimeInMs());
                eventObj.put("v", eventDescription.getV());
                eventObj.put("deviceId", eventDescription.getDeviceId());

                return eventObj;
            }

            private MPDbAdapter mDbAdapter;
            private final Set<Integer> mSeenSurveys;
            private long mFlushInterval; // XXX remove when associated deprecated APIs are removed
            private boolean mDisableFallback; // XXX remove when associated deprecated APIs are removed
        }// AnalyticsMessageHandler

        private void updateFlushFrequency() {
            final long now = System.currentTimeMillis();
            final long newFlushCount = mFlushCount + 1;

            if (mLastFlushTime > 0) {
                final long flushInterval = now - mLastFlushTime;
                final long totalFlushTime = flushInterval + (mAveFlushFrequency * mFlushCount);
                mAveFlushFrequency = totalFlushTime / newFlushCount;

                final long seconds = mAveFlushFrequency / 1000;
                logAboutMessageToSpresso("Average send frequency approximately " + seconds + " seconds.");
            }

            mLastFlushTime = now;
            mFlushCount = newFlushCount;
        }

        private final Object mHandlerLock = new Object();
        private Handler mHandler;
        private long mFlushCount = 0;
        private long mAveFlushFrequency = 0;
        private long mLastFlushTime = -1;
        private SystemInformation mSystemInformation;
    }

    /////////////////////////////////////////////////////////

    public static boolean isSendingEnabled() {
		return sendingEnabled;
	}

	public static void setSendingEnabled(boolean sendingEnabled) {
		AnalyticsMessages.sendingEnabled = sendingEnabled;
	}

	// Used across thread boundaries
    private final AtomicBoolean mLogSpressoMessages;
    private final Worker mWorker;
    private final Context mContext;
    private static boolean sendingEnabled = true;
    private final SpressoConfig mConfig;

    // Messages for our thread
    private static int ENQUEUE_PEOPLE = 0; // submit events and people data
    private static int ENQUEUE_EVENTS = 1; // push given JSON message to people DB
    private static int FLUSH_QUEUE = 2; // push given JSON message to events DB
    private static int KILL_WORKER = 5; // Hard-kill the worker thread, discarding all events on the eve

    private static int SET_FLUSH_INTERVAL = 4; // XXX REMOVE when associated deprecated APIs are removed
    private static int SET_DISABLE_FALLBACK = 10; // XXX REMOVE when associated deprecated APIs are removed

    private static final ExecutorService CALLBACK_EXECUTOR = Executors.newSingleThreadExecutor();

    private static final String LOGTAG = "SpressoAPI";

    private static final Map<Context, AnalyticsMessages> sInstances = new HashMap<Context, AnalyticsMessages>();

}
