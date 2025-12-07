package org.client.scrcpy.decoder;

import android.media.*;
import android.os.Build;
import com.genymobile.scrcpy.Options;
import com.genymobile.scrcpy.util.CodecOption;
import com.genymobile.scrcpy.util.CodecUtils;
import com.genymobile.scrcpy.util.Ln;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

public class AudioDecoder {

    public static final String MIMETYPE_AUDIO_AAC = "audio/mp4a-latm";

    private MediaCodec mCodec;
    private Worker mWorker;
    private AtomicBoolean mIsConfigured = new AtomicBoolean(false);

    private AudioTrack audioTrack;
    private final int SAMPLE_RATE = 48000;

    private void initAudioTrack() {
        int bufferSizeInBytes = AudioTrack.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT);
        audioTrack = new AudioTrack(AudioManager.STREAM_MUSIC, SAMPLE_RATE, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT,
                bufferSizeInBytes, AudioTrack.MODE_STREAM);
    }

    public void decodeSample(byte[] data, int offset, int size, long presentationTimeUs, int flags) {
        if (mWorker != null) {
            mWorker.decodeSample(data, offset, size, presentationTimeUs, flags);
        }
    }

    public void configure(byte[] data, Options options) {
        if (mWorker != null) {
            mWorker.configure(data, options);
        }
    }


    public void start() {
        if (mWorker == null) {
            mWorker = new Worker();
            mWorker.setRunning(true);
            mWorker.start();
        }
    }

    public void stop() {
        if (mWorker != null) {
            mWorker.setRunning(false);
            mWorker = null;
            mIsConfigured.set(false);
            if (mCodec != null) {
                mCodec.stop();
            }
            if (audioTrack != null) {
                audioTrack.stop();
            }
        }
    }

    private class Worker extends Thread {

        private AtomicBoolean mIsRunning = new AtomicBoolean(false);

        Worker() {
        }

        private void setRunning(boolean isRunning) {
            mIsRunning.set(isRunning);
        }

        private MediaFormat createFormat(String mimeType, int bitRate, List<CodecOption> codecOptions) {
            MediaFormat format = new MediaFormat();
            format.setString(MediaFormat.KEY_MIME, mimeType);
            format.setInteger(MediaFormat.KEY_BIT_RATE, bitRate);
            format.setInteger(MediaFormat.KEY_CHANNEL_COUNT, 2);
            format.setInteger(MediaFormat.KEY_SAMPLE_RATE, SAMPLE_RATE);

            if (codecOptions != null) {
                for (CodecOption option : codecOptions) {
                    String key = option.getKey();
                    Object value = option.getValue();
                    CodecUtils.setCodecOption(format, key, value);
                    Ln.d("Audio codec option set: " + key + " (" + value.getClass().getSimpleName() + ") = " + value);
                }
            }

            return format;
        }

        private void configure(byte[] data, Options options) {
            if (mIsConfigured.get()) {
                mIsConfigured.set(false);
                if (mCodec != null) {
                    mCodec.stop();
                }
                if (audioTrack != null) {
                    audioTrack.stop();
                }
            }
//            MediaFormat format = MediaFormat.createAudioFormat(options.getAudioCodec().getMimeType(), options.getAudioBitRate(), 2);
            MediaFormat format = createFormat(options.getAudioCodec().getMimeType(),options.getAudioBitRate(),  options.getAudioCodecOptions());
            // 设置比特率
//            format.setInteger(MediaFormat.KEY_BIT_RATE, 128000);
            // adts 0
            // format.setInteger(MediaFormat.KEY_IS_ADTS, 1);
//            format.setByteBuffer("csd-0", ByteBuffer.wrap(data));

            try {
                mCodec = MediaCodec.createDecoderByType(MIMETYPE_AUDIO_AAC);
            } catch (IOException e) {
                throw new RuntimeException("Failed to create codec", e);
            }
            mCodec.configure(format, null, null, 0);
            mCodec.start();
            mIsConfigured.set(true);

            // 初始化音频播放器
            initAudioTrack();
            // audio track 启动
            audioTrack.play();
        }


        @SuppressWarnings("deprecation")
        public void decodeSample(byte[] data, int offset, int size, long presentationTimeUs, int flags) {
            if (mIsConfigured.get() && mIsRunning.get()) {
                int index = mCodec.dequeueInputBuffer(-1);
                if (index >= 0) {
                    ByteBuffer buffer;

                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
                        buffer = mCodec.getInputBuffers()[index];
                        buffer.clear();
                    } else {
                        buffer = mCodec.getInputBuffer(index);
                    }
                    if (buffer != null) {
                        buffer.put(data, offset, size);
                        mCodec.queueInputBuffer(index, 0, size, presentationTimeUs, flags);
                    }
                }
            }
        }

        @Override
        public void run() {
            try {
                MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
                while (mIsRunning.get()) {
                    if (mIsConfigured.get()) {
                        int index = mCodec.dequeueOutputBuffer(info, 0);
                        // Log.e("Scrcpy", "Audio Decoder: " + index);
                        if (index >= 0) {
                            if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) == MediaCodec.BUFFER_FLAG_END_OF_STREAM) {
                                break;
                            }
                            // Log.e("Scrcpy", "Audio success get frame: " + index);

                            // 读取 pcm 数据，写入 audiotrack 播放
                            ByteBuffer outputBuffer = mCodec.getOutputBuffer(index);
                            if (outputBuffer != null) {
                                byte[] data = new byte[info.size];
                                outputBuffer.get(data);
                                outputBuffer.clear();
                                audioTrack.write(data, 0, info.size);
                            }
                            // release
                            mCodec.releaseOutputBuffer(index, true);
                        }
                    } else {
                        // just waiting to be configured, then decode and render
                        try {
                            Thread.sleep(5);
                        } catch (InterruptedException ignore) {
                        }
                    }
                }
            } catch (IllegalStateException e) {
            }

        }
    }
}