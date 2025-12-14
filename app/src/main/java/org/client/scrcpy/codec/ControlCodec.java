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
            case ControlMessage.TYPE_INJECT_KEYCODE:
                out.put((byte) msg.getAction());
                out.putInt(msg.getKeycode());
                out.putInt(msg.getRepeat());
                out.putInt(msg.getMetaState());
                break;
            case ControlMessage.TYPE_INJECT_TEXT:
                encode(msg.getText(), 4, out);
                break;
            case ControlMessage.TYPE_INJECT_TOUCH_EVENT:
                out.put((byte) msg.getAction());
                out.putLong(msg.getPointerId());
                encode(msg.getPosition(), out);
                out.putShort(floatToU16FixedPoint(msg.getPressure()));
                out.putInt(msg.getActionButton());
                out.putInt(msg.getButtons());
                break;
            case ControlMessage.TYPE_INJECT_SCROLL_EVENT:
                encode(msg.getPosition(), out);
                out.putShort(floatToI16FixedPoint(msg.getHScroll() / 16));
                out.putShort(floatToI16FixedPoint(msg.getVScroll() / 16));
                out.putInt(msg.getButtons());
                break;
            case ControlMessage.TYPE_BACK_OR_SCREEN_ON:
                out.put((byte) msg.getAction());
                break;
            case ControlMessage.TYPE_GET_CLIPBOARD:
                out.put((byte) msg.getCopyKey());
                break;
            case ControlMessage.TYPE_SET_CLIPBOARD:
                out.putLong(msg.getSequence());
                out.put((byte) (msg.getPaste() ? 1 : 0));
                encode(msg.getText(), 4, out);
                break;
            case ControlMessage.TYPE_SET_DISPLAY_POWER:
                out.put((byte) (msg.getOn() ? 1 : 0));
                break;
            case ControlMessage.TYPE_UHID_CREATE:
                out.putShort((short) msg.getId());
                out.putShort((short) msg.getVendorId());
                out.putShort((short) msg.getProductId());
                encode(msg.getText(), 1, out);
                out.putShort((short) msg.getData().length);
                out.put(msg.getData());
                break;
            case ControlMessage.TYPE_UHID_INPUT:
                out.putShort((short) msg.getId());
                out.putShort((short) msg.getData().length);
                out.put(msg.getData());
                break;
            case ControlMessage.TYPE_UHID_DESTROY:
                out.putShort((short) msg.getId());
                break;
            case ControlMessage.TYPE_START_APP:
                encode(msg.getText(), 1, out);
                break;
            // Empty messages (no extra data)
            case ControlMessage.TYPE_EXPAND_NOTIFICATION_PANEL:
            case ControlMessage.TYPE_EXPAND_SETTINGS_PANEL:
            case ControlMessage.TYPE_COLLAPSE_PANELS:
            case ControlMessage.TYPE_ROTATE_DEVICE:
            case ControlMessage.TYPE_OPEN_HARD_KEYBOARD_SETTINGS:
            case ControlMessage.TYPE_RESET_VIDEO:
                // nothing to encode
                break;
            default:
                throw new IllegalArgumentException("Cannot encode unknown ControlMessage type: " + msg.getType());
        }
    }

    public static void encode(Position position, ByteBuffer out) {
        out.putInt(position.getPoint().getX());
        out.putInt(position.getPoint().getY());
        out.putShort((short) position.getScreenSize().getWidth());
        out.putShort((short) position.getScreenSize().getHeight());
    }

    /**
     * Encode a string with variable-length size prefix
     *
     * @param out       ByteBuffer to write to
     * @param str       String to encode
     * @param sizeBytes Number of bytes used to store the length (1–4)
     */
    private static void encode(String str, int sizeBytes, ByteBuffer out) {
        if (sizeBytes < 1 || sizeBytes > 4) {
            throw new IllegalArgumentException("sizeBytes must be between 1 and 4");
        }

        if (str == null) {
            str = "";
        }

        byte[] bytes = str.getBytes(StandardCharsets.UTF_8);
        int length = bytes.length;

        if (length >= (1 << (8 * sizeBytes))) {
            throw new IllegalArgumentException("String too long to encode in " + sizeBytes + " bytes: " + length);
        }

        // write length in big-endian
        for (int i = sizeBytes - 1; i >= 0; i--) {
            out.put((byte) ((length >> (8 * i)) & 0xFF));
        }

        out.put(bytes);
    }

    /**
     * Convert float [0,1] to unsigned 16-bit fixed-point
     *
     * @param value float value between 0 and 1
     * @return encoded short
     */
    public static short floatToU16FixedPoint(float value) {
        if (value >= 1f) {
            return (short) 0xffff;
        } else if (value <= 0f) {
            return 0;
        } else {
            return (short) (value * 0x1p16f);
        }
    }

    /**
     * Convert float [-1,1] to signed 16-bit fixed-point
     *
     * @param value float value between -1 and 1
     * @return encoded short
     */
    public static short floatToI16FixedPoint(float value) {
        if (value >= 1f) {
            return 0x7fff;
        } else if (value <= -1f) {
            return (short) 0x8000;
        } else {
            return (short) (value * 0x1p15f);
        }
    }

}
