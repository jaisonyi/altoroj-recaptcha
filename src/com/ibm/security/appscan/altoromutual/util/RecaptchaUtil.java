/**
This application is for demonstration use only. It contains known application security
vulnerabilities that were created expressly for demonstrating the functionality of
application security testing tools. These vulnerabilities may present risks to the
technical environment in which the application is installed. You must delete and
uninstall this demonstration application upon completion of the demonstration for
which it is intended.

IBM AltoroJ
(c) Copyright IBM Corp. 2008, 2013 All Rights Reserved.

reCAPTCHA support added on top of the AltoroJ 3.2 baseline. This variant replaces the
TOTP multi-factor-authentication experiment with a Google reCAPTCHA v2 ("I'm not a
robot") challenge on the login pages.
 */
package com.ibm.security.appscan.altoromutual.util;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.Properties;

import com.ibm.security.appscan.Log4AltoroJ;

/**
 * Helper that loads the reCAPTCHA keys and performs server-side verification of the
 * token that the browser widget submits as the {@code g-recaptcha-response} parameter.
 *
 * <p>Keys are read from {@code recaptcha.properties} on the classpath (packaged into
 * {@code WEB-INF/classes}). If the file or a key is missing, Google's official test
 * key pair is used so the application still runs out of the box. The test keys always
 * pass verification and display a "for testing purposes only" banner on the widget.</p>
 */
public class RecaptchaUtil {

	/** Google's official reCAPTCHA v2 test site key (always passes). */
	public static final String TEST_SITE_KEY = "6LeIxAcTAAAAAJcZVRqyHh71UMIEGNQ_MXjiZKhI";
	/** Google's official reCAPTCHA v2 test secret key (always passes). */
	public static final String TEST_SECRET_KEY = "6LeIxAcTAAAAAGG-vFI1TnRWxMZNFuojJ4WifJWe";

	private static final String VERIFY_URL = "https://www.google.com/recaptcha/api/siteverify";

	private static String siteKey = TEST_SITE_KEY;
	private static String secretKey = TEST_SECRET_KEY;

	static {
		InputStream in = null;
		try {
			in = RecaptchaUtil.class.getResourceAsStream("/recaptcha.properties");
			if (in != null) {
				Properties props = new Properties();
				props.load(in);
				String s = props.getProperty("recaptcha.siteKey");
				String k = props.getProperty("recaptcha.secretKey");
				if (s != null && s.trim().length() > 0) {
					siteKey = s.trim();
				}
				if (k != null && k.trim().length() > 0) {
					secretKey = k.trim();
				}
			}
		} catch (Exception e) {
			Log4AltoroJ.getInstance().logError("Could not load recaptcha.properties, using test keys: " + e.getMessage());
		} finally {
			if (in != null) {
				try { in.close(); } catch (Exception ignored) { /* do nothing */ }
			}
		}
	}

	/**
	 * @return the public site key to embed in the reCAPTCHA widget on the login pages.
	 */
	public static String getSiteKey() {
		return siteKey;
	}

	/**
	 * Verify a reCAPTCHA response token with Google's siteverify endpoint.
	 *
	 * @param responseToken the value of the {@code g-recaptcha-response} form parameter
	 * @param remoteIp the client's IP address (optional, may be null)
	 * @return {@code true} only if Google confirms the challenge was solved
	 */
	public static boolean verify(String responseToken, String remoteIp) {
		if (responseToken == null || responseToken.trim().length() == 0) {
			return false;
		}

		HttpURLConnection conn = null;
		try {
			StringBuilder postData = new StringBuilder();
			postData.append("secret=").append(URLEncoder.encode(secretKey, "UTF-8"));
			postData.append("&response=").append(URLEncoder.encode(responseToken, "UTF-8"));
			if (remoteIp != null && remoteIp.trim().length() > 0) {
				postData.append("&remoteip=").append(URLEncoder.encode(remoteIp, "UTF-8"));
			}
			byte[] body = postData.toString().getBytes("UTF-8");

			URL url = new URL(VERIFY_URL);
			conn = (HttpURLConnection) url.openConnection();
			conn.setDoOutput(true);
			conn.setRequestMethod("POST");
			conn.setConnectTimeout(10000);
			conn.setReadTimeout(10000);
			conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
			conn.setRequestProperty("Content-Length", Integer.toString(body.length));

			DataOutputStream out = new DataOutputStream(conn.getOutputStream());
			try {
				out.write(body);
				out.flush();
			} finally {
				out.close();
			}

			StringBuilder responseBody = new StringBuilder();
			BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"));
			try {
				String line;
				while ((line = reader.readLine()) != null) {
					responseBody.append(line);
				}
			} finally {
				reader.close();
			}

			// Google returns {"success": true|false, ...}. A demo-grade check is sufficient.
			boolean success = responseBody.toString().replaceAll("\\s", "").contains("\"success\":true");
			if (!success) {
				Log4AltoroJ.getInstance().logError("reCAPTCHA verification failed: " + responseBody.toString());
			}
			return success;
		} catch (Exception e) {
			Log4AltoroJ.getInstance().logError("reCAPTCHA verification error: " + e.getMessage());
			return false;
		} finally {
			if (conn != null) {
				conn.disconnect();
			}
		}
	}
}
