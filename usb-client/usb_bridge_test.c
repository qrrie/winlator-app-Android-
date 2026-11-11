
#define WIN32_LEAN_AND_MEAN
#include <winsock2.h>
#include <ws2tcpip.h>
#include <windows.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

static int send_all(SOCKET s, const char *buf, int len) {
    while (len > 0) {
        int n = send(s, buf, len, 0);
        if (n <= 0) return 0;
        buf += n;
        len -= n;
    }
    return 1;
}

static int recv_all(SOCKET s, char *buf, int len) {
    while (len > 0) {
        int n = recv(s, buf, len, 0);
        if (n <= 0) return 0;
        buf += n;
        len -= n;
    }
    return 1;
}

static int read_int(SOCKET s, int *value) {
    unsigned char b[4];
    if (!recv_all(s, (char *)b, 4)) return 0;
    *value = (int)(
        ((unsigned)b[0] << 24) |
        ((unsigned)b[1] << 16) |
        ((unsigned)b[2] << 8) |
        (unsigned)b[3]
    );
    return 1;
}

int main(void) {
    WSADATA wsa;
    SOCKET sock = INVALID_SOCKET;
    struct sockaddr_in addr;
    char host[128], port_text[32], token[128];
    DWORD n;
    int port, status, length, i;
    unsigned char descriptor[65536];
    unsigned char auth[130];
    int result = 1;

    printf("Winlator USB Bridge Test\n");
    printf("========================\n");

    n = GetEnvironmentVariableA(
        "WINLATOR_USB_HOST", host, sizeof(host));
    if (!n || n >= sizeof(host)) {
        printf("ERROR: USB host variable missing\n");
        goto finish;
    }

    n = GetEnvironmentVariableA(
        "WINLATOR_USB_PORT", port_text,
        sizeof(port_text));
    if (!n || n >= sizeof(port_text)) {
        printf("ERROR: USB port variable missing\n");
        goto finish;
    }

    n = GetEnvironmentVariableA(
        "WINLATOR_USB_TOKEN", token, sizeof(token));
    if (!n || n >= sizeof(token)) {
        printf("ERROR: USB token variable missing\n");
        goto finish;
    }

    port = atoi(port_text);
    if (port < 1 || port > 65535) {
        printf("ERROR: Invalid port\n");
        goto finish;
    }

    if (WSAStartup(MAKEWORD(2,2), &wsa) != 0) {
        printf("ERROR: Winsock startup failed\n");
        goto finish;
    }

    sock = socket(AF_INET, SOCK_STREAM, IPPROTO_TCP);
    if (sock == INVALID_SOCKET) {
        printf("ERROR: Cannot create socket\n");
        goto cleanup;
    }

    memset(&addr, 0, sizeof(addr));
    addr.sin_family = AF_INET;
    addr.sin_port = htons((unsigned short)port);
    addr.sin_addr.s_addr = inet_addr(host);

    if (addr.sin_addr.s_addr == INADDR_NONE) {
        printf("ERROR: Invalid bridge address\n");
        goto cleanup;
    }

    if (connect(sock, (struct sockaddr *)&addr,
                sizeof(addr)) != 0) {
        printf("ERROR: Cannot connect to Android bridge\n");
        goto cleanup;
    }

    printf("Connected to Android bridge\n");

    /* Java DataInputStream.readUTF framing.
       Token is ASCII hexadecimal. */
    length = (int)strlen(token);
    if (length != 48) {
        printf("ERROR: Invalid token length\n");
        goto cleanup;
    }

    auth[0] = (unsigned char)(length >> 8);
    auth[1] = (unsigned char)length;
    memcpy(auth + 2, token, length);

    if (!send_all(sock, (char *)auth, length + 2)) {
        printf("ERROR: Authentication send failed\n");
        goto cleanup;
    }

    if (!read_int(sock, &status) ||
        !read_int(sock, &length)) {
        printf("ERROR: No authentication response\n");
        goto cleanup;
    }

    if (status != 0 || length != 0) {
        printf("ERROR: USB device unavailable: %d\n", status);
        goto cleanup;
    }

    printf("USB device opened successfully\n");

    /* Command 1: Read raw USB descriptors. */
    {
        unsigned char command = 1;
        if (!send_all(sock, (char *)&command, 1)) {
            printf("ERROR: Descriptor request failed\n");
            goto cleanup;
        }
    }

    if (!read_int(sock, &status) ||
        !read_int(sock, &length)) {
        printf("ERROR: No descriptor response\n");
        goto cleanup;
    }

    if (status < 0 || length < 0 ||
        length > (int)sizeof(descriptor)) {
        printf("ERROR: Descriptor status %d\n", status);
        goto cleanup;
    }

    if (length > 0 &&
        !recv_all(sock, (char *)descriptor, length)) {
        printf("ERROR: Incomplete descriptor data\n");
        goto cleanup;
    }

    printf("USB DESCRIPTOR RECEIVED: %d bytes\n", length);

    if (length >= 12) {
        unsigned vendor =
            descriptor[8] | (descriptor[9] << 8);
        unsigned product =
            descriptor[10] | (descriptor[11] << 8);

        printf("Vendor ID: %04X\n", vendor);
        printf("Product ID: %04X\n", product);

        if (vendor == 0x0403 && product == 0xFA24) {
            printf("RESULT: HEX USB DEVICE MATCHED\n");
            result = 0;
        } else {
            printf("RESULT: UNEXPECTED USB DEVICE\n");
        }
    } else {
        printf("ERROR: Descriptor too short\n");
    }

cleanup:
    if (sock != INVALID_SOCKET) closesocket(sock);
    WSACleanup();

finish:
    printf("\nPress Enter to close...\n");
    getchar();
    return result;
}
