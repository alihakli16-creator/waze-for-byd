// SPDX-License-Identifier: GPL-3.0-only
// Ported from alihakli16-creator/maps-automotive-for-byd-patches (runtime/local/maps/cluster/VehicleLocalAdb.java)
package local.waze.cluster;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.security.spec.RSAPrivateCrtKeySpec;
import java.util.Arrays;
import java.util.Base64;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.function.BooleanSupplier;
import java.util.regex.Pattern;

/**
 * A single-purpose, bounded classic ADB transport to this head unit (127.0.0.1:5555) only.
 * Protocol and RSA public-key layout follow the Android Open Source Project.
 * The unique private key stays in the application's no-backup directory.
 */
public final class VehicleLocalAdb implements Closeable {
    private static final int VERSION = 0x01000000;
    private static final int MAX_PACKET = 4096, MAX_OUTPUT = 16384;
    private static final long HANDSHAKE_MS = 15000, COMMAND_MS = 25000;
    private static final ScheduledThreadPoolExecutor TIMEOUTS = newTimeouts();
    private static final int CNXN = command("CNXN"), AUTH = command("AUTH");
    private static final int OPEN = command("OPEN"), OKAY = command("OKAY");
    private static final int WRTE = command("WRTE"), CLSE = command("CLSE");
    private static final byte[] SHA1_DIGEST_INFO = {
        0x30,0x21,0x30,0x09,0x06,0x05,0x2b,0x0e,0x03,0x02,0x1a,0x05,0x00,0x04,0x14
    };

    /** Strict forbidden characters rejecting shell chaining, subshells, redirection and newlines. */
    private static final Pattern FORBIDDEN_CHARS = Pattern.compile("[;&|$\\\\()<>\r\n`]");

    /** Strict allowlist pattern for preflight checks and AutoContainer parcel commands only. */
    private static final Pattern STRICT_ALLOWED = Pattern.compile(
        "^(" +
        "am get-current-user|" +
        "id -u|" +
        "getprop ro\\.build\\.version\\.sdk|" +
        "getprop ro\\.build\\.display\\.id|" +
        "getprop ro\\.product\\.name|" +
        "service check AutoContainer|" +
        "pm path --user [0-9]{1,6} com\\.example\\.amapservice|" +
        "service call AutoContainer 2 i32 1000 i32 (?:16|17|18|35) s16 ''" +
        ")$"
    );

    private final Socket socket;
    private final DataInputStream input;
    private final java.io.OutputStream output;
    private final BooleanSupplier stillAllowed;
    private volatile boolean closed;
    private int nextLocalId = 1;
    private int completedLocalId, completedRemoteId;
    private volatile long deadline;
    private final Object timeoutLock = new Object();
    private long timeoutVersion;
    private ScheduledFuture<?> timeoutTask;

    public static final class ApprovalRequiredException extends IOException {
        public ApprovalRequiredException() { super("ADB approval required"); }
        ApprovalRequiredException(IOException cause) { super("ADB approval required", cause); }
    }

    public static final class ConnectionRefusedException extends IOException {
        public ConnectionRefusedException(String message) { super(message); }
        public ConnectionRefusedException(String message, Throwable cause) { super(message, cause); }
    }

