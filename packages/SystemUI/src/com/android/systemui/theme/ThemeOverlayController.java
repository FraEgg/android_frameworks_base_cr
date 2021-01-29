/*
 * Copyright (C) 2019 The Android Open Source Project
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
package com.android.systemui.theme;

import static com.android.systemui.theme.ThemeOverlayApplier.OVERLAY_CATEGORY_ACCENT_COLOR;
import static com.android.systemui.theme.ThemeOverlayApplier.OVERLAY_CATEGORY_SYSTEM_PALETTE;

import android.annotation.Nullable;
import android.app.ActivityManager;
import android.app.WallpaperColors;
import android.app.WallpaperManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.om.FabricatedOverlay;
import android.content.om.OverlayIdentifier;
import android.content.pm.UserInfo;
import android.database.ContentObserver;
import android.graphics.Color;
import android.net.Uri;
import android.os.Handler;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.Log;

import androidx.annotation.NonNull;

import com.android.internal.annotations.VisibleForTesting;
import com.android.systemui.Dumpable;
import com.android.systemui.SystemUI;
import com.android.systemui.broadcast.BroadcastDispatcher;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.dagger.qualifiers.Background;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.dump.DumpManager;
import com.android.systemui.statusbar.FeatureFlags;
import com.android.systemui.statusbar.policy.KeyguardStateController;
import com.android.systemui.util.settings.SecureSettings;

import com.google.android.collect.Sets;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.stream.Collectors;

import javax.inject.Inject;

/**
 * Controls the application of theme overlays across the system for all users.
 * This service is responsible for:
 * - Observing changes to Settings.Secure.THEME_CUSTOMIZATION_OVERLAY_PACKAGES and applying the
 * corresponding overlays across the system
 * - Observing user switches, applying the overlays for the current user to user 0 (for systemui)
 * - Observing work profile changes and applying overlays from the primary user to their
 * associated work profiles
 */
