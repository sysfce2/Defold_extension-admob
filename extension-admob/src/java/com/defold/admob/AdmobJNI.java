package com.defold.admob;

import androidx.annotation.NonNull;
import android.util.Log;
import android.util.DisplayMetrics;
import android.app.Activity;
import android.view.Display;
import android.graphics.Rect;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.view.View;
import android.view.ViewGroup.LayoutParams;
import android.view.ViewGroup.MarginLayoutParams;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;
import android.provider.Settings;

import androidx.lifecycle.LifecycleObserver;
import androidx.lifecycle.ProcessLifecycleOwner;
import androidx.lifecycle.OnLifecycleEvent;
import androidx.lifecycle.Lifecycle.Event;

import com.google.android.libraries.ads.mobile.sdk.MobileAds;
import com.google.android.libraries.ads.mobile.sdk.appopen.AppOpenAd;
import com.google.android.libraries.ads.mobile.sdk.appopen.AppOpenAdEventCallback;
import com.google.android.libraries.ads.mobile.sdk.banner.AdSize;
import com.google.android.libraries.ads.mobile.sdk.banner.AdView;
import com.google.android.libraries.ads.mobile.sdk.banner.BannerAd;
import com.google.android.libraries.ads.mobile.sdk.banner.BannerAdEventCallback;
import com.google.android.libraries.ads.mobile.sdk.banner.BannerAdRefreshCallback;
import com.google.android.libraries.ads.mobile.sdk.banner.BannerAdRequest;
import com.google.android.libraries.ads.mobile.sdk.common.AdInspectorError;
import com.google.android.libraries.ads.mobile.sdk.common.AdLoadCallback;
import com.google.android.libraries.ads.mobile.sdk.common.AdRequest;
import com.google.android.libraries.ads.mobile.sdk.common.FullScreenContentError;
import com.google.android.libraries.ads.mobile.sdk.common.LoadAdError;
import com.google.android.libraries.ads.mobile.sdk.common.OnAdInspectorClosedListener;
import com.google.android.libraries.ads.mobile.sdk.common.RequestConfiguration;
import com.google.android.libraries.ads.mobile.sdk.initialization.AdapterStatus;
import com.google.android.libraries.ads.mobile.sdk.initialization.InitializationConfig;
import com.google.android.libraries.ads.mobile.sdk.initialization.InitializationStatus;
import com.google.android.libraries.ads.mobile.sdk.initialization.OnAdapterInitializationCompleteListener;
import com.google.android.libraries.ads.mobile.sdk.interstitial.InterstitialAd;
import com.google.android.libraries.ads.mobile.sdk.interstitial.InterstitialAdEventCallback;
import com.google.android.libraries.ads.mobile.sdk.rewarded.OnUserEarnedRewardListener;
import com.google.android.libraries.ads.mobile.sdk.rewarded.RewardItem;
import com.google.android.libraries.ads.mobile.sdk.rewarded.RewardedAd;
import com.google.android.libraries.ads.mobile.sdk.rewarded.RewardedAdEventCallback;
import com.google.android.libraries.ads.mobile.sdk.rewarded.ServerSideVerificationOptions;
import com.google.android.libraries.ads.mobile.sdk.rewardedinterstitial.RewardedInterstitialAd;
import com.google.android.libraries.ads.mobile.sdk.rewardedinterstitial.RewardedInterstitialAdEventCallback;

import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.json.JSONObject;
import org.json.JSONException;

public class AdmobJNI implements LifecycleObserver {

  private static final String TAG = "AdmobJNI";

  public static native void admobAddToQueue(int msg, String json);

  // duplicate of enums from admob_callback_private.h:
  // CONSTANTS:
  private static final int MSG_INTERSTITIAL =         1;
  private static final int MSG_REWARDED =             2;
  private static final int MSG_BANNER =               3;
  private static final int MSG_INITIALIZATION =       4;
  private static final int MSG_IDFA =                 5;
  private static final int MSG_REWARDED_INTERSTITIAL =6;
  private static final int MSG_APPOPEN =              7;

  private static final int EVENT_CLOSED =             1;
  private static final int EVENT_FAILED_TO_SHOW =     2;
  private static final int EVENT_OPENING =            3;
  private static final int EVENT_FAILED_TO_LOAD =     4;
  private static final int EVENT_LOADED =             5;
  private static final int EVENT_NOT_LOADED =         6;
  private static final int EVENT_EARNED_REWARD =      7;
  private static final int EVENT_COMPLETE =           8;
  private static final int EVENT_CLICKED =            9;
  private static final int EVENT_DESTROYED =          10;
  private static final int EVENT_JSON_ERROR =         11;
  private static final int EVENT_IMPRESSION_RECORDED =12;
  // 13-16 are for iOS only
  private static final int EVENT_NOT_SUPPORTED =      17;

  private static final int SIZE_ADAPTIVE_BANNER =     0;
  private static final int SIZE_BANNER =              1;
  private static final int SIZE_FLUID =               2;
  private static final int SIZE_FULL_BANNER =         3;
  private static final int SIZE_LARGE_BANNER =        4;
  private static final int SIZE_LEADEARBOARD =        5;
  private static final int SIZE_MEDIUM_RECTANGLE =    6;
  private static final int SIZE_LARGE_ADAPTIVE_BANNER = 10;