    /** Must be called off the main thread, and only after the owner's opt-in. */
    public static VehicleLocalAdb open(File noBackupDirectory, boolean allowPublicKey,
                                       BooleanSupplier stillAllowed) throws IOException {
        try {
            Class<?> looperClass = Class.forName("android.os.Looper");
            Object myLooper = looperClass.getMethod("myLooper").invoke(null);
            Object mainLooper = looperClass.getMethod("getMainLooper").invoke(null);
            if (myLooper != null && myLooper.equals(mainLooper)) {
                throw new IllegalStateException("ADB network calls must not run on the UI thread");
            }
        } catch (ClassNotFoundException | NoSuchMethodException | IllegalAccessException | java.lang.reflect.InvocationTargetException ignored) {
            // Standalone JVM host test environment
        }
        requireAllowed(stillAllowed);
        KeyPair key = loadOrCreateKey(noBackupDirectory, stillAllowed);
        Socket socket = new Socket();
        try {
            requireAllowed(stillAllowed);
            socket.connect(new InetSocketAddress(InetAddress.getByAddress(new byte[]{127,0,0,1}), 5555), 3000);
            socket.setSoTimeout(5000);
            socket.setTcpNoDelay(true);
            return openConnected(socket, key, allowPublicKey, stillAllowed);
        } catch (ConnectException | SocketTimeoutException e) {
            try { socket.close(); } catch (IOException ignored) {}
            throw new ConnectionRefusedException("Araçta ağ ADB'yi etkinleştirin (127.0.0.1:5555 bağlantısı reddedildi) / Enable network ADB in the vehicle", e);
        } catch (IOException | RuntimeException e) {
            try { socket.close(); } catch (IOException ignored) {}
            throw e;
        }
    }

    // The socket/key seam is package-private and used by deterministic protocol tests.
    static VehicleLocalAdb openConnected(Socket socket, KeyPair key, boolean allowPublicKey,
                                         BooleanSupplier stillAllowed) throws IOException {
        VehicleLocalAdb client = new VehicleLocalAdb(socket, stillAllowed);
        try { client.handshake(key, allowPublicKey); return client; }
        catch (IOException | RuntimeException e) { client.close(); throw e; }
    }

    private VehicleLocalAdb(Socket socket, BooleanSupplier allowed) throws IOException {
        this.socket = socket; this.stillAllowed = allowed;
        input = new DataInputStream(socket.getInputStream()); output = socket.getOutputStream();
    }

    /** Validates that command strictly conforms to the bounded vehicle allowlist. */
    static boolean isCommandAllowed(String command) {
        if (command == null) return false;
        String trimmed = command.trim();
        if (FORBIDDEN_CHARS.matcher(trimmed).find()) return false;
        return STRICT_ALLOWED.matcher(trimmed).matches();
    }

    /** Runs a single command and returns its standard output. */
    public String execute(String command) throws IOException {
        requireAllowed(stillAllowed);
        if (!isCommandAllowed(command)) {
            throw new IllegalArgumentException("Command is not permitted by allowlist: " + command);
        }
        int localId = nextLocalId++;
        armTimeout(COMMAND_MS);
        try {
            writeMessage(OPEN, localId, 0, ("shell:" + command + "\0").getBytes(StandardCharsets.UTF_8));
            int remoteId = 0;
            ByteArrayOutputStream result = new ByteArrayOutputStream();
            while (true) {
                requireAllowed(stillAllowed);
                Message m = readMessage();
                if (m.command == OKAY) {
                    if (m.arg1 != localId) continue;
                    remoteId = m.arg0;
                } else if (m.command == WRTE) {
                    if (m.arg1 != localId) continue;
                    if (remoteId != 0 && m.arg0 != remoteId) throw new IOException("ADB stream id mismatch");
                    remoteId = m.arg0;
                    if (result.size() + m.data.length > MAX_OUTPUT) throw new IOException("ADB output exceeded limit");
                    result.write(m.data);
                    writeMessage(OKAY, localId, remoteId, new byte[0]);
                } else if (m.command == CLSE) {
                    if (m.arg1 != localId) continue;
                    completedLocalId = localId; completedRemoteId = remoteId;
                    return new String(result.toByteArray(), StandardCharsets.UTF_8);
                }
            }
        } finally {
            disarmTimeout();
        }
    }

    private void handshake(KeyPair key, boolean allowPublicKey) throws IOException {
        armTimeout(HANDSHAKE_MS);
        try {
            writeMessage(CNXN, VERSION, MAX_PACKET, "host::waze-for-byd\0".getBytes(StandardCharsets.UTF_8));
            Message response = readMessage();
            if (response.command == AUTH) {
                if (response.arg0 != 1) throw new IOException("Unexpected ADB AUTH type " + response.arg0);
                byte[] token = response.data;
                byte[] signature = signToken(key.getPrivate(), token);
                writeMessage(AUTH, 2, 0, signature);
                response = readMessage();
                if (response.command == AUTH && response.arg0 == 1) {
                    if (!allowPublicKey) throw new ApprovalRequiredException();
                    byte[] pubKey = formatPublicKey((RSAPublicKey) key.getPublic());
                    writeMessage(AUTH, 3, 0, pubKey);
                    response = readMessage();
                }
            }
            if (response.command != CNXN) throw new ApprovalRequiredException();
        } finally {
            disarmTimeout();
        }
    }

