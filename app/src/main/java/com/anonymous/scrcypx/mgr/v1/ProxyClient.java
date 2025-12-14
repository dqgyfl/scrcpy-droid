package com.anonymous.scrcypx.mgr.v1;

/**
 * @author VV
 * @date 12/7/2025
 */

import javax.net.ssl.*;
import java.net.InetSocketAddress;
import java.util.Collections;

public class ProxyClient {
    public static SSLSocket connect(String address, int port, String sniHost) throws Exception {
        // Create socket
        SSLSocket socket = (SSLSocket) MgrClient.getSslFactory().createSocket();
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

