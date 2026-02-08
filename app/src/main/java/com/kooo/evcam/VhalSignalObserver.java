package com.kooo.evcam;

import android.os.Handler;
import android.os.Looper;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import io.grpc.CallOptions;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.okhttp.OkHttpChannelBuilder;
import io.grpc.stub.ClientCalls;
import io.grpc.stub.MetadataUtils;
import io.grpc.stub.StreamObserver;

/**
 * 通过 VHAL gRPC 直接监听转向灯信号。
 *
 * 连接车机系统内置的 VHAL gRPC 服务（vendor.ecarx.automotive.vehicle），
 * 订阅属性变化流，过滤转向灯 property 回调给调用方。
 *
 * 无需 root、无需 daemon，系统服务开机即运行。
 *
 * 转向灯属性: TURN_SIGNAL_STATE (0x11400408 / 289408008)
 *   值: 0=无, 1=右转, 2=左转
 */
public class VhalSignalObserver {
    private static final String TAG = "VhalSignalObserver";

    private static final String GRPC_HOST = "localhost";
    private static final int GRPC_PORT = 40004;

    // TURN_SIGNAL_STATE property ID
    public static final int PROP_TURN_SIGNAL_STATE = 289408008; // 0x11400408

    // 转向灯状态值
    private static final int SIGNAL_NONE = 0;
    private static final int SIGNAL_RIGHT = 1;
    private static final int SIGNAL_LEFT = 2;

    /**
     * 转向灯信号回调接口
     */
    public interface TurnSignalListener {
        /** 转向灯状态变化 */
        void onTurnSignal(String direction, boolean on);
        /** 连接状态变化 */
        void onConnectionStateChanged(boolean connected);
    }

    private final TurnSignalListener listener;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private ManagedChannel grpcChannel;
    private Thread connectThread;
    private volatile boolean running = false;
    private volatile boolean connected = false;

    // 上一次的转向灯状态，避免重复回调
    private int lastSignalState = -1;

    // 重连参数
    private static final long RECONNECT_DELAY_MS = 3000;

    // gRPC method descriptors (使用 ByteMarshaller，手动编解码 protobuf)
    private static final MethodDescriptor<byte[], byte[]> START_STREAM_METHOD =
            MethodDescriptor.<byte[], byte[]>newBuilder()
                    .setType(MethodDescriptor.MethodType.SERVER_STREAMING)
                    .setFullMethodName("vhal_proto.VehicleServer/StartPropertyValuesStream")
                    .setRequestMarshaller(ByteMarshaller.INSTANCE)
                    .setResponseMarshaller(ByteMarshaller.INSTANCE)
                    .build();

    private static final MethodDescriptor<byte[], byte[]> SEND_ALL_METHOD =
            MethodDescriptor.<byte[], byte[]>newBuilder()
                    .setType(MethodDescriptor.MethodType.UNARY)
                    .setFullMethodName("vhal_proto.VehicleServer/SendAllPropertyValuesToStream")
                    .setRequestMarshaller(ByteMarshaller.INSTANCE)
                    .setResponseMarshaller(ByteMarshaller.INSTANCE)
                    .build();

    public VhalSignalObserver(TurnSignalListener listener) {
        this.listener = listener;
    }

    /**
     * 启动连接和监听
     */
    public void start() {
        if (running) return;
        running = true;
        lastSignalState = -1;
        connectThread = new Thread(this::connectLoop, "VhalGrpcConnect");
        connectThread.setDaemon(true);
        connectThread.start();
    }

    /**
     * 停止连接和监听
     */
    public void stop() {
        running = false;
        disconnect();
        if (connectThread != null) {
            connectThread.interrupt();
            connectThread = null;
        }
    }

    /**
     * 当前是否已连接
     */
    public boolean isConnected() {
        return connected;
    }

