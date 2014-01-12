/*
 * Copyright (C) 2006-2008 The Android Open Source Project
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

package com.android.server.am;

import static android.content.pm.PackageManager.PERMISSION_GRANTED;
import static com.android.internal.util.XmlUtils.readIntAttribute;
import static com.android.internal.util.XmlUtils.readLongAttribute;
import static com.android.internal.util.XmlUtils.writeIntAttribute;
import static com.android.internal.util.XmlUtils.writeLongAttribute;
import static com.android.server.Watchdog.NATIVE_STACKS_OF_INTEREST;
import static org.xmlpull.v1.XmlPullParser.END_DOCUMENT;
import static org.xmlpull.v1.XmlPullParser.START_TAG;

import static com.android.server.am.ActivityStackSupervisor.HOME_STACK_ID;

import android.app.AppOpsManager;
import android.appwidget.AppWidgetManager;
import android.util.ArrayMap;
import com.android.internal.R;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.app.IAppOpsService;
import com.android.internal.app.ProcessStats;
import com.android.internal.os.BackgroundThread;
import com.android.internal.os.BatteryStatsImpl;
import com.android.internal.os.ProcessCpuTracker;
import com.android.internal.os.TransferPipe;
import com.android.internal.util.FastPrintWriter;
import com.android.internal.util.FastXmlSerializer;
import com.android.internal.util.MemInfoReader;
import com.android.internal.util.Preconditions;
import com.android.server.AppOpsService;
import com.android.server.AttributeCache;
import com.android.server.IntentResolver;
import com.android.internal.app.ProcessMap;
import com.android.server.SystemServer;
import com.android.server.Watchdog;
import com.android.server.am.ActivityStack.ActivityState;
import com.android.server.firewall.IntentFirewall;
import com.android.server.pm.UserManagerService;
import com.android.server.wm.AppTransition;
import com.android.server.wm.StackBox;
import com.android.server.wm.WindowManagerService;
import com.google.android.collect.Lists;
import com.google.android.collect.Maps;

import dalvik.system.Zygote;

import libcore.io.IoUtils;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlSerializer;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.ActivityManager.RunningTaskInfo;
import android.app.ActivityManager.StackBoxInfo;
import android.app.ActivityManager.StackInfo;
import android.app.ActivityManagerNative;
import android.app.ActivityOptions;
import android.app.ActivityThread;
import android.app.AlertDialog;
import android.app.AppGlobals;
import android.app.ApplicationErrorReport;
import android.app.Dialog;
import android.app.IActivityController;
import android.app.IApplicationThread;
import android.app.IInstrumentationWatcher;
import android.app.INotificationManager;
import android.app.IProcessObserver;
import android.app.IServiceConnection;
import android.app.IStopUserCallback;
import android.app.IThumbnailReceiver;
import android.app.IUiAutomationConnection;
import android.app.IUserSwitchObserver;
import android.app.Instrumentation;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.backup.IBackupManager;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ComponentCallbacks2;
import android.content.ComponentName;
import android.content.ContentProvider;
import android.content.ContentResolver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.IContentProvider;
import android.content.IIntentReceiver;
import android.content.IIntentSender;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.IntentSender;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.ConfigurationInfo;
import android.content.pm.IPackageDataObserver;
import android.content.pm.IPackageManager;
import android.content.pm.InstrumentationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ParceledListSlice;
import android.content.pm.UserInfo;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.pm.PathPermission;
import android.content.pm.ProviderInfo;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.content.res.CompatibilityInfo;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.net.Proxy;
import android.net.ProxyProperties;
import android.net.Uri;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.Debug;
import android.os.DropBoxManager;
import android.os.Environment;
import android.os.FileObserver;
import android.os.FileUtils;
import android.os.Handler;
import android.os.IBinder;
import android.os.IPermissionController;
import android.os.IRemoteCallback;
import android.os.IUserManager;
import android.os.Looper;
import android.os.Message;
import android.os.Parcel;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.os.SELinux;
import android.os.ServiceManager;
import android.os.StrictMode;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.os.UpdateLock;
import android.os.UserHandle;
import android.provider.Settings;
import android.text.format.DateUtils;
import android.text.format.Time;
import android.util.AtomicFile;
import android.util.EventLog;
import android.util.Log;
import android.util.Pair;
import android.util.PrintWriterPrinter;
import android.util.Slog;
import android.util.SparseArray;
import android.util.TimeUtils;
import android.util.Xml;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

public final class ActivityManagerService extends ActivityManagerNative
        implements Watchdog.Monitor, BatteryStatsImpl.BatteryCallback {
    private static final String USER_DATA_DIR = "/data/user/";
    static final String TAG = "ActivityManager";
    static final String TAG_MU = "ActivityManagerServiceMU";
    static final boolean DEBUG = false;
    static final boolean localLOGV = DEBUG;
    static final boolean DEBUG_BACKUP = localLOGV || false;
    static final boolean DEBUG_BROADCAST = localLOGV || false;
    static final boolean DEBUG_BROADCAST_LIGHT = DEBUG_BROADCAST || false;
    static final boolean DEBUG_BACKGROUND_BROADCAST = DEBUG_BROADCAST || false;
    static final boolean DEBUG_CLEANUP = localLOGV || false;
    static final boolean DEBUG_CONFIGURATION = localLOGV || false;
    static final boolean DEBUG_FOCUS = false;
    static final boolean DEBUG_IMMERSIVE = localLOGV || false;
    static final boolean DEBUG_MU = localLOGV || false;
    static final boolean DEBUG_OOM_ADJ = localLOGV || false;
    static final boolean DEBUG_LRU = localLOGV || false;
    static final boolean DEBUG_PAUSE = localLOGV || false;
    static final boolean DEBUG_POWER = localLOGV || false;
    static final boolean DEBUG_POWER_QUICK = DEBUG_POWER || false;
    static final boolean DEBUG_PROCESS_OBSERVERS = localLOGV || false;
    static final boolean DEBUG_PROCESSES = localLOGV || false;
    static final boolean DEBUG_PROVIDER = localLOGV || false;
    static final boolean DEBUG_RESULTS = localLOGV || false;
    static final boolean DEBUG_SERVICE = localLOGV || false;
    static final boolean DEBUG_SERVICE_EXECUTING = localLOGV || false;
    static final boolean DEBUG_STACK = localLOGV || false;
    static final boolean DEBUG_SWITCH = localLOGV || false;
    static final boolean DEBUG_TASKS = localLOGV || false;
    static final boolean DEBUG_THUMBNAILS = localLOGV || false;
    static final boolean DEBUG_TRANSITION = localLOGV || false;
    static final boolean DEBUG_URI_PERMISSION = localLOGV || false;
    static final boolean DEBUG_USER_LEAVING = localLOGV || false;
    static final boolean DEBUG_VISBILITY = localLOGV || false;
    static final boolean DEBUG_PSS = localLOGV || false;
    static final boolean DEBUG_LOCKSCREEN = localLOGV || false;
    static final boolean VALIDATE_TOKENS = false;
    static final boolean SHOW_ACTIVITY_START_TIME = true;

    // Control over CPU and battery monitoring.
    static final long BATTERY_STATS_TIME = 30*60*1000;      // write battery stats every 30 minutes.
    static final boolean MONITOR_CPU_USAGE = true;
    static final long MONITOR_CPU_MIN_TIME = 5*1000;        // don't sample cpu less than every 5 seconds.
    static final long MONITOR_CPU_MAX_TIME = 0x0fffffff;    // wait possibly forever for next cpu sample.
    static final boolean MONITOR_THREAD_CPU_USAGE = false;

    // The flags that are set for all calls we make to the package manager.
    static final int STOCK_PM_FLAGS = PackageManager.GET_SHARED_LIBRARY_FILES;

    private static final String SYSTEM_DEBUGGABLE = "ro.debuggable";

    static final boolean IS_USER_BUILD = "user".equals(Build.TYPE);

    // Maximum number of recent tasks that we can remember.
    static final int MAX_RECENT_TASKS = ActivityManager.isLowRamDeviceStatic() ? 10 : 20;

    // Amount of time after a call to stopAppSwitches() during which we will
    // prevent further untrusted switches from happening.
    static final long APP_SWITCH_DELAY_TIME = 5*1000;

    // How long we wait for a launched process to attach to the activity manager
    // before we decide it's never going to come up for real.
    static final int PROC_START_TIMEOUT = 10*1000;

    // How long we wait for a launched process to attach to the activity manager
    // before we decide it's never going to come up for real, when the process was
    // started with a wrapper for instrumentation (such as Valgrind) because it
    // could take much longer than usual.
    static final int PROC_START_TIMEOUT_WITH_WRAPPER = 300*1000;

    // How long to wait after going idle before forcing apps to GC.
    static final int GC_TIMEOUT = 5*1000;

    // The minimum amount of time between successive GC requests for a process.
    static final int GC_MIN_INTERVAL = 60*1000;

    // The minimum amount of time between successive PSS requests for a process.
    static final int FULL_PSS_MIN_INTERVAL = 10*60*1000;

    // The minimum amount of time between successive PSS requests for a process
    // when the request is due to the memory state being lowered.
    static final int FULL_PSS_LOWERED_INTERVAL = 2*60*1000;

    // The rate at which we check for apps using excessive power -- 15 mins.
    static final int POWER_CHECK_DELAY = (DEBUG_POWER_QUICK ? 2 : 10) * 60*1000;

    // The minimum sample duration we will allow before deciding we have
    // enough data on wake locks to start killing things.
    static final int WAKE_LOCK_MIN_CHECK_DURATION = (DEBUG_POWER_QUICK ? 1 : 2) * 60*1000;

    // The minimum sample duration we will allow before deciding we have
    // enough data on CPU usage to start killing things.
    static final int CPU_MIN_CHECK_DURATION = (DEBUG_POWER_QUICK ? 1 : 5) * 60*1000;

    // How long we allow a receiver to run before giving up on it.
    static final int BROADCAST_FG_TIMEOUT = 10*1000;
    static final int BROADCAST_BG_TIMEOUT = 60*1000;

    // How long we wait until we timeout on key dispatching.
    static final int KEY_DISPATCHING_TIMEOUT = 5*1000;

    // How long we wait until we timeout on key dispatching during instrumentation.
    static final int INSTRUMENTATION_KEY_DISPATCHING_TIMEOUT = 60*1000;

    // Amount of time we wait for observers to handle a user switch before
    // giving up on them and unfreezing the screen.
    static final int USER_SWITCH_TIMEOUT = 2*1000;

    // Maximum number of users we allow to be running at a time.
    static final int MAX_RUNNING_USERS = 3;

    // How long to wait in getAssistContextExtras for the activity and foreground services
    // to respond with the result.
    static final int PENDING_ASSIST_EXTRAS_TIMEOUT = 500;

    // Maximum number of persisted Uri grants a package is allowed
    static final int MAX_PERSISTED_URI_GRANTS = 128;

    static final int MY_PID = Process.myPid();

    static final String[] EMPTY_STRING_ARRAY = new String[0];

    // How many bytes to write into the dropbox log before truncating
    static final int DROPBOX_MAX_SIZE = 256 * 1024;

    /** Run all ActivityStacks through this */
    ActivityStackSupervisor mStackSupervisor;

    public IntentFirewall mIntentFirewall;

    private final boolean mHeadless;

    // Whether we should show our dialogs (ANR, crash, etc) or just perform their
    // default actuion automatically.  Important for devices without direct input
    // devices.
    private boolean mShowDialogs = true;

    /**
     * Description of a request to start a new activity, which has been held
     * due to app switches being disabled.
     */
    static class PendingActivityLaunch {
        final ActivityRecord r;
        final ActivityRecord sourceRecord;
        final int startFlags;
        final ActivityStack stack;

        PendingActivityLaunch(ActivityRecord _r, ActivityRecord _sourceRecord,
                int _startFlags, ActivityStack _stack) {
            r = _r;
            sourceRecord = _sourceRecord;
            startFlags = _startFlags;
            stack = _stack;
        }
    }

    final ArrayList<PendingActivityLaunch> mPendingActivityLaunches
            = new ArrayList<PendingActivityLaunch>();

    BroadcastQueue mFgBroadcastQueue;
    BroadcastQueue mBgBroadcastQueue;
    // Convenient for easy iteration over the queues. Foreground is first
    // so that dispatch of foreground broadcasts gets precedence.
    final BroadcastQueue[] mBroadcastQueues = new BroadcastQueue[2];

    BroadcastQueue broadcastQueueForIntent(Intent intent) {
        final boolean isFg = (intent.getFlags() & Intent.FLAG_RECEIVER_FOREGROUND) != 0;
        if (DEBUG_BACKGROUND_BROADCAST) {
            Slog.i(TAG, "Broadcast intent " + intent + " on "
                    + (isFg ? "foreground" : "background")
                    + " queue");
        }
        return (isFg) ? mFgBroadcastQueue : mBgBroadcastQueue;
    }

    BroadcastRecord broadcastRecordForReceiverLocked(IBinder receiver) {
        for (BroadcastQueue queue : mBroadcastQueues) {
            BroadcastRecord r = queue.getMatchingOrderedReceiver(receiver);
            if (r != null) {
                return r;
            }
        }
        return null;
    }

    /**
     * Activity we have told the window manager to have key focus.
     */
    ActivityRecord mFocusedActivity = null;

    /**
     * List of intents that were used to start the most recent tasks.
     */
    private final ArrayList<TaskRecord> mRecentTasks = new ArrayList<TaskRecord>();

    public class PendingAssistExtras extends Binder implements Runnable {
        public final ActivityRecord activity;
        public boolean haveResult = false;
        public Bundle result = null;
        public PendingAssistExtras(ActivityRecord _activity) {
            activity = _activity;
        }
        @Override
        public void run() {
            Slog.w(TAG, "getAssistContextExtras failed: timeout retrieving from " + activity);
            synchronized (this) {
                haveResult = true;
                notifyAll();
            }
        }
    }

    final ArrayList<PendingAssistExtras> mPendingAssistExtras
            = new ArrayList<PendingAssistExtras>();

    /**
     * Process management.
     */
    final ProcessList mProcessList = new ProcessList();

    /**
     * All of the applications we currently have running organized by name.
     * The keys are strings of the application package name (as
     * returned by the package manager), and the keys are ApplicationRecord
     * objects.
     */
    final ProcessMap<ProcessRecord> mProcessNames = new ProcessMap<ProcessRecord>();

    /**
     * Tracking long-term execution of processes to look for abuse and other
     * bad app behavior.
     */
    final ProcessStatsService mProcessStats;

    /**
     * The currently running isolated processes.
     */
    final SparseArray<ProcessRecord> mIsolatedProcesses = new SparseArray<ProcessRecord>();

    /**
     * Counter for assigning isolated process uids, to avoid frequently reusing the
     * same ones.
     */
    int mNextIsolatedProcessUid = 0;

    /**
     * The currently running heavy-weight process, if any.
     */
    ProcessRecord mHeavyWeightProcess = null;

    /**
     * The last time that various processes have crashed.
     */
    final ProcessMap<Long> mProcessCrashTimes = new ProcessMap<Long>();

    /**
     * Information about a process that is currently marked as bad.
     */
    static final class BadProcessInfo {
        BadProcessInfo(long time, String shortMsg, String longMsg, String stack) {
            this.time = time;
            this.shortMsg = shortMsg;
            this.longMsg = longMsg;
            this.stack = stack;
        }

        final long time;
        final String shortMsg;
        final String longMsg;
        final String stack;
    }

    /**
     * Set of applications that we consider to be bad, and will reject
     * incoming broadcasts from (which the user has no control over).
     * Processes are added to this set when they have crashed twice within
     * a minimum amount of time; they are removed from it when they are
     * later restarted (hopefully due to some user action).  The value is the
     * time it was added to the list.
     */
    final ProcessMap<BadProcessInfo> mBadProcesses = new ProcessMap<BadProcessInfo>();

    /**
     * All of the processes we currently have running organized by pid.
     * The keys are the pid running the application.
     *
     * <p>NOTE: This object is protected by its own lock, NOT the global
     * activity manager lock!
     */
    final SparseArray<ProcessRecord> mPidsSelfLocked = new SparseArray<ProcessRecord>();

    /**
     * All of the processes that have been forced to be foreground.  The key
     * is the pid of the caller who requested it (we hold a death
     * link on it).
     */
    abstract class ForegroundToken implements IBinder.DeathRecipient {
        int pid;
        IBinder token;
    }
    final SparseArray<ForegroundToken> mForegroundProcesses = new SparseArray<ForegroundToken>();

    /**
     * List of records for processes that someone had tried to start before the
     * system was ready.  We don't start them at that point, but ensure they
     * are started by the time booting is complete.
     */
    final ArrayList<ProcessRecord> mProcessesOnHold = new ArrayList<ProcessRecord>();

    /**
     * List of persistent applications that are in the process
     * of being started.
     */
    final ArrayList<ProcessRecord> mPersistentStartingProcesses = new ArrayList<ProcessRecord>();

    /**
     * Processes that are being forcibly torn down.
     */
    final ArrayList<ProcessRecord> mRemovedProcesses = new ArrayList<ProcessRecord>();

    /**
     * List of running applications, sorted by recent usage.
     * The first entry in the list is the least recently used.
     */
    final ArrayList<ProcessRecord> mLruProcesses = new ArrayList<ProcessRecord>();

    /**
     * Where in mLruProcesses that the processes hosting activities start.
     */
    int mLruProcessActivityStart = 0;

    /**
     * Where in mLruProcesses that the processes hosting services start.
     * This is after (lower index) than mLruProcessesActivityStart.
     */
    int mLruProcessServiceStart = 0;

    /**
     * List of processes that should gc as soon as things are idle.
     */
    final ArrayList<ProcessRecord> mProcessesToGc = new ArrayList<ProcessRecord>();

    /**
     * Processes we want to collect PSS data from.
     */
    final ArrayList<ProcessRecord> mPendingPssProcesses = new ArrayList<ProcessRecord>();

    /**
     * Last time we requested PSS data of all processes.
     */
    long mLastFullPssTime = SystemClock.uptimeMillis();

    /**
     * This is the process holding what we currently consider to be
     * the "home" activity.
     */
    ProcessRecord mHomeProcess;

    /**
     * This is the process holding the activity the user last visited that
     * is in a different process from the one they are currently in.
     */
    ProcessRecord mPreviousProcess;

    /**
     * The time at which the previous process was last visible.
     */
    long mPreviousProcessVisibleTime;

    /**
     * Which uses have been started, so are allowed to run code.
     */
    final SparseArray<UserStartedState> mStartedUsers = new SparseArray<UserStartedState>();

    /**
     * LRU list of history of current users.  Most recently current is at the end.
     */
    final ArrayList<Integer> mUserLru = new ArrayList<Integer>();

    /**
     * Constant array of the users that are currently started.
     */
    int[] mStartedUserArray = new int[] { 0 };

    /**
     * Registered observers of the user switching mechanics.
     */
    final RemoteCallbackList<IUserSwitchObserver> mUserSwitchObservers
            = new RemoteCallbackList<IUserSwitchObserver>();

    /**
     * Currently active user switch.
     */
    Object mCurUserSwitchCallback;

    /**
     * Packages that the user has asked to have run in screen size
     * compatibility mode instead of filling the screen.
     */
    final CompatModePackages mCompatModePackages;

    /**
     * Set of IntentSenderRecord objects that are currently active.
     */
    final HashMap<PendingIntentRecord.Key, WeakReference<PendingIntentRecord>> mIntentSenderRecords
            = new HashMap<PendingIntentRecord.Key, WeakReference<PendingIntentRecord>>();

    /**
     * Fingerprints (hashCode()) of stack traces that we've
     * already logged DropBox entries for.  Guarded by itself.  If
     * something (rogue user app) forces this over
     * MAX_DUP_SUPPRESSED_STACKS entries, the contents are cleared.
     */
    private final HashSet<Integer> mAlreadyLoggedViolatedStacks = new HashSet<Integer>();
    private static final int MAX_DUP_SUPPRESSED_STACKS = 5000;

    /**
     * Strict Mode background batched logging state.
     *
     * The string buffer is guarded by itself, and its lock is also
     * used to determine if another batched write is already
     * in-flight.
     */
    private final StringBuilder mStrictModeBuffer = new StringBuilder();

    /**
     * Keeps track of all IIntentReceivers that have been registered for
     * broadcasts.  Hash keys are the receiver IBinder, hash value is
     * a ReceiverList.
     */
    final HashMap<IBinder, ReceiverList> mRegisteredReceivers =
            new HashMap<IBinder, ReceiverList>();

    /**
     * Resolver for broadcast intents to registered receivers.
     * Holds BroadcastFilter (subclass of IntentFilter).
     */
    final IntentResolver<BroadcastFilter, BroadcastFilter> mReceiverResolver
            = new IntentResolver<BroadcastFilter, BroadcastFilter>() {
        @Override
        protected boolean allowFilterResult(
                BroadcastFilter filter, List<BroadcastFilter> dest) {
            IBinder target = filter.receiverList.receiver.asBinder();
            for (int i=dest.size()-1; i>=0; i--) {
                if (dest.get(i).receiverList.receiver.asBinder() == target) {
                    return false;
                }
            }
            return true;
        }

        @Override
        protected BroadcastFilter newResult(BroadcastFilter filter, int match, int userId) {
            if (userId == UserHandle.USER_ALL || filter.owningUserId == UserHandle.USER_ALL
                    || userId == filter.owningUserId) {
                return super.newResult(filter, match, userId);
            }
            return null;
        }

        @Override
        protected BroadcastFilter[] newArray(int size) {
            return new BroadcastFilter[size];
        }

        @Override
        protected boolean isPackageForFilter(String packageName, BroadcastFilter filter) {
            return packageName.equals(filter.packageName);
        }
    };

    /**
     * State of all active sticky broadcasts per user.  Keys are the action of the
     * sticky Intent, values are an ArrayList of all broadcasted intents with
     * that action (which should usually be one).  The SparseArray is keyed
     * by the user ID the sticky is for, and can include UserHandle.USER_ALL
     * for stickies that are sent to all users.
     */
    final SparseArray<ArrayMap<String, ArrayList<Intent>>> mStickyBroadcasts =
            new SparseArray<ArrayMap<String, ArrayList<Intent>>>();

    final ActiveServices mServices;

    /**
     * Backup/restore process management
     */
    String mBackupAppName = null;
    BackupRecord mBackupTarget = null;

    /**
     * List of PendingThumbnailsRecord objects of clients who are still
     * waiting to receive all of the thumbnails for a task.
     */
    final ArrayList<PendingThumbnailsRecord> mPendingThumbnails =
            new ArrayList<PendingThumbnailsRecord>();

    final ProviderMap mProviderMap;

    /**
     * List of content providers who have clients waiting for them.  The
     * application is currently being launched and the provider will be
     * removed from this list once it is published.
     */
    final ArrayList<ContentProviderRecord> mLaunchingProviders
            = new ArrayList<ContentProviderRecord>();

    /**
     * File storing persisted {@link #mGrantedUriPermissions}.
     */
    private final AtomicFile mGrantFile;

    /** XML constants used in {@link #mGrantFile} */
    private static final String TAG_URI_GRANTS = "uri-grants";
    private static final String TAG_URI_GRANT = "uri-grant";
    private static final String ATTR_USER_HANDLE = "userHandle";
    private static final String ATTR_SOURCE_PKG = "sourcePkg";
    private static final String ATTR_TARGET_PKG = "targetPkg";
    private static final String ATTR_URI = "uri";
    private static final String ATTR_MODE_FLAGS = "modeFlags";
    private static final String ATTR_CREATED_TIME = "createdTime";

    /**
     * Global set of specific {@link Uri} permissions that have been granted.
     * This optimized lookup structure maps from {@link UriPermission#targetUid}
     * to {@link UriPermission#uri} to {@link UriPermission}.
     */
    @GuardedBy("this")
    private final SparseArray<ArrayMap<Uri, UriPermission>>
            mGrantedUriPermissions = new SparseArray<ArrayMap<Uri, UriPermission>>();

    CoreSettingsObserver mCoreSettingsObserver;

    /**
     * Thread-local storage used to carry caller permissions over through
     * indirect content-provider access.
     */
    private class Identity {
        public int pid;
        public int uid;

        Identity(int _pid, int _uid) {
            pid = _pid;
            uid = _uid;
        }
    }

    private static ThreadLocal<Identity> sCallerIdentity = new ThreadLocal<Identity>();

    /**
     * All information we have collected about the runtime performance of
     * any user id that can impact battery performance.
     */
    final BatteryStatsService mBatteryStatsService;

    /**
     * Information about component usage
     */
    final UsageStatsService mUsageStatsService;

    /**
     * Information about and control over application operations
     */
    final AppOpsService mAppOpsService;

    /**
     * Current configuration information.  HistoryRecord objects are given
     * a reference to this object to indicate which configuration they are
     * currently running in, so this object must be kept immutable.
     */
    Configuration mConfiguration = new Configuration();

    /**
     * Current sequencing integer of the configuration, for skipping old
     * configurations.
     */
    int mConfigurationSeq = 0;

    /**
     * Hardware-reported OpenGLES version.
     */
    final int GL_ES_VERSION;

    /**
     * List of initialization arguments to pass to all processes when binding applications to them.
     * For example, references to the commonly used services.
     */
    HashMap<String, IBinder> mAppBindArgs;

    /**
     * Temporary to avoid allocations.  Protected by main lock.
     */
    final StringBuilder mStringBuilder = new StringBuilder(256);

    /**
     * Used to control how we initialize the service.
     */
    boolean mStartRunning = false;
    ComponentName mTopComponent;
    String mTopAction;
    String mTopData;
    boolean mProcessesReady = false;
    boolean mSystemReady = false;
    boolean mBooting = false;
    boolean mWaitingUpdate = false;
    boolean mDidUpdate = false;
    boolean mOnBattery = false;
    boolean mLaunchWarningShown = false;

    Context mContext;

    int mFactoryTest;

    boolean mCheckedForSetup;

    /**
     * The time at which we will allow normal application switches again,
     * after a call to {@link #stopAppSwitches()}.
     */
    long mAppSwitchesAllowedTime;

    /**
     * This is set to true after the first switch after mAppSwitchesAllowedTime
     * is set; any switches after that will clear the time.
     */
    boolean mDidAppSwitch;

    /**
     * Last time (in realtime) at which we checked for power usage.
     */
    long mLastPowerCheckRealtime;

    /**
     * Last time (in uptime) at which we checked for power usage.
     */
    long mLastPowerCheckUptime;

    /**
     * Set while we are wanting to sleep, to prevent any
     * activities from being started/resumed.
     */
    boolean mSleeping = false;

    /**
     * State of external calls telling us if the device is asleep.
     */
    boolean mWentToSleep = false;

    /**
     * State of external call telling us if the lock screen is shown.
     */
    boolean mLockScreenShown = false;

    /**
     * Set if we are shutting down the system, similar to sleeping.
     */
    boolean mShuttingDown = false;

    /**
     * Current sequence id for oom_adj computation traversal.
     */
    int mAdjSeq = 0;

    /**
     * Current sequence id for process LRU updating.
     */
    int mLruSeq = 0;

    /**
     * Keep track of the non-cached/empty process we last found, to help
     * determine how to distribute cached/empty processes next time.
     */
    int mNumNonCachedProcs = 0;

    /**
     * Keep track of the number of cached hidden procs, to balance oom adj
     * distribution between those and empty procs.
     */
    int mNumCachedHiddenProcs = 0;

    /**
     * Keep track of the number of service processes we last found, to
     * determine on the next iteration which should be B services.
     */
    int mNumServiceProcs = 0;
    int mNewNumAServiceProcs = 0;
    int mNewNumServiceProcs = 0;

    /**
     * Allow the current computed overall memory level of the system to go down?
     * This is set to false when we are killing processes for reasons other than
     * memory management, so that the now smaller process list will not be taken as
     * an indication that memory is tighter.
     */
    boolean mAllowLowerMemLevel = false;

    /**
     * The last computed memory level, for holding when we are in a state that
     * processes are going away for other reasons.
     */
    int mLastMemoryLevel = ProcessStats.ADJ_MEM_FACTOR_NORMAL;

    /**
     * The last total number of process we have, to determine if changes actually look
     * like a shrinking number of process due to lower RAM.
     */
    int mLastNumProcesses;

    /**
     * The uptime of the last time we performed idle maintenance.
     */
    long mLastIdleTime = SystemClock.uptimeMillis();

    /**
     * Total time spent with RAM that has been added in the past since the last idle time.
     */
    long mLowRamTimeSinceLastIdle = 0;

    /**
     * If RAM is currently low, when that horrible situatin started.
     */
    long mLowRamStartTime = 0;

    /**
     * This is set if we had to do a delayed dexopt of an app before launching
     * it, to increasing the ANR timeouts in that case.
     */
    boolean mDidDexOpt;

    String mDebugApp = null;
    boolean mWaitForDebugger = false;
    boolean mDebugTransient = false;
    String mOrigDebugApp = null;
    boolean mOrigWaitForDebugger = false;
    boolean mAlwaysFinishActivities = false;
    IActivityController mController = null;
    String mProfileApp = null;
    ProcessRecord mProfileProc = null;
    String mProfileFile;
    ParcelFileDescriptor mProfileFd;
    int mProfileType = 0;
    boolean mAutoStopProfiler = false;
    String mOpenGlTraceApp = null;

    static class ProcessChangeItem {
        static final int CHANGE_ACTIVITIES = 1<<0;
        static final int CHANGE_IMPORTANCE= 1<<1;
        int changes;
        int uid;
        int pid;
        int importance;
        boolean foregroundActivities;
    }

    final RemoteCallbackList<IProcessObserver> mProcessObservers
            = new RemoteCallbackList<IProcessObserver>();
    ProcessChangeItem[] mActiveProcessChanges = new ProcessChangeItem[5];

    final ArrayList<ProcessChangeItem> mPendingProcessChanges
            = new ArrayList<ProcessChangeItem>();
    final ArrayList<ProcessChangeItem> mAvailProcessChanges
            = new ArrayList<ProcessChangeItem>();

    /**
     * Runtime CPU use collection thread.  This object's lock is used to
     * protect all related state.
     */
    final Thread mProcessCpuThread;

    /**
     * Used to collect process stats when showing not responding dialog.
     * Protected by mProcessCpuThread.
     */
    final ProcessCpuTracker mProcessCpuTracker = new ProcessCpuTracker(
            MONITOR_THREAD_CPU_USAGE);
    final AtomicLong mLastCpuTime = new AtomicLong(0);
    final AtomicBoolean mProcessCpuMutexFree = new AtomicBoolean(true);

    long mLastWriteTime = 0;

    /**
     * Used to retain an update lock when the foreground activity is in
     * immersive mode.
     */
    final UpdateLock mUpdateLock = new UpdateLock("immersive");

    /**
     * Set to true after the system has finished booting.
     */
    boolean mBooted = false;

    int mProcessLimit = ProcessList.MAX_CACHED_APPS;
    int mProcessLimitOverride = -1;

    WindowManagerService mWindowManager;

    static ActivityManagerService mSelf;
    static ActivityThread mSystemThread;

    int mCurrentUserId = 0;
    private UserManagerService mUserManager;

    private final class AppDeathRecipient implements IBinder.DeathRecipient {
        final ProcessRecord mApp;
        final int mPid;
        final IApplicationThread mAppThread;

        AppDeathRecipient(ProcessRecord app, int pid,
                IApplicationThread thread) {
            if (localLOGV) Slog.v(
                TAG, "New death recipient " + this
                + " for thread " + thread.asBinder());
            mApp = app;
            mPid = pid;
            mAppThread = thread;
        }

        @Override
        public void binderDied() {
            if (localLOGV) Slog.v(
                TAG, "Death received in " + this
                + " for thread " + mAppThread.asBinder());
            synchronized(ActivityManagerService.this) {
                appDiedLocked(mApp, mPid, mAppThread);
            }
        }
    }

    static final int SHOW_ERROR_MSG = 1;
    static final int SHOW_NOT_RESPONDING_MSG = 2;
    static final int SHOW_FACTORY_ERROR_MSG = 3;
    static final int UPDATE_CONFIGURATION_MSG = 4;
    static final int GC_BACKGROUND_PROCESSES_MSG = 5;
    static final int WAIT_FOR_DEBUGGER_MSG = 6;
    static final int SERVICE_TIMEOUT_MSG = 12;
    static final int UPDATE_TIME_ZONE = 13;
    static final int SHOW_UID_ERROR_MSG = 14;
    static final int IM_FEELING_LUCKY_MSG = 15;
    static final int PROC_START_TIMEOUT_MSG = 20;
    static final int DO_PENDING_ACTIVITY_LAUNCHES_MSG = 21;
    static final int KILL_APPLICATION_MSG = 22;
    static final int FINALIZE_PENDING_INTENT_MSG = 23;
    static final int POST_HEAVY_NOTIFICATION_MSG = 24;
    static final int CANCEL_HEAVY_NOTIFICATION_MSG = 25;
    static final int SHOW_STRICT_MODE_VIOLATION_MSG = 26;
    static final int CHECK_EXCESSIVE_WAKE_LOCKS_MSG = 27;
    static final int CLEAR_DNS_CACHE_MSG = 28;
    static final int UPDATE_HTTP_PROXY_MSG = 29;
    static final int SHOW_COMPAT_MODE_DIALOG_MSG = 30;
    static final int DISPATCH_PROCESSES_CHANGED = 31;
    static final int DISPATCH_PROCESS_DIED = 32;
    static final int REPORT_MEM_USAGE_MSG = 33;
    static final int REPORT_USER_SWITCH_MSG = 34;
    static final int CONTINUE_USER_SWITCH_MSG = 35;
    static final int USER_SWITCH_TIMEOUT_MSG = 36;
    static final int IMMERSIVE_MODE_LOCK_MSG = 37;
    static final int PERSIST_URI_GRANTS_MSG = 38;
    static final int REQUEST_ALL_PSS_MSG = 39;

    static final int FIRST_ACTIVITY_STACK_MSG = 100;
    static final int FIRST_BROADCAST_QUEUE_MSG = 200;
    static final int FIRST_COMPAT_MODE_MSG = 300;
    static final int FIRST_SUPERVISOR_STACK_MSG = 100;

    AlertDialog mUidAlert;
    CompatModeDialog mCompatModeDialog;
    long mLastMemUsageReportTime = 0;

    /**
     * Flag whether the current user is a "monkey", i.e. whether
     * the UI is driven by a UI automation tool.
     */
    private boolean mUserIsMonkey;

    final Handler mHandler = new Handler() {
        //public Handler() {
        //    if (localLOGV) Slog.v(TAG, "Handler started!");
        //}

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
            case SHOW_ERROR_MSG: {
                HashMap<String, Object> data = (HashMap<String, Object>) msg.obj;
                boolean showBackground = Settings.Secure.getInt(mContext.getContentResolver(),
                        Settings.Secure.ANR_SHOW_BACKGROUND, 0) != 0;
                synchronized (ActivityManagerService.this) {
                    ProcessRecord proc = (ProcessRecord)data.get("app");
                    AppErrorResult res = (AppErrorResult) data.get("result");
                    if (proc != null && proc.crashDialog != null) {
                        Slog.e(TAG, "App already has crash dialog: " + proc);
                        if (res != null) {
                            res.set(0);
                        }
                        return;
                    }
                    if (!showBackground && UserHandle.getAppId(proc.uid)
                            >= Process.FIRST_APPLICATION_UID && proc.userId != mCurrentUserId
                            && proc.pid != MY_PID) {
                        Slog.w(TAG, "Skipping crash dialog of " + proc + ": background");
                        if (res != null) {
                            res.set(0);
                        }
                        return;
                    }
                    if (mShowDialogs && !mSleeping && !mShuttingDown) {
                        Dialog d = new AppErrorDialog(mContext,
                                ActivityManagerService.this, res, proc);
                        d.show();
                        proc.crashDialog = d;
                    } else {
                        // The device is asleep, so just pretend that the user
                        // saw a crash dialog and hit "force quit".
                        if (res != null) {
                            res.set(0);
                        }
                    }
                }

                ensureBootCompleted();
            } break;
            case SHOW_NOT_RESPONDING_MSG: {
                synchronized (ActivityManagerService.this) {
                    HashMap<String, Object> data = (HashMap<String, Object>) msg.obj;
                    ProcessRecord proc = (ProcessRecord)data.get("app");
                    if (proc != null && proc.anrDialog != null) {
                        Slog.e(TAG, "App already has anr dialog: " + proc);
                        return;
                    }

                    Intent intent = new Intent("android.intent.action.ANR");
                    if (!mProcessesReady) {
                        intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY
                                | Intent.FLAG_RECEIVER_FOREGROUND);
                    }
                    broadcastIntentLocked(null, null, intent,
                            null, null, 0, null, null, null, AppOpsManager.OP_NONE,
                            false, false, MY_PID, Process.SYSTEM_UID, 0 /* TODO: Verify */);

                    if (mShowDialogs) {
                        Dialog d = new AppNotRespondingDialog(ActivityManagerService.this,
                                mContext, proc, (ActivityRecord)data.get("activity"),
                                msg.arg1 != 0);
                        d.show();
                        proc.anrDialog = d;
                    } else {
                        // Just kill the app if there is no dialog to be shown.
                        killAppAtUsersRequest(proc, null);
                    }
                }

                ensureBootCompleted();
            } break;
            case SHOW_STRICT_MODE_VIOLATION_MSG: {
                HashMap<String, Object> data = (HashMap<String, Object>) msg.obj;
                synchronized (ActivityManagerService.this) {
                    ProcessRecord proc = (ProcessRecord) data.get("app");
                    if (proc == null) {
                        Slog.e(TAG, "App not found when showing strict mode dialog.");
                        break;
                    }
                    if (proc.crashDialog != null) {
                        Slog.e(TAG, "App already has strict mode dialog: " + proc);
                        return;
                    }
                    AppErrorResult res = (AppErrorResult) data.get("result");
                    if (mShowDialogs && !mSleeping && !mShuttingDown) {
                        Dialog d = new StrictModeViolationDialog(mContext,
                                ActivityManagerService.this, res, proc);
                        d.show();
                        proc.crashDialog = d;
                    } else {
                        // The device is asleep, so just pretend that the user
                        // saw a crash dialog and hit "force quit".
                        res.set(0);
                    }
                }
                ensureBootCompleted();
            } break;
            case SHOW_FACTORY_ERROR_MSG: {
                Dialog d = new FactoryErrorDialog(
                    mContext, msg.getData().getCharSequence("msg"));
                d.show();
                ensureBootCompleted();
            } break;
            case UPDATE_CONFIGURATION_MSG: {
                final ContentResolver resolver = mContext.getContentResolver();
                Settings.System.putConfiguration(resolver, (Configuration)msg.obj);
            } break;
            case GC_BACKGROUND_PROCESSES_MSG: {
                synchronized (ActivityManagerService.this) {
                    performAppGcsIfAppropriateLocked();
                }
            } break;
            case WAIT_FOR_DEBUGGER_MSG: {
                synchronized (ActivityManagerService.this) {
                    ProcessRecord app = (ProcessRecord)msg.obj;
                    if (msg.arg1 != 0) {
                        if (!app.waitedForDebugger) {
                            Dialog d = new AppWaitingForDebuggerDialog(
                                    ActivityManagerService.this,
                                    mContext, app);
                            app.waitDialog = d;
                            app.waitedForDebugger = true;
                            d.show();
                        }
                    } else {
                        if (app.waitDialog != null) {
                            app.waitDialog.dismiss();
                            app.waitDialog = null;
                        }
                    }
                }
            } break;
            case SERVICE_TIMEOUT_MSG: {
                if (mDidDexOpt) {
                    mDidDexOpt = false;
                    Message nmsg = mHandler.obtainMessage(SERVICE_TIMEOUT_MSG);
                    nmsg.obj = msg.obj;
                    mHandler.sendMessageDelayed(nmsg, ActiveServices.SERVICE_TIMEOUT);
                    return;
                }
                mServices.serviceTimeout((ProcessRecord)msg.obj);
            } break;
            case UPDATE_TIME_ZONE: {
                synchronized (ActivityManagerService.this) {
                    for (int i = mLruProcesses.size() - 1 ; i >= 0 ; i--) {
                        ProcessRecord r = mLruProcesses.get(i);
                        if (r.thread != null) {
                            try {
                                r.thread.updateTimeZone();
                            } catch (RemoteException ex) {
                                Slog.w(TAG, "Failed to update time zone for: " + r.info.processName);
                            }
                        }
                    }
                }
            } break;
            case CLEAR_DNS_CACHE_MSG: {
                synchronized (ActivityManagerService.this) {
                    for (int i = mLruProcesses.size() - 1 ; i >= 0 ; i--) {
                        ProcessRecord r = mLruProcesses.get(i);
                        if (r.thread != null) {
                            try {
                                r.thread.clearDnsCache();
                            } catch (RemoteException ex) {
                                Slog.w(TAG, "Failed to clear dns cache for: " + r.info.processName);
                            }
                        }
                    }
                }
            } break;
            case UPDATE_HTTP_PROXY_MSG: {
                ProxyProperties proxy = (ProxyProperties)msg.obj;
                String host = "";
                String port = "";
                String exclList = "";
                String pacFileUrl = null;
                if (proxy != null) {
                    host = proxy.getHost();
                    port = Integer.toString(proxy.getPort());
                    exclList = proxy.getExclusionList();
                    pacFileUrl = proxy.getPacFileUrl();
                }
                synchronized (ActivityManagerService.this) {
                    for (int i = mLruProcesses.size() - 1 ; i >= 0 ; i--) {
                        ProcessRecord r = mLruProcesses.get(i);
                        if (r.thread != null) {
                            try {
                                r.thread.setHttpProxy(host, port, exclList, pacFileUrl);
                            } catch (RemoteException ex) {
                                Slog.w(TAG, "Failed to update http proxy for: " +
                                        r.info.processName);
                            }
                        }
                    }
                }
            } break;
            case SHOW_UID_ERROR_MSG: {
                String title = "System UIDs Inconsistent";
                String text = "UIDs on the system are inconsistent, you need to wipe your"
                        + " data partition or your device will be unstable.";
                Log.e(TAG, title + ": " + text);
                if (mShowDialogs) {
                    // XXX This is a temporary dialog, no need to localize.
                    AlertDialog d = new BaseErrorDialog(mContext);
                    d.getWindow().setType(WindowManager.LayoutParams.TYPE_SYSTEM_ERROR);
                    d.setCancelable(false);
                    d.setTitle(title);
                    d.setMessage(text);
                    d.setButton(DialogInterface.BUTTON_POSITIVE, "I'm Feeling Lucky",
                            mHandler.obtainMessage(IM_FEELING_LUCKY_MSG));
                    mUidAlert = d;
                    d.show();
                }
            } break;
            case IM_FEELING_LUCKY_MSG: {
                if (mUidAlert != null) {
                    mUidAlert.dismiss();
                    mUidAlert = null;
                }
            } break;
            case PROC_START_TIMEOUT_MSG: {
                if (mDidDexOpt) {
                    mDidDexOpt = false;
                    Message nmsg = mHandler.obtainMessage(PROC_START_TIMEOUT_MSG);
                    nmsg.obj = msg.obj;
                    mHandler.sendMessageDelayed(nmsg, PROC_START_TIMEOUT);
                    return;
                }
                ProcessRecord app = (ProcessRecord)msg.obj;
                synchronized (ActivityManagerService.this) {
                    processStartTimedOutLocked(app);
                }
            } break;
            case DO_PENDING_ACTIVITY_LAUNCHES_MSG: {
                synchronized (ActivityManagerService.this) {
                    doPendingActivityLaunchesLocked(true);
                }
            } break;
            case KILL_APPLICATION_MSG: {
                synchronized (ActivityManagerService.this) {
                    int appid = msg.arg1;
                    boolean restart = (msg.arg2 == 1);
                    Bundle bundle = (Bundle)msg.obj;
                    String pkg = bundle.getString("pkg");
                    String reason = bundle.getString("reason");
                    forceStopPackageLocked(pkg, appid, restart, false, true, false,
                            UserHandle.USER_ALL, reason);
                }
            } break;
            case FINALIZE_PENDING_INTENT_MSG: {
                ((PendingIntentRecord)msg.obj).completeFinalize();
            } break;
            case POST_HEAVY_NOTIFICATION_MSG: {
                INotificationManager inm = NotificationManager.getService();
                if (inm == null) {
                    return;
                }

                ActivityRecord root = (ActivityRecord)msg.obj;
                ProcessRecord process = root.app;
                if (process == null) {
                    return;
                }

                try {
                    Context context = mContext.createPackageContext(process.info.packageName, 0);
                    String text = mContext.getString(R.string.heavy_weight_notification,
                            context.getApplicationInfo().loadLabel(context.getPackageManager()));
                    Notification notification = new Notification();
                    notification.icon = com.android.internal.R.drawable.stat_sys_adb; //context.getApplicationInfo().icon;
                    notification.when = 0;
                    notification.flags = Notification.FLAG_ONGOING_EVENT;
                    notification.tickerText = text;
                    notification.defaults = 0; // please be quiet
                    notification.sound = null;
                    notification.vibrate = null;
                    notification.setLatestEventInfo(context, text,
                            mContext.getText(R.string.heavy_weight_notification_detail),
                            PendingIntent.getActivityAsUser(mContext, 0, root.intent,
                                    PendingIntent.FLAG_CANCEL_CURRENT, null,
                                    new UserHandle(root.userId)));

                    try {
                        int[] outId = new int[1];
                        inm.enqueueNotificationWithTag("android", "android", null,
                                R.string.heavy_weight_notification,
                                notification, outId, root.userId);
                    } catch (RuntimeException e) {
                        Slog.w(ActivityManagerService.TAG,
                                "Error showing notification for heavy-weight app", e);
                    } catch (RemoteException e) {
                    }
                } catch (NameNotFoundException e) {
                    Slog.w(TAG, "Unable to create context for heavy notification", e);
                }
            } break;
            case CANCEL_HEAVY_NOTIFICATION_MSG: {
                INotificationManager inm = NotificationManager.getService();
                if (inm == null) {
                    return;
                }
                try {
                    inm.cancelNotificationWithTag("android", null,
                            R.string.heavy_weight_notification,  msg.arg1);
                } catch (RuntimeException e) {
                    Slog.w(ActivityManagerService.TAG,
                            "Error canceling notification for service", e);
                } catch (RemoteException e) {
                }
            } break;
            case CHECK_EXCESSIVE_WAKE_LOCKS_MSG: {
                synchronized (ActivityManagerService.this) {
                    checkExcessivePowerUsageLocked(true);
                    removeMessages(CHECK_EXCESSIVE_WAKE_LOCKS_MSG);
                    Message nmsg = obtainMessage(CHECK_EXCESSIVE_WAKE_LOCKS_MSG);
                    sendMessageDelayed(nmsg, POWER_CHECK_DELAY);
                }
            } break;
            case SHOW_COMPAT_MODE_DIALOG_MSG: {
                synchronized (ActivityManagerService.this) {
                    ActivityRecord ar = (ActivityRecord)msg.obj;
                    if (mCompatModeDialog != null) {
                        if (mCompatModeDialog.mAppInfo.packageName.equals(
                                ar.info.applicationInfo.packageName)) {
                            return;
                        }
                        mCompatModeDialog.dismiss();
                        mCompatModeDialog = null;
                    }
                    if (ar != null && false) {
                        if (mCompatModePackages.getPackageAskCompatModeLocked(
                                ar.packageName)) {
                            int mode = mCompatModePackages.computeCompatModeLocked(
                                    ar.info.applicationInfo);
                            if (mode == ActivityManager.COMPAT_MODE_DISABLED
                                    || mode == ActivityManager.COMPAT_MODE_ENABLED) {
                                mCompatModeDialog = new CompatModeDialog(
                                        ActivityManagerService.this, mContext,
                                        ar.info.applicationInfo);
                                mCompatModeDialog.show();
                            }
                        }
                    }
                }
                break;
            }
            case DISPATCH_PROCESSES_CHANGED: {
                dispatchProcessesChanged();
                break;
            }
            case DISPATCH_PROCESS_DIED: {
                final int pid = msg.arg1;
                final int uid = msg.arg2;
                dispatchProcessDied(pid, uid);
                break;
            }
            case REPORT_MEM_USAGE_MSG: {
                final ArrayList<ProcessMemInfo> memInfos = (ArrayList<ProcessMemInfo>)msg.obj;
                Thread thread = new Thread() {
                    @Override public void run() {
                        final SparseArray<ProcessMemInfo> infoMap
                                = new SparseArray<ProcessMemInfo>(memInfos.size());
                        for (int i=0, N=memInfos.size(); i<N; i++) {
                            ProcessMemInfo mi = memInfos.get(i);
                            infoMap.put(mi.pid, mi);
                        }
                        updateCpuStatsNow();
                        synchronized (mProcessCpuThread) {
                            final int N = mProcessCpuTracker.countStats();
                            for (int i=0; i<N; i++) {
                                ProcessCpuTracker.Stats st = mProcessCpuTracker.getStats(i);
                                if (st.vsize > 0) {
                                    long pss = Debug.getPss(st.pid, null);
                                    if (pss > 0) {
                                        if (infoMap.indexOfKey(st.pid) < 0) {
                                            ProcessMemInfo mi = new ProcessMemInfo(st.name, st.pid,
                                                    ProcessList.NATIVE_ADJ, -1, "native", null);
                                            mi.pss = pss;
                                            memInfos.add(mi);
                                        }
                                    }
                                }
                            }
                        }

                        long totalPss = 0;
                        for (int i=0, N=memInfos.size(); i<N; i++) {
                            ProcessMemInfo mi = memInfos.get(i);
                            if (mi.pss == 0) {
                                mi.pss = Debug.getPss(mi.pid, null);
                            }
                            totalPss += mi.pss;
                        }
                        Collections.sort(memInfos, new Comparator<ProcessMemInfo>() {
                            @Override public int compare(ProcessMemInfo lhs, ProcessMemInfo rhs) {
                                if (lhs.oomAdj != rhs.oomAdj) {
                                    return lhs.oomAdj < rhs.oomAdj ? -1 : 1;
                                }
                                if (lhs.pss != rhs.pss) {
                                    return lhs.pss < rhs.pss ? 1 : -1;
                                }
                                return 0;
                            }
                        });

                        StringBuilder tag = new StringBuilder(128);
                        StringBuilder stack = new StringBuilder(128);
                        tag.append("Low on memory -- ");
                        appendMemBucket(tag, totalPss, "total", false);
                        appendMemBucket(stack, totalPss, "total", true);

                        StringBuilder logBuilder = new StringBuilder(1024);
                        logBuilder.append("Low on memory:\n");

                        boolean firstLine = true;
                        int lastOomAdj = Integer.MIN_VALUE;
                        for (int i=0, N=memInfos.size(); i<N; i++) {
                            ProcessMemInfo mi = memInfos.get(i);

                            if (mi.oomAdj != ProcessList.NATIVE_ADJ
                                    && (mi.oomAdj < ProcessList.SERVICE_ADJ
                                            || mi.oomAdj == ProcessList.HOME_APP_ADJ
                                            || mi.oomAdj == ProcessList.PREVIOUS_APP_ADJ)) {
                                if (lastOomAdj != mi.oomAdj) {
                                    lastOomAdj = mi.oomAdj;
                                    if (mi.oomAdj <= ProcessList.FOREGROUND_APP_ADJ) {
                                        tag.append(" / ");
                                    }
                                    if (mi.oomAdj >= ProcessList.FOREGROUND_APP_ADJ) {
                                        if (firstLine) {
                                            stack.append(":");
                                            firstLine = false;
                                        }
                                        stack.append("\n\t at ");
                                    } else {
                                        stack.append("$");
                                    }
                                } else {
                                    tag.append(" ");
                                    stack.append("$");
                                }
                                if (mi.oomAdj <= ProcessList.FOREGROUND_APP_ADJ) {
                                    appendMemBucket(tag, mi.pss, mi.name, false);
                                }
                                appendMemBucket(stack, mi.pss, mi.name, true);
                                if (mi.oomAdj >= ProcessList.FOREGROUND_APP_ADJ
                                        && ((i+1) >= N || memInfos.get(i+1).oomAdj != lastOomAdj)) {
                                    stack.append("(");
                                    for (int k=0; k<DUMP_MEM_OOM_ADJ.length; k++) {
                                        if (DUMP_MEM_OOM_ADJ[k] == mi.oomAdj) {
                                            stack.append(DUMP_MEM_OOM_LABEL[k]);
                                            stack.append(":");
                                            stack.append(DUMP_MEM_OOM_ADJ[k]);
                                        }
                                    }
                                    stack.append(")");
                                }
                            }

                            logBuilder.append("  ");
                            logBuilder.append(ProcessList.makeOomAdjString(mi.oomAdj));
                            logBuilder.append(' ');
                            logBuilder.append(ProcessList.makeProcStateString(mi.procState));
                            logBuilder.append(' ');
                            ProcessList.appendRamKb(logBuilder, mi.pss);
                            logBuilder.append(" kB: ");
                            logBuilder.append(mi.name);
                            logBuilder.append(" (");
                            logBuilder.append(mi.pid);
                            logBuilder.append(") ");
                            logBuilder.append(mi.adjType);
                            logBuilder.append('\n');
                            if (mi.adjReason != null) {
                                logBuilder.append("                      ");
                                logBuilder.append(mi.adjReason);
                                logBuilder.append('\n');
                            }
                        }

                        logBuilder.append("           ");
                        ProcessList.appendRamKb(logBuilder, totalPss);
                        logBuilder.append(" kB: TOTAL\n");

                        long[] infos = new long[Debug.MEMINFO_COUNT];
                        Debug.getMemInfo(infos);
                        logBuilder.append("  MemInfo: ");
                        logBuilder.append(infos[Debug.MEMINFO_SLAB]).append(" kB slab, ");
                        logBuilder.append(infos[Debug.MEMINFO_SHMEM]).append(" kB shmem, ");
                        logBuilder.append(infos[Debug.MEMINFO_BUFFERS]).append(" kB buffers, ");
                        logBuilder.append(infos[Debug.MEMINFO_CACHED]).append(" kB cached, ");
                        logBuilder.append(infos[Debug.MEMINFO_FREE]).append(" kB free\n");
                        if (infos[Debug.MEMINFO_ZRAM_TOTAL] != 0) {
                            logBuilder.append("  ZRAM: ");
                            logBuilder.append(infos[Debug.MEMINFO_ZRAM_TOTAL]);
                            logBuilder.append(" kB RAM, ");
                            logBuilder.append(infos[Debug.MEMINFO_SWAP_TOTAL]);
                            logBuilder.append(" kB swap total, ");
                            logBuilder.append(infos[Debug.MEMINFO_SWAP_FREE]);
                            logBuilder.append(" kB swap free\n");
                        }
                        Slog.i(TAG, logBuilder.toString());

                        StringBuilder dropBuilder = new StringBuilder(1024);
                        /*
                        StringWriter oomSw = new StringWriter();
                        PrintWriter oomPw = new FastPrintWriter(oomSw, false, 256);
                        StringWriter catSw = new StringWriter();
                        PrintWriter catPw = new FastPrintWriter(catSw, false, 256);
                        String[] emptyArgs = new String[] { };
                        dumpApplicationMemoryUsage(null, oomPw, "  ", emptyArgs, true, catPw);
                        oomPw.flush();
                        String oomString = oomSw.toString();
                        */
                        dropBuilder.append(stack);
                        dropBuilder.append('\n');
                        dropBuilder.append('\n');
                        dropBuilder.append(logBuilder);
                        dropBuilder.append('\n');
                        /*
                        dropBuilder.append(oomString);
                        dropBuilder.append('\n');
                        */
                        StringWriter catSw = new StringWriter();
                        synchronized (ActivityManagerService.this) {
                            PrintWriter catPw = new FastPrintWriter(catSw, false, 256);
                            String[] emptyArgs = new String[] { };
                            catPw.println();
                            dumpProcessesLocked(null, catPw, emptyArgs, 0, false, null);
                            catPw.println();
                            mServices.dumpServicesLocked(null, catPw, emptyArgs, 0,
                                    false, false, null);
                            catPw.println();
                            dumpActivitiesLocked(null, catPw, emptyArgs, 0, false, false, null);
                            catPw.flush();
                        }
                        dropBuilder.append(catSw.toString());
                        addErrorToDropBox("lowmem", null, "system_server", null,
                                null, tag.toString(), dropBuilder.toString(), null, null);
                        //Slog.i(TAG, "Sent to dropbox:");
                        //Slog.i(TAG, dropBuilder.toString());
                        synchronized (ActivityManagerService.this) {
                            long now = SystemClock.uptimeMillis();
                            if (mLastMemUsageReportTime < now) {
                                mLastMemUsageReportTime = now;
                            }
                        }
                    }
                };
                thread.start();
                break;
            }
            case REPORT_USER_SWITCH_MSG: {
                dispatchUserSwitch((UserStartedState)msg.obj, msg.arg1, msg.arg2);
                break;
            }
            case CONTINUE_USER_SWITCH_MSG: {
                continueUserSwitch((UserStartedState)msg.obj, msg.arg1, msg.arg2);
                break;
            }
            case USER_SWITCH_TIMEOUT_MSG: {
                timeoutUserSwitch((UserStartedState)msg.obj, msg.arg1, msg.arg2);
                break;
            }
            case IMMERSIVE_MODE_LOCK_MSG: {
                final boolean nextState = (msg.arg1 != 0);
                if (mUpdateLock.isHeld() != nextState) {
                    if (DEBUG_IMMERSIVE) {
                        final ActivityRecord r = (ActivityRecord) msg.obj;
                        Slog.d(TAG, "Applying new update lock state '" + nextState + "' for " + r);
                    }
                    if (nextState) {
                        mUpdateLock.acquire();
                    } else {
                        mUpdateLock.release();
                    }
                }
                break;
            }
            case PERSIST_URI_GRANTS_MSG: {
                writeGrantedUriPermissions();
                break;
            }
            case REQUEST_ALL_PSS_MSG: {
                requestPssAllProcsLocked(SystemClock.uptimeMillis(), true, false);
                break;
            }
            }
        }
    };

    static final int COLLECT_PSS_BG_MSG = 1;

    final Handler mBgHandler = new Handler(BackgroundThread.getHandler().getLooper()) {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
            case COLLECT_PSS_BG_MSG: {
                int i=0, num=0;
                long start = SystemClock.uptimeMillis();
                long[] tmp = new long[1];
                do {
                    ProcessRecord proc;
                    int procState;
                    int pid;
                    synchronized (ActivityManagerService.this) {
                        if (i >= mPendingPssProcesses.size()) {
                            if (DEBUG_PSS) Slog.d(TAG, "Collected PSS of " + num + " of " + i
                                    + " processes in " + (SystemClock.uptimeMillis()-start) + "ms");
                            mPendingPssProcesses.clear();
                            return;
                        }
                        proc = mPendingPssProcesses.get(i);
                        procState = proc.pssProcState;
                        if (proc.thread != null && procState == proc.setProcState) {
                            pid = proc.pid;
                        } else {
                            proc = null;
                            pid = 0;
                        }
                        i++;
                    }
                    if (proc != null) {
                        long pss = Debug.getPss(pid, tmp);
                        synchronized (ActivityManagerService.this) {
                            if (proc.thread != null && proc.setProcState == procState
                                    && proc.pid == pid) {
                                num++;
                                proc.lastPssTime = SystemClock.uptimeMillis();
                                proc.baseProcessTracker.addPss(pss, tmp[0], true, proc.pkgList);
                                if (DEBUG_PSS) Slog.d(TAG, "PSS of " + proc.toShortString()
                                        + ": " + pss + " lastPss=" + proc.lastPss
                                        + " state=" + ProcessList.makeProcStateString(procState));
                                if (proc.initialIdlePss == 0) {
                                    proc.initialIdlePss = pss;
                                }
                                proc.lastPss = pss;
                                if (procState >= ActivityManager.PROCESS_STATE_HOME) {
                                    proc.lastCachedPss = pss;
                                }
                            }
                        }
                    }
                } while (true);
            }
            }
        }
    };

    public static void setSystemProcess() {
        try {
            ActivityManagerService m = mSelf;

            ServiceManager.addService(Context.ACTIVITY_SERVICE, m, true);
            ServiceManager.addService(ProcessStats.SERVICE_NAME, m.mProcessStats);
            ServiceManager.addService("meminfo", new MemBinder(m));
            ServiceManager.addService("gfxinfo", new GraphicsBinder(m));
            ServiceManager.addService("dbinfo", new DbBinder(m));
            if (MONITOR_CPU_USAGE) {
                ServiceManager.addService("cpuinfo", new CpuBinder(m));
            }
            ServiceManager.addService("permission", new PermissionController(m));

            ApplicationInfo info =
                mSelf.mContext.getPackageManager().getApplicationInfo(
                            "android", STOCK_PM_FLAGS);
            mSystemThread.installSystemApplicationInfo(info);

            synchronized (mSelf) {
                ProcessRecord app = mSelf.newProcessRecordLocked(info,
                        info.processName, false);
                app.persistent = true;
                app.pid = MY_PID;
                app.maxAdj = ProcessList.SYSTEM_ADJ;
                app.makeActive(mSystemThread.getApplicationThread(), mSelf.mProcessStats);
                mSelf.mProcessNames.put(app.processName, app.uid, app);
                synchronized (mSelf.mPidsSelfLocked) {
                    mSelf.mPidsSelfLocked.put(app.pid, app);
                }
                mSelf.updateLruProcessLocked(app, false, null);
                mSelf.updateOomAdjLocked();
            }
        } catch (PackageManager.NameNotFoundException e) {
            throw new RuntimeException(
                    "Unable to find android system package", e);
        }
    }

    public void setWindowManager(WindowManagerService wm) {
        mWindowManager = wm;
        mStackSupervisor.setWindowManager(wm);
        wm.createStack(HOME_STACK_ID, -1, StackBox.TASK_STACK_GOES_OVER, 1.0f);
    }

    public void startObservingNativeCrashes() {
        final NativeCrashListener ncl = new NativeCrashListener();
        ncl.start();
    }

    public static final Context main(int factoryTest) {
        AThread thr = new AThread();
        thr.start();

        synchronized (thr) {
            while (thr.mService == null) {
                try {
                    thr.wait();
                } catch (InterruptedException e) {
                }
            }
        }

        ActivityManagerService m = thr.mService;
        mSelf = m;
        ActivityThread at = ActivityThread.systemMain();
        mSystemThread = at;
        Context context = at.getSystemContext();
        context.setTheme(android.R.style.Theme_Holo);
        m.mContext = context;
        m.mFactoryTest = factoryTest;
        m.mIntentFirewall = new IntentFirewall(m.new IntentFirewallInterface());

        m.mStackSupervisor = new ActivityStackSupervisor(m, context, thr.mLooper);

        m.mBatteryStatsService.publish(context);
        m.mUsageStatsService.publish(context);
        m.mAppOpsService.publish(context);

        synchronized (thr) {
            thr.mReady = true;
            thr.notifyAll();
        }

        m.startRunning(null, null, null, null);

        return context;
    }

    public static ActivityManagerService self() {
        return mSelf;
    }

    public IAppOpsService getAppOpsService() {
        return mAppOpsService;
    }

    static class AThread extends Thread {
        ActivityManagerService mService;
        Looper mLooper;
        boolean mReady = false;

        public AThread() {
            super("ActivityManager");
        }

        @Override
        public void run() {
            Looper.prepare();

            android.os.Process.setThreadPriority(
                    android.os.Process.THREAD_PRIORITY_FOREGROUND);
            android.os.Process.setCanSelfBackground(false);

            ActivityManagerService m = new ActivityManagerService();

            synchronized (this) {
                mService = m;
                mLooper = Looper.myLooper();
                Watchdog.getInstance().addThread(new Handler(mLooper), getName());
                notifyAll();
            }

            synchronized (this) {
                while (!mReady) {
                    try {
                        wait();
                    } catch (InterruptedException e) {
                    }
                }
            }

            // For debug builds, log event loop stalls to dropbox for analysis.
            if (StrictMode.conditionallyEnableDebugLogging()) {
                Slog.i(TAG, "Enabled StrictMode logging for AThread's Looper");
            }

            Looper.loop();
        }
    }

    static class MemBinder extends Binder {
        ActivityManagerService mActivityManagerService;
        MemBinder(ActivityManagerService activityManagerService) {
            mActivityManagerService = activityManagerService;
        }

        @Override
        protected void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
            if (mActivityManagerService.checkCallingPermission(android.Manifest.permission.DUMP)
                    != PackageManager.PERMISSION_GRANTED) {
                pw.println("Permission Denial: can't dump meminfo from from pid="
                        + Binder.getCallingPid() + ", uid=" + Binder.getCallingUid()
                        + " without permission " + android.Manifest.permission.DUMP);
                return;
            }

            mActivityManagerService.dumpApplicationMemoryUsage(fd, pw, "  ", args, false, null);
        }
    }

    static class GraphicsBinder extends Binder {
        ActivityManagerService mActivityManagerService;
        GraphicsBinder(ActivityManagerService activityManagerService) {
            mActivityManagerService = activityManagerService;
        }

        @Override
        protected void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
            if (mActivityManagerService.checkCallingPermission(android.Manifest.permission.DUMP)
                    != PackageManager.PERMISSION_GRANTED) {
                pw.println("Permission Denial: can't dump gfxinfo from from pid="
                        + Binder.getCallingPid() + ", uid=" + Binder.getCallingUid()
                        + " without permission " + android.Manifest.permission.DUMP);
                return;
            }

            mActivityManagerService.dumpGraphicsHardwareUsage(fd, pw, args);
        }
    }

    static class DbBinder extends Binder {
        ActivityManagerService mActivityManagerService;
        DbBinder(ActivityManagerService activityManagerService) {
            mActivityManagerService = activityManagerService;
        }

        @Override
        protected void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
            if (mActivityManagerService.checkCallingPermission(android.Manifest.permission.DUMP)
                    != PackageManager.PERMISSION_GRANTED) {
                pw.println("Permission Denial: can't dump dbinfo from from pid="
                        + Binder.getCallingPid() + ", uid=" + Binder.getCallingUid()
                        + " without permission " + android.Manifest.permission.DUMP);
                return;
            }

            mActivityManagerService.dumpDbInfo(fd, pw, args);
        }
    }

    static class CpuBinder extends Binder {
        ActivityManagerService mActivityManagerService;
        CpuBinder(ActivityManagerService activityManagerService) {
            mActivityManagerService = activityManagerService;
        }

        @Override
        protected void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
            if (mActivityManagerService.checkCallingPermission(android.Manifest.permission.DUMP)
                    != PackageManager.PERMISSION_GRANTED) {
                pw.println("Permission Denial: can't dump cpuinfo from from pid="
                        + Binder.getCallingPid() + ", uid=" + Binder.getCallingUid()
                        + " without permission " + android.Manifest.permission.DUMP);
                return;
            }

            synchronized (mActivityManagerService.mProcessCpuThread) {
                pw.print(mActivityManagerService.mProcessCpuTracker.printCurrentLoad());
                pw.print(mActivityManagerService.mProcessCpuTracker.printCurrentState(
                        SystemClock.uptimeMillis()));
            }
        }
    }

    private ActivityManagerService() {
        Slog.i(TAG, "Memory class: " + ActivityManager.staticGetMemoryClass());

        mFgBroadcastQueue = new BroadcastQueue(this, "foreground", BROADCAST_FG_TIMEOUT, false);
        mBgBroadcastQueue = new BroadcastQueue(this, "background", BROADCAST_BG_TIMEOUT, true);
        mBroadcastQueues[0] = mFgBroadcastQueue;
        mBroadcastQueues[1] = mBgBroadcastQueue;

        mServices = new ActiveServices(this);
        mProviderMap = new ProviderMap(this);

        File dataDir = Environment.getDataDirectory();
        File systemDir = new File(dataDir, "system");
        systemDir.mkdirs();
        mBatteryStatsService = new BatteryStatsService(new File(
                systemDir, "batterystats.bin").toString());
        mBatteryStatsService.getActiveStatistics().readLocked();
        mBatteryStatsService.getActiveStatistics().writeAsyncLocked();
        mOnBattery = DEBUG_POWER ? true
                : mBatteryStatsService.getActiveStatistics().getIsOnBattery();
        mBatteryStatsService.getActiveStatistics().setCallback(this);

        mProcessStats = new ProcessStatsService(this, new File(systemDir, "procstats"));

        mUsageStatsService = new UsageStatsService(new File(systemDir, "usagestats").toString());
        mAppOpsService = new AppOpsService(new File(systemDir, "appops.xml"));

        mGrantFile = new AtomicFile(new File(systemDir, "urigrants.xml"));

        mHeadless = "1".equals(SystemProperties.get("ro.config.headless", "0"));

        // User 0 is the first and only user that runs at boot.
        mStartedUsers.put(0, new UserStartedState(new UserHandle(0), true));
        mUserLru.add(Integer.valueOf(0));
        updateStartedUserArrayLocked();

        GL_ES_VERSION = SystemProperties.getInt("ro.opengles.version",
            ConfigurationInfo.GL_ES_VERSION_UNDEFINED);

        mConfiguration.setToDefaults();
        mConfiguration.setLocale(Locale.getDefault());

        mConfigurationSeq = mConfiguration.seq = 1;
        mProcessCpuTracker.init();

        mCompatModePackages = new CompatModePackages(this, systemDir);

        // Add ourself to the Watchdog monitors.
        Watchdog.getInstance().addMonitor(this);

        mProcessCpuThread = new Thread("CpuTracker") {
            @Override
            public void run() {
                while (true) {
                    try {
                        try {
                            synchronized(this) {
                                final long now = SystemClock.uptimeMillis();
                                long nextCpuDelay = (mLastCpuTime.get()+MONITOR_CPU_MAX_TIME)-now;
                                long nextWriteDelay = (mLastWriteTime+BATTERY_STATS_TIME)-now;
                                //Slog.i(TAG, "Cpu delay=" + nextCpuDelay
                                //        + ", write delay=" + nextWriteDelay);
                                if (nextWriteDelay < nextCpuDelay) {
                                    nextCpuDelay = nextWriteDelay;
                                }
                                if (nextCpuDelay > 0) {
                                    mProcessCpuMutexFree.set(true);
                                    this.wait(nextCpuDelay);
                                }
                            }
                        } catch (InterruptedException e) {
                        }
                        updateCpuStatsNow();
                    } catch (Exception e) {
                        Slog.e(TAG, "Unexpected exception collecting process stats", e);
                    }
                }
            }
        };
        mProcessCpuThread.start();
    }

    @Override
    public boolean onTransact(int code, Parcel data, Parcel reply, int flags)
            throws RemoteException {
        if (code == SYSPROPS_TRANSACTION) {
            // We need to tell all apps about the system property change.
            ArrayList<IBinder> procs = new ArrayList<IBinder>();
            synchronized(this) {
                final int NP = mProcessNames.getMap().size();
                for (int ip=0; ip<NP; ip++) {
                    SparseArray<ProcessRecord> apps = mProcessNames.getMap().valueAt(ip);
                    final int NA = apps.size();
                    for (int ia=0; ia<NA; ia++) {
                        ProcessRecord app = apps.valueAt(ia);
                        if (app.thread != null) {
                            procs.add(app.thread.asBinder());
                        }
                    }
                }
            }

            int N = procs.size();
            for (int i=0; i<N; i++) {
                Parcel data2 = Parcel.obtain();
                try {
                    procs.get(i).transact(IBinder.SYSPROPS_TRANSACTION, data2, null, 0);
                } catch (RemoteException e) {
                }
                data2.recycle();
            }
        }
        try {
            return super.onTransact(code, data, reply, flags);
        } catch (RuntimeException e) {
            // The activity manager only throws security exceptions, so let's
            // log all others.
            if (!(e instanceof SecurityException)) {
                Slog.wtf(TAG, "Activity Manager Crash", e);
            }
            throw e;
        }
    }

    void updateCpuStats() {
        final long now = SystemClock.uptimeMillis();
        if (mLastCpuTime.get() >= now - MONITOR_CPU_MIN_TIME) {
            return;
        }
        if (mProcessCpuMutexFree.compareAndSet(true, false)) {
            synchronized (mProcessCpuThread) {
                mProcessCpuThread.notify();
            }
        }
    }

    void updateCpuStatsNow() {
        synchronized (mProcessCpuThread) {
            mProcessCpuMutexFree.set(false);
            final long now = SystemClock.uptimeMillis();
            boolean haveNewCpuStats = false;

            if (MONITOR_CPU_USAGE &&
                    mLastCpuTime.get() < (now-MONITOR_CPU_MIN_TIME)) {
                mLastCpuTime.set(now);
                haveNewCpuStats = true;
                mProcessCpuTracker.update();
                //Slog.i(TAG, mProcessCpu.printCurrentState());
                //Slog.i(TAG, "Total CPU usage: "
                //        + mProcessCpu.getTotalCpuPercent() + "%");

                // Slog the cpu usage if the property is set.
                if ("true".equals(SystemProperties.get("events.cpu"))) {
                    int user = mProcessCpuTracker.getLastUserTime();
                    int system = mProcessCpuTracker.getLastSystemTime();
                    int iowait = mProcessCpuTracker.getLastIoWaitTime();
                    int irq = mProcessCpuTracker.getLastIrqTime();
                    int softIrq = mProcessCpuTracker.getLastSoftIrqTime();
                    int idle = mProcessCpuTracker.getLastIdleTime();

                    int total = user + system + iowait + irq + softIrq + idle;
                    if (total == 0) total = 1;

                    EventLog.writeEvent(EventLogTags.CPU,
                            ((user+system+iowait+irq+softIrq) * 100) / total,
                            (user * 100) / total,
                            (system * 100) / total,
                            (iowait * 100) / total,
                            (irq * 100) / total,
                            (softIrq * 100) / total);
                }
            }

            long[] cpuSpeedTimes = mProcessCpuTracker.getLastCpuSpeedTimes();
            final BatteryStatsImpl bstats = mBatteryStatsService.getActiveStatistics();
            synchronized(bstats) {
                synchronized(mPidsSelfLocked) {
                    if (haveNewCpuStats) {
                        if (mOnBattery) {
                            int perc = bstats.startAddingCpuLocked();
                            int totalUTime = 0;
                            int totalSTime = 0;
                            final int N = mProcessCpuTracker.countStats();
                            for (int i=0; i<N; i++) {
                                ProcessCpuTracker.Stats st = mProcessCpuTracker.getStats(i);
                                if (!st.working) {
                                    continue;
                                }
                                ProcessRecord pr = mPidsSelfLocked.get(st.pid);
                                int otherUTime = (st.rel_utime*perc)/100;
                                int otherSTime = (st.rel_stime*perc)/100;
                                totalUTime += otherUTime;
                                totalSTime += otherSTime;
                                if (pr != null) {
                                    BatteryStatsImpl.Uid.Proc ps = bstats.getProcessStatsLocked(
                                            st.name, st.pid);
                                    ps.addCpuTimeLocked(st.rel_utime-otherUTime,
                                            st.rel_stime-otherSTime);
                                    ps.addSpeedStepTimes(cpuSpeedTimes);
                                    pr.curCpuTime += (st.rel_utime+st.rel_stime) * 10;
                                } else if (st.uid >= Process.FIRST_APPLICATION_UID) {
                                    BatteryStatsImpl.Uid.Proc ps = st.batteryStats;
                                    if (ps == null) {
                                        st.batteryStats = ps = bstats.getProcessStatsLocked(st.uid,
                                                "(Unknown)");
                                    }
                                    ps.addCpuTimeLocked(st.rel_utime-otherUTime,
                                            st.rel_stime-otherSTime);
                                    ps.addSpeedStepTimes(cpuSpeedTimes);
                                } else {
                                    BatteryStatsImpl.Uid.Proc ps =
                                            bstats.getProcessStatsLocked(st.name, st.pid);
                                    if (ps != null) {
                                        ps.addCpuTimeLocked(st.rel_utime-otherUTime,
                                                st.rel_stime-otherSTime);
                                        ps.addSpeedStepTimes(cpuSpeedTimes);
                                    }
                                }
                            }
                            bstats.finishAddingCpuLocked(perc, totalUTime,
                                    totalSTime, cpuSpeedTimes);
                        }
                    }
                }

                if (mLastWriteTime < (now-BATTERY_STATS_TIME)) {
                    mLastWriteTime = now;
                    mBatteryStatsService.getActiveStatistics().writeAsyncLocked();
                }
            }
        }
    }

    @Override
    public void batteryNeedsCpuUpdate() {
        updateCpuStatsNow();
    }

    @Override
    public void batteryPowerChanged(boolean onBattery) {
        // When plugging in, update the CPU stats first before changing
        // the plug state.
        updateCpuStatsNow();
        synchronized (this) {
            synchronized(mPidsSelfLocked) {
                mOnBattery = DEBUG_POWER ? true : onBattery;
            }
        }
    }

    /**
     * Initialize the application bind args. These are passed to each
     * process when the bindApplication() IPC is sent to the process. They're
     * lazily setup to make sure the services are running when they're asked for.
     */
    private HashMap<String, IBinder> getCommonServicesLocked() {
        if (mAppBindArgs == null) {
            mAppBindArgs = new HashMap<String, IBinder>();

            // Setup the application init args
            mAppBindArgs.put("package", ServiceManager.getService("package"));
            mAppBindArgs.put("window", ServiceManager.getService("window"));
            mAppBindArgs.put(Context.ALARM_SERVICE,
                    ServiceManager.getService(Context.ALARM_SERVICE));
        }
        return mAppBindArgs;
    }

    final void setFocusedActivityLocked(ActivityRecord r) {
        if (mFocusedActivity != r) {
            if (DEBUG_FOCUS) Slog.d(TAG, "setFocusedActivityLocked: r=" + r);
            mFocusedActivity = r;
            mStackSupervisor.setFocusedStack(r);
            if (r != null) {
                mWindowManager.setFocusedApp(r.appToken, true);
            }
            applyUpdateLockStateLocked(r);
        }
    }

    @Override
    public void setFocusedStack(int stackId) {
        if (DEBUG_FOCUS) Slog.d(TAG, "setFocusedStack: stackId=" + stackId);
        synchronized (ActivityManagerService.this) {
            ActivityStack stack = mStackSupervisor.getStack(stackId);
            if (stack != null) {
                ActivityRecord r = stack.topRunningActivityLocked(null);
                if (r != null) {
                    setFocusedActivityLocked(r);
                }
            }
        }
    }

    @Override
    public void notifyActivityDrawn(IBinder token) {
        if (DEBUG_VISBILITY) Slog.d(TAG, "notifyActivityDrawn: token=" + token);
        synchronized (this) {
            ActivityRecord r= mStackSupervisor.isInAnyStackLocked(token);
            if (r != null) {
                r.task.stack.notifyActivityDrawnLocked(r);
            }
        }
    }

    final void applyUpdateLockStateLocked(ActivityRecord r) {
        // Modifications to the UpdateLock state are done on our handler, outside
        // the activity manager's locks.  The new state is determined based on the
        // state *now* of the relevant activity record.  The object is passed to
        // the handler solely for logging detail, not to be consulted/modified.
        final boolean nextState = r != null && r.immersive;
        mHandler.sendMessage(
                mHandler.obtainMessage(IMMERSIVE_MODE_LOCK_MSG, (nextState) ? 1 : 0, 0, r));
    }

    final void showAskCompatModeDialogLocked(ActivityRecord r) {
        Message msg = Message.obtain();
        msg.what = SHOW_COMPAT_MODE_DIALOG_MSG;
        msg.obj = r.task.askedCompatMode ? null : r;
        mHandler.sendMessage(msg);
    }

    private final int updateLruProcessInternalLocked(ProcessRecord app, long now, int index,
            String what, Object obj, ProcessRecord srcApp) {
        app.lastActivityTime = now;

        if (app.activities.size() > 0) {
            // Don't want to touch dependent processes that are hosting activities.
            return index;
        }

        int lrui = mLruProcesses.lastIndexOf(app);
        if (lrui < 0) {
            Slog.wtf(TAG, "Adding dependent process " + app + " not on LRU list: "
                    + what + " " + obj + " from " + srcApp);
            return index;
        }

        if (lrui >= index) {
            // Don't want to cause this to move dependent processes *back* in the
            // list as if they were less frequently used.
            return index;
        }

        if (lrui >= mLruProcessActivityStart) {
            // Don't want to touch dependent processes that are hosting activities.
            return index;
        }

        mLruProcesses.remove(lrui);
        if (index > 0) {
            index--;
        }
        if (DEBUG_LRU) Slog.d(TAG, "Moving dep from " + lrui + " to " + index
                + " in LRU list: " + app);
        mLruProcesses.add(index, app);
        return index;
    }

    final void removeLruProcessLocked(ProcessRecord app) {
        int lrui = mLruProcesses.lastIndexOf(app);
        if (lrui >= 0) {
            if (lrui <= mLruProcessActivityStart) {
                mLruProcessActivityStart--;
            }
            if (lrui <= mLruProcessServiceStart) {
                mLruProcessServiceStart--;
            }
            mLruProcesses.remove(lrui);
        }
    }

    final void updateLruProcessLocked(ProcessRecord app, boolean activityChange,
            ProcessRecord client) {
        final boolean hasActivity = app.activities.size() > 0 || app.hasClientActivities;
        final boolean hasService = false; // not impl yet. app.services.size() > 0;
        if (!activityChange && hasActivity) {
            // The process has activties, so we are only going to allow activity-based
            // adjustments move it.  It should be kept in the front of the list with other
            // processes that have activities, and we don't want those to change their
            // order except due to activity operations.
            return;
        }

        mLruSeq++;
        final long now = SystemClock.uptimeMillis();
        app.lastActivityTime = now;

        // First a quick reject: if the app is already at the position we will
        // put it, then there is nothing to do.
        if (hasActivity) {
            final int N = mLruProcesses.size();
            if (N > 0 && mLruProcesses.get(N-1) == app) {
                if (DEBUG_LRU) Slog.d(TAG, "Not moving, already top activity: " + app);
                return;
            }
        } else {
            if (mLruProcessServiceStart > 0
                    && mLruProcesses.get(mLruProcessServiceStart-1) == app) {
                if (DEBUG_LRU) Slog.d(TAG, "Not moving, already top other: " + app);
                return;
            }
        }

        int lrui = mLruProcesses.lastIndexOf(app);

        if (app.persistent && lrui >= 0) {
            // We don't care about the position of persistent processes, as long as
            // they are in the list.
            if (DEBUG_LRU) Slog.d(TAG, "Not moving, persistent: " + app);
            return;
        }

        /* In progress: compute new position first, so we can avoid doing work
           if the process is not actually going to move.  Not yet working.
        int addIndex;
        int nextIndex;
        boolean inActivity = false, inService = false;
        if (hasActivity) {
            // Process has activities, put it at the very tipsy-top.
            addIndex = mLruProcesses.size();
            nextIndex = mLruProcessServiceStart;
            inActivity = true;
        } else if (hasService) {
            // Process has services, put it at the top of the service list.
            addIndex = mLruProcessActivityStart;
            nextIndex = mLruProcessServiceStart;
            inActivity = true;
            inService = true;
        } else  {
            // Process not otherwise of interest, it goes to the top of the non-service area.
            addIndex = mLruProcessServiceStart;
            if (client != null) {
                int clientIndex = mLruProcesses.lastIndexOf(client);
                if (clientIndex < 0) Slog.d(TAG, "Unknown client " + client + " when updating "
                        + app);
                if (clientIndex >= 0 && addIndex > clientIndex) {
                    addIndex = clientIndex;
                }
            }
            nextIndex = addIndex > 0 ? addIndex-1 : addIndex;
        }

        Slog.d(TAG, "Update LRU at " + lrui + " to " + addIndex + " (act="
                + mLruProcessActivityStart + "): " + app);
        */

        if (lrui >= 0) {
            if (lrui < mLruProcessActivityStart) {
                mLruProcessActivityStart--;
            }
            if (lrui < mLruProcessServiceStart) {
                mLruProcessServiceStart--;
            }
            /*
            if (addIndex > lrui) {
                addIndex--;
            }
            if (nextIndex > lrui) {
                nextIndex--;
            }
            */
            mLruProcesses.remove(lrui);
        }

        /*
        mLruProcesses.add(addIndex, app);
        if (inActivity) {
            mLruProcessActivityStart++;
        }
        if (inService) {
            mLruProcessActivityStart++;
        }
        */

        int nextIndex;
        if (hasActivity) {
            final int N = mLruProcesses.size();
            if (app.activities.size() == 0 && mLruProcessActivityStart < (N-1)) {
                // Process doesn't have activities, but has clients with
                // activities...  move it up, but one below the top (the top
                // should always have a real activity).
                if (DEBUG_LRU) Slog.d(TAG, "Adding to second-top of LRU activity list: " + app);
                mLruProcesses.add(N-1, app);
                // To keep it from spamming the LRU list (by making a bunch of clients),
                // we will push down any other entries owned by the app.
                final int uid = app.info.uid;
                for (int i=N-2; i>mLruProcessActivityStart; i--) {
                    ProcessRecord subProc = mLruProcesses.get(i);
                    if (subProc.info.uid == uid) {
                        // We want to push this one down the list.  If the process after
                        // it is for the same uid, however, don't do so, because we don't
                        // want them internally to be re-ordered.
                        if (mLruProcesses.get(i-1).info.uid != uid) {
                            if (DEBUG_LRU) Slog.d(TAG, "Pushing uid " + uid + " swapping at " + i
                                    + ": " + mLruProcesses.get(i) + " : " + mLruProcesses.get(i-1));
                            ProcessRecord tmp = mLruProcesses.get(i);
                            mLruProcesses.set(i, mLruProcesses.get(i-1));
                            mLruProcesses.set(i-1, tmp);
                            i--;
                        }
                    } else {
                        // A gap, we can stop here.
                        break;
                    }
                }
            } else {
                // Process has activities, put it at the very tipsy-top.
                if (DEBUG_LRU) Slog.d(TAG, "Adding to top of LRU activity list: " + app);
                mLruProcesses.add(app);
            }
            nextIndex = mLruProcessServiceStart;
        } else if (hasService) {
            // Process has services, put it at the top of the service list.
            if (DEBUG_LRU) Slog.d(TAG, "Adding to top of LRU service list: " + app);
            mLruProcesses.add(mLruProcessActivityStart, app);
            nextIndex = mLruProcessServiceStart;
            mLruProcessActivityStart++;
        } else  {
            // Process not otherwise of interest, it goes to the top of the non-service area.
            int index = mLruProcessServiceStart;
            if (client != null) {
                // If there is a client, don't allow the process to be moved up higher
                // in the list than that client.
                int clientIndex = mLruProcesses.lastIndexOf(client);
                if (DEBUG_LRU && clientIndex < 0) Slog.d(TAG, "Unknown client " + client
                        + " when updating " + app);
                if (clientIndex <= lrui) {
                    // Don't allow the client index restriction to push it down farther in the
                    // list than it already is.
                    clientIndex = lrui;
                }
                if (clientIndex >= 0 && index > clientIndex) {
                    index = clientIndex;
                }
            }
            if (DEBUG_LRU) Slog.d(TAG, "Adding at " + index + " of LRU list: " + app);
            mLruProcesses.add(index, app);
            nextIndex = index-1;
            mLruProcessActivityStart++;
            mLruProcessServiceStart++;
        }

        // If the app is currently using a content provider or service,
        // bump those processes as well.
        for (int j=app.connections.size()-1; j>=0; j--) {
            ConnectionRecord cr = app.connections.valueAt(j);
            if (cr.binding != null && !cr.serviceDead && cr.binding.service != null
                    && cr.binding.service.app != null
                    && cr.binding.service.app.lruSeq != mLruSeq
                    && !cr.binding.service.app.persistent) {
                nextIndex = updateLruProcessInternalLocked(cr.binding.service.app, now, nextIndex,
                        "service connection", cr, app);
            }
        }
        for (int j=app.conProviders.size()-1; j>=0; j--) {
            ContentProviderRecord cpr = app.conProviders.get(j).provider;
            if (cpr.proc != null && cpr.proc.lruSeq != mLruSeq && !cpr.proc.persistent) {
                nextIndex = updateLruProcessInternalLocked(cpr.proc, now, nextIndex,
                        "provider reference", cpr, app);
            }
        }
    }

    final ProcessRecord getProcessRecordLocked(String processName, int uid, boolean keepIfLarge) {
        if (uid == Process.SYSTEM_UID) {
            // The system gets to run in any process.  If there are multiple
            // processes with the same uid, just pick the first (this
            // should never happen).
            SparseArray<ProcessRecord> procs = mProcessNames.getMap().get(processName);
            if (procs == null) return null;
            final int N = procs.size();
            for (int i = 0; i < N; i++) {
                if (UserHandle.isSameUser(procs.keyAt(i), uid)) return procs.valueAt(i);
            }
        }
        ProcessRecord proc = mProcessNames.get(processName, uid);
        if (false && proc != null && !keepIfLarge
                && proc.setProcState >= ActivityManager.PROCESS_STATE_CACHED_EMPTY
                && proc.lastCachedPss >= 4000) {
            // Turn this condition on to cause killing to happen regularly, for testing.
            if (proc.baseProcessTracker != null) {
                proc.baseProcessTracker.reportCachedKill(proc.pkgList, proc.lastCachedPss);
            }
            killUnneededProcessLocked(proc, Long.toString(proc.lastCachedPss)
                    + "k from cached");
        } else if (proc != null && !keepIfLarge
                && mLastMemoryLevel > ProcessStats.ADJ_MEM_FACTOR_NORMAL
                && proc.setProcState >= ActivityManager.PROCESS_STATE_CACHED_EMPTY) {
            if (DEBUG_PSS) Slog.d(TAG, "May not keep " + proc + ": pss=" + proc.lastCachedPss);
            if (proc.lastCachedPss >= mProcessList.getCachedRestoreThresholdKb()) {
                if (proc.baseProcessTracker != null) {
                    proc.baseProcessTracker.reportCachedKill(proc.pkgList, proc.lastCachedPss);
                }
                killUnneededProcessLocked(proc, Long.toString(proc.lastCachedPss)
                        + "k from cached");
            }
        }
        return proc;
    }

    void ensurePackageDexOpt(String packageName) {
        IPackageManager pm = AppGlobals.getPackageManager();
        try {
            if (pm.performDexOpt(packageName)) {
                mDidDexOpt = true;
            }
        } catch (RemoteException e) {
        }
    }

    boolean isNextTransitionForward() {
        int transit = mWindowManager.getPendingAppTransition();
        return transit == AppTransition.TRANSIT_ACTIVITY_OPEN
                || transit == AppTransition.TRANSIT_TASK_OPEN
                || transit == AppTransition.TRANSIT_TASK_TO_FRONT;
    }

    final ProcessRecord startProcessLocked(String processName,
            ApplicationInfo info, boolean knownToBeDead, int intentFlags,
            String hostingType, ComponentName hostingName, boolean allowWhileBooting,
            boolean isolated, boolean keepIfLarge) {
        ProcessRecord app;
        if (!isolated) {
            app = getProcessRecordLocked(processName, info.uid, keepIfLarge);
        } else {
            // If this is an isolated process, it can't re-use an existing process.
            app = null;
        }
        // We don't have to do anything more if:
        // (1) There is an existing application record; and
        // (2) The caller doesn't think it is dead, OR there is no thread
        //     object attached to it so we know it couldn't have crashed; and
        // (3) There is a pid assigned to it, so it is either starting or
        //     already running.
        if (DEBUG_PROCESSES) Slog.v(TAG, "startProcess: name=" + processName
                + " app=" + app + " knownToBeDead=" + knownToBeDead
                + " thread=" + (app != null ? app.thread : null)
                + " pid=" + (app != null ? app.pid : -1));
        if (app != null && app.pid > 0) {
            if (!knownToBeDead || app.thread == null) {
                // We already have the app running, or are waiting for it to
                // come up (we have a pid but not yet its thread), so keep it.
                if (DEBUG_PROCESSES) Slog.v(TAG, "App already running: " + app);
                // If this is a new package in the process, add the package to the list
                app.addPackage(info.packageName, mProcessStats);
                return app;
            }

            // An application record is attached to a previous process,
            // clean it up now.
            if (DEBUG_PROCESSES || DEBUG_CLEANUP) Slog.v(TAG, "App died: " + app);
            handleAppDiedLocked(app, true, true);
        }

        String hostingNameStr = hostingName != null
                ? hostingName.flattenToShortString() : null;

        if (!isolated) {
            if ((intentFlags&Intent.FLAG_FROM_BACKGROUND) != 0) {
                // If we are in the background, then check to see if this process
                // is bad.  If so, we will just silently fail.
                if (mBadProcesses.get(info.processName, info.uid) != null) {
                    if (DEBUG_PROCESSES) Slog.v(TAG, "Bad process: " + info.uid
                            + "/" + info.processName);
                    return null;
                }
            } else {
                // When the user is explicitly starting a process, then clear its
                // crash count so that we won't make it bad until they see at
                // least one crash dialog again, and make the process good again
                // if it had been bad.
                if (DEBUG_PROCESSES) Slog.v(TAG, "Clearing bad process: " + info.uid
                        + "/" + info.processName);
                mProcessCrashTimes.remove(info.processName, info.uid);
                if (mBadProcesses.get(info.processName, info.uid) != null) {
                    EventLog.writeEvent(EventLogTags.AM_PROC_GOOD,
                            UserHandle.getUserId(info.uid), info.uid,
                            info.processName);
                    mBadProcesses.remove(info.processName, info.uid);
                    if (app != null) {
                        app.bad = false;
                    }
                }
            }
        }

        if (app == null) {
            app = newProcessRecordLocked(info, processName, isolated);
            if (app == null) {
                Slog.w(TAG, "Failed making new process record for "
                        + processName + "/" + info.uid + " isolated=" + isolated);
                return null;
            }
            mProcessNames.put(processName, app.uid, app);
            if (isolated) {
                mIsolatedProcesses.put(app.uid, app);
            }
        } else {
            // If this is a new package in the process, add the package to the list
            app.addPackage(info.packageName, mProcessStats);
        }

        // If the system is not ready yet, then hold off on starting this
        // process until it is.
        if (!mProcessesReady
                && !isAllowedWhileBooting(info)
                && !allowWhileBooting) {
            if (!mProcessesOnHold.contains(app)) {
                mProcessesOnHold.add(app);
            }
            if (DEBUG_PROCESSES) Slog.v(TAG, "System not ready, putting on hold: " + app);
            return app;
        }

        startProcessLocked(app, hostingType, hostingNameStr);
        return (app.pid != 0) ? app : null;
    }

    boolean isAllowedWhileBooting(ApplicationInfo ai) {
        return (ai.flags&ApplicationInfo.FLAG_PERSISTENT) != 0;
    }

    private final void startProcessLocked(ProcessRecord app,
            String hostingType, String hostingNameStr) {
        if (app.pid > 0 && app.pid != MY_PID) {
            synchronized (mPidsSelfLocked) {
                mPidsSelfLocked.remove(app.pid);
                mHandler.removeMessages(PROC_START_TIMEOUT_MSG, app);
            }
            app.setPid(0);
        }

        if (DEBUG_PROCESSES && mProcessesOnHold.contains(app)) Slog.v(TAG,
                "startProcessLocked removing on hold: " + app);
        mProcessesOnHold.remove(app);

        updateCpuStats();

        try {
            int uid = app.uid;

            int[] gids = null;
            int mountExternal = Zygote.MOUNT_EXTERNAL_NONE;
            if (!app.isolated) {
                int[] permGids = null;
                try {
                    final PackageManager pm = mContext.getPackageManager();
                    permGids = pm.getPackageGids(app.info.packageName);

                    if (Environment.isExternalStorageEmulated()) {
                        if (pm.checkPermission(
                                android.Manifest.permission.ACCESS_ALL_EXTERNAL_STORAGE,
                                app.info.packageName) == PERMISSION_GRANTED) {
                            mountExternal = Zygote.MOUNT_EXTERNAL_MULTIUSER_ALL;
                        } else {
                            mountExternal = Zygote.MOUNT_EXTERNAL_MULTIUSER;
                        }
                    }
                } catch (PackageManager.NameNotFoundException e) {
                    Slog.w(TAG, "Unable to retrieve gids", e);
                }

                /*
                 * Add shared application GID so applications can share some
                 * resources like shared libraries
                 */
                if (permGids == null) {
                    gids = new int[1];
                } else {
                    gids = new int[permGids.length + 1];
                    System.arraycopy(permGids, 0, gids, 1, permGids.length);
                }
                gids[0] = UserHandle.getSharedAppGid(UserHandle.getAppId(uid));
            }
            if (mFactoryTest != SystemServer.FACTORY_TEST_OFF) {
                if (mFactoryTest == SystemServer.FACTORY_TEST_LOW_LEVEL
                        && mTopComponent != null
                        && app.processName.equals(mTopComponent.getPackageName())) {
                    uid = 0;
                }
                if (mFactoryTest == SystemServer.FACTORY_TEST_HIGH_LEVEL
                        && (app.info.flags&ApplicationInfo.FLAG_FACTORY_TEST) != 0) {
                    uid = 0;
                }
            }
            int debugFlags = 0;
            if ((app.info.flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
                debugFlags |= Zygote.DEBUG_ENABLE_DEBUGGER;
                // Also turn on CheckJNI for debuggable apps. It's quite
                // awkward to turn on otherwise.
                debugFlags |= Zygote.DEBUG_ENABLE_CHECKJNI;
            }
            // Run the app in safe mode if its manifest requests so or the
            // system is booted in safe mode.
            if ((app.info.flags & ApplicationInfo.FLAG_VM_SAFE_MODE) != 0 ||
                Zygote.systemInSafeMode == true) {
                debugFlags |= Zygote.DEBUG_ENABLE_SAFEMODE;
            }
            if ("1".equals(SystemProperties.get("debug.checkjni"))) {
                debugFlags |= Zygote.DEBUG_ENABLE_CHECKJNI;
            }
            if ("1".equals(SystemProperties.get("debug.jni.logging"))) {
                debugFlags |= Zygote.DEBUG_ENABLE_JNI_LOGGING;
            }
            if ("1".equals(SystemProperties.get("debug.assert"))) {
                debugFlags |= Zygote.DEBUG_ENABLE_ASSERT;
            }

            // Start the process.  It will either succeed and return a result containing
            // the PID of the new process, or else throw a RuntimeException.
            Process.ProcessStartResult startResult = Process.start("android.app.ActivityThread",
                    app.processName, uid, uid, gids, debugFlags, mountExternal,
                    app.info.targetSdkVersion, app.info.seinfo, null);

            BatteryStatsImpl bs = mBatteryStatsService.getActiveStatistics();
            synchronized (bs) {
                if (bs.isOnBattery()) {
                    bs.getProcessStatsLocked(app.uid, app.processName).incStartsLocked();
                }
            }

            EventLog.writeEvent(EventLogTags.AM_PROC_START,
                    UserHandle.getUserId(uid), startResult.pid, uid,
                    app.processName, hostingType,
                    hostingNameStr != null ? hostingNameStr : "");

            if (app.persistent) {
                Watchdog.getInstance().processStarted(app.processName, startResult.pid);
            }

            StringBuilder buf = mStringBuilder;
            buf.setLength(0);
            buf.append("Start proc ");
            buf.append(app.processName);
            buf.append(" for ");
            buf.append(hostingType);
            if (hostingNameStr != null) {
                buf.append(" ");
                buf.append(hostingNameStr);
            }
            buf.append(": pid=");
            buf.append(startResult.pid);
            buf.append(" uid=");
            buf.append(uid);
            buf.append(" gids={");
            if (gids != null) {
                for (int gi=0; gi<gids.length; gi++) {
                    if (gi != 0) buf.append(", ");
                    buf.append(gids[gi]);

                }
            }
            buf.append("}");
            Slog.i(TAG, buf.toString());
            app.setPid(startResult.pid);
            app.usingWrapper = startResult.usingWrapper;
            app.removed = false;
            synchronized (mPidsSelfLocked) {
                this.mPidsSelfLocked.put(startResult.pid, app);
                Message msg = mHandler.obtainMessage(PROC_START_TIMEOUT_MSG);
                msg.obj = app;
                mHandler.sendMessageDelayed(msg, startResult.usingWrapper
                        ? PROC_START_TIMEOUT_WITH_WRAPPER : PROC_START_TIMEOUT);
            }
        } catch (RuntimeException e) {
            // XXX do better error recovery.
            app.setPid(0);
            Slog.e(TAG, "Failure starting process " + app.processName, e);
        }
    }

    void updateUsageStats(ActivityRecord component, boolean resumed) {
        if (DEBUG_SWITCH) Slog.d(TAG, "updateUsageStats: comp=" + component + "res=" + resumed);
        final BatteryStatsImpl stats = mBatteryStatsService.getActiveStatistics();
        if (resumed) {
            mUsageStatsService.noteResumeComponent(component.realActivity);
            synchronized (stats) {
                stats.noteActivityResumedLocked(component.app.uid);
            }
        } else {
            mUsageStatsService.notePauseComponent(component.realActivity);
            synchronized (stats) {
                stats.noteActivityPausedLocked(component.app.uid);
            }
        }
    }

    Intent getHomeIntent() {
        Intent intent = new Intent(mTopAction, mTopData != null ? Uri.parse(mTopData) : null);
        intent.setComponent(mTopComponent);
        if (mFactoryTest != SystemServer.FACTORY_TEST_LOW_LEVEL) {
            intent.addCategory(Intent.CATEGORY_HOME);
        }
        return intent;
    }

    boolean startHomeActivityLocked(int userId) {
        if (mHeadless) {
            // Added because none of the other calls to ensureBootCompleted seem to fire
            // when running headless.
            ensureBootCompleted();
            return false;
        }

        if (mFactoryTest == SystemServer.FACTORY_TEST_LOW_LEVEL
                && mTopAction == null) {
            // We are running in factory test mode, but unable to find
            // the factory test app, so just sit around displaying the
            // error message and don't try to start anything.
            return false;
        }
        Intent intent = getHomeIntent();
        ActivityInfo aInfo =
            resolveActivityInfo(intent, STOCK_PM_FLAGS, userId);
        if (aInfo != null) {
            intent.setComponent(new ComponentName(
                    aInfo.applicationInfo.packageName, aInfo.name));
            // Don't do this if the home app is currently being
            // instrumented.
            aInfo = new ActivityInfo(aInfo);
            aInfo.applicationInfo = getAppInfoForUser(aInfo.applicationInfo, userId);
            ProcessRecord app = getProcessRecordLocked(aInfo.processName,
                    aInfo.applicationInfo.uid, true);
            if (app == null || app.instrumentationClass == null) {
                intent.setFlags(intent.getFlags() | Intent.FLAG_ACTIVITY_NEW_TASK);
                mStackSupervisor.startHomeActivity(intent, aInfo);
            }
        }

        return true;
    }

    private ActivityInfo resolveActivityInfo(Intent intent, int flags, int userId) {
        ActivityInfo ai = null;
        ComponentName comp = intent.getComponent();
        try {
            if (comp != null) {
                ai = AppGlobals.getPackageManager().getActivityInfo(comp, flags, userId);
            } else {
                ResolveInfo info = AppGlobals.getPackageManager().resolveIntent(
                        intent,
                        intent.resolveTypeIfNeeded(mContext.getContentResolver()),
                            flags, userId);

                if (info != null) {
                    ai = info.activityInfo;
                }
            }
        } catch (RemoteException e) {
            // ignore
        }

        return ai;
    }

    /**
     * Starts the "new version setup screen" if appropriate.
     */
    void startSetupActivityLocked() {
        // Only do this once per boot.
        if (mCheckedForSetup) {
            return;
        }

        // We will show this screen if the current one is a different
        // version than the last one shown, and we are not running in
        // low-level factory test mode.
        final ContentResolver resolver = mContext.getContentResolver();
        if (mFactoryTest != SystemServer.FACTORY_TEST_LOW_LEVEL &&
                Settings.Global.getInt(resolver,
                        Settings.Global.DEVICE_PROVISIONED, 0) != 0) {
            mCheckedForSetup = true;

            // See if we should be showing the platform update setup UI.
            Intent intent = new Intent(Intent.ACTION_UPGRADE_SETUP);
            List<ResolveInfo> ris = mSelf.mContext.getPackageManager()
                    .queryIntentActivities(intent, PackageManager.GET_META_DATA);

            // We don't allow third party apps to replace this.
            ResolveInfo ri = null;
            for (int i=0; ris != null && i<ris.size(); i++) {
                if ((ris.get(i).activityInfo.applicationInfo.flags
                        & ApplicationInfo.FLAG_SYSTEM) != 0) {
                    ri = ris.get(i);
                    break;
                }
            }

            if (ri != null) {
                String vers = ri.activityInfo.metaData != null
                        ? ri.activityInfo.metaData.getString(Intent.METADATA_SETUP_VERSION)
                        : null;
                if (vers == null && ri.activityInfo.applicationInfo.metaData != null) {
                    vers = ri.activityInfo.applicationInfo.metaData.getString(
                            Intent.METADATA_SETUP_VERSION);
                }
                String lastVers = Settings.Secure.getString(
                        resolver, Settings.Secure.LAST_SETUP_SHOWN);
                if (vers != null && !vers.equals(lastVers)) {
                    intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    intent.setComponent(new ComponentName(
                            ri.activityInfo.packageName, ri.activityInfo.name));
                    mStackSupervisor.startActivityLocked(null, intent, null, ri.activityInfo,
                            null, null, 0, 0, 0, null, 0, null, false, null);
                }
            }
        }
    }

    CompatibilityInfo compatibilityInfoForPackageLocked(ApplicationInfo ai) {
        return mCompatModePackages.compatibilityInfoForPackageLocked(ai);
    }

    void enforceNotIsolatedCaller(String caller) {
        if (UserHandle.isIsolated(Binder.getCallingUid())) {
            throw new SecurityException("Isolated process not allowed to call " + caller);
        }
    }

    @Override
    public int getFrontActivityScreenCompatMode() {
        enforceNotIsolatedCaller("getFrontActivityScreenCompatMode");
        synchronized (this) {
            return mCompatModePackages.getFrontActivityScreenCompatModeLocked();
        }
    }

    @Override
    public void setFrontActivityScreenCompatMode(int mode) {
        enforceCallingPermission(android.Manifest.permission.SET_SCREEN_COMPATIBILITY,
                "setFrontActivityScreenCompatMode");
        synchronized (this) {
            mCompatModePackages.setFrontActivityScreenCompatModeLocked(mode);
        }
    }

    @Override
    public int getPackageScreenCompatMode(String packageName) {
        enforceNotIsolatedCaller("getPackageScreenCompatMode");
        synchronized (this) {
            return mCompatModePackages.getPackageScreenCompatModeLocked(packageName);
        }
    }

    @Override
    public void setPackageScreenCompatMode(String packageName, int mode) {
        enforceCallingPermission(android.Manifest.permission.SET_SCREEN_COMPATIBILITY,
                "setPackageScreenCompatMode");
        synchronized (this) {
            mCompatModePackages.setPackageScreenCompatModeLocked(packageName, mode);
        }
    }

    @Override
    public boolean getPackageAskScreenCompat(String packageName) {
        enforceNotIsolatedCaller("getPackageAskScreenCompat");
        synchronized (this) {
            return mCompatModePackages.getPackageAskCompatModeLocked(packageName);
        }
    }

    @Override
    public void setPackageAskScreenCompat(String packageName, boolean ask) {
        enforceCallingPermission(android.Manifest.permission.SET_SCREEN_COMPATIBILITY,
                "setPackageAskScreenCompat");
        synchronized (this) {
            mCompatModePackages.setPackageAskCompatModeLocked(packageName, ask);
        }
    }

    private void dispatchProcessesChanged() {
        int N;
        synchronized (this) {
            N = mPendingProcessChanges.size();
            if (mActiveProcessChanges.length < N) {
                mActiveProcessChanges = new ProcessChangeItem[N];
            }
            mPendingProcessChanges.toArray(mActiveProcessChanges);
            mAvailProcessChanges.addAll(mPendingProcessChanges);
            mPendingProcessChanges.clear();
            if (DEBUG_PROCESS_OBSERVERS) Slog.i(TAG, "*** Delivering " + N + " process changes");
        }

        int i = mProcessObservers.beginBroadcast();
        while (i > 0) {
            i--;
            final IProcessObserver observer = mProcessObservers.getBroadcastItem(i);
            if (observer != null) {
                try {
                    for (int j=0; j<N; j++) {
                        ProcessChangeItem item = mActiveProcessChanges[j];
                        if ((item.changes&ProcessChangeItem.CHANGE_ACTIVITIES) != 0) {
                            if (DEBUG_PROCESS_OBSERVERS) Slog.i(TAG, "ACTIVITIES CHANGED pid="
                                    + item.pid + " uid=" + item.uid + ": "
                                    + item.foregroundActivities);
                            observer.onForegroundActivitiesChanged(item.pid, item.uid,
                                    item.foregroundActivities);
                        }
                        if ((item.changes&ProcessChangeItem.CHANGE_IMPORTANCE) != 0) {
                            if (DEBUG_PROCESS_OBSERVERS) Slog.i(TAG, "IMPORTANCE CHANGED pid="
                                    + item.pid + " uid=" + item.uid + ": " + item.importance);
                            observer.onImportanceChanged(item.pid, item.uid,
                                    item.importance);
                        }
                    }
                } catch (RemoteException e) {
                }
            }
        }
        mProcessObservers.finishBroadcast();
    }

    private void dispatchProcessDied(int pid, int uid) {
        int i = mProcessObservers.beginBroadcast();
        while (i > 0) {
            i--;
            final IProcessObserver observer = mProcessObservers.getBroadcastItem(i);
            if (observer != null) {
                try {
                    observer.onProcessDied(pid, uid);
                } catch (RemoteException e) {
                }
            }
        }
        mProcessObservers.finishBroadcast();
    }

    final void doPendingActivityLaunchesLocked(boolean doResume) {
        final int N = mPendingActivityLaunches.size();
        if (N <= 0) {
            return;
        }
        for (int i=0; i<N; i++) {
            PendingActivityLaunch pal = mPendingActivityLaunches.get(i);
            mStackSupervisor.startActivityUncheckedLocked(pal.r, pal.sourceRecord, pal.startFlags,
                    doResume && i == (N-1), null);
        }
        mPendingActivityLaunches.clear();
    }

    @Override
    public final int startActivity(IApplicationThread caller, String callingPackage,
            Intent intent, String resolvedType, IBinder resultTo,
            String resultWho, int requestCode, int startFlags,
            String profileFile, ParcelFileDescriptor profileFd, Bundle options) {
        return startActivityAsUser(caller, callingPackage, intent, resolvedType, resultTo,
                resultWho, requestCode,
                startFlags, profileFile, profileFd, options, UserHandle.getCallingUserId());
    }

    @Override
    public final int startActivityAsUser(IApplicationThread caller, String callingPackage,
            Intent intent, String resolvedType, IBinder resultTo,
            String resultWho, int requestCode, int startFlags,
            String profileFile, ParcelFileDescriptor profileFd, Bundle options, int userId) {
        enforceNotIsolatedCaller("startActivity");
        userId = handleIncomingUser(Binder.getCallingPid(), Binder.getCallingUid(), userId,
                false, true, "startActivity", null);
        // TODO: Switch to user app stacks here.
        return mStackSupervisor.startActivityMayWait(caller, -1, callingPackage, intent, resolvedType,
                resultTo, resultWho, requestCode, startFlags, profileFile, profileFd,
                null, null, options, userId);
    }

    @Override
    public final WaitResult startActivityAndWait(IApplicationThread caller, String callingPackage,
            Intent intent, String resolvedType, IBinder resultTo,
            String resultWho, int requestCode, int startFlags, String profileFile,
            ParcelFileDescriptor profileFd, Bundle options, int userId) {
        enforceNotIsolatedCaller("startActivityAndWait");
        userId = handleIncomingUser(Binder.getCallingPid(), Binder.getCallingUid(), userId,
                false, true, "startActivityAndWait", null);
        WaitResult res = new WaitResult();
        // TODO: Switch to user app stacks here.
        mStackSupervisor.startActivityMayWait(caller, -1, callingPackage, intent, resolvedType,
                resultTo, resultWho, requestCode, startFlags, profileFile, profileFd,
                res, null, options, UserHandle.getCallingUserId());
        return res;
    }

    @Override
    public final int startActivityWithConfig(IApplicationThread caller, String callingPackage,
            Intent intent, String resolvedType, IBinder resultTo,
            String resultWho, int requestCode, int startFlags, Configuration config,
            Bundle options, int userId) {
        enforceNotIsolatedCaller("startActivityWithConfig");
        userId = handleIncomingUser(Binder.getCallingPid(), Binder.getCallingUid(), userId,
                false, true, "startActivityWithConfig", null);
        // TODO: Switch to user app stacks here.
        int ret = mStackSupervisor.startActivityMayWait(caller, -1, callingPackage, intent,
                resolvedType, resultTo, resultWho, requestCode, startFlags,
                null, null, null, config, options, userId);
        return ret;
    }

    @Override
    public int startActivityIntentSender(IApplicationThread caller,
            IntentSender intent, Intent fillInIntent, String resolvedType,
            IBinder resultTo, String resultWho, int requestCode,
            int flagsMask, int flagsValues, Bundle options) {
        enforceNotIsolatedCaller("startActivityIntentSender");
        // Refuse possible leaked file descriptors
        if (fillInIntent != null && fillInIntent.hasFileDescriptors()) {
            throw new IllegalArgumentException("File descriptors passed in Intent");
        }

        IIntentSender sender = intent.getTarget();
        if (!(sender instanceof PendingIntentRecord)) {
            throw new IllegalArgumentException("Bad PendingIntent object");
        }

        PendingIntentRecord pir = (PendingIntentRecord)sender;

        synchronized (this) {
            // If this is coming from the currently resumed activity, it is
            // effectively saying that app switches are allowed at this point.
            final ActivityStack stack = getFocusedStack();
            if (stack.mResumedActivity != null &&
                    stack.mResumedActivity.info.applicationInfo.uid == Binder.getCallingUid()) {
                mAppSwitchesAllowedTime = 0;
            }
        }
        int ret = pir.sendInner(0, fillInIntent, resolvedType, null, null,
                resultTo, resultWho, requestCode, flagsMask, flagsValues, options);
        return ret;
    }

    @Override
    public boolean startNextMatchingActivity(IBinder callingActivity,
            Intent intent, Bundle options) {
        // Refuse possible leaked file descriptors
        if (intent != null && intent.hasFileDescriptors() == true) {
            throw new IllegalArgumentException("File descriptors passed in Intent");
        }

        synchronized (this) {
            final ActivityRecord r = ActivityRecord.isInStackLocked(callingActivity);
            if (r == null) {
                ActivityOptions.abort(options);
                return false;
            }
            if (r.app == null || r.app.thread == null) {
                // The caller is not running...  d'oh!
                ActivityOptions.abort(options);
                return false;
            }
            intent = new Intent(intent);
            // The caller is not allowed to change the data.
            intent.setDataAndType(r.intent.getData(), r.intent.getType());
            // And we are resetting to find the next component...
            intent.setComponent(null);

            final boolean debug = ((intent.getFlags() & Intent.FLAG_DEBUG_LOG_RESOLUTION) != 0);

            ActivityInfo aInfo = null;
            try {
                List<ResolveInfo> resolves =
                    AppGlobals.getPackageManager().queryIntentActivities(
                            intent, r.resolvedType,
                            PackageManager.MATCH_DEFAULT_ONLY | STOCK_PM_FLAGS,
                            UserHandle.getCallingUserId());

                // Look for the original activity in the list...
                final int N = resolves != null ? resolves.size() : 0;
                for (int i=0; i<N; i++) {
                    ResolveInfo rInfo = resolves.get(i);
                    if (rInfo.activityInfo.packageName.equals(r.packageName)
                            && rInfo.activityInfo.name.equals(r.info.name)) {
                        // We found the current one...  the next matching is
                        // after it.
                        i++;
                        if (i<N) {
                            aInfo = resolves.get(i).activityInfo;
                        }
                        if (debug) {
                            Slog.v(TAG, "Next matching activity: found current " + r.packageName
                                    + "/" + r.info.name);
                            Slog.v(TAG, "Next matching activity: next is " + aInfo.packageName
                                    + "/" + aInfo.name);
                        }
                        break;
                    }
                }
            } catch (RemoteException e) {
            }

            if (aInfo == null) {
                // Nobody who is next!
                ActivityOptions.abort(options);
                if (debug) Slog.d(TAG, "Next matching activity: nothing found");
                return false;
            }

            intent.setComponent(new ComponentName(
                    aInfo.applicationInfo.packageName, aInfo.name));
            intent.setFlags(intent.getFlags()&~(
                    Intent.FLAG_ACTIVITY_FORWARD_RESULT|
                    Intent.FLAG_ACTIVITY_CLEAR_TOP|
                    Intent.FLAG_ACTIVITY_MULTIPLE_TASK|
                    Intent.FLAG_ACTIVITY_NEW_TASK));

            // Okay now we need to start the new activity, replacing the
            // currently running activity.  This is a little tricky because
            // we want to start the new one as if the current one is finished,
            // but not finish the current one first so that there is no flicker.
            // And thus...
            final boolean wasFinishing = r.finishing;
            r.finishing = true;

            // Propagate reply information over to the new activity.
            final ActivityRecord resultTo = r.resultTo;
            final String resultWho = r.resultWho;
            final int requestCode = r.requestCode;
            r.resultTo = null;
            if (resultTo != null) {
                resultTo.removeResultsLocked(r, resultWho, requestCode);
            }

            final long origId = Binder.clearCallingIdentity();
            int res = mStackSupervisor.startActivityLocked(r.app.thread, intent,
                    r.resolvedType, aInfo, resultTo != null ? resultTo.appToken : null,
                    resultWho, requestCode, -1, r.launchedFromUid, r.launchedFromPackage, 0,
                    options, false, null);
            Binder.restoreCallingIdentity(origId);

            r.finishing = wasFinishing;
            if (res != ActivityManager.START_SUCCESS) {
                return false;
            }
            return true;
        }
    }

    final int startActivityInPackage(int uid, String callingPackage,
            Intent intent, String resolvedType, IBinder resultTo,
            String resultWho, int requestCode, int startFlags, Bundle options, int userId) {

        userId = handleIncomingUser(Binder.getCallingPid(), Binder.getCallingUid(), userId,
                false, true, "startActivityInPackage", null);

        // TODO: Switch to user app stacks here.
        int ret = mStackSupervisor.startActivityMayWait(null, uid, callingPackage, intent, resolvedType,
                resultTo, resultWho, requestCode, startFlags,
                null, null, null, null, options, userId);
        return ret;
    }

    @Override
    public final int startActivities(IApplicationThread caller, String callingPackage,
            Intent[] intents, String[] resolvedTypes, IBinder resultTo, Bundle options,
            int userId) {
        enforceNotIsolatedCaller("startActivities");
        userId = handleIncomingUser(Binder.getCallingPid(), Binder.getCallingUid(), userId,
                false, true, "startActivity", null);
        // TODO: Switch to user app stacks here.
        int ret = mStackSupervisor.startActivities(caller, -1, callingPackage, intents,
                resolvedTypes, resultTo, options, userId);
        return ret;
    }

    final int startActivitiesInPackage(int uid, String callingPackage,
            Intent[] intents, String[] resolvedTypes, IBinder resultTo,
            Bundle options, int userId) {

        userId = handleIncomingUser(Binder.getCallingPid(), Binder.getCallingUid(), userId,
                false, true, "startActivityInPackage", null);
        // TODO: Switch to user app stacks here.
        int ret = mStackSupervisor.startActivities(null, uid, callingPackage, intents, resolvedTypes,
                resultTo, options, userId);
        return ret;
    }

    final void addRecentTaskLocked(TaskRecord task) {
        int N = mRecentTasks.size();
        // Quick case: check if the top-most recent task is the same.
        if (N > 0 && mRecentTasks.get(0) == task) {
            return;
        }
        // Remove any existing entries that are the same kind of task.
        for (int i=0; i<N; i++) {
            TaskRecord tr = mRecentTasks.get(i);
            if (task.userId == tr.userId
                    && ((task.affinity != null && task.affinity.equals(tr.affinity))
                    || (task.intent != null && task.intent.filterEquals(tr.intent)))) {
                tr.disposeThumbnail();
                mRecentTasks.remove(i);
                i--;
                N--;
                if (task.intent == null) {
                    // If the new recent task we are adding is not fully
                    // specified, then replace it with the existing recent task.
                    task = tr;
                }
            }
        }
        if (N >= MAX_RECENT_TASKS) {
            mRecentTasks.remove(N-1).disposeThumbnail();
        }
        mRecentTasks.add(0, task);
    }

    @Override
    public void reportActivityFullyDrawn(IBinder token) {
        synchronized (this) {
            ActivityRecord r = ActivityRecord.isInStackLocked(token);
            if (r == null) {
                return;
            }
            r.reportFullyDrawnLocked();
        }
    }

    @Override
    public void setRequestedOrientation(IBinder token, int requestedOrientation) {
        synchronized (this) {
            ActivityRecord r = ActivityRecord.isInStackLocked(token);
            if (r == null) {
                return;
            }
            final long origId = Binder.clearCallingIdentity();
            mWindowManager.setAppOrientation(r.appToken, requestedOrientation);
            Configuration config = mWindowManager.updateOrientationFromAppTokens(
                    mConfiguration, r.mayFreezeScreenLocked(r.app) ? r.appToken : null);
            if (config != null) {
                r.frozenBeforeDestroy = true;
                if (!updateConfigurationLocked(config, r, false, false)) {
                    mStackSupervisor.resumeTopActivitiesLocked();
                }
            }
            Binder.restoreCallingIdentity(origId);
        }
    }

    @Override
    public int getRequestedOrientation(IBinder token) {
        synchronized (this) {
            ActivityRecord r = ActivityRecord.isInStackLocked(token);
            if (r == null) {
                return ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;
            }
            return mWindowManager.getAppOrientation(r.appToken);
        }
    }

    /**
     * This is the internal entry point for handling Activity.finish().
     *
     * @param token The Binder token referencing the Activity we want to finish.
     * @param resultCode Result code, if any, from this Activity.
     * @param resultData Result data (Intent), if any, from this Activity.
     *
     * @return Returns true if the activity successfully finished, or false if it is still running.
     */
    @Override
    public final boolean finishActivity(IBinder token, int resultCode, Intent resultData) {
        // Refuse possible leaked file descriptors
        if (resultData != null && resultData.hasFileDescriptors() == true) {
            throw new IllegalArgumentException("File descriptors passed in Intent");
        }

        synchronized(this) {
            ActivityRecord r = ActivityRecord.isInStackLocked(token);
            if (r == null) {
                return true;
            }
            if (mController != null) {
                // Find the first activity that is not finishing.
                ActivityRecord next = r.task.stack.topRunningActivityLocked(token, 0);
                if (next != null) {
                    // ask watcher if this is allowed
                    boolean resumeOK = true;
                    try {
                        resumeOK = mController.activityResuming(next.packageName);
                    } catch (RemoteException e) {
                        mController = null;
                        Watchdog.getInstance().setActivityController(null);
                    }

                    if (!resumeOK) {
                        return false;
                    }
                }
            }
            final long origId = Binder.clearCallingIdentity();
            boolean res = r.task.stack.requestFinishActivityLocked(token, resultCode,
                    resultData, "app-request", true);
            Binder.restoreCallingIdentity(origId);
            return res;
        }
    }

    @Override
    public final void finishHeavyWeightApp() {
        if (checkCallingPermission(android.Manifest.permission.FORCE_STOP_PACKAGES)
                != PackageManager.PERMISSION_GRANTED) {
            String msg = "Permission Denial: finishHeavyWeightApp() from pid="
                    + Binder.getCallingPid()
                    + ", uid=" + Binder.getCallingUid()
                    + " requires " + android.Manifest.permission.FORCE_STOP_PACKAGES;
            Slog.w(TAG, msg);
            throw new SecurityException(msg);
        }

        synchronized(this) {
            if (mHeavyWeightProcess == null) {
                return;
            }

            ArrayList<ActivityRecord> activities = new ArrayList<ActivityRecord>(
                    mHeavyWeightProcess.activities);
            for (int i=0; i<activities.size(); i++) {
                ActivityRecord r = activities.get(i);
                if (!r.finishing) {
                    r.task.stack.finishActivityLocked(r, Activity.RESULT_CANCELED,
                            null, "finish-heavy", true);
                }
            }

            mHandler.sendMessage(mHandler.obtainMessage(CANCEL_HEAVY_NOTIFICATION_MSG,
                    mHeavyWeightProcess.userId, 0));
            mHeavyWeightProcess = null;
        }
    }

    @Override
    public void crashApplication(int uid, int initialPid, String packageName,
            String message) {
        if (checkCallingPermission(android.Manifest.permission.FORCE_STOP_PACKAGES)
                != PackageManager.PERMISSION_GRANTED) {
            String msg = "Permission Denial: crashApplication() from pid="
                    + Binder.getCallingPid()
                    + ", uid=" + Binder.getCallingUid()
                    + " requires " + android.Manifest.permission.FORCE_STOP_PACKAGES;
            Slog.w(TAG, msg);
            throw new SecurityException(msg);
        }

        synchronized(this) {
            ProcessRecord proc = null;

            // Figure out which process to kill.  We don't trust that initialPid
            // still has any relation to current pids, so must scan through the
            // list.
            synchronized (mPidsSelfLocked) {
                for (int i=0; i<mPidsSelfLocked.size(); i++) {
                    ProcessRecord p = mPidsSelfLocked.valueAt(i);
                    if (p.uid != uid) {
                        continue;
                    }
                    if (p.pid == initialPid) {
                        proc = p;
                        break;
                    }
                    if (p.pkgList.containsKey(packageName)) {
                        proc = p;
                    }
                }
            }

            if (proc == null) {
                Slog.w(TAG, "crashApplication: nothing for uid=" + uid
                        + " initialPid=" + initialPid
                        + " packageName=" + packageName);
                return;
            }

            if (proc.thread != null) {
                if (proc.pid == Process.myPid()) {
                    Log.w(TAG, "crashApplication: trying to crash self!");
                    return;
                }
                long ident = Binder.clearCallingIdentity();
                try {
                    proc.thread.scheduleCrash(message);
                } catch (RemoteException e) {
                }
                Binder.restoreCallingIdentity(ident);
            }
        }
    }

    @Override
    public final void finishSubActivity(IBinder token, String resultWho,
            int requestCode) {
        synchronized(this) {
            final long origId = Binder.clearCallingIdentity();
            ActivityRecord r = ActivityRecord.isInStackLocked(token);
            if (r != null) {
                r.task.stack.finishSubActivityLocked(r, resultWho, requestCode);
            }
            Binder.restoreCallingIdentity(origId);
        }
    }

    @Override
    public boolean finishActivityAffinity(IBinder token) {
        synchronized(this) {
            final long origId = Binder.clearCallingIdentity();
            ActivityRecord r = ActivityRecord.isInStackLocked(token);
            boolean res = false;
            if (r != null) {
                res = r.task.stack.finishActivityAffinityLocked(r);
            }
            Binder.restoreCallingIdentity(origId);
            return res;
        }
    }

    @Override
    public boolean willActivityBeVisible(IBinder token) {
        synchronized(this) {
            ActivityStack stack = ActivityRecord.getStackLocked(token);
            if (stack != null) {
                return stack.willActivityBeVisibleLocked(token);
            }
            return false;
        }
    }

    @Override
    public void overridePendingTransition(IBinder token, String packageName,
            int enterAnim, int exitAnim) {
        synchronized(this) {
            ActivityRecord self = ActivityRecord.isInStackLocked(token);
            if (self == null) {
                return;
            }

            final long origId = Binder.clearCallingIdentity();

            if (self.state == ActivityState.RESUMED
                    || self.state == ActivityState.PAUSING) {
                mWindowManager.overridePendingAppTransition(packageName,
                        enterAnim, exitAnim, null);
            }

            Binder.restoreCallingIdentity(origId);
        }
    }

    /**
     * Main function for removing an existing process from the activity manager
     * as a result of that process going away.  Clears out all connections
     * to the process.
     */
    private final void handleAppDiedLocked(ProcessRecord app,
            boolean restarting, boolean allowRestart) {
        cleanUpApplicationRecordLocked(app, restarting, allowRestart, -1);
        if (!restarting) {
            removeLruProcessLocked(app);
        }

        if (mProfileProc == app) {
            clearProfilerLocked();
        }

        // Remove this application's activities from active lists.
        boolean hasVisibleActivities = mStackSupervisor.handleAppDiedLocked(app);

        app.activities.clear();

        if (app.instrumentationClass != null) {
            Slog.w(TAG, "Crash of app " + app.processName
                  + " running instrumentation " + app.instrumentationClass);
            Bundle info = new Bundle();
            info.putString("shortMsg", "Process crashed.");
            finishInstrumentationLocked(app, Activity.RESULT_CANCELED, info);
        }

        if (!restarting) {
            if (!mStackSupervisor.resumeTopActivitiesLocked()) {
                // If there was nothing to resume, and we are not already
                // restarting this process, but there is a visible activity that
                // is hosted by the process...  then make sure all visible
                // activities are running, taking care of restarting this
                // process.
                if (hasVisibleActivities) {
                    mStackSupervisor.ensureActivitiesVisibleLocked(null, 0);
                }
            }
        }
    }

    private final int getLRURecordIndexForAppLocked(IApplicationThread thread) {
        IBinder threadBinder = thread.asBinder();
        // Find the application record.
        for (int i=mLruProcesses.size()-1; i>=0; i--) {
            ProcessRecord rec = mLruProcesses.get(i);
            if (rec.thread != null && rec.thread.asBinder() == threadBinder) {
                return i;
            }
        }
        return -1;
    }

    final ProcessRecord getRecordForAppLocked(
            IApplicationThread thread) {
        if (thread == null) {
            return null;
        }

        int appIndex = getLRURecordIndexForAppLocked(thread);
        return appIndex >= 0 ? mLruProcesses.get(appIndex) : null;
    }

    final void doLowMemReportIfNeededLocked(ProcessRecord dyingProc) {
        // If there are no longer any background processes running,
        // and the app that died was not running instrumentation,
        // then tell everyone we are now low on memory.
        boolean haveBg = false;
        for (int i=mLruProcesses.size()-1; i>=0; i--) {
            ProcessRecord rec = mLruProcesses.get(i);
            if (rec.thread != null
                    && rec.setProcState >= ActivityManager.PROCESS_STATE_CACHED_ACTIVITY) {
                haveBg = true;
                break;
            }
        }

        if (!haveBg) {
            boolean doReport = "1".equals(SystemProperties.get(SYSTEM_DEBUGGABLE, "0"));
            if (doReport) {
                long now = SystemClock.uptimeMillis();
                if (now < (mLastMemUsageReportTime+5*60*1000)) {
                    doReport = false;
                } else {
                    mLastMemUsageReportTime = now;
                }
            }
            final ArrayList<ProcessMemInfo> memInfos
                    = doReport ? new ArrayList<ProcessMemInfo>(mLruProcesses.size()) : null;
            EventLog.writeEvent(EventLogTags.AM_LOW_MEMORY, mLruProcesses.size());
            long now = SystemClock.uptimeMillis();
            for (int i=mLruProcesses.size()-1; i>=0; i--) {
                ProcessRecord rec = mLruProcesses.get(i);
                if (rec == dyingProc || rec.thread == null) {
                    continue;
                }
                if (doReport) {
                    memInfos.add(new ProcessMemInfo(rec.processName, rec.pid, rec.setAdj,
                            rec.setProcState, rec.adjType, rec.makeAdjReason()));
                }
                if ((rec.lastLowMemory+GC_MIN_INTERVAL) <= now) {
                    // The low memory report is overriding any current
                    // state for a GC request.  Make sure to do
                    // heavy/important/visible/foreground processes first.
                    if (rec.setAdj <= ProcessList.HEAVY_WEIGHT_APP_ADJ) {
                        rec.lastRequestedGc = 0;
                    } else {
                        rec.lastRequestedGc = rec.lastLowMemory;
                    }
                    rec.reportLowMemory = true;
                    rec.lastLowMemory = now;
                    mProcessesToGc.remove(rec);
                    addProcessToGcListLocked(rec);
                }
            }
            if (doReport) {
                Message msg = mHandler.obtainMessage(REPORT_MEM_USAGE_MSG, memInfos);
                mHandler.sendMessage(msg);
            }
            scheduleAppGcsLocked();
        }
    }

    final void appDiedLocked(ProcessRecord app, int pid,
            IApplicationThread thread) {

        BatteryStatsImpl stats = mBatteryStatsService.getActiveStatistics();
        synchronized (stats) {
            stats.noteProcessDiedLocked(app.info.uid, pid);
        }

        // Clean up already done if the process has been re-started.
        if (app.pid == pid && app.thread != null &&
                app.thread.asBinder() == thread.asBinder()) {
            boolean doLowMem = app.instrumentationClass == null;
            boolean doOomAdj = doLowMem;
            if (!app.killedByAm) {
                Slog.i(TAG, "Process " + app.processName + " (pid " + pid
                        + ") has died.");
                mAllowLowerMemLevel = true;
            } else {
                // Note that we always want to do oom adj to update our state with the
                // new number of procs.
                mAllowLowerMemLevel = false;
                doLowMem = false;
            }
            EventLog.writeEvent(EventLogTags.AM_PROC_DIED, app.userId, app.pid, app.processName);
            if (DEBUG_CLEANUP) Slog.v(
                TAG, "Dying app: " + app + ", pid: " + pid
                + ", thread: " + thread.asBinder());
            handleAppDiedLocked(app, false, true);

            if (doOomAdj) {
                updateOomAdjLocked();
            }
            if (doLowMem) {
                doLowMemReportIfNeededLocked(app);
            }
        } else if (app.pid != pid) {
            // A new process has already been started.
            Slog.i(TAG, "Process " + app.processName + " (pid " + pid
                    + ") has died and restarted (pid " + app.pid + ").");
            EventLog.writeEvent(EventLogTags.AM_PROC_DIED, app.userId, app.pid, app.processName);
        } else if (DEBUG_PROCESSES) {
            Slog.d(TAG, "Received spurious death notification for thread "
                    + thread.asBinder());
        }
    }

    /**
     * If a stack trace dump file is configured, dump process stack traces.
     * @param clearTraces causes the dump file to be erased prior to the new
     *    traces being written, if true; when false, the new traces will be
     *    appended to any existing file content.
     * @param firstPids of dalvik VM processes to dump stack traces for first
     * @param lastPids of dalvik VM processes to dump stack traces for last
     * @param nativeProcs optional list of native process names to dump stack crawls
     * @return file containing stack traces, or null if no dump file is configured
     */
    public static File dumpStackTraces(boolean clearTraces, ArrayList<Integer> firstPids,
            ProcessCpuTracker processCpuTracker, SparseArray<Boolean> lastPids, String[] nativeProcs) {
        String tracesPath = SystemProperties.get("dalvik.vm.stack-trace-file", null);
        if (tracesPath == null || tracesPath.length() == 0) {
            return null;
        }

        File tracesFile = new File(tracesPath);
        try {
            File tracesDir = tracesFile.getParentFile();
            if (!tracesDir.exists()) {
                tracesFile.mkdirs();
                if (!SELinux.restorecon(tracesDir)) {
                    return null;
                }
            }
            FileUtils.setPermissions(tracesDir.getPath(), 0775, -1, -1);  // drwxrwxr-x

            if (clearTraces && tracesFile.exists()) tracesFile.delete();
            tracesFile.createNewFile();
            FileUtils.setPermissions(tracesFile.getPath(), 0666, -1, -1); // -rw-rw-rw-
        } catch (IOException e) {
            Slog.w(TAG, "Unable to prepare ANR traces file: " + tracesPath, e);
            return null;
        }

        dumpStackTraces(tracesPath, firstPids, processCpuTracker, lastPids, nativeProcs);
        return tracesFile;
    }

    private static void dumpStackTraces(String tracesPath, ArrayList<Integer> firstPids,
            ProcessCpuTracker processCpuTracker, SparseArray<Boolean> lastPids, String[] nativeProcs) {
        // Use a FileObserver to detect when traces finish writing.
        // The order of traces is considered important to maintain for legibility.
        FileObserver observer = new FileObserver(tracesPath, FileObserver.CLOSE_WRITE) {
            @Override
            public synchronized void onEvent(int event, String path) { notify(); }
        };

        try {
            observer.startWatching();

            // First collect all of the stacks of the most important pids.
            if (firstPids != null) {
                try {
                    int num = firstPids.size();
                    for (int i = 0; i < num; i++) {
                        synchronized (observer) {
                            Process.sendSignal(firstPids.get(i), Process.SIGNAL_QUIT);
                            observer.wait(200);  // Wait for write-close, give up after 200msec
                        }
                    }
                } catch (InterruptedException e) {
                    Log.wtf(TAG, e);
                }
            }

            // Next collect the stacks of the native pids
            if (nativeProcs != null) {
                int[] pids = Process.getPidsForCommands(nativeProcs);
                if (pids != null) {
                    for (int pid : pids) {
                        Debug.dumpNativeBacktraceToFile(pid, tracesPath);
                    }
                }
            }

            // Lastly, measure CPU usage.
            if (processCpuTracker != null) {
                processCpuTracker.init();
                System.gc();
                processCpuTracker.update();
                try {
                    synchronized (processCpuTracker) {
                        processCpuTracker.wait(500); // measure over 1/2 second.
                    }
                } catch (InterruptedException e) {
                }
                processCpuTracker.update();

                // We'll take the stack crawls of just the top apps using CPU.
                final int N = processCpuTracker.countWorkingStats();
                int numProcs = 0;
                for (int i=0; i<N && numProcs<5; i++) {
                    ProcessCpuTracker.Stats stats = processCpuTracker.getWorkingStats(i);
                    if (lastPids.indexOfKey(stats.pid) >= 0) {
                        numProcs++;
                        try {
                            synchronized (observer) {
                                Process.sendSignal(stats.pid, Process.SIGNAL_QUIT);
                                observer.wait(200);  // Wait for write-close, give up after 200msec
                            }
                        } catch (InterruptedException e) {
                            Log.wtf(TAG, e);
                        }

                    }
                }
            }
        } finally {
            observer.stopWatching();
        }
    }

    final void logAppTooSlow(ProcessRecord app, long startTime, String msg) {
        if (true || IS_USER_BUILD) {
            return;
        }
        String tracesPath = SystemProperties.get("dalvik.vm.stack-trace-file", null);
        if (tracesPath == null || tracesPath.length() == 0) {
            return;
        }

        StrictMode.ThreadPolicy oldPolicy = StrictMode.allowThreadDiskReads();
        StrictMode.allowThreadDiskWrites();
        try {
            final File tracesFile = new File(tracesPath);
            final File tracesDir = tracesFile.getParentFile();
            final File tracesTmp = new File(tracesDir, "__tmp__");
            try {
                if (!tracesDir.exists()) {
                    tracesFile.mkdirs();
                    if (!SELinux.restorecon(tracesDir.getPath())) {
                        return;
                    }
                }
                FileUtils.setPermissions(tracesDir.getPath(), 0775, -1, -1);  // drwxrwxr-x

                if (tracesFile.exists()) {
                    tracesTmp.delete();
                    tracesFile.renameTo(tracesTmp);
                }
                StringBuilder sb = new StringBuilder();
                Time tobj = new Time();
                tobj.set(System.currentTimeMillis());
                sb.append(tobj.format("%Y-%m-%d %H:%M:%S"));
                sb.append(": ");
                TimeUtils.formatDuration(SystemClock.uptimeMillis()-startTime, sb);
                sb.append(" since ");
                sb.append(msg);
                FileOutputStream fos = new FileOutputStream(tracesFile);
                fos.write(sb.toString().getBytes());
                if (app == null) {
                    fos.write("\n*** No application process!".getBytes());
                }
                fos.close();
                FileUtils.setPermissions(tracesFile.getPath(), 0666, -1, -1); // -rw-rw-rw-
            } catch (IOException e) {
                Slog.w(TAG, "Unable to prepare slow app traces file: " + tracesPath, e);
                return;
            }

            if (app != null) {
                ArrayList<Integer> firstPids = new ArrayList<Integer>();
                firstPids.add(app.pid);
                dumpStackTraces(tracesPath, firstPids, null, null, null);
            }

            File lastTracesFile = null;
            File curTracesFile = null;
            for (int i=9; i>=0; i--) {
                String name = String.format(Locale.US, "slow%02d.txt", i);
                curTracesFile = new File(tracesDir, name);
                if (curTracesFile.exists()) {
                    if (lastTracesFile != null) {
                        curTracesFile.renameTo(lastTracesFile);
                    } else {
                        curTracesFile.delete();
                    }
                }
                lastTracesFile = curTracesFile;
            }
            tracesFile.renameTo(curTracesFile);
            if (tracesTmp.exists()) {
                tracesTmp.renameTo(tracesFile);
            }
        } finally {
            StrictMode.setThreadPolicy(oldPolicy);
        }
    }

    final void appNotResponding(ProcessRecord app, ActivityRecord activity,
            ActivityRecord parent, boolean aboveSystem, final String annotation) {
        ArrayList<Integer> firstPids = new ArrayList<Integer>(5);
        SparseArray<Boolean> lastPids = new SparseArray<Boolean>(20);

        if (mController != null) {
            try {
                // 0 == continue, -1 = kill process immediately
                int res = mController.appEarlyNotResponding(app.processName, app.pid, annotation);
                if (res < 0 && app.pid != MY_PID) Process.killProcess(app.pid);
            } catch (RemoteException e) {
                mController = null;
                Watchdog.getInstance().setActivityController(null);
            }
        }

        long anrTime = SystemClock.uptimeMillis();
        if (MONITOR_CPU_USAGE) {
            updateCpuStatsNow();
        }

        synchronized (this) {
            // PowerManager.reboot() can block for a long time, so ignore ANRs while shutting down.
            if (mShuttingDown) {
                Slog.i(TAG, "During shutdown skipping ANR: " + app + " " + annotation);
                return;
            } else if (app.notResponding) {
                Slog.i(TAG, "Skipping duplicate ANR: " + app + " " + annotation);
                return;
            } else if (app.crashing) {
                Slog.i(TAG, "Crashing app skipping ANR: " + app + " " + annotation);
                return;
            }

            // In case we come through here for the same app before completing
            // this one, mark as anring now so we will bail out.
            app.notResponding = true;

            // Log the ANR to the event log.
            EventLog.writeEvent(EventLogTags.AM_ANR, app.userId, app.pid,
                    app.processName, app.info.flags, annotation);

            // Dump thread traces as quickly as we can, starting with "interesting" processes.
            firstPids.add(app.pid);

            int parentPid = app.pid;
            if (parent != null && parent.app != null && parent.app.pid > 0) parentPid = parent.app.pid;
            if (parentPid != app.pid) firstPids.add(parentPid);

            if (MY_PID != app.pid && MY_PID != parentPid) firstPids.add(MY_PID);

            for (int i = mLruProcesses.size() - 1; i >= 0; i--) {
                ProcessRecord r = mLruProcesses.get(i);
                if (r != null && r.thread != null) {
                    int pid = r.pid;
                    if (pid > 0 && pid != app.pid && pid != parentPid && pid != MY_PID) {
                        if (r.persistent) {
                            firstPids.add(pid);
                        } else {
                            lastPids.put(pid, Boolean.TRUE);
                        }
                    }
                }
            }
        }

        // Log the ANR to the main log.
        StringBuilder info = new StringBuilder();
        info.setLength(0);
        info.append("ANR in ").append(app.processName);
        if (activity != null && activity.shortComponentName != null) {
            info.append(" (").append(activity.shortComponentName).append(")");
        }
        info.append("\n");
        info.append("PID: ").append(app.pid).append("\n");
        if (annotation != null) {
            info.append("Reason: ").append(annotation).append("\n");
        }
        if (parent != null && parent != activity) {
            info.append("Parent: ").append(parent.shortComponentName).append("\n");
        }

        final ProcessCpuTracker processCpuTracker = new ProcessCpuTracker(true);

        File tracesFile = dumpStackTraces(true, firstPids, processCpuTracker, lastPids,
                NATIVE_STACKS_OF_INTEREST);

        String cpuInfo = null;
        if (MONITOR_CPU_USAGE) {
            updateCpuStatsNow();
            synchronized (mProcessCpuThread) {
                cpuInfo = mProcessCpuTracker.printCurrentState(anrTime);
            }
            info.append(processCpuTracker.printCurrentLoad());
            info.append(cpuInfo);
        }

        info.append(processCpuTracker.printCurrentState(anrTime));

        Slog.e(TAG, info.toString());
        if (tracesFile == null) {
            // There is no trace file, so dump (only) the alleged culprit's threads to the log
            Process.sendSignal(app.pid, Process.SIGNAL_QUIT);
        }

        addErrorToDropBox("anr", app, app.processName, activity, parent, annotation,
                cpuInfo, tracesFile, null);

        if (mController != null) {
            try {
                // 0 == show dialog, 1 = keep waiting, -1 = kill process immediately
                int res = mController.appNotResponding(app.processName, app.pid, info.toString());
                if (res != 0) {
                    if (res < 0 && app.pid != MY_PID) {
                        Process.killProcess(app.pid);
                    } else {
                        synchronized (this) {
                            mServices.scheduleServiceTimeoutLocked(app);
                        }
                    }
                    return;
                }
            } catch (RemoteException e) {
                mController = null;
                Watchdog.getInstance().setActivityController(null);
            }
        }

        // Unless configured otherwise, swallow ANRs in background processes & kill the process.
        boolean showBackground = Settings.Secure.getInt(mContext.getContentResolver(),
                Settings.Secure.ANR_SHOW_BACKGROUND, 0) != 0;

        synchronized (this) {
            if (!showBackground && !app.isInterestingToUserLocked() && app.pid != MY_PID) {
                killUnneededProcessLocked(app, "background ANR");
                return;
            }

            // Set the app's notResponding state, and look up the errorReportReceiver
            makeAppNotRespondingLocked(app,
                    activity != null ? activity.shortComponentName : null,
                    annotation != null ? "ANR " + annotation : "ANR",
                    info.toString());

            // Bring up the infamous App Not Responding dialog
            Message msg = Message.obtain();
            HashMap<String, Object> map = new HashMap<String, Object>();
            msg.what = SHOW_NOT_RESPONDING_MSG;
            msg.obj = map;
            msg.arg1 = aboveSystem ? 1 : 0;
            map.put("app", app);
            if (activity != null) {
                map.put("activity", activity);
            }

            mHandler.sendMessage(msg);
        }
    }

    final void showLaunchWarningLocked(final ActivityRecord cur, final ActivityRecord next) {
        if (!mLaunchWarningShown) {
            mLaunchWarningShown = true;
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    synchronized (ActivityManagerService.this) {
                        final Dialog d = new LaunchWarningWindow(mContext, cur, next);
                        d.show();
                        mHandler.postDelayed(new Runnable() {
                            @Override
                            public void run() {
                                synchronized (ActivityManagerService.this) {
                                    d.dismiss();
                                    mLaunchWarningShown = false;
                                }
                            }
                        }, 4000);
                    }
                }
            });
        }
    }

    @Override
    public boolean clearApplicationUserData(final String packageName,
            final IPackageDataObserver observer, int userId) {
        enforceNotIsolatedCaller("clearApplicationUserData");
        int uid = Binder.getCallingUid();
        int pid = Binder.getCallingPid();
        userId = handleIncomingUser(pid, uid,
                userId, false, true, "clearApplicationUserData", null);
        long callingId = Binder.clearCallingIdentity();
        try {
            IPackageManager pm = AppGlobals.getPackageManager();
            int pkgUid = -1;
            synchronized(this) {
                try {
                    pkgUid = pm.getPackageUid(packageName, userId);
                } catch (RemoteException e) {
                }
                if (pkgUid == -1) {
                    Slog.w(TAG, "Invalid packageName: " + packageName);
                    if (observer != null) {
                        try {
                            observer.onRemoveCompleted(packageName, false);
                        } catch (RemoteException e) {
                            Slog.i(TAG, "Observer no longer exists.");
                        }
                    }
                    return false;
                }
                if (uid == pkgUid || checkComponentPermission(
                        android.Manifest.permission.CLEAR_APP_USER_DATA,
                        pid, uid, -1, true)
                        == PackageManager.PERMISSION_GRANTED) {
                    forceStopPackageLocked(packageName, pkgUid, "clear data");
                } else {
                    throw new SecurityException(pid+" does not have permission:"+
                            android.Manifest.permission.CLEAR_APP_USER_DATA+" to clear data" +
                                    "for process:"+packageName);
                }
            }

            try {
                // Clear application user data
                pm.clearApplicationUserData(packageName, observer, userId);

                // Remove all permissions granted from/to this package
                removeUriPermissionsForPackageLocked(packageName, userId, true);

                Intent intent = new Intent(Intent.ACTION_PACKAGE_DATA_CLEARED,
                        Uri.fromParts("package", packageName, null));
                intent.putExtra(Intent.EXTRA_UID, pkgUid);
                broadcastIntentInPackage("android", Process.SYSTEM_UID, intent,
                        null, null, 0, null, null, null, false, false, userId);
            } catch (RemoteException e) {
            }
        } finally {
            Binder.restoreCallingIdentity(callingId);
        }
        return true;
    }

    @Override
    public void killBackgroundProcesses(final String packageName, int userId) {
        if (checkCallingPermission(android.Manifest.permission.KILL_BACKGROUND_PROCESSES)
                != PackageManager.PERMISSION_GRANTED &&
                checkCallingPermission(android.Manifest.permission.RESTART_PACKAGES)
                        != PackageManager.PERMISSION_GRANTED) {
            String msg = "Permission Denial: killBackgroundProcesses() from pid="
                    + Binder.getCallingPid()
                    + ", uid=" + Binder.getCallingUid()
                    + " requires " + android.Manifest.permission.KILL_BACKGROUND_PROCESSES;
            Slog.w(TAG, msg);
            throw new SecurityException(msg);
        }

        userId = handleIncomingUser(Binder.getCallingPid(), Binder.getCallingUid(),
                userId, true, true, "killBackgroundProcesses", null);
        long callingId = Binder.clearCallingIdentity();
        try {
            IPackageManager pm = AppGlobals.getPackageManager();
            synchronized(this) {
                int appId = -1;
                try {
                    appId = UserHandle.getAppId(pm.getPackageUid(packageName, 0));
                } catch (RemoteException e) {
                }
                if (appId == -1) {
                    Slog.w(TAG, "Invalid packageName: " + packageName);
                    return;
                }
                killPackageProcessesLocked(packageName, appId, userId,
                        ProcessList.SERVICE_ADJ, false, true, true, false, "kill background");
            }
        } finally {
            Binder.restoreCallingIdentity(callingId);
        }
    }

    @Override
    public void killAllBackgroundProcesses() {
        if (checkCallingPermission(android.Manifest.permission.KILL_BACKGROUND_PROCESSES)
                != PackageManager.PERMISSION_GRANTED) {
            String msg = "Permission Denial: killAllBackgroundProcesses() from pid="
                    + Binder.getCallingPid()
                    + ", uid=" + Binder.getCallingUid()
                    + " requires " + android.Manifest.permission.KILL_BACKGROUND_PROCESSES;
            Slog.w(TAG, msg);
            throw new SecurityException(msg);
        }

        long callingId = Binder.clearCallingIdentity();
        try {
            synchronized(this) {
                ArrayList<ProcessRecord> procs = new ArrayList<ProcessRecord>();
                final int NP = mProcessNames.getMap().size();
                for (int ip=0; ip<NP; ip++) {
                    SparseArray<ProcessRecord> apps = mProcessNames.getMap().valueAt(ip);
                    final int NA = apps.size();
                    for (int ia=0; ia<NA; ia++) {
                        ProcessRecord app = apps.valueAt(ia);
                        if (app.persistent) {
                            // we don't kill persistent processes
                            continue;
                        }
                        if (app.removed) {
                            procs.add(app);
                        } else if (app.setAdj >= ProcessList.CACHED_APP_MIN_ADJ) {
                            app.removed = true;
                            procs.add(app);
                        }
                    }
                }

                int N = procs.size();
                for (int i=0; i<N; i++) {
                    removeProcessLocked(procs.get(i), false, true, "kill all background");
                }
                mAllowLowerMemLevel = true;
                updateOomAdjLocked();
                doLowMemReportIfNeededLocked(null);
            }
        } finally {
            Binder.restoreCallingIdentity(callingId);
        }
    }

    @Override
    public void forceStopPackage(final String packageName, int userId) {
        if (checkCallingPermission(android.Manifest.permission.FORCE_STOP_PACKAGES)
                != PackageManager.PERMISSION_GRANTED) {
            String msg = "Permission Denial: forceStopPackage() from pid="
                    + Binder.getCallingPid()
                    + ", uid=" + Binder.getCallingUid()
                    + " requires " + android.Manifest.permission.FORCE_STOP_PACKAGES;
            Slog.w(TAG, msg);
            throw new SecurityException(msg);
        }
        final int callingPid = Binder.getCallingPid();
        userId = handleIncomingUser(callingPid, Binder.getCallingUid(),
                userId, true, true, "forceStopPackage", null);
        long callingId = Binder.clearCallingIdentity();
        try {
            IPackageManager pm = AppGlobals.getPackageManager();
            synchronized(this) {
                int[] users = userId == UserHandle.USER_ALL
                        ? getUsersLocked() : new int[] { userId };
                for (int user : users) {
                    int pkgUid = -1;
                    try {
                        pkgUid = pm.getPackageUid(packageName, user);
                    } catch (RemoteException e) {
                    }
                    if (pkgUid == -1) {
                        Slog.w(TAG, "Invalid packageName: " + packageName);
                        continue;
                    }
                    try {
                        pm.setPackageStoppedState(packageName, true, user);
                    } catch (RemoteException e) {
                    } catch (IllegalArgumentException e) {
                        Slog.w(TAG, "Failed trying to unstop package "
                                + packageName + ": " + e);
                    }
                    if (isUserRunningLocked(user, false)) {
                        forceStopPackageLocked(packageName, pkgUid, "from pid " + callingPid);
                    }
                }
            }
        } finally {
            Binder.restoreCallingIdentity(callingId);
        }
    }

    /*
     * The pkg name and app id have to be specified.
     */
    @Override
    public void killApplicationWithAppId(String pkg, int appid, String reason) {
        if (pkg == null) {
            return;
        }
        // Make sure the uid is valid.
        if (appid < 0) {
            Slog.w(TAG, "Invalid appid specified for pkg : " + pkg);
            return;
        }
        int callerUid = Binder.getCallingUid();
        // Only the system server can kill an application
        if (callerUid == Process.SYSTEM_UID) {
            // Post an aysnc message to kill the application
            Message msg = mHandler.obtainMessage(KILL_APPLICATION_MSG);
            msg.arg1 = appid;
            msg.arg2 = 0;
            Bundle bundle = new Bundle();
            bundle.putString("pkg", pkg);
            bundle.putString("reason", reason);
            msg.obj = bundle;
            mHandler.sendMessage(msg);
        } else {
            throw new SecurityException(callerUid + " cannot kill pkg: " +
                    pkg);
        }
    }

    @Override
    public void closeSystemDialogs(String reason) {
        enforceNotIsolatedCaller("closeSystemDialogs");

        final int pid = Binder.getCallingPid();
        final int uid = Binder.getCallingUid();
        final long origId = Binder.clearCallingIdentity();
        try {
            synchronized (this) {
                // Only allow this from foreground processes, so that background
                // applications can't abuse it to prevent system UI from being shown.
                if (uid >= Process.FIRST_APPLICATION_UID) {
                    ProcessRecord proc;
                    synchronized (mPidsSelfLocked) {
                        proc = mPidsSelfLocked.get(pid);
                    }
                    if (proc.curRawAdj > ProcessList.PERCEPTIBLE_APP_ADJ) {
                        Slog.w(TAG, "Ignoring closeSystemDialogs " + reason
                                + " from background process " + proc);
                        return;
                    }
                }
                closeSystemDialogsLocked(reason);
            }
        } finally {
            Binder.restoreCallingIdentity(origId);
        }
    }

    void closeSystemDialogsLocked(String reason) {
        Intent intent = new Intent(Intent.ACTION_CLOSE_SYSTEM_DIALOGS);
        intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY
                | Intent.FLAG_RECEIVER_FOREGROUND);
        if (reason != null) {
            intent.putExtra("reason", reason);
        }
        mWindowManager.closeSystemDialogs(reason);

        mStackSupervisor.closeSystemDialogsLocked();

        broadcastIntentLocked(null, null, intent, null,
                null, 0, null, null, null, AppOpsManager.OP_NONE, false, false, -1,
                Process.SYSTEM_UID, UserHandle.USER_ALL);
    }

    @Override
    public Debug.MemoryInfo[] getProcessMemoryInfo(int[] pids) {
        enforceNotIsolatedCaller("getProcessMemoryInfo");
        Debug.MemoryInfo[] infos = new Debug.MemoryInfo[pids.length];
        for (int i=pids.length-1; i>=0; i--) {
            ProcessRecord proc;
            int oomAdj;
            synchronized (this) {
                synchronized (mPidsSelfLocked) {
                    proc = mPidsSelfLocked.get(pids[i]);
                    oomAdj = proc != null ? proc.setAdj : 0;
                }
            }
            infos[i] = new Debug.MemoryInfo();
            Debug.getMemoryInfo(pids[i], infos[i]);
            if (proc != null) {
                synchronized (this) {
                    if (proc.thread != null && proc.setAdj == oomAdj) {
                        // Record this for posterity if the process has been stable.
                        proc.baseProcessTracker.addPss(infos[i].getTotalPss(),
                                infos[i].getTotalUss(), false, proc.pkgList);
                    }
                }
            }
        }
        return infos;
    }

    @Override
    public long[] getProcessPss(int[] pids) {
        enforceNotIsolatedCaller("getProcessPss");
        long[] pss = new long[pids.length];
        for (int i=pids.length-1; i>=0; i--) {
            ProcessRecord proc;
            int oomAdj;
            synchronized (this) {
                synchronized (mPidsSelfLocked) {
                    proc = mPidsSelfLocked.get(pids[i]);
                    oomAdj = proc != null ? proc.setAdj : 0;
                }
            }
            long[] tmpUss = new long[1];
            pss[i] = Debug.getPss(pids[i], tmpUss);
            if (proc != null) {
                synchronized (this) {
                    if (proc.thread != null && proc.setAdj == oomAdj) {
                        // Record this for posterity if the process has been stable.
                        proc.baseProcessTracker.addPss(pss[i], tmpUss[0], false, proc.pkgList);
                    }
                }
            }
        }
        return pss;
    }

    @Override
    public void killApplicationProcess(String processName, int uid) {
        if (processName == null) {
            return;
        }

        int callerUid = Binder.getCallingUid();
        // Only the system server can kill an application
        if (callerUid == Process.SYSTEM_UID) {
            synchronized (this) {
                ProcessRecord app = getProcessRecordLocked(processName, uid, true);
                if (app != null && app.thread != null) {
                    try {
                        app.thread.scheduleSuicide();
                    } catch (RemoteException e) {
                        // If the other end already died, then our work here is done.
                    }
                } else {
                    Slog.w(TAG, "Process/uid not found attempting kill of "
                            + processName + " / " + uid);
                }
            }
        } else {
            throw new SecurityException(callerUid + " cannot kill app process: " +
                    processName);
        }
    }

    private void forceStopPackageLocked(final String packageName, int uid, String reason) {
        forceStopPackageLocked(packageName, UserHandle.getAppId(uid), false,
                false, true, false, UserHandle.getUserId(uid), reason);
        Intent intent = new Intent(Intent.ACTION_PACKAGE_RESTARTED,
                Uri.fromParts("package", packageName, null));
        if (!mProcessesReady) {
            intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY
                    | Intent.FLAG_RECEIVER_FOREGROUND);
        }
        intent.putExtra(Intent.EXTRA_UID, uid);
        intent.putExtra(Intent.EXTRA_USER_HANDLE, UserHandle.getUserId(uid));
        broadcastIntentLocked(null, null, intent,
                null, null, 0, null, null, null, AppOpsManager.OP_NONE,
                false, false,
                MY_PID, Process.SYSTEM_UID, UserHandle.getUserId(uid));
    }

    private void forceStopUserLocked(int userId, String reason) {
        forceStopPackageLocked(null, -1, false, false, true, false, userId, reason);
        Intent intent = new Intent(Intent.ACTION_USER_STOPPED);
        intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY
                | Intent.FLAG_RECEIVER_FOREGROUND);
        intent.putExtra(Intent.EXTRA_USER_HANDLE, userId);
        broadcastIntentLocked(null, null, intent,
                null, null, 0, null, null, null, AppOpsManager.OP_NONE,
                false, false,
                MY_PID, Process.SYSTEM_UID, UserHandle.USER_ALL);
    }

    private final boolean killPackageProcessesLocked(String packageName, int appId,
            int userId, int minOomAdj, boolean callerWillRestart, boolean allowRestart,
            boolean doit, boolean evenPersistent, String reason) {
        ArrayList<ProcessRecord> procs = new ArrayList<ProcessRecord>();

        // Remove all processes this package may have touched: all with the
        // same UID (except for the system or root user), and all whose name
        // matches the package name.
        final String procNamePrefix = packageName != null ? (packageName + ":") : null;
        final int NP = mProcessNames.getMap().size();
        for (int ip=0; ip<NP; ip++) {
            SparseArray<ProcessRecord> apps = mProcessNames.getMap().valueAt(ip);
            final int NA = apps.size();
            for (int ia=0; ia<NA; ia++) {
                ProcessRecord app = apps.valueAt(ia);
                if (app.persistent && !evenPersistent) {
                    // we don't kill persistent processes
                    continue;
                }
                if (app.removed) {
                    if (doit) {
                        procs.add(app);
                    }
                    continue;
                }

                // Skip process if it doesn't meet our oom adj requirement.
                if (app.setAdj < minOomAdj) {
                    continue;
                }

                // If no package is specified, we call all processes under the
                // give user id.
                if (packageName == null) {
                    if (app.userId != userId) {
                        continue;
                    }
                    if (appId >= 0 && UserHandle.getAppId(app.uid) != appId) {
                        continue;
                    }
                // Package has been specified, we want to hit all processes
                // that match it.  We need to qualify this by the processes
                // that are running under the specified app and user ID.
                } else {
                    if (UserHandle.getAppId(app.uid) != appId) {
                        continue;
                    }
                    if (userId != UserHandle.USER_ALL && app.userId != userId) {
                        continue;
                    }
                    if (!app.pkgList.containsKey(packageName)) {
                        continue;
                    }
                }

                // Process has passed all conditions, kill it!
                if (!doit) {
                    return true;
                }
                app.removed = true;
                procs.add(app);
            }
        }

        int N = procs.size();
        for (int i=0; i<N; i++) {
            removeProcessLocked(procs.get(i), callerWillRestart, allowRestart, reason);
        }
        updateOomAdjLocked();
        return N > 0;
    }

    private final boolean forceStopPackageLocked(String name, int appId,
            boolean callerWillRestart, boolean purgeCache, boolean doit,
            boolean evenPersistent, int userId, String reason) {
        int i;
        int N;

        if (userId == UserHandle.USER_ALL && name == null) {
            Slog.w(TAG, "Can't force stop all processes of all users, that is insane!");
        }

        if (appId < 0 && name != null) {
            try {
                appId = UserHandle.getAppId(
                        AppGlobals.getPackageManager().getPackageUid(name, 0));
            } catch (RemoteException e) {
            }
        }

        if (doit) {
            if (name != null) {
                Slog.i(TAG, "Force stopping " + name + " appid=" + appId
                        + " user=" + userId + ": " + reason);
            } else {
                Slog.i(TAG, "Force stopping u" + userId + ": " + reason);
            }

            final ArrayMap<String, SparseArray<Long>> pmap = mProcessCrashTimes.getMap();
            for (int ip=pmap.size()-1; ip>=0; ip--) {
                SparseArray<Long> ba = pmap.valueAt(ip);
                for (i=ba.size()-1; i>=0; i--) {
                    boolean remove = false;
                    final int entUid = ba.keyAt(i);
                    if (name != null) {
                        if (userId == UserHandle.USER_ALL) {
                            if (UserHandle.getAppId(entUid) == appId) {
                                remove = true;
                            }
                        } else {
                            if (entUid == UserHandle.getUid(userId, appId)) {
                                remove = true;
                            }
                        }
                    } else if (UserHandle.getUserId(entUid) == userId) {
                        remove = true;
                    }
                    if (remove) {
                        ba.removeAt(i);
                    }
                }
                if (ba.size() == 0) {
                    pmap.removeAt(ip);
                }
            }
        }

        boolean didSomething = killPackageProcessesLocked(name, appId, userId,
                -100, callerWillRestart, true, doit, evenPersistent,
                name == null ? ("stop user " + userId) : ("stop " + name));

        if (mStackSupervisor.forceStopPackageLocked(name, doit, evenPersistent, userId)) {
            if (!doit) {
                return true;
            }
            didSomething = true;
        }

        if (mServices.forceStopLocked(name, userId, evenPersistent, doit)) {
            if (!doit) {
                return true;
            }
            didSomething = true;
        }

        if (name == null) {
            // Remove all sticky broadcasts from this user.
            mStickyBroadcasts.remove(userId);
        }

        ArrayList<ContentProviderRecord> providers = new ArrayList<ContentProviderRecord>();
        if (mProviderMap.collectForceStopProviders(name, appId, doit, evenPersistent,
                userId, providers)) {
            if (!doit) {
                return true;
            }
            didSomething = true;
        }
        N = providers.size();
        for (i=0; i<N; i++) {
            removeDyingProviderLocked(null, providers.get(i), true);
        }

        // Remove transient permissions granted from/to this package/user
        removeUriPermissionsForPackageLocked(name, userId, false);

        if (name == null) {
            // Remove pending intents.  For now we only do this when force
            // stopping users, because we have some problems when doing this
            // for packages -- app widgets are not currently cleaned up for
            // such packages, so they can be left with bad pending intents.
            if (mIntentSenderRecords.size() > 0) {
                Iterator<WeakReference<PendingIntentRecord>> it
                        = mIntentSenderRecords.values().iterator();
                while (it.hasNext()) {
                    WeakReference<PendingIntentRecord> wpir = it.next();
                    if (wpir == null) {
                        it.remove();
                        continue;
                    }
                    PendingIntentRecord pir = wpir.get(
