package com.anonymous.scrcpyx;

import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.IBinder;
import android.util.Log;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.Surface;
import com.anonymous.scrcpyx.core.codec.ControlCodec;
import com.anonymous.scrcpyx.core.decoder.AudioDecoder;
import com.anonymous.scrcpyx.core.decoder.VideoDecoder;
import com.anonymous.scrcpyx.core.model.AudioPacket;
import com.anonymous.scrcpyx.core.model.VideoPacket;
import com.anonymous.scrcpyx.mgr.MgrClient;
import com.anonymous.scrcpyx.mgr.ProxyClient;
import com.genymobile.scrcpy.Options;
import com.genymobile.scrcpy.control.ControlMessage;
import com.genymobile.scrcpy.device.Position;
import scrcpyx.mgr.v1.StartScrcpyServerRequest;
import scrcpyx.mgr.v1.StartScrcpyServerResponse;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;


public class Scrcpy extends Service {

    private static final int WORKER_CNT = 4;

    private Options options;
    private ServiceCallbacks serviceCallbacks;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicInteger status = new AtomicInteger(0);
    private final AtomicBoolean hasUpdate = new AtomicBoolean(false);
    private final AtomicBoolean connected = new AtomicBoolean(false);


    private final AtomicReference<ExecutorService> workersRef = new AtomicReference<>(null);
    private final VideoDecoder videoDecoder = new VideoDecoder();
    private final AtomicInteger decoderSignal = new AtomicInteger(0);
    private Surface surface;
    // width, height
    private final int[] videoHeader = new int[2];
    // currently used for signal video size change
    private final AudioDecoder audioDecoder = new AudioDecoder();
    private final BlockingQueue<byte[]> event = new LinkedBlockingQueue<>();


    public void start(String serverAdr, String did, String app, List<String> args, ServiceCallbacks callbacks) {
        String[] serverInfo = serverAdr.split(":");
        String serverHost = serverInfo[0];
        int serverPort = serverInfo.length == 2 ? Integer.parseInt(serverInfo[1]) : 443;
        options = Options.parse(args.toArray(new String[0]));
        serviceCallbacks = callbacks;

        ExecutorService workers = Executors.newFixedThreadPool(WORKER_CNT);
        ExecutorService oldWorkers = workersRef.getAndSet(workers);
        if (oldWorkers != null) {
            oldWorkers.shutdownNow();
        }

        Log.e("ScrcpyxView", String.format("Starting Scrcpyx Server at %s %s %s %s", serverAdr, did, app, args));
        workers.submit(() -> {
            // video, audio, control
            SimpleChannel[] chs = {null, null, null};
            // scrcpy client args + scrcpy server args, currently only server args
            try {
                MgrClient.setServer(serverAdr);
                StartScrcpyServerResponse rsp = MgrClient.getClient().startScrcpyServer(StartScrcpyServerRequest.newBuilder()
                        .setDid(did)
                        .addAllArgs(args)
                        .build());
                // wait server startup
                Thread.sleep(1000);
                String[] s = {
                        this.options.getVideo() ? "video" : null,
                        this.options.getAudio() ? "audio" : null,
                        this.options.getControl() ? "control" : null
                };
                SimpleChannel firstCh = null;
                for (int i = 0; i < s.length; i++) {
                    if (s[i] == null) {
                        continue;
                    }
                    SimpleChannel ch = new SimpleChannel();
                    ch.chName = s[i];
                    ch.chType = i;
                    ch.socket = ProxyClient.connect(serverHost, serverPort, rsp.getSessionId());
                    ch.in = new DataInputStream(ch.socket.getInputStream());
                    ch.out = new DataOutputStream(ch.socket.getOutputStream());
                    chs[i] = ch;
                    if (firstCh == null) {
                        firstCh = ch;
                    }
                }
//                firstCh.waitReady();

                if (firstCh != null) {
                    byte[] meta = new byte[64];
                    firstCh.in.readFully(meta, 0, 64);
                    String ascii = new String(meta, StandardCharsets.UTF_8);
                    Log.d("Scrcpy", "connected to : " + ascii);
                }

                // move this into video handling
                running.set(true);
                connected.set(true);
                hasUpdate.set(true);

                List<Callable<String>> tasks = createTasks(chs);
                videoDecoder.start(decoderSignal);
                audioDecoder.start();

                SimpleChannel control = chs[2];
                if (control != null) {
                    Thread.sleep(500);
                    // -S, --turn-screen-off
                    sendEvent(ControlMessage.createSetDisplayPower(false));
                    // --start-app
                    if (app != null && !app.isBlank()) {
                        sendEvent(ControlMessage.createStartApp(app));
                    }
                }

                workers.invokeAny(tasks);
            } catch (Exception e) {
                Log.e("Scrcpy", "error" + e.getMessage());
                if (serviceCallbacks != null) {
                    serviceCallbacks.errorDisconnect();
                }
            } finally {
                for (SimpleChannel ch : chs) {
                    if (ch == null || ch.socket == null) {
                        continue;
                    }
                    try {
                        ch.socket.close();
                    } catch (IOException ignored) {
                    }
                }
                workers.shutdownNow();
                workersRef.compareAndSet(workers, null);
            }
        });
    }

