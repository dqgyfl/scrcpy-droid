package org.client.scrcpy;

import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.IBinder;
import android.util.Log;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.Surface;
import com.anonymous.scrcypx.mgr.v1.ProxyClient;
import com.genymobile.scrcpy.Options;
import com.genymobile.scrcpy.control.ControlMessage;
import com.genymobile.scrcpy.device.Position;
import org.client.scrcpy.codec.ControlCodec;
import org.client.scrcpy.decoder.AudioDecoder;
import org.client.scrcpy.decoder.VideoDecoder;
import org.client.scrcpy.model.AudioPacket;
import org.client.scrcpy.model.VideoPacket;
import org.client.scrcpy.utils.Util;

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


public class Scrcpy extends Service {

    public static final String LOCAL_IP = "127.0.0.1";
    // 本地画面转发占用的端口
    public static final int LOCAL_FORWART_PORT = 7008;

    public static final int DEFAULT_ADB_PORT = 5555;
    private String serverHost;
    private int serverPort = DEFAULT_ADB_PORT;
    private Surface surface;
    private int screenWidth;
    private int screenHeight;

    private final BlockingQueue<byte[]> event = new LinkedBlockingQueue<>();
    // private byte[] event = null;
    private VideoDecoder videoDecoder;
    private AudioDecoder audioDecoder;
    private final AtomicBoolean updateAvailable = new AtomicBoolean(true);
    private final IBinder mBinder = new MyServiceBinder();
    private boolean needRotate = false;

    private final AtomicBoolean LetServceRunning = new AtomicBoolean(true);
    private ServiceCallbacks serviceCallbacks;
    private final int[] remote_dev_resolution = new int[2];
    // width, height
    private final int[] videoHeader = new int[2];
    private boolean socket_status = false;

    private Options options;
    // currently used for signal video size change
    private AtomicInteger decoderSignal = new AtomicInteger(0);


    @Override
    public IBinder onBind(Intent intent) {
        return mBinder;
    }

    public void setServiceCallbacks(ServiceCallbacks callbacks) {
        serviceCallbacks = callbacks;
    }

    public void setParms(Surface NewSurface, int NewWidth, int NewHeight) {
        this.screenWidth = NewWidth;
        this.screenHeight = NewHeight;
        this.surface = NewSurface;

        videoDecoder.start(decoderSignal);
        audioDecoder.start();


        updateAvailable.set(true);

    }

    public static class SimpleChannel extends Binder {
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

    public void start(Surface surface, String serverAdr, int screenHeight, int screenWidth, int delay, String app, List<String> args) {
        this.videoDecoder = new VideoDecoder();
        videoDecoder.start(decoderSignal);

        this.audioDecoder = new AudioDecoder();
        audioDecoder.start();

        String[] serverInfo = Util.getServerHostAndPort(serverAdr);
        this.serverHost = serverInfo[0];
        this.serverPort = Integer.parseInt(serverInfo[1]);

        this.screenHeight = screenHeight;
        this.screenWidth = screenWidth;
        this.surface = surface;
        Thread thread = new Thread(() -> {
            // video, audio, control
            SimpleChannel[] chs = {null, null, null};
            // scrcpy client args + scrcpy server args, currently only server args
            this.options = Options.parse(args.toArray(new String[0]));
            try {
                // wait server startup
                Thread.sleep(500);
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
                    ch.socket = ProxyClient.connect(serverHost, serverPort, SendCommands.session_id);
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
                socket_status = true;
                List<Callable<String>> tasks = new ArrayList<>();
                for (SimpleChannel ch : chs) {
                    if (ch == null || ch.socket == null) {
                        continue;
                    }
                    tasks.add(() -> {
                        try {
                            loop(ch.in, ch.out, delay, ch.chType);
                        } catch (InterruptedException | IOException ignored) {
                            LetServceRunning.set(false);
                        } finally {
                            try {
                                ch.socket.close();
                            } catch (IOException ignored) {
                            }
                        }
                        Log.e("Scrcpy", ch.chName + " stoped");
                        return "";
                    });
                }

                SimpleChannel control = chs[2];
                if (control != null) {
                    Thread.sleep(500);
                    // -S, --turn-screen-off
                    control.out.write(ControlCodec.encode(ControlMessage.createSetDisplayPower(false)));
                    // --start-app
                    if (app != null && !app.isBlank()) {
                        control.out.write(ControlCodec.encode(ControlMessage.createStartApp(app)));
                    }
                }
                try (ExecutorService executor = Executors.newFixedThreadPool(3)) {
                    executor.invokeAny(tasks);
                }
            } catch (Exception e) {
                for (SimpleChannel ch : chs) {
                    if (ch == null || ch.socket == null) {
                        continue;
                    }
                    try {
                        ch.socket.close();
                    } catch (IOException ignored) {
                    }
                }
                if (serviceCallbacks != null) {
                    serviceCallbacks.errorDisconnect();
                }
            }
        });
        thread.start();
    }

    public void pause() {
        if (videoDecoder != null) {
            videoDecoder.stop();
        }

        if (audioDecoder != null) {
            audioDecoder.stop();
        }
    }

    public void resume() {
        if (videoDecoder != null) {
            videoDecoder.start(decoderSignal);
        }
        if (audioDecoder != null) {
            audioDecoder.start();
        }
        updateAvailable.set(true);
    }

    public void StopService() {
        LetServceRunning.set(false);
        if (videoDecoder != null) {
            videoDecoder.stop();
        }
        if (audioDecoder != null) {
            audioDecoder.stop();
        }
        stopSelf();
    }


