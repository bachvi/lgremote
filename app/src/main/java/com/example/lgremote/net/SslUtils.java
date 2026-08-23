package com.example.lgremote.net;

import java.security.SecureRandom;
import java.security.cert.X509Certificate;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * LG TVs present a self-signed certificate on their WebSocket endpoint,
 * so the app uses a trust-all SSL context for the local connection.
 */
public final class SslUtils {

    private SslUtils() {
    }

    public static final X509TrustManager TRUST_ALL = new X509TrustManager() {
        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) {
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) {
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return new X509Certificate[0];
        }
    };

    public static SSLSocketFactory trustAllSslSocketFactory() {
        try {
            SSLContext sc = SSLContext.getInstance("TLS");
            sc.init(null, new TrustManager[]{TRUST_ALL}, new SecureRandom());
            return sc.getSocketFactory();
        } catch (Exception e) {
            throw new IllegalStateException("Unable to create SSL context", e);
        }
    }
}
