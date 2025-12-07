package org.client.scrcpy.codec;

import com.genymobile.scrcpy.control.ControlMessage;
import com.genymobile.scrcpy.device.Position;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/**
 * @author VV
 * @date 12/4/2025
 */
public class ControlCodec {

    public static byte[] encode(ControlMessage msg) {
        ByteBuffer buffer = ByteBuffer.allocate(128);
        encode(msg, buffer);
        buffer.flip();
        byte[] ret = new byte[buffer.remaining()];
        buffer.get(ret);
        return ret;
    }

    public static void encode(ControlMessage msg, ByteBuffer out) {
        out.put((byte) msg.getType());
        switch (msg.getType()) {
            case ControlMessage.TYPE_INJECT_TOUCH_EVENT:
                out.put((byte) msg.getAction());
                out.putLong(msg.getPointerId());
                encode(msg.getPosition(), out);
                // todo is pressure right?
                out.putShort((short) msg.getPressure());
                out.putInt(msg.getActionButton());
                out.putInt(msg.getButtons());
                break;
            case ControlMessage.TYPE_INJECT_KEYCODE:
                out.put((byte) msg.getAction());
                out.putInt(msg.getKeycode());
                out.putInt(msg.getRepeat());
                out.putInt(msg.getMetaState());
                break;
            case ControlMessage.TYPE_START_APP:
                byte[] bytes = msg.getText().getBytes(StandardCharsets.UTF_8);
                out.put((byte) bytes.length);
                out.put(bytes);
                break;
        }
    }

    public static void encode(Position position, ByteBuffer out) {
        out.putInt(position.getPoint().getX());
        out.putInt(position.getPoint().getY());
        out.putShort((short) position.getScreenSize().getWidth());
        out.putShort((short) position.getScreenSize().getHeight());
    }

}
