/*
 * Copyright (C) 2011 Warren Chu
 * Copyright (C) 2007 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.zhaoshouren.android.apps.deskclock.provider;

import static com.zhaoshouren.android.apps.deskclock.DeskClock.DEVELOPER_MODE;
import static com.zhaoshouren.android.apps.deskclock.DeskClock.FORMAT_DATE_TIME;
import static com.zhaoshouren.android.apps.deskclock.DeskClock.TAG;
import static com.zhaoshouren.android.apps.deskclock.util.Alarm.INVALID_ID;

import android.app.AlarmManager;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.os.Handler;
import android.provider.BaseColumns;
import android.provider.Settings;
import android.support.v4.content.CursorLoader;
import android.support.v4.content.Loader;
import android.text.TextUtils;
import android.util.Log;

import com.zhaoshouren.android.apps.deskclock.R;
import com.zhaoshouren.android.apps.deskclock.provider.AlarmDatabase.Sql;
import com.zhaoshouren.android.apps.deskclock.util.Alarm;
import com.zhaoshouren.android.apps.deskclock.util.Days;
import com.zhaoshouren.android.apps.deskclock.util.FormattedTime;

import java.util.Arrays;

public final class AlarmContract {

    public static class Actions {
        /**
         * This action triggers the AlarmBroadcastReceiver as well as the AlarmPlayerService. It is
         * a public action used in the manifest for receiving Alarm broadcasts from the alarm
         * manager.
         */
        public static final String ALERT =
                "com.zhaoshouren.android.apps.deskclock.ACTION_ALARM_ALERT";
        /**
         * A public action sent by AlarmPlayerService when the alarm has stopped sounding for any
         * reason (e.g. because it has been dismissed from AlarmAlertFullScreen, or killed due to an
         * incoming phone call, etc).
         */
        public static final String DONE =
                "com.zhaoshouren.android.apps.deskclock.ACTION_ALARM_DONE";
        /**
         * AlarmAlertFullScreen listens for this broadcast intent, so that other applications can
         * snooze the alarm (after ALARM_ALERT_ACTION and before ALARM_DONE_ACTION).
         */
        public static final String SNOOZE =
                "com.zhaoshouren.android.apps.deskclock.ACTION_ALARM_SNOOZE";
        /**
         * AlarmAlertFullScreen listens for this broadcast intent, so that other applications can
         * dismiss the alarm (after ALARM_ALERT_ACTION and before ALARM_DONE_ACTION).
         */
        public static final String DISMISS =
                "com.zhaoshouren.android.apps.deskclock.ACTION_ALARM_DISMISS";
        /**
         * This is an action used by the AlarmPlayerService to update the UI to show the alarm has
         * been killed.
         */
        public static final String KILLED =
                "com.zhaoshouren.android.apps.deskclock.ACTION_ALARM_KILLED";
        /**
         * Extra in the ACTION_ALARM_KILLED intent to indicate to the user how long the alarm played
         * before being killed.
         */

        public static final String SNOOZE_CANCEL =
                "com.zhaoshouren.android.apps.deskclock.ACTION_ALARM_SNOOZE_CANCEL";
        public static final String PLAY =
                "com.zhaoshouren.android.apps.deskclock.ACTION_ALARM_PLAY";
        public static final String STOP =
                "com.zhaoshouren.android.apps.deskclock.ACTION_ALARM_STOP";
    }

    public static class Alarms implements AlarmsColumns, BaseColumns {

    }

    private static interface AlarmsColumns {
        /**
         * Alarm time in UTC milliseconds from the epoch.
         * <P>
         * SQLite Type: INTEGER
         * </P>
         */
        public static final String TIME = "time";

        /**
         * Selected days bitmask as integer
         * <P>
         * SQLite Type: INTEGER
         * </P>
         */
        public static final String SELECTED_DAYS = "selected_days";

        /**
         * True if alarm is active
         * <P>
         * SQLite Type: INTEGER
         * </P>
         */
        public static final String ENABLED = "enabled";

        /**
         * True if alarm should vibrate
         * <P>
         * SQLite Type: INTEGER
         * </P>
         */
        public static final String VIBRATE = "vibrate";

        /**
         * Label for alarm
         * <P>
         * SQLite Type: TEXT
         * </P>
         */
        public static final String LABEL = "label";

        /**
         * Audio alert to play when alarm triggers
         * <P>
         * SQLite Type: TEXT
         * </P>
         */
        public static final String RINGTONE_URI = "ringtone_uri";

        /**
         * Calculated sort value derived from hour and minutes of alarm
         * <P>
         * SQLite Type: INTEGER
         * </P>
         */
        public static final String SORT = "sort";
    }

    public static class Keys {
        /**
         * 
         */
        public static final String SNOOZE_ALARM_ID = "snooze_id";

        /**
         * 
         */
        public static final String SNOOZE_ALARM_TIME = "snooze_time";

        public static final String ALARM_KILLED_TIMEOUT = "alarm_killed_timeout";
        /**
         * This intent is sent from the notification when the user cancels the snooze alert.
         */
    }

    public static final String PREFERENCES_DESKCLOCK = "com.zhaoshouren.android.apps.deskclock";

    public static final Handler sHandler = new Handler();

    public static void cancelAlarm(final Context context) {
        // Cancel alarm via AlarmManager
        ((AlarmManager) context.getSystemService(Context.ALARM_SERVICE)).cancel(PendingIntent
                .getBroadcast(context, 0, new Intent(Actions.ALERT),
                        PendingIntent.FLAG_CANCEL_CURRENT));

        setStatusBarIcon(context, false);
        setSettingSystemNextAlarmFormatted(context, "");
    }

    /**
     * Cancel snooze if {@code alarmId} matches or is set to {@literal INVALID_ID} (-1)
     * 
     * @param context
     * @param alarmId
     */
    public static void cancelSnooze(final Context context, final int alarmId) {
        final SharedPreferences sharedPreferences =
                context.getSharedPreferences(PREFERENCES_DESKCLOCK, Context.MODE_PRIVATE);
        final int snoozeAlarmId = sharedPreferences.getInt(Keys.SNOOZE_ALARM_ID, INVALID_ID);

        if (alarmId == INVALID_ID || snoozeAlarmId == alarmId) {
            ((NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE))
                    .cancel(snoozeAlarmId);

            sharedPreferences.edit().remove(Keys.SNOOZE_ALARM_ID).remove(Keys.SNOOZE_ALARM_TIME)
                    .commit();
        }
    }

    /**
     * Cleans up {@link Alarm} database by disabling expired alarms and updating times for expired
     * recurring to next recurrence
     * 
     * @param context
     */
    public static void cleanUpAlarms(final Context context) {
        final Cursor cursor = getEnableAlarmsCursor(context);

        if (cursor.moveToFirst()) {
            final long currentTime = System.currentTimeMillis();
            final int[] expiredAlarmIds = new int[cursor.getCount()];
            int index = 0;
            do {
                if (cursor.getLong(cursor.getColumnIndex(AlarmsColumns.TIME)) < currentTime) {
                    if (cursor.getInt(cursor.getColumnIndex(AlarmsColumns.SELECTED_DAYS)) == Days.NO_DAYS_SELECTED) {
                        expiredAlarmIds[index++] =
                                cursor.getInt(cursor.getColumnIndex(BaseColumns._ID));
                    } else {
                        final Alarm alarm = new Alarm(context, cursor);
                        alarm.updateScheduledTime();
                        saveAlarm(context, alarm);
                    }
                }
            } while (cursor.moveToNext());

            final ContentValues contentValues = new ContentValues(1);
            contentValues.put(AlarmsColumns.ENABLED, false);

            final String[] selectionArgs = new String[1];
            selectionArgs[0] = Arrays.toString(expiredAlarmIds);

            context.getContentResolver().update(AlarmProvider.CONTENT_URI, contentValues,
                    Sql.WHERE_ID_IN, selectionArgs);
        } else {
            setSettingSystemNextAlarmFormatted(context, "");
        }
        cursor.close();
    }

    /**
     * Deletes alarm from the database, clears associated snooze alarm if exists, and sets the next
     * alarm with the Alarm Service
     * 
     * @param context
     * @param alarmId
     */
    public static void deleteAlarm(final Context context, final int alarmId) {
        cancelSnooze(context, alarmId);

        context.getContentResolver().delete(
                ContentUris.withAppendedId(AlarmProvider.CONTENT_URI, alarmId), "", null);

        setNextAlarm(context);
    }

    public static void disableAlarm(final Context context, final Alarm alarm) {
        updateAlarm(context, alarm, false);
    }

    public static void enableAlarm(final Context context, final Alarm alarm) {
        updateAlarm(context, alarm, true);
    }

    /**
     * Gets alarm from cursor
     * 
     * @param context
     *            use Activity context (<em>this</em> reference)
     * @param cursor
     *            preferably from a {@link CursorLoader}
     * @return <em>alarm</em> from database or <em>null</em> if cursor is <em>null</em> or
     *         <em>empty</em>
     */
    public static Alarm getAlarm(final Context context, final Cursor cursor) {
        Alarm alarm = null;
        if (cursor.moveToFirst()) {
            alarm = new Alarm(context, cursor);
        }
        cursor.close();
        return alarm;
    }

    /**
     * Gets Alarm from the database, UI blocking operation
     * 
     * @param context
     * @param alarmId
     * @return
     */
    @Deprecated
    public static Alarm getAlarm(final Context context, final int alarmId) {
        final Cursor cursor =
                context.getContentResolver().query(
                        ContentUris.withAppendedId(AlarmProvider.CONTENT_URI, alarmId), Sql.SELECT,
                        null, null, null);
        final Alarm alarm = getAlarm(context, cursor);
        cursor.close();
        return alarm;
    }

    public static Loader<Cursor> getAlarmCursorLoader(final Context context, final int alarmId) {
        return new CursorLoader(context, ContentUris.withAppendedId(AlarmProvider.CONTENT_URI,
                alarmId), Sql.SELECT, null, null, null);
    }

    public static Loader<Cursor> getAlarmsCursorLoader(final Context context) {
        return getAlarmsCursorLoader(context, Sql.SORT_BY_TIME_OF_DAY);
    }

    public static Loader<Cursor>
            getAlarmsCursorLoader(final Context context, final boolean enabled) {
        return getAlarmsCursorLoader(context, enabled, Sql.SORT_BY_TIME);
    }

    public static Loader<Cursor> getAlarmsCursorLoader(final Context context,
            final boolean enabled, final String sort) {
        return new CursorLoader(context, AlarmProvider.CONTENT_URI, Sql.SELECT, enabled
                ? Sql.WHERE_ENABLED : Sql.WHERE_DISABLED, null, sort);
    }

    public static Loader<Cursor> getAlarmsCursorLoader(final Context context, final String sort) {
        return new CursorLoader(context, AlarmProvider.CONTENT_URI, Sql.SELECT, null, null, sort);
    }

    /**
     * Gets enabled alarms sorted by time; operation is blocking
     * 
     * @param context
     * @return
     */
    private static Cursor getEnableAlarmsCursor(final Context context) {
        return context.getContentResolver().query(AlarmProvider.CONTENT_URI, Sql.SELECT,
                Sql.WHERE_ENABLED, null, Sql.SORT_BY_TIME);
    }

    /**
     * Gets Alarm from the database, UI blocking operation
     * 
     * @param context
     * @param alarmId
     * @return
     */
    @Deprecated
    public static Alarm getNextEnabledAlarm(final Context context) {
        final Cursor cursor = getEnableAlarmsCursor(context);
        final Alarm alarm = getAlarm(context, cursor);
        cursor.close();
        return alarm;
    }

    public static String getTickerText(final Context context, final Alarm alarm) {
        return !TextUtils.isEmpty(alarm.label) ? alarm.label : context
                .getString(R.string.default_alarm_ticker_text);
    }

    /**
     * Saves <em>Alarm</em> to database. Operation is blocking
     * 
     * @param context
     * @param alarm
     */
    public static void saveAlarm(final Context context, final Alarm alarm) {
        if (alarm.id == INVALID_ID) {
            ContentUris.parseId(context.getContentResolver().insert(AlarmProvider.CONTENT_URI,
                    alarm.toContentValues()));
        } else {
            final int rowsUpdated =
                    context.getContentResolver().update(
                            ContentUris.withAppendedId(AlarmProvider.CONTENT_URI, alarm.id),
                            alarm.toContentValues(), null, null);

            // TODO: do we really need to check?
            if (rowsUpdated == 0) {
                Log.e(TAG, "Alarms.saveAlarm(Context, Alarm): Failed to update Alarm[" + alarm.id
                        + (TextUtils.isEmpty(alarm.label) ? "" : "|" + alarm.label)
                        + "], alarm doesn't exist in database");
                alarm.id = INVALID_ID;
                saveAlarm(context, alarm);
                return;
            }
        }

        if (alarm.enabled) {
            final SharedPreferences sharedPreferences =
                    context.getSharedPreferences(PREFERENCES_DESKCLOCK, 0);
            if (alarm.toMillis(true) < sharedPreferences.getLong(Keys.SNOOZE_ALARM_TIME, 0)) {
                cancelSnooze(context, alarm.id);
            }
        }

        setNextAlarm(context);
    }

    /**
     * Sets alarm in AlarmManger and StatusBar. This is what will actually launch the alert when the
     * alarm triggers.
     * 
     * @param alarm
     *            Alarm.
     * @param atTimeInMillis
     *            milliseconds since epoch
     */
    public static void setAlarm(final Context context, final Alarm alarm) {
        if (DEVELOPER_MODE) {
            Log.d(TAG, "Alarms.enableAlarmAlert(): Enable (Alarm)snoozeAlert[" + alarm.id
                    + (!TextUtils.isEmpty(alarm.label) ? "|" + alarm.label : "") + "]  for "
                    + alarm.format(FORMAT_DATE_TIME));
        }

        /**
         * XXX: As of gingerbread-release PendingIntents with Parcels are inflated by the
         * AlarmManagerService which would result in a ClassNotFoundException since if we were to
         * pass the Alarm as a Parcel since it does not know about Alarm.class, hence we are passing
         * it as raw data in the form of a byte[]
         */
        // Set slarm via AlarmManager
        ((AlarmManager) context.getSystemService(Context.ALARM_SERVICE)).set(
                AlarmManager.RTC_WAKEUP, alarm.toMillis(true), PendingIntent.getBroadcast(context,
                        0,
                        new Intent(Actions.ALERT).putExtra(Alarm.Keys.RAW_DATA, alarm.toRawData()),
                        PendingIntent.FLAG_CANCEL_CURRENT));

        setStatusBarIcon(context, true);
        setSettingSystemNextAlarmFormatted(context, alarm.format(context
                .getString(FormattedTime.is24HourFormat
                        ? FormattedTime.FORMAT_ABBREV_WEEKDAY_HOUR_MINUTE_24
                        : FormattedTime.FORMAT_ABBREV_WEEKDAY_HOUR_MINUTE_CAP_AM_PM)));
    }

    /**
     * Called at system startup, on time/timezone change, and whenever the user changes alarm
     * settings. Activates snooze if set, otherwise loads all alarms, activates next alert.
     */
    public static void setNextAlarm(final Context context) {
        cleanUpAlarms(context);
        final Alarm alarm = getAlarm(context, getEnableAlarmsCursor(context));
        if (alarm != null) {
            setAlarm(context, alarm);
        }
    }

    /**
     * Save the time of the next alarm as a formatted string into the system settings
     * 
     * @param context
     * @param nextAlarmFormatted
     */
    private static void setSettingSystemNextAlarmFormatted(final Context context,
            final String nextAlarmFormatted) {
        Settings.System.putString(context.getContentResolver(),
                Settings.System.NEXT_ALARM_FORMATTED, nextAlarmFormatted);
    }

    /**
     * XXX
     */
    public static void setSnooze(final Context context, final Alarm alarm) {
        final SharedPreferences preferences =
                context.getSharedPreferences(PREFERENCES_DESKCLOCK, 0);

        final SharedPreferences.Editor sharedPreferencesEditor = preferences.edit();
        sharedPreferencesEditor.putInt(Keys.SNOOZE_ALARM_ID, alarm.id);
        sharedPreferencesEditor.putLong(Keys.SNOOZE_ALARM_TIME, alarm.toMillis(true));
        sharedPreferencesEditor.commit();

        // set Snooze Alarm
        setAlarm(context, alarm);
    }

    /**
     * Send broadcast to be consumed by Status Bar to enable/disable alarm icon. Warning: uses
     * private API
     * 
     * @param context
     * @param enabled
     */
    private static void setStatusBarIcon(final Context context, final boolean enabled) {
        /**
         * XXX: android.intent.action.ALARM_CHANGED is a private API
         */
        context.sendBroadcast(new Intent("android.intent.action.ALARM_CHANGED").putExtra(
                "alarmSet", enabled));
    }

    /**
     * update alarm's enabled state and time accordingly.
     * 
     * @param context
     * @param alarm
     *            to update in the database
     * @param enable
     *            set to <em>true</em> to enable alarm; set to <em>false</em> to disable it.
     */
    private static void updateAlarm(final Context context, final Alarm alarm, final boolean enable) {
        final ContentValues contentValues = new ContentValues(2);
        contentValues.put(Alarms.ENABLED, enable);

        // update scheduled time when enabling alarm
        if (enable) {
            alarm.updateScheduledTime();
            contentValues.put(Alarms.TIME, alarm.toMillis(true));
        } else {
            cancelSnooze(context, alarm.id);
        }

        context.getContentResolver().update(
                ContentUris.withAppendedId(AlarmProvider.CONTENT_URI, alarm.id), contentValues,
                null, null);
    }

    /**
     * Default constructor set to private to prevent instantiation
     */
    private AlarmContract() {
    }
}