  private static final int POS_NONE =                 0;
  private static final int POS_TOP_LEFT =             1;
  private static final int POS_TOP_CENTER =           2;
  private static final int POS_TOP_RIGHT =            3;
  private static final int POS_BOTTOM_LEFT =          4;
  private static final int POS_BOTTOM_CENTER =        5;
  private static final int POS_BOTTOM_RIGHT =         6;
  private static final int POS_CENTER =               7;

  private static final int MAX_AD_CONTENT_RATING_G =  0;
  private static final int MAX_AD_CONTENT_RATING_PG = 1;
  private static final int MAX_AD_CONTENT_RATING_T =  2;
  private static final int MAX_AD_CONTENT_RATING_MA = 3;

  // END CONSTANTS

  private String defoldUserAgent = "defold-x.y.z";
  private final Activity activity;
  private final String appId;
  private final List<String> testDeviceIds = new ArrayList<String>();
  private RequestConfiguration.MaxAdContentRating maxAdContentRating =
      RequestConfiguration.MaxAdContentRating.MAX_AD_CONTENT_RATING_UNSPECIFIED;
  private boolean initializationStarted = false;
  private boolean initializationComplete = false;
  private final List<Runnable> pendingInitializationTasks = new ArrayList<Runnable>();

  public AdmobJNI(Activity activity, String appId, String appOpenAdUnitId, String defoldUserAgent, boolean testAdsInDebug) {
      this.activity = activity;
      this.appId = appId;
      this.mAppOpenAdUnitId = appOpenAdUnitId;
      this.defoldUserAgent = defoldUserAgent;

      configureTestAdsIfNeeded(testAdsInDebug);

      if (isAutomaticAppOpenEnabled()) {
        activity.runOnUiThread(new Runnable() {
          @Override
          public void run() {
            ProcessLifecycleOwner.get().getLifecycle().addObserver(AdmobJNI.this);
            loadAppOpen(appOpenAdUnitId, true);
          }
        });
      }
  }

  private void configureTestAdsIfNeeded(boolean testAdsInDebug) {
    if (!testAdsInDebug) {
      return;
    }
    String deviceId = getHashedDeviceId();
    if (deviceId != null && deviceId.length() > 0) {
      testDeviceIds.add(deviceId);
    }
    Log.d(TAG, "Test ads enabled for this device: " + (deviceId != null ? deviceId : "unknown"));
  }

  private String getHashedDeviceId() {
    String androidId = Settings.Secure.getString(activity.getContentResolver(), Settings.Secure.ANDROID_ID);
    if (androidId == null || androidId.length() == 0) {
      return null;
    }
    try {
      MessageDigest md = MessageDigest.getInstance("MD5");
      md.update(androidId.getBytes("UTF-8"));
      byte[] digest = md.digest();
      StringBuilder builder = new StringBuilder();
      for (int i = 0; i < digest.length; i++) {
        builder.append(String.format("%02X", digest[i] & 0xFF));
      }
      return builder.toString();
    } catch (Exception e) {
      Log.w(TAG, "Failed to compute test device id", e);
      return null;
    }
  }

  private synchronized RequestConfiguration buildRequestConfiguration() {
    RequestConfiguration.Builder builder = new RequestConfiguration.Builder();
    if (!testDeviceIds.isEmpty()) {
      builder.setTestDeviceIds(new ArrayList<String>(testDeviceIds));
    }
    if (maxAdContentRating != RequestConfiguration.MaxAdContentRating.MAX_AD_CONTENT_RATING_UNSPECIFIED) {
      builder.setMaxAdContentRating(maxAdContentRating);
    }
    return builder.build();
  }

  public void initialize() {
    synchronized (this) {
      if (initializationComplete) {
        sendSimpleMessage(MSG_INITIALIZATION, EVENT_COMPLETE);
        return;
      }
      if (initializationStarted) {
        return;
      }
      initializationStarted = true;
    }

    final InitializationConfig initializationConfig =
        new InitializationConfig.Builder(appId)
            .setRequestConfiguration(buildRequestConfiguration())
            .build();

    new Thread(new Runnable() {
      @Override
      public void run() {
        MobileAds.initialize(activity, initializationConfig, new OnAdapterInitializationCompleteListener() {
          @Override
          public void onAdapterInitializationComplete(InitializationStatus initializationStatus) {
            logAdapterStatus(initializationStatus);

            List<Runnable> tasks;
            synchronized (AdmobJNI.this) {
              initializationComplete = true;
              tasks = new ArrayList<Runnable>(pendingInitializationTasks);
              pendingInitializationTasks.clear();
            }

            // Pick up configuration changes made while mediation adapters were initializing.
            MobileAds.setRequestConfiguration(buildRequestConfiguration());
            sendSimpleMessage(MSG_INITIALIZATION, EVENT_COMPLETE);
            for (final Runnable task : tasks) {
              activity.runOnUiThread(task);
            }
          }
        });
      }
    }).start();
  }

  private void runWhenInitialized(Runnable task) {
    boolean runNow;
    synchronized (this) {
      runNow = initializationComplete;
      if (!runNow) {
        pendingInitializationTasks.add(task);
      }
    }

    if (runNow) {
      activity.runOnUiThread(task);
    }
  }

  private void logAdapterStatus(InitializationStatus initializationStatus) {
    Map<String, AdapterStatus> statusMap = initializationStatus.getAdapterStatusMap();
    for (Map.Entry<String, AdapterStatus> entry : statusMap.entrySet()) {
      String adapterClass = entry.getKey();
      AdapterStatus status = entry.getValue();
      Log.d(
          TAG,
          String.format(
              "Adapter name: %s, Description: %s, Latency: %d",
              adapterClass, status.getDescription(), status.getLatency()));
    }
  }