@SysUISingleton
public class ThemeOverlayController extends SystemUI implements Dumpable {
    protected static final String TAG = "ThemeOverlayController";
    private static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);

    protected static final int MAIN = 0;
    protected static final int ACCENT = 1;

    // If lock screen wallpaper colors should also be considered when selecting the theme.
    // Doing this has performance impact, given that overlays would need to be swapped when
    // the device unlocks.
    @VisibleForTesting
    static final boolean USE_LOCK_SCREEN_WALLPAPER = false;

    private final ThemeOverlayApplier mThemeManager;
    private final UserManager mUserManager;
    private final BroadcastDispatcher mBroadcastDispatcher;
    private final Executor mBgExecutor;
    private final SecureSettings mSecureSettings;
    private final Executor mMainExecutor;
    private final Handler mBgHandler;
    private final WallpaperManager mWallpaperManager;
    private final KeyguardStateController mKeyguardStateController;
    private final boolean mIsMonetEnabled;
    private WallpaperColors mLockColors;
    private WallpaperColors mSystemColors;
    // If fabricated overlays were already created for the current theme.
    private boolean mNeedsOverlayCreation;
    // Dominant olor extracted from wallpaper, NOT the color used on the overlay
    protected int mMainWallpaperColor = Color.TRANSPARENT;
    // Accent color extracted from wallpaper, NOT the color used on the overlay
    protected int mWallpaperAccentColor = Color.TRANSPARENT;
    // System colors overlay
    private FabricatedOverlay mSystemOverlay;
    // Accent colors overlay
    private FabricatedOverlay mAccentOverlay;

    @Inject
    public ThemeOverlayController(Context context, BroadcastDispatcher broadcastDispatcher,
            @Background Handler bgHandler, @Main Executor mainExecutor,
            @Background Executor bgExecutor, ThemeOverlayApplier themeOverlayApplier,
            SecureSettings secureSettings, WallpaperManager wallpaperManager,
            UserManager userManager, KeyguardStateController keyguardStateController,
            DumpManager dumpManager, FeatureFlags featureFlags) {
        super(context);

        mIsMonetEnabled = featureFlags.isMonetEnabled();
        mBroadcastDispatcher = broadcastDispatcher;
        mUserManager = userManager;
        mBgExecutor = bgExecutor;
        mMainExecutor = mainExecutor;
        mBgHandler = bgHandler;
        mThemeManager = themeOverlayApplier;
        mSecureSettings = secureSettings;
        mWallpaperManager = wallpaperManager;
        mKeyguardStateController = keyguardStateController;
        dumpManager.registerDumpable(TAG, this);
    }

    @Override
    public void start() {
        if (DEBUG) Log.d(TAG, "Start");
        final IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_USER_SWITCHED);
        filter.addAction(Intent.ACTION_MANAGED_PROFILE_ADDED);
        mBroadcastDispatcher.registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (DEBUG) Log.d(TAG, "Updating overlays for user switch / profile added.");
                updateThemeOverlays();
            }
        }, filter, mBgExecutor, UserHandle.ALL);
        mSecureSettings.registerContentObserverForUser(
                Settings.Secure.getUriFor(Settings.Secure.THEME_CUSTOMIZATION_OVERLAY_PACKAGES),
                false,
                new ContentObserver(mBgHandler) {
                    @Override
                    public void onChange(boolean selfChange, Collection<Uri> collection, int flags,
                            int userId) {
                        if (DEBUG) Log.d(TAG, "Overlay changed for user: " + userId);
                        if (ActivityManager.getCurrentUser() == userId) {
                            updateThemeOverlays();
                        }
                    }
                },
                UserHandle.USER_ALL);

        // Upon boot, make sure we have the most up to date colors
        mBgExecutor.execute(() -> {
            WallpaperColors lockColors = mWallpaperManager.getWallpaperColors(
                    WallpaperManager.FLAG_LOCK);
            WallpaperColors systemColor = mWallpaperManager.getWallpaperColors(
                    WallpaperManager.FLAG_SYSTEM);
            mMainExecutor.execute(() -> {
                if (USE_LOCK_SCREEN_WALLPAPER) {
                    mLockColors = lockColors;
                }
                mSystemColors = systemColor;
                reevaluateSystemTheme();
            });
        });
        if (USE_LOCK_SCREEN_WALLPAPER) {
            mKeyguardStateController.addCallback(new KeyguardStateController.Callback() {
                @Override
                public void onKeyguardShowingChanged() {
                    if (mLockColors == null) {
                        return;
                    }
                    // It's possible that the user has a lock screen wallpaper. On this case we'll
                    // end up with different colors after unlocking.
                    reevaluateSystemTheme();
                }
            });
        }
        mWallpaperManager.addOnColorsChangedListener((wallpaperColors, which) -> {
            if (USE_LOCK_SCREEN_WALLPAPER && (which & WallpaperManager.FLAG_LOCK) != 0) {
                mLockColors = wallpaperColors;
                if (DEBUG) {
                    Log.d(TAG, "got new lock colors: " + wallpaperColors + " where: " + which);
                }
            }
            if ((which & WallpaperManager.FLAG_SYSTEM) != 0) {
                mSystemColors = wallpaperColors;
                if (DEBUG) {
                    Log.d(TAG, "got new lock colors: " + wallpaperColors + " where: " + which);
                }
            }
            reevaluateSystemTheme();
        }, null, UserHandle.USER_ALL);
    }

    private void reevaluateSystemTheme() {
        WallpaperColors currentColors =
                mKeyguardStateController.isShowing() && mLockColors != null
                        ? mLockColors : mSystemColors;

        final int mainColor;
        final int accentCandidate;
        if (currentColors == null) {
            mainColor = Color.TRANSPARENT;
            accentCandidate = Color.TRANSPARENT;
        } else {
            mainColor = getDominantColor(currentColors);
            accentCandidate = getAccentColor(currentColors);
        }

        if (mMainWallpaperColor == mainColor && mWallpaperAccentColor == accentCandidate) {
            return;
        }

        mMainWallpaperColor = mainColor;
        mWallpaperAccentColor = accentCandidate;

        if (mIsMonetEnabled) {
            mSystemOverlay = getOverlay(mMainWallpaperColor, MAIN);
            mAccentOverlay = getOverlay(mWallpaperAccentColor, ACCENT);
            mNeedsOverlayCreation = true;
            if (DEBUG) {
                Log.d(TAG, "fetched overlays. system: " + mSystemOverlay + " accent: "
                        + mAccentOverlay);
            }
        }

        updateThemeOverlays();
    }

    /**
     * Return the main theme color from a given {@link WallpaperColors} instance.
     */
    protected int getDominantColor(@NonNull WallpaperColors wallpaperColors) {
        return wallpaperColors.getPrimaryColor().toArgb();
    }

    protected int getAccentColor(@NonNull WallpaperColors wallpaperColors) {
        Color accentCandidate = wallpaperColors.getSecondaryColor();
        if (accentCandidate == null) {
            accentCandidate = wallpaperColors.getTertiaryColor();
        }
        if (accentCandidate == null) {
            accentCandidate = wallpaperColors.getPrimaryColor();
        }
        return accentCandidate.toArgb();
    }

    /**
     * Given a color candidate, return an overlay definition.
     */
    protected @Nullable FabricatedOverlay getOverlay(int color, int type) {
        return null;
    }

    private void updateThemeOverlays() {
        final int currentUser = ActivityManager.getCurrentUser();
        final String overlayPackageJson = mSecureSettings.getStringForUser(
                Settings.Secure.THEME_CUSTOMIZATION_OVERLAY_PACKAGES,
                currentUser);
        if (DEBUG) Log.d(TAG, "updateThemeOverlays. Setting: " + overlayPackageJson);
        final Map<String, OverlayIdentifier> categoryToPackage = new ArrayMap<>();
        if (!TextUtils.isEmpty(overlayPackageJson)) {
            try {
                JSONObject object = new JSONObject(overlayPackageJson);
                for (String category : ThemeOverlayApplier.THEME_CATEGORIES) {
                    if (object.has(category)) {
                        OverlayIdentifier identifier =
                                new OverlayIdentifier(object.getString(category));
                        categoryToPackage.put(category, identifier);
                    }
                }
            } catch (JSONException e) {
                Log.i(TAG, "Failed to parse THEME_CUSTOMIZATION_OVERLAY_PACKAGES.", e);
            }
        }

        // Let's generate system overlay if the style picker decided to override it.
        OverlayIdentifier systemPalette = categoryToPackage.get(OVERLAY_CATEGORY_SYSTEM_PALETTE);
        if (mIsMonetEnabled && systemPalette != null && systemPalette.getPackageName() != null) {
            try {
                int color = Integer.parseInt(systemPalette.getPackageName().toLowerCase(), 16);
                mSystemOverlay = getOverlay(color, MAIN);
                mNeedsOverlayCreation = true;
                categoryToPackage.remove(OVERLAY_CATEGORY_SYSTEM_PALETTE);
            } catch (NumberFormatException e) {
                Log.w(TAG, "Invalid color definition: " + systemPalette.getPackageName());
            }
        }

        // Same for accent color.
        OverlayIdentifier accentPalette = categoryToPackage.get(OVERLAY_CATEGORY_ACCENT_COLOR);
        if (mIsMonetEnabled && accentPalette != null && accentPalette.getPackageName() != null) {
            try {
                int color = Integer.parseInt(accentPalette.getPackageName().toLowerCase(), 16);
                mAccentOverlay = getOverlay(color, ACCENT);
                mNeedsOverlayCreation = true;
                categoryToPackage.remove(OVERLAY_CATEGORY_ACCENT_COLOR);
            } catch (NumberFormatException e) {
                Log.w(TAG, "Invalid color definition: " + accentPalette.getPackageName());
            }
        }

        // Compatibility with legacy themes, where full packages were defined, instead of just
        // colors.
        if (!categoryToPackage.containsKey(OVERLAY_CATEGORY_SYSTEM_PALETTE)
                && mSystemOverlay != null) {
            categoryToPackage.put(OVERLAY_CATEGORY_SYSTEM_PALETTE, mSystemOverlay.getIdentifier());
        }
        if (!categoryToPackage.containsKey(OVERLAY_CATEGORY_ACCENT_COLOR)
                && mAccentOverlay != null) {
            categoryToPackage.put(OVERLAY_CATEGORY_ACCENT_COLOR, mAccentOverlay.getIdentifier());
        }

        Set<UserHandle> userHandles = Sets.newHashSet(UserHandle.of(currentUser));
        for (UserInfo userInfo : mUserManager.getEnabledProfiles(currentUser)) {
            if (userInfo.isManagedProfile()) {
                userHandles.add(userInfo.getUserHandle());
            }
        }
        if (DEBUG) {
            Log.d(TAG, "Applying overlays: " + categoryToPackage.keySet().stream()
                    .map(key -> key + " -> " + categoryToPackage.get(key)).collect(
                            Collectors.joining(", ")));
        }
        if (mNeedsOverlayCreation) {
            mNeedsOverlayCreation = false;
            mThemeManager.applyCurrentUserOverlays(categoryToPackage, new FabricatedOverlay[] {
                    mSystemOverlay, mAccentOverlay
            }, userHandles);
        } else {
            mThemeManager.applyCurrentUserOverlays(categoryToPackage, null, userHandles);
        }
    }

    @Override
    public void dump(@NonNull FileDescriptor fd, @NonNull PrintWriter pw, @NonNull String[] args) {
        pw.println("USE_LOCK_SCREEN_WALLPAPER=" + USE_LOCK_SCREEN_WALLPAPER);
        pw.println("mLockColors=" + mLockColors);
        pw.println("mSystemColors=" + mSystemColors);
        pw.println("mMainWallpaperColor=" + Integer.toHexString(mMainWallpaperColor));
        pw.println("mWallpaperAccentColor=" + Integer.toHexString(mWallpaperAccentColor));
        pw.println("mSystemOverlayColor=" + mSystemOverlay);
        pw.println("mAccentOverlayColor=" + mAccentOverlay);
        pw.println("mIsMonetEnabled=" + mIsMonetEnabled);
        pw.println("mNeedsOverlayCreation=" + mNeedsOverlayCreation);
    }
}