    private static byte[] signToken(PrivateKey privateKey, byte[] token) throws IOException {
        try {
            Signature rsa = Signature.getInstance("NONEwithRSA");
            rsa.initSign(privateKey);
            rsa.update(SHA1_DIGEST_INFO);
            rsa.update(token);
            return rsa.sign();
        } catch (GeneralSecurityException e) {
            throw new IOException("Failed to sign ADB token", e);
        }
    }

    static byte[] formatPublicKey(RSAPublicKey key) {
        BigInteger n = key.getModulus();
        BigInteger r = BigInteger.valueOf(2).pow(2048);
        BigInteger rr = r.multiply(r).mod(n);
        BigInteger n0inv = n.modInverse(BigInteger.valueOf(2).pow(32)).negate();
        int n0 = n0inv.intValue();

        ByteBuffer buf = ByteBuffer.allocate(524).order(ByteOrder.LITTLE_ENDIAN);
        buf.putInt(64); // 2048 / 32
        buf.putInt(n0);
        putLittleEndian(buf, n, 64);
        putLittleEndian(buf, rr, 64);
        buf.putInt(key.getPublicExponent().intValue());

        byte[] base64 = Base64.getEncoder().encode(buf.array());
        byte[] user = " waze@car\0".getBytes(StandardCharsets.UTF_8);
        byte[] result = new byte[base64.length + user.length];
        System.arraycopy(base64, 0, result, 0, base64.length);
        System.arraycopy(user, 0, result, base64.length, user.length);
        return result;
    }

    private static void putLittleEndian(ByteBuffer buf, BigInteger val, int words) {
        byte[] raw = val.toByteArray();
        int offset = 0;
        while (offset < raw.length && raw[offset] == 0) offset++;
        int len = raw.length - offset;
        byte[] le = new byte[words * 4];
        for (int i = 0; i < len; i++) {
            le[i] = raw[raw.length - 1 - i];
        }
        buf.put(le);
    }

    private void writeMessage(int command, int arg0, int arg1, byte[] data) throws IOException {
        ByteBuffer buf = ByteBuffer.allocate(24 + data.length).order(ByteOrder.LITTLE_ENDIAN);
        buf.putInt(command);
        buf.putInt(arg0);
        buf.putInt(arg1);
        buf.putInt(data.length);
        buf.putInt(checksum(data));
        buf.putInt(command ^ 0xFFFFFFFF);
        buf.put(data);
        output.write(buf.array());
        output.flush();
    }

