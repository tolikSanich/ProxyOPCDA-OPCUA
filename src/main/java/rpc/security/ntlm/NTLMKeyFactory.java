package rpc.security.ntlm;

import java.io.UnsupportedEncodingException;
import java.security.DigestException;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Random;

import org.bouncycastle.crypto.StreamCipher;
import org.bouncycastle.crypto.digests.MD4Digest;
import org.bouncycastle.crypto.digests.MD5Digest;
import org.bouncycastle.crypto.engines.RC4Engine;
import org.bouncycastle.crypto.params.KeyParameter;

/**
 * NTLMKeyFactory — фабрика ключей для протокола NTLM.
 * Переписанный класс из проекта Jarapac (http://jarapac.sourceforge.net/).
 * Оригинальный код распространялся под лицензией GNU LGPL.
 */
public class NTLMKeyFactory {

    private final Random random = new Random();

    // Магические константы для генерации ключей подписи и шифрования
    private static final byte[] clientSigningMagicConstant = new byte[] {
            0x73, 0x65, 0x73, 0x73, 0x69, 0x6f, 0x6e, 0x20, 0x6b, 0x65,
            0x79, 0x20, 0x74, 0x6f, 0x20, 0x63, 0x6c, 0x69, 0x65, 0x6e,
            0x74, 0x2d, 0x74, 0x6f, 0x2d, 0x73, 0x65, 0x72, 0x76, 0x65,
            0x72, 0x20, 0x73, 0x69, 0x67, 0x6e, 0x69, 0x6e, 0x67, 0x20,
            0x6b, 0x65, 0x79, 0x20, 0x6d, 0x61, 0x67, 0x69, 0x63, 0x20,
            0x63, 0x6f, 0x6e, 0x73, 0x74, 0x61, 0x6e, 0x74, 0x00
    }; // "session key to client-to-server signing key magic constant"

    private static final byte[] serverSigningMagicConstant = new byte[] {
            0x73, 0x65, 0x73, 0x73, 0x69, 0x6f, 0x6e, 0x20, 0x6b, 0x65,
            0x79, 0x20, 0x74, 0x6f, 0x20, 0x73, 0x65, 0x72, 0x76, 0x65,
            0x72, 0x2d, 0x74, 0x6f, 0x2d, 0x63, 0x6c, 0x69, 0x65, 0x6e,
            0x74, 0x20, 0x73, 0x69, 0x67, 0x6e, 0x69, 0x6e, 0x67, 0x20,
            0x6b, 0x65, 0x79, 0x20, 0x6d, 0x61, 0x67, 0x69, 0x63, 0x20,
            0x63, 0x6f, 0x6e, 0x73, 0x74, 0x61, 0x6e, 0x74, 0x00
    }; // "session key to server-to-client signing key magic constant"

    private static final byte[] clientSealingMagicConstant = new byte[] {
            0x73, 0x65, 0x73, 0x73, 0x69, 0x6f, 0x6e, 0x20, 0x6b, 0x65,
            0x79, 0x20, 0x74, 0x6f, 0x20, 0x63, 0x6c, 0x69, 0x65, 0x6e,
            0x74, 0x2d, 0x74, 0x6f, 0x2d, 0x73, 0x65, 0x72, 0x76, 0x65,
            0x72, 0x20, 0x73, 0x65, 0x61, 0x6c, 0x69, 0x6e, 0x67, 0x20,
            0x6b, 0x65, 0x79, 0x20, 0x6d, 0x61, 0x67, 0x69, 0x63, 0x20,
            0x63, 0x6f, 0x6e, 0x73, 0x74, 0x61, 0x6e, 0x74, 0x00
    }; // "session key to client-to-server sealing key magic constant"

    private static final byte[] serverSealingMagicConstant = new byte[] {
            0x73, 0x65, 0x73, 0x73, 0x69, 0x6f, 0x6e, 0x20, 0x6b, 0x65,
            0x79, 0x20, 0x74, 0x6f, 0x20, 0x73, 0x65, 0x72, 0x76, 0x65,
            0x72, 0x2d, 0x74, 0x6f, 0x2d, 0x63, 0x6c, 0x69, 0x65, 0x6e,
            0x74, 0x20, 0x73, 0x65, 0x61, 0x6c, 0x69, 0x6e, 0x67, 0x20,
            0x6b, 0x65, 0x79, 0x20, 0x6d, 0x61, 0x67, 0x69, 0x63, 0x20,
            0x63, 0x6f, 0x6e, 0x73, 0x74, 0x61, 0x6e, 0x74, 0x00
    }; // "session key to server-to-client sealing key magic constant"

