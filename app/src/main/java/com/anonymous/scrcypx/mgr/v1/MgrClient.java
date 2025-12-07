package com.anonymous.scrcypx.mgr.v1;

import io.grpc.ManagedChannel;
import io.grpc.okhttp.OkHttpChannelBuilder;

/**
 * @author VV
 * @date 12/7/2025
 */
public class MgrClient {

    public static ManagedChannel connect(String host, int port) {
        return OkHttpChannelBuilder
                .forAddress(host, 50051)
                .usePlaintext()
                .build();
    }

}
