package dev.personal.adtprobe;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.util.Base64;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.Map;
import java.util.UUID;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** Real AES-GCM over inert fixture text and software test keys; no Android keystore, network or account. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public final class AdtCredentialStoreTest {
    private static final String USERNAME = "inert-login-username@example.invalid";
    private static final char[] PASSWORD = "inert-only-passphrase-\u00e9-\ud83d\udd12".toCharArray();
    private Context context;
    private SharedPreferences preferences;
    private AdtCredentialStore.KeySource original;
    private Keys keys;

    @Before public void prepare() {
        context = RuntimeEnvironment.getApplication();
        preferences = context.getSharedPreferences(AdtCredentialStore.PREFERENCES, Context.MODE_PRIVATE);
        original = AdtCredentialStore.keySource;
        keys = new Keys(); AdtCredentialStore.keySource = keys;
        AdtCredentialStore.clear(context);
    }
    @After public void restore() {
        keys.fail = false;
        AdtCredentialStore.clear(context);
        AdtCredentialStore.keySource = original;
    }

    @Test public void roundTripEncryptsBothFieldsAndClosingZeroesOnlyReturnedPassword() {
        char[] input = PASSWORD.clone();
        assertTrue(AdtCredentialStore.save(context, USERNAME, input));
        assertArrayEquals("The caller still owns its input", PASSWORD, input);
        assertTrue(AdtCredentialStore.configured(context));
        Map<String, ?> values = preferences.getAll();
        assertEquals(3, values.size());
        assertTrue(values.keySet().containsAll(Arrays.asList("blob", "iv", "version")));
        assertFalse(values.toString().contains(USERNAME));
        assertFalse(values.toString().contains(new String(PASSWORD)));
        byte[] ciphertext = Base64.decode(preferences.getString("blob", ""), Base64.NO_WRAP);
        assertFalse(new String(ciphertext, StandardCharsets.UTF_8).contains(USERNAME));
        assertFalse(new String(ciphertext, StandardCharsets.UTF_8).contains(new String(PASSWORD)));
        assertEquals(12, Base64.decode(preferences.getString("iv", ""), Base64.NO_WRAP).length);
        AdtCredentialStore.Credentials loaded = AdtCredentialStore.load(context);
        assertNotNull(loaded);
        assertEquals(USERNAME, loaded.username); assertArrayEquals(PASSWORD, loaded.password);
        assertEquals(AdtCredentialStore.version(context), loaded.version);
        assertEquals("ADT credentials (redacted)", loaded.toString());
        loaded.close(); loaded.close();
        assertArrayEquals(new char[PASSWORD.length], loaded.password);
        assertArrayEquals(PASSWORD, input);
        try (AdtCredentialStore.Credentials again = AdtCredentialStore.load(context)) {
            assertNotNull(again); assertArrayEquals(PASSWORD, again.password);
        }
    }

    @Test public void identicalSavesUseIndependentIvsAndNewVersions() {
        assertTrue(AdtCredentialStore.save(context, USERNAME, PASSWORD));
        String firstIv = preferences.getString("iv", ""), firstBlob = preferences.getString("blob", "");
        String firstVersion = AdtCredentialStore.version(context);
        assertTrue(AdtCredentialStore.save(context, USERNAME, PASSWORD));
        assertNotEquals(firstIv, preferences.getString("iv", ""));
        assertNotEquals(firstBlob, preferences.getString("blob", ""));
        assertNotEquals(firstVersion, AdtCredentialStore.version(context));
        assertEquals("Overwriting does not replace an existing encryption key", 1, keys.created);
    }

    @Test public void deviceProtectedContextsCannotStoreOrReadCredentials() {
        assertTrue(AdtCredentialStore.save(context, USERNAME, PASSWORD));
        Context deviceProtected = new ContextWrapper(context) {
            @Override public boolean isDeviceProtectedStorage() { return true; }
        };
        assertFalse(AdtCredentialStore.save(deviceProtected, USERNAME, PASSWORD));
        assertNull(AdtCredentialStore.load(deviceProtected));
        assertEquals("", AdtCredentialStore.version(deviceProtected));
        assertTrue("The original credential-encrypted record is unchanged", AdtCredentialStore.configured(context));
    }

    @Test public void overwriteAndClearRevokeOldCredentialsAndCiphertext() {
        assertTrue(AdtCredentialStore.save(context, USERNAME, PASSWORD));
        Map<String, ?> old = preferences.getAll();
        String first = AdtCredentialStore.version(context);
        assertTrue(AdtCredentialStore.save(context, "replacement@example.invalid", new char[]{'n', 'e', 'w'}));
        assertNotEquals(first, AdtCredentialStore.version(context));
        try (AdtCredentialStore.Credentials value = AdtCredentialStore.load(context)) {
            assertEquals("replacement@example.invalid", value.username); assertArrayEquals(new char[]{'n', 'e', 'w'}, value.password);
        }
        AdtCredentialStore.clear(context);
        assertTrue(preferences.getAll().isEmpty()); assertNull(keys.key);
        assertEquals("", AdtCredentialStore.version(context));
        unavailable();
        restoreEnvelope(old);
        unavailable();
        assertFalse("An old ciphertext cannot cause a new key to replace its missing key", AdtCredentialStore.save(context, USERNAME, PASSWORD));
        assertEquals(1, keys.created);
        AdtCredentialStore.clear(context);
        assertTrue(AdtCredentialStore.save(context, USERNAME, PASSWORD));
        assertEquals(2, keys.created); assertNotEquals(first, AdtCredentialStore.version(context));
    }

    @Test public void tamperingWithCiphertextIvOrVersionFailsAuthentication() {
        assertTrue(AdtCredentialStore.save(context, USERNAME, PASSWORD));
        Map<String, ?> good = preferences.getAll();
        for (String field : new String[]{"blob", "iv"}) {
            byte[] bytes = Base64.decode((String) good.get(field), Base64.NO_WRAP); bytes[0] ^= 1;
            preferences.edit().putString(field, Base64.encodeToString(bytes, Base64.NO_WRAP)).commit();
            unavailable(); restoreEnvelope(good);
        }
        preferences.edit().putString("version", UUID.randomUUID().toString()).commit();
        unavailable();
        assertEquals("No read creates or replaces a key", 1, keys.created);
    }

    @Test public void missingInvalidatedAndWrongKeysFailClosedWithoutRegeneration() throws Exception {
        assertTrue(AdtCredentialStore.save(context, USERNAME, PASSWORD));
        keys.key = null;
        unavailable(); assertFalse(AdtCredentialStore.save(context, USERNAME, PASSWORD));
        assertEquals(1, keys.created);
        keys.key = makeKey();
        unavailable();
        keys.fail = true;
        unavailable(); assertFalse(AdtCredentialStore.save(context, USERNAME, PASSWORD));
        assertEquals(1, keys.created);
    }

    @Test public void versionIsOnlyMetadataAndNeverTouchesTheKeyBackend() {
        assertTrue(AdtCredentialStore.save(context, USERNAME, PASSWORD));
        String version = AdtCredentialStore.version(context);
        int lookups = keys.lookups;
        keys.fail = true;
        assertEquals(version, AdtCredentialStore.version(context));
        assertEquals("Cookie generation checks must not decrypt or look up a key", lookups, keys.lookups);
        assertFalse("A generation alone does not claim the credentials are usable", AdtCredentialStore.configured(context));
    }

    @Test public void aFailedCommitDoesNotExposeTheNewInMemoryRecord() {
        assertTrue(AdtCredentialStore.save(context, USERNAME, PASSWORD));
        Context failing = new ContextWrapper(context) {
            @Override public Context getApplicationContext() { return this; }
            @Override public SharedPreferences getSharedPreferences(String name, int mode) {
                return (SharedPreferences) Proxy.newProxyInstance(SharedPreferences.class.getClassLoader(),
                    new Class<?>[]{SharedPreferences.class}, (proxy, method, args) -> {
                        if (method.getName().equals("edit")) {
                            SharedPreferences.Editor editor = preferences.edit();
                            return Proxy.newProxyInstance(SharedPreferences.Editor.class.getClassLoader(),
                                new Class<?>[]{SharedPreferences.Editor.class}, (editorProxy, operation, arguments) -> {
                                    try {
                                        Object result = operation.invoke(editor, arguments);
                                        // Model commit(false) even though the in-memory record already changed.
                                        if (operation.getName().equals("commit")) return false;
                                        return result == editor ? editorProxy : result;
                                    } catch (InvocationTargetException error) { throw error.getCause(); }
                                });
                        }
                        try { return method.invoke(preferences, args); }
                        catch (InvocationTargetException error) { throw error.getCause(); }
                    });
            }
        };
        assertFalse(AdtCredentialStore.save(failing, "replacement@example.invalid", new char[]{'n', 'e', 'w'}));
        unavailable(); assertEquals("", AdtCredentialStore.version(context));
        AdtCredentialStore.clear(context);
        assertTrue(AdtCredentialStore.save(context, USERNAME, PASSWORD));
        assertTrue(AdtCredentialStore.configured(context));
    }

    @Test public void clearStillRemovesCiphertextWhenTheKeyBackendCannotDelete() {
        assertTrue(AdtCredentialStore.save(context, USERNAME, PASSWORD));
        keys.fail = true;
        AdtCredentialStore.clear(context);
        assertTrue(preferences.getAll().isEmpty());
        unavailable(); assertEquals("", AdtCredentialStore.version(context));
        keys.fail = false;
        assertTrue(AdtCredentialStore.save(context, USERNAME, PASSWORD));
        assertTrue(AdtCredentialStore.configured(context));
    }

    @Test public void invalidInputsDoNotChangeAnExistingGeneration() {
        assertTrue(AdtCredentialStore.save(context, USERNAME, PASSWORD));
        String version = AdtCredentialStore.version(context);
        for (String username : new String[]{null, "", "  ", "line\nfeed", repeat('u', AdtCredentialStore.MAX_USERNAME_CHARS + 1), "\ud800"})
            assertFalse(AdtCredentialStore.save(context, username, PASSWORD));
        assertFalse(AdtCredentialStore.save(context, USERNAME, null));
        assertFalse(AdtCredentialStore.save(context, USERNAME, new char[0]));
        assertFalse(AdtCredentialStore.save(context, USERNAME, new char[AdtCredentialStore.MAX_PASSWORD_CHARS + 1]));
        assertFalse(AdtCredentialStore.save(context, USERNAME, new char[]{'\ud800'}));
        assertEquals(version, AdtCredentialStore.version(context));
    }

    @Test public void malformedEnvelopeAndAuthenticatedInvalidLengthsAreRejected() throws Exception {
        assertTrue(AdtCredentialStore.save(context, USERNAME, PASSWORD));
        Map<String, ?> good = preferences.getAll();
        preferences.edit().putString("blob", "not-base64").commit(); unavailable(); restoreEnvelope(good);
        preferences.edit().putInt("version", 1).commit(); unavailable(); restoreEnvelope(good);
        preferences.edit().putString("iv", "").commit(); unavailable(); restoreEnvelope(good);
        // Correctly authenticated bytes with an impossible username length must still fail parsing.
        byte[] malformed = ByteBuffer.allocate(16).putInt(1).putInt(Integer.MAX_VALUE).putInt(1).putInt(0).array();
        String version = UUID.randomUUID().toString();
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE, keys.key);
        cipher.updateAAD(("ADT-CREDENTIALS/1:" + version).getBytes(StandardCharsets.US_ASCII));
        preferences.edit().clear().putString("version", version)
            .putString("iv", Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP))
            .putString("blob", Base64.encodeToString(cipher.doFinal(malformed), Base64.NO_WRAP)).commit();
        unavailable();
    }

    private void unavailable() {
        assertNull(AdtCredentialStore.load(context)); assertFalse(AdtCredentialStore.configured(context));
    }
    private void restoreEnvelope(Map<String, ?> values) {
        SharedPreferences.Editor edit = preferences.edit().clear();
        for (Map.Entry<String, ?> value : values.entrySet()) edit.putString(value.getKey(), (String) value.getValue());
        assertTrue(edit.commit());
    }
    private static String repeat(char value, int length) { char[] chars = new char[length]; Arrays.fill(chars, value); return new String(chars); }
    private static SecretKey makeKey() throws GeneralSecurityException {
        KeyGenerator generator = KeyGenerator.getInstance("AES"); generator.init(256); return generator.generateKey();
    }
    private static final class Keys implements AdtCredentialStore.KeySource {
        SecretKey key; int created, lookups; boolean fail;
        @Override public SecretKey existing() throws GeneralSecurityException {
            lookups++;
            if (fail) throw new GeneralSecurityException("Inert invalidated key"); return key;
        }
        @Override public SecretKey create() throws GeneralSecurityException {
            if (fail || key != null) throw new GeneralSecurityException("Inert unavailable key");
            created++; return key = makeKey();
        }
        @Override public void delete() throws GeneralSecurityException {
            if (fail) throw new GeneralSecurityException("Inert unavailable key"); key = null;
        }
    }
}