    private Message readMessage() throws IOException {
        byte[] header = new byte[24];
        input.readFully(header);
        ByteBuffer buf = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);
        int command = buf.getInt();
        int arg0 = buf.getInt();
        int arg1 = buf.getInt();
        int length = buf.getInt();
        int check = buf.getInt();
        int magic = buf.getInt();
        if ((command ^ 0xFFFFFFFF) != magic) throw new IOException("ADB header magic mismatch");
        if (length < 0 || length > MAX_PACKET) throw new IOException("Invalid ADB packet length: " + length);
        byte[] data = new byte[length];
        input.readFully(data);
        if (checksum(data) != check) throw new IOException("ADB checksum mismatch");
        return new Message(command, arg0, arg1, data);
    }

    private static int checksum(byte[] data) {
        int sum = 0;
        for (byte b : data) sum += (b & 0xFF);
        return sum;
    }

    private static int command(String s) {
        return (s.charAt(0) & 0xFF) | ((s.charAt(1) & 0xFF) << 8) |
               ((s.charAt(2) & 0xFF) << 16) | ((s.charAt(3) & 0xFF) << 24);
    }

    private static final class Message {
        final int command, arg0, arg1;
        final byte[] data;
        Message(int command, int arg0, int arg1, byte[] data) {
            this.command = command; this.arg0 = arg0; this.arg1 = arg1; this.data = data;
        }
    }

    private static KeyPair loadOrCreateKey(File dir, BooleanSupplier allowed) throws IOException {
        File privFile = new File(dir, "waze_adb_key");
        File pubFile = new File(dir, "waze_adb_key.pub");
        if (privFile.exists() && pubFile.exists()) {
            try { return loadKey(privFile, pubFile); }
            catch (Exception ignored) { /* regenerate if corrupt */ }
        }
        requireAllowed(allowed);
        try {
            KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
            kpg.initialize(2048);
            KeyPair kp = kpg.generateKeyPair();
            saveKey(privFile, pubFile, kp);
            return kp;
        } catch (GeneralSecurityException e) {
            throw new IOException("Failed to generate RSA key", e);
        }
    }

    private static KeyPair loadKey(File privFile, File pubFile) throws IOException, GeneralSecurityException {
        byte[] privBytes = new byte[(int) privFile.length()];
        try (FileInputStream in = new FileInputStream(privFile)) {
            new DataInputStream(in).readFully(privBytes);
        }
        KeyFactory kf = KeyFactory.getInstance("RSA");
        PrivateKey priv = kf.generatePrivate(new PKCS8EncodedKeySpec(privBytes));

        byte[] pubBytes = new byte[(int) pubFile.length()];
        try (FileInputStream in = new FileInputStream(pubFile)) {
            new DataInputStream(in).readFully(pubBytes);
        }
        RSAPrivateCrtKeySpec privCrt = (RSAPrivateCrtKeySpec) kf.getKeySpec(priv, RSAPrivateCrtKeySpec.class);
        RSAPublicKeySpec pubSpec = new RSAPublicKeySpec(privCrt.getModulus(), privCrt.getPublicExponent());
        return new KeyPair(kf.generatePublic(pubSpec), priv);
    }

    private static void saveKey(File privFile, File pubFile, KeyPair kp) throws IOException {
        try (FileOutputStream out = new FileOutputStream(privFile)) {
            out.write(kp.getPrivate().getEncoded());
        }
        try (FileOutputStream out = new FileOutputStream(pubFile)) {
            out.write(kp.getPublic().getEncoded());
        }
    }

    private static void requireAllowed(BooleanSupplier allowed) throws IOException {
        if (allowed != null && !allowed.getAsBoolean()) throw new IOException("Operation cancelled");
    }

    private static ScheduledThreadPoolExecutor newTimeouts() {
        ScheduledThreadPoolExecutor pool = new ScheduledThreadPoolExecutor(1, r -> {
            Thread t = new Thread(r, "WazeLocalAdbTimeout");
            t.setDaemon(true);
            return t;
        });
        pool.setRemoveOnCancelPolicy(true);
        return pool;
    }

    private void armTimeout(long ms) {
        synchronized (timeoutLock) {
            final long v = ++timeoutVersion;
            deadline = System.currentTimeMillis() + ms;
            if (timeoutTask != null) timeoutTask.cancel(false);
            timeoutTask = TIMEOUTS.schedule(() -> {
                synchronized (timeoutLock) {
                    if (timeoutVersion == v && !closed) close();
                }
            }, ms, TimeUnit.MILLISECONDS);
        }
    }

    private void disarmTimeout() {
        synchronized (timeoutLock) {
            timeoutVersion++;
            if (timeoutTask != null) { timeoutTask.cancel(false); timeoutTask = null; }
        }
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        disarmTimeout();
        try {
            if (completedLocalId != 0 && completedRemoteId != 0) {
                writeMessage(CLSE, completedLocalId, completedRemoteId, new byte[0]);
            }
        } catch (Exception ignored) {}
        try { socket.close(); } catch (Exception ignored) {}
    }
}
