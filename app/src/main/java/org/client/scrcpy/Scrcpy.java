package org.client.scrcpy;

import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.IBinder;
import android.util.Log;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.Surface;

import com.anonuymous.scrcypx.mgr.v1.ProxyClient;
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
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;


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
    private final AtomicBoolean updateAvailable = new AtomicBoolean(false);
    private final IBinder mBinder = new MyServiceBinder();
    private boolean first_time = true;

    private final AtomicBoolean LetServceRunning = new AtomicBoolean(true);
    private ServiceCallbacks serviceCallbacks;
    private final int[] remote_dev_resolution = new int[2];
    // width, height
    private final int[] videoHeader = new int[2];
    private boolean socket_status = false;

    private Options options;


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

        videoDecoder.start();
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

    public void start(Surface surface, String serverAdr, int screenHeight, int screenWidth, int delay) {
        this.videoDecoder = new VideoDecoder();
        videoDecoder.start();

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
            List<SimpleChannel> chs = new LinkedList<>();
            this.options = Options.parse(SendCommands.scrcpyCmd);
            try {
                String[] s = {"video", "audio", "control"};
                for (int i = 0; i < 3; i++) {
//                    Socket socket = new Socket();
                    SimpleChannel ch = new SimpleChannel();
                    ch.chName = s[i];
                    ch.chType = i;
                    ch.socket = ProxyClient.connect("192.168.0.77", 4430, SendCommands.session_id);
                    chs.add(ch);
//                    socket.connect(new InetSocketAddress(serverHost, serverPort), 5000);
                    ch.in = new DataInputStream(ch.socket.getInputStream());
                    ch.out = new DataOutputStream(ch.socket.getOutputStream());
                }
                SimpleChannel firstCh = chs.get(0);
//                firstCh.waitReady();
                byte[] meta = new byte[64];
                int rr = firstCh.in.read(meta, 0, 64);
                String ascii = new String(meta, StandardCharsets.UTF_8);
                Log.d("Scrcpy", "connected to : " + ascii);

                chs.get(2).out.write(ControlCodec.encode(ControlMessage.createStartApp("com.android.browser")));
                Thread.sleep(delay);

                // move this into video handling
                socket_status = true;
                for (SimpleChannel ch : chs) {
                    new Thread(null, () -> {
                        try {
                            loop(ch.in, ch.out, delay, ch.chType);
                        } catch (InterruptedException | IOException ignored) {
                        } finally {
                            try {
                                ch.socket.close();
                            } catch (IOException ignored) {
                            }
                        }
                    }, "worker-" + ch.chName).start();
                }
            } catch (IOException e) {
                for (SimpleChannel ch : chs) {
                    try {
                        ch.socket.close();
                    } catch (IOException ignored) {
                    }
                }
                throw new RuntimeException(e);
            } catch (Exception e) {
                throw new RuntimeException(e);
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
            videoDecoder.start();
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
        int pointerId = touch_event.getPointerId(actionIndex);
        int pointCount = touch_event.getPointerCount();
        // Log.e("Scrcpy", "pointer id: " + pointerId + " , action: " + touch_event.getAction() + " ,point count: " + pointCount + " x: " + touch_event.getX() + " y: " + touch_event.getY());


        switch (touch_event.getAction()) {
            case MotionEvent.ACTION_MOVE: // 所有手指移动
                // 遍历所有触摸点，使用 pointerId 和 pointerIndex 来获取所有触摸点的信息
                for (int i = 0; i < touch_event.getPointerCount(); i++) {
                    int currentPointerId = touch_event.getPointerId(i);
                    int x = (int) touch_event.getX(i);
                    int y = (int) touch_event.getY(i);
                    // 处理每一个触摸点的x, y坐标
                    // Log.e("Scrcpy", "触摸移动，index : " + i + " ,x : " + x + " , y: " + y + " ,currentPointerId: " + currentPointerId);
//                    sendTouchEvent(touch_event.getAction(), touch_event.getButtonState(), (int) (x * realW / displayW), (int) (y * realH / displayH), currentPointerId);
                    sendTouchEvent(touch_event, i, displayW, displayH);
                }
                break;
            case MotionEvent.ACTION_POINTER_UP: // 中间手指抬起
            case MotionEvent.ACTION_UP: // 最后一个手指抬起
            case MotionEvent.ACTION_DOWN: // 第一个手指按下
            case MotionEvent.ACTION_POINTER_DOWN: // 中间的手指按下
            default:
//                sendTouchEvent(touch_event.getAction(), touch_event.getButtonState(), (int) (touch_event.getX() * realW / displayW), (int) (touch_event.getY() * realH / displayH), pointerId);
                sendTouchEvent(touch_event, actionIndex, displayW, displayH);
                break;

        }
        return true;
    }

    private void sendTouchEvent(MotionEvent touch_event, int idx, int w, int h) {
        // todo: there got a swap in loop width and height, right now match by aspect
        int ww = videoHeader[0];
        int hh = videoHeader[1];
        if ((w > h) != (ww > hh)) {
            ww = videoHeader[1];
            hh = videoHeader[0];
        }
        float xMod = ((float) ww) / w;
        float yMod = ((float) hh) / h;
        ControlMessage msg = ControlMessage.createInjectTouchEvent(
                touch_event.getAction(), touch_event.getPointerId(idx),
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

    private void startConnection(String ip, int port, int delay) {

        videoDecoder = new VideoDecoder();
        videoDecoder.start();
        audioDecoder = new AudioDecoder();
        audioDecoder.start();

        DataInputStream dataInputStream = null;
        DataOutputStream dataOutputStream = null;
        Socket socket = null;
        boolean firstConnect = true;
        int attempts = 50;
        while (attempts > 0) {
            try {
                Log.e("Scrcpy", "Connecting to " + LOCAL_IP);
                // socket = new Socket(ip, port);
                socket = new Socket();
                socket.connect(new InetSocketAddress(ip, port), 5000); //设置超时5000毫秒
                if (!LetServceRunning.get()) {
                    return;
                }

                Log.e("Scrcpy", "Connecting to " + LOCAL_IP + " success");

                // 能够正常进行连接，说明可能建立了 tcp 连接，需要等待数据
                // 一次等待时间为 2s ，最多等待五次，也就是 10秒
                if (firstConnect) {  // 此处有 while 循环，不能一直设置为10
                    firstConnect = false;
                    // waitResolutionCount 为 10，等待100ms 也就是共计一秒钟，设置attempts 为 5，也就是 5秒后则退出
                    attempts = 5;
                }
                dataInputStream = new DataInputStream(socket.getInputStream());
                int waitResolutionCount = 10;
                while (dataInputStream.available() <= 0 && waitResolutionCount > 0) {
                    waitResolutionCount--;
                    try {
                        Thread.sleep(100);
                    } catch (InterruptedException ignore) {
                    }
                }
                if (dataInputStream.available() <= 0) {
                    throw new IOException("can't read socket Resolution : " + attempts);
                }


                dataOutputStream = new DataOutputStream(socket.getOutputStream());
                attempts = 0;
                byte[] buf = new byte[16];
                dataInputStream.read(buf, 0, 16);
                for (int i = 0; i < remote_dev_resolution.length; i++) {
                    remote_dev_resolution[i] = (((int) (buf[i * 4]) << 24) & 0xFF000000) |
                            (((int) (buf[i * 4 + 1]) << 16) & 0xFF0000) |
                            (((int) (buf[i * 4 + 2]) << 8) & 0xFF00) |
                            ((int) (buf[i * 4 + 3]) & 0xFF);
                }
                if (remote_dev_resolution[0] > remote_dev_resolution[1]) {
                    first_time = false;
                    int i = remote_dev_resolution[0];
                    remote_dev_resolution[0] = remote_dev_resolution[1];
                    remote_dev_resolution[1] = i;
                }
                socket_status = true;

                loop(dataInputStream, dataOutputStream, delay);

            } catch (Exception e) {
                e.printStackTrace();
                if (LetServceRunning.get()) {
                    attempts--;
                    if (attempts < 0) {
                        socket_status = false;

                        if (serviceCallbacks != null) {
                            serviceCallbacks.errorDisconnect();
                        }
                        return;
                    }
                    try {
                        Thread.sleep(100);
                    } catch (InterruptedException ignore) {
                    }
                }
                Log.e("Scrcpy", e.getMessage());
                Log.e("Scrcpy", "attempts--");
            } finally {
                if (socket != null) {
                    try {
                        socket.close();
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
                if (dataOutputStream != null) {
                    try {
                        dataOutputStream.close();
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
                if (dataInputStream != null) {
                    try {
                        dataInputStream.close();
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
                // 清除事件队列
                event.clear();

            }

        }

    }

    private void loop(DataInputStream dataInputStream, DataOutputStream dataOutputStream, int delay) throws InterruptedException {
        try {
            loop(dataInputStream, dataOutputStream, delay, 1);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private void loop(DataInputStream dataInputStream, DataOutputStream dataOutputStream, int delay, int chType) throws InterruptedException, IOException {
        if (chType == 0) {
            byte[] buf = new byte[8];
            int r = dataInputStream.read(buf, 0, 4);
            String codec = new String(buf, 0, 4, java.nio.charset.StandardCharsets.US_ASCII);
            r = dataInputStream.read(buf, 0, 8);
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
                first_time = false;
                int i = remote_dev_resolution[0];
                remote_dev_resolution[0] = remote_dev_resolution[1];
                remote_dev_resolution[1] = i;
            }
        } else if (chType == 1) {
            byte[] buf = new byte[8];
            int r = dataInputStream.read(buf, 0, 4);
            String codec = new String(buf, 0, 4, java.nio.charset.StandardCharsets.US_ASCII);
            Log.e("Scrcpy", "audio codec :" + codec);
        }


        VideoPacket.StreamSettings streamSettings = null;
        byte[] packetSize = new byte[4];

        // 由于网络传输存在延迟，丢弃数据包计数
        long lastVideoOffset = 0;
        long lastAudioOffset = 0;
        int videoPassCount = 0;
        int audioPassCount = 0;

        while (LetServceRunning.get()) {
            try {
                if (chType == 2) {

                    byte[] sendevent = event.poll(3, TimeUnit.SECONDS);
                    if (sendevent != null) {
                        try {
                            dataOutputStream.write(sendevent, 0, sendevent.length);
                        } catch (IOException e) {
                            e.printStackTrace();
                            if (serviceCallbacks != null) {
                                serviceCallbacks.errorDisconnect();
                            }
                            LetServceRunning.set(false);
                        } finally {
                            // event = null;
                        }
                    }
                    continue;
                }

                // block for read
                if (dataInputStream.available() >= 0) {
                    long pts = dataInputStream.readLong();
                    int size = dataInputStream.readInt();
                    if (size > 4 * 1024 * 1024) {  // 如果单个数据包大于 4m ，直接断开连接
                        if (serviceCallbacks != null) {
                            serviceCallbacks.errorDisconnect();
                        }
                        LetServceRunning.set(false);
                        return;
                    }
                    byte[] packet = new byte[size];
                    dataInputStream.readFully(packet, 0, size);
//                    if (MediaPacket.Type.getType(packet[0]) == MediaPacket.Type.VIDEO) {
                    if (chType == 0) {
                        VideoPacket videoPacket = VideoPacket.parsePts(pts);
                        // byte[] data = videoPacket.data;
                        if (videoPacket.flag == VideoPacket.Flag.CONFIG || updateAvailable.get()) {
                            if (!updateAvailable.get()) {
                                int dataLength = packet.length - VideoPacket.getHeadLen();
                                byte[] data = new byte[dataLength];
                                System.arraycopy(packet, VideoPacket.getHeadLen(), data, 0, dataLength);
                                streamSettings = VideoPacket.getStreamSettings(data);
                                if (!first_time) {
                                    if (serviceCallbacks != null) {
                                        serviceCallbacks.loadNewRotation();
                                    }
                                    while (!updateAvailable.get()) {
                                        // Waiting for new surface
                                        try {
                                            Thread.sleep(100);
                                        } catch (InterruptedException e) {
                                            e.printStackTrace();
                                        }
                                    }

                                }
                            }
                            updateAvailable.set(false);
                            // dirty fix, remove fist_time, it seems used to adjust rotation, to match aspect of video
                            // need a more robust way of handling it
                            // also remove updateAvailable field seems bad
                            // should orientation change block?
                            while (!surface.isValid()) {
                                // Waiting for new surface
                                try {
                                    Thread.sleep(100);
                                } catch (InterruptedException e) {
                                    e.printStackTrace();
                                }
                            }
                            if (streamSettings != null) {
                                videoDecoder.configure(surface, screenWidth, screenHeight, streamSettings.sps, streamSettings.pps);
                            }
                        } else if (videoPacket.flag == VideoPacket.Flag.END) {
                            // need close stream
                            Log.e("Scrcpy", "END ... ");
                        } else {
                            // Log.e("Scrcpy", "videoPacket presentationTimeStamp ... " + videoPacket.presentationTimeStamp);
                            // 帧在 100 ms 以内
                            if (lastVideoOffset == 0) {
                                lastVideoOffset = System.currentTimeMillis() - (videoPacket.presentationTimeStamp / 1000);
                            }
                            if (videoPacket.flag == VideoPacket.Flag.KEY_FRAME) {
                                videoDecoder.decodeSample(packet, VideoPacket.getHeadLen(), packet.length - VideoPacket.getHeadLen(),
                                        0, videoPacket.flag.getFlag());
                            } else {
                                if (System.currentTimeMillis() - (lastVideoOffset + (videoPacket.presentationTimeStamp / 1000)) < delay) {
                                    videoPassCount = 0;
                                    videoDecoder.decodeSample(packet, VideoPacket.getHeadLen(), packet.length - VideoPacket.getHeadLen(),
                                            0, videoPacket.flag.getFlag());
                                } else {
                                    videoPassCount++;
                                }
                            }
                        }
                        first_time = false;
//                    } else if (MediaPacket.Type.getType(packet[0]) == MediaPacket.Type.AUDIO) {
                    } else if (chType == 1) {
                        AudioPacket audioPacket = AudioPacket.parsePts(pts);
                        // byte[] data = audioPacket.data;
                        if (audioPacket.flag == AudioPacket.Flag.CONFIG) {
                            int dataLength = packet.length - AudioPacket.getHeadLen();
                            byte[] data = new byte[dataLength];
                            System.arraycopy(packet, AudioPacket.getHeadLen(), data, 0, dataLength);
                            audioDecoder.configure(data, options);
                        } else if (audioPacket.flag == AudioPacket.Flag.END) {
                            // need close stream
                            Log.e("Scrcpy", "Audio END ... ");
                        } else {
                            if (lastAudioOffset == 0) {
                                lastAudioOffset = System.currentTimeMillis() - (audioPacket.presentationTimeStamp / 1000);
                            }
                            if (System.currentTimeMillis() - (lastAudioOffset + (audioPacket.presentationTimeStamp / 1000)) < delay) {
                                audioPassCount = 0;
                                audioDecoder.decodeSample(packet, VideoPacket.getHeadLen(), packet.length - AudioPacket.getHeadLen(),
                                        0, audioPacket.flag.getFlag());
                            } else {
                                audioPassCount++;
                            }
                        }
                    }

                }
            } catch (IOException e) {
                Log.e("Scrcpy", "IOException: " + e.getMessage());
                e.printStackTrace();
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