    NTLMKeyFactory() {
        // Пустой конструктор по умолчанию
    }

    /**
     * NTLMv1 User Session Key. Случаи, когда LMcompatibilitylevel = 0,1,2.
     * Для 3,4,5 логика отличается и зависит от типа ответа (LMv2 или NTLMv2).
     */
    byte[] getNTLMUserSessionKey(final String password)
            throws UnsupportedEncodingException, DigestException {
        final byte[] ntlmHash = Responses.ntlmHash(password);
        return digestMD4(ntlmHash);
    }

    /**
     * NTLMv2 User Session Key.
     */
    byte[] getNTLMv2UserSessionKey(final String target, final String user,
                                   final String password, final byte[] challenge,
                                   final byte[] blob) throws Exception {
        final byte[] ntlm2Hash = Responses.ntlmv2Hash(target, user, password);
        final byte[] data = new byte[challenge.length + blob.length];
        System.arraycopy(challenge, 0, data, 0, challenge.length);
        System.arraycopy(blob, 0, data, challenge.length, blob.length);
        final byte[] mac = Responses.hmacMD5(data, ntlm2Hash);
        return Responses.hmacMD5(mac, ntlm2Hash);
    }

    /**
     * NTLM2 Session Response User Session Key.
     *
     * @param password    пароль пользователя
     * @param servernonce challenge + nonce из NTLM2 Session Response
     */
    byte[] getNTLM2SessionResponseUserSessionKey(final String password,
                                                 final byte[] servernonce)
            throws NoSuchAlgorithmException, UnsupportedEncodingException,
            DigestException {
        return Responses.hmacMD5(servernonce, getNTLMUserSessionKey(password));
    }

    /**
     * Случайно сгенерированный 16-байтовый вторичный сессионный ключ.
     */
    byte[] getSecondarySessionKey() {
        final byte[] key = new byte[16];
        this.random.nextBytes(key);
        return key;
    }

    /**
     * Создаёт и инициализирует RC4-шифр с заданным ключом.
     */
    StreamCipher getRC4(final byte[] key) {
        final RC4Engine rc4 = new RC4Engine();
        rc4.init(true, new KeyParameter(key));
        return rc4;
    }

    /**
     * Применяет RC4-шифр к данным (шифрование/дешифрование).
     * * Исправленный метод для работы с BC 1.78.1
     */

    byte[] applyRC4(final StreamCipher streamCipher, final byte[] data) {
        final byte[] retData = new byte[data.length];

        // В BC 1.78.1 processBytes возвращает int.
        // Мы вызываем его и игнорируем результат, чтобы не было ошибки.
        streamCipher.processBytes(data, 0, data.length, retData, 0);

        return retData;
    }

    private void safeProcessBytes(StreamCipher cipher, byte[] in, int inOff, int len, byte[] out, int outOff) {
        try {
            // Пробуем вызвать метод через рефлексию, если сигнатуры различаются
            cipher.processBytes(in, inOff, len, out, outOff);
        } catch (NoSuchMethodError e) {
            // Это сработает, если сигнатура ожидает возвращаемое значение,
            // но мы его игнорируем
        }
    }

    /**
     * Дешифрование вторичного сессионного ключа с помощью RC4.
     */
    byte[] decryptSecondarySessionKey(final byte[] encryptedData, final byte[] key)
            throws IllegalStateException {
        return applyRC4(getRC4(key), encryptedData);
    }

    /**
     * Шифрование вторичного сессионного ключа с помощью RC4.
     */
    byte[] encryptSecondarySessionKey(final byte[] plainData, final byte[] key)
            throws IllegalStateException {
        return applyRC4(getRC4(key), plainData);
    }

    /**
     * Генерация клиентского ключа подписи на основе согласованного вторичного сессионного ключа.
     */
    byte[] generateClientSigningKeyUsingNegotiatedSecondarySessionKey(
            final byte[] secondarySessionKey) {
        final byte[] dataforhash = new byte[secondarySessionKey.length
                + clientSigningMagicConstant.length];
        System.arraycopy(secondarySessionKey, 0, dataforhash, 0,
                secondarySessionKey.length);
        System.arraycopy(clientSigningMagicConstant, 0, dataforhash,
                secondarySessionKey.length, clientSigningMagicConstant.length);
        return digestMD5(dataforhash);
    }

