
package com.winlator.usbbridge;

import android.content.Context;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;

import java.util.Locale;

public class UsbBridgeProbe {

    private static final int VID = 0x0403;
    private static final int PID = 0xFA24;

    private static String hex(int value) {
        return String.format(
            Locale.US, "0x%02X", value);
    }

    private static String transferType(int type) {
        switch (type) {
            case UsbConstants.USB_ENDPOINT_XFER_CONTROL:
                return "CONTROL";
            case UsbConstants.USB_ENDPOINT_XFER_ISOC:
                return "ISOCHRONOUS";
            case UsbConstants.USB_ENDPOINT_XFER_BULK:
                return "BULK";
            case UsbConstants.USB_ENDPOINT_XFER_INT:
                return "INTERRUPT";
            default:
                return "UNKNOWN";
        }
    }

    public static String test(Context context) {
        UsbManager manager = (UsbManager)
            context.getSystemService(Context.USB_SERVICE);

        if (manager == null) {
            return "USB manager unavailable";
        }

        UsbDevice target = null;

        for (UsbDevice device :
                manager.getDeviceList().values()) {
            if (device.getVendorId() == VID &&
                device.getProductId() == PID) {
                target = device;
                break;
            }
        }

        if (target == null) {
            return "HEX-V2 not detected (0403:FA24)";
        }

        if (!manager.hasPermission(target)) {
            return "Cable detected - permission required";
        }

        StringBuilder result = new StringBuilder();

        result.append("USB BRIDGE v0.2\n");
        result.append("VID: 0403  PID: FA24\n");
        result.append("Interfaces: ")
              .append(target.getInterfaceCount())
              .append("\n");

        UsbDeviceConnection connection =
            manager.openDevice(target);

        if (connection == null) {
            result.append("USB open: FAILED");
            return result.toString();
        }

        result.append("USB open: OK\n");

        try {
            for (int i = 0;
                 i < target.getInterfaceCount(); i++) {

                UsbInterface iface = target.getInterface(i);

                result.append("\nInterface ")
                      .append(i)
                      .append("\nClass: ")
                      .append(hex(iface.getInterfaceClass()))
                      .append("\nSubclass: ")
                      .append(hex(iface.getInterfaceSubclass()))
                      .append("\nProtocol: ")
                      .append(hex(iface.getInterfaceProtocol()))
                      .append("\n");

                boolean hasIn = false;
                boolean hasOut = false;

                for (int j = 0;
                     j < iface.getEndpointCount(); j++) {

                    UsbEndpoint ep = iface.getEndpoint(j);

                    result.append("EP ")
                          .append(hex(ep.getAddress()))
                          .append(" ")
                          .append(transferType(ep.getType()))
                          .append(" MPS=")
                          .append(ep.getMaxPacketSize())
                          .append("\n");

                    if (ep.getType() ==
                            UsbConstants.USB_ENDPOINT_XFER_BULK) {
                        if (ep.getDirection() ==
                                UsbConstants.USB_DIR_IN) {
                            hasIn = true;
                        } else {
                            hasOut = true;
                        }
                    }
                }

                boolean claimed =
                    connection.claimInterface(iface, false);

                result.append("Claim: ")
                      .append(claimed ? "OK" : "FAILED")
                      .append("\n");

                if (claimed) {
                    connection.releaseInterface(iface);
                }

                if (hasIn && hasOut) {
                    result.append("Bulk IN/OUT: YES\n");
                }
            }

            result.append("\nRead-only inspection complete.");
            result.append("\nNo USB data transferred.");
            result.append("\nWine bridge: NOT IMPLEMENTED");

            return result.toString();

        } catch (Exception e) {
            result.append("\nInspection failed: ")
                  .append(e.getClass().getSimpleName());
            return result.toString();
        } finally {
            connection.close();
        }
    }
}
