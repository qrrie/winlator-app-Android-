
package com.winlator.usbbridge;

import android.content.Context;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDeviceConnection;

public class UsbBridgeProbe {

    private static final int VID = 0x0403;
    private static final int PID = 0xFA24;

    public static String test(Context context) {
        UsbManager manager = (UsbManager)
            context.getSystemService(Context.USB_SERVICE);

        if (manager == null) {
            return "USB manager unavailable";
        }

        for (UsbDevice device :
                manager.getDeviceList().values()) {

            if (device.getVendorId() != VID ||
                device.getProductId() != PID) {
                continue;
            }

            if (!manager.hasPermission(device)) {
                return "Cable detected - USB permission required";
            }

            UsbDeviceConnection connection =
                manager.openDevice(device);

            if (connection == null) {
                return "Cable detected - USB open failed";
            }

            try {
                for (int i = 0;
                     i < device.getInterfaceCount(); i++) {

                    UsbInterface iface = device.getInterface(i);
                    boolean bulkIn = false;
                    boolean bulkOut = false;

                    for (int j = 0;
                         j < iface.getEndpointCount(); j++) {

                        UsbEndpoint ep = iface.getEndpoint(j);

                        if (ep.getType() !=
                            UsbConstants.USB_ENDPOINT_XFER_BULK) {
                            continue;
                        }

                        if (ep.getAddress() == 0x81) bulkIn = true;
                        if (ep.getAddress() == 0x02) bulkOut = true;
                    }

                    if (bulkIn && bulkOut) {
                        if (connection.claimInterface(iface, false)) {
                            connection.releaseInterface(iface);
                            return "USB ACCESS OK - HEX-USB detected";
                        }
                        return "USB interface claim failed";
                    }
                }

                return "Expected USB endpoints not found";

            } finally {
                connection.close();
            }
        }

        return "HEX-USB cable not detected";
    }
}