  @SuppressWarnings("deprecation")
  public void setPrivacySettings(boolean enable_rdp) {
    SharedPreferences sharedPref = PreferenceManager.getDefaultSharedPreferences(activity);
    sharedPref.edit().putInt("gad_rdp", enable_rdp ? 1 : 0).apply();
  }

  public void setMaxAdContentRating(final int max_ad_rating) {
    RequestConfiguration.MaxAdContentRating rating = null;
    switch (max_ad_rating) {
      case MAX_AD_CONTENT_RATING_G:
        rating = RequestConfiguration.MaxAdContentRating.MAX_AD_CONTENT_RATING_G;
        break;
      case MAX_AD_CONTENT_RATING_PG:
        rating = RequestConfiguration.MaxAdContentRating.MAX_AD_CONTENT_RATING_PG;
        break;
      case MAX_AD_CONTENT_RATING_T:
        rating = RequestConfiguration.MaxAdContentRating.MAX_AD_CONTENT_RATING_T;
        break;
      case MAX_AD_CONTENT_RATING_MA:
        rating = RequestConfiguration.MaxAdContentRating.MAX_AD_CONTENT_RATING_MA;
        break;
      }
    if (rating != null) {
      boolean applyImmediately;
      synchronized (this) {
        maxAdContentRating = rating;
        applyImmediately = initializationComplete;
      }
      if (applyImmediately) {
        MobileAds.setRequestConfiguration(buildRequestConfiguration());
      }
      Log.d(TAG, "setMaxAdContentRating " + rating);
    }
  }

  public void requestIDFA() {
    sendSimpleMessage(MSG_IDFA, EVENT_NOT_SUPPORTED);
  }

  public void showAdInspector() {
    runWhenInitialized(new Runnable() {
      @Override
      public void run() {
        MobileAds.openAdInspector(new OnAdInspectorClosedListener() {
          @Override
          public void onAdInspectorClosed(AdInspectorError error) {
            // Error will be non-null if ad inspector closed due to an error.
            if (error != null) {
              Log.d(
                  TAG,
                  String.format(
                      "Ad Inspector closed with error %s (%d): %s",
                      error.getCode(), error.getCode().getValue(), error.getMessage()));
            }
          }
        });
      }
    });
  }

  // https://www.baeldung.com/java-json-escaping
  private String getJsonConversionErrorMessage(String messageText) {
    String message = null;
      try {
          JSONObject obj = new JSONObject();
          obj.put("error", messageText);
          obj.put("event", EVENT_JSON_ERROR);
          message = obj.toString();
      } catch (JSONException e) {
          message = "{ \"error\": \"Error while converting simple message to JSON.\", \"event\": "+EVENT_JSON_ERROR+" }";
      }
    return message;
  }

  private void sendSimpleMessage(int msg, int eventId) {
      String message = null;
      try {
          JSONObject obj = new JSONObject();
          obj.put("event", eventId);
          message = obj.toString();
      } catch (JSONException e) {
          message = getJsonConversionErrorMessage(e.getLocalizedMessage());
      }
      admobAddToQueue(msg, message);
  }

  private void sendSimpleMessage(int msg, int eventId, String key_2, String value_2) {
      String message = null;
      try {
          JSONObject obj = new JSONObject();
          obj.put("event", eventId);
          obj.put(key_2, value_2);
          message = obj.toString();
      } catch (JSONException e) {
          message = getJsonConversionErrorMessage(e.getLocalizedMessage());
      }
      admobAddToQueue(msg, message);
    }

  private void sendSimpleMessage(int msg, int eventId, String key_2, int value_2, String key_3, String value_3) {
      String message = null;
      try {
          JSONObject obj = new JSONObject();
          obj.put("event", eventId);
          obj.put(key_2, value_2);
          obj.put(key_3, value_3);
          message = obj.toString();
      } catch (JSONException e) {
          message = getJsonConversionErrorMessage(e.getLocalizedMessage());
      }
      admobAddToQueue(msg, message);
    }

  private void sendSimpleMessage(int msg, int eventId, String key_2, int value_2, String key_3, int value_3) {
    String message = null;
    try {
        JSONObject obj = new JSONObject();
        obj.put("event", eventId);
        obj.put(key_2, value_2);
        obj.put(key_3, value_3);
        message = obj.toString();
    } catch (JSONException e) {
        message = getJsonConversionErrorMessage(e.getLocalizedMessage());
    }
    admobAddToQueue(msg, message);
  }

  private void sendLoadError(int msg, int eventId, LoadAdError error) {
    sendSimpleMessage(
        msg,
        eventId,
        "code",
        error.getCode().getValue(),
        "error",
        String.format("Error code: \"%s\". %s", error.getCode(), error.getMessage()));
  }

  private void sendShowError(int msg, FullScreenContentError error) {
    sendSimpleMessage(
        msg,
        EVENT_FAILED_TO_SHOW,
        "code",
        error.getCode().getValue(),
        "error",
        String.format("Error code: \"%s\". %s", error.getCode(), error.getMessage()));
  }

  private AdRequest createAdRequest(String unitId) {
    return new AdRequest.Builder(unitId).setRequestAgent(defoldUserAgent).build();
  }

//--------------------------------------------------
// App Open Ads

