package fq.router2;

import android.app.PendingIntent;
import android.content.Intent;
import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.net.VpnService;
import android.os.ParcelFileDescriptor;
import fq.router2.feedback.HandleFatalErrorIntent;
import fq.router2.life_cycle.ExitService;
import fq.router2.utils.LogUtils;

import java.io.*;
import java.net.*;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class SocksVpnService extends VpnService {

    private static ParcelFileDescriptor tunPFD;
    private Set<String> skippedFds = new HashSet<String>();
    private Set<Integer> stagingFds = new HashSet<Integer>();

    @Override
    public void onStart(Intent intent, int startId) {
        startVpn();
        LogUtils.i("on start");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startVpn();
        return START_STICKY;
    }

    @Override
    public void onRevoke() {
        stopSelf();
    }

    @Override
    public void onDestroy() {
        stopVpn();
    }

    private void startVpn() {
        try {
            if (tunPFD != null) {
                throw new RuntimeException("another VPN is still running");
            }
            Intent statusActivityIntent = new Intent(this, MainActivity.class);
            PendingIntent pIntent = PendingIntent.getActivity(this, 0, statusActivityIntent, 0);
            tunPFD = new Builder()
                    .setConfigureIntent(pIntent)
                    .setSession("fqrouter2")
                    .addAddress("10.25.1.1", 24)
                    .addRoute("0.0.0.0", 0)
                    .addDnsServer("8.8.8.8")
                    .establish();
            if (tunPFD == null) {
                stopSelf();
                return;
            }
            final int tunFD = tunPFD.getFd();
            LogUtils.i("tunFD is " + tunFD);
            LogUtils.i("Started in VPN mode");
            sendBroadcast(new SocksVpnConnectedIntent());
            new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        listenFdServerSocket(tunPFD.getFileDescriptor());
                    } catch (Exception e) {
                        LogUtils.e("fdsock failed " + e, e);
                    }
                }
            }).start();
        } catch (Exception e) {
            handleFatalError(LogUtils.e("VPN establish failed", e));
        }
    }

    private void listenFdServerSocket(final FileDescriptor tunFD) throws Exception {
        final LocalServerSocket fdServerSocket = new LocalServerSocket("fdsock2");
        try {
            ExecutorService executorService = Executors.newFixedThreadPool(16);
            int count = 0;
            while (isRunning()) {
                try {
                    final LocalSocket fdSocket = fdServerSocket.accept();
                    executorService.submit(new Runnable() {
                        @Override
                        public void run() {
                            try {
                                passFileDescriptor(fdSocket, tunFD);
                            } catch (Exception e) {
                                LogUtils.e("failed to handle fdsock", e);
                            }
                        }
                    });
                    count += 1;
                    if (count % 100 == 0) {
                        garbageCollectFds();
                    }
                } catch (Exception e) {
                    LogUtils.e("failed to handle fdsock", e);
                }
            }
            executorService.shutdown();
        } finally {
            fdServerSocket.close();
        }
    }

    private void garbageCollectFds() {
        if (listFds() == null) {
            LogUtils.e("can not gc fd as can not list them");
        } else {
            if (skippedFds.isEmpty()) {
                initSkippedFds();
            } else {
                closeStagingFds();
            }
        }
    }

    private String[] listFds() {
        String[] fds = new File("/proc/" + android.os.Process.myPid() + "/fd").list();
        if (null != fds) {
            return fds;
        }
        return new File("/proc/self/fd").list();
    }

    private void initSkippedFds() {
        String[] fileNames = listFds();
        LogUtils.i("init skipped fd count: " + fileNames.length);
        Collections.addAll(skippedFds, fileNames);
    }

    private void closeStagingFds() {
        int count = 0;
        for (int stagingFd : stagingFds) {
            try {
                if (isSocket(stagingFd)) {
                    ParcelFileDescriptor.adoptFd(stagingFd).close();
                    count += 1;
                }
            } catch (Exception e) {
                // ignore
            }
        }
        LogUtils.i("closed fd count: " + count);
        stagingFds.clear();
        String[] fileNames = listFds();
        LogUtils.i("current total fd count: " + fileNames.length);
        for (String fileName : fileNames) {
            if (skippedFds.contains(fileName)) {
                continue;
            }
            try {
                if (isSocket(fileName)) {
                    stagingFds.add(Integer.parseInt(fileName));
                }
            } catch (Exception e) {
                skippedFds.add(fileName);
                continue;
            }
        }
    }

    private boolean isSocket(Object fileName) throws IOException {
        return new File("/proc/self/fd/" + fileName).getCanonicalPath().contains("socket:");
    }

    public static boolean isRunning() {
        return tunPFD != null;
    }

    private void passFileDescriptor(LocalSocket fdSocket, FileDescriptor tunFD) throws Exception {
        OutputStream outputStream = fdSocket.getOutputStream();
        InputStream inputStream = fdSocket.getInputStream();
        try {
            BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream), 1);
            String request = reader.readLine();
            String[] parts = request.split(",");
            if ("TUN".equals(parts[0])) {
                fdSocket.setFileDescriptorsForSend(new FileDescriptor[]{tunFD});
                outputStream.write('*');
            } else if ("PING".equals(parts[0])) {
                OutputStreamWriter outputStreamWriter = new OutputStreamWriter(outputStream);
                outputStreamWriter.write("PONG");
                outputStreamWriter.close();
            } else if ("OPEN UDP".equals(parts[0])) {
                passUdpFileDescriptor(fdSocket, outputStream);
            } else if ("OPEN TCP".equals(parts[0])) {
                String dstIp = parts[1];
                int dstPort = Integer.parseInt(parts[2]);
                int connectTimeout = Integer.parseInt(parts[3]);
                passTcpFileDescriptor(fdSocket, outputStream, dstIp, dstPort, connectTimeout);
            } else {
                throw new UnsupportedOperationException("fdsock unable to handle: " + request);
            }
        } finally {
            try {
                inputStream.close();
            } catch (Exception e) {
                LogUtils.e("failed to close input stream", e);
            }
            try {
                outputStream.close();
            } catch (Exception e) {
                LogUtils.e("failed to close output stream", e);
            }
            fdSocket.close();
        }
    }

    private void passTcpFileDescriptor(
            LocalSocket fdSocket, OutputStream outputStream,
            String dstIp, int dstPort, int connectTimeout) throws Exception {
        Socket sock = new Socket();
        sock.setTcpNoDelay(true); // force file descriptor being created
        try {
            ParcelFileDescriptor fd = ParcelFileDescriptor.fromSocket(sock);
            if (protect(fd.getFd())) {
                try {
                    sock.connect(new InetSocketAddress(dstIp, dstPort), connectTimeout);
                    try {
                        fdSocket.setFileDescriptorsForSend(new FileDescriptor[]{fd.getFileDescriptor()});
                        outputStream.write('*');
                        outputStream.flush();
                    } finally {
                        sock.close();
                        fd.close();
                    }
                } catch (ConnectException e) {
                    LogUtils.e("connect " + dstIp + ":" + dstPort + " failed");
                    outputStream.write('!');
                } catch (SocketTimeoutException e) {
                    LogUtils.e("connect " + dstIp + ":" + dstPort + " failed");
                    outputStream.write('!');
                } finally {
                    outputStream.flush();
                }
            } else {
                LogUtils.e("protect tcp socket failed");
            }
        } finally {
            sock.close();
        }
    }

    private void passUdpFileDescriptor(LocalSocket fdSocket, OutputStream outputStream) throws Exception {
        DatagramSocket sock = new DatagramSocket();
        try {
            ParcelFileDescriptor fd = ParcelFileDescriptor.fromDatagramSocket(sock);
            if (protect(fd.getFd())) {
                try {
                    fdSocket.setFileDescriptorsForSend(new FileDescriptor[]{fd.getFileDescriptor()});
                    outputStream.write('*');
                    outputStream.flush();
                } finally {
                    sock.close();
                    fd.close();
                }
            } else {
                LogUtils.e("protect udp socket failed");
            }
        } finally {
            sock.close();
        }
    }


    private void stopVpn() {
        if (tunPFD != null) {
            try {
                tunPFD.close();
            } catch (IOException e) {
                LogUtils.e("failed to stop tunPFD", e);
            }
            tunPFD = null;
        }
        ExitService.execute(this);
    }

    private void handleFatalError(String message) {
        sendBroadcast(new HandleFatalErrorIntent(message));
    }
}