    public void stop() {
        ExecutorService es = workersRef.getAndSet(null);
        if (es != null) {
            es.shutdownNow();
        }
        videoDecoder.stop();
        audioDecoder.stop();
        running.set(false);
    }

    private List<Callable<String>> createTasks(SimpleChannel[] chs) {
        List<Callable<String>> tasks = new ArrayList<>();
        for (SimpleChannel ch : chs) {
            if (ch == null || ch.socket == null) {
                continue;
            }
            tasks.add(() -> {
                try {
                    Log.d("Scrcpy", ch.chName + " started");
                    loop(ch.in, ch.out, ch.chType);
                } finally {
                    Log.d("Scrcpy", ch.chName + " stopped");
                }
                return "";
            });
        }
        return tasks;
    }

    public boolean isConnected() {
        return connected.get();
    }

    public void attachSurface(Surface NewSurface) {
        // decoder should manage this before it start
        this.surface = NewSurface;
        videoDecoder.start(decoderSignal);
        hasUpdate.set(true);
    }

    public void detachSurface() {
        // decoder should manage this before it start
        this.surface = null;
        videoDecoder.stop();
    }

    public void sendTouchEvent(MotionEvent touch_event, int idx, int w, int h) {
        Log.e("ScrcpyxView", String.format("sendTouchEvent (%s,%s) [%sx%s]", touch_event.getX(), touch_event.getY(), w, h));
        if (touch_event.getPointerCount() != 1) {
            return;
        }
        int ww = videoHeader[0];
        int hh = videoHeader[1];
        if ((w > h) != (ww > hh)) { // in second display it seems video width and height not correct
            ww = videoHeader[1];
            hh = videoHeader[0];
        }
        float xMod = ((float) ww) / w;
        float yMod = ((float) hh) / h;
        ControlMessage msg = ControlMessage.createInjectTouchEvent(
                touch_event.getActionMasked(), idx,
                new Position((int) (touch_event.getX(idx) * xMod), (int) (touch_event.getY(idx) * yMod), ww, hh),
                touch_event.getPressure(),
                touch_event.getActionButton(),
                touch_event.getButtonState()
        );
        event.offer(ControlCodec.encode(msg));
    }

    public void sendEvent(ControlMessage msg) {
        event.offer(ControlCodec.encode(msg));
    }

    public void sendKeyEvent(int keycode) {
        if (running.get()) {
            event.offer(ControlCodec.encode(ControlMessage.createInjectKeycode(
                    KeyEvent.ACTION_DOWN, keycode, 0, 0
            )));
            try {
                // remote this
                Thread.sleep(100);
            } catch (InterruptedException e) {
            }
            event.offer(ControlCodec.encode(ControlMessage.createInjectKeycode(
                    KeyEvent.ACTION_UP, keycode, 0, 0
            )));
        }
    }

    public void sendKeyEvent(KeyEvent evt) {
        event.offer(ControlCodec.encode(ControlMessage.createInjectKeycode(
                evt.getAction(), evt.getKeyCode(),
                evt.getRepeatCount(), evt.getMetaState()
        )));
    }