    /**
     * Генерация клиентского ключа шифрования (sealing) на основе согласованного
     * вторичного сессионного ключа.
     */
    byte[] generateClientSealingKeyUsingNegotiatedSecondarySessionKey(
            final byte[] secondarySessionKey) {
        final byte[] dataforhash = new byte[secondarySessionKey.length
                + clientSealingMagicConstant.length];
        System.arraycopy(secondarySessionKey, 0, dataforhash, 0,
                secondarySessionKey.length);
        System.arraycopy(clientSealingMagicConstant, 0, dataforhash,
                secondarySessionKey.length, clientSealingMagicConstant.length);
        return digestMD5(dataforhash);
    }

    /**
     * Генерация серверного ключа подписи на основе согласованного вторичного сессионного ключа.
     */
    byte[] generateServerSigningKeyUsingNegotiatedSecondarySessionKey(
            final byte[] secondarySessionKey) {
        final byte[] dataforhash = new byte[secondarySessionKey.length
                + serverSigningMagicConstant.length];
        System.arraycopy(secondarySessionKey, 0, dataforhash, 0,
                secondarySessionKey.length);
        System.arraycopy(serverSigningMagicConstant, 0, dataforhash,
                secondarySessionKey.length, serverSigningMagicConstant.length);
        return digestMD5(dataforhash);
    }

    /**
     * Вычисляет MD5-дайджест.
     */
    public static byte[] digestMD5(final byte[] dataforhash) {
        final MD5Digest md5 = new MD5Digest();
        md5.update(dataforhash, 0, dataforhash.length);
        final byte[] digest = new byte[md5.getDigestSize()];
        md5.doFinal(digest, 0);
        return digest;
    }

    /**
     * Вычисляет MD4-дайджест.
     */
    public static byte[] digestMD4(final byte[] dataforhash) {
        final MD4Digest md4 = new MD4Digest();
        md4.update(dataforhash, 0, dataforhash.length);
        final byte[] digest = new byte[md4.getDigestSize()];
        md4.doFinal(digest, 0);
        return digest;
    }

    /**
     * Генерация серверного ключа шифрования (sealing) на основе согласованного
     * вторичного сессионного ключа.
     */
    byte[] generateServerSealingKeyUsingNegotiatedSecondarySessionKey(
            final byte[] secondarySessionKey) {
        final byte[] dataforhash = new byte[secondarySessionKey.length
                + serverSealingMagicConstant.length];
        System.arraycopy(secondarySessionKey, 0, dataforhash, 0,
                secondarySessionKey.length);
        System.arraycopy(serverSealingMagicConstant, 0, dataforhash,
                secondarySessionKey.length, serverSealingMagicConstant.length);
        return digestMD5(dataforhash);
    }

    /**
     * Первая часть формирования подписи: вычисление HMAC-MD5 и сборка
     * 16-байтового верификатора.
     */
    byte[] signingPt1(final int sequenceNumber, final byte[] signingKey,
                      final byte[] data, final int lengthOfBuffer)
            throws NoSuchAlgorithmException, IllegalStateException {
        final byte[] seqNumPlusData = new byte[4 + lengthOfBuffer];

        seqNumPlusData[0] = (byte) (sequenceNumber & 0xFF);
        seqNumPlusData[1] = (byte) ((sequenceNumber >> 8) & 0xFF);
        seqNumPlusData[2] = (byte) ((sequenceNumber >> 16) & 0xFF);
        seqNumPlusData[3] = (byte) ((sequenceNumber >> 24) & 0xFF);

        System.arraycopy(data, 0, seqNumPlusData, 4, lengthOfBuffer);

        final byte[] retval = new byte[16];
        retval[0] = 0x01; // Версия 1 (LE)

        final byte[] sign = Responses.hmacMD5(seqNumPlusData, signingKey);

        for (int i = 0; i < 8; i++) {
            retval[i + 4] = sign[i];
        }

        retval[12] = (byte) (sequenceNumber & 0xFF);
        retval[13] = (byte) ((sequenceNumber >> 8) & 0xFF);
        retval[14] = (byte) ((sequenceNumber >> 16) & 0xFF);
        retval[15] = (byte) ((sequenceNumber >> 24) & 0xFF);

        return retval;
    }

    /**
     * Вторая часть формирования подписи: наложение RC4-шифра на первые
     * 8 байт верификатора.
     * Исправленный метод для работы с BC 1.78.1
     */
    /**

     */
    void signingPt2(final byte[] verifier, final StreamCipher rc4)
            throws IllegalStateException {
        for (int i = 0; i < 8; i++) {
            // В современных BC StreamCipher имеет метод returnByte,
            // который возвращает byte. Убедитесь, что он используется.
            verifier[i + 4] = rc4.returnByte(verifier[i + 4]);
        }
    }

    /**
     * Сравнивает две подписи.
     */
    boolean compareSignature(final byte[] src, final byte[] target) {
        return Arrays.equals(src, target);
    }
}