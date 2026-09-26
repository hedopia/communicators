package com.sds.communicators.cluster;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** One-directional TCP proxy on loopback from one node to another: cut() drops live connections and resets new ones. */
final class Link {
    private final ServerSocket server;
    private final int targetPort;
    private final Set<Socket> live = ConcurrentHashMap.newKeySet();
    private volatile boolean cut = false;

    Link(int targetPort) throws Exception {
        this.targetPort = targetPort;
        server = new ServerSocket(0, 200, InetAddress.getLoopbackAddress());
        var thread = new Thread(this::acceptLoop, "link->" + targetPort);
        thread.setDaemon(true);
        thread.start();
    }

    int port() {
        return server.getLocalPort();
    }

    void setCut(boolean cut) {
        this.cut = cut;
        if (cut) {
            for (var socket : live) {
                try {
                    socket.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    private void acceptLoop() {
        while (!server.isClosed()) {
            try {
                Socket in = server.accept();
                if (cut) {
                    reset(in);
                    continue;
                }
                Socket out;
                try {
                    out = new Socket(InetAddress.getLoopbackAddress(), targetPort);
                } catch (Exception e) {
                    reset(in);
                    continue;
                }
                live.add(in);
                live.add(out);
                pipe(in, out);
                pipe(out, in);
            } catch (Exception e) {
                if (server.isClosed())
                    return;
            }
        }
    }

    private static void reset(Socket socket) throws Exception {
        socket.setSoLinger(true, 0);
        socket.close();
    }

    private void pipe(Socket from, Socket to) {
        var thread = new Thread(() -> {
            try (InputStream input = from.getInputStream(); OutputStream output = to.getOutputStream()) {
                byte[] buffer = new byte[16384];
                int n;
                while ((n = input.read(buffer)) >= 0) {
                    if (cut)
                        break;
                    output.write(buffer, 0, n);
                    output.flush();
                }
            } catch (Exception ignored) {
            } finally {
                try {
                    from.close();
                } catch (Exception ignored) {
                }
                try {
                    to.close();
                } catch (Exception ignored) {
                }
                live.remove(from);
                live.remove(to);
            }
        }, "link-pipe");
        thread.setDaemon(true);
        thread.start();
    }

    void close() {
        try {
            server.close();
        } catch (Exception ignored) {
        }
        setCut(true);
    }
}
