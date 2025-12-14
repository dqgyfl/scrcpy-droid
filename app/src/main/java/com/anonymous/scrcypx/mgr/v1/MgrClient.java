package com.anonymous.scrcypx.mgr.v1;

import io.grpc.ManagedChannel;
import io.grpc.okhttp.OkHttpChannelBuilder;
import scrcpyx.mgr.v1.ScrcpyxMgrServiceGrpc;

import javax.net.ssl.*;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;

/**
 * @author VV
 * @date 12/7/2025
 */
public class MgrClient {

    private static String currentServerAddr;
    private static ManagedChannel channel;
    private static ScrcpyxMgrServiceGrpc.ScrcpyxMgrServiceBlockingV2Stub client;

    public static ManagedChannel connect(String addr) throws NoSuchAlgorithmException, KeyManagementException {
        return OkHttpChannelBuilder
                .forTarget(addr)
                .socketFactory(getSslFactory())
                .usePlaintext()
                .build();
    }

    public static ScrcpyxMgrServiceGrpc.ScrcpyxMgrServiceBlockingV2Stub getClient() {
        if (client == null) {
            throw new IllegalStateException("Server not set. Call setServer() first.");
        }
        return client;
    }

    public static synchronized void setServer(String addr) {
        // If the server changed, recreate channel
        if (!addr.equals(currentServerAddr)) {
            // Shutdown previous channel if exists
            if (channel != null && !channel.isShutdown()) {
                channel.shutdownNow();
            }
            try {
                channel = connect(addr);
                client = ScrcpyxMgrServiceGrpc.newBlockingV2Stub(channel);
            } catch (Exception ignored) {
            }
            currentServerAddr = addr;
        }
    }

    public static SSLSocketFactory getSslFactory() throws NoSuchAlgorithmException, KeyManagementException {
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

        return factory;
    }

}
