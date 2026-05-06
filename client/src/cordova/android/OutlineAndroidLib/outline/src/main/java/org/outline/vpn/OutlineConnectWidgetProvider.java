// Copyright 2026 The Outline Authors
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package org.outline.vpn;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.VpnService;
import android.os.Build;
import android.widget.RemoteViews;

import java.util.Locale;
import java.util.logging.Logger;

import org.json.JSONObject;

/** Home screen widget with direct actions for the most recently used Outline tunnel. */
public class OutlineConnectWidgetProvider extends AppWidgetProvider {
  private static final Logger LOG = Logger.getLogger(OutlineConnectWidgetProvider.class.getName());
  static final String CONNECT_ACTION = "org.outline.vpn.widget.CONNECT";
  static final String OPEN_APP_ACTION = "org.outline.vpn.widget.OPEN_APP";

  @Override
  public void onReceive(Context context, Intent intent) {
    super.onReceive(context, intent);
    final String action = intent.getAction();
    if (VpnTunnelService.STATUS_BROADCAST_KEY.equals(action)) {
      // The VPN service runs in a separate :vpn process. To avoid reading a stale
      // SharedPreferences cache in the main process, we extract the status directly
      // from the broadcast intent and sync it to the main process's SharedPreferences.
      int statusValue = intent.getIntExtra(
          VpnTunnelService.MessageData.PAYLOAD.value,
          VpnTunnelService.TunnelStatus.DISCONNECTED.value);
      VpnTunnelService.TunnelStatus status = statusFromValue(statusValue);
      new VpnTunnelStore(context).setTunnelStatus(status);
      updateAllWidgets(context);
    }
  }

  /**
   * Converts a TunnelStatus integer value to its enum equivalent.
   * Falls back to DISCONNECTED if the value is unrecognized.
   */
  private static VpnTunnelService.TunnelStatus statusFromValue(int value) {
    for (VpnTunnelService.TunnelStatus s : VpnTunnelService.TunnelStatus.values()) {
      if (s.value == value) {
        return s;
      }
    }
    return VpnTunnelService.TunnelStatus.DISCONNECTED;
  }

  @Override
  public void onUpdate(Context context, AppWidgetManager appWidgetManager, int[] appWidgetIds) {
    for (int appWidgetId : appWidgetIds) {
      updateWidget(context, appWidgetManager, appWidgetId);
    }
  }

  static void connectLastTunnel(Context context) {
    VpnTunnelStore tunnelStore = new VpnTunnelStore(context);
    if (VpnService.prepare(context) != null) {
      tunnelStore.setTunnelStatus(VpnTunnelService.TunnelStatus.DISCONNECTED);
      updateAllWidgets(context);
      openApp(context);
      return;
    }

    tunnelStore.setTunnelStatus(VpnTunnelService.TunnelStatus.RECONNECTING);
    updateAllWidgets(context);

    Intent serviceIntent = new Intent(context, VpnTunnelService.class);
    serviceIntent.putExtra(VpnServiceStarter.AUTOSTART_EXTRA, true);
    startForegroundService(context, serviceIntent);
  }

  static void disconnectTunnel(Context context) {
    new VpnTunnelStore(context).setTunnelStatus(VpnTunnelService.TunnelStatus.DISCONNECTED);
    updateAllWidgets(context);

    Intent serviceIntent = new Intent(context, VpnTunnelService.class);
    serviceIntent.setAction(VpnTunnelService.DISCONNECT_ACTION);
    try {
      context.startService(serviceIntent);
    } catch (IllegalStateException e) {
      LOG.warning("Failed to disconnect from widget while service is background restricted");
      openApp(context);
    }
  }

  public static void updateAllWidgets(Context context) {
    AppWidgetManager manager = AppWidgetManager.getInstance(context);
    ComponentName widget = new ComponentName(context, OutlineConnectWidgetProvider.class);
    int[] appWidgetIds = manager.getAppWidgetIds(widget);
    for (int appWidgetId : appWidgetIds) {
      updateWidget(context, manager, appWidgetId);
    }
  }