  private String mAppOpenAdUnitId = null;
  private volatile AppOpenAd mAppOpenAd = null;
  private boolean mIsLoadingAppOpenAd = false;
  private boolean mIsShowingAppOpenAd = false;

  @OnLifecycleEvent(Event.ON_START)
  protected void onMoveToForeground() {
    Log.d(TAG, "onMoveToForeground");
    showAppOpen();
  }

  private boolean isAutomaticAppOpenEnabled() {
    return mAppOpenAdUnitId != null && mAppOpenAdUnitId.length() > 0;
  }

  public boolean isAppOpenLoaded() {
    return mAppOpenAd != null;
  }

  public void showAppOpen() {
    runWhenInitialized(new Runnable() {
      @Override
      public void run() {
        showAppOpenInternal();
      }
    });
  }

  private void showAppOpenInternal() {
    // If the app open ad is already showing, do not show the ad again
    if (mIsShowingAppOpenAd) {
      Log.d(TAG, "The app open ad is already showing.");
      return;
    }

    // If the app open ad is not available yet then load it
    if (!isAppOpenLoaded() && isAutomaticAppOpenEnabled()) {
      Log.d(TAG, "The app open ad is not ready yet.");
      loadAppOpenInternal(mAppOpenAdUnitId, true);
      return;
    }

    if (!isAppOpenLoaded()) {
      sendSimpleMessage(MSG_APPOPEN, EVENT_NOT_LOADED, "error", "Can't show App Open AD that wasn't loaded.");
      return;
    }

    Log.d(TAG, "Showing app open ad.");
    mIsShowingAppOpenAd = true;
    mAppOpenAd.setAdEventCallback(
        new AppOpenAdEventCallback() {

          @Override
          public void onAdDismissedFullScreenContent() {
            activity.runOnUiThread(new Runnable() {
              @Override
              public void run() {
                // Called when fullscreen content is dismissed.
                // Clean up state and load a new ad
                Log.d(TAG, "Ad dismissed fullscreen content.");
                sendSimpleMessage(MSG_APPOPEN, EVENT_CLOSED);
                mAppOpenAd = null;
                mIsShowingAppOpenAd = false;
                if (isAutomaticAppOpenEnabled()) {
                  loadAppOpen(mAppOpenAdUnitId, false);
                }
              }
            });
          }

          @Override
          public void onAdFailedToShowFullScreenContent(final FullScreenContentError error) {
            activity.runOnUiThread(new Runnable() {
              @Override
              public void run() {
                // Called when fullscreen content failed to show.
                // Clean up state and load a new ad
                Log.d(TAG, error.getMessage());
                sendShowError(MSG_APPOPEN, error);
                mAppOpenAd = null;
                mIsShowingAppOpenAd = false;
                if (isAutomaticAppOpenEnabled()) {
                  loadAppOpen(mAppOpenAdUnitId, false);
                }
              }
            });
          }

          @Override
          public void onAdShowedFullScreenContent() {
            activity.runOnUiThread(new Runnable() {
              @Override
              public void run() {
                // Called when fullscreen content is shown.
                Log.d(TAG, "Ad showed fullscreen content.");
                sendSimpleMessage(MSG_APPOPEN, EVENT_OPENING);
              }
            });
          }

          @Override
          public void onAdImpression() {
            activity.runOnUiThread(new Runnable() {
              @Override
              public void run() {
                sendSimpleMessage(MSG_APPOPEN, EVENT_IMPRESSION_RECORDED);
              }
            });
          }

          @Override
          public void onAdClicked() {
            activity.runOnUiThread(new Runnable() {
              @Override
              public void run() {
                sendSimpleMessage(MSG_APPOPEN, EVENT_CLICKED);
              }
            });
          }
        });
    mAppOpenAd.show(activity);
  }

  // Load an app open ad with the provided ad unit id, optionally also showing it
  // immediately when it loaded
  public void loadAppOpen(final String adUnitId, final boolean showImmediately) {
    runWhenInitialized(new Runnable() {
      @Override
      public void run() {
        loadAppOpenInternal(adUnitId, showImmediately);
      }
    });
  }

  private void loadAppOpenInternal(final String adUnitId, final boolean showImmediately) {
    // Do not load ad if one is already loading.
    if (mIsLoadingAppOpenAd) {
      Log.d(TAG, "Already loading app open ad.");
      return;
    }

    // Do not load ad if there is an unused ad.
    if (isAppOpenLoaded()) {
      Log.d(TAG, "App open ad was already loaded.");
      sendSimpleMessage(MSG_APPOPEN, EVENT_LOADED);
      if (showImmediately) {
        showAppOpen();
      }
      return;
    }

    Log.d(TAG, "Loading app open ad.");
    mIsLoadingAppOpenAd = true;
    AppOpenAd.load(
      createAdRequest(adUnitId),
      new AdLoadCallback<AppOpenAd>() {
        @Override
        public void onAdLoaded(final AppOpenAd ad) {
          activity.runOnUiThread(new Runnable() {
            @Override
            public void run() {
              // Called when an app open ad has loaded.
              Log.d(TAG, "Ad was loaded.");
              sendSimpleMessage(MSG_APPOPEN, EVENT_LOADED);
              mAppOpenAd = ad;
              mIsLoadingAppOpenAd = false;
              if (showImmediately) {
                showAppOpenInternal();
              }
            }
          });
        }

        @Override
        public void onAdFailedToLoad(final LoadAdError loadAdError) {
          activity.runOnUiThread(new Runnable() {
            @Override
            public void run() {
              // Called when an app open ad has failed to load.
              Log.d(TAG, loadAdError.getMessage());
              sendLoadError(MSG_APPOPEN, EVENT_FAILED_TO_LOAD, loadAdError);
              mIsLoadingAppOpenAd = false;
            }
          });
        }
      });
  }

