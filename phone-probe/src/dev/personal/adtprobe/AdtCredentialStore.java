package dev.personal.adtprobe;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.Key;
import java.security.KeyStore;
import java.util.Arrays;
import java.util.UUID;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Phone-only credentials. Both identifiers and secrets are encrypted; nothing here logs them. */
final class AdtCredentialStore {
    static final String PREFERENCES = "adt_encrypted_credentials";
    private static final String KEY_ALIAS = "dev.personal.adtprobe.adt_credentials.v1";
    private static final String AAD_PREFIX = "ADT-CREDENTIALS/1:";
    static final int MAX_USERNAME_CHARS = 512, MAX_PASSWORD_CHARS = 4096;
    private static final int IV_BYTES = 12, TAG_BYTES = 16;
    private static final int MAX_PLAIN_BYTES = 12 + 4 * (MAX_USERNAME_CHARS + MAX_PASSWORD_CHARS);
    private static final int MAX_BLOB_CHARS = 4 * ((MAX_PLAIN_BYTES + TAG_BYTES + 2) / 3);
    private static boolean storageFailed;

    /** Only the key backend is replaceable in tests; encryption and format validation stay real. */
    interface KeySource {
        SecretKey existing() throws GeneralSecurityException, IOException;
        SecretKey create() throws GeneralSecurityException, IOException;
        void delete() throws GeneralSecurityException, IOException;
    }
    static KeySource keySource = new AndroidKeys();

    private AdtCredentialStore() { }

    static final class Credentials implements AutoCloseable {
        final String username;
        final char[] password;
        final String version;
        /** Takes ownership of password; closing clears this exact array. */
        Credentials(String username, char[] password, String version) {
            this.username = username; this.password = password; this.version = version;
        }
        @Override public void close() { Arrays.fill(password, '\0'); }
        @Override public String toString() { return "ADT credentials (redacted)"; }
    }

    static synchronized boolean configured(Context context) {
        try (Credentials value = load(context)) { return value != null; }
    }

    /** A successful replacement gets a new generation; the caller retains ownership of its input. */
    static synchronized boolean save(Context context, String username, char[] password) {
        if (!validUsername(username) || password == null || password.length == 0
                || password.length > MAX_PASSWORD_CHARS) return false;
        byte[] plain = null;
        boolean writing = false;
        try {
            plain = encode(username, password);
            SharedPreferences stored = preferences(context);
            SecretKey key = keySource.existing();
            // Restored/corrupt ciphertext cannot silently cause a replacement key to be generated.
            if (key == null) {
                if (!stored.getAll().isEmpty()) return false;
                key = keySource.create();
            }
            if (key == null) return false;
            String version = UUID.randomUUID().toString();
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            // Android Keystore generates a fresh random IV; callers must not supply one for encryption.
            cipher.init(Cipher.ENCRYPT_MODE, key);
            byte[] iv = cipher.getIV();
            if (iv == null || iv.length != IV_BYTES) return false;
            cipher.updateAAD(aad(version));
            byte[] blob = cipher.doFinal(plain);
            writing = true;
            boolean saved = stored.edit().clear()
                .putString("blob", Base64.encodeToString(blob, Base64.NO_WRAP))
                .putString("iv", Base64.encodeToString(iv, Base64.NO_WRAP))
                .putString("version", version).commit();
            // commit(false) can still alter SharedPreferences' memory cache. Never use that result.
            storageFailed = !saved;
            return saved;
        } catch (GeneralSecurityException | IOException | RuntimeException ignored) {
            if (writing) storageFailed = true;
            return false;
        } finally { wipe(plain); }
    }

