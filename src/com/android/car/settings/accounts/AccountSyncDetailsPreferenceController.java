/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.car.settings.accounts;

import android.accounts.Account;
import android.accounts.AccountManager;
import android.app.Activity;
import android.car.drivingstate.CarUxRestrictions;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentSender;
import android.content.SyncAdapterType;
import android.content.SyncInfo;
import android.content.SyncStatusInfo;
import android.content.SyncStatusObserver;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.os.Bundle;
import android.os.UserHandle;
import android.text.TextUtils;
import android.text.format.DateFormat;

import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import androidx.collection.ArrayMap;
import androidx.preference.Preference;
import androidx.preference.PreferenceGroup;

import com.android.car.settings.R;
import com.android.car.settings.common.FragmentController;
import com.android.car.settings.common.Logger;
import com.android.car.settings.common.PreferenceController;
import com.android.settingslib.accounts.AuthenticatorHelper;
import com.android.settingslib.utils.ThreadUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Controller that presents all visible sync adapters for an account.
 *
 * <p>Largely derived from {@link com.android.settings.accounts.AccountSyncSettings}.
 */
public class AccountSyncDetailsPreferenceController extends
        PreferenceController<PreferenceGroup> implements
        AuthenticatorHelper.OnAccountsUpdateListener {
    private static final Logger LOG = new Logger(AccountSyncDetailsPreferenceController.class);
    /**
     * Preferences are keyed by authority so that existing SyncPreferences can be reused on account
     * sync.
     */
    private final Map<String, SyncPreference> mSyncPreferences = new ArrayMap<>();
    private final Set<SyncAdapterType> mInvisibleAdapters = new HashSet<>();
    private boolean mIsStarted = false;
    private Account mAccount;
    private UserHandle mUserHandle;
    private AuthenticatorHelper mAuthenticatorHelper;
    private Object mStatusChangeListenerHandle;
    private SyncStatusObserver mSyncStatusObserver =
            which -> ThreadUtils.postOnMainThread(() -> {
                // The observer call may occur even if the fragment hasn't been started, so
                // only force an update if the fragment hasn't been stopped.
                if (mIsStarted) {
                    forceUpdateSyncCategory();
                }
            });

    public AccountSyncDetailsPreferenceController(Context context, String preferenceKey,
            FragmentController fragmentController, CarUxRestrictions uxRestrictions) {
        super(context, preferenceKey, fragmentController, uxRestrictions);
    }

    /** Sets the account that the sync preferences are being shown for. */
    public void setAccount(Account account) {
        mAccount = account;
    }

    /** Sets the user handle used by the controller. */
    public void setUserHandle(UserHandle userHandle) {
        mUserHandle = userHandle;
    }

    @Override
    protected Class<PreferenceGroup> getPreferenceType() {
        return PreferenceGroup.class;
    }

    /**
     * Verifies that the controller was properly initialized with {@link #setAccount(Account)} and
     * {@link #setUserHandle(UserHandle)}.
     *
     * @throws IllegalStateException if the account or user handle is {@code null}
     */
    @Override
    protected void checkInitialized() {
        LOG.v("checkInitialized");
        if (mAccount == null) {
            throw new IllegalStateException(
                    "AccountSyncDetailsPreferenceController must be initialized by calling "
                            + "setAccount(Account)");
        }
        if (mUserHandle == null) {
            throw new IllegalStateException(
                    "AccountSyncDetailsPreferenceController must be initialized by calling "
                            + "setUserHandle(UserHandle)");
        }
    }

    /**
     * Initializes the authenticator helper.
     */
    @Override
    protected void onCreateInternal() {
        mAuthenticatorHelper = new AuthenticatorHelper(getContext(), mUserHandle, /* listener= */
                this);
    }

    /**
     * Registers the account update and sync status change callbacks.
     */
    @Override
    protected void onStartInternal() {
        mIsStarted = true;
        mAuthenticatorHelper.listenToAccountUpdates();

        mStatusChangeListenerHandle = ContentResolver.addStatusChangeListener(
                ContentResolver.SYNC_OBSERVER_TYPE_ACTIVE
                        | ContentResolver.SYNC_OBSERVER_TYPE_STATUS
                        | ContentResolver.SYNC_OBSERVER_TYPE_SETTINGS, mSyncStatusObserver);
    }

    /**
     * Unregisters the account update and sync status change callbacks.
     */
    @Override
    protected void onStopInternal() {
        mIsStarted = false;
        mAuthenticatorHelper.stopListeningToAccountUpdates();
        if (mStatusChangeListenerHandle != null) {
            ContentResolver.removeStatusChangeListener(mStatusChangeListenerHandle);
        }
    }

    @Override
    public void onAccountsUpdate(UserHandle userHandle) {
        // Only force a refresh if accounts have changed for the current user.
        if (userHandle.equals(mUserHandle)) {
            forceUpdateSyncCategory();
        }
    }

    @Override
    public void updateState(PreferenceGroup preferenceGroup) {
        // Add preferences for each account if the controller should be available
        forceUpdateSyncCategory();
    }

    /**
     * Handles toggling/syncing when a sync preference is clicked on.
     *
     * <p>Largely derived from
     * {@link com.android.settings.accounts.AccountSyncSettings#onPreferenceTreeClick}.
     */
    private boolean onSyncPreferenceClicked(SyncPreference preference) {
        String authority = preference.getKey();
        String packageName = preference.getPackageName();
        int uid = preference.getUid();
        if (preference.isOneTimeSyncMode()) {
            // If the sync adapter doesn't have access to the account we either
            // request access by starting an activity if possible or kick off the
            // sync which will end up posting an access request notification.
            if (requestAccountAccessIfNeeded(packageName, uid)) {
                return true;
            }
            requestSync(authority);
        } else {
            boolean syncOn = preference.isChecked();
            int userId = mUserHandle.getIdentifier();
            boolean oldSyncState = ContentResolver.getSyncAutomaticallyAsUser(mAccount,
                    authority, userId);
            if (syncOn != oldSyncState) {
                // Toggling this switch triggers sync but we may need a user approval.
                // If the sync adapter doesn't have access to the account we either
                // request access by starting an activity if possible or kick off the
                // sync which will end up posting an access request notification.
                if (syncOn && requestAccountAccessIfNeeded(packageName, uid)) {
                    return true;
                }
                // if we're enabling sync, this will request a sync as well
                ContentResolver.setSyncAutomaticallyAsUser(mAccount, authority, syncOn, userId);
                // if the master sync switch is off, the request above will
                // get dropped.  when the user clicks on this toggle,
                // we want to force the sync, however.
                if (!ContentResolver.getMasterSyncAutomaticallyAsUser(userId) || !syncOn) {
                    if (syncOn) {
                        requestSync(authority);
                    } else {
                        cancelSync(authority);
                    }
                }
            }
        }
        return true;
    }

    /**
     * Requests a sync.
     *
     * <p>Derived from
     * {@link com.android.settings.accounts.AccountSyncSettings#requestOrCancelSync}.
     */
    private void requestSync(String authority) {
        Bundle extras = new Bundle();
        extras.putBoolean(ContentResolver.SYNC_EXTRAS_MANUAL, true);
        ContentResolver.requestSyncAsUser(mAccount, authority, mUserHandle.getIdentifier(),
                extras);
    }

    /**
     * Cancels a sync.
     *
     * <p>Derived from
     * {@link com.android.settings.accounts.AccountSyncSettings#requestOrCancelSync}.
     */
    private void cancelSync(String authority) {
        ContentResolver.cancelSyncAsUser(mAccount, authority, mUserHandle.getIdentifier());
    }

    /**
     * Requests account access if needed.
     *
     * <p>Copied from
     * {@link com.android.settings.accounts.AccountSyncSettings#requestAccountAccessIfNeeded}.
     */
    private boolean requestAccountAccessIfNeeded(String packageName, int uid) {
        if (packageName == null) {
            return false;
        }

        AccountManager accountManager = getContext().getSystemService(AccountManager.class);
        if (!accountManager.hasAccountAccess(mAccount, packageName, mUserHandle)) {
            IntentSender intent = accountManager.createRequestAccountAccessIntentSenderAsUser(
                    mAccount, packageName, mUserHandle);
            if (intent != null) {
                try {
                    getFragmentController().startIntentSenderForResult(intent,
                            uid, /* fillInIntent= */ null, /* flagsMask= */0,
                            /* flagsValues= */0, /* options= */null,
                            this::onAccountRequestApproved);
                    return true;
                } catch (IntentSender.SendIntentException e) {
                    LOG.e("Error requesting account access", e);
                }
            }
        }
        return false;
    }

    /** Handles a sync adapter refresh when an account request was approved. */
    public void onAccountRequestApproved(int uid, int resultCode, @Nullable Intent data) {
        if (resultCode == Activity.RESULT_OK) {
            for (SyncPreference pref : mSyncPreferences.values()) {
                if (pref.getUid() == uid) {
                    onSyncPreferenceClicked(pref);
                    return;
                }
            }
        }
    }

    /** Forces a refresh of the sync adapter preferences. */
    private void forceUpdateSyncCategory() {
        Set<String> preferencesToRemove = new HashSet<>(mSyncPreferences.keySet());
        List<SyncPreference> preferences = getSyncPreferences(preferencesToRemove);

        // Sort the preferences, add the ones that need to be added, and remove the ones that need
        // to be removed. Manually set the order so that existing preferences are reordered
        // correctly.
        Collections.sort(preferences, Comparator.comparing(
                (SyncPreference a) -> a.getTitle().toString())
                .thenComparing((SyncPreference a) -> a.getSummary().toString()));

        for (int i = 0; i < preferences.size(); i++) {
            SyncPreference pref = preferences.get(i);
            pref.setOrder(i);
            mSyncPreferences.put(pref.getKey(), pref);
            getPreference().addPreference(pref);
        }

        for (String key : preferencesToRemove) {
            getPreference().removePreference(mSyncPreferences.get(key));
            mSyncPreferences.remove(key);
        }
    }

    /**
     * Returns a list of preferences corresponding to the visible sync adapters for the current
     * user.
     *
     * <p> Derived from {@link com.android.settings.accounts.AccountSyncSettings#setFeedsState}
     * and {@link com.android.settings.accounts.AccountSyncSettings#updateAccountSwitches}.
     *
     * @param preferencesToRemove the keys for the preferences currently being shown; only the keys
     *                            for preferences to be removed will remain after method execution
     */
    private List<SyncPreference> getSyncPreferences(Set<String> preferencesToRemove) {
        int userId = mUserHandle.getIdentifier();
        PackageManager packageManager = getContext().getPackageManager();
        SyncAdapterType[] syncAdapters = ContentResolver.getSyncAdapterTypesAsUser(
                mUserHandle.getIdentifier());
        List<SyncInfo> currentSyncs = ContentResolver.getCurrentSyncsAsUser(userId);
        // Whether one time sync is enabled rather than automtic sync
        boolean oneTimeSyncMode = !ContentResolver.getMasterSyncAutomaticallyAsUser(userId);
        boolean syncIsFailing = false;
        mInvisibleAdapters.clear();

        List<SyncPreference> syncPreferences = new ArrayList<>();

        for (int i = 0; i < syncAdapters.length; i++) {
            SyncAdapterType syncAdapter = syncAdapters[i];
            String authority = syncAdapter.authority;

            // If the sync adapter is not for this account type, don't show it
            if (!syncAdapter.accountType.equals(mAccount.type)) {
                continue;
            }

            if (!syncAdapter.isUserVisible()) {
                // keep track of invisible sync adapters, so sync now forces them to sync as well.
                // TODO: sync invisible adapters
                mInvisibleAdapters.add(syncAdapter);
                // If the sync adapter is not visible, don't show it
                continue;
            }

            boolean isSyncable = ContentResolver.getIsSyncableAsUser(mAccount, authority,
                    mUserHandle.getIdentifier()) > 0;
            // If the adapter is not syncable, don't show it
            if (!isSyncable) {
                continue;
            }

            int uid;
            try {
                uid = packageManager.getPackageUidAsUser(syncAdapter.getPackageName(), userId);
            } catch (PackageManager.NameNotFoundException e) {
                LOG.e("No uid for package" + syncAdapter.getPackageName(), e);
                // If we can't get the Uid for the package hosting the sync adapter, don't show it
                continue;
            }

            CharSequence title = getTitle(authority);
            if (TextUtils.isEmpty(title)) {
                continue;
            }

            // If we've reached this point, the sync adapter should be shown. If a preference for
            // the sync adapter already exists, update its state. Otherwise, create a new
            // preference.
            SyncPreference pref = mSyncPreferences.getOrDefault(authority,
                    new SyncPreference(getContext(), authority));
            pref.setUid(uid);
            pref.setPackageName(syncAdapter.getPackageName());
            pref.setTitle(title);
            pref.setOnPreferenceClickListener(
                    (Preference p) -> onSyncPreferenceClicked((SyncPreference) p));

            // Keep track of preferences that need to be added and removed
            syncPreferences.add(pref);
            preferencesToRemove.remove(authority);

            SyncStatusInfo status = ContentResolver.getSyncStatusAsUser(mAccount, authority,
                    userId);
            boolean syncEnabled = ContentResolver.getSyncAutomaticallyAsUser(mAccount, authority,
                    userId);
            boolean activelySyncing = isSyncing(currentSyncs, authority);

            // The preference should be checked if one one-time sync or regular sync is enabled
            boolean checked = oneTimeSyncMode || syncEnabled;
            pref.setChecked(checked);

            String summary = getSummary(status, syncEnabled, activelySyncing);
            pref.setSummary(summary);

            // Update the sync state so the icon is updated
            SyncPreference.SYNC_STATE syncState = getSyncState(status, syncEnabled,
                    activelySyncing);
            pref.setSyncState(syncState);
            pref.setOneTimeSyncMode(oneTimeSyncMode);

            // TODO: handle when sync is failing
            boolean syncIsPending = status != null && status.pending;
            if (syncState == SyncPreference.SYNC_STATE.FAILED && !activelySyncing
                    && !syncIsPending) {
                syncIsFailing = true;
            }
        }

        return syncPreferences;
    }

    /**
     * Returns the label for a given sync authority.
     *
     * @return the title if available, and an empty CharSequence otherwise
     */
    private CharSequence getTitle(String authority) {
        PackageManager packageManager = getContext().getPackageManager();
        ProviderInfo providerInfo = packageManager.resolveContentProviderAsUser(
                authority, /* flags= */ 0, mUserHandle.getIdentifier());
        if (providerInfo == null) {
            return "";
        }

        return providerInfo.loadLabel(packageManager);
    }

    private String getSummary(SyncStatusInfo status, boolean syncEnabled, boolean activelySyncing) {
        long successEndTime = (status == null) ? 0 : status.lastSuccessTime;
        // Set the summary based on the current syncing state
        if (!syncEnabled) {
            return getContext().getString(R.string.sync_disabled);
        } else if (activelySyncing) {
            return getContext().getString(R.string.sync_in_progress);
        } else if (successEndTime != 0) {
            Date date = new Date();
            date.setTime(successEndTime);
            String timeString = formatSyncDate(date);
            return getContext().getString(R.string.last_synced, timeString);
        }
        return "";
    }

    private SyncPreference.SYNC_STATE getSyncState(SyncStatusInfo status, boolean syncEnabled,
            boolean activelySyncing) {
        boolean initialSync = status != null && status.initialize;
        boolean syncIsPending = status != null && status.pending;
        boolean lastSyncFailed = syncEnabled && status != null && status.lastFailureTime != 0
                && status.getLastFailureMesgAsInt(0)
                != ContentResolver.SYNC_ERROR_SYNC_ALREADY_IN_PROGRESS;
        if (activelySyncing && !initialSync) {
            return SyncPreference.SYNC_STATE.ACTIVE;
        } else if (syncIsPending && !initialSync) {
            return SyncPreference.SYNC_STATE.PENDING;
        } else if (lastSyncFailed) {
            return SyncPreference.SYNC_STATE.FAILED;
        }
        return SyncPreference.SYNC_STATE.NONE;
    }

    /** Returns whether a sync adapter is currently syncing for the account being shown. */
    private boolean isSyncing(List<SyncInfo> currentSyncs, String authority) {
        for (SyncInfo syncInfo : currentSyncs) {
            if (syncInfo.account.equals(mAccount) && syncInfo.authority.equals(authority)) {
                return true;
            }
        }
        return false;
    }

    @VisibleForTesting
    String formatSyncDate(Date date) {
        return DateFormat.getDateFormat(getContext()).format(date) + " " + DateFormat.getTimeFormat(
                getContext()).format(date);
    }
}