  // Load an app open ad with the provided ad unit id but don't show it
  public void loadAppOpen(String adUnitId) {
    loadAppOpen(adUnitId, false);
  }

//--------------------------------------------------
// Interstitial ADS

  private volatile InterstitialAd mInterstitialAd;

  public void loadInterstitial(final String unitId) {
    runWhenInitialized(new Runnable() {
      @Override
      public void run() {
        InterstitialAd.load(createAdRequest(unitId), new AdLoadCallback<InterstitialAd>() {
          @Override
          public void onAdLoaded(@NonNull final InterstitialAd interstitialAd) {
            activity.runOnUiThread(new Runnable() {
              @Override
              public void run() {
                mInterstitialAd = interstitialAd;
                sendSimpleMessage(MSG_INTERSTITIAL, EVENT_LOADED);
                interstitialAd.setAdEventCallback(new InterstitialAdEventCallback() {
                  @Override
                  public void onAdDismissedFullScreenContent() {
                    activity.runOnUiThread(new Runnable() {
                      @Override
                      public void run() {
                        sendSimpleMessage(MSG_INTERSTITIAL, EVENT_CLOSED);
                      }
                    });
                  }

                  @Override
                  public void onAdFailedToShowFullScreenContent(final FullScreenContentError error) {
                    activity.runOnUiThread(new Runnable() {
                      @Override
                      public void run() {
                        sendShowError(MSG_INTERSTITIAL, error);
                      }
                    });
                  }

                  @Override
                  public void onAdShowedFullScreenContent() {
                    activity.runOnUiThread(new Runnable() {
                      @Override
                      public void run() {
                        sendSimpleMessage(MSG_INTERSTITIAL, EVENT_OPENING);
                      }
                    });
                  }

                  @Override
                  public void onAdImpression() {
                    sendSimpleMessage(MSG_INTERSTITIAL, EVENT_IMPRESSION_RECORDED);
                  }

                  @Override
                  public void onAdClicked() {
                    sendSimpleMessage(MSG_INTERSTITIAL, EVENT_CLICKED);
                  }
                });
              }
            });
          }

          @Override
          public void onAdFailedToLoad(@NonNull final LoadAdError loadAdError) {
            activity.runOnUiThread(new Runnable() {
              @Override
              public void run() {
                sendLoadError(MSG_INTERSTITIAL, EVENT_FAILED_TO_LOAD, loadAdError);
              }
            });
          }
        });
      }
    });
  }

  public void showInterstitial() {
    activity.runOnUiThread(new Runnable() {
        @Override
        public void run() {
            if (isInterstitialLoaded()) {
              InterstitialAd interstitialAd = mInterstitialAd;
              mInterstitialAd = null;
              interstitialAd.show(activity);
            } else {
              // Log.d(TAG, "The interstitial ad wasn't ready yet.");
              sendSimpleMessage(MSG_INTERSTITIAL, EVENT_NOT_LOADED, "error", "Can't show Interstitial AD that wasn't loaded.");
            }
        }
    });
  }

  public boolean isInterstitialLoaded() {
    return mInterstitialAd != null;
  }

//--------------------------------------------------
// Rewarded ADS

  private volatile RewardedAd mRewardedAd;

  private void setRewardedCustomData(RewardedAd rewardedAd, final String userId, final String customData) {
    ServerSideVerificationOptions options = new ServerSideVerificationOptions(
        userId != null ? userId : "",
        customData != null ? customData : "");
    rewardedAd.setServerSideVerificationOptions(options);
  }

  public void loadRewarded(final String unitId, final String userId,  final String customData) {
    runWhenInitialized(new Runnable() {
      @Override
      public void run() {
        RewardedAd.load(createAdRequest(unitId), new AdLoadCallback<RewardedAd>() {
          @Override
          public void onAdLoaded(@NonNull final RewardedAd rewardedAd) {
            activity.runOnUiThread(new Runnable() {
              @Override
              public void run() {
                mRewardedAd = rewardedAd;
                setRewardedCustomData(rewardedAd, userId, customData);
                sendSimpleMessage(MSG_REWARDED, EVENT_LOADED);
                rewardedAd.setAdEventCallback(new RewardedAdEventCallback() {
                  @Override
                  public void onAdDismissedFullScreenContent() {
                    activity.runOnUiThread(new Runnable() {
                      @Override
                      public void run() {
                        sendSimpleMessage(MSG_REWARDED, EVENT_CLOSED);
                      }
                    });
                  }

                  @Override
                  public void onAdFailedToShowFullScreenContent(final FullScreenContentError error) {
                    activity.runOnUiThread(new Runnable() {
                      @Override
                      public void run() {
                        sendShowError(MSG_REWARDED, error);
                      }
                    });
                  }

                  @Override
                  public void onAdShowedFullScreenContent() {
                    activity.runOnUiThread(new Runnable() {
                      @Override
                      public void run() {
                        sendSimpleMessage(MSG_REWARDED, EVENT_OPENING);
                      }
                    });
                  }

                  @Override
                  public void onAdImpression() {
                    sendSimpleMessage(MSG_REWARDED, EVENT_IMPRESSION_RECORDED);
                  }

                  @Override
                  public void onAdClicked() {
                    sendSimpleMessage(MSG_REWARDED, EVENT_CLICKED);
                  }
                });
              }
            });
          }

          @Override
          public void onAdFailedToLoad(@NonNull final LoadAdError loadAdError) {
            activity.runOnUiThread(new Runnable() {
              @Override
              public void run() {
                sendLoadError(MSG_REWARDED, EVENT_FAILED_TO_LOAD, loadAdError);
              }
            });
          }
        });
      }
    });
  }