    /** Returns null for missing, invalidated, malformed or unauthenticated material; never creates a key. */
    static synchronized Credentials load(Context context) {
        if (storageFailed) return null;
        byte[] plain = null;
        try {
            SharedPreferences stored = preferences(context);
            String version = stored.getString("version", "");
            String encodedIv = stored.getString("iv", ""), encodedBlob = stored.getString("blob", "");
            if (!uuid(version) || encodedIv.length() != 16 || encodedBlob.length() < 40
                    || encodedBlob.length() > MAX_BLOB_CHARS || stored.getAll().size() != 3) return null;
            byte[] iv = Base64.decode(encodedIv, Base64.NO_WRAP), blob = Base64.decode(encodedBlob, Base64.NO_WRAP);
            if (iv.length != IV_BYTES || blob.length < 30 || blob.length > MAX_PLAIN_BYTES + TAG_BYTES) return null;
            SecretKey key = keySource.existing();
            if (key == null) return null;
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BYTES * 8, iv));
            cipher.updateAAD(aad(version));
            plain = cipher.doFinal(blob);
            return decode(plain, version);
        } catch (GeneralSecurityException | IOException | RuntimeException ignored) {
            return null;
        } finally { wipe(plain); }
    }

    /** Cheap record-generation guard. Usability is established separately by configured()/load(). */
    static synchronized String version(Context context) {
        if (storageFailed) return "";
        try {
            SharedPreferences stored = preferences(context);
            String version = stored.getString("version", "");
            String iv = stored.getString("iv", ""), blob = stored.getString("blob", "");
            return uuid(version) && iv.length() == 16 && blob.length() >= 40
                && blob.length() <= MAX_BLOB_CHARS && stored.getAll().size() == 3 ? version : "";
        } catch (RuntimeException ignored) { return ""; }
    }

    /** Remove ciphertext and its key independently, so either successful deletion revokes the stored secret. */
    static synchronized void clear(Context context) {
        storageFailed = true;
        boolean erased = false, deleted = false;
        try { erased = preferences(context).edit().clear().commit(); }
        catch (RuntimeException ignored) { }
        try { keySource.delete(); deleted = true; }
        catch (GeneralSecurityException | IOException | RuntimeException ignored) { }
        storageFailed = !erased || !deleted;
    }

    private static SharedPreferences preferences(Context context) {
        Context app = context.getApplicationContext();
        // This is credential-encrypted storage: available after first unlock, including while relocked.
        if (context.isDeviceProtectedStorage() || app.isDeviceProtectedStorage())
            throw new IllegalArgumentException("Credential-encrypted storage required");
        return app.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
    }

    private static byte[] aad(String version) { return (AAD_PREFIX + version).getBytes(StandardCharsets.US_ASCII); }

    private static byte[] encode(String username, char[] password) throws CharacterCodingException {
        byte[] userBytes = null, passwordBytes = null;
        try {
            userBytes = utf8(CharBuffer.wrap(username));
            passwordBytes = utf8(CharBuffer.wrap(password));
            return ByteBuffer.allocate(12 + userBytes.length + passwordBytes.length)
                .putInt(1).putInt(userBytes.length).putInt(passwordBytes.length)
                .put(userBytes).put(passwordBytes).array();
        } finally { wipe(userBytes); wipe(passwordBytes); }
    }

    private static Credentials decode(byte[] plain, String version) throws CharacterCodingException {
        if (plain.length < 14 || plain.length > MAX_PLAIN_BYTES) return null;
        ByteBuffer bytes = ByteBuffer.wrap(plain);
        if (bytes.getInt() != 1) return null;
        int userLength = bytes.getInt(), passwordLength = bytes.getInt();
        if (userLength < 1 || userLength > 4 * MAX_USERNAME_CHARS || passwordLength < 1
                || passwordLength > 4 * MAX_PASSWORD_CHARS || bytes.remaining() != userLength + passwordLength) return null;
        CharBuffer user = null, secret = null;
        char[] password = null;
        try {
            user = utf8(ByteBuffer.wrap(plain, 12, userLength));
            secret = utf8(ByteBuffer.wrap(plain, 12 + userLength, passwordLength));
            String username = user.toString();
            if (!validUsername(username) || secret.remaining() == 0 || secret.remaining() > MAX_PASSWORD_CHARS) return null;
            password = new char[secret.remaining()]; secret.get(password);
            Credentials result = new Credentials(username, password, version);
            password = null; // The returned object now owns the only retained password array.
            return result;
        } finally {
            wipe(user); wipe(secret);
            if (password != null) Arrays.fill(password, '\0');
        }
    }

    private static byte[] utf8(CharBuffer input) throws CharacterCodingException {
        ByteBuffer bytes = StandardCharsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).encode(input);
        try {
            byte[] result = new byte[bytes.remaining()]; bytes.get(result); return result;
        } finally { if (bytes.hasArray()) wipe(bytes.array()); }
    }
    private static CharBuffer utf8(ByteBuffer input) throws CharacterCodingException {
        return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(input);
    }
    private static boolean validUsername(String username) {
        if (username == null || username.isEmpty() || username.length() > MAX_USERNAME_CHARS || username.trim().isEmpty()) return false;
        for (int i = 0; i < username.length(); i++) if (Character.isISOControl(username.charAt(i))) return false;
        return true;
    }
    private static boolean uuid(String value) {
        try { return UUID.fromString(value).toString().equals(value); }
        catch (IllegalArgumentException | NullPointerException ignored) { return false; }
    }
    private static void wipe(byte[] bytes) { if (bytes != null) Arrays.fill(bytes, (byte) 0); }
    private static void wipe(CharBuffer chars) { if (chars != null && chars.hasArray()) Arrays.fill(chars.array(), '\0'); }

    private static final class AndroidKeys implements KeySource {
        private KeyStore store() throws GeneralSecurityException, IOException {
            KeyStore result = KeyStore.getInstance("AndroidKeyStore"); result.load(null); return result;
        }
        @Override public SecretKey existing() throws GeneralSecurityException, IOException {
            KeyStore store = store();
            if (!store.containsAlias(KEY_ALIAS)) return null;
            Key key = store.getKey(KEY_ALIAS, null);
            if (!(key instanceof SecretKey)) throw new GeneralSecurityException("Credential key unavailable");
            return (SecretKey) key;
        }
        @Override public SecretKey create() throws GeneralSecurityException, IOException {
            if (store().containsAlias(KEY_ALIAS)) throw new GeneralSecurityException("Credential key already exists");
            KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
            generator.init(new KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true).setUserAuthenticationRequired(false)
                .setUnlockedDeviceRequired(false).build());
            return generator.generateKey();
        }
        @Override public void delete() throws GeneralSecurityException, IOException { store().deleteEntry(KEY_ALIAS); }
    }
}
