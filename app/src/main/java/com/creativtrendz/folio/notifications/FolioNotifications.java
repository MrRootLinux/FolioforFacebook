package com.creativtrendz.folio.notifications;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.graphics.Color;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.support.v4.app.NotificationCompat;
import android.support.v4.app.NotificationCompat.WearableExtender;
import android.support.v4.app.TaskStackBuilder;
import android.webkit.CookieManager;
import android.webkit.CookieSyncManager;
import android.widget.Toast;

import com.creativetrends.folio.app.R;
import com.creativtrendz.folio.activities.FolioApplication;
import com.creativtrendz.folio.activities.MainActivity;
import com.creativtrendz.folio.services.Connectivity;
import com.creativtrendz.folio.utils.Logger;

import net.grandcentrix.tray.TrayAppPreferences;

import org.jsoup.Jsoup;
import org.jsoup.select.Elements;
import org.xml.sax.SAXException;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;

import nl.matshofman.saxrssreader.RssFeed;
import nl.matshofman.saxrssreader.RssItem;
import nl.matshofman.saxrssreader.RssReader;

public class FolioNotifications extends Service {

    // Facebook URL constants
    private static final String BASE_URL = "https://www.facebook.com";
    private static final String NOTIFICATIONS_URL = "https://www.facebook.com/notifications";
    private static final String NOTIFICATIONS_URL_BACKUP = "https://web.facebook.com/notifications";
    private static final String MESSAGES_URL = "https://m.facebook.com/messages";
    private static final String MESSAGES_URL_BACKUP = "https://mobile.facebook.com/messages";
    private static final String NOTIFICATION_MESSAGE_URL = "https://m.facebook.com/messages";

    // number of trials during notifications or messages checking
    private static final int MAX_RETRY = 3;
    private static final int JSOUP_TIMEOUT = 10000;
    private static final String TAG;

    // HandlerThread, Handler (final to allow synchronization) and its runnable
    private final HandlerThread handlerThread;
    private final Handler handler;
    private static Runnable runnable;

    // volatile boolean to safely skip checking while service is being stopped
    private volatile boolean shouldContinue = true;
    private static String userAgent;
    private TrayAppPreferences trayPreferences;

    /* Well, bad practice. Object name starting with a capital, but it's convenient.
    In order to use my custom logger I just removed Log import and I'm getting an
    instance of my Logger here. Its usage is exactly the same as the usage of Log */
    private final Logger Log;

    // static initializer
    static {
        TAG = FolioNotifications.class.getSimpleName();
    }

    // class constructor, starts a new thread in which checkers are being run
    public FolioNotifications() {
        handlerThread = new HandlerThread("Handler Thread");
        handlerThread.start();
        handler = new Handler(handlerThread.getLooper());
        Log = Logger.getInstance();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        Log.i(TAG, "********** Service created! **********");
        super.onCreate();

        // get TrayPreferences
        trayPreferences = new TrayAppPreferences(getApplicationContext());

        // create a runnable needed by a Handler
        runnable = new HandlerRunnable();

        // start a repeating checking, first run delay (3 seconds)
        handler.postDelayed(runnable, 3000);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        Log.i(TAG, "onDestroy: Service stopping...");
        super.onDestroy();

        synchronized (handler) {
            shouldContinue = false;
            handler.notify();
        }

        handler.removeCallbacksAndMessages(null);
        handlerThread.quit();
    }

    /** A runnable used by the Handler to schedule checking. */
    private class HandlerRunnable implements Runnable {

