
package com.winlator.usbbridge;

import android.content.Context;
import android.hardware.usb.*;
import android.util.Log;
import java.io.*;
import java.net.*;
import java.security.SecureRandom;
import java.util.Locale;

public final class UsbBridgeServer implements AutoCloseable {
    private static final String TAG = "WinlatorUsbBridge";
    private static final int VID = 0x0403;
    private static final int PID = 0xFA24;
    private static final int MAX = 65536;

    private final UsbManager manager;
    private final String token;
    private ServerSocket server;
    private Thread worker;
    private volatile boolean running;
    private volatile Socket active;

    public UsbBridgeServer(Context context) {
        manager = (UsbManager) context.getSystemService(
            Context.USB_SERVICE
        );
        byte[] bytes = new byte[24];
        new SecureRandom().nextBytes(bytes);
        StringBuilder s = new StringBuilder();
        for (byte b : bytes) {
            s.append(String.format(
                Locale.US, "%02x", b & 255
            ));
        }
        token = s.toString();
    }

    public synchronized int start() throws IOException {
        if (running) return server.getLocalPort();

        server = new ServerSocket();
        server.bind(new InetSocketAddress(
            InetAddress.getByName("127.0.0.1"), 0
        ));
        server.setSoTimeout(1000);
        running = true;

        worker = new Thread(
            this::acceptLoop, "usb-bridge-server"
        );
        worker.setDaemon(true);
        worker.start();
        return server.getLocalPort();
    }

    public String token() {
        return token;
    }

    private UsbDevice findDevice() {
        if (manager == null) return null;
        for (UsbDevice d : manager.getDeviceList().values()) {
            if (d.getVendorId() == VID
                    && d.getProductId() == PID) {
                return d;
            }
        }
        return null;
    }

    private void acceptLoop() {
        while (running) {
            try {
                Socket socket = server.accept();
                active = socket;
                socket.setSoTimeout(5000);
                try {
                    serve(socket);
                } finally {
                    socket.close();
                    active = null;
                }
            } catch (SocketTimeoutException ignored) {
            } catch (IOException e) {
                if (running) {
                    Log.w(TAG, "Client/server I/O: "
                        + e.getClass().getSimpleName());
                }
            }
        }
    }

    private static void reply(
            DataOutputStream out,
            int status,
            byte[] data,
            int size) throws IOException {
        out.writeInt(status);
        out.writeInt(size);
        if (size > 0) out.write(data, 0, size);
        out.flush();
    }

    private void serve(Socket socket) throws IOException {
        DataInputStream in = new DataInputStream(
            new BufferedInputStream(socket.getInputStream())
        );
        DataOutputStream out = new DataOutputStream(
            new BufferedOutputStream(socket.getOutputStream())
        );

        if (!token.equals(in.readUTF())) return;

        UsbDevice device = findDevice();
        if (device == null || !manager.hasPermission(device)) {
            reply(out, -3, new byte[0], 0);
            return;
        }

        UsbDeviceConnection connection =
            manager.openDevice(device);

        if (connection == null) {
            reply(out, -3, new byte[0], 0);
            return;
        }

        boolean[] claimed =
            new boolean[device.getInterfaceCount()];

        try {
            for (int i = 0; i < claimed.length; i++) {
                claimed[i] = connection.claimInterface(
                    device.getInterface(i), false
                );
            }

            reply(out, 0, new byte[0], 0);

            while (running) {
                int command;
                try {
                    command = in.readUnsignedByte();
                } catch (EOFException e) {
                    break;
                }

                if (findDevice() == null) {
                    reply(out, -3, new byte[0], 0);
                    break;
                }

                if (command == 1) {
                    byte[] desc =
                        connection.getRawDescriptors();
                    if (desc == null) desc = new byte[0];
                    reply(out, desc.length, desc, desc.length);

                } else if (command == 2 || command == 3) {
                    int address = in.readInt();
                    int length = in.readInt();
                    int timeout = in.readInt();

                    if (length < 0 || length > MAX
                            || timeout < 1 || timeout > 10000) {
                        reply(out, -2, new byte[0], 0);
                        break;
                    }

                    byte[] buffer = new byte[length];
                    if (command == 3) in.readFully(buffer);

                    UsbEndpoint endpoint = null;
                    for (int i = 0;
                            i < device.getInterfaceCount(); i++) {
                        if (!claimed[i]) continue;
                        UsbInterface iface =
                            device.getInterface(i);

                        for (int j = 0;
                                j < iface.getEndpointCount(); j++) {
                            UsbEndpoint ep = iface.getEndpoint(j);
                            if (ep.getAddress() == address
                                    && ep.getType()
                                    == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                                endpoint = ep;
                            }
                        }
                    }

                    if (endpoint == null
                            || (command == 2
                                && endpoint.getDirection()
                                != UsbConstants.USB_DIR_IN)
                            || (command == 3
                                && endpoint.getDirection()
                                != UsbConstants.USB_DIR_OUT)) {
                        reply(out, -2, new byte[0], 0);
                        continue;
                    }

                    int n = connection.bulkTransfer(
                        endpoint, buffer, length, timeout
                    );
                    reply(out, n, buffer,
                        command == 2 && n > 0 ? n : 0);

                } else if (command == 4) {
                    int type = in.readInt();
                    int request = in.readInt();
                    int value = in.readInt();
                    int index = in.readInt();
                    int length = in.readInt();
                    int timeout = in.readInt();

                    if (length < 0 || length > MAX
                            || timeout < 1 || timeout > 10000
                            || (type & ~255) != 0
                            || (request & ~255) != 0) {
                        reply(out, -2, new byte[0], 0);
                        break;
                    }

                    byte[] buffer = new byte[length];
                    boolean read =
                        (type & UsbConstants.USB_DIR_IN) != 0;

                    if (!read) in.readFully(buffer);

                    int n = connection.controlTransfer(
                        type, request, value, index,
                        buffer, length, timeout
                    );
                    reply(out, n, buffer,
                        read && n > 0 ? n : 0);

                } else {
                    reply(out, -2, new byte[0], 0);
                    break;
                }
            }
        } finally {
            for (int i = 0; i < claimed.length; i++) {
                if (claimed[i]) {
                    connection.releaseInterface(
                        device.getInterface(i)
                    );
                }
            }
            connection.close();
        }
    }

    @Override
    public synchronized void close() {
        running = false;
        try {
            if (active != null) active.close();
        } catch (IOException ignored) {}

        try {
            if (server != null) server.close();
        } catch (IOException ignored) {}

        if (worker != null) worker.interrupt();
    }
}