  private static void updateWidget(
      Context context, AppWidgetManager manager, int appWidgetId) {
    RemoteViews views = new RemoteViews(context.getPackageName(), getResourceId(context, "outline_connect_widget", "layout"));
    VpnTunnelStore tunnelStore = new VpnTunnelStore(context);
    VpnTunnelService.TunnelStatus status = getTunnelStatus(tunnelStore);
    boolean isConnected = status == VpnTunnelService.TunnelStatus.CONNECTED;
    boolean isChanging = status == VpnTunnelService.TunnelStatus.RECONNECTING;
    // Determine the button action based on tunnel status alone.
    // We avoid calling VpnService.prepare() here because it may not work correctly
    // from a BroadcastReceiver context (non-Activity). We also avoid loading the saved
    // tunnel config because SharedPreferences may be stale across processes (the VPN
    // service runs in the :vpn process while the widget runs in the main process).
    // The actual connect/disconnect handlers have proper fallback to open the app
    // when VPN permission is missing or no saved tunnel exists.
    String buttonAction = getButtonAction(isConnected, isChanging);
    String buttonTextResource = "outline_widget_connect";
    String buttonBackgroundResource = "outline_widget_button";
    String statusDotResource = "outline_widget_status_disconnected";
    if (isConnected) {
      buttonTextResource = "outline_widget_disconnect";
      buttonBackgroundResource = "outline_widget_button_disconnect";
      statusDotResource = "outline_widget_status_connected";
    } else if (isChanging) {
      buttonTextResource = "outline_widget_connecting";
      buttonBackgroundResource = "outline_widget_button_connecting";
      statusDotResource = "outline_widget_status_connecting";
    }

    views.setTextViewText(getResourceId(context, "outline_widget_title", "id"), context.getString(getResourceId(context, "outline_widget_name", "string")));
    views.setTextViewText(getResourceId(context, "outline_widget_server", "id"), getServerName(context, tunnelStore));
    views.setTextViewText(getResourceId(context, "outline_widget_status", "id"), getStatusText(context, status, isValidSavedTunnel(tunnelStore.load())));
    views.setTextViewText(getResourceId(context, "outline_widget_toggle", "id"), context.getString(getResourceId(context, buttonTextResource, "string")));
    views.setInt(getResourceId(context, "outline_widget_status_dot", "id"), "setBackgroundResource", getResourceId(context, statusDotResource, "drawable"));
    views.setInt(getResourceId(context, "outline_widget_toggle", "id"), "setBackgroundResource", getResourceId(context, buttonBackgroundResource, "drawable"));
    views.setOnClickPendingIntent(getResourceId(context, "outline_widget_toggle", "id"), pendingIntent(context, buttonAction, 1));
    manager.updateAppWidget(appWidgetId, views);
  }

  /**
   * Determines the button action based on tunnel status alone.
   * Connected/Reconnecting -> Disconnect; Disconnected -> Connect.
   * The connect handler has fallback to open the app if VPN is not prepared or no saved tunnel.
   */
  private static String getButtonAction(boolean isConnected, boolean isChanging) {
    return isConnected || isChanging ? VpnTunnelService.DISCONNECT_ACTION : CONNECT_ACTION;
  }

  private static String getServerName(Context context, VpnTunnelStore tunnelStore) {
    JSONObject tunnel = tunnelStore.load();
    if (!isValidSavedTunnel(tunnel)) {
      return context.getString(getResourceId(context, "outline_widget_no_server", "string"));
    }
    return tunnel.optString("serverName", context.getString(getResourceId(context, "server_default_name_outline", "string")));
  }

  private static String getStatusText(
      Context context, VpnTunnelService.TunnelStatus status, boolean hasSavedTunnel) {
    if (!hasSavedTunnel) {
      return context.getString(getResourceId(context, "outline_widget_open_app_hint", "string"));
    }
    switch (status) {
      case CONNECTED:
        return context.getString(getResourceId(context, "connected_server_state", "string"));
      case RECONNECTING:
        return context.getString(getResourceId(context, "outline_widget_connecting", "string"));
      case DISCONNECTED:
      default:
        return context.getString(getResourceId(context, "outline_widget_disconnected", "string"));
    }
  }

  private static PendingIntent pendingIntent(Context context, String action, int requestCode) {
    Intent intent = new Intent(context, OutlineConnectWidgetActionReceiver.class);
    intent.setAction(action);
    int flags = PendingIntent.FLAG_UPDATE_CURRENT;
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
      flags |= PendingIntent.FLAG_IMMUTABLE;
    }
    return PendingIntent.getBroadcast(context, requestCode, intent, flags);
  }

  private static void startForegroundService(Context context, Intent intent) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      context.startForegroundService(intent);
    } else {
      context.startService(intent);
    }
  }

  private static boolean isValidSavedTunnel(JSONObject tunnel) {
    return tunnel != null
        && tunnel.optString("id", "").length() > 0
        && tunnel.optString("config", "").length() > 0;
  }

  private static VpnTunnelService.TunnelStatus getTunnelStatus(VpnTunnelStore tunnelStore) {
    try {
      return tunnelStore.getTunnelStatus();
    } catch (IllegalArgumentException e) {
      LOG.warning("Stored tunnel status is invalid");
      return VpnTunnelService.TunnelStatus.DISCONNECTED;
    }
  }

  static void openApp(Context context) {
    Intent launchIntent = context.getPackageManager().getLaunchIntentForPackage(context.getPackageName());
    if (launchIntent == null) {
      LOG.warning(String.format(Locale.ROOT, "Failed to find launch intent for %s", context.getPackageName()));
      return;
    }
    launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    context.startActivity(launchIntent);
  }

  private static int getResourceId(Context context, String name, String type) {
    return context.getResources().getIdentifier(name, type, context.getPackageName());
  }
}