    /**
     * 一次性连接测试（阻塞调用，用于 UI 状态检查）
     */
    public static boolean testConnection() {
        try {
            java.net.Socket s = new java.net.Socket();
            s.connect(new java.net.InetSocketAddress("127.0.0.1", GRPC_PORT), 2000);
            s.close();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    // ==================== Internal ====================

    private void connectLoop() {
        while (running) {
            try {
                AppLog.d(TAG, "Connecting to VHAL gRPC service...");
                boolean ok = connect();
                if (ok) {
                    AppLog.d(TAG, "gRPC connected, starting property stream");
                    notifyConnectionState(true);
                    streamProperties(); // blocks until disconnected
                }
            } catch (Exception e) {
                AppLog.e(TAG, "gRPC connection error: " + e.getMessage());
            }

            connected = false;
            notifyConnectionState(false);
            disconnect();

            if (!running) break;

            try {
                AppLog.d(TAG, "Reconnecting in " + RECONNECT_DELAY_MS + "ms...");
                Thread.sleep(RECONNECT_DELAY_MS);
            } catch (InterruptedException e) {
                break;
            }
        }
    }

    private boolean connect() {
        try {
            // 构建 gRPC channel，附带 session_id 和 client_id metadata
            // Ecarx VHAL 服务器要求 session_id 非空
            String sessionId = UUID.randomUUID().toString();
            Metadata headers = new Metadata();
            headers.put(
                    Metadata.Key.of("session_id", Metadata.ASCII_STRING_MARSHALLER),
                    sessionId
            );
            headers.put(
                    Metadata.Key.of("client_id", Metadata.ASCII_STRING_MARSHALLER),
                    "evcam_signal"
            );

            grpcChannel = OkHttpChannelBuilder.forAddress(GRPC_HOST, GRPC_PORT)
                    .usePlaintext()
                    .intercept(MetadataUtils.newAttachHeadersInterceptor(headers))
                    .build();

            connected = true;
            AppLog.d(TAG, "gRPC channel created, session_id=" + sessionId);
            return true;
        } catch (Exception e) {
            AppLog.e(TAG, "gRPC connect failed: " + e.getMessage());
            disconnect();
            return false;
        }
    }

    private void disconnect() {
        connected = false;
        if (grpcChannel != null) {
            try {
                grpcChannel.shutdown();
                if (!grpcChannel.awaitTermination(2, TimeUnit.SECONDS)) {
                    grpcChannel.shutdownNow();
                }
            } catch (Exception ignored) {
                try { grpcChannel.shutdownNow(); } catch (Exception ignored2) {}
            }
            grpcChannel = null;
        }
    }

    /**
     * 开始属性流监听（阻塞直到断开或出错）
     */
    private void streamProperties() {
        if (grpcChannel == null) return;

        // 使用 CountDownLatch 等待流结束
        final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        final boolean[] streamError = {false};

        try {
            var call = grpcChannel.newCall(START_STREAM_METHOD, CallOptions.DEFAULT);

            ClientCalls.asyncServerStreamingCall(call, new byte[0], new StreamObserver<byte[]>() {
                @Override
                public void onNext(byte[] value) {
                    try {
                        processPropertyBatch(value);
                    } catch (Exception e) {
                        AppLog.e(TAG, "Failed to process property batch: " + e.getMessage());
                    }
                }

                @Override
                public void onError(Throwable t) {
                    AppLog.e(TAG, "Property stream error: " + t.getMessage());
                    streamError[0] = true;
                    latch.countDown();
                }

                @Override
                public void onCompleted() {
                    AppLog.d(TAG, "Property stream completed");
                    latch.countDown();
                }
            });

            // 请求服务器推送所有当前属性值（与 EVCC 一致，立即调用无延迟）
            // 服务器通过 channel metadata 中的 session_id 关联此请求和 stream
            new Thread(() -> {
                try {
                    if (grpcChannel != null) {
                        var sendCall = grpcChannel.newCall(SEND_ALL_METHOD, CallOptions.DEFAULT);
                        ClientCalls.blockingUnaryCall(sendCall, new byte[0]);
                        AppLog.d(TAG, "Requested all property values to stream");
                    }
                } catch (Exception e) {
                    AppLog.w(TAG, "SendAll failed (non-fatal): " + e.getMessage());
                }
            }, "VhalSendAll").start();

            // 等待流结束
            latch.await();

        } catch (Exception e) {
            AppLog.e(TAG, "Stream setup failed: " + e.getMessage());
        }
    }

    /**
     * 处理一批属性值更新（protobuf 手动解码）
     *
     * Wire format: WrappedVehiclePropValues → repeated WrappedVehiclePropValue → VehiclePropValue
     */
    private void processPropertyBatch(byte[] data) {
        // 解码 WrappedVehiclePropValues: field 1 = repeated WrappedVehiclePropValue (message)
        List<byte[]> wrappedValues = ProtoDecoder.readRepeatedMessage(data, 1);

        for (byte[] wrapped : wrappedValues) {
            // 解码 WrappedVehiclePropValue: field 1 = VehiclePropValue (message)
            byte[] propValueBytes = ProtoDecoder.readMessage(wrapped, 1);
            if (propValueBytes == null) continue;

            // 解码 VehiclePropValue: field 1 = prop (int32), field 5 = int32_values (sint32)
            int propId = ProtoDecoder.readInt32(propValueBytes, 1);
            if (propId != PROP_TURN_SIGNAL_STATE) continue;

            // 读取 int32_values (zigzag encoded sint32)
            List<Integer> int32Values = ProtoDecoder.readPackedSint32(propValueBytes, 5);
            int signalState = int32Values.isEmpty() ? 0 : int32Values.get(0);

            if (signalState == lastSignalState) continue; // 避免重复回调

            AppLog.d(TAG, "TURN_SIGNAL_STATE changed: " + lastSignalState + " -> " + signalState
                    + " (" + signalStateName(signalState) + ")");

            int previousState = lastSignalState;
            lastSignalState = signalState;

            // 分发事件
            mainHandler.post(() -> {
                if (listener == null) return;

                switch (signalState) {
                    case SIGNAL_LEFT:
                        listener.onTurnSignal("left", true);
                        break;
                    case SIGNAL_RIGHT:
                        listener.onTurnSignal("right", true);
                        break;
                    case SIGNAL_NONE:
                        // 转向灯关闭，根据之前的状态发送 off
                        if (previousState == SIGNAL_LEFT) {
                            listener.onTurnSignal("left", false);
                        } else if (previousState == SIGNAL_RIGHT) {
                            listener.onTurnSignal("right", false);
                        }
                        break;
                }
            });
        }
    }

    private static String signalStateName(int state) {
        switch (state) {
            case SIGNAL_NONE: return "none";
            case SIGNAL_RIGHT: return "right";
            case SIGNAL_LEFT: return "left";
            default: return "unknown(" + state + ")";
        }
    }

    private void notifyConnectionState(boolean isConnected) {
        mainHandler.post(() -> {
            if (listener != null) {
                listener.onConnectionStateChanged(isConnected);
            }
        });
    }

    // ==================== ByteMarshaller ====================

    /** gRPC marshaller that passes raw bytes (same as EVCC's approach) */
    private enum ByteMarshaller implements MethodDescriptor.Marshaller<byte[]> {
        INSTANCE;

        @Override
        public InputStream stream(byte[] value) {
            return new ByteArrayInputStream(value);
        }

        @Override
        public byte[] parse(InputStream stream) {
            try {
                // 不使用 readAllBytes()，Android 11 (API 30) 不支持该方法
                java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[4096];
                int len;
                while ((len = stream.read(buf)) != -1) {
                    baos.write(buf, 0, len);
                }
                return baos.toByteArray();
            } catch (Exception e) {
                return new byte[0];
            }
        }
    }

    // ==================== Lightweight Protobuf Decoder ====================

    /**
     * 最小化 protobuf 解码器，仅处理 VHAL 属性流所需的字段类型。
     * 避免引入 Wire/protobuf-java 等重依赖。
     */
    static class ProtoDecoder {

        /** 读取 message 字段（返回子消息原始字节） */
        static byte[] readMessage(byte[] data, int fieldNumber) {
            int pos = 0;
            while (pos < data.length) {
                int[] tagResult = readVarint(data, pos);
                int tag = tagResult[0];
                pos = tagResult[1];
                int wireType = tag & 0x07;
                int field = tag >>> 3;

                if (wireType == 2) { // length-delimited
                    int[] lenResult = readVarint(data, pos);
                    int len = lenResult[0];
                    pos = lenResult[1];
                    if (field == fieldNumber) {
                        byte[] result = new byte[len];
                        System.arraycopy(data, pos, result, 0, len);
                        return result;
                    }
                    pos += len;
                } else if (wireType == 0) { // varint
                    int[] v = readVarint(data, pos);
                    pos = v[1];
                } else if (wireType == 5) { // 32-bit
                    pos += 4;
                } else if (wireType == 1) { // 64-bit
                    pos += 8;
                } else {
                    break; // unknown wire type
                }
            }
            return null;
        }

        /** 读取所有同一 field number 的 message 字段 */
        static List<byte[]> readRepeatedMessage(byte[] data, int fieldNumber) {
            List<byte[]> results = new ArrayList<>();
            int pos = 0;
            while (pos < data.length) {
                int[] tagResult = readVarint(data, pos);
                int tag = tagResult[0];
                pos = tagResult[1];
                int wireType = tag & 0x07;
                int field = tag >>> 3;

                if (wireType == 2) {
                    int[] lenResult = readVarint(data, pos);
                    int len = lenResult[0];
                    pos = lenResult[1];
                    if (field == fieldNumber) {
                        byte[] result = new byte[len];
                        System.arraycopy(data, pos, result, 0, len);
                        results.add(result);
                    }
                    pos += len;
                } else if (wireType == 0) {
                    int[] v = readVarint(data, pos);
                    pos = v[1];
                } else if (wireType == 5) {
                    pos += 4;
                } else if (wireType == 1) {
                    pos += 8;
                } else {
                    break;
                }
            }
            return results;
        }

        /** 读取 int32/uint32 varint 字段 */
        static int readInt32(byte[] data, int fieldNumber) {
            int pos = 0;
            while (pos < data.length) {
                int[] tagResult = readVarint(data, pos);
                int tag = tagResult[0];
                pos = tagResult[1];
                int wireType = tag & 0x07;
                int field = tag >>> 3;

                if (wireType == 0) {
                    int[] v = readVarint(data, pos);
                    pos = v[1];
                    if (field == fieldNumber) {
                        return v[0];
                    }
                } else if (wireType == 2) {
                    int[] lenResult = readVarint(data, pos);
                    pos = lenResult[1] + lenResult[0];
                } else if (wireType == 5) {
                    pos += 4;
                } else if (wireType == 1) {
                    pos += 8;
                } else {
                    break;
                }
            }
            return 0;
        }

        /**
         * 读取 packed repeated sint32 字段 (zigzag 编码)。
         * 也兼容非 packed 的逐个 varint 编码。
         */
        static List<Integer> readPackedSint32(byte[] data, int fieldNumber) {
            List<Integer> results = new ArrayList<>();
            int pos = 0;
            while (pos < data.length) {
                int[] tagResult = readVarint(data, pos);
                int tag = tagResult[0];
                pos = tagResult[1];
                int wireType = tag & 0x07;
                int field = tag >>> 3;

                if (field == fieldNumber) {
                    if (wireType == 2) {
                        // packed: length-delimited containing varints
                        int[] lenResult = readVarint(data, pos);
                        int len = lenResult[0];
                        pos = lenResult[1];
                        int end = pos + len;
                        while (pos < end) {
                            int[] v = readVarint(data, pos);
                            pos = v[1];
                            // zigzag decode: (n >>> 1) ^ -(n & 1)
                            int decoded = (v[0] >>> 1) ^ -(v[0] & 1);
                            results.add(decoded);
                        }
                    } else if (wireType == 0) {
                        // non-packed: single varint
                        int[] v = readVarint(data, pos);
                        pos = v[1];
                        int decoded = (v[0] >>> 1) ^ -(v[0] & 1);
                        results.add(decoded);
                    }
                } else {
                    if (wireType == 0) {
                        int[] v = readVarint(data, pos);
                        pos = v[1];
                    } else if (wireType == 2) {
                        int[] lenResult = readVarint(data, pos);
                        pos = lenResult[1] + lenResult[0];
                    } else if (wireType == 5) {
                        pos += 4;
                    } else if (wireType == 1) {
                        pos += 8;
                    } else {
                        break;
                    }
                }
            }
            return results;
        }

        /** 读取 varint，返回 [value, newPosition] */
        private static int[] readVarint(byte[] data, int pos) {
            int result = 0;
            int shift = 0;
            while (pos < data.length) {
                byte b = data[pos++];
                result |= (b & 0x7F) << shift;
                if ((b & 0x80) == 0) break;
                shift += 7;
            }
            return new int[]{result, pos};
        }
    }
}
