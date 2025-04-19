/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.facebook.stetho.inspector;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.text.TextUtils;
import com.facebook.stetho.common.ProcessUtil;
import com.facebook.stetho.server.http.ExactPathMatcher;
import com.facebook.stetho.server.http.HandlerRegistry;
import com.facebook.stetho.server.http.HttpHandler;
import com.facebook.stetho.server.http.HttpStatus;
import com.facebook.stetho.server.SocketLike;
import com.facebook.stetho.server.http.LightHttpBody;
import com.facebook.stetho.server.http.LightHttpRequest;
import com.facebook.stetho.server.http.LightHttpResponse;
import com.facebook.stetho.common.LogRedirector;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.annotation.Nullable;

/**
 * Provides sufficient responses to convince Chrome's {@code chrome://inspect/devices} that we're
 * "one of them".  Note that we are being discovered automatically by the name of our socket
 * as defined in {@link LocalSocketHttpServer}.  After discovery, we're required to provide
 * some context on how exactly to display and inspect what we have.
 */
public class ChromeDiscoveryHandler implements HttpHandler {
  private static final String TAG = "ChromeDiscoveryHandler";
  private static final String PAGE_ID = "1";

  private static final String PATH_PAGE_LIST = "/json";
  private static final String PATH_PAGE_LIST1 = "/json/list";
  private static final String PATH_VERSION = "/json/version";
  private static final String PATH_ACTIVATE = "/json/activate/" + PAGE_ID;

  /**
   * Latest version of the WebKit Inspector UI that we've tested again (ideally).
   */
  private static final String WEBKIT_REV = "@81b36b9535e3e3b610a52df3da48cd81362ec860";
  private static final String WEBKIT_VERSION = "537.36 (" + WEBKIT_REV + ")";

  private static final String USER_AGENT = "Stetho";

  /**
   * Structured version of the WebKit Inspector protocol that we understand.
   */
  private static final String PROTOCOL_VERSION = "1.3";
  
  // Pattern to extract Chrome version from User-Agent
  private static final Pattern CHROME_PATTERN = Pattern.compile("Chrome/([0-9]+)\\.");

  private final Context mContext;
  private final String mInspectorPath;
  
  // Current client's Chrome version
  private int mChromeVersion = 99;

  @Nullable private LightHttpBody mVersionResponse;
  @Nullable private LightHttpBody mPageListResponse;

  public ChromeDiscoveryHandler(Context context, String inspectorPath) {
    mContext = context;
    mInspectorPath = inspectorPath;
  }

  public void register(HandlerRegistry registry) {
    registry.register(new ExactPathMatcher(PATH_PAGE_LIST), this);
    registry.register(new ExactPathMatcher(PATH_PAGE_LIST1), this);
    registry.register(new ExactPathMatcher(PATH_VERSION), this);
    registry.register(new ExactPathMatcher(PATH_ACTIVATE), this);
  }

  @Override
  public boolean handleRequest(SocketLike socket, LightHttpRequest request, LightHttpResponse response) {
    String path = request.uri.getPath();
    
    // Extract Chrome version from User-Agent header
    detectChromeVersionFromRequest(request);
    
    // Reset cached responses since we may need different responses for different Chrome versions
    mVersionResponse = null;
    mPageListResponse = null;
    
    try {
      if (PATH_VERSION.equals(path)) {
        handleVersion(response);
      } else if (PATH_PAGE_LIST.equals(path) || PATH_PAGE_LIST1.equals(path)) {
        handlePageList(response);
      } else if (PATH_ACTIVATE.equals(path)) {
        handleActivate(response);
      } else {
        response.code = HttpStatus.HTTP_NOT_IMPLEMENTED;
        response.reasonPhrase = "Not implemented";
        response.body = LightHttpBody.create("No support for " + path + "\n", "text/plain");
      }
    } catch (JSONException e) {
      response.code = HttpStatus.HTTP_INTERNAL_SERVER_ERROR;
      response.reasonPhrase = "Internal server error";
      response.body = LightHttpBody.create(e.toString() + "\n", "text/plain");
    }
    return true;
  }
  