        public void run() {
            try {

                // get time interval from tray preferences
                final int timeInterval = trayPreferences.getInt("interval_pref", 1800000);
                Log.i(TAG, "Time interval: " + (timeInterval / 1000) + " seconds");

                // time since last check = now - last check
                final long now = System.currentTimeMillis();
                final long sinceLastCheck = now - trayPreferences.getLong("last_check", now);
                final boolean ntfLastStatus = trayPreferences.getBoolean("ntf_last_status", false);
                final boolean msgLastStatus = trayPreferences.getBoolean("msg_last_status", false);

                if ((sinceLastCheck < timeInterval) && ntfLastStatus && msgLastStatus) {
                    final long waitTime = timeInterval - sinceLastCheck;
                    if (waitTime >= 1000) {  // waiting less than a second is just stupid
                        Log.i(TAG, "I'm going to wait. Resuming in: " + (waitTime / 1000) + " seconds");

                        synchronized (handler) {
                            try {
                                handler.wait(waitTime);
                            } catch (InterruptedException ex) {
                                Log.i(TAG, "Thread interrupted");
                            } finally {
                                Log.i(TAG, "Lock is now released");
                            }
                        }

                    }
                }

                // when onDestroy() is run and lock is released, don't go on
                if (shouldContinue) {
                    // start AsyncTasks if there is internet connection
                    if (Connectivity.isConnected(getApplicationContext())) {
                        Log.i(TAG, "Internet connection active. Starting AsyncTasks...");
                        String connectionType = "Wi-Fi";
                        if (Connectivity.isConnectedMobile(getApplicationContext()))
                            connectionType = "Mobile";
                        Log.i(TAG, "Connection Type: " + connectionType);
                        userAgent = trayPreferences.getString("webview_user_agent", System.getProperty("http.agent"));
                        Log.i(TAG, "User Agent: " + userAgent);

                        if (trayPreferences.getBoolean("notifications_activated", false))
                            new RssReaderTask().executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR, (Void) null);
                        if (trayPreferences.getBoolean("messages_activated", false))
                            new CheckMessagesTask().executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR, (Void) null);

                        // save current time (last potentially successful checking)
                        trayPreferences.put("last_check", System.currentTimeMillis());
                    } else
                        Log.i(TAG, "No internet connection. Skip checking.");

                    // set repeat time interval
                    handler.postDelayed(runnable, timeInterval);
                } else
                    Log.i(TAG, "Notified to stop running. Exiting...");

            } catch (RuntimeException re) {
                Log.i(TAG, "RuntimeException caught");
                restartItself();
            }
        }

    }

    /** Notifications checker task: it checks Facebook notifications only. */
    private class RssReaderTask extends AsyncTask<Void, Void, ArrayList<RssItem>> {

        private boolean syncProblemOccurred = false;

        private String getFeed(String connectUrl) {
            try {
                Elements element = Jsoup.connect(connectUrl).userAgent(userAgent).timeout(JSOUP_TIMEOUT)
                        .cookie("https://m.facebook.com", CookieManager.getInstance().getCookie("https://m.facebook.com")).get()
                        .select("div._li").select("div#globalContainer").select("div.fwn").select("a[href*=rss20]");

                return element.attr("href");
            } catch (IllegalArgumentException ex) {
                Log.i("CheckMessagesTask", "Cookie sync problem occurred");
                if (!syncProblemOccurred) {
                    syncProblemToast();
                    syncProblemOccurred = true;
                }
            } catch (IOException ex) {
                ex.printStackTrace();
            }
            return "failure";
        }

        @Override
        protected ArrayList<RssItem> doInBackground(Void... params) {
            ArrayList<RssItem> result = null;
            String feedUrl;
            int tries = 0;

            // sync cookies to get the right data
            syncCookies();

            while (tries++ < MAX_RETRY && result == null) {
                // try to generate rss feed address
                Log.i("RssReaderTask:getFeed", "Trying: " + NOTIFICATIONS_URL);
                String secondPart = getFeed(NOTIFICATIONS_URL);
                if (secondPart.length() < 10) {
                    Log.i("RssReaderTask:getFeed", "Trying: " + NOTIFICATIONS_URL_BACKUP);
                    secondPart = getFeed(NOTIFICATIONS_URL_BACKUP);
                }
                // final generation: base + second part
                if (secondPart.length() > 10)
                    feedUrl = BASE_URL + secondPart;
                else
                    feedUrl = "malformed";

                try {
                    Log.i("RssReaderTask", "doInBackground: Processing... Trial: " + tries);
                    URL url = new URL(feedUrl);
                    RssFeed feed = RssReader.read(url);
                    result = feed.getRssItems();
                } catch (MalformedURLException ex) {
                    Log.i("RssReaderTask", "doInBackground: URL error");
                } catch (SAXException | IOException ex) {
                    Log.i("RssReaderTask", "doInBackground: Feed error");
                }
            }

            return result;
        }

        @Override
        protected void onPostExecute(ArrayList<RssItem> result) {

            /** The first service start ever will display a fake notification.
             *  Not fake actually - the latest one. I've been thinking instead
             *  of avoiding it, it's a nice example how it will work in the future.
             */

            // get the last PubDate (String) from TrayPreferences
            final String savedDate = trayPreferences.getString("saved_date", "nothing");

            // if the saved PubDate is different than the new one it means there is new notification
            // display it only when MainActivity is not active or 'Always notify' is checked
            try {
                if (!result.get(0).getPubDate().toString().equals(savedDate))
                    if (!trayPreferences.getBoolean("activity_visible", false) || trayPreferences.getBoolean("notifications_everywhere", true))
                        notifier(result.get(0).getTitle(), result.get(0).getDescription(), result.get(0).getLink(), false);

                // save the latest PubDate (as a String) to TrayPreferences
                trayPreferences.put("saved_date", result.get(0).getPubDate().toString());

                // save this check status
                trayPreferences.put("ntf_last_status", true);
                Log.i("RssReaderTask", "onPostExecute: Aight biatch ;)");
            } catch (NullPointerException | IndexOutOfBoundsException ex) {
                // save this check status
                trayPreferences.put("ntf_last_status", false);
                Log.i("RssReaderTask", "onPostExecute: Failure");
            }
        }

    }

    /** Messages checker task: it checks new messages only. */
    private class CheckMessagesTask extends AsyncTask<Void, Void, String> {

        boolean syncProblemOccurred = false;

        private String getNumber(String connectUrl) {
            try {
                Elements message = Jsoup.connect(connectUrl).userAgent(userAgent).timeout(JSOUP_TIMEOUT)
                        .cookie("https://m.facebook.com", CookieManager.getInstance().getCookie("https://m.facebook.com")).get()
                        .select("div#viewport").select("div#page").select("div._129-")
                        .select("#messages_jewel").select("span._59tg");

                return message.html();
            } catch (IllegalArgumentException ex) {
                Log.i("CheckMessagesTask", "Cookie sync problem occurred");
                if (!syncProblemOccurred) {
                    syncProblemToast();
                    syncProblemOccurred = true;
                }
            } catch (IOException ex) {
                ex.printStackTrace();
            }
            return "failure";
        }

        @Override
        protected String doInBackground(Void... params) {
            String result = null;
            int tries = 0;

            // sync cookies to get the right data
            syncCookies();

            while (tries++ < MAX_RETRY && result == null) {
                Log.i("CheckMessagesTask", "doInBackground: Processing... Trial: " + tries);

                // try to generate rss feed address
                Log.i("CheckMsgTask:getNumber", "Trying: " + MESSAGES_URL);
                String number = getNumber(MESSAGES_URL);
                if (!number.matches("^[+-]?\\d+$")) {
                    Log.i("CheckMsgTask:getNumber", "Trying: " + MESSAGES_URL_BACKUP);
                    number = getNumber(MESSAGES_URL_BACKUP);
                }
                if (number.matches("^[+-]?\\d+$"))
                    result = number;
            }

            return result;
        }

        @SuppressLint("StringFormatInvalid")
        @Override
        protected void onPostExecute(String result) {
            try {
                // parse a number of unread messages
                int newMessages = Integer.parseInt(result);

                if (!trayPreferences.getBoolean("activity_visible", false) || trayPreferences.getBoolean("notifications_everywhere", true)) {
                    if (newMessages == 1)
                        notifier(getString(R.string.you_have_one_message), null, NOTIFICATION_MESSAGE_URL, true);
                    else if (newMessages > 1)
                        notifier(String.format(getString(R.string.you_have_n_messages), newMessages), null, NOTIFICATION_MESSAGE_URL, true);
                }

                // save this check status
                trayPreferences.put("msg_last_status", true);
                Log.i("CheckMessagesTask", "onPostExecute: Aight biatch ;)");
            } catch (NumberFormatException ex) {
                // save this check status
                trayPreferences.put("msg_last_status", false);
                Log.i("CheckMessagesTask", "onPostExecute: Failure");
            }
        }

    }


    /** CookieSyncManager was deprecated in API level 21.
     *  We need it for API level lower than 21 though.
     *  In API level >= 21 it's done automatically.
     */
    @SuppressWarnings("deprecation")
    private void syncCookies() {
        if (Build.VERSION.SDK_INT < 21) {
            CookieSyncManager.createInstance(getApplicationContext());
            CookieSyncManager.getInstance().sync();
        }
    }

    // show a Sync Problem Toast while not being on UI Thread
    private void syncProblemToast() {
        Handler handler = new Handler(Looper.getMainLooper());
        handler.post(new Runnable() {
            @Override
            public void run() {
                Toast.makeText(getApplicationContext(), getString(R.string.sync_problem),
                        Toast.LENGTH_SHORT).show();
            }
        });
    }

    // restart the service from inside the service
    private void restartItself() {
        final Context context = FolioApplication.getContextOfApplication();
        final Intent intent = new Intent(context, FolioNotifications.class);
        context.stopService(intent);
        context.startService(intent);
    }

    @SuppressLint("InlinedApi")
    private void notifier(String title, String summary, String url, boolean isMessage) {

        final String contentTitle;
        if (isMessage)
            contentTitle = getString(R.string.app_name);
        else
            contentTitle = getString(R.string.app_name);


        Log.i(TAG, "Start notification - isMessage: " + isMessage);

        Intent actionIntent = new Intent(this, MainActivity.class);
        actionIntent.putExtra("start_url", "https://m.facebook.com/notifications");
        PendingIntent actionPendingIntent =
                PendingIntent.getActivity(this, 0, actionIntent,
                        PendingIntent.FLAG_UPDATE_CURRENT);

        Intent messageIntent = new Intent(this, MainActivity.class);
        messageIntent.putExtra("start_url", "https://m.facebook.com/messages");
        PendingIntent messagePendingIntent =
                PendingIntent.getActivity(this, 1, messageIntent,
                        PendingIntent.FLAG_UPDATE_CURRENT);

        NotificationCompat.Action action =
                new NotificationCompat.Action.Builder(R.drawable.ic_public,
                        getString(R.string.app_name), actionPendingIntent)
                        .build();

        NotificationCompat.Action message =
                new NotificationCompat.Action.Builder(R.drawable.ic_messenger_new,
                        getString(R.string.app_name), messagePendingIntent)
                        .build();


        NotificationCompat.Builder mBuilder =
                new NotificationCompat.Builder(this)
                        .setStyle(new NotificationCompat.BigTextStyle().bigText(title))
                        .setSmallIcon(R.drawable.ic_stat_f)
                        .setColor(getResources().getColor(R.color.PrimaryDarkColor))
                        .setContentTitle(contentTitle)
                        .setContentText(title)
                        .setTicker(title)
                        .setWhen(System.currentTimeMillis())
                        .extend(new WearableExtender().addAction(action))
                        .setAutoCancel(true);



        if (!isMessage) {
            Intent allNotificationsIntent = new Intent(this, MainActivity.class);
            allNotificationsIntent.putExtra("start_url", "https://m.facebook.com/notifications");
            allNotificationsIntent.setAction("ALL_NOTIFICATIONS_ACTION");
            PendingIntent piAllNotifications = PendingIntent.getActivity(getApplicationContext(), 0, allNotificationsIntent, 0);
            mBuilder.addAction(R.drawable.ic_public, getString(R.string.all_notifications), piAllNotifications);
            mBuilder.extend(new WearableExtender().addAction(message));

        }

        // ringtone
        String ringtoneKey = "ringtone";
        if (isMessage)
            ringtoneKey = "ringtone_msg";

        Uri ringtoneUri = Uri.parse(trayPreferences.getString(ringtoneKey, "content://settings/system/notification_sound"));
        mBuilder.setSound(ringtoneUri);

        // vibration
        if (trayPreferences.getBoolean("vibrate", false))
            mBuilder.setVibrate(new long[] {500, 500});
        else
            mBuilder.setVibrate(new long[] {0L});

        // LED light
        if (trayPreferences.getBoolean("led_light", false)) {
            Resources resources = getResources(), systemResources = Resources.getSystem();
            mBuilder.setLights(Color.CYAN,
                    resources.getInteger(systemResources.getIdentifier("config_defaultNotificationLedOn", "integer", "android")),
                    resources.getInteger(systemResources.getIdentifier("config_defaultNotificationLedOff", "integer", "android")));
        }

        // priority for Heads-up
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN)
            mBuilder.setPriority(Notification.PRIORITY_HIGH);

        // intent with notification url in extra
        Intent intent = new Intent(this, MainActivity.class);
        intent.putExtra("start_url", url);
        intent.setAction("NOTIFICATION_URL_ACTION");

        // final notification building
        TaskStackBuilder stackBuilder = TaskStackBuilder.create(this);
        stackBuilder.addParentStack(MainActivity.class);
        stackBuilder.addNextIntent(intent);
        PendingIntent resultPendingIntent = PendingIntent.getActivity(getApplicationContext(), 0, intent, PendingIntent.FLAG_UPDATE_CURRENT);
        mBuilder.setContentIntent(resultPendingIntent);
        mBuilder.setOngoing(false);
        Notification note = mBuilder.build();

        // LED light flag
        if (trayPreferences.getBoolean("led_light", false))
            note.flags |= Notification.FLAG_SHOW_LIGHTS;

        // display a notification
        NotificationManager mNotificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);

        // because message notifications are displayed separately
        if (isMessage)
            mNotificationManager.notify(1, note);
        else
            mNotificationManager.notify(0, note);
    }

    public static void clearNotifications() {
        NotificationManager notificationManager = (NotificationManager)
                FolioApplication.getContextOfApplication().getSystemService(Context.NOTIFICATION_SERVICE);

        notificationManager.cancel(0);
    }

    public static void clearMessages() {
        NotificationManager notificationManager = (NotificationManager)
                FolioApplication.getContextOfApplication().getSystemService(Context.NOTIFICATION_SERVICE);
        notificationManager.cancel(1);
    }

}