    public boolean touchevent(MotionEvent touch_event, boolean landscape, int displayW, int displayH) {
        float remoteW;
        float remoteH;
        float realH;
        float realW;

        if (landscape) {  // 横屏的话，宽高相反
            remoteW = Math.max(remote_dev_resolution[0], remote_dev_resolution[1]);
            remoteH = Math.min(remote_dev_resolution[0], remote_dev_resolution[1]);

            realW = Math.min(remoteW, screenWidth);
            realH = realW * remoteH / remoteW;
        } else {
            remoteW = Math.min(remote_dev_resolution[0], remote_dev_resolution[1]);
            remoteH = Math.max(remote_dev_resolution[0], remote_dev_resolution[1]);
            realH = Math.min(remoteH, screenHeight);
            realW = realH * remoteW / remoteH;
        }

        int actionIndex = touch_event.getActionIndex();
        if (touch_event.getPointerCount() != 1) {
            return true;
        }
        int pointerId = touch_event.getPointerId(actionIndex);
        int pointCount = touch_event.getPointerCount();
        // Log.e("Scrcpy", "pointer id: " + pointerId + " , action: " + touch_event.getAction() + " ,point count: " + pointCount + " x: " + touch_event.getX() + " y: " + touch_event.getY());


        switch (touch_event.getActionMasked()) {
//            case MotionEvent.ACTION_MOVE: // 所有手指移动
//                // 遍历所有触摸点，使用 pointerId 和 pointerIndex 来获取所有触摸点的信息
//                for (int i = 0; i < touch_event.getPointerCount(); i++) {
//                    sendTouchEvent(touch_event, i, displayW, displayH);
//                }
//                break;
//            case MotionEvent.ACTION_POINTER_UP: // 中间手指抬起
//            case MotionEvent.ACTION_UP: // 最后一个手指抬起
//            case MotionEvent.ACTION_DOWN: // 第一个手指按下
//            case MotionEvent.ACTION_POINTER_DOWN: // 中间的手指按下
            default:
                sendTouchEvent(touch_event, actionIndex, displayW, displayH);
                break;

        }
        return true;
    }

    private void sendTouchEvent(MotionEvent touch_event, int idx, int w, int h) {
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

    private void sendTouchEvent(int action, int buttonState, int x, int y, int pointerId) {
        // 为支持多点触控，将 pointid 添加到最末尾
        // TODO : 后续需要改造 event 传输方式
        int[] buf = new int[]{action, buttonState, x, y, pointerId};
        final byte[] array = new byte[buf.length * 4]; // https://stackoverflow.com/questions/2183240/java-integer-to-byte-array
        for (int j = 0; j < buf.length; j++) {
            final int c = buf[j];
            array[j * 4] = (byte) ((c & 0xFF000000) >> 24);
            array[j * 4 + 1] = (byte) ((c & 0xFF0000) >> 16);
            array[j * 4 + 2] = (byte) ((c & 0xFF00) >> 8);
            array[j * 4 + 3] = (byte) (c & 0xFF);
        }
        if (LetServceRunning.get()) {
            event.offer(array);
        }
        // event = array;
    }

    public int[] get_remote_device_resolution() {
        return remote_dev_resolution;
    }

    public boolean check_socket_connection() {
        return socket_status;
    }

    public void sendKeyevent(int keycode) {
        int[] buf = new int[]{keycode};

        final byte[] array = new byte[buf.length * 4];   // https://stackoverflow.com/questions/2183240/java-integer-to-byte-array
        for (int j = 0; j < buf.length; j++) {
            final int c = buf[j];
            array[j * 4] = (byte) ((c & 0xFF000000) >> 24);
            array[j * 4 + 1] = (byte) ((c & 0xFF0000) >> 16);
            array[j * 4 + 2] = (byte) ((c & 0xFF00) >> 8);
            array[j * 4 + 3] = (byte) (c & 0xFF);
        }
        if (LetServceRunning.get()) {
            event.offer(ControlCodec.encode(ControlMessage.createInjectKeycode(
                    KeyEvent.ACTION_DOWN, keycode, 0, 0
            )));
            try {
                // remote his
                Thread.sleep(100);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
            event.offer(ControlCodec.encode(ControlMessage.createInjectKeycode(
                    KeyEvent.ACTION_UP, keycode, 0, 0
            )));
//            event.offer(array);
            // event = array;
        }
    }

    public void sendKeyEvent(KeyEvent evt) {
        event.offer(ControlCodec.encode(ControlMessage.createInjectKeycode(
                evt.getAction(), evt.getKeyCode(),
                evt.getRepeatCount(), evt.getMetaState()
        )));
    }

    private void loop(DataInputStream dataInputStream, DataOutputStream dataOutputStream, int delay, int chType) throws InterruptedException, IOException {
        if (chType == 0) {
            byte[] buf = new byte[8];
            dataInputStream.readFully(buf, 0, 4);
            String codec = new String(buf, 0, 4, java.nio.charset.StandardCharsets.US_ASCII);
            dataInputStream.readFully(buf, 0, 8);
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
            String codec = new String(buf, java.nio.charset.StandardCharsets.US_ASCII);
            Log.e("Scrcpy", "audio codec :" + codec);
        }

        VideoPacket.StreamSettings streamSettings = null;
        while (LetServceRunning.get()) {
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
                if (videoPacket.flag == VideoPacket.Flag.CONFIG) {
                    if (streamSettings != null) {
                        // force rotate screen if already configured
                        needRotate = true;
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
                    updateAvailable.set(false);
                    serviceCallbacks.loadNewRotation();
                    while (!updateAvailable.get()) {
                        Thread.sleep(100);
                    }
                }
                if (updateAvailable.get() && streamSettings != null) {
                    updateAvailable.compareAndSet(true, false);
                    while (!surface.isValid()) {
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

    public class MyServiceBinder extends Binder {
        public Scrcpy getService() {
            return Scrcpy.this;
        }
    }


}
