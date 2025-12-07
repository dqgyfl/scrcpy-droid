package com.anonymous.scrcypx.mgr.v1;

/**
 * @author VV
 * @date 12/7/2025
 */

import javax.net.ssl.*;
import java.net.InetSocketAddress;
import java.security.SecureRandom;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.Collections;

public class ProxyClient {
    public static SSLSocket connect(String address, int port, String sniHost) throws Exception {

        // ---------- 1) Create trust-all manager ----------
        TrustManager[] trustAll = new TrustManager[]{
                new X509TrustManager() {
                    @Override
                    public void checkServerTrusted(X509Certificate[] chain, String authType) {
                    }

                    @Override
                    public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
                    }

                    @Override
                    public X509Certificate[] getAcceptedIssuers() {
                        return new X509Certificate[]{};
                    }
                }
        };

        // ---------- 2) Disable hostname verification ----------
        HostnameVerifier allHostsValid = (hostname, session) -> true;
        HttpsURLConnection.setDefaultHostnameVerifier(allHostsValid);

        // ---------- 3) Create insecure SSLContext ----------
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, trustAll, new SecureRandom());

        SSLSocketFactory factory = context.getSocketFactory();

        // Create socket
        SSLSocket socket = (SSLSocket) factory.createSocket();
        socket.connect(new InetSocketAddress(address, port), 5000);

        // ---------- 4) Set SNI manually ----------
        SSLParameters params = socket.getSSLParameters();
        params.setServerNames(Collections.singletonList(new SNIHostName(sniHost)));
        socket.setSSLParameters(params);

        // ---------- 5) Handshake ----------
        socket.startHandshake();

        return socket;
    }

    public static SSLSocket connect2(String address, int port, String sniHost) throws Exception {
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, null, null);

        SSLSocketFactory factory = context.getSocketFactory();
        SSLSocket socket = (SSLSocket) factory.createSocket();

        // Connect TCP
        socket.connect(new InetSocketAddress(address, port), 5000);

        // Configure SNI
        SSLParameters params = socket.getSSLParameters();
        params.setServerNames(Collections.singletonList(new SNIHostName(sniHost)));
        socket.setSSLParameters(params);

        // Start TLS handshake
        socket.startHandshake();

        return socket;
    }
}