  /**
   * Detect Chrome version from User-Agent header
   */
  private void detectChromeVersionFromRequest(LightHttpRequest request) {
    String userAgent = request.getFirstHeaderValue("User-Agent");
    if (!TextUtils.isEmpty(userAgent)) {
      Matcher matcher = CHROME_PATTERN.matcher(userAgent);
      if (matcher.find()) {
        try {
          mChromeVersion = Integer.parseInt(matcher.group(1));
          LogRedirector.d(TAG, "Detected Chrome version: " + mChromeVersion);
        } catch (NumberFormatException e) {
          LogRedirector.w(TAG, "Failed to parse Chrome version from: " + userAgent);
          mChromeVersion = 99;
        }
      } else {
        mChromeVersion = 99;
      }
    }
  }

  private void handleVersion(LightHttpResponse response)
      throws JSONException {
    if (mVersionResponse == null) {
      JSONObject reply = new JSONObject();
      reply.put("WebKit-Version", WEBKIT_VERSION);
      reply.put("User-Agent", USER_AGENT);
      reply.put("Protocol-Version", PROTOCOL_VERSION);
      reply.put("Browser", getAppLabelAndVersion());
      reply.put("Android-Package", mContext.getPackageName());
      mVersionResponse = LightHttpBody.create(reply.toString(), "application/json");
    }
    setSuccessfulResponse(response, mVersionResponse);
  }

  private void handlePageList(LightHttpResponse response)
      throws JSONException {
    if (mPageListResponse == null) {
      JSONArray reply = new JSONArray();
      JSONObject page = new JSONObject();
      page.put("type", "app");
      page.put("title", makeTitle());
      page.put("id", PAGE_ID);
      page.put("description", "");

      page.put("webSocketDebuggerUrl", "ws://" + mInspectorPath);
      
      Uri chromeFrontendUrl;
      if (mChromeVersion > 0 && mChromeVersion <= 89) {
        LogRedirector.d(TAG, "Using devtools.html for Chrome " + mChromeVersion);
        chromeFrontendUrl = new Uri.Builder()
                .scheme("http")
                .authority("chrome-devtools-frontend.appspot.com")
                .appendEncodedPath("serve_rev")
                .appendEncodedPath("@188492")
                .appendEncodedPath("devtools.html")
                .appendQueryParameter("ws", mInspectorPath)
                .build();
      } else {
        chromeFrontendUrl = new Uri.Builder()
                .scheme("http")
                .authority("chrome-devtools-frontend.appspot.com")
                .appendEncodedPath("serve_rev")
                .appendEncodedPath(WEBKIT_REV)
                .appendEncodedPath("inspector.html")
                .appendQueryParameter("ws", mInspectorPath)
                .build();
      }
      
      page.put("devtoolsFrontendUrl", chromeFrontendUrl.toString());

      reply.put(page);
      mPageListResponse = LightHttpBody.create(reply.toString(), "application/json");
    }
    setSuccessfulResponse(response, mPageListResponse);
  }

  private String makeTitle() {
    StringBuilder b = new StringBuilder();
    b.append(getAppLabel());

    b.append(" (powered by Stetho)");

    String processName = ProcessUtil.getProcessName();
    int colonIndex = processName.indexOf(':');
    if (colonIndex >= 0) {
      String nonDefaultProcessName = processName.substring(colonIndex);
      b.append(nonDefaultProcessName);
    }

    return b.toString();
  }

  private void handleActivate(LightHttpResponse response) {
    // Arbitrary response seem acceptable :)
    setSuccessfulResponse(
        response,
        LightHttpBody.create("Target activation ignored\n", "text/plain"));
  }

  private static void setSuccessfulResponse(
      LightHttpResponse response,
      LightHttpBody body) {
    response.code = HttpStatus.HTTP_OK;
    response.reasonPhrase = "OK";
    response.body = body;
  }

  private String getAppLabelAndVersion() {
    StringBuilder b = new StringBuilder();
    PackageManager pm = mContext.getPackageManager();
    b.append(getAppLabel());
    b.append('/');
    try {
      PackageInfo info = pm.getPackageInfo(mContext.getPackageName(), 0 /* flags */);
      b.append(info.versionName);
    } catch (PackageManager.NameNotFoundException e) {
      throw new RuntimeException(e);
    }
    return b.toString();
  }

  private CharSequence getAppLabel() {
    PackageManager pm = mContext.getPackageManager();
    return pm.getApplicationLabel(mContext.getApplicationInfo());
  }
}
