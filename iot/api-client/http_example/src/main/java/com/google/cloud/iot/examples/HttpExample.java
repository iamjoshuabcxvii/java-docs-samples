/*
  Copyright 2017, Google, Inc.

  Licensed under the Apache License, Version 2.0 (the "License");
  you may not use this file except in compliance with the License.
  You may obtain a copy of the License at

      http://www.apache.org/licenses/LICENSE-2.0

  Unless required by applicable law or agreed to in writing, software
  distributed under the License is distributed on an "AS IS" BASIS,
  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
  See the License for the specific language governing permissions and
  limitations under the License.
*/


package com.google.cloud.iot.examples;

// [START cloudiotcore_http_imports]
import io.jsonwebtoken.JwtBuilder;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.HttpURLConnection;
import java.net.ProtocolException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.KeyFactory;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;
import org.joda.time.DateTime;
import org.json.JSONException;
import org.json.JSONObject;
// [END cloudiotcore_http_imports]

/**
 * Java sample of connecting to Google Cloud IoT Core vice via HTTP, using JWT.
 *
 * <p>This example connects to Google Cloud IoT Core via HTTP Bridge, using a JWT for device
 * authentication. After connecting, by default the device publishes 100 messages at a rate of one
 * per second, and then exits. You can change The behavior to set state instead of events by using
 * flag -message_type to 'state'.
 *
 * <p>To run this example, follow the instructions in the README located in the sample's parent
 * folder.
 */
public class HttpExample {
  // [START cloudiotcore_http_createjwt]
  /** Create a RSA-based JWT for the given project id, signed with the given private key. */
  private static String createJwtRsa(String projectId, String privateKeyFile) throws Exception {
    DateTime now = new DateTime();
    // Create a JWT to authenticate this device. The device will be disconnected after the token
    // expires, and will have to reconnect with a new token. The audience field should always be set
    // to the GCP project id.
    JwtBuilder jwtBuilder =
        Jwts.builder()
            .setIssuedAt(now.toDate())
            .setExpiration(now.plusMinutes(20).toDate())
            .setAudience(projectId);

    byte[] keyBytes = Files.readAllBytes(Paths.get(privateKeyFile));
    PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(keyBytes);
    KeyFactory kf = KeyFactory.getInstance("RSA");

    return jwtBuilder.signWith(SignatureAlgorithm.RS256, kf.generatePrivate(spec)).compact();
  }

  /** Create an ES-based JWT for the given project id, signed with the given private key. */
  private static String createJwtEs(String projectId, String privateKeyFile) throws Exception {
    DateTime now = new DateTime();
    // Create a JWT to authenticate this device. The device will be disconnected after the token
    // expires, and will have to reconnect with a new token. The audience field should always be set
    // to the GCP project id.
    JwtBuilder jwtBuilder =
        Jwts.builder()
            .setIssuedAt(now.toDate())
            .setExpiration(now.plusMinutes(20).toDate())
            .setAudience(projectId);

    byte[] keyBytes = Files.readAllBytes(Paths.get(privateKeyFile));
    PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(keyBytes);
    KeyFactory kf = KeyFactory.getInstance("ES256");

    return jwtBuilder.signWith(SignatureAlgorithm.ES256, kf.generatePrivate(spec)).compact();
  }
  // [END cloudiotcore_http_createjwt]

  // [START cloudiotcore_http_publishmessage]
  /** Publish an event or state message using Cloud IoT Core via the HTTP API. */
  public static void publishMessage(String payload, String urlPath, String messageType,
      String token, String projectId, String cloudRegion, String registryId, String deviceId)
      throws UnsupportedEncodingException, IOException, JSONException, ProtocolException {
    // Build the resource path of the device that is going to be authenticated.
    String devicePath =
        String.format(
            "projects/%s/locations/%s/registries/%s/devices/%s",
            projectId, cloudRegion, registryId, deviceId);
    String urlSuffix = messageType.equals("event") ? "publishEvent" : "setState";

    // Data sent through the wire has to be base64 encoded.
    Base64.Encoder encoder = Base64.getEncoder();

    String encPayload = encoder.encodeToString(payload.getBytes("UTF-8"));


    urlPath = urlPath + devicePath + ":" + urlSuffix;
    URL url = new URL(urlPath);
    HttpURLConnection httpCon = (HttpURLConnection) url.openConnection();
    httpCon.setDoOutput(true);
    httpCon.setRequestMethod("POST");

    // Add headers.
    httpCon.setRequestProperty("authorization", String.format("Bearer %s", token));
    httpCon.setRequestProperty("content-type", "application/json; charset=UTF-8");
    httpCon.setRequestProperty("cache-control", "no-cache");

    // Add post data. The data sent depends on whether we're updating state or publishing events.
    JSONObject data = new JSONObject();
    if (messageType.equals("event")) {
      data.put("binary_data", encPayload);
    } else {
      JSONObject state = new JSONObject();
      state.put("binary_data", encPayload);
      data.put("state", state);
    }
    httpCon.getOutputStream().write(data.toString().getBytes("UTF-8"));
    httpCon.getOutputStream().close();

    System.out.println(httpCon.getResponseCode());
    System.out.println(httpCon.getResponseMessage());
  }
  // [END cloudiotcore_http_publishmessage]

  // [START cloudiotcore_http_run]
  /** Parse arguments and publish messages. */
  public static void main(String[] args) throws Exception {
    HttpExampleOptions options = HttpExampleOptions.fromFlags(args);
    if (options == null) {
      // Could not parse the flags.
      System.exit(1);
    }

    // Create the corresponding JWT depending on the selected algorithm.
    String token;
    DateTime iat = new DateTime();
    if (options.algorithm.equals("RS256")) {
      token = createJwtRsa(options.projectId, options.privateKeyFile);
    } else if (options.algorithm.equals("ES256")) {
      token = createJwtEs(options.projectId, options.privateKeyFile);
    } else {
      throw new IllegalArgumentException(
          "Invalid algorithm " + options.algorithm + ". Should be one of 'RS256' or 'ES256'.");
    }

    String urlPath = String.format("%s/%s/", options.httpBridgeAddress, options.apiVersion);
    System.out.format("Using URL: '%s'\n", urlPath);

    // Publish numMessages messages to the HTTP bridge.
    for (int i = 1; i <= options.numMessages; ++i) {
      String payload = String.format("%s/%s-payload-%d", options.registryId, options.deviceId, i);
      System.out.format(
          "Publishing %s message %d/%d: '%s'\n",
          options.messageType, i, options.numMessages, payload);

      // Refresh the authentication token if the token has expired.
      long secsSinceRefresh = ((new DateTime()).getMillis() - iat.getMillis()) / 1000;
      if (secsSinceRefresh > (options.tokenExpMins * 60)) {
        System.out.format("\tRefreshing token after: %d seconds\n", secsSinceRefresh);
        iat = new DateTime();

        if (options.algorithm.equals("RS256")) {
          token = createJwtRsa(options.projectId, options.privateKeyFile);
        } else if (options.algorithm.equals("ES256")) {
          token = createJwtEs(options.projectId, options.privateKeyFile);
        }
      }

      publishMessage(payload, urlPath, options.messageType, token, options.projectId,
              options.cloudRegion, options.registryId, options.deviceId);

      if (options.messageType.equals("event")) {
        // Frequently send event payloads (every second)
        Thread.sleep(1000);
      } else {
        // Update state with low frequency (once every 5 seconds)
        Thread.sleep(5000);
      }
    }
    System.out.println("Finished loop successfully. Goodbye!");
  }
  // [END cloudiotcore_http_run]
}