  public void showRewarded() {
    activity.runOnUiThread(new Runnable() {
        @Override
        public void run() {
          if (isRewardedLoaded()) {
            RewardedAd rewardedAd = mRewardedAd;
            mRewardedAd = null;
            rewardedAd.show(activity, new OnUserEarnedRewardListener() {
              @Override
              public void onUserEarnedReward(@NonNull RewardItem rewardItem) {
                // Handle the reward.
                // Log.d(TAG, "The user earned the reward.");
                int rewardAmount = rewardItem.getAmount();
                String rewardType = rewardItem.getType();
                sendSimpleMessage(MSG_REWARDED, EVENT_EARNED_REWARD, "amount", rewardAmount, "type", rewardType);
              }
            });
          } else {
            // Log.d(TAG, "The rewarded ad wasn't ready yet.");
            sendSimpleMessage(MSG_REWARDED, EVENT_NOT_LOADED, "error", "Can't show Rewarded AD that wasn't loaded.");
          }
        }
    });
  }

  public boolean isRewardedLoaded() {
    return mRewardedAd != null;
  }

//--------------------------------------------------
// Rewarded Interstitial ADS

  private volatile RewardedInterstitialAd mRewardedInterstitialAd;

  public void loadRewardedInterstitial(final String unitId) {
    runWhenInitialized(new Runnable() {
      @Override
      public void run() {
        RewardedInterstitialAd.load(
            createAdRequest(unitId),
            new AdLoadCallback<RewardedInterstitialAd>() {
          @Override
          public void onAdLoaded(@NonNull final RewardedInterstitialAd rewardedAd) {
            activity.runOnUiThread(new Runnable() {
              @Override
              public void run() {
                mRewardedInterstitialAd = rewardedAd;
                sendSimpleMessage(MSG_REWARDED_INTERSTITIAL, EVENT_LOADED);
                rewardedAd.setAdEventCallback(new RewardedInterstitialAdEventCallback() {
                  @Override
                  public void onAdDismissedFullScreenContent() {
                    activity.runOnUiThread(new Runnable() {
                      @Override
                      public void run() {
                        sendSimpleMessage(MSG_REWARDED_INTERSTITIAL, EVENT_CLOSED);
                      }
                    });
                  }

                  @Override
                  public void onAdFailedToShowFullScreenContent(final FullScreenContentError error) {
                    activity.runOnUiThread(new Runnable() {
                      @Override
                      public void run() {
                        sendShowError(MSG_REWARDED_INTERSTITIAL, error);
                      }
                    });
                  }

                  @Override
                  public void onAdShowedFullScreenContent() {
                    activity.runOnUiThread(new Runnable() {
                      @Override
                      public void run() {
                        sendSimpleMessage(MSG_REWARDED_INTERSTITIAL, EVENT_OPENING);
                      }
                    });
                  }

                  @Override
                  public void onAdImpression() {
                    sendSimpleMessage(MSG_REWARDED_INTERSTITIAL, EVENT_IMPRESSION_RECORDED);
                  }

                  @Override
                  public void onAdClicked() {
                    sendSimpleMessage(MSG_REWARDED_INTERSTITIAL, EVENT_CLICKED);
                  }
                });
              }
            });
          }

          @Override
          public void onAdFailedToLoad(@NonNull final LoadAdError loadAdError) {
            activity.runOnUiThread(new Runnable() {
              @Override
              public void run() {
                sendLoadError(MSG_REWARDED_INTERSTITIAL, EVENT_FAILED_TO_LOAD, loadAdError);
              }
            });
          }
        });
      }
    });
  }

  public void showRewardedInterstitial() {
    activity.runOnUiThread(new Runnable() {
        @Override
        public void run() {
          if (isRewardedInterstitialLoaded()) {
            RewardedInterstitialAd rewardedInterstitialAd = mRewardedInterstitialAd;
            mRewardedInterstitialAd = null;
            rewardedInterstitialAd.show(activity, new OnUserEarnedRewardListener() {
              @Override
              public void onUserEarnedReward(@NonNull RewardItem rewardItem) {
                // Handle the reward.
                // Log.d(TAG, "The user earned the reward.");
                int rewardAmount = rewardItem.getAmount();
                String rewardType = rewardItem.getType();
                sendSimpleMessage(MSG_REWARDED_INTERSTITIAL, EVENT_EARNED_REWARD, "amount", rewardAmount, "type", rewardType);
              }
            });
          } else {
            // Log.d(TAG, "The rewarded ad wasn't ready yet.");
            sendSimpleMessage(MSG_REWARDED_INTERSTITIAL, EVENT_NOT_LOADED, "error", "Can't show Rewarded Interstitial AD that wasn't loaded.");
          }
        }
    });
  }

  public boolean isRewardedInterstitialLoaded() {
    return mRewardedInterstitialAd != null;
  }

//--------------------------------------------------
// Banner ADS

