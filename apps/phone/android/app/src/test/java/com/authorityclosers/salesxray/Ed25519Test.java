package com.authorityclosers.salesxray;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.util.Arrays;
import java.util.Random;
import org.junit.Test;

/** The app's own Ed25519 check (live web updates): RFC 8032's vectors, and agreement with the JDK's Ed25519. */
public class Ed25519Test {

    /** RFC 8032 section 7.1, tests 1-3: public key, message, signature. */
    private static final String[][] RFC_8032 = {
        {
            "d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a",
            "",
            "e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e065224901555fb8821590a33bacc61e39701cf9b46bd25bf5f0595bbe24655141438e7a100b",
        },
        {
            "3d4017c3e843895a92b70aa74d1b7ebc9c982ccf2ec4968cc0cd55f12af4660c",
            "72",
            "92a009a9f0d4cab8720e820b5f642540a2b27b5416503f8fb3762223ebdb69da085ac1e43e15996e458f3613d0f11d8c387b2eaeb4302aeeb00d291612bb0c00",
        },
        {
            "fc51cd8e6218a1a38da47ed00230f0580816ed13ba3303ac5deb911548908025",
            "af82",
            "6291d657deec24024827e69c3abe01a30ce548a284743a445e3680d7db5ac3ac18ff9b538d16f290ae67f760984dc6594a7c15e9716ed28dc027beceea1ec40a",
        },
    };

    @Test
    public void rfc8032VectorsVerify() {
        for (String[] vector : RFC_8032) {
            assertTrue(vector[0], Ed25519.verify(LiveWebRules.hex(vector[0]), LiveWebRules.hex(vector[1]), LiveWebRules.hex(vector[2])));
        }
    }

    @Test
    public void anyChangedBitIsRefused() {
        String[] vector = RFC_8032[2];
        byte[] key = LiveWebRules.hex(vector[0]);
        byte[] message = LiveWebRules.hex(vector[1]);
        byte[] signature = LiveWebRules.hex(vector[2]);
        for (int bit = 0; bit < signature.length * 8; bit += 7) {
            byte[] changed = signature.clone();
            changed[bit / 8] ^= (byte) (1 << (bit % 8));
            assertFalse("signature bit " + bit, Ed25519.verify(key, message, changed));
        }
        for (int bit = 0; bit < message.length * 8; bit++) {
            byte[] changed = message.clone();
            changed[bit / 8] ^= (byte) (1 << (bit % 8));
            assertFalse("message bit " + bit, Ed25519.verify(key, changed, signature));
        }
        byte[] otherKey = LiveWebRules.hex(RFC_8032[1][0]);
        assertFalse(Ed25519.verify(otherKey, message, signature));
        assertFalse(Ed25519.verify(key, message, Arrays.copyOf(signature, 63)));
        assertFalse(Ed25519.verify(Arrays.copyOf(key, 31), message, signature));
        assertFalse(Ed25519.verify(null, message, signature));
    }

    @Test
    public void aScalarAtOrAboveTheGroupOrderIsRefused() {
        // S + L verifies under TweetNaCl's own maths; RFC 8032 (and the JDK) refuse it, and so does the app.
        String[] vector = RFC_8032[0];
        byte[] signature = LiveWebRules.hex(vector[2]);
        int[] order = { 0xed, 0xd3, 0xf5, 0x5c, 0x1a, 0x63, 0x12, 0x58, 0xd6, 0x9c, 0xf7, 0xa2, 0xde, 0xf9, 0xde, 0x14, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0x10 };
        int carry = 0;
        for (int i = 0; i < 32; i++) {
            int sum = (signature[32 + i] & 0xff) + order[i] + carry;
            signature[32 + i] = (byte) sum;
            carry = sum >> 8;
        }
        assertEquals(0, carry);
        assertFalse(Ed25519.verify(LiveWebRules.hex(vector[0]), LiveWebRules.hex(vector[1]), signature));
    }

    @Test
    public void agreesWithTheJdkOnRandomKeysAndMessages() throws Exception {
        Random random = new Random(20261028);
        KeyPairGenerator generator = KeyPairGenerator.getInstance("Ed25519");
        for (int round = 0; round < 40; round++) {
            KeyPair pair = generator.generateKeyPair();
            byte[] message = new byte[random.nextInt(600)];
            random.nextBytes(message);
            Signature signer = Signature.getInstance("Ed25519");
            signer.initSign(pair.getPrivate());
            signer.update(message);
            byte[] signature = signer.sign();
            byte[] key = rawPublicKey(pair);
            assertTrue("round " + round, Ed25519.verify(key, message, signature));
            if (message.length > 0) {
                byte[] changed = message.clone();
                changed[random.nextInt(changed.length)] ^= 1;
                assertFalse("round " + round + " changed message", Ed25519.verify(key, changed, signature));
            }
            byte[] changed = signature.clone();
            changed[random.nextInt(64)] ^= (byte) (1 << random.nextInt(8));
            assertFalse("round " + round + " changed signature", Ed25519.verify(key, message, changed));
        }
    }

    /** The 32-byte key at the end of the JDK's X.509 encoding (302a300506032b6570032100 + key). */
    static byte[] rawPublicKey(KeyPair pair) {
        byte[] encoded = pair.getPublic().getEncoded();
        assertEquals(44, encoded.length);
        return Arrays.copyOfRange(encoded, 12, 44);
    }
}
