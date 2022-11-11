package com.spressoinsights.spresso;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.provider.Settings.Secure;
import android.util.Log;

import java.text.SimpleDateFormat;
import java.util.ConcurrentModificationException;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.TimeZone;
import java.util.UUID;
import java.util.concurrent.Future;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Core class for interacting with Spresso Analytics.
 *
 * <p>Call {@link #getInstance(Context, String)} with
 * your main application activity and your Spresso API token as arguments
 * an to get an instance you can use to report how users are using your
 * application.
 *
 * <p>Once you have an instance, you can send events to Spresso
 * using {@link #track(String, JSONObject)}, and update People Analytics
 * records with {@link #getPeople()}
 *
 * <p>The Spresso library will periodically send information to
 * Spresso servers, so your application will need to have
 * <tt>android.permission.INTERNET</tt>. In addition, to preserve
 * battery life, messages to Spresso servers may not be sent immediately
 * when you call <tt>track</tt> or {@link People#set(String, Object)}.
 * The library will send messages periodically throughout the lifetime
 * of your application, but you will need to call {@link #flush()}
 * before your application is completely shutdown to ensure all of your
 * events are sent.
 *
 * <p>A typical use-case for the library might look like this:
 *
 * <pre>
 * {@code
 * public class MainActivity extends Activity {
 *      Spresso Spresso;
 *
 *      public void onCreate(Bundle saved) {
 *          mSpresso = SpressoAPI.getInstance(this, "YOUR SPRESSO API TOKEN");
 *          ...
 *      }
 *
 *      public void whenSomethingInterestingHappens(int flavor) {
 *          JSONObject properties = new JSONObject();
 *          properties.put("flavor", flavor);
 *          mSpresso.track("Something Interesting Happened", properties);
 *          ...
 *      }
 *
 *      public void onDestroy() {
 *          mSpresso.flush();
 *          super.onDestroy();
 *      }
 * }
 * }
 * </pre>
 *
 * <p>In addition to this documentation, you may wish to take a look at
 * <a href="https://github.com/spresso/sample-android-spresso-integration">the Spresso sample Android application</a>.
 * It demonstrates a variety of techniques, including
 * updating People Analytics records with {@link People} and registering for
 * and receiving push notifications with {@link People#initPushHandling(String)}.
 *
 * <p>There are also <a href="https://spresso.com/docs/">step-by-step getting started documents</a>
 * available at spresso.com
 *
 * @see <a href="https://spresso.com/docs/integration-libraries/android">getting started documentation for tracking events</a>
 * @see <a href="https://spresso.com/docs/people-analytics/android">getting started documentation for People Analytics</a>
 * @see <a href="https://spresso.com/docs/people-analytics/android-push">getting started with push notifications for Android</a>
 * @see <a href="https://github.com/spresso/sample-android-spresso-integration">The Spresso Android sample application</a>
 */
public class Spresso {
    
    /**
     * String version of the library.
     */
    public static final String VERSION = SpressoConfig.VERSION;
    private static final int SESSION_INACTIVITY_TIME = 5 * 60 * 1000; //5mins

    public static final String SPRESSO_EVENT_CREATE_ORDER = "spresso_create_order";
    public static final String SPRESSO_EVENT_GLIMPSE_PLE = "spresso_glimpse_ple";
    public static final String SPRESSO_EVENT_GLIMPSE_PRODUCT_PLE = "spresso_glimpse_product_ple";
    public static final String SPRESSO_EVENT_VIEW_PAGE = "spresso_screen_view";
    public static final String SPRESSO_EVENT_PURCHASE_VARIANT = "spresso_purchase_variant";
    public static final String SPRESSO_EVENT_ADD_TO_CART = "spresso_tap_add_to_cart";
    public static final String SPRESSO_EVENT_VIEW_PRODUCT = "spresso_view_pdp";
    
    
    Spresso(Context context, Future<SharedPreferences> referrerPreferences, String token, boolean debug) {
        mContext = context;
        mToken = token;
        mPeople = new PeopleImpl();
        mMessages = getAnalyticsMessages();
        
        final SharedPreferencesLoader.OnPrefsLoadedListener listener = new SharedPreferencesLoader.OnPrefsLoadedListener() {
            @Override
            public void onPrefsLoaded(SharedPreferences preferences) {
                final JSONArray records = PersistentProperties.waitingPeopleRecordsForSending(preferences);
                if (null != records) {
                    sendAllPeopleRecords(records);
                }
            }
        };
        
        final Future<SharedPreferences> storedPreferences = sPrefsLoader.loadPreferences(context, "com.boxed.spresso.SpressoAPI_" + token, listener);
        mPersistentProperties = new PersistentProperties(referrerPreferences, storedPreferences);
        
        String androidId = Secure.getString(context.getContentResolver(),
                Secure.ANDROID_ID);
        //Log.i(LOGTAG,"AndroidID=" + androidId);
        mPersistentProperties.setDeviceId( (androidId != null ? androidId : getDistinctId()));
    }

    public static Spresso getInstance(Context context, String orgId, boolean isDebug) {
        if (null == context) {
            return null;
        }
        String token = "some token";
        mDebug = isDebug;
        final AnalyticsMessages msgs = AnalyticsMessages.getInstance(context, mDebug);
        msgs.setOrgId(orgId);
        synchronized (sInstanceMap) {
            final Context appContext = context.getApplicationContext();

            if (null == sReferrerPrefs) {
                sReferrerPrefs = sPrefsLoader.loadPreferences(context, SpressoConfig.REFERRER_PREFS_NAME, null);
            }

            Map <Context, Spresso> instances = sInstanceMap.get(token);
            if (null == instances) {
                instances = new HashMap<Context, Spresso>();
                sInstanceMap.put(token, instances);
            }

            Spresso instance = instances.get(appContext);
            if (null == instance) {
                instance = new Spresso(appContext, sReferrerPrefs, token, isDebug);
                instances.put(appContext, instance);
            }
            return instance;
        }
    }
    
    /**
     * Sets the target frequency of messages to Spresso servers.
     * If no calls to {@link #flush()} are made, the Spresso
     * library attempts to send tracking information in batches at a rate
     * that provides a reasonable compromise between battery life and liveness of data.
     * Callers can override this value, for the whole application, by calling
     * <tt>setFlushInterval</tt>.
     *
     * If milliseconds is negative, Spresso will never flush the data automatically,
     * and require callers to call {@link #flush()} to send data. This can have
     * implications for storage and is not appropriate for most situations.
     *
     * @param context the execution context associated with this application, probably
     *      the main application activity.
     * @param milliseconds the target number of milliseconds between automatic flushes.
     *      this value is advisory, actual flushes may be more or less frequent
     * @deprecated in 4.0.0, use com.spresso.android.MPConfig.FlushInterval application metadata instead
     */
    @Deprecated
    public static void setFlushInterval(Context context, long milliseconds) {
        Log.i(
                LOGTAG,
                "Spresso.setFlushInterval is deprecated.\n" +
                        "    To set a custom Spresso flush interval for your application, add\n" +
                        "    <meta-data android:name=\"com.spresso.android.MPConfig.FlushInterval\" android:value=\"YOUR_INTERVAL\" />\n" +
                        "    to the <application> section of your AndroidManifest.xml."
        );
        final AnalyticsMessages msgs = AnalyticsMessages.getInstance(context, mDebug);
        msgs.setFlushInterval(milliseconds);
    }
    
    /**
     * By default, if the Spresso cannot contact the API server over HTTPS,
     * it will attempt to contact the server via regular HTTP. To disable this
     * behavior, call enableFallbackServer(context, false)
     *
     * @param context the execution context associated with this context.
     * @param enableIfTrue if true, the library will fall back to using http
     *      when https is unavailable.
     * @deprecated in 4.0.0, use com.spresso.android.MPConfig.EventsFallbackEndpoint, com.spresso.android.MPConfig.PeopleFallbackEndpoint, or com.spresso.android.MPConfig.DecideFallbackEndpoint instead
     */
    @Deprecated
    public static void enableFallbackServer(Context context, boolean enableIfTrue) {
        Log.i(
                LOGTAG,
                "Spresso.enableFallbackServer is deprecated.\n" +
                        "    To disable fallback in your application, add\n" +
                        "    <meta-data android:name=\"com.spresso.android.MPConfig.DisableFallback\" android:value=\"true\" />\n" +
                        "    to the <application> section of your AndroidManifest.xml."
        );
        final AnalyticsMessages msgs = AnalyticsMessages.getInstance(context, mDebug);
        msgs.setDisableFallback(! enableIfTrue);
    }
    
    /**
     * Associate all future calls to {@link #track(String, JSONObject)} with the user identified by
     * the given distinct id.
     *
     * <p>This call does not identify the user for People Analytics;
     * to do that, see {@link People#identify(String)}. Spresso recommends using
     * the same distinct_id for both calls, and using a distinct_id that is easy
     * to associate with the given user, for example, a server-side account identifier.
     *
     * <p>Calls to {@link #track(String, JSONObject)} made before corresponding calls to
     * identify will use an internally generated distinct id, which means it is best
     * to call identify early to ensure that your Spresso funnels and retention
     * analytics can continue to track the user throughout their lifetime. We recommend
     * calling identify as early as you can.
     *
     * <p>Once identify is called, the given distinct id persists across restarts of your
     * application.
     *
     * @param u a string uniquely identifying this user. Events sent to
     *     Spresso using the same disinct_id will be considered associated with the
     *     same visitor/customer for retention and funnel reporting, so be sure that the given
     *     value is globally unique for each individual user you intend to track.
     *
     * @see People#identify(String)
     */
    public void identify(String u) {
        //mPersistentProperties.setEventsDistinctId(distinctId);
        mPersistentProperties.setUserId(u);
    }
    
    /**
     * Track an event.
     *
     * <p>Every call to track eventually results in a data point sent to Spresso. These data points
     * are what are measured, counted, and broken down to create your Spresso reports. Events
     * have a string name, and an optional set of name/value pairs that describe the properties of
     * that event.
     *
     * @param eventName The name of the event to send
     * @param properties A JSONObject containing the key value pairs of the properties to include in this event.
     *                   Pass null if no extra properties exist.
     */
    public void track(String eventName, JSONObject properties) {
        
        if (!collectionEnabled) {
            Log.d(LOGTAG, "collection disabled. ");
            return;
        }
        if (SpressoConfig.DEBUG) Log.d(LOGTAG, "track " + eventName);
        
        try {
            final JSONObject messageProps = new JSONObject();
            
            final Map<String, String> referrerProperties = mPersistentProperties.getReferrerProperties();
            for (final Map.Entry<String, String> entry:referrerProperties.entrySet()) {
                final String key = entry.getKey();
                final String value = entry.getValue();
                messageProps.put(key, value);
            }
            
            final JSONObject superProperties = mPersistentProperties.getSuperProperties();
            final Iterator<?> superIter = superProperties.keys();
            while (superIter.hasNext()) {
                final String key = (String) superIter.next();
                messageProps.put(key, superProperties.get(key));
            }
            
            // Don't allow super properties or referral properties to override these fields,
            // but DO allow the caller to override them in their given properties.
            final long time = System.currentTimeMillis();
            // messageProps.put("time", time);
            // messageProps.put("distinct_id", getDistinctId());
            if (getUserId() != null) {
                messageProps.put("userId", getUserId());
                messageProps.put("isLoggedIn", true);
            } else {
                messageProps.put("isLoggedIn", false);
            }
            messageProps.put("deviceId", getDeviceId());
            
            checkSessionId();
            if (sessionId != null) {
                messageProps.put("sessionId", sessionId);
            }
            messageProps.put("uid", UUID.randomUUID().toString());
            messageProps.put("timezoneOffsetms", TimeZone.getDefault().getOffset(System.currentTimeMillis()));
            
            if (null != properties) {
                final Iterator<?> propIter = properties.keys();
                while (propIter.hasNext()) {
                    final String key = (String) propIter.next();
                    messageProps.put(key, properties.get(key));
                }
            }
            
            final AnalyticsMessages.EventDescription eventDTO = new AnalyticsMessages.EventDescription(eventName, messageProps, mToken, time, SpressoConfig.VERSION, getDeviceId());
            mMessages.eventsMessage(eventDTO);
            
            if (eventName != null && !eventName.equals("glimpseAction")) {
                lastActivityDate = new Date();
            }
        } catch (final JSONException e) {
            Log.e(LOGTAG, "Exception tracking event " + eventName, e);
        } catch (final ConcurrentModificationException cme) {
            Log.e(LOGTAG, "Concurrent Modification Exception tracking event " + eventName, cme);
        }
    }
    
    /**
     * Push all queued Spresso events and People Analytics changes to Spresso servers.
     *
     * <p>Events and People messages are pushed gradually throughout
     * the lifetime of your application. This means that to ensure that all messages
     * are sent to Spresso when your application is shut down, you will
     * need to call flush() to let the Spresso library know it should
     * send all remaining messages to the server. We strongly recommend
     * placing a call to flush() in the onDestroy() method of
     * your main application activity.
     */
    public void flush() {
        if (SpressoConfig.DEBUG) Log.d(LOGTAG, "flushEvents");
        
        mMessages.postToServer();
    }
    
    /**
     * Returns the string id currently being used to uniquely identify the user associated
     * with events sent using {@link #track(String, JSONObject)}. Before any calls to
     * {@link #identify(String)}, this will be an id automatically generated by the library.
     *
     * <p>The id returned by getDistinctId is independent of the distinct id used to identify
     * any People Analytics properties in Spresso. To read and write that identifier,
     * use {@link People#identify(String)} and {@link People#getDistinctId()}.
     *
     * @return The distinct id associated with event tracking
     *
     * @see #identify(String)
     * @see People#getDistinctId()
     */
    public String getDistinctId() {
        return mPersistentProperties.getEventsDistinctId();
    }
    
    public String getUserId() {
        return mPersistentProperties.getUserId();
    }
    
    public String getDeviceId() {
        return mPersistentProperties.getDeviceId();
    }
    
    public void setDeviceID(String deviceId) {
        mPersistentProperties.setDeviceId(deviceId);
    }
    
    private void checkSessionId() {
        if (sessionId == null) {
            createNewSessionId();
        } else if (lastActivityDate == null) {
            createNewSessionId();
        } else {
            Date now = new Date();
            long timeSinceLastActivity = now.getTime() - lastActivityDate.getTime();
            if (timeSinceLastActivity > SESSION_INACTIVITY_TIME) {
                createNewSessionId();
            }
        }
    }
    
    public void createNewSessionId() {
        String deviceId = getDeviceId();
        if (deviceId != null && deviceId.length() > 0) {
            Date now = new Date();
            sessionId = deviceId + "-" + now.getTime();
        } else {
            sessionId = null;
        }
    }
    
    public JSONObject getSuperProperties() {
        return mPersistentProperties.getSuperProperties();
    }
    
    /**
     * Register properties that will be sent with every subsequent call to {@link #track(String, JSONObject)}.
     *
     * <p>SuperProperties are a collection of properties that will be sent with every event to Spresso,
     * and persist beyond the lifetime of your application.
     *
     * <p>Setting a superProperty with registerSuperProperties will store a new superProperty,
     * possibly overwriting any existing superProperty with the same name (to set a
     * superProperty only if it is currently unset, use {@link #registerSuperPropertiesOnce(JSONObject)})
     *
     * <p>SuperProperties will persist even if your application is taken completely out of memory.
     * to remove a superProperty, call {@link #unregisterSuperProperty(String)} or {@link #clearSuperProperties()}
     *
     * @param superProperties    A JSONObject containing super properties to register
     * @see #registerSuperPropertiesOnce(JSONObject)
     * @see #unregisterSuperProperty(String)
     * @see #clearSuperProperties()
     */
    public void registerSuperProperties(JSONObject superProperties) {
        mPersistentProperties.registerSuperProperties(superProperties);
    }
    
    /**
     * Remove a single superProperty, so that it will not be sent with future calls to {@link #track(String, JSONObject)}.
     *
     * <p>If there is a superProperty registered with the given name, it will be permanently
     * removed from the existing superProperties.
     * To clear all superProperties, use {@link #clearSuperProperties()}
     *
     * @param superPropertyName name of the property to unregister
     * @see #registerSuperProperties(JSONObject)
     */
    public void unregisterSuperProperty(String superPropertyName) {
        mPersistentProperties.unregisterSuperProperty(superPropertyName);
    }
    
    /**
     * Register super properties for events, only if no other super property with the
     * same names has already been registered.
     *
     * <p>Calling registerSuperPropertiesOnce will never overwrite existing properties.
     *
     * @param superProperties A JSONObject containing the super properties to register.
     * @see #registerSuperProperties(JSONObject)
     */
    public void registerSuperPropertiesOnce(JSONObject superProperties) {
        mPersistentProperties.registerSuperPropertiesOnce(superProperties);
    }
    
    /**
     * Erase all currently registered superProperties.
     *
     * <p>Future tracking calls to Spresso (even those already queued up but not
     * yet sent to Spresso servers) will not be associated with the superProperties registered
     * before this call was made.
     *
     * <p>To remove a single superProperty, use {@link #unregisterSuperProperty(String)}
     *
     * @see #registerSuperProperties(JSONObject)
     */
    public void clearSuperProperties() {
        mPersistentProperties.clearSuperProperties();
    }
    
    /**
     * Returns a Spresso.People object that can be used to set and increment
     * People Analytics properties.
     *
     * @return an instance of {@link People} that you can use to update
     *     records in Spresso People Analytics and manage Spresso Google Cloud Messaging notifications.
     */
    public People getPeople() {
        return mPeople;
    }
    
    public interface People {
        /**
         * Associate future calls to {@link #set(JSONObject)}, {@link #increment(Map)},
         * with a particular People Analytics user.
         *
         * <p>All future calls to the People object will rely on this value to assign
         * and increment properties. The user identification will persist across
         * restarts of your application. We recommend calling
         * People.identify as soon as you know the distinct id of the user.
         *
         * @param distinctId a String that uniquely identifies the user. Users identified with
         *     the same distinct id will be considered to be the same user in Spresso,
         *     across all platforms and devices. We recommend choosing a distinct id
         *     that is meaningful to your other systems (for example, a server-side account
         *     identifier), and using the same distinct id for both calls to People.identify
         *     and {@link Spresso#identify(String)}
         *
         * @see Spresso#identify(String)
         */
        void identify(String distinctId);
        
        /**
         * Sets a single property with the given name and value for this user.
         * The given name and value will be assigned to the user in Spresso People Analytics,
         * possibly overwriting an existing property with the same name.
         *
         * @param propertyName The name of the Spresso property. This must be a String, for example "Zip Code"
         * @param value The value of the Spresso property. For "Zip Code", this value might be the String "90210"
         */
        void set(String propertyName, Object value);
        
        /**
         * Set a collection of properties on the identified user all at once.
         *
         * @param properties a JSONObject containing the collection of properties you wish to apply
         *      to the identified user. Each key in the JSONObject will be associated with
         *      a property name, and the value of that key will be assigned to the property.
         */
        void set(JSONObject properties);
        
        /**
         * Works just like set(), except it will not overwrite existing property values. This is useful for properties like "First login date".
         *
         * @param propertyName The name of the Spresso property. This must be a String, for example "Zip Code"
         * @param value The value of the Spresso property. For "Zip Code", this value might be the String "90210"
         */
        void setOnce(String propertyName, Object value);
        
        /**
         * Like set(), but will not set properties that already exist on a record.
         *
         * @param properties a JSONObject containing the collection of properties you wish to apply
         *      to the identified user. Each key in the JSONObject will be associated with
         *      a property name, and the value of that key will be assigned to the property.
         */
        void setOnce(JSONObject properties);
        
        /**
         * Add the given amount to an existing property on the identified user. If the user does not already
         * have the associated property, the amount will be added to zero. To reduce a property,
         * provide a negative number for the value.
         *
         * @param name the People Analytics property that should have its value changed
         * @param increment the amount to be added to the current value of the named property
         *
         * @see #increment(Map)
         */
        void increment(String name, double increment);
        
        /**
         * Change the existing values of multiple People Analytics properties at once.
         *
         * <p>If the user does not already have the associated property, the amount will
         * be added to zero. To reduce a property, provide a negative number for the value.
         *
         * @param properties A map of String properties names to Long amounts. Each
         *     property associated with a name in the map will have its value changed by the given amount
         *
         * @see #increment(String, double)
         */
        void increment(Map<String, ? extends Number> properties);
        
        /**
         * Appends a value to a list-valued property. If the property does not currently exist,
         * it will be created as a list of one element. If the property does exist and doesn't
         * currently have a list value, the append will be ignored.
         * @param name the People Analytics property that should have it's value appended to
         * @param value the new value that will appear at the end of the property's list
         */
        void append(String name, Object value);
        
        /**
         * Adds values to a list-valued property only if they are not already present in the list.
         * If the property does not currently exist, it will be created with the given list as it's value.
         * If the property exists and is not list-valued, the union will be ignored.
         *
         * @param name name of the list-valued property to set or modify
         * @param value an array of values to add to the property value if not already present
         */
        void union(String name, JSONArray value);
        
        
        /**
         * permanently removes the property with the given name from the user's profile
         * @param name name of a property to unset
         */
        void unset(String name);
        
        /**
         * Track a revenue transaction for the identified people profile.
         *
         * @param amount the amount of money exchanged. Positive amounts represent purchases or income from the customer, negative amounts represent refunds or payments to the customer.
         * @param properties an optional collection of properties to associate with this transaction.
         */
        void trackCharge(double amount, JSONObject properties);
        
        /**
         * Permanently clear the whole transaction history for the identified people profile.
         */
        void clearCharges();
        
        /**
         * Permanently deletes the identified user's record from People Analytics.
         *
         * <p>Calling deleteUser deletes an entire record completely. Any future calls
         * to People Analytics using the same distinct id will create and store new values.
         */
        void deleteUser();
        
        /**
         * Returns the string id currently being used to uniquely identify the user associated
         * with events sent using {@link People#set(String, Object)} and {@link People#increment(String, double)}.
         * If no calls to {@link People#identify(String)} have been made, this method will return null.
         *
         * <p>The id returned by getDistinctId is independent of the distinct id used to identify
         * any events sent with {@link Spresso#track(String, JSONObject)}. To read and write that identifier,
         * use {@link Spresso#identify(String)} and {@link Spresso#getDistinctId()}.
         *
         * @return The distinct id associated with updates to People Analytics
         *
         * @see People#identify(String)
         * @see Spresso#getDistinctId()
         */
        String getDistinctId();
        
        
        
        /**
         * Return an instance of Spresso people with a temporary distinct id.
         * This is used by Spresso Surveys but is likely not needed in your code.
         */
        People withIdentity(String distinctId);
    }
    
    /**
     * Manage verbose logging about messages sent to Spresso.
     *
     * <p>Under ordinary circumstances, the Spresso library will only send messages
     * to the log when errors occur. However, after logPosts is called, Spresso will
     * send messages describing it's communication with the Spresso servers to
     * the system log.
     *
     * <p>Spresso will log its verbose messages tag "Spresso" with priority I("Information")
     */
    public void logPosts() {
        mMessages.logPosts();
    }
    
    
    
    // Package-level access. Used (at least) by GCMReceiver
    // when OS-level events occur.
    /* package */ interface InstanceProcessor {
        void process(Spresso m);
    }
    
    /* package */ static void allInstances(InstanceProcessor processor) {
        synchronized (sInstanceMap) {
            for (final Map<Context, Spresso> contextInstances:sInstanceMap.values()) {
                for (final Spresso instance:contextInstances.values()) {
                    processor.process(instance);
                }
            }
        }
    }
    
    ////////////////////////////////////////////////////////////////////
    // Conveniences for testing. These methods should not be called by
    // non-test client code.
    
    /* package */ AnalyticsMessages getAnalyticsMessages() {
        return AnalyticsMessages.getInstance(mContext, mDebug);
    }
    
    /* package */ void clearPreferences() {
        // Will clear distinct_ids, superProperties,
        // and waiting People Analytics properties. Will have no effect
        // on messages already queued to send with AnalyticsMessages.
        mPersistentProperties.clearPreferences();
    }
    
    ///////////////////////
    
    private class PeopleImpl implements People {
        @Override
        public void identify(String distinctId) {
            mPersistentProperties.setPeopleDistinctId(distinctId);
            pushWaitingPeopleRecord();
        }
        
        @Override
        public void set(JSONObject properties) {
            if (SpressoConfig.DEBUG) Log.d(LOGTAG, "set " + properties.toString());
            
            try {
                final JSONObject message = stdPeopleMessage("$set", properties);
                recordPeopleMessage(message);
            } catch (final JSONException e) {
                Log.e(LOGTAG, "Exception setting people properties", e);
            }
        }
        
        @Override
        public void set(String property, Object value) {
            try {
                set(new JSONObject().put(property, value));
            } catch (final JSONException e) {
                Log.e(LOGTAG, "set", e);
            }
        }
        
        @Override
        public void setOnce(JSONObject properties) {
            if (SpressoConfig.DEBUG) Log.d(LOGTAG, "setOnce " + properties.toString());
            
            try {
                final JSONObject message = stdPeopleMessage("$set_once", properties);
                recordPeopleMessage(message);
            } catch (final JSONException e) {
                Log.e(LOGTAG, "Exception setting people properties");
            }
        }
        
        @Override
        public void setOnce(String property, Object value) {
            try {
                setOnce(new JSONObject().put(property, value));
            } catch (final JSONException e) {
                Log.e(LOGTAG, "set", e);
            }
        }
        
        @Override
        public void increment(Map<String, ? extends Number> properties) {
            final JSONObject json = new JSONObject(properties);
            if (SpressoConfig.DEBUG) Log.d(LOGTAG, "increment " + json.toString());
            try {
                final JSONObject message = stdPeopleMessage("$add", json);
                recordPeopleMessage(message);
            } catch (final JSONException e) {
                Log.e(LOGTAG, "Exception incrementing properties", e);
            }
        }
        
        @Override
        public void increment(String property, double value) {
            final Map<String, Double> map = new HashMap<String, Double>();
            map.put(property, value);
            increment(map);
        }
        
        @Override
        public void append(String name, Object value) {
            try {
                final JSONObject properties = new JSONObject();
                properties.put(name, value);
                final JSONObject message = stdPeopleMessage("$append", properties);
                recordPeopleMessage(message);
            } catch (final JSONException e) {
                Log.e(LOGTAG, "Exception appending a property", e);
            }
        }
        
        @Override
        public void union(String name, JSONArray value) {
            try {
                final JSONObject properties = new JSONObject();
                properties.put(name, value);
                final JSONObject message = stdPeopleMessage("$union", properties);
                recordPeopleMessage(message);
            } catch (final JSONException e) {
                Log.e(LOGTAG, "Exception unioning a property");
            }
        }
        
        @Override
        public void unset(String name) {
            try {
                final JSONArray names = new JSONArray();
                names.put(name);
                final JSONObject message = stdPeopleMessage("$unset", names);
                recordPeopleMessage(message);
            } catch (final JSONException e) {
                Log.e(LOGTAG, "Exception unsetting a property", e);
            }
        }
        
        
        
        
        @Override
        public void trackCharge(double amount, JSONObject properties) {
            final Date now = new Date();
            final SimpleDateFormat dateFormat = new SimpleDateFormat(ENGAGE_DATE_FORMAT_STRING);
            dateFormat.setTimeZone(TimeZone.getTimeZone("UTC"));
            
            try {
                final JSONObject transactionValue = new JSONObject();
                transactionValue.put("$amount", amount);
                transactionValue.put("$time", dateFormat.format(now));
                
                if (null != properties) {
                    for (final Iterator<?> iter = properties.keys(); iter.hasNext();) {
                        final String key = (String) iter.next();
                        transactionValue.put(key, properties.get(key));
                    }
                }
                
                this.append("$transactions", transactionValue);
            } catch (final JSONException e) {
                Log.e(LOGTAG, "Exception creating new charge", e);
            }
        }
        
        /**
         * Permanently clear the whole transaction history for the identified people profile.
         */
        @Override
        public void clearCharges() {
            this.unset("$transactions");
        }
        
        @Override
        public void deleteUser() {
            if (SpressoConfig.DEBUG) Log.d(LOGTAG, "delete");
            try {
                final JSONObject message = stdPeopleMessage("$delete", JSONObject.NULL);
                recordPeopleMessage(message);
            } catch (final JSONException e) {
                Log.e(LOGTAG, "Exception deleting a user");
            }
        }
        
        @Override
        public String getDistinctId() {
            return mPersistentProperties.getPeopleDistinctId();
        }
        
        @Override
        public People withIdentity(final String distinctId) {
            if (null == distinctId) {
                return null;
            }
            return new PeopleImpl() {
                @Override
                public String getDistinctId() {
                    return distinctId;
                }
                
                @Override
                public void identify(String distinctId) {
                    throw new RuntimeException("This SpressoPeople object has a fixed, constant distinctId");
                }
            };
        }
        
        public JSONObject stdPeopleMessage(String actionType, Object properties)
                throws JSONException {
            final JSONObject dataObj = new JSONObject();
            final String distinctId = getDistinctId();
            
            dataObj.put(actionType, properties);
            dataObj.put("$token", mToken);
            dataObj.put("$time", System.currentTimeMillis());
            
            if (null != distinctId) {
                dataObj.put("$distinct_id", getDistinctId());
            }
            
            return dataObj;
        }
    }// PeopleImpl
    
    ////////////////////////////////////////////////////
    
    private void recordPeopleMessage(JSONObject message) {
        if (message.has("$distinct_id")) {
            mMessages.peopleMessage(message);
        } else {
            mPersistentProperties.storeWaitingPeopleRecord(message);
        }
    }
    
    private void pushWaitingPeopleRecord() {
        final JSONArray records = mPersistentProperties.waitingPeopleRecordsForSending();
        if (null != records) {
            sendAllPeopleRecords(records);
        }
    }
    
    // MUST BE THREAD SAFE. Called from crazy places. mPersistentProperties may not exist
    // when this is called (from it's crazy thread)
    private void sendAllPeopleRecords(JSONArray records) {
        for (int i = 0; i < records.length(); i++) {
            try {
                final JSONObject message = records.getJSONObject(i);
                mMessages.peopleMessage(message);
            } catch (final JSONException e) {
                Log.e(LOGTAG, "Malformed people record stored pending identity, will not send it.", e);
            }
        }
    }
    
    public static boolean isCollectionEnabled() {
        return collectionEnabled;
    }
    
    
    public static void setCollectionEnabled(boolean collectionEnabled) {
        //Log.i(LOGTAG, "***setting collecting to : " + collectionEnabled);
        Spresso.collectionEnabled = collectionEnabled;
    }
    
    
    public static void setSendingEnabled (boolean s) {
        //Log.i(LOGTAG, "***setting sending to : " + s);
        AnalyticsMessages.setSendingEnabled(s);
        
    }
    
    private static final String LOGTAG = "Spresso";
    private static final String ENGAGE_DATE_FORMAT_STRING = "yyyy-MM-dd'T'HH:mm:ss";
    
    private final Context mContext;
    private final AnalyticsMessages mMessages;
    private final String mToken;
    private final PeopleImpl mPeople;
    private final PersistentProperties mPersistentProperties;
    
    // Cache of survey assets for showSurveys. Synchronized access only.
    private final Object mCachedSurveyAssetsLock = new Object();
    private int mCachedSurveyActivityHashcode = -1;
    private Bitmap mCachedSurveyBitmap;
    private int mCachedSurveyHighlightColor;
    private static boolean collectionEnabled = true;
    protected static boolean mDebug = false;
    private String sessionId;
    private Date lastActivityDate;
    
    // Maps each token to a singleton Spresso instance
    private static final Map<String, Map<Context, Spresso>> sInstanceMap = new HashMap<String, Map<Context, Spresso>>();
    private static final SharedPreferencesLoader sPrefsLoader = new SharedPreferencesLoader();
    private static Future<SharedPreferences> sReferrerPrefs;
}