  private LinearLayout layout;
  private volatile AdView bannerAdView;
  private WindowManager windowManager;
  private boolean isBannerLoading = false;
  private boolean isBannerShown = false;
  private int bannerPosition = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
  private int bannerSizeConst = SIZE_ADAPTIVE_BANNER;

  public void loadBanner(final String unitId, final int bannerSize) {
    runWhenInitialized(new Runnable() {
      @Override
      public void run() {
        if (isBannerLoaded() || isBannerLoading) {
          return;
        }

        isBannerLoading = true;
        bannerSizeConst = bannerSize;
        final AdView view = new AdView(activity);
        final AdSize adSize = getSizeConstant(bannerSize);
        final int bannerWidth = adSize.getWidthInPixels(activity);
        final int bannerHeight = adSize.getHeightInPixels(activity);
        BannerAdRequest request = new BannerAdRequest.Builder(unitId, adSize)
            .setRequestAgent(defoldUserAgent)
            .build();

        view.loadAd(request, new AdLoadCallback<BannerAd>() {
          @Override
          public void onAdLoaded(@NonNull final BannerAd bannerAd) {
            bannerAd.setAdEventCallback(new BannerAdEventCallback() {
              @Override
              public void onAdShowedFullScreenContent() {
                sendSimpleMessage(MSG_BANNER, EVENT_OPENING);
              }

              @Override
              public void onAdDismissedFullScreenContent() {
                sendSimpleMessage(MSG_BANNER, EVENT_CLOSED);
              }

              @Override
              public void onAdFailedToShowFullScreenContent(FullScreenContentError error) {
                sendShowError(MSG_BANNER, error);
              }

              @Override
              public void onAdClicked() {
                sendSimpleMessage(MSG_BANNER, EVENT_CLICKED);
              }

              @Override
              public void onAdImpression() {
                sendSimpleMessage(MSG_BANNER, EVENT_IMPRESSION_RECORDED);
              }
            });
            bannerAd.setBannerAdRefreshCallback(new BannerAdRefreshCallback() {
              @Override
              public void onAdRefreshed() {
                sendSimpleMessage(MSG_BANNER, EVENT_LOADED, "height", bannerHeight, "width", bannerWidth);
              }

              @Override
              public void onAdFailedToRefresh(LoadAdError error) {
                sendLoadError(MSG_BANNER, EVENT_FAILED_TO_LOAD, error);
              }
            });

            activity.runOnUiThread(new Runnable() {
              @Override
              public void run() {
                isBannerLoading = false;
                bannerAdView = view;
                createLayout();
                sendSimpleMessage(MSG_BANNER, EVENT_LOADED, "height", bannerHeight, "width", bannerWidth);
              }
            });
          }

          @Override
          public void onAdFailedToLoad(@NonNull final LoadAdError loadAdError) {
            activity.runOnUiThread(new Runnable() {
              @Override
              public void run() {
                isBannerLoading = false;
                view.destroy();
                sendLoadError(MSG_BANNER, EVENT_FAILED_TO_LOAD, loadAdError);
              }
            });
          }
        });
      }
    });
  }

  public void destroyBanner() {
    activity.runOnUiThread(new Runnable() {
        @Override
        public void run() {
          if (!isBannerLoaded()) {
            return;
          }
          if (isBannerShown) {
            windowManager.removeView(layout);
          }
          bannerAdView.destroy();
          layout = null;
          bannerAdView = null;
          isBannerShown = false;
          sendSimpleMessage(MSG_BANNER, EVENT_DESTROYED);
        }
      });
  }

  public void showBanner(final int pos) {
    activity.runOnUiThread(new Runnable() {
        @Override
        public void run() {
          if (!isBannerLoaded()) {
            return;
          }
          layout.setSystemUiVisibility(activity.getWindow().getDecorView().getSystemUiVisibility());
          int gravity = normalizeAdaptiveGravity(getGravity(pos));
          if (gravity != Gravity.NO_GRAVITY) {
            bannerPosition = gravity;
          }
          layout.setGravity(bannerPosition);
          if (isBannerShown) {
            windowManager.updateViewLayout(layout, getParameters());
            return;
          }
          if (!layout.isShown())
          {
            windowManager.addView(layout, getParameters());
            isBannerShown = true;
          }
        }
    });
  }

  public void hideBanner() {
    activity.runOnUiThread(new Runnable() {
        @Override
        public void run() {
          if (!isBannerLoaded() || !isBannerShown) {
            return;
          }
          isBannerShown = false;
          windowManager.removeView(layout);
        }
    });
  }

  public boolean isBannerLoaded() {
    return bannerAdView != null;
  }

  public void updateBannerLayout() {
    activity.runOnUiThread(new Runnable() {
        @Override
        public void run() {
          if (!isBannerLoaded()) {
            return;
          }
          layout.setSystemUiVisibility(activity.getWindow().getDecorView().getSystemUiVisibility());
          layout.setGravity(bannerPosition);
          if (!isBannerShown) {
            return;
          }
          if (layout.isShown()) {
            windowManager.updateViewLayout(layout, getParameters());
          } else {
            windowManager.addView(layout, getParameters());
          }
        }
    });
  }

