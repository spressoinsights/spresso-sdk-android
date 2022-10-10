package com.aeon.analytics.spresso;

import android.content.ContentValues;
import android.util.Log;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONException;
import org.json.JSONObject;

/* package */ class ServerMessage {

    public enum Status {
        // The post was sent and understood by the Spresso service.
        SUCCEEDED,

        // The post couldn't be sent (for example, because there was no connectivity)
        // but might work later.
        FAILED_RECOVERABLE,

        // The post itself is bad/unsendable (for example, too big for system memory)
        // and shouldn't be retried.
        FAILED_UNRECOVERABLE
    }

    public static class Result {
        /* package */ Result(Status status, String response) {
            mStatus = status;
            mResponse = response;
        }

        public Status getStatus() {
            return mStatus;
        }

        public String getResponse() {
            return mResponse;
        }

        private final String mResponse;
        private final Status mStatus;
    }

    public Result postData(String rawMessage, String endpointUrl, String fallbackUrl) {
        Status status = Status.FAILED_UNRECOVERABLE;
       // final String encodedData = Base64Coder.encodeString(rawMessage);
        String encodedData = rawMessage;
        final List<ContentValues> nameValuePairs = new ArrayList<ContentValues>(1);
        ContentValues value = new ContentValues();
        value.put("datas", encodedData);
        nameValuePairs.add(value);

        final Result baseResult = performRequest(endpointUrl, nameValuePairs, "{\"datas\":" + encodedData + "}");
        final Status baseStatus = baseResult.getStatus();
        String response = baseResult.getResponse();
        if (baseStatus == Status.SUCCEEDED) {
            // Could still be a failure if the application successfully
            // returned an error message...
            if (SpressoConfig.DEBUG) {
                try {
                    final JSONObject verboseResponse = new JSONObject(response);
                    if (verboseResponse.optInt("status") == 1) {
                        status = Status.SUCCEEDED;
                    }
                } catch (final JSONException e) {
                    status = Status.FAILED_UNRECOVERABLE;
                }
            }
            else if (response.equals("1\n")) {
                status = Status.SUCCEEDED;
            }
        }

        if (baseStatus == Status.FAILED_RECOVERABLE && fallbackUrl != null) {
            if (SpressoConfig.DEBUG) Log.d(LOGTAG, "Retrying post with new URL: " + fallbackUrl);
            final Result retryResult = postData(rawMessage, fallbackUrl, null);
            final Status retryStatus = retryResult.getStatus();
            response = retryResult.getResponse();
            if (retryStatus != Status.SUCCEEDED) {
                Log.e(LOGTAG, "Could not post data to Spresso");
            } else {
                status = Status.SUCCEEDED;
            }
        }

        return new Result(status, response);
    }

    public Result get(String endpointUrl, String fallbackUrl) {
        Result ret = performRequest(endpointUrl, null, null);
        if (ret.getStatus() == Status.FAILED_RECOVERABLE && fallbackUrl != null) {
            ret = get(fallbackUrl, null);
        }
        return ret;
    }

    /**
     * Considers *any* response a SUCCESS, callers should check Result.getResponse() for errors
     * and craziness.
     *
     * Will POST if nameValuePairs is not null.
     */
    private Result performRequest(String endpointUrl, List<ContentValues> nameValuePairs, String rawData) {
        Status status = Status.FAILED_UNRECOVERABLE;
        String response = null;
        try {
            // the while(retries) loop is a workaround for a bug in some Android HttpURLConnection
            // libraries- The underlying library will attempt to reuse stale connections,
            // meaning the second (or every other) attempt to connect fails with an EOFException.
            // Apparently this nasty retry logic is the current state of the workaround art.
            int retries = 0;
            boolean succeeded = false;
            while (retries < 3 && !succeeded) {
                InputStream in = null;
                BufferedInputStream bin = null;
                OutputStream out = null;
                BufferedOutputStream bout = null;
                HttpURLConnection connection = null;

                try {
                    final URL url = new URL(endpointUrl);
                    connection = (HttpURLConnection) url.openConnection();
                    if (null != nameValuePairs) {
                        byte[] rawDataByte = rawData.getBytes("UTF-16");
                        connection.setDoOutput(true);
                        connection.setRequestMethod("POST");
                        connection.setRequestProperty("Content-Type", "application/json");
                        connection.setRequestProperty("Accept", "application/json");
                        connection.setFixedLengthStreamingMode(rawDataByte.length);
                        out = connection.getOutputStream();
                        bout = new BufferedOutputStream(out);
                        bout.write(rawDataByte);
                        bout.close();
                        bout = null;
                        out.close();
                        out = null;
                    }
                    in = connection.getInputStream();
                    bin = new BufferedInputStream(in);
                    response = StringUtils.inputStreamToString(in);
                    bin.close();
                    bin = null;
                    in.close();
                    in = null;
                    succeeded = true;
                } catch (final EOFException e) {
                    if (SpressoConfig.DEBUG) Log.d(LOGTAG, "Failure to connect, likely caused by a known issue with Android lib. Retrying.");
                    retries = retries + 1;
                } finally {
                    if (null != bout)
                        try { bout.close(); } catch (final IOException e) { }
                    if (null != out)
                        try { out.close(); } catch (final IOException e) { }
                    if (null != bin)
                        try { bin.close(); } catch (final IOException e) { }
                    if (null != in)
                        try { in.close(); } catch (final IOException e) { }
                    if (null != connection)
                        connection.disconnect();
                }
            }// while
        } catch (final MalformedURLException e) {
            Log.e(LOGTAG, "Cannot iterpret " + endpointUrl + " as a URL", e);
            status = Status.FAILED_UNRECOVERABLE;
        } catch (final IOException e) {
            if (SpressoConfig.DEBUG) Log.d(LOGTAG, "Cannot post message to Spresso Servers (ok, can retry.)");
            status = Status.FAILED_RECOVERABLE;
        } catch (final OutOfMemoryError e) {
            Log.e(LOGTAG, "Cannot post message to Spresso Servers, will not retry.", e);
            status = Status.FAILED_UNRECOVERABLE;
        }

        if (null != response) {
            status = Status.SUCCEEDED;
            if (SpressoConfig.DEBUG) Log.d(LOGTAG, "Request returned:\n" + response);
        }

        return new Result(status, response);
    }

    private static final String LOGTAG = "SpressoAPI";
}