    private void loop(DataInputStream dataInputStream, DataOutputStream dataOutputStream, int chType) throws InterruptedException, IOException {
        boolean needRotate = false;
        if (chType == 0) {
            byte[] buf = new byte[8];
            dataInputStream.readFully(buf, 0, 4);
            String codec = new String(buf, 0, 4, StandardCharsets.US_ASCII);
            dataInputStream.readFully(buf, 0, 8);
            final int[] remote_dev_resolution = new int[2];
            for (int i = 0; i < remote_dev_resolution.length; i++) {
                remote_dev_resolution[i] = (((int) (buf[i * 4]) << 24) & 0xFF000000) |
                        (((int) (buf[i * 4 + 1]) << 16) & 0xFF0000) |
                        (((int) (buf[i * 4 + 2]) << 8) & 0xFF00) |
                        ((int) (buf[i * 4 + 3]) & 0xFF);
            }
            videoHeader[0] = remote_dev_resolution[0];
            videoHeader[1] = remote_dev_resolution[1];
            Log.e("Scrcpy", String.format("video meta : %s %d-%d", codec, videoHeader[0], videoHeader[1]));
            if (remote_dev_resolution[0] > remote_dev_resolution[1]) {
                needRotate = true;
                int i = remote_dev_resolution[0];
                remote_dev_resolution[0] = remote_dev_resolution[1];
                remote_dev_resolution[1] = i;
            }
        } else if (chType == 1) {
            byte[] buf = new byte[4];
            dataInputStream.readFully(buf);
            String codec = new String(buf, StandardCharsets.US_ASCII);
            Log.e("Scrcpy", "audio codec :" + codec);
        }

        VideoPacket.StreamSettings streamSettings = null;
        while (running.get()) {
            if (chType == 2) {
                byte[] sendevent = event.poll(3, TimeUnit.SECONDS);
                if (sendevent != null) {
                    dataOutputStream.write(sendevent);
                }
                continue;
            }

            long pts = dataInputStream.readLong();
            int size = dataInputStream.readInt();
            if (size > 4 * 1024 * 1024) {  // 如果单个数据包大于 4m ，直接断开连接
                return;
            }
            byte[] packet = new byte[size];
            dataInputStream.readFully(packet);
            if (chType == 0) {
                VideoPacket videoPacket = VideoPacket.parsePts(pts);
                videoPacket.presentationTimeStamp = 0;
                if (videoPacket.flag == VideoPacket.Flag.CONFIG) {
                    if (streamSettings != null) {
                        // force rotate screen if already configured
                        needRotate = true;
                        hasUpdate.set(true);
                    }
                    streamSettings = VideoPacket.getStreamSettings(packet);
                } else if (videoPacket.flag == VideoPacket.Flag.END) {
                    // need close stream
                    Log.e("Scrcpy", "END ... ");
                } else {
                    videoDecoder.decodeSample(packet, 0, packet.length,
                            videoPacket.presentationTimeStamp, videoPacket.flag.getFlag()
                    );
                }

                // handling rotation and update request
                if (needRotate && serviceCallbacks != null) {
                    needRotate = false;

                    // not sure whether this is safe, loadNewRotation always reset this?
                    hasUpdate.set(false);
                    serviceCallbacks.loadNewRotation();
                    while (!hasUpdate.get()) {
                        Thread.sleep(100);
                    }
                }
                if (hasUpdate.get() && streamSettings != null) {
                    hasUpdate.compareAndSet(true, false);
                    while (surface == null || !surface.isValid()) {
                        Thread.sleep(100);
                    }
                    videoDecoder.configure(surface, videoHeader[0], videoHeader[1], streamSettings.sps, streamSettings.pps);
                }

                int s = decoderSignal.get();
                if (s != 0) {
                    decoderSignal.compareAndSet(s, 0);
                    videoDecoder.getVideoSize(videoHeader);
                    Log.e("Scrcpy", "Video Size Changed: " + Arrays.toString(videoHeader));
                }
            } else if (chType == 1) {
                AudioPacket audioPacket = AudioPacket.parsePts(pts);
                audioPacket.presentationTimeStamp = 0;
                if (audioPacket.flag == AudioPacket.Flag.CONFIG) {
                    audioDecoder.configure(packet, options);
                } else if (audioPacket.flag == AudioPacket.Flag.END) {
                    // need close stream
                    Log.e("Scrcpy", "Audio END ... ");
                } else {
                    audioDecoder.decodeSample(packet, 0, packet.length,
                            audioPacket.presentationTimeStamp, audioPacket.flag.getFlag()
                    );
                }
            }
        }
    }

    public interface ServiceCallbacks {
        void loadNewRotation();

        void errorDisconnect();
    }

    private static class SimpleChannel extends Binder {
        private Socket socket;
        private int chType;
        private String chName;
        private DataInputStream in;
        private DataOutputStream out;

        public void waitReady() throws IOException {
            int attempts = 5;
            int waitResolutionCount = 10;
            while (in.available() <= 0 && waitResolutionCount > 0) {
                waitResolutionCount--;
                try {
                    Thread.sleep(100);
                } catch (InterruptedException ignore) {
                }
            }
            if (in.available() <= 0) {
                throw new IOException("can't read socket Resolution : " + attempts);
            }
        }
    }

    public class MyServiceBinder extends Binder {
        public Scrcpy getService() {
            return Scrcpy.this;
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return mBinder;
    }

    private final IBinder mBinder = new MyServiceBinder();

}