  private int getGravity(int bannerPosConst) {
    int bannerPos = Gravity.NO_GRAVITY; //POS_NONE
    switch (bannerPosConst) {
      case POS_TOP_LEFT:
        bannerPos = Gravity.TOP | Gravity.START;
        break;
      case POS_TOP_CENTER:
        bannerPos = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        break;
      case POS_TOP_RIGHT:
        bannerPos = Gravity.TOP | Gravity.END;
        break;
      case POS_BOTTOM_LEFT:
        bannerPos = Gravity.BOTTOM | Gravity.START;
        break;
      case POS_BOTTOM_CENTER:
        bannerPos = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        break;
      case POS_BOTTOM_RIGHT:
        bannerPos = Gravity.BOTTOM | Gravity.END;
        break;
      case POS_CENTER:
        bannerPos = Gravity.CENTER;
        break;
      }
    return bannerPos;
  }

  private int normalizeAdaptiveGravity(int gravity) {
    if (gravity == Gravity.NO_GRAVITY) {
      return gravity;
    }
    if (bannerSizeConst != SIZE_ADAPTIVE_BANNER && bannerSizeConst != SIZE_LARGE_ADAPTIVE_BANNER) {
      return gravity;
    }
    int vertical = gravity & Gravity.VERTICAL_GRAVITY_MASK;
    int horizontal = gravity & Gravity.HORIZONTAL_GRAVITY_MASK;
    if (horizontal == Gravity.START || horizontal == Gravity.END || horizontal == Gravity.LEFT || horizontal == Gravity.RIGHT) {
      gravity = vertical | Gravity.CENTER_HORIZONTAL;
    }
    return gravity;
  }

  private AdSize getSizeConstant(int bannerSizeConst) {
    AdSize bannerSize = getAdaptiveSize(); // SIZE_ADAPTIVE_BANNER
    switch (bannerSizeConst) {
      case SIZE_LARGE_ADAPTIVE_BANNER:
        bannerSize = getLargeAdaptiveSize();
        break;
      case SIZE_BANNER:
        bannerSize = AdSize.BANNER;
        break;
      case SIZE_FLUID:
        bannerSize = AdSize.FLUID;
        break;
      case SIZE_FULL_BANNER:
        bannerSize = AdSize.FULL_BANNER;
        break;
      case SIZE_LARGE_BANNER:
        bannerSize = AdSize.LARGE_BANNER;
        break;
      case SIZE_LEADEARBOARD:
        bannerSize = AdSize.LEADERBOARD;
        break;
      case SIZE_MEDIUM_RECTANGLE:
        bannerSize = AdSize.MEDIUM_RECTANGLE;
        break;
      }
    return bannerSize;
  }

  private int getAdaptiveWidthDp(DisplayMetrics outMetrics) {
    // Determine visible width to avoid requesting banners wider than the viewport.
    Rect visibleFrame = new Rect();
    activity.getWindow().getDecorView().getWindowVisibleDisplayFrame(visibleFrame);

    int visibleWidth = visibleFrame.width() > 0 ? visibleFrame.width() : outMetrics.widthPixels;
    float adWidthPixels = layout != null ? layout.getWidth() : 0;
    if (adWidthPixels <= 0 || adWidthPixels > visibleWidth) {
      adWidthPixels = visibleWidth;
    }
    return Math.max(1, (int) (adWidthPixels / outMetrics.density));
  }

  private AdSize getAdaptiveSize() {
    Display display = activity.getWindowManager().getDefaultDisplay();
    DisplayMetrics outMetrics = new DisplayMetrics();
    display.getMetrics(outMetrics);

    int adWidth = getAdaptiveWidthDp(outMetrics);
    return AdSize.getCurrentOrientationAnchoredAdaptiveBannerAdSize(activity, adWidth);
  }

  private AdSize getLargeAdaptiveSize() {
    Display display = activity.getWindowManager().getDefaultDisplay();
    DisplayMetrics outMetrics = new DisplayMetrics();
    display.getMetrics(outMetrics);

    Rect visibleFrame = new Rect();
    activity.getWindow().getDecorView().getWindowVisibleDisplayFrame(visibleFrame);
    int visibleWidth = visibleFrame.width() > 0 ? visibleFrame.width() : outMetrics.widthPixels;
    int visibleHeight = visibleFrame.height() > 0 ? visibleFrame.height() : outMetrics.heightPixels;
    int adWidth = getAdaptiveWidthDp(outMetrics);

    if (visibleWidth > visibleHeight) {
      return AdSize.getLargeLandscapeAnchoredAdaptiveBannerAdSize(activity, adWidth);
    }
    return AdSize.getLargePortraitAnchoredAdaptiveBannerAdSize(activity, adWidth);
  }

  private void createLayout() {
    windowManager = activity.getWindowManager();
    layout = new LinearLayout(activity);
    layout.setOrientation(LinearLayout.VERTICAL);
    layout.setGravity(bannerPosition);

    MarginLayoutParams params = new MarginLayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    params.setMargins(0, 0, 0, 0);
    layout.setSystemUiVisibility(activity.getWindow().getDecorView().getSystemUiVisibility());

    layout.addView(bannerAdView, params);
  }

  private WindowManager.LayoutParams getParameters() {
    WindowManager.LayoutParams windowParams = new WindowManager.LayoutParams();
    windowParams.x = 0;
    windowParams.y = 0;
    windowParams.width = WindowManager.LayoutParams.MATCH_PARENT;
    windowParams.height = WindowManager.LayoutParams.WRAP_CONTENT;
    windowParams.flags = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
    windowParams.gravity = (bannerPosition & Gravity.VERTICAL_GRAVITY_MASK) == 0
        ? Gravity.BOTTOM
        : (bannerPosition & Gravity.VERTICAL_GRAVITY_MASK);
    return windowParams;
  }

}
