/*
 * Copyright 2014, The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.managedprovisioning;

import android.app.admin.DevicePolicyManager;
import android.content.pm.PackageManager;
import android.content.pm.UserInfo;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.UserHandle;
import android.os.UserManager;

import com.android.managedprovisioning.task.DeleteNonRequiredAppsTask;
import com.android.managedprovisioning.task.DisableInstallShortcutListenersTask;

import java.util.List;

/**
 * After a system update, this class resets the cross-profile intent filters and checks
 * if apps that have been added to the system image need to be deleted.
 */
public class PreBootListener extends BroadcastReceiver {

    private UserManager mUserManager;
    private PackageManager mPackageManager;
    private DevicePolicyManager mDevicePolicyManager;

    @Override
    public void onReceive(Context context, Intent intent) {
        if (context.getUserId() != UserHandle.USER_SYSTEM) {
            return;
        }
        mUserManager = (UserManager) context.getSystemService(Context.USER_SERVICE);

        mDevicePolicyManager = (DevicePolicyManager) context.getSystemService(
                Context.DEVICE_POLICY_SERVICE);
        mPackageManager = context.getPackageManager();

        // Check for device owner.
        if (mDevicePolicyManager.getDeviceOwner() != null && DeleteNonRequiredAppsTask
                    .shouldDeleteNonRequiredApps(context, UserHandle.USER_SYSTEM)) {

            // Delete new apps.
            new DeleteNonRequiredAppsTask(context, mDevicePolicyManager.getDeviceOwner(),
                    DeleteNonRequiredAppsTask.DEVICE_OWNER,
                    false /* not creating new profile */,
                    UserHandle.USER_SYSTEM,
                    false /* delete non-required system apps */,
                    new DeleteNonRequiredAppsTask.Callback() {

                        @Override
                        public void onSuccess() {}

                        @Override
                        public void onError() {
                            ProvisionLogger.loge("Error while checking if there are new system "
                                    + "apps that need to be deleted");
                        }
                    }).run();
        }

        for (UserInfo userInfo : mUserManager.getUsers()) {
            if (userInfo.isManagedProfile()) {
                mUserManager.setUserRestriction(UserManager.DISALLOW_WALLPAPER, true,
                        userInfo.getUserHandle());
                runManagedProfileDisablingTasks(userInfo.id, context);
            } else {
                // if this user has managed profiles, reset the cross-profile intent filters between
                // this user and its managed profiles.
                resetCrossProfileIntentFilters(userInfo.id);
            }
        }
    }

    /**
     * Reset the cross profile intent filters between userId and all of its managed profiles if any.
     */
    private void resetCrossProfileIntentFilters(int userId) {
        List<UserInfo> profiles = mUserManager.getProfiles(userId);
        if (profiles.size() <= 1) {
            return;
        }

        // Removes cross profile intent filters from the parent to all the managed profiles.
        mPackageManager.clearCrossProfileIntentFilters(userId);

        // For each managed profile reset cross profile intent filters
        for (UserInfo profile : profiles) {
            if (!profile.isManagedProfile()) {
                continue;
            }
            mPackageManager.clearCrossProfileIntentFilters(profile.id);
            CrossProfileIntentFiltersHelper.setFilters(
                    mPackageManager, userId, profile.id);
        }
    }

    void runManagedProfileDisablingTasks(int userId, Context context) {
        ComponentName profileOwner = mDevicePolicyManager.getProfileOwnerAsUser(userId);
        if (profileOwner == null) {
            // Shouldn't happen.
            ProvisionLogger.loge("No profile owner on managed profile " + userId);
            return;
        }
        final DisableInstallShortcutListenersTask disableInstallShortcutListenersTask
                = new DisableInstallShortcutListenersTask(context, userId);

        final DeleteNonRequiredAppsTask deleteNonRequiredAppsTask
                = new DeleteNonRequiredAppsTask(context,
            profileOwner.getPackageName(),
            DeleteNonRequiredAppsTask.PROFILE_OWNER,
            false /* not creating new profile */,
            userId,
            false /* delete non-required system apps */,
            new DeleteNonRequiredAppsTask.Callback() {

                @Override
                public void onSuccess() {
                    disableInstallShortcutListenersTask.run();
                }

                @Override
                public void onError() {
                    ProvisionLogger.loge("Error while checking if there are new system "
                            + "apps that need to be deleted");
                }
            });

        deleteNonRequiredAppsTask.run();
    }
}